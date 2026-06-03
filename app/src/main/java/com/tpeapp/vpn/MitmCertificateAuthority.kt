package com.tpeapp.vpn

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
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.KeyStore
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
    )

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
        )
    }

    fun buildInstallIntent(ca: CaInfo): Intent {
        return KeyChain.createInstallIntent().apply {
            putExtra(KeyChain.EXTRA_CERTIFICATE, ca.certificateDer)
            putExtra(KeyChain.EXTRA_NAME, ca.alias)
        }
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
        if (Security.getProvider("BC") == null) {
            Security.addProvider(BouncyCastleProvider())
        }
    }
}
