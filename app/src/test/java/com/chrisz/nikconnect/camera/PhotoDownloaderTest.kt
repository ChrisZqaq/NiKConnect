package com.chrisz.nikconnect.camera

import com.chrisz.nikconnect.ptp.PhotoObject
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PhotoDownloaderTest {
    @Test
    fun `partial file resumes within one connection but never across reconnect`() {
        val directory = File("cache/downloads")
        val photo = PhotoObject(7, 1, 0x3801, 8_500_000, "DSC_0007.JPG", "", 24_000)

        val firstAttempt = partialFile(directory, "connection-A", photo)
        val sameConnectionRetry = partialFile(directory, "connection-A", photo)
        val afterReconnect = partialFile(directory, "connection-B", photo)

        assertEquals(firstAttempt, sameConnectionRetry)
        assertNotEquals(firstAttempt, afterReconnect)
    }

    @Test
    fun `session cleanup removes only files owned by the old connection`() {
        val directory = Files.createTempDirectory("zf-relay-session-cleanup").toFile()
        try {
            val old = File(directory, "connection-A-1-10-DSC_0001.JPG.part").apply { writeBytes(ByteArray(4)) }
            val current = File(directory, "connection-B-2-10-DSC_0002.JPG.part").apply { writeBytes(ByteArray(4)) }

            cleanupPartialFiles(directory, "connection-A")

            assertFalse(old.exists())
            assertTrue(current.exists())
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun `stale partial cleanup preserves published and unrelated files`() {
        val directory = Files.createTempDirectory("zf-relay-cleanup").toFile()
        try {
            val first = File(directory, "connection-A-1-10-DSC_0001.JPG.part").apply { writeBytes(ByteArray(4)) }
            val second = File(directory, "connection-B-2-10-DSC_0002.JPG.part").apply { writeBytes(ByteArray(4)) }
            val published = File(directory, "DSC_0001.JPG").apply { writeBytes(ByteArray(4)) }
            val nested = File(directory, "nested.part").apply { mkdir() }
            val nestedPartial = File(nested, "keep.part").apply { writeBytes(ByteArray(4)) }

            cleanupPartialFiles(directory)

            assertFalse(first.exists())
            assertFalse(second.exists())
            assertTrue(published.exists())
            assertTrue(nestedPartial.exists())
        } finally {
            directory.deleteRecursively()
        }
    }
}
