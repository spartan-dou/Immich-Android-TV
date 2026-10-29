package nl.giejay.android.tv.immich.auth

import timber.log.Timber
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.net.URLDecoder
import java.security.SecureRandom
import kotlin.concurrent.thread

/**
 * Minimal HTTP server serving a sign-in page to a phone on the same network.
 * It only runs while the local sign-in screen is visible and the sign-in page only answers on a
 * random, unguessable path, so credentials never go through a third-party server.
 *
 * SSO: the identity provider redirects the phone back to [callbackUrl], which must be allowed as
 * a redirect URI for Immich's OAuth client. The port is fixed so that URI doesn't change.
 */
class LocalLoginServer(
    private val texts: PageTexts,
    private val defaultHostName: String,
    private val onPasswordSignIn: (LoginForm) -> LoginResult,
    private val onStartSso: (hostName: String, redirectUri: String) -> SsoStart,
    private val onSsoCallback: (callbackUrl: String, params: Map<String, String>) -> LoginResult
) {
    data class LoginForm(val hostName: String, val email: String, val password: String)

    data class LoginResult(val success: Boolean, val message: String)

    data class SsoStart(val redirectUrl: String?, val error: String?)

    data class PageTexts(
        val title: String,
        val serverUrl: String,
        val ssoButton: String,
        val orPassword: String,
        val email: String,
        val password: String,
        val submit: String,
        val localNetworkNotice: String,
        val ssoRedirectNotice: String,
        val tooManyAttempts: String
    )

    private val token = randomToken()
    private var serverSocket: ServerSocket? = null
    private var baseUrl: String? = null
    @Volatile
    private var attempts = 0

    val callbackUrl: String? get() = baseUrl?.let { "$it$CALLBACK_PATH" }

    fun start(): String? {
        val address = localIpv4Address() ?: return null
        val socket = ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(PORT))
        }
        serverSocket = socket
        baseUrl = "http://$address:$PORT"
        thread(name = "local-login-server", isDaemon = true) {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { handle(it) }
                } catch (e: SocketException) {
                    // stop() closed the socket
                } catch (e: Exception) {
                    Timber.w(e, "Local sign-in request failed")
                }
            }
        }
        return "$baseUrl/$token"
    }

    fun stop() {
        serverSocket?.close()
        serverSocket = null
    }

    private fun handle(client: Socket) {
        client.soTimeout = SOCKET_TIMEOUT_MS
        val input = BufferedInputStream(client.getInputStream())
        val requestLine = readLine(input) ?: return
        val headers = generateSequence { readLine(input)?.takeIf { it.isNotEmpty() } }
            .mapNotNull { line -> line.split(":", limit = 2).takeIf { it.size == 2 } }
            .associate { (name, value) -> name.trim().lowercase() to value.trim() }

        val (method, target) = requestLine.split(" ").let { it.getOrNull(0) to it.getOrNull(1).orEmpty() }
        val path = target.substringBefore('?')
        val query = parseForm(target.substringAfter('?', ""))

        when {
            path == "/$token" && method == "GET" -> respond(client, 200, formPage(defaultHostName, null))
            path == "/$token" && method == "POST" -> {
                val length = headers["content-length"]?.toIntOrNull() ?: 0
                if (length !in 0..MAX_BODY_BYTES) {
                    respond(client, 413, "Request too large")
                    return
                }
                if (tooManyAttempts(client)) return
                val form = parseForm(String(readBytes(input, length), Charsets.UTF_8))
                val loginForm = LoginForm(form["host"].orEmpty(), form["email"].orEmpty(), form["password"].orEmpty())
                val result = onPasswordSignIn(loginForm)
                respond(client, 200, if (result.success) messagePage(result.message) else formPage(loginForm.hostName, result))
            }
            path == "/$token/sso" && method == "GET" -> {
                if (tooManyAttempts(client)) return
                val hostName = query["host"].orEmpty()
                val start = onStartSso(hostName, callbackUrl!!)
                if (start.redirectUrl != null) {
                    respond(client, 302, "", start.redirectUrl)
                } else {
                    respond(client, 200, formPage(hostName, LoginResult(false, start.error.orEmpty())))
                }
            }
            // Fixed path: only acts on the state of an SSO sign-in started from the secret path
            path == CALLBACK_PATH && method == "GET" -> {
                val result = onSsoCallback("$baseUrl$target", query)
                respond(client, 200, if (result.success) messagePage(result.message) else formPage(defaultHostName, result))
            }
            path == "/$token" || path == "/$token/sso" || path == CALLBACK_PATH -> respond(client, 405, "Method not allowed")
            else -> respond(client, 404, "Not found")
        }
    }

    private fun tooManyAttempts(client: Socket): Boolean {
        if (++attempts <= MAX_ATTEMPTS) return false
        respond(client, 429, messagePage(texts.tooManyAttempts))
        stop()
        return true
    }

    private fun formPage(hostName: String, error: LoginResult?): String = page(
        """
        <h1>${escape(texts.title)}</h1>
        ${error?.let { "<p class=\"error\">${escape(it.message)}</p>" } ?: ""}
        <label>${escape(texts.serverUrl)}<input id="host" type="url" required value="${escape(hostName)}" placeholder="https://immich.example.com"></label>
        <form method="get" action="/$token/sso" onsubmit="this.host.value = document.getElementById('host').value">
          <input type="hidden" name="host">
          <button type="submit">${escape(texts.ssoButton)}</button>
        </form>
        <h2>${escape(texts.orPassword)}</h2>
        <form method="post" action="/$token" autocomplete="on" onsubmit="this.host.value = document.getElementById('host').value">
          <input type="hidden" name="host">
          <label>${escape(texts.email)}<input name="email" type="email" required autocomplete="username"></label>
          <label>${escape(texts.password)}<input name="password" type="password" required autocomplete="current-password"></label>
          <button type="submit" class="secondary">${escape(texts.submit)}</button>
        </form>
        <p class="notice">${escape(texts.localNetworkNotice)}</p>
        <p class="notice">${escape(texts.ssoRedirectNotice)}<br><code>${escape(callbackUrl.orEmpty())}</code></p>
        """
    )

    private fun messagePage(message: String): String = page("<h1>${escape(texts.title)}</h1><p>${escape(message)}</p>")

    private fun page(body: String): String = """
        <!doctype html>
        <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
        <title>${escape(texts.title)}</title>
        <style>
          body { font-family: sans-serif; max-width: 420px; margin: 0 auto; padding: 24px 16px; background: #f5f5f5; color: #222; }
          h2 { font-size: 16px; margin-top: 32px; color: #555; }
          label { display: block; margin: 16px 0 4px; font-weight: bold; }
          input { display: block; width: 100%; box-sizing: border-box; margin-top: 4px; padding: 12px; font-size: 16px; border: 1px solid #bbb; border-radius: 8px; }
          button { margin-top: 16px; width: 100%; padding: 14px; font-size: 16px; border: 0; border-radius: 8px; background: #4250af; color: #fff; }
          button.secondary { background: #666; }
          code { word-break: break-all; }
          .error { color: #b00020; }
          .notice { margin-top: 24px; font-size: 13px; color: #666; }
        </style></head><body>$body</body></html>
    """.trimIndent()

    private fun respond(client: Socket, status: Int, body: String, location: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $status ${STATUS_TEXT[status] ?: "OK"}\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            (location?.let { "Location: $it\r\n" } ?: "") +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Connection: close\r\n\r\n"
        client.getOutputStream().apply {
            write(head.toByteArray(Charsets.UTF_8))
            write(bytes)
            flush()
        }
    }

    private fun readLine(input: InputStream): String? {
        val buffer = ByteArrayOutputStream()
        while (true) {
            val byte = input.read()
            if (byte == -1) return if (buffer.size() == 0) null else buffer.toString("ISO-8859-1")
            if (byte == '\n'.code) return buffer.toString("ISO-8859-1").trimEnd('\r')
            if (buffer.size() >= MAX_LINE_BYTES) return null
            buffer.write(byte)
        }
    }

    private fun readBytes(input: InputStream, length: Int): ByteArray {
        val bytes = ByteArray(length)
        var read = 0
        while (read < length) {
            val count = input.read(bytes, read, length - read)
            if (count == -1) break
            read += count
        }
        return bytes.copyOf(read)
    }

    private fun parseForm(body: String): Map<String, String> = body.split("&")
        .mapNotNull { it.split("=", limit = 2).takeIf { pair -> pair.size == 2 } }
        .associate { (key, value) -> URLDecoder.decode(key, "UTF-8") to URLDecoder.decode(value, "UTF-8") }

    private fun escape(value: String): String = value
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\"", "&quot;").replace("'", "&#39;")

    companion object {
        const val PORT = 47913
        const val CALLBACK_PATH = "/oauth-callback"
        private const val SOCKET_TIMEOUT_MS = 10_000
        private const val MAX_BODY_BYTES = 16 * 1024
        private const val MAX_LINE_BYTES = 8 * 1024
        private const val MAX_ATTEMPTS = 10
        private val STATUS_TEXT = mapOf(
            200 to "OK", 302 to "Found", 404 to "Not Found", 405 to "Method Not Allowed",
            413 to "Payload Too Large", 429 to "Too Many Requests"
        )

        private fun randomToken(): String =
            ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

        // Ethernet or Wi-Fi address the phone can reach on the local network
        fun localIpv4Address(): String? = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback && !it.isVirtual }
            .flatMap { it.inetAddresses.toList() }
            .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
            ?.hostAddress
    }
}
