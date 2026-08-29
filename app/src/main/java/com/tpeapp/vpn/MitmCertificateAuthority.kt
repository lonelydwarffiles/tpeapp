package com.hound.controller.vpn

import android.content.Context
import android.content.Intent
import android.security.KeyChain
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.asn1.x509.BasicConstraints
import org.bouncycastle.asn1.x509.Extension
import org.bouncycastle.asn1.x509.KeyUsage
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import java.math.BigInteger
import java.security.MessageDigest
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Security
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.Date

object MitmCertificateAuthority {

    private const val KEYSTORE_FILE = "mitm_ca.p12"
    private const val KEYSTORE_TYPE = "PKCS12"
    private const val KEY_ALIAS = "tpe_mitm_root"
    private const val KEY_PASSWORD = "tpe-mitm-ca"

    data class CaInfo(
        val alias: String,
        val generatedAtMs: Long,
        val certificateDer: ByteArray,
        val certificatePem: String,
        val privateKey: PrivateKey,
        val certificate: X509Certificate,
    )

    data class LeafTlsContext(
        val host: String,
        val serverContext: javax.net.ssl.SSLContext,
    )

    data class CaTrustStatus(
        val scope: String,
        val trustedInUserStore: Boolean,
        val trustedInSystemStore: Boolean,
    )

    data class SystemCaInjectionResult(
        val certHash: String,
        val filename: String,
        val systemStoreInjected: Boolean,
        val apexStoreInjected: Boolean,
        val overlayModuleGenerated: Boolean,
        val overlayModulePath: String?,
    ) {
        val anySuccess get() = systemStoreInjected || apexStoreInjected || overlayModuleGenerated
    }

    fun loadExisting(context: Context): CaInfo? {
        ensureProvider()
        val file = File(context.filesDir, KEYSTORE_FILE)
        if (!file.exists()) return null
        val ks = KeyStore.getInstance(KEYSTORE_TYPE)
        FileInputStream(file).use { ks.load(it, KEY_PASSWORD.toCharArray()) }
        val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate ?: return null
        val key = ks.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as? PrivateKey ?: return null
        return CaInfo(
            alias = KEY_ALIAS,
            generatedAtMs = cert.notBefore.time,
            certificateDer = cert.encoded,
            certificatePem = toPem(cert.encoded),
            privateKey = key,
            certificate = cert,
        )
    }

    fun queryTrustStatus(context: Context): CaTrustStatus {
        val existing = loadExisting(context) ?: return CaTrustStatus(
            scope = "missing",
            trustedInUserStore = false,
            trustedInSystemStore = false,
        )

        val targetFingerprint = sha256(existing.certificateDer)
        var userTrusted = false
        var systemTrusted = false

        runCatching {
            val store = KeyStore.getInstance("AndroidCAStore")
            store.load(null)
            val aliases = store.aliases()
            while (aliases.hasMoreElements()) {
                val alias = aliases.nextElement()
                val cert = store.getCertificate(alias) as? X509Certificate ?: continue
                if (sha256(cert.encoded) != targetFingerprint) continue
                if (alias.startsWith("system:")) systemTrusted = true
                if (alias.startsWith("user:")) userTrusted = true
            }
        }

        runCatching {
            val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
            val systemDir = File("/system/etc/security/cacerts")
            if (systemDir.isDirectory) {
                systemDir.listFiles()?.forEach { file ->
                    runCatching {
                        FileInputStream(file).use { fis ->
                            val cert = certFactory.generateCertificate(fis) as X509Certificate
                            if (sha256(cert.encoded) == targetFingerprint) {
                                systemTrusted = true
                            }
                        }
                    }
                }
            }
        }

        val scope = when {
            systemTrusted -> "system"
            userTrusted -> "user"
            else -> "untrusted"
        }
        return CaTrustStatus(
            scope = scope,
            trustedInUserStore = userTrusted,
            trustedInSystemStore = systemTrusted,
        )
    }

    fun ensure(context: Context): CaInfo {
        ensureProvider()
        val file = File(context.filesDir, KEYSTORE_FILE)
        val ks = KeyStore.getInstance(KEYSTORE_TYPE)

        if (file.exists()) {
            FileInputStream(file).use { ks.load(it, KEY_PASSWORD.toCharArray()) }
            val cert = ks.getCertificate(KEY_ALIAS) as? X509Certificate
            if (cert != null) {
                return CaInfo(
                    alias = KEY_ALIAS,
                    generatedAtMs = cert.notBefore.time,
                    certificateDer = cert.encoded,
                    certificatePem = toPem(cert.encoded),
                    privateKey = ks.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as PrivateKey,
                    certificate = cert,
                )
            }
        }

        ks.load(null, null)
        val keyPair = generateKeyPair()
        val cert = buildCaCertificate(keyPair)
        ks.setKeyEntry(KEY_ALIAS, keyPair.private, KEY_PASSWORD.toCharArray(), arrayOf(cert))
        FileOutputStream(file).use { ks.store(it, KEY_PASSWORD.toCharArray()) }

        return CaInfo(
            alias = KEY_ALIAS,
            generatedAtMs = cert.notBefore.time,
            certificateDer = cert.encoded,
            certificatePem = toPem(cert.encoded),
            privateKey = keyPair.private,
            certificate = cert,
        )
    }

    /**
     * Computes the OpenSSL "old" subject hash that Android uses as the
     * cacerts filename (e.g. `a1b2c3d4.0`).
     *
     * Algorithm: MD5 of the DER-encoded subject name; first 4 bytes
     * interpreted as a little-endian unsigned 32-bit integer.
     */
    fun computeAndroidCaCertHash(cert: X509Certificate): String {
        val derSubject = cert.subjectX500Principal.encoded
        val md5 = MessageDigest.getInstance("MD5").digest(derSubject)
        val hash = ((md5[0].toLong() and 0xFFL)) or
            ((md5[1].toLong() and 0xFFL) shl 8) or
            ((md5[2].toLong() and 0xFFL) shl 16) or
            ((md5[3].toLong() and 0xFFL) shl 24)
        return "%08x".format(hash and 0xFFFFFFFFL)
    }

    /**
     * Attempts to inject the MITM root CA into the Android system trust store
     * using root (`su`) access. Requires the device to be rooted.
     *
     * Writes to both `/system/etc/security/cacerts/` (legacy path) and
     * `/apex/com.android.conscrypt/cacerts/` (Android 14+ APEX path).
     * Returns [SystemCaInjectionResult] indicating which paths succeeded.
     *
     * Note: this modifies system partitions. Only call on a device you own
     * and control. A reboot or SELinux enforcement may undo the injection.
     */
    fun tryInjectSystemCa(context: Context, ca: CaInfo): SystemCaInjectionResult {
        val certHash = computeAndroidCaCertHash(ca.certificate)
        val filename = "$certHash.0"

        // Stage PEM to a path that su can read from.
        val tempPem = File(context.filesDir, "mitm_ca_inject_$certHash.pem")
        tempPem.writeText(ca.certificatePem)
        tempPem.setReadable(true, false)
        val srcPath = tempPem.absolutePath

        val systemDest = "/system/etc/security/cacerts/$filename"
        val apexDest = "/apex/com.android.conscrypt/cacerts/$filename"

        var systemSuccess = false
        var apexSuccess = false
        var overlayGenerated = false
        var overlayPath: String? = null

        runCatching {
            val script = """
                mount -o remount,rw /system 2>/dev/null || true
                cp "$srcPath" "$systemDest"
                chmod 644 "$systemDest"
                mount -o remount,ro /system 2>/dev/null || true
            """.trimIndent()
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc.waitFor(10L, TimeUnit.SECONDS)
            // Verify via su since /system may not be accessible to app process.
            val verify = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f \"$systemDest\" && echo ok"))
            verify.waitFor(5L, TimeUnit.SECONDS)
            systemSuccess = verify.inputStream.bufferedReader().readText().trim() == "ok"
        }.onFailure { Log.w(TAG, "System store CA injection failed", it) }

        runCatching {
            // Android 14+ places the live cert store inside the Conscrypt APEX.
            // The apex path is a tmpfs; copies survive until the next reboot unless
            // a persistent overlay is applied separately.
            val script = """
                cp "$srcPath" "$apexDest" 2>/dev/null
                chmod 644 "$apexDest" 2>/dev/null
            """.trimIndent()
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", script))
            proc.waitFor(10L, TimeUnit.SECONDS)
            val verify = Runtime.getRuntime().exec(arrayOf("su", "-c", "test -f \"$apexDest\" && echo ok"))
            verify.waitFor(5L, TimeUnit.SECONDS)
            apexSuccess = verify.inputStream.bufferedReader().readText().trim() == "ok"
        }.onFailure { Log.w(TAG, "APEX store CA injection failed (may not be available)", it) }

        if (!systemSuccess && !apexSuccess) {
            // Fallback for ROMs where /system and /apex are immutable at runtime:
            // generate a lightweight Magisk/APatch style module skeleton that the
            // user can place under /data/adb/modules for early-boot overlay mounting.
            runCatching {
                val moduleDir = File(context.filesDir, "modules/tpe_ca_overlay_$certHash")
                if (!moduleDir.exists()) moduleDir.mkdirs()

                val systemCacertsDir = File(moduleDir, "system/etc/security/cacerts")
                val apexCacertsDir = File(moduleDir, "system/apex/com.android.conscrypt/cacerts")
                systemCacertsDir.mkdirs()
                apexCacertsDir.mkdirs()

                val systemCert = File(systemCacertsDir, filename)
                val apexCert = File(apexCacertsDir, filename)
                systemCert.writeText(ca.certificatePem)
                apexCert.writeText(ca.certificatePem)

                File(moduleDir, "module.prop").writeText(
                    """
                    id=tpe-ca-overlay-$certHash
                    name=TPE CA Overlay ($certHash)
                    version=1.0
                    versionCode=1
                    author=TPE
                    description=Injects TPE MITM CA into system/conscrypt trust stores.
                    """.trimIndent() + "\n"
                )

                // post-fs-data.sh ensures permissions on boot-time overlay targets.
                File(moduleDir, "post-fs-data.sh").writeText(
                    """
                    #!/system/bin/sh
                    chmod 0644 /system/etc/security/cacerts/$filename 2>/dev/null
                    chmod 0644 /apex/com.android.conscrypt/cacerts/$filename 2>/dev/null
                    """.trimIndent() + "\n"
                )

                val customize = File(moduleDir, "customize.sh")
                customize.writeText(
                    """
                    #!/system/bin/sh
                    ui_print "TPE CA overlay module staged."
                    """.trimIndent() + "\n"
                )

                overlayGenerated = true
                overlayPath = moduleDir.absolutePath
            }.onFailure {
                Log.w(TAG, "Overlay module generation failed", it)
            }
        }

        Log.i(
            TAG,
            "CA injection: hash=$certHash system=$systemSuccess apex=$apexSuccess overlay=$overlayGenerated"
        )
        return SystemCaInjectionResult(
            certHash = certHash,
            filename = filename,
            systemStoreInjected = systemSuccess,
            apexStoreInjected = apexSuccess,
            overlayModuleGenerated = overlayGenerated,
            overlayModulePath = overlayPath,
        )
    }

    fun buildInstallIntent(ca: CaInfo): Intent {
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, ca.certificateDer)
            putExtra(KeyChain.EXTRA_NAME, ca.alias)
        }
    }

    fun createLeafTlsContext(context: Context, host: String): LeafTlsContext {
        val ca = ensure(context)
        val leafKeyPair = java.security.KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = java.util.Date()
        val notAfter = java.util.Date(now.time + 365L * 24L * 60L * 60L * 1000L)

        val certBuilder = org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder(
            org.bouncycastle.asn1.x500.X500Name(ca.certificate.subjectX500Principal.name),
            java.math.BigInteger.valueOf(System.currentTimeMillis()),
            now,
            notAfter,
            org.bouncycastle.asn1.x500.X500Name("CN=$host"),
            leafKeyPair.public,
        )

        val sanType = if (isIpAddress(host)) {
            org.bouncycastle.asn1.x509.GeneralName.iPAddress
        } else {
            org.bouncycastle.asn1.x509.GeneralName.dNSName
        }
        val san = org.bouncycastle.asn1.x509.GeneralNames(
            org.bouncycastle.asn1.x509.GeneralName(sanType, host)
        )
        certBuilder.addExtension(
            org.bouncycastle.asn1.x509.Extension.subjectAlternativeName,
            false,
            san,
        )
        certBuilder.addExtension(
            org.bouncycastle.asn1.x509.Extension.keyUsage,
            true,
            org.bouncycastle.asn1.x509.KeyUsage(
                org.bouncycastle.asn1.x509.KeyUsage.digitalSignature or
                    org.bouncycastle.asn1.x509.KeyUsage.keyEncipherment
            ),
        )
        certBuilder.addExtension(
            org.bouncycastle.asn1.x509.Extension.extendedKeyUsage,
            false,
            org.bouncycastle.asn1.x509.ExtendedKeyUsage(org.bouncycastle.asn1.x509.KeyPurposeId.id_kp_serverAuth),
        )

        val signer = org.bouncycastle.operator.jcajce.JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(ca.privateKey)
        val leafHolder = certBuilder.build(signer)
        val leafCert = org.bouncycastle.cert.jcajce.JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(leafHolder)

        val keyStore = java.security.KeyStore.getInstance("PKCS12")
        keyStore.load(null, null)
        val password = "tpe-mitm".toCharArray()
        keyStore.setKeyEntry(
            "leaf-$host",
            leafKeyPair.private,
            password,
            arrayOf(leafCert, ca.certificate),
        )

        val kmf = javax.net.ssl.KeyManagerFactory.getInstance(javax.net.ssl.KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)

        val serverContext = javax.net.ssl.SSLContext.getInstance("TLS")
        serverContext.init(kmf.keyManagers, null, java.security.SecureRandom())
        return LeafTlsContext(host = host, serverContext = serverContext)
    }

    fun buildUpstreamClientTlsContext(): javax.net.ssl.SSLContext {
        val trustAll = arrayOf<javax.net.ssl.TrustManager>(object : javax.net.ssl.X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })
        val context = javax.net.ssl.SSLContext.getInstance("TLS")
        context.init(null, trustAll, java.security.SecureRandom())
        return context
    }

    private fun generateKeyPair(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        return generator.generateKeyPair()
    }

    private fun buildCaCertificate(keyPair: KeyPair): X509Certificate {
        val now = System.currentTimeMillis()
        val notBefore = Date(now - 5 * 60_000L)
        val notAfter = Date(now + (3650L * 24L * 60L * 60L * 1000L))
        val issuer = X500Name("CN=TPE Local MITM Root,O=TPE,C=US")
        val serial = BigInteger(160, SecureRandom())

        val builder = JcaX509v3CertificateBuilder(
            issuer,
            serial,
            notBefore,
            notAfter,
            issuer,
            keyPair.public,
        )
        builder.addExtension(Extension.basicConstraints, true, BasicConstraints(true))
        builder.addExtension(
            Extension.keyUsage,
            true,
            KeyUsage(KeyUsage.keyCertSign or KeyUsage.cRLSign or KeyUsage.digitalSignature),
        )

        val signer = JcaContentSignerBuilder("SHA256withRSA")
            .setProvider("BC")
            .build(keyPair.private)
        val holder = builder.build(signer)
        return JcaX509CertificateConverter()
            .setProvider("BC")
            .getCertificate(holder)
    }

    private fun toPem(der: ByteArray): String {
        val base64 = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
        return buildString {
            append("-----BEGIN CERTIFICATE-----\n")
            append(base64)
            append("\n-----END CERTIFICATE-----\n")
        }
    }

    private fun ensureProvider() {
        // Android pre-registers a stripped-down BouncyCastle provider under "BC" that
        // lacks algorithms like SHA256withRSA. Remove the stub and insert the full
        // provider at position 1 (highest priority) to guarantee all algorithms work.
        val existing = Security.getProvider("BC")
        if (existing !is BouncyCastleProvider) {
            if (existing != null) {
                Security.removeProvider("BC")
            }
            Security.insertProviderAt(BouncyCastleProvider(), 1)
        }
    }

    private fun isIpAddress(host: String): Boolean {
        return runCatching { InetAddress.getByName(host) }
            .map { addr -> addr.hostAddress.equals(host, ignoreCase = true) }
            .getOrDefault(false)
    }

    private const val TAG = "MitmCertificateAuthority"

    private fun sha256(input: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input)
        return digest.joinToString("") { b -> "%02x".format(b) }
    }
}
