package com.crosspoint.launcher.data

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Environment
import android.util.Log
import javax.jmdns.JmDNS
import javax.jmdns.ServiceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PushbackInputStream
import java.net.URLDecoder
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLEncoder
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * A tiny single-purpose HTTP server: serves an upload page on GET / and accepts multipart EPUB
 * uploads on POST /upload, saving them into the device's Books folder. Mirrors the reader's
 * WiFi "File Transfer" feature so books can be sideloaded from a browser on the same network.
 */
class FileTransferServer(
    private val context: Context,
    private val port: Int = DEFAULT_PORT,
    private val onUploaded: (String) -> Unit = {}
) {
    private var serverSocket: ServerSocket? = null
    private var job: Job? = null
    private var jmdns: JmDNS? = null
    private var multicastLock: WifiManager.MulticastLock? = null
    @Volatile var running: Boolean = false
        private set

    val booksDir: File
        get() = File(Environment.getExternalStorageDirectory(), "Books").apply { mkdirs() }

    fun start(scope: CoroutineScope) {
        if (running) return
        running = true
        job = scope.launch(Dispatchers.IO) {
            try {
                registerMdnsService()
                val ss = ServerSocket(port)
                serverSocket = ss
                Log.i(TAG, "listening on port $port")
                while (running) {
                    val socket = try { ss.accept() } catch (e: Exception) { break }
                    launch(Dispatchers.IO) { runCatching { handle(socket) } }
                }
            } catch (e: Exception) {
                Log.w(TAG, "server error: ${e.message}")
                running = false
            }
        }
    }

    fun stop() {
        running = false
        unregisterMdnsService()
        runCatching { serverSocket?.close() }
        serverSocket = null
        job?.cancel()
        job = null
    }

    private fun registerMdnsService() {
        runCatching {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            multicastLock = wifi?.createMulticastLock("crosspoint-mdns")?.apply {
                setReferenceCounted(false)
                acquire()
            }
            val address = localIpv4Address() ?: return
            val dns = JmDNS.create(address, LOCAL_NAME)
            val service = ServiceInfo.create(
                "_http._tcp.local.",
                LOCAL_NAME,
                port,
                0,
                0,
                "path=/"
            )
            dns.registerService(service)
            jmdns = dns
            Log.i(TAG, "registered http://$LOCAL_HOST:$port at ${address.hostAddress}")
        }.onFailure {
            Log.w(TAG, "mDNS registration error", it)
            unregisterMdnsService()
        }
    }

    private fun unregisterMdnsService() {
        runCatching { jmdns?.unregisterAllServices() }
        runCatching { jmdns?.close() }
        jmdns = null
        runCatching { multicastLock?.release() }
        multicastLock = null
    }

    private fun localIpv4Address(): Inet4Address? = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress } as? Inet4Address
    }.getOrNull()

    private fun handle(socket: Socket) {
        socket.use { s ->
            val input = PushbackInputStream(s.getInputStream(), 8)
            val out = BufferedOutputStream(s.getOutputStream())
            val requestLine = readLine(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val path = parts[1]

            // Read headers.
            val headers = HashMap<String, String>()
            while (true) {
                val line = readLine(input) ?: break
                if (line.isEmpty()) break
                val idx = line.indexOf(':')
                if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] =
                    line.substring(idx + 1).trim()
            }

            when {
                method == "OPTIONS" -> respondOptions(out)
                method == "HEAD" && (path == "/" || path.startsWith("/?")) ->
                    respondHead(out, "200 OK", "text/html; charset=utf-8", HOME_PAGE.toByteArray().size)
                method == "GET" && (path == "/" || path.startsWith("/?")) -> respondHtml(out, HOME_PAGE)
                method == "HEAD" && path.startsWith("/files") ->
                    respondHead(out, "200 OK", "text/html; charset=utf-8", FILES_PAGE.toByteArray().size)
                method == "GET" && path.startsWith("/files") -> respondHtml(out, FILES_PAGE)
                method == "GET" && path.startsWith("/api/status") ->
                    respond(out, "200 OK", "application/json", statusJson().toByteArray())
                method == "GET" && path.startsWith("/api/files") ->
                    respond(out, "200 OK", "application/json", filesJson(target(path)).toByteArray())
                method == "GET" && path.startsWith("/download") -> handleDownload(out, path)
                method == "POST" && path.startsWith("/upload") -> {
                    val upload = runCatching { handleUpload(input, headers, target(path)) }
                    val saved = upload.getOrNull()
                    if (saved != null) {
                        onUploaded(saved)
                        if (headers["accept"]?.contains("text/html") == true) {
                            redirect(out, "/files?uploaded=${urlEncode(saved)}")
                        } else {
                            respond(out, "200 OK", "text/plain", "File uploaded successfully: $saved".toByteArray())
                        }
                    } else {
                        val reason = upload.exceptionOrNull()?.message ?: "Upload failed"
                        if (headers["accept"]?.contains("text/html") == true) redirect(out, "/files?error=${urlEncode(reason)}")
                        else respond(out, "400 Bad Request", "text/plain", reason.toByteArray())
                    }
                }
                method == "POST" && path.startsWith("/delete") -> {
                    val removed = handleDelete(target(path), urlDecode(path.substringAfter("name=", "").substringBefore("&")))
                    if (removed) respond(out, "200 OK", "application/json", "{\"ok\":true}".toByteArray())
                    else respond(out, "404 Not Found", "application/json", "{\"ok\":false}".toByteArray())
                }
                method == "PUT" -> handlePut(input, out, headers, path)
                else -> respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())
            }
            out.flush()
        }
    }

    private fun handlePut(
        input: InputStream,
        out: OutputStream,
        headers: Map<String, String>,
        path: String
    ) {
        val length = headers["content-length"]?.toLongOrNull() ?: 0L
        if (length <= 0 || length > MAX_UPLOAD) {
            respond(out, "400 Bad Request", "text/plain", "Invalid upload size".toByteArray())
            return
        }
        val name = File(urlDecode(path.substringBefore("?"))).name
            .ifBlank { "upload_${System.currentTimeMillis()}.epub" }
        val target = File(booksDir, normalizeUploadFileName(name))
        target.outputStream().use { output ->
            val buffer = ByteArray(16 * 1024)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                output.write(buffer, 0, read)
                remaining -= read
            }
        }
        normalizeWrappedEpub(target)
        onUploaded(target.name)
        respond(out, "201 Created", "text/plain", "File uploaded successfully: ${target.name}".toByteArray())
    }

    private fun handleDownload(out: OutputStream, path: String) {
        val rawPath = path.substringAfter("path=", "").substringBefore("&").takeIf { it.isNotBlank() }
        val rawName = path.substringAfter("name=", "").substringBefore("&").takeIf { it.isNotBlank() }
        val name = (rawPath ?: rawName)
            ?.let { urlDecode(it) }
            ?.let { File(it).name }
            ?: return respond(out, "400 Bad Request", "text/plain", "Missing file".toByteArray())
        val file = File(booksDir, name)
        if (!file.exists() || !file.isFile) {
            respond(out, "404 Not Found", "text/plain", "Not found".toByteArray())
            return
        }
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: application/epub+zip\r\n" +
            "Content-Length: ${file.length()}\r\n" +
            "Content-Disposition: attachment; filename=\"${file.name.replace("\"", "")}\"\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        file.inputStream().use { input -> input.copyTo(out) }
    }

    /** The File Manager's three sections; uploads and listings pick one with ?path=/Books|/Fonts|/Dictionaries. */
    private enum class Target { BOOKS, FONTS, DICTIONARIES }

    private fun target(path: String): Target {
        val value = urlDecode(path.substringAfter("path=", "").substringBefore("&")).trim('/').lowercase()
        return when (value) { "fonts" -> Target.FONTS; "dictionaries" -> Target.DICTIONARIES; else -> Target.BOOKS }
    }

    /** Removes an added font family (its folder, or a loose one-style file) or an installed dictionary. Books stay. */
    private fun handleDelete(target: Target, name: String): Boolean {
        if (name.isBlank() || name.startsWith(".") || name.contains('/') || name.contains('\\')) return false
        val root = when (target) {
            Target.FONTS -> com.crosspoint.launcher.reader.ReaderSettings.importedFontDir(context)
            Target.DICTIONARIES -> com.crosspoint.launcher.reader.StarDict.installedDir(context)
            Target.BOOKS -> return false
        }
        val folder = File(root, name)
        if (folder.isDirectory) return folder.deleteRecursively()
        val loose = root.listFiles { it.isFile && it.nameWithoutExtension == name }.orEmpty()
        return loose.isNotEmpty() && loose.all { it.delete() }
    }

    private fun handleUpload(input: InputStream, headers: Map<String, String>, target: Target = Target.BOOKS): String? {
        val contentType = headers["content-type"] ?: return null
        val boundary = Regex("boundary=(.+)").find(contentType)?.groupValues?.get(1)?.trim('"')
            ?: return null
        val length = headers["content-length"]?.toLongOrNull() ?: return null
        val body = ByteArray(length.toInt().coerceAtMost(MAX_UPLOAD))
        var read = 0
        while (read < body.size) {
            val n = input.read(body, read, body.size - read)
            if (n < 0) break
            read += n
        }
        // Every part: text fields (e.g. a font "family") and one or more files.
        val delimiter = "--$boundary".toByteArray()
        val fields = HashMap<String, String>()
        val files = ArrayList<Triple<String, Int, Int>>() // name, data start, data end
        var at = indexOf(body, delimiter, 0)
        while (at >= 0) {
            val headerStart = at + delimiter.size + 2
            if (headerStart > read) break
            val headerEnd = indexOf(body, "\r\n\r\n".toByteArray(), headerStart)
            if (headerEnd < 0) break
            val partHeader = String(body, headerStart, headerEnd - headerStart)
            val dataStart = headerEnd + 4
            val next = indexOf(body, delimiter, dataStart)
            var end = if (next < 0) read else next
            if (end >= 2 && body[end - 1] == '\n'.code.toByte() && body[end - 2] == '\r'.code.toByte()) end -= 2
            val field = Regex("name=\"([^\"]*)\"").find(partHeader)?.groupValues?.get(1).orEmpty()
            val filename = Regex("filename=\"([^\"]*)\"").find(partHeader)?.groupValues?.get(1)
            if (filename == null) fields[field] = String(body, dataStart, (end - dataStart).coerceAtLeast(0)).trim()
            else if (filename.isNotBlank() && end > dataStart) files += Triple(File(filename).name, dataStart, end)
            at = next
        }
        if (files.isEmpty()) return null

        when (target) {
            // A font family is a folder of style files, like the firmware's /.fonts/<Family>/ (Regular, Bold, Italic…).
            Target.FONTS -> {
                require(files.all { it.first.substringAfterLast('.', "").lowercase() in listOf("ttf", "otf", "ttc") }) { "Fonts must be .ttf, .otf or .ttc files" }
                val family = fields["family"]?.replace(Regex("[^\\w .-]"), "_")?.trim()?.take(60)?.ifEmpty { null }
                    ?: com.crosspoint.launcher.reader.ReaderSettings.familyName(files.map { it.first })
                val dir = File(com.crosspoint.launcher.reader.ReaderSettings.importedFontDir(context), family).apply { mkdirs() }
                files.forEach { (name, start, end) -> File(dir, normalizeUploadFileName(name)).outputStream().use { it.write(body, start, end - start) } }
                if (com.crosspoint.launcher.reader.ReaderSettings.loadFamily(dir.listFiles().orEmpty().toList()) == null) {
                    dir.deleteRecursively(); error("Those files aren't usable fonts")
                }
                return family
            }
            // Dictionaries are StarDict archives, unpacked and installed like Add Dictionary from Device.
            Target.DICTIONARIES -> {
                val (name, start, end) = files.first()
                return com.crosspoint.launcher.reader.StarDict.install(context, java.io.ByteArrayInputStream(body, start, end - start), name)
            }
            Target.BOOKS -> return files.map { (name, start, end) ->
                val file = File(booksDir, normalizeUploadFileName(name))
                file.outputStream().use { os -> os.write(body, start, end - start) }
                normalizeWrappedEpub(file)
                file.name
            }.joinToString(", ")
        }
    }

    private fun normalizeUploadFileName(raw: String): String {
        var safe = File(raw).name
            .ifBlank { "upload_${System.currentTimeMillis()}.epub" }
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val lower = safe.lowercase()
        // Keep the real name; only a zipped EPUB ("x.epub.zip") is unwrapped back to ".epub".
        safe = when {
            lower.endsWith(".epub.zip.epub") -> safe.dropLast(".zip.epub".length)
            lower.endsWith(".epub.zip") -> safe.dropLast(".zip".length)
            else -> safe
        }
        return safe
    }

    private fun normalizeWrappedEpub(file: File) {
        if (!file.name.endsWith(".epub", ignoreCase = true)) return
        val prefix = runCatching {
            ZipFile(file).use { zip ->
                val names = zip.entries().asSequence()
                    .map { it.name }
                    .filter { it.isNotBlank() && !it.endsWith("/") }
                    .toList()
                val firstParts = names.mapNotNull { it.substringBefore('/', missingDelimiterValue = "") }
                    .filter { it.isNotBlank() }
                    .distinct()
                val single = firstParts.singleOrNull() ?: return
                val wrapped = "$single/"
                if (!single.endsWith(".epub", ignoreCase = true)) return
                if (names.all { it.startsWith(wrapped) }) wrapped else null
            }
        }.getOrNull() ?: return

        val tmp = File(file.parentFile, "${file.name}.tmp")
        runCatching {
            ZipFile(file).use { zip ->
                ZipOutputStream(tmp.outputStream()).use { out ->
                    zip.entries().asSequence().forEach { entry ->
                        val strippedName = entry.name.removePrefix(prefix)
                        if (strippedName.isBlank()) return@forEach
                        val outEntry = ZipEntry(strippedName).apply {
                            time = entry.time
                        }
                        out.putNextEntry(outEntry)
                        if (!entry.isDirectory) {
                            zip.getInputStream(entry).use { input -> input.copyTo(out) }
                        }
                        out.closeEntry()
                    }
                }
            }
            if (tmp.length() > 0) {
                tmp.copyTo(file, overwrite = true)
            }
        }.onFailure {
            Log.w(TAG, "wrapped EPUB normalization failed for ${file.name}: ${it.message}")
        }
        tmp.delete()
    }

    private fun respondHtml(out: OutputStream, html: String) =
        respond(out, "200 OK", "text/html; charset=utf-8", html.toByteArray())

    private fun respondHead(out: OutputStream, status: String, contentType: String, length: Int) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $length\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
    }

    private fun respond(out: OutputStream, status: String, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 $status\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
        out.write(body)
    }

    private fun redirect(out: OutputStream, location: String) {
        val header = "HTTP/1.1 303 See Other\r\n" +
            "Location: $location\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
    }

    private fun respondOptions(out: OutputStream) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Allow: OPTIONS, GET, HEAD, POST, PUT\r\n" +
            "DAV: 1\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n\r\n"
        out.write(header.toByteArray())
    }

    private fun statusJson(): String =
        """{"version":"Android","ip":"${LOCAL_HOST}:$port","freeHeap":0,"mode":"STA","rssi":0}"""

    private fun filesJson(target: Target = Target.BOOKS): String {
        if (target == Target.FONTS) return com.crosspoint.launcher.reader.ReaderSettings.importedFontDir(context).listFiles().orEmpty()
            .sortedBy { it.name.lowercase() }.joinToString(prefix = "[", postfix = "]") { entry ->
                val files = if (entry.isDirectory) entry.listFiles().orEmpty().toList() else listOf(entry)
                """{"name":"${jsonEscape(if (entry.isDirectory) entry.name else entry.nameWithoutExtension)}","size":${files.sumOf { it.length() }},"isDirectory":false,"isEpub":false,"styles":${files.size}}"""
            }
        if (target == Target.DICTIONARIES) return com.crosspoint.launcher.reader.StarDict.installedDir(context).listFiles { it.isDirectory && !it.name.startsWith(".") }.orEmpty()
            .sortedBy { it.name.lowercase() }.joinToString(prefix = "[", postfix = "]") { dir ->
                """{"name":"${jsonEscape(dir.name)}","size":${dir.listFiles().orEmpty().sumOf { it.length() }},"isDirectory":true,"isEpub":false}"""
            }
        val entries = booksDir.listFiles()
            ?.filter { it.isFile && !it.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() }
            ?: emptyList()
        return entries.joinToString(prefix = "[", postfix = "]") { file ->
            """{"name":"${jsonEscape(file.name)}","size":${file.length()},"isDirectory":false,"isEpub":${file.name.endsWith(".epub", ignoreCase = true)},"downloadUrl":"/download?path=${urlEncode("/Books/${file.name}")}"}"""
        }
    }

    private fun readLine(input: InputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val c = input.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) {
                if (prev == '\r'.code && sb.isNotEmpty()) sb.deleteCharAt(sb.length - 1)
                return sb.toString()
            }
            sb.append(c.toChar())
            prev = c
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }

    private fun jsonEscape(value: String): String =
        value.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun urlEncode(value: String): String =
        URLEncoder.encode(value, "UTF-8")

    private fun urlDecode(value: String): String =
        URLDecoder.decode(value, "UTF-8")

    companion object {
        private const val TAG = "FileTransferServer"
        const val LOCAL_NAME = "crosspoint"
        const val LOCAL_HOST = "$LOCAL_NAME.local"
        const val DEFAULT_PORT = 8080
        private const val MAX_UPLOAD = 400 * 1024 * 1024
        private val SHARED_STYLE = """
            <style>
              :root {
                --stone-50: #fafaf9;
                --stone-100: #f5f5f4;
                --stone-200: #e7e5e4;
                --stone-300: #d6d3d1;
                --stone-400: #a8a29e;
                --stone-500: #78716c;
                --stone-600: #57534e;
                --stone-700: #44403c;
                --stone-900: #1c1917;
                --brand-50: #f0f5f3;
                --brand-100: #d6e5de;
                --brand-200: #b3cfc2;
                --brand-500: #4a7a62;
                --brand-600: #3d6652;
                --brand-700: #315243;
                --bg: var(--stone-50);
                --surface: #ffffff;
                --text: var(--stone-900);
                --muted: var(--stone-500);
                --border: var(--stone-200);
                --shadow: 0 18px 50px -28px rgba(28, 25, 23, 0.32);
              }
              @media (prefers-color-scheme: dark) {
                :root {
                  --bg: #1c1917;
                  --surface: #292524;
                  --text: #fafaf9;
                  --muted: #a8a29e;
                  --border: #44403c;
                  --brand-50: #1f2f28;
                  --brand-100: #253e33;
                  color-scheme: dark;
                }
              }
              * { box-sizing: border-box; }
              body {
                margin: 0;
                background:
                  radial-gradient(circle at top left, var(--brand-50), transparent 30rem),
                  var(--bg);
                color: var(--text);
                font-family: Inter, ui-sans-serif, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif;
                line-height: 1.5;
              }
              .topbar {
                background: color-mix(in srgb, var(--surface) 92%, transparent);
                border-bottom: 1px solid var(--border);
                backdrop-filter: blur(10px);
              }
              .topbar-inner {
                max-width: 72rem;
                margin: 0 auto;
                padding: 0.8rem 1rem;
                display: flex;
                align-items: center;
                justify-content: space-between;
                gap: 1rem;
              }
              .brand {
                display: inline-flex;
                align-items: center;
                gap: 0.6rem;
                color: var(--text);
                text-decoration: none;
                font-weight: 650;
                letter-spacing: -0.02em;
              }
              .brand-mark {
                width: 1.75rem;
                height: 1.75rem;
                display: grid;
                place-items: center;
                border-radius: 0.45rem;
                background: #fff;
                border: 1px solid var(--border);
                box-shadow: 0 1px 2px rgba(0,0,0,0.06);
              }
              .brand-mark svg { width: 1.05rem; height: 1.16rem; display: block; }
              .nav-links { display: flex; gap: 0.4rem; flex-wrap: wrap; justify-content: flex-end; }
              .nav-links a {
                color: var(--stone-500);
                text-decoration: none;
                border-radius: 0.45rem;
                padding: 0.45rem 0.7rem;
                font-size: 0.9rem;
                font-weight: 600;
              }
              .nav-links a.active {
                background: var(--brand-500);
                color: white;
              }
              .nav-links a:not(.active):hover {
                color: var(--stone-700);
                background: var(--stone-100);
              }
              main {
                max-width: 56rem;
                margin: 0 auto;
                padding: 3.2rem 1rem 4rem;
              }
              .eyebrow {
                margin: 0;
                color: var(--brand-500);
                font-weight: 700;
                font-size: 0.88rem;
              }
              h1 {
                margin: 0.35rem 0 0;
                color: var(--text);
                font-family: Georgia, "Times New Roman", serif;
                font-size: clamp(2.2rem, 7vw, 4.6rem);
                line-height: 0.95;
                letter-spacing: -0.045em;
                font-weight: 500;
              }
              h2 {
                color: var(--text);
                margin: 0;
                font-size: 1.05rem;
                letter-spacing: -0.02em;
              }
              .lede {
                max-width: 42rem;
                margin: 1rem 0 0;
                color: var(--muted);
                font-size: 1.04rem;
              }
              .stack { display: grid; gap: 1rem; margin-top: 2rem; }
              .card {
                background: var(--surface);
                border: 1px solid var(--border);
                border-radius: 1rem;
                padding: 1.25rem;
                box-shadow: var(--shadow);
              }
              .info-row {
                display: flex;
                justify-content: space-between;
                align-items: center;
                gap: 1rem;
                padding: 0.75rem 0;
                border-bottom: 1px solid var(--border);
              }
              .info-row:last-child { border-bottom: none; }
              .label {
                color: var(--muted);
                font-weight: 650;
                font-size: 0.92rem;
              }
              .value {
                color: var(--text);
                word-break: break-word;
                text-align: right;
                font-weight: 600;
              }
              .status {
                display: inline-flex;
                align-items: center;
                border-radius: 999px;
                background: var(--brand-100);
                color: var(--brand-700);
                padding: 0.25rem 0.7rem;
                font-size: 0.82rem;
                font-weight: 700;
              }
              .page-header {
                display: flex;
                justify-content: space-between;
                align-items: center;
                flex-wrap: wrap;
                gap: 0.8rem;
                margin-bottom: 1rem;
              }
              .upload-form {
                display: grid;
                gap: 0.8rem;
                margin-top: 1rem;
              }
              .upload-form input[type="file"] {
                width: 100%;
                color: var(--muted);
                font-size: 0.95rem;
              }
              .upload-form input[type="file"]::file-selector-button {
                margin-right: 0.8rem;
                border: 0;
                border-radius: 0.55rem;
                background: var(--brand-50);
                color: var(--brand-700);
                padding: 0.55rem 0.8rem;
                font-weight: 700;
                cursor: pointer;
              }
              .upload-btn {
                color: white;
                background: var(--brand-500);
                padding: 0.8rem 1rem;
                border: none;
                border-radius: 0.55rem;
                cursor: pointer;
                font-size: 0.95rem;
                font-weight: 750;
                width: fit-content;
              }
              .upload-btn:hover { background: var(--brand-600); }
              .file-table { width: 100%; border-collapse: collapse; }
              .file-table th,
              .file-table td {
                padding: 0.85rem 0.7rem;
                text-align: left;
                border-bottom: 1px solid var(--border);
              }
              .file-table th {
                font-weight: 700;
                color: var(--muted);
                font-size: 0.82rem;
                text-transform: uppercase;
                letter-spacing: 0.08em;
              }
              .file-table tr:hover { background: var(--brand-50); }
              .file-link {
                word-break: break-word;
                color: var(--brand-600);
                text-decoration: none;
                font-weight: 650;
              }
              .file-link:hover {
                color: var(--brand-700);
                text-decoration: underline;
                text-underline-offset: 3px;
              }
              .epub-badge {
                display: inline-block;
                padding: 0.18rem 0.5rem;
                background: var(--brand-100);
                color: var(--brand-700);
                border-radius: 999px;
                font-size: 0.75rem;
                font-weight: 800;
              }
              .muted { color: var(--muted); }
              .footer-note {
                margin-top: 1rem;
                text-align: center;
                color: var(--muted);
                font-size: 0.88rem;
              }
              @media (max-width: 640px) {
                .topbar-inner { align-items: flex-start; flex-direction: column; }
                .nav-links { justify-content: flex-start; }
                main { padding-top: 2.2rem; }
                .info-row { align-items: flex-start; flex-direction: column; gap: 0.25rem; }
                .value { text-align: left; }
                .file-table th:nth-child(2), .file-table td:nth-child(2) { display: none; }
              }
            </style>
        """.trimIndent()

        private val BRAND_MARK = """
            <span class="brand-mark" aria-hidden="true">
              <svg viewBox="0 0 162 180" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M141.133 1.93774C150.102 -3.44192 161.517 3.01665 161.521 13.4749V166.526C161.517 176.984 150.102 183.442 141.133 178.063L80.7588 141.837L20.3867 178.063C11.4176 183.442 0.00328866 176.984 0 166.526V13.4749C0.00337257 3.01685 11.4176 -3.44137 20.3867 1.93774L80.7598 38.1614L141.133 1.93774ZM121.275 117.524L92.3848 134.864L147.281 167.811C148.277 168.408 149.542 167.685 149.545 166.526V100.567L121.275 117.524ZM14.2393 12.1897C13.2438 11.5924 11.979 12.3152 11.9756 13.4749V86.6125L74.7725 124.29V48.51L14.2393 12.1897Z" fill="black"/>
              </svg>
            </span>
        """.trimIndent()

        private val HOME_PAGE = """
            <!DOCTYPE html><html><head><meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>CrossPoint</title>$SHARED_STYLE</head>
            <body>
              <nav class="topbar">
                <div class="topbar-inner">
                  <a class="brand" href="/">$BRAND_MARK<span>CrossPoint</span></a>
                  <div class="nav-links"><a href="/" class="active">Home</a><a href="/files">File Manager</a></div>
                </div>
              </nav>
              <main>
                <p class="eyebrow">Local file transfer</p>
                <h1>Send books to CrossPoint.</h1>
                <p class="lede">Upload EPUB files from any browser on the same Wi-Fi network. Files land in the device Books folder and appear in your launcher library after scanning.</p>
                <div class="stack">
                  <div class="card">
                    <h2>Device Status</h2>
                    <div class="info-row"><span class="label">Version</span><span class="value" id="version"></span></div>
                    <div class="info-row"><span class="label">Wi-Fi Status</span><span class="status">Connected</span></div>
                    <div class="info-row"><span class="label">Web Address</span><span class="value">http://$LOCAL_HOST:$DEFAULT_PORT</span></div>
                  </div>
                  <div class="card">
                    <h2>Quick Upload</h2>
                    <form class="upload-form" action="/upload?path=/Books" method="post" enctype="multipart/form-data">
                      <input type="file" name="file" accept=".epub" />
                      <input type="hidden" name="path" value="/Books" />
                      <button class="upload-btn" type="submit">Upload EPUB</button>
                    </form>
                  </div>
                </div>
                <p class="footer-note">CrossPoint E-Reader • Open Source</p>
              </main>
              <script>
                fetch('/api/status').then(r => r.json()).then(data => {
                  document.getElementById('version').textContent = data.version || 'Android';
                });
              </script>
            </body></html>
        """.trimIndent()

        private val FILES_PAGE = """
            <!DOCTYPE html><html><head><meta charset="UTF-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0" />
            <title>Files - CrossPoint</title>$SHARED_STYLE</head>
            <body>
              <nav class="topbar">
                <div class="topbar-inner">
                  <a class="brand" href="/">$BRAND_MARK<span>CrossPoint</span></a>
                  <div class="nav-links"><a href="/">Home</a><a href="/files" class="active">File Manager</a></div>
                </div>
              </nav>
              <main>
                <p class="eyebrow">Books, fonts and dictionaries</p>
                <h1>File Manager</h1>
                <p class="lede">Upload books, reader fonts and dictionaries, and download books already on this device.</p>
                <p id="notice" class="muted"></p>
                <div class="stack" id="sections"></div>
              </main>
              <script>
                function formatBytes(bytes) {
                  if (!bytes) return '0 B';
                  const units = ['B', 'KB', 'MB', 'GB'];
                  let size = bytes;
                  let unit = 0;
                  while (size >= 1024 && unit < units.length - 1) { size /= 1024; unit++; }
                  return size.toFixed(unit === 0 ? 0 : 1) + ' ' + units[unit];
                }
                const sections = [
                  { path: '/Books', title: 'Books', hint: 'EPUB books, saved to /Books', accept: '.epub', button: 'Upload EPUB', empty: 'No EPUB files yet.', multiple: true },
                  { path: '/Fonts', removable: true, title: 'Fonts', hint: 'Choose a family\'s Regular, Bold, Italic and Bold Italic files together (.ttf, .otf, .ttc)', accept: '.ttf,.otf,.ttc', button: 'Upload Font Family', empty: 'No fonts added yet.', family: true, multiple: true },
                  { path: '/Dictionaries', removable: true, title: 'Dictionaries', hint: 'StarDict .zip or .tar.gz, used by Define', accept: '.zip,.gz,.tgz,.tar', button: 'Upload Dictionary', empty: 'No dictionaries added yet.' },
                ];
                function escapeHtml(value) { const d = document.createElement('div'); d.textContent = value; return d.innerHTML; }
                document.getElementById('sections').innerHTML = sections.map((s, i) =>
                  '<div class="card"><div class="page-header"><h2>' + s.title + '</h2><span class="muted">' + s.hint + '</span></div>' +
                  '<form class="upload-form" action="/upload?path=' + s.path + '" method="post" enctype="multipart/form-data">' +
                  (s.family ? '<input type="text" name="family" placeholder="Family name (optional, e.g. Literata)" />' : '') +
                  '<input type="file" name="file" accept="' + s.accept + '"' + (s.multiple ? ' multiple' : '') + ' /><button class="upload-btn" type="submit">' + s.button + '</button></form>' +
                  '<table class="file-table"><thead><tr><th>Name</th><th>Type</th><th>Size</th>' + (s.removable ? '<th></th>' : '') + '</tr></thead>' +
                  '<tbody id="list-' + i + '"><tr><td colspan="3" class="muted">Loading...</td></tr></tbody></table></div>'
                ).join('');
                const params = new URLSearchParams(location.search);
                const notice = params.get('error') ? 'Upload failed: ' + params.get('error') : params.get('uploaded') ? 'Uploaded ' + params.get('uploaded') : '';
                document.getElementById('notice').textContent = notice;
                async function loadFiles(section, index) {
                  const body = document.getElementById('list-' + index);
                  try {
                    const files = await fetch('/api/files?path=' + section.path).then(r => r.json());
                    if (!files || files.length === 0) {
                      body.innerHTML = '<tr><td colspan="3" class="muted">' + section.empty + '</td></tr>';
                      return;
                    }
                    body.innerHTML = files.map(file => {
                      const name = escapeHtml(file.name);
                      const label = file.isEpub ? '<span class="epub-badge">EPUB</span>' : file.isDirectory ? 'Dictionary'
                        : file.styles ? file.styles + (file.styles === 1 ? ' style' : ' styles') : (name.split('.').pop() || '').toUpperCase();
                      const link = file.downloadUrl ? '<a class="file-link" href="' + file.downloadUrl + '">' + name + '</a>' : name;
                      const remove = section.removable
                        ? '<td><button class="upload-btn" type="button" onclick="removeItem(' + index + ', \'' + encodeURIComponent(file.name) + '\')">Delete</button></td>' : '';
                      return '<tr><td>' + link + '</td><td>' + label + '</td><td>' + formatBytes(file.size) + '</td>' + remove + '</tr>';
                    }).join('');
                  } catch (error) {
                    body.innerHTML = '<tr><td colspan="3" class="muted">Could not load files.</td></tr>';
                  }
                }
                async function removeItem(index, encodedName) {
                  const section = sections[index];
                  const name = decodeURIComponent(encodedName);
                  if (!confirm('Delete ' + name + ' from this device?')) return;
                  const response = await fetch('/delete?path=' + section.path + '&name=' + encodedName, { method: 'POST' });
                  document.getElementById('notice').textContent = response.ok ? 'Deleted ' + name : 'Could not delete ' + name;
                  loadFiles(section, index);
                }
                sections.forEach(loadFiles);
              </script>
            </body></html>
        """.trimIndent()
    }
}
