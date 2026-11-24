package com.amsales.vpn.vpn

import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import java.io.FileDescriptor
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * rawtun client for Android: carries the VPN's UDP (hysteria2) inside IP protocol 136
 * (UDP-Lite) straight to the server.
 *
 * Why: when a server's IP is blocked, the ISP's DPI box drops TCP, UDP and ICMP to it but
 * still passes other IP protocols. On a PC rawtun.exe uses raw sockets for that; an Android
 * app cannot open raw sockets, but it can open an ordinary UDP-Lite datagram socket, which
 * puts the same protocol number on the wire. The server side is the same `rawtun -mode server`.
 *
 *   sing-box (hysteria2) -> UDP 127.0.0.1:[LOCAL_PORT] -> this class == ip proto 136 ==> server
 *
 * The phone's kernel verifies the UDP-Lite checksum of every reply, and NAT boxes either fix
 * that checksum when rewriting addresses or do not. So on start we ask the server for a reply
 * in both variants ("RTUNH"), see which one arrives, and tell the server to use it ("RTUNM").
 *
 * The socket is protect()ed, so it bypasses our own TUN.
 */
class RawtunClient(private val vpn: VpnService, private val serverIp: String) {

    companion object {
        const val LOCAL_PORT = 44300
        private const val TAG = "Rawtun"
        private const val IPPROTO_UDPLITE = 136
        private const val CONTROL_PORT = 7     // server: control packets
        private const val DATA_PORT = 443      // server: tunnelled datagrams
        private const val MODE_STANDARD = 0    // NAT fixes the checksum itself
        private const val MODE_COMPENSATED = 1 // server pre-computes it for our private address
    }

    private var sock: FileDescriptor? = null
    private var local: DatagramSocket? = null
    @Volatile private var running = false
    @Volatile private var peer: SocketAddress? = null   // sing-box's UDP source port
    @Volatile private var gotStandard = false
    @Volatile private var gotCompensated = false
    private var helloLatch = CountDownLatch(1)

    /** Starts the tunnel. Returns null on success, otherwise a message for the user. */
    fun start(): String? {
        val server = InetAddress.getByName(serverIp)   // IPv4 literal: no DNS lookup
        val s = try {
            Os.socket(OsConstants.AF_INET, OsConstants.SOCK_DGRAM, IPPROTO_UDPLITE)
        } catch (e: ErrnoException) {
            Log.w(TAG, "socket(UDPLITE): ${e.message}")
            return "Телефон не даёт открыть сокет UDP-Lite (${e.message}). Прямой обход на этом устройстве недоступен."
        }
        sock = s
        try {
            // protect() so the UDP-Lite socket bypasses our own TUN. Use the raw int fd
            // (getInt$ via reflection on the FileDescriptor) and, as a fallback, a dup.
            val rawFd = try {
                val m = FileDescriptor::class.java.getDeclaredMethod("getInt\$").apply { isAccessible = true }
                m.invoke(s) as Int
            } catch (_: Exception) { -1 }
            val protectedOk = when {
                rawFd >= 0 -> vpn.protect(rawFd)
                else -> ParcelFileDescriptor.dup(s).use { vpn.protect(it.fd) }
            }
            Log.i(TAG, "UDP-Lite socket fd=$rawFd protect=$protectedOk")

            // Bind the local port FIRST, so sing-box can connect even while we probe the server.
            val l = DatagramSocket(null)
            l.reuseAddress = true
            l.bind(InetSocketAddress(InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)), LOCAL_PORT))
            local = l
            Log.i(TAG, "listening on 127.0.0.1:$LOCAL_PORT")

            val localIp = localAddressTowards(server)
                ?: return fail("Не удалось определить адрес телефона в сети.")
            val ipBytes = localIp.address

            running = true
            thread(name = "rawtun-down", isDaemon = true) { downlink(s) }

            // Ask for both checksum variants, up to 5 times (~3.5 s total).
            val hello = "RTUNH".toByteArray() + ipBytes
            for (i in 0 until 5) {
                Os.sendto(s, hello, 0, hello.size, 0, server, CONTROL_PORT)
                if (helloLatch.await(700, TimeUnit.MILLISECONDS)) break
            }
            if (!gotStandard && !gotCompensated) {
                return fail("Сеть не пропускает протокол UDP-Lite до сервера $serverIp. " +
                    "Через мобильный интернет так обычно и бывает — попробуйте Wi-Fi или другой сервер.")
            }
            // Give the second variant a moment, then prefer the standard one.
            Thread.sleep(150)
            val mode = if (gotStandard) MODE_STANDARD else MODE_COMPENSATED
            val modeMsg = "RTUNM".toByteArray() + byteArrayOf(mode.toByte()) + ipBytes
            Os.sendto(s, modeMsg, 0, modeMsg.size, 0, server, CONTROL_PORT)
            Log.i(TAG, "server $serverIp reachable over UDP-Lite, checksum mode $mode, local $localIp")

            thread(name = "rawtun-up", isDaemon = true) { uplink(s, l, server) }
            // The server keeps the mode per (address, port); repeat it so a NAT rebinding
            // (new source port) does not silently fall back to the standard checksum.
            thread(name = "rawtun-keep", isDaemon = true) {
                while (running) {
                    try { Thread.sleep(15_000) } catch (_: InterruptedException) { break }
                    try { Os.sendto(s, modeMsg, 0, modeMsg.size, 0, server, CONTROL_PORT) } catch (_: Exception) {}
                }
            }
            return null
        } catch (e: Exception) {
            Log.w(TAG, "start failed", e)
            return fail("Не удалось запустить прямой обход: ${e.message}")
        }
    }

    fun stop() {
        running = false
        try { local?.close() } catch (_: Exception) {}
        local = null
        sock?.let { try { Os.close(it) } catch (_: Exception) {} }
        sock = null
    }

    private fun fail(msg: String): String {
        stop()
        return msg
    }

    /** Our address on the underlying (non-VPN) network: a protected UDP socket "connected" to the server. */
    private fun localAddressTowards(server: InetAddress): InetAddress? = try {
        DatagramSocket().use { d ->
            vpn.protect(d)
            d.connect(server, DATA_PORT)
            d.localAddress.takeIf { it.address.size == 4 && !it.isAnyLocalAddress }
        }
    } catch (e: Exception) {
        Log.w(TAG, "local address: ${e.message}")
        null
    }

    /** sing-box -> server. */
    private fun uplink(s: FileDescriptor, l: DatagramSocket, server: InetAddress) {
        val buf = ByteArray(65535)
        val pkt = DatagramPacket(buf, buf.size)
        while (running) {
            try {
                pkt.setLength(buf.size)
                l.receive(pkt)
                peer = pkt.socketAddress
                Os.sendto(s, buf, 0, pkt.length, 0, server, DATA_PORT)
            } catch (e: Exception) {
                if (running) Log.w(TAG, "uplink: ${e.message}")
            }
        }
    }

    /** server -> sing-box, plus control replies. poll() with a timeout so stop() can end the loop. */
    private fun downlink(s: FileDescriptor) {
        val buf = ByteArray(65535)
        val from = InetSocketAddress(0)
        val pfd = StructPollfd().apply { fd = s; events = OsConstants.POLLIN.toShort() }
        val fds = arrayOf(pfd)
        while (running) {
            try {
                if (Os.poll(fds, 500) <= 0) continue
                val n = Os.recvfrom(s, buf, 0, buf.size, 0, from)
                if (n <= 0) continue
                if (from.port == CONTROL_PORT) {
                    if (n >= 5 && buf[0] == 'R'.code.toByte() && buf[3] == 'N'.code.toByte()) {
                        when (buf[4].toInt().toChar()) {
                            'A' -> { gotStandard = true; helloLatch.countDown() }
                            'B' -> { gotCompensated = true; helloLatch.countDown() }
                        }
                    }
                    continue
                }
                val p = peer ?: continue
                local?.send(DatagramPacket(buf, n, p))
            } catch (e: Exception) {
                if (running) Log.w(TAG, "downlink: ${e.message}")
            }
        }
    }
}
