package com.vstoianov.libre2key.server

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.nio.charset.StandardCharsets

/**
 * HTTP server for GET /scan.json. Bound on all interfaces so a second phone
 * running Zepp (typical: Android NFC + iPhone Zepp) can pull on the same Wi‑Fi.
 */
class ScanHttpServer(
    private val jsonProvider: () -> String?,
    private val onServed: () -> Unit
) {
    @Volatile
    private var running = false
    private var server: ServerSocket? = null
    private var acceptThread: Thread? = null

    fun start() {
        if (running) return
        running = true
        // Bind every interface so an iPhone on the same Wi‑Fi (Zepp) can pull.
        // 127.0.0.1 is useless when Libre2 Key and Zepp are on different phones.
        server = ServerSocket(PORT, 8)
        acceptThread = Thread({
            while (running) {
                try {
                    val socket = server?.accept() ?: break
                    Thread({ handle(socket) }, "scan-http-req").apply {
                        isDaemon = true
                        start()
                    }
                } catch (_: Exception) {
                    if (!running) break
                }
            }
        }, "scan-http").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        try {
            server?.close()
        } catch (_: Exception) {
        }
        server = null
        acceptThread = null
    }

    private fun handle(socket: Socket) {
        socket.use { sock ->
            sock.soTimeout = 3000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8))
            val request = reader.readLine() ?: return
            val parts = request.split(' ')
            val method = parts.getOrNull(0) ?: ""
            val path = (parts.getOrNull(1) ?: "").substringBefore('?')
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val out = sock.getOutputStream()
            if (method == "OPTIONS") {
                write(out, 204, "No Content", BYTE_EMPTY, "text/plain")
                return
            }
            if (method != "GET" || path != PATH) {
                write(out, 404, "Not Found", BYTE_EMPTY, "text/plain")
                return
            }
            val json = jsonProvider()
            if (json.isNullOrBlank()) {
                write(out, 404, "Not Found", BYTE_EMPTY, "text/plain")
                return
            }
            write(out, 200, "OK", json.toByteArray(StandardCharsets.UTF_8), "application/json")
            onServed()
        }
    }

    private fun write(out: OutputStream, code: Int, reason: String, body: ByteArray, type: String) {
        val header = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(type).append("; charset=utf-8\r\n")
            append("Content-Length: ").append(body.size).append("\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: *\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(header.toByteArray(StandardCharsets.US_ASCII))
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    companion object {
        const val PORT = 17580
        const val PATH = "/scan.json"
        private val BYTE_EMPTY = ByteArray(0)
    }
}
