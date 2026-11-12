package dev.turkce.olcum

import java.io.File
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GizliDosyaTest {
    /** A key made in memory stands in for the Keystore key; the file layout and the cipher are the same. */
    private val key = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val otherKey = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val pairing = ByteArray(40) { (it * 7).toByte() } + "192.168.1.20".toByteArray()

    @Test
    fun sealedBytesComeBackTheSame() {
        val sealed = SealedFormat.seal(pairing, key)
        assertTrue(SealedFormat.isSealed(sealed))
        assertEquals(SealedFormat.VERSION, sealed[3])
        assertArrayEquals(pairing, SealedFormat.open(sealed, key))
        assertArrayEquals(ByteArray(0), SealedFormat.open(SealedFormat.seal(ByteArray(0), key), key))
    }

    @Test
    fun everySealUsesANewIv() {
        val first = SealedFormat.seal(pairing, key)
        val second = SealedFormat.seal(pairing, key)
        assertFalse(first.copyOfRange(4, 4 + SealedFormat.IV_SIZE).contentEquals(second.copyOfRange(4, 4 + SealedFormat.IV_SIZE)))
        assertFalse("the keys must not show in the file", first.toList().windowed(16).contains(pairing.copyOfRange(0, 16).toList()))
    }

    @Test
    fun changedOrForeignDataGivesNothingInsteadOfFailing() {
        val sealed = SealedFormat.seal(pairing, key)
        for (index in listOf(5, 4 + SealedFormat.IV_SIZE, sealed.size - 1)) {
            val changed = sealed.copyOf().also { it[index] = (it[index] + 1).toByte() }
            assertNull("byte $index", SealedFormat.open(changed, key))
        }
        assertNull(SealedFormat.open(sealed, otherKey))
        assertNull(SealedFormat.open(sealed.copyOf(sealed.size - 5), key))
        assertNull(SealedFormat.open(sealed.copyOf(20), key))
    }

    @Test
    fun filesFromBeforeEncryptionAreToldApart() {
        assertFalse(SealedFormat.isSealed(pairing))
        assertFalse(SealedFormat.isSealed("AAECAwQFBgc=".toByteArray()))
        assertFalse(SealedFormat.isSealed(ByteArray(0)))
        assertNull(SealedFormat.open(pairing, key))
        val sealed = SealedFormat.seal(pairing, key)
        assertFalse("too short to be sealed", SealedFormat.isSealed(sealed.copyOf(4 + SealedFormat.IV_SIZE + 15)))
        assertFalse("another version", SealedFormat.isSealed(sealed.copyOf().also { it[3] = 2 }))
    }

    @get:Rule
    val folder = TemporaryFolder()

    private fun noTemporaryLeft() = assertTrue(folder.root.listFiles().orEmpty().none { it.name.endsWith(".yeni") })

    @Test
    fun aPlainFileIsEncryptedOnItsFirstRead() {
        val file = folder.newFile("ps5.kayit").apply { writeBytes(pairing) }
        val failures = mutableListOf<Exception>()
        assertArrayEquals(pairing, SealedFile.read(file, { key }, failures::add))
        assertTrue(failures.isEmpty())
        val onDisk = file.readBytes()
        assertTrue("the plain file is replaced by the encrypted one", SealedFormat.isSealed(onDisk))
        assertArrayEquals(pairing, SealedFormat.open(onDisk, key))
        assertArrayEquals(pairing, SealedFile.read(file, { key }, failures::add))
        noTemporaryLeft()
    }

    @Test
    fun aFailedMigrationKeepsThePairingAndTriesAgainOnTheNextRead() {
        val file = folder.newFile("ps5.kayit").apply { writeBytes(pairing) }
        val failures = mutableListOf<Exception>()
        val noKeystore: () -> SecretKey = { throw java.security.KeyStoreException("Keystore kapali") }
        assertArrayEquals("the pairing still works", pairing, SealedFile.read(file, noKeystore, failures::add))
        assertEquals("the failure is reported, not swallowed", 1, failures.size)
        assertArrayEquals("the plain file is untouched", pairing, file.readBytes())
        noTemporaryLeft()
        assertArrayEquals(pairing, SealedFile.read(file, { key }, failures::add))
        assertEquals(1, failures.size)
        assertArrayEquals(pairing, SealedFormat.open(file.readBytes(), key))
    }

    @Test
    fun aWriteReplacesTheOldFileOnlyWithAVerifiedOne() {
        val file = folder.newFile("psn.hesap").apply { writeBytes(pairing) }
        SealedFile.write(file, "AAECAwQFBgc=".toByteArray(), key)
        assertArrayEquals("AAECAwQFBgc=".toByteArray(), SealedFormat.open(file.readBytes(), key))
        SealedFile.write(file, pairing, key)
        assertArrayEquals(pairing, SealedFormat.open(file.readBytes(), key))
        noTemporaryLeft()
    }

    @Test
    fun aWriteThatCannotFinishLeavesTheOldFileAndNoTemporary() {
        val file = folder.newFile("ps5.kayit").apply { writeBytes(pairing) }
        // A folder where the temporary file should go makes the write fail before the old file is touched.
        File(folder.root, "ps5.kayit.yeni").mkdir()
        assertTrue(runCatching { SealedFile.write(file, "yeni".toByteArray(), key) }.isFailure)
        assertArrayEquals(pairing, file.readBytes())
    }

    @Test
    fun aMissingOrUnreadableFileReadsAsNothing() {
        val failures = mutableListOf<Exception>()
        assertNull(SealedFile.read(File(folder.root, "yok"), { key }, failures::add))
        val sealed = folder.newFile("ps5.kayit").apply { writeBytes(SealedFormat.seal(pairing, otherKey)) }
        assertNull(SealedFile.read(sealed, { key }, failures::add))
        assertTrue(failures.isEmpty())
    }
}
