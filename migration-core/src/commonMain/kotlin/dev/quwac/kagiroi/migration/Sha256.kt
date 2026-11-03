@file:Suppress("LongMethod", "MagicNumber")

package dev.quwac.kagiroi.migration

/** Computes a lowercase SHA-256 digest using common Kotlin only. */
fun sha256Hex(value: String): String {
    val source = value.encodeToByteArray()
    val bitLength = source.size.toLong() * 8L
    val paddedLength = ((source.size + 9 + 63) / 64) * 64
    val bytes = ByteArray(paddedLength)
    source.copyInto(bytes)
    bytes[source.size] = 0x80.toByte()
    repeat(8) { index ->
        bytes[bytes.size - 1 - index] = (bitLength ushr (index * 8)).toByte()
    }

    var h0 = 0x6a09e667
    var h1 = 0xbb67ae85.toInt()
    var h2 = 0x3c6ef372
    var h3 = 0xa54ff53a.toInt()
    var h4 = 0x510e527f
    var h5 = 0x9b05688c.toInt()
    var h6 = 0x1f83d9ab
    var h7 = 0x5be0cd19
    val words = IntArray(64)

    bytes.indices.step(64).forEach { offset ->
        repeat(16) { index ->
            val start = offset + index * 4
            words[index] = ((bytes[start].toInt() and 0xff) shl 24) or
                ((bytes[start + 1].toInt() and 0xff) shl 16) or
                ((bytes[start + 2].toInt() and 0xff) shl 8) or
                (bytes[start + 3].toInt() and 0xff)
        }
        for (index in 16 until 64) {
            val smallSigma0 = words[index - 15].rotateRight(7) xor words[index - 15].rotateRight(18) xor
                (words[index - 15] ushr 3)
            val smallSigma1 = words[index - 2].rotateRight(17) xor words[index - 2].rotateRight(19) xor
                (words[index - 2] ushr 10)
            words[index] = words[index - 16] + smallSigma0 + words[index - 7] + smallSigma1
        }

        var a = h0
        var b = h1
        var c = h2
        var d = h3
        var e = h4
        var f = h5
        var g = h6
        var h = h7
        repeat(64) { index ->
            val bigSigma1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val choice = (e and f) xor (e.inv() and g)
            val temporary1 = h + bigSigma1 + choice + SHA256_CONSTANTS[index] + words[index]
            val bigSigma0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val majority = (a and b) xor (a and c) xor (b and c)
            val temporary2 = bigSigma0 + majority
            h = g
            g = f
            f = e
            e = d + temporary1
            d = c
            c = b
            b = a
            a = temporary1 + temporary2
        }
        h0 += a
        h1 += b
        h2 += c
        h3 += d
        h4 += e
        h5 += f
        h6 += g
        h7 += h
    }
    return intArrayOf(h0, h1, h2, h3, h4, h5, h6, h7).joinToString(separator = "") { word ->
        word.toUInt().toString(16).padStart(8, '0')
    }
}

private fun Int.rotateRight(bits: Int): Int = (this ushr bits) or (this shl (32 - bits))

private val SHA256_CONSTANTS = intArrayOf(
    0x428a2f98, 0x71374491, 0xb5c0fbcf.toInt(), 0xe9b5dba5.toInt(), 0x3956c25b, 0x59f111f1,
    0x923f82a4.toInt(), 0xab1c5ed5.toInt(), 0xd807aa98.toInt(), 0x12835b01, 0x243185be, 0x550c7dc3,
    0x72be5d74, 0x80deb1fe.toInt(), 0x9bdc06a7.toInt(), 0xc19bf174.toInt(), 0xe49b69c1.toInt(),
    0xefbe4786.toInt(), 0x0fc19dc6, 0x240ca1cc, 0x2de92c6f, 0x4a7484aa, 0x5cb0a9dc, 0x76f988da,
    0x983e5152.toInt(), 0xa831c66d.toInt(), 0xb00327c8.toInt(), 0xbf597fc7.toInt(), 0xc6e00bf3.toInt(),
    0xd5a79147.toInt(), 0x06ca6351, 0x14292967, 0x27b70a85, 0x2e1b2138, 0x4d2c6dfc, 0x53380d13,
    0x650a7354, 0x766a0abb, 0x81c2c92e.toInt(), 0x92722c85.toInt(), 0xa2bfe8a1.toInt(),
    0xa81a664b.toInt(), 0xc24b8b70.toInt(), 0xc76c51a3.toInt(), 0xd192e819.toInt(), 0xd6990624.toInt(),
    0xf40e3585.toInt(), 0x106aa070, 0x19a4c116, 0x1e376c08, 0x2748774c, 0x34b0bcb5, 0x391c0cb3,
    0x4ed8aa4a, 0x5b9cca4f, 0x682e6ff3, 0x748f82ee, 0x78a5636f, 0x84c87814.toInt(), 0x8cc70208.toInt(),
    0x90befffa.toInt(), 0xa4506ceb.toInt(), 0xbef9a3f7.toInt(), 0xc67178f2.toInt(),
)
