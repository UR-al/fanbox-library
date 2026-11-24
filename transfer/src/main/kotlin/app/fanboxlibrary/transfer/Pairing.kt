package app.fanboxlibrary.transfer

import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.Base64
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

class PairingAddress private constructor(
    val address: InetAddress, val port: Int, internal val pin: ByteArray, internal val secret: ByteArray,
) {
    fun encode(): String = listOf("FL1", address.hostAddress, port.toString(), encode(pin), encode(secret)).joinToString(" ")

    companion object {
        private fun encode(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        internal fun create(address: InetAddress, port: Int, certificate: X509Certificate): PairingAddress {
            require(privateIpv4(address))
            return PairingAddress(address, port, MessageDigest.getInstance("SHA-256").digest(certificate.encoded),
                ByteArray(32).also { SecureRandom().nextBytes(it) })
        }
        fun parse(code: String): PairingAddress {
            val parts = code.trim().split(Regex("\\s+"))
            require(parts.size == 5 && parts[0] == "FL1") { "PC에 표시된 연결 코드를 전부 붙여 넣으세요." }
            val octets = parts[1].split('.')
            require(octets.size == 4 && octets.all { it.matches(Regex("[0-9]{1,3}")) && it.toInt() in 0..255 })
            val address = InetAddress.getByAddress(octets.map { it.toInt().toByte() }.toByteArray())
            require(privateIpv4(address)) { "같은 Wi-Fi 네트워크의 로컬 IP 주소를 사용하세요." }
            val port = parts[2].toInt()
            require(port in 1..65535)
            fun bytes(i: Int): ByteArray {
                require(parts[i].length == 43)
                return Base64.getUrlDecoder().decode(parts[i]).also { require(it.size == 32) }
            }
            return PairingAddress(address, port, bytes(3), bytes(4))
        }
    }
}

private fun privateIpv4(address: InetAddress): Boolean {
    if (address !is Inet4Address || address.isAnyLocalAddress) return false
    return address.isLoopbackAddress || address.isSiteLocalAddress || address.isLinkLocalAddress
}

fun localAddresses(): List<InetAddress> = NetworkInterface.getNetworkInterfaces().toList()
    .filter { it.isUp && !it.isLoopback }.flatMap { it.inetAddresses.toList() }
    .filter { privateIpv4(it) && !it.isLoopbackAddress }.distinctBy { it.hostAddress }
    .sortedByDescending { it.isSiteLocalAddress }

internal fun PairingAddress.connect(): SSLSocket {
    val trust = object : X509TrustManager {
        override fun getAcceptedIssuers() = emptyArray<X509Certificate>()
        override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
            throw CertificateException("Client certificate unexpected")
        }
        override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
            val leaf = chain.firstOrNull() ?: throw CertificateException("Missing certificate")
            leaf.checkValidity()
            if (!MessageDigest.isEqual(MessageDigest.getInstance("SHA-256").digest(leaf.encoded), pin))
                throw CertificateException("PC 인증서가 연결 코드와 일치하지 않습니다.")
        }
    }
    val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
    val socket = context.socketFactory.createSocket() as SSLSocket
    try {
        socket.soTimeout = TIMEOUT_MS
        socket.enabledProtocols = socket.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        socket.connect(InetSocketAddress(address, port), 8000)
        socket.startHandshake()
        return socket
    } catch (e: Exception) {
        socket.close()
        throw e
    }
}

internal const val TIMEOUT_MS = 180_000
