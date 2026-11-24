package app.fanboxlibrary.transfer

import app.fanboxlibrary.shared.LibraryPost
import app.fanboxlibrary.shared.LibraryStore
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread

class PairHost(
    bind: InetAddress,
    context: SSLContext,
    certificate: X509Certificate,
    private val store: LibraryStore,
    posts: Collection<LibraryPost>,
    private val allowReceiving: Boolean,
    private val onEvent: (String) -> Unit = {},
) : Closeable {
    private val selected = posts.associateBy { it.id }
    private val server = (context.serverSocketFactory.createServerSocket() as SSLServerSocket).apply {
        enabledProtocols = supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        bind(InetSocketAddress(bind, 0), 8)
    }
    val pairing = PairingAddress.create(bind, server.localPort, certificate)
    private val active = mutableSetOf<SSLSocket>()
    private val expiry = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "pair-expiry").apply { isDaemon = true } }
    init {
        expiry.schedule({ close(); onEvent("연결 코드가 만료되었습니다.") }, 30, TimeUnit.MINUTES)
        thread(isDaemon = true, name = "pair-listener") {
            try {
                while (!server.isClosed) {
                    val socket = server.accept() as SSLSocket
                    val accepted = synchronized(active) {
                        !server.isClosed && active.size < 4 && active.add(socket)
                    }
                    if (!accepted) { socket.close(); continue }
                    thread(isDaemon = true, name = "pair-transfer") {
                        try { socket.use(::serve) }
                        catch (_: Exception) { if (!server.isClosed) onEvent("연결이 끊겼습니다. 같은 작품을 다시 선택해 이어받을 수 있습니다.") }
                        finally { synchronized(active) { active.remove(socket) } }
                    }
                }
            } catch (_: java.io.IOException) {
                if (!server.isClosed) { close(); onEvent("연결이 종료되었습니다.") }
            }
        }
    }
    private fun serve(socket: SSLSocket) {
        socket.soTimeout = 8000
        val c = Channel(socket)
        if (c.input.readUTF() != "FBL1") return
        val token = ByteArray(32).also { c.input.readFully(it) }
        val op = c.input.readUnsignedByte()
        val allowed = MessageDigest.isEqual(pairing.secret, token) &&
            (op == LIST || op == DETAILS || op == DIGEST || op == DOWNLOAD || op == UPLOAD && allowReceiving)
        c.output.writeBoolean(allowed); c.output.flush()
        if (!allowed) return
        socket.soTimeout = TIMEOUT_MS
        when (op) {
            LIST -> c.list(selected.values)
            DETAILS -> c.files(selected[c.input.readUTF()] ?: return)
            DIGEST -> c.files(selected[c.input.readUTF()] ?: return, withDigests = true)
            DOWNLOAD -> {
                val post = selected[c.input.readUTF()] ?: return
                c.send(c.selected(post))
                onEvent("폰으로 보냄 · ${post.title}")
            }
            UPLOAD -> {
                val result = c.receive(store)
                onEvent("PC에서 받음 · 파일 ${result.added}개 추가")
            }
        }
    }
    override fun close() {
        server.close()
        synchronized(active) { active.forEach { runCatching { it.close() } } }
        expiry.shutdownNow()
    }
}
