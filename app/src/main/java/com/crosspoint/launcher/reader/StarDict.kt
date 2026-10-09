package com.crosspoint.launcher.reader

import android.os.Environment
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.Inflater

/**
 * The firmware's dictionaries: StarDict folders under /dictionaries or /.dictionaries holding <stem>.idx plus
 * <stem>.dict or <stem>.dict.dz. Lookups binary-search a sampled index kept in memory, like the firmware's .qidx.
 */
class StarDict private constructor(base: File) {
    class Entry(val word: String, val definition: String, val html: Boolean)

    private val idx = File(base.path + ".idx")
    private val dict = File(base.path + ".dict").takeIf { it.isFile }
    private val dictZip = File(base.path + ".dict.dz")
    private val ifo = runCatching { File(base.path + ".ifo").readLines().associate { it.substringBefore('=') to it.substringAfter('=', "") } }
        .getOrDefault(emptyMap())
    private val sameType = ifo["sametypesequence"].orEmpty()
    private val offsetBytes = if (ifo["idxoffsetbits"] == "64") 8 else 4
    private val samples = ArrayList<Pair<ByteArray, Long>>()

    init {
        // ponytail: one sample per 32 entries, rebuilt per process; persist a sidecar if large dictionaries open slowly.
        BufferedInputStream(idx.inputStream(), 64 * 1024).use { input ->
            var position = 0L
            var count = 0
            while (true) {
                val word = readWord(input) ?: break
                if (count++ % SAMPLE == 0) samples += word to position
                input.skipFully((offsetBytes + 4).toLong())
                position += word.size + 1 + offsetBytes + 4
            }
        }
    }

    /** Exact (ASCII case-insensitive) match for the first candidate the dictionary knows. */
    fun lookup(candidates: List<String>): Entry? {
        for (candidate in candidates.distinct()) {
            val key = candidate.toByteArray()
            var low = 0; var high = samples.lastIndex; var start = 0
            while (low <= high) {
                val mid = (low + high) / 2
                if (compareFold(samples[mid].first, key) < 0) { start = mid; low = mid + 1 } else high = mid - 1
            }
            BufferedInputStream(idx.inputStream().apply { skip(samples.getOrNull(start)?.second ?: 0) }, 16 * 1024).use { input ->
                repeat(SAMPLE * 2) {
                    val word = readWord(input) ?: return@use
                    val offset = if (offsetBytes == 8) input.readLong() else input.readInt().toLong() and 0xffffffffL
                    val size = input.readInt()
                    val order = compareFold(word, key)
                    if (order == 0) return Entry(String(word), decode(read(offset, size)), sameType.any { it in "hgx" })
                    if (order > 0) return@use
                }
            }
        }
        return null
    }

    private fun read(offset: Long, size: Int): ByteArray {
        dict?.let { file -> return RandomAccessFile(file, "r").use { ByteArray(size).also { bytes -> it.seek(offset); it.readFully(bytes) } } }
        return DictZip(dictZip).read(offset, size)
    }

    /** sametypesequence data is the bare field; otherwise each field carries its type letter. */
    private fun decode(data: ByteArray): String {
        if (sameType.length == 1) return String(data)
        val out = StringBuilder()
        var i = 0
        var field = 0
        while (i < data.size) {
            val type = if (sameType.isNotEmpty()) sameType.getOrNull(field++) ?: break else data[i++].toInt().toChar()
            if (type.isUpperCase()) {
                if (i + 4 > data.size) break
                val length = ((data[i].toInt() and 255) shl 24) or ((data[i + 1].toInt() and 255) shl 16) or
                    ((data[i + 2].toInt() and 255) shl 8) or (data[i + 3].toInt() and 255)
                i += 4 + length
            } else {
                val end = if (sameType.isNotEmpty() && field == sameType.length) data.size else data.indexOfZero(i)
                if (type in "mtyhgx") out.append(String(data, i, end - i)).append('\n')
                i = end + 1
            }
        }
        return out.toString().trim()
    }

    /** dictzip: gzip with an "RA" extra field listing independently inflatable chunk sizes. */
    private class DictZip(file: File) {
        private val raf = RandomAccessFile(file, "r")
        private var chunkLength = 0
        private val chunkStarts = ArrayList<Long>()

        init {
            val header = ByteArray(10).also { raf.readFully(it) }
            val flags = header[3].toInt()
            require(header[0] == 0x1f.toByte() && header[1] == 0x8b.toByte() && flags and 4 != 0) { "Not a dictzip file" }
            val extraLength = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
            val extraEnd = raf.filePointer + extraLength
            while (raf.filePointer < extraEnd) {
                val id = "${raf.readUnsignedByte().toChar()}${raf.readUnsignedByte().toChar()}"
                val length = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
                if (id != "RA") { raf.skipBytes(length); continue }
                raf.skipBytes(2) // version
                chunkLength = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
                val count = raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8)
                val sizes = IntArray(count) { raf.readUnsignedByte() or (raf.readUnsignedByte() shl 8) }
                var start = 0L
                sizes.forEach { chunkStarts += start; start += it }
                chunkStarts += start
            }
            raf.seek(extraEnd)
            if (flags and 8 != 0) while (raf.readByte().toInt() != 0) Unit // file name
            if (flags and 16 != 0) while (raf.readByte().toInt() != 0) Unit // comment
            if (flags and 2 != 0) raf.skipBytes(2)
            val dataStart = raf.filePointer
            for (i in chunkStarts.indices) chunkStarts[i] += dataStart
            require(chunkLength > 0 && chunkStarts.size > 1) { "dictzip index missing" }
        }

        fun read(offset: Long, size: Int): ByteArray = raf.use {
            val out = ByteArrayOutputStream(size)
            var chunk = (offset / chunkLength).toInt()
            var skip = (offset % chunkLength).toInt()
            while (out.size() < size && chunk < chunkStarts.size - 1) {
                val compressed = ByteArray((chunkStarts[chunk + 1] - chunkStarts[chunk]).toInt())
                raf.seek(chunkStarts[chunk]); raf.readFully(compressed)
                val plain = ByteArray(chunkLength)
                val inflater = Inflater(true).apply { setInput(compressed) }
                val produced = try { inflater.inflate(plain) } finally { inflater.end() }
                val take = minOf(produced - skip, size - out.size())
                if (take > 0) out.write(plain, skip, take)
                skip = 0; chunk++
            }
            out.toByteArray()
        }
    }

    companion object {
        private const val SAMPLE = 32
        private val opened = HashMap<String, StarDict>()

        /** Folder name → base path (folder/stem), from /dictionaries then /.dictionaries; ambiguous folders are skipped. */
        /** Dictionaries added from the device live here, ahead of the shared /dictionaries folders. */
        fun installedDir(context: android.content.Context) = File(context.filesDir, "dictionaries")

        fun discover(context: android.content.Context): Map<String, File> = (listOf(installedDir(context)) +
            listOf("dictionaries", ".dictionaries").map { File(Environment.getExternalStorageDirectory(), it) }).flatMap { root ->
            root.listFiles { it.isDirectory }.orEmpty().mapNotNull { folder ->
                val stems = folder.listFiles { it.name.endsWith(".idx") }.orEmpty().map { it.name.removeSuffix(".idx") }
                stems.singleOrNull()?.let { File(folder, it) }
                    ?.takeIf { File(it.path + ".dict").isFile || File(it.path + ".dict.dz").isFile }?.let { folder.name to it }
            }
        }.distinctBy { it.first }.sortedBy { it.first.lowercase() }.toMap()

        /**
         * Installs a StarDict archive (.zip, .tar.gz/.tgz or .tar) into [installedDir], keeping only the dictionary files.
         * Returns the dictionary's name (its .ifo bookname, else the archive name).
         */
        fun install(context: android.content.Context, archive: java.io.InputStream, fileName: String): String {
            val lower = fileName.lowercase()
            val base = fileName.substringBefore(".tar").substringBefore(".tgz").substringBefore(".zip").trim().ifEmpty { "Dictionary" }
            val staging = File(installedDir(context), ".install").apply { deleteRecursively(); mkdirs() }
            var total = 0L
            fun keep(name: String, data: java.io.InputStream) {
                val file = name.substringAfterLast('/')
                if (!Regex("(?i).+\\.(ifo|idx|dict|dict\\.dz|syn)$").matches(file)) return
                File(staging, file).outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = data.read(buffer); if (count < 0) break
                        total += count
                        require(total <= 512L * 1024 * 1024) { "That dictionary is larger than 512 MB" }
                        out.write(buffer, 0, count)
                    }
                }
            }
            try {
                when {
                    lower.endsWith(".zip") -> java.util.zip.ZipInputStream(archive.buffered()).use { zip ->
                        while (true) { val entry = zip.nextEntry ?: break; if (!entry.isDirectory) keep(entry.name, zip) }
                    }
                    lower.endsWith(".tar.gz") || lower.endsWith(".tgz") -> untar(java.util.zip.GZIPInputStream(archive.buffered()), ::keep)
                    lower.endsWith(".tar") -> untar(archive.buffered(), ::keep)
                    else -> error("Choose a StarDict .zip, .tar.gz or .tgz file")
                }
                val idx = staging.listFiles { it.name.endsWith(".idx") }.orEmpty().singleOrNull() ?: error("No StarDict dictionary in that file")
                val stem = idx.name.removeSuffix(".idx")
                require(File(staging, "$stem.dict").isFile || File(staging, "$stem.dict.dz").isFile) { "The dictionary's .dict file is missing" }
                val name = runCatching { File(staging, "$stem.ifo").readLines().firstOrNull { it.startsWith("bookname=") }?.substringAfter('=') }
                    .getOrNull()?.trim()?.ifEmpty { null } ?: base
                val folder = name.replace(Regex("[^\\w ()+.-]"), "_").take(60)
                val target = File(installedDir(context), folder)
                target.deleteRecursively()
                check(staging.renameTo(target)) { "Could not save the dictionary" }
                synchronized(this) { opened.keys.filter { it.startsWith(target.path) }.forEach(opened::remove) }
                open(File(target, stem)) // Fails here, not at the first lookup, if the index is unreadable.
                return folder
            } finally {
                staging.deleteRecursively()
            }
        }

        /** Minimal ustar/GNU tar reader: regular files (with GNU long names) are handed to [file]. */
        private fun untar(input: java.io.InputStream, file: (String, java.io.InputStream) -> Unit) {
            val header = ByteArray(512)
            var longName: String? = null
            while (true) {
                if (input.readNBytesCompat(header) < 512 || header.all { it.toInt() == 0 }) return
                fun field(from: Int, length: Int) = String(header, from, length, Charsets.UTF_8).substringBefore('\u0000').trim()
                val size = field(124, 12).ifEmpty { "0" }.toLong(8)
                val type = header[156].toInt().toChar()
                val name = longName ?: (field(345, 155).let { if (it.isEmpty()) "" else "$it/" } + field(0, 100))
                longName = null
                val data = BoundedStream(input, size)
                when (type) {
                    'L' -> longName = String(data.readBytes(), Charsets.UTF_8).substringBefore('\u0000')
                    '0', '\u0000' -> file(name, data)
                }
                data.skipRest()
                val padding = (512 - size % 512) % 512
                input.skipFully(padding)
            }
        }

        private class BoundedStream(private val input: java.io.InputStream, private var left: Long) : java.io.InputStream() {
            override fun read(): Int = if (left <= 0) -1 else input.read().also { if (it >= 0) left-- }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (left <= 0) return -1
                val count = input.read(b, off, minOf(len.toLong(), left).toInt())
                if (count > 0) left -= count
                return count
            }
            fun skipRest() { input.skipFully(left); left = 0 }
        }

        private fun java.io.InputStream.readNBytesCompat(buffer: ByteArray): Int {
            var read = 0
            while (read < buffer.size) { val count = read(buffer, read, buffer.size - read); if (count < 0) break; read += count }
            return read
        }

        private fun java.io.InputStream.skipFully(count: Long) {
            var left = count
            val scratch = ByteArray(8192)
            while (left > 0) { val n = read(scratch, 0, minOf(left, scratch.size.toLong()).toInt()); if (n < 0) return; left -= n }
        }

        @Synchronized fun open(base: File): StarDict = opened.getOrPut(base.path) { StarDict(base) }

        /** The selected word, then lowercase and a few simple inflections. */
        fun candidates(word: String): List<String> {
            val lower = word.lowercase()
            return listOf(word, lower) + listOf("'s", "s", "es", "ed", "ing", "ly").filter { lower.endsWith(it) && lower.length > it.length + 2 }
                .map { lower.removeSuffix(it) } + listOf(lower.removeSuffix("ies") + "y").filter { lower.endsWith("ies") }
        }

        /** StarDict order: ASCII case-insensitive, then byte order (stardict_strcmp). */
        private fun compareFold(a: ByteArray, b: ByteArray): Int {
            for (i in 0 until minOf(a.size, b.size)) {
                val x = fold(a[i]); val y = fold(b[i])
                if (x != y) return x - y
            }
            return a.size - b.size
        }
        private fun fold(byte: Byte): Int = (byte.toInt() and 255).let { if (it in 65..90) it + 32 else it }

        private fun readWord(input: BufferedInputStream): ByteArray? {
            val word = ByteArrayOutputStream(32)
            while (true) {
                val b = input.read()
                if (b < 0) return null
                if (b == 0) return word.toByteArray()
                word.write(b)
            }
        }
        private fun BufferedInputStream.readInt(): Int = (read() shl 24) or (read() shl 16) or (read() shl 8) or read()
        private fun BufferedInputStream.readLong(): Long = (readInt().toLong() shl 32) or (readInt().toLong() and 0xffffffffL)
        private fun BufferedInputStream.skipFully(count: Long) { var left = count; while (left > 0) { val n = skip(left); if (n <= 0) { if (read() < 0) return; left-- } else left -= n } }
        private fun ByteArray.indexOfZero(from: Int): Int { for (i in from until size) if (this[i].toInt() == 0) return i; return size }
    }
}
