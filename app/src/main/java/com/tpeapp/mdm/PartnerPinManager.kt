package com.tpeapp.mdm

import android.content.Context
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Manages the Accountability Partner's PIN used to guard app-deactivation
 * and uninstall.
 *
 * The PIN is hashed with **PBKDF2WithHmacSHA256** using a random 16-byte salt
 * and stored (hash + salt) in [EncryptedSharedPreferences] backed by the
 * Android Keystore.  Using a random salt per PIN prevents rainbow-table
 * attacks even if the encrypted store is somehow extracted.
 */
class PartnerPinManager(context: Context) {

    companion object {
        private const val PREFS_FILE    = "partner_pin_prefs"
        private const val KEY_HASH      = "hashed_pin"
        private const val KEY_SALT      = "pin_salt"

        // PBKDF2 parameters — iterate enough to make offline attacks expensive
        // while remaining imperceptible (< 1 ms) on modern hardware.
        private const val PBKDF2_ITERATIONS = 120_000
        private const val PBKDF2_KEY_LENGTH = 256   // bits
    }

    private val prefs = EncryptedSharedPreferences.create(
        context,
        PREFS_FILE,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** Returns true if a PIN has already been configured. */
    fun isPinSet(): Boolean = prefs.contains(KEY_HASH)

    /**
     * Stores a new PIN.  A fresh random salt is generated and stored
     * alongside the PBKDF2 hash — never the raw PIN.
     */
    fun setPin(pin: String) {
        val salt      = generateSalt()
        val hashBytes = pbkdf2(pin, salt)
        prefs.edit()
            .putString(KEY_SALT, Base64.encodeToString(salt,      Base64.NO_WRAP))
            .putString(KEY_HASH, Base64.encodeToString(hashBytes, Base64.NO_WRAP))
            .apply()
    }

    /** Returns true if [candidate] matches the stored PIN. */
    fun verifyPin(candidate: String): Boolean {
        val storedHash = prefs.getString(KEY_HASH, null) ?: return false
        val storedSalt = prefs.getString(KEY_SALT, null) ?: return false
        val salt          = Base64.decode(storedSalt, Base64.NO_WRAP)
        val expectedBytes = Base64.decode(storedHash,  Base64.NO_WRAP)
        val candidateBytes = pbkdf2(candidate, salt)
        // Constant-time comparison to prevent timing attacks.
        return java.security.MessageDigest.isEqual(expectedBytes, candidateBytes)
    }

    /** Removes the stored PIN (called after voluntary deactivation). */
    fun clearPin() = prefs.edit().remove(KEY_HASH).remove(KEY_SALT).apply()

    // ------------------------------------------------------------------
    //  Internal
    // ------------------------------------------------------------------

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        java.security.SecureRandom().nextBytes(salt)
        return salt
    }

    private fun pbkdf2(pin: String, salt: ByteArray): ByteArray {
        val spec = PBEKeySpec(pin.toCharArray(), salt, PBKDF2_ITERATIONS, PBKDF2_KEY_LENGTH)
        return SecretKeyFactory
            .getInstance("PBKDF2WithHmacSHA256")
            .generateSecret(spec)
            .encoded
    }
}
