package dev.turkce.olcum

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * The layout of an encrypted file: a 3-byte mark and a version byte, the 12-byte IV, then the AES-GCM ciphertext
 * with its 16-byte tag. Pure functions over bytes and a key, so the tests run them with a key made in memory.
 */
internal object SealedFormat {
    private val MARK = byteArrayOf('T'.code.toByte(), 'T'.code.toByte(), 'V'.code.toByte())
    const val VERSION: Byte = 1
    const val IV_SIZE = 12
    private const val TAG_BITS = 128
    private const val HEADER = 4
    private const val TRANSFORM = "AES/GCM/NoPadding"

    /** True for bytes in this layout; anything else is a file written before encryption, kept as plain bytes. */
    fun isSealed(bytes: ByteArray): Boolean =
        bytes.size >= HEADER + IV_SIZE + TAG_BITS / 8 &&
            (0 until MARK.size).all { bytes[it] == MARK[it] } && bytes[MARK.size] == VERSION

    /** [plain] encrypted with [key] under a fresh IV the cipher picks, in this layout. */
    fun seal(plain: ByteArray, key: SecretKey): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val body = cipher.doFinal(plain)
        val iv = cipher.iv
        check(iv != null && iv.size == IV_SIZE) { "Beklenmeyen IV" }
        return MARK + byteArrayOf(VERSION) + iv + body
    }

    /** The plain bytes, or null when [bytes] is not in this layout, was changed, or needs another key. */
    fun open(bytes: ByteArray, key: SecretKey): ByteArray? {
        if (!isSealed(bytes)) return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, HEADER, IV_SIZE))
            cipher.doFinal(bytes, HEADER + IV_SIZE, bytes.size - HEADER - IV_SIZE)
        }.getOrNull()
    }
}

/**
 * A secret file on disk, with the key handed in so the tests run it with a key made in memory. A write goes to a
 * temporary file that is read back and decrypted before it replaces the old file in one step, so the old file (a
 * plain one from before encryption included) is gone only once the encrypted one is known good.
 */
internal object SealedFile {
    fun write(file: File, data: ByteArray, key: SecretKey) {
        val sealed = SealedFormat.seal(data, key)
        val temporary = File(file.parentFile, file.name + ".yeni")
        try {
            FileOutputStream(temporary).use {
                it.write(sealed)
                it.fd.sync()
            }
            check(SealedFormat.open(temporary.readBytes(), key)?.contentEquals(data) == true) { "Kayıt dosyası doğrulanamadı." }
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: Exception) {
            temporary.delete()
            throw error
        }
    }

    /**
     * The file's plain bytes; null when it is missing or cannot be decrypted. A file from before encryption is
     * returned as it is and encrypted in place; when that fails, [migrationFailed] hears why and the plain file stays
     * for the next read to try again.
     */
    fun read(file: File, key: () -> SecretKey, migrationFailed: (Exception) -> Unit): ByteArray? {
        val bytes = runCatching { file.takeIf { it.isFile }?.readBytes() }.getOrNull() ?: return null
        if (!SealedFormat.isSealed(bytes)) {
            try {
                write(file, bytes, key())
            } catch (error: Exception) {
                migrationFailed(error)
            }
            return bytes
        }
        val opened = runCatching { key() }.getOrNull() ?: return null
        return SealedFormat.open(bytes, opened)
    }
}

/**
 * Reads and writes the app's secret files (the PS5 pairing and the PSN account) encrypted with an AES-256 key that
 * lives in Android Keystore and never leaves it. The key needs no screen lock, so the TV can use it at any time.
 * A file from before encryption is read as it is and rewritten encrypted on that first read; when that fails the
 * pairing keeps working from the plain file, [migrationPending] says so to the phone page, and the next read retries.
 */
internal object SecretFiles {
    private const val STORE = "AndroidKeyStore"
    private const val KEY_ALIAS = "turkcetv.gizli.dosya"
    private const val KEY_BITS = 256

    /** Names of the files still in plain bytes because encrypting them failed. */
    private val pending = HashSet<String>()

    /** True while a secret file could not be encrypted yet and still sits on disk in plain bytes. */
    val migrationPending: Boolean
        @Synchronized get() = pending.isNotEmpty()

    /** The file's plain bytes; null when it is missing or cannot be decrypted, which callers take as "not paired". */
    @Synchronized
    fun read(file: File): ByteArray? {
        var failed = false
        val bytes = SealedFile.read(file, ::key) { error ->
            failed = true
            if (BuildConfig.DEBUG) Log.w("TurkceTv", "gizli dosya sifrelenemedi (${file.name}), sonraki okumada yeniden denenecek: $error")
        }
        if (failed) pending += file.name else pending -= file.name
        return bytes
    }

    /** Encrypts [data] into [file], through a temporary file so a crash never leaves half a file. */
    @Synchronized
    fun write(file: File, data: ByteArray) {
        SealedFile.write(file, data, key())
        pending -= file.name
    }

    fun readText(file: File): String? = read(file)?.toString(Charsets.UTF_8)

    fun writeText(file: File, text: String) = write(file, text.toByteArray(Charsets.UTF_8))

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance(STORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, STORE)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_BITS)
                .setRandomizedEncryptionRequired(true)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }
}
