package com.topjohnwu.magisk.core.utils

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

class AXMLIdentityTest {
    private val original = "com.topjohnwu.magisk"
    private val strings = listOf(original, "$original.provider", "Magisk", "$original.App")

    private fun fixture(utf8: Boolean): ByteArray {
        val data = ByteArrayOutputStream()
        val offsets = strings.map {
            val off = data.size()
            if (utf8) {
                data.write(it.length)
                data.write(it.toByteArray().size)
                data.write(it.toByteArray())
                data.write(0)
            } else {
                data.write(it.length)
                data.write(0)
                data.write(it.toByteArray(Charsets.UTF_16LE))
                data.write(0)
                data.write(0)
            }
            off
        }
        while (data.size() % 4 != 0) data.write(0)
        val header = 28 + strings.size * 4
        val poolSize = header + data.size()
        val xml = ByteBuffer.allocate(8 + poolSize + 8).order(LITTLE_ENDIAN)
        xml.putInt(0x00080003).putInt(xml.capacity())
        xml.putInt(0x001c0001).putInt(poolSize).putInt(strings.size).putInt(0)
        xml.putInt(if (utf8) 0x100 else 0).putInt(header).putInt(0)
        offsets.forEach { xml.putInt(it) }
        xml.put(data.toByteArray())
        xml.putInt(0x00080180).putInt(8) // Later chunk must survive resizing.
        return xml.array()
    }

    private fun verify(utf8: Boolean) {
        val patched = ManifestIdentity.rewrite(fixture(utf8), original, "abcdefgh.ijklmnop", "Neutralxyz")
        val read = mutableListOf<String>()
        assertTrue(AXML(patched).patchStrings { read.add(it); it })
        assertEquals(listOf("abcdefgh.ijklmnop", "abcdefgh.ijklmnop.provider", "Neutralxyz", "$original.App"), read)
        val buffer = ByteBuffer.wrap(patched).order(LITTLE_ENDIAN)
        assertEquals(patched.size, buffer.getInt(4))
        assertEquals(0x00080180, buffer.getInt(patched.size - 8))
    }

    @Test fun repacksUtf8WithoutChangingClasses() = verify(true)
    @Test fun repacksUtf16WithoutChangingClasses() = verify(false)
    @Test fun rejectsInvalidIdentity() {
        assertThrows(IllegalArgumentException::class.java) {
            ManifestIdentity.rewrite(fixture(true), original, "x;command.y", "Label")
        }
    }
}
