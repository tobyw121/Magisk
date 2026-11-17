package com.topjohnwu.magisk.core.utils

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder.LITTLE_ENDIAN

/** Rewrites an unstyled Android binary XML string pool, preserving all string indices. */
class AXML(input: ByteArray) {
    var bytes = input
        private set

    fun patchStrings(map: (String) -> String): Boolean {
        val buffer = ByteBuffer.wrap(bytes).order(LITTLE_ENDIAN)
        require(bytes.size >= 8 && buffer.getInt(4) == bytes.size)
        var start = 8
        while (start + 28 <= bytes.size && buffer.getInt(start) != 0x001c0001) {
            val size = buffer.getInt(start + 4)
            require(size >= 8 && size <= bytes.size - start)
            start += size
        }
        if (start + 28 > bytes.size) return false
        val size = buffer.getInt(start + 4)
        val count = buffer.getInt(start + 8)
        require(size >= 28 && size <= bytes.size - start)
        require(count >= 0 && count <= (size - 28) / 4)
        require(buffer.getInt(start + 12) == 0 && buffer.getInt(start + 24) == 0)
        val flags = buffer.getInt(start + 16)
        val data = buffer.getInt(start + 20)
        require(data >= 28 + count * 4 && data < size)
        val output = ByteArrayOutputStream()
        output.write(bytes, 0, start + data)
        val offsets = IntArray(count)
        for (i in 0 until count) {
            var pos = start + data + buffer.getInt(start + 28 + i * 4)
            require(pos >= start + data && pos < start + size)
            fun length8(): Int {
                require(pos < start + size)
                val first = bytes[pos++].toInt() and 255
                if (first < 128) return first
                require(pos < start + size)
                return ((first and 127) shl 8) or (bytes[pos++].toInt() and 255)
            }
            val str = if (flags and 0x100 != 0) {
                length8() // UTF-16 character count
                val length = length8()
                require(length < start + size - pos && bytes[pos + length] == 0.toByte())
                String(bytes, pos, length, Charsets.UTF_8)
            } else {
                require(pos + 2 <= start + size)
                val length = buffer.getShort(pos).toInt() and 65535
                require(length < 32768 && length * 2 + 4 <= start + size - pos)
                String(bytes, pos + 2, length * 2, Charsets.UTF_16LE)
            }
            val mapped = map(str)
            require(mapped.length < 32768)
            offsets[i] = output.size() - start - data
            output.write(mapped.length and 255)
            output.write(mapped.length ushr 8)
            output.write(mapped.toByteArray(Charsets.UTF_16LE))
            output.write(0)
            output.write(0)
        }
        while (output.size() % 4 != 0) output.write(0)
        val newSize = output.size() - start
        output.write(bytes, start + size, bytes.size - start - size)
        val result = output.toByteArray()
        val patched = ByteBuffer.wrap(result).order(LITTLE_ENDIAN)
        patched.putInt(4, result.size)
        patched.putInt(start + 4, newSize)
        // Output is UTF-16 and no longer sorted after replacements.
        patched.putInt(start + 16, flags and 0x101.inv())
        offsets.forEachIndexed { i, off -> patched.putInt(start + 28 + i * 4, off) }
        bytes = result
        return true
    }
}
