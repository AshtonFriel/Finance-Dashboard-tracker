package com.financedashboard.app.data.db

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.io.File
import java.security.SecureRandom

/**
 * Database encryption support: a random SQLCipher passphrase kept in
 * Keystore-backed encrypted preferences, plus a one-time migration of any
 * pre-existing plaintext database. Every step fails safe: if anything goes
 * wrong the app keeps using the plaintext database rather than losing data.
 */
object DbCrypto {

    private const val PREFS = "db-crypto"
    private const val KEY = "db-passphrase-hex"

    /**
     * A 64-char hex string used as the SQLCipher passphrase (text, so the same
     * key derivation applies in Room's factory and in the migration ATTACH).
     */
    fun getOrCreatePassphrase(context: Context): String? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        val prefs = EncryptedSharedPreferences.create(
            context, PREFS, masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
        prefs.getString(KEY, null) ?: run {
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val generated = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(KEY, generated).apply()
            generated
        }
    } catch (_: Exception) {
        null // Keystore unavailable — run unencrypted rather than crash.
    }

    /** True if the file begins with the plaintext SQLite magic header. */
    fun isPlaintextDb(file: File): Boolean {
        if (!file.exists() || file.length() < 16) return false
        val header = ByteArray(16)
        file.inputStream().use { it.read(header) }
        return header.toString(Charsets.ISO_8859_1).startsWith("SQLite format 3")
    }

    /**
     * Migrates a plaintext database in place to SQLCipher encryption using
     * sqlcipher_export. Returns true when the database at [dbFile] ends up
     * encrypted; on any failure the original plaintext file is left untouched.
     */
    fun migratePlaintext(context: Context, dbFile: File, passphrase: String): Boolean {
        val encrypted = File(dbFile.parent, dbFile.name + ".enc")
        return try {
            System.loadLibrary("sqlcipher")
            encrypted.delete()
            val plain = net.zetetic.database.sqlcipher.SQLiteDatabase.openDatabase(
                dbFile.absolutePath, "", null,
                net.zetetic.database.sqlcipher.SQLiteDatabase.OPEN_READWRITE, null,
            )
            plain.use { db ->
                db.rawExecSQL("ATTACH DATABASE '${encrypted.absolutePath}' AS encrypted KEY '$passphrase';")
                db.rawExecSQL("SELECT sqlcipher_export('encrypted');")
                db.rawExecSQL("DETACH DATABASE encrypted;")
            }
            // Swap files only after a successful export.
            val backup = File(dbFile.parent, dbFile.name + ".plain-bak")
            if (dbFile.renameTo(backup) && encrypted.renameTo(dbFile)) {
                backup.delete()
                // Sidecar files from the plaintext db are stale now.
                File(dbFile.parent, dbFile.name + "-wal").delete()
                File(dbFile.parent, dbFile.name + "-shm").delete()
                true
            } else {
                backup.renameTo(dbFile)
                encrypted.delete()
                false
            }
        } catch (_: Throwable) {
            encrypted.delete()
            false
        }
    }
}
