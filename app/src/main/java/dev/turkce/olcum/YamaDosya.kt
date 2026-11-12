package dev.turkce.olcum

import java.util.zip.Inflater

/** One file as listed in a ZIP's central directory. */
internal class ZipEntryInfo(val name: String, val nameBytes: Int, val method: Int, val compressed: Long, val offset: Long)

/**
 * Looks inside a ZIP without downloading all of it. The list of files sits at the end of the archive and says where
 * each file starts, so a few small byte ranges are enough to read the beginning of one translation file.
 */
internal object ZipSample {
    /** How much of the archive's end is fetched to find the list of files. */
    const val TAIL = 64 * 1024
    private const val END = 0x06054b50
    private const val CENTRAL = 0x02014b50
    private const val LOCAL = 0x04034b50

    /** The byte range of the list of files, read from the last bytes of the archive; null when this is not a ZIP. */
    fun directory(tail: ByteArray): LongRange? {
        var i = tail.size - 22
        while (i >= 0 && i >= tail.size - 22 - 65_535) {
            if (int(tail, i) == END) {
                val size = int(tail, i + 12).toLong() and 0xffffffffL
                val offset = int(tail, i + 16).toLong() and 0xffffffffL
                return offset until offset + size
            }
            i--
        }
        return null
    }

    fun entries(directory: ByteArray): List<ZipEntryInfo> {
        val out = ArrayList<ZipEntryInfo>()
        var i = 0
        while (i + 46 <= directory.size && int(directory, i) == CENTRAL) {
            val nameLength = short(directory, i + 28)
            if (i + 46 + nameLength > directory.size) break
            out.add(ZipEntryInfo(
                String(directory, i + 46, nameLength, Charsets.UTF_8), nameLength, short(directory, i + 10),
                int(directory, i + 20).toLong() and 0xffffffffL, int(directory, i + 42).toLong() and 0xffffffffL,
            ))
            i += 46 + nameLength + short(directory, i + 30) + short(directory, i + 32)
        }
        return out
    }

    /** The beginning of one file, from bytes fetched at its local header; at most [limit] bytes once unpacked. */
    fun content(local: ByteArray, entry: ZipEntryInfo, limit: Int): ByteArray {
        if (local.size < 30 || int(local, 0) != LOCAL) return ByteArray(0)
        val start = 30 + short(local, 26) + short(local, 28)
        if (start >= local.size) return ByteArray(0)
        val data = local.copyOfRange(start, minOf(local.size.toLong(), start + entry.compressed).toInt())
        return when (entry.method) {
            0 -> data.copyOf(minOf(data.size, limit))
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(data)
                    val out = ByteArray(limit)
                    var n = 0
                    while (n < limit && !inflater.finished()) {
                        // The input usually stops mid-stream; whatever came out until then is enough.
                        val got = runCatching { inflater.inflate(out, n, limit - n) }.getOrDefault(0)
                        if (got == 0) break
                        n += got
                    }
                    out.copyOf(n)
                } finally {
                    inflater.end()
                }
            }
            else -> ByteArray(0)
        }
    }

    private fun short(b: ByteArray, i: Int) = (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8)
    private fun int(b: ByteArray, i: Int) = short(b, i) or (short(b, i + 2) shl 16)
}
