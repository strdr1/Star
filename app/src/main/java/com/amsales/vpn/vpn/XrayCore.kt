package com.amsales.vpn.vpn

import android.content.Context
import android.net.VpnService
import android.util.Log
import com.amsales.vpn.data.ProxyKey
import io.nekohasekai.libv2ray.CoreCallbackHandler
import io.nekohasekai.libv2ray.CoreController
import io.nekohasekai.libv2ray.Libv2ray
import org.json.JSONArray
import org.json.JSONObject

private fun jsonArrayOf(items: List<String>) = JSONArray().apply { items.forEach { put(it) } }

/**
 * Xray core (libv2ray) as a local SOCKS proxy, for key types sing-box cannot do — chiefly
 * xhttp with Xray-specific extra/xmux settings (the same engine Happ and v2rayNG use).
 *
 *   sing-box (TUN, outbound=socks) -> 127.0.0.1:LOCAL_PORT -> [Xray] -> vless/xhttp server
 *
 * Xray handles the exotic transport; sing-box keeps the TUN and routing. Xray is started
 * before sing-box (like the rawtun wrapper) and stopped with it.
 */
class XrayCore(private val vpn: VpnService) {

    companion object {
        const val LOCAL_PORT = 10853
        private const val TAG = "XrayCore"
        private var envReady = false
    }

    private var controller: CoreController? = null

    /** Build an Xray client JSON from a parsed key (vless only; the type that needs Xray). */
    fun configFor(k: ProxyKey): String {
        val stream = JSONObject().put("network", k.type).put("security", k.security)
        when (k.security) {
            "tls" -> stream.put("tlsSettings", JSONObject()
                .put("serverName", k.sni.ifEmpty { k.host })
                .put("fingerprint", k.fp.ifEmpty { "chrome" })
                .apply { if (k.alpn.isNotEmpty()) put("alpn", jsonArrayOf(k.alpn)) })
            "reality" -> stream.put("realitySettings", JSONObject()
                .put("serverName", k.sni.ifEmpty { k.host })
                .put("fingerprint", k.fp.ifEmpty { "chrome" })
                .put("publicKey", k.pbk).put("shortId", k.sid).put("spiderX", k.spx.ifEmpty { "/" }))
        }
        when (k.type) {
            "xhttp" -> stream.put("xhttpSettings", JSONObject()
                .put("path", k.path.ifEmpty { "/" })
                .put("host", k.hostHeader.ifEmpty { k.host })
                .put("mode", k.xhttpMode.ifEmpty { "auto" })
                .apply { if (k.xhttpExtra.isNotEmpty()) try { put("extra", JSONObject(k.xhttpExtra)) } catch (_: Exception) {} })
            "ws" -> stream.put("wsSettings", JSONObject().put("path", k.path.ifEmpty { "/" })
                .put("headers", JSONObject().put("Host", k.hostHeader.ifEmpty { k.host })))
            "grpc" -> stream.put("grpcSettings", JSONObject().put("serviceName", k.serviceName))
        }

        val user = JSONObject().put("id", k.uuid).put("encryption", "none")
        if (k.flow.isNotEmpty()) user.put("flow", k.flow)
        val outbound = JSONObject()
            .put("protocol", "vless")
            .put("settings", JSONObject().put("vnext", JSONArray().put(JSONObject()
                .put("address", k.host).put("port", k.port)
                .put("users", JSONArray().put(user)))))
            .put("streamSettings", stream)

        return JSONObject()
            .put("log", JSONObject().put("loglevel", "info"))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("listen", "127.0.0.1").put("port", LOCAL_PORT).put("protocol", "socks")
                .put("settings", JSONObject().put("udp", true))))
            .put("outbounds", JSONArray().put(outbound))
            .toString()
    }

    /** Starts Xray with the given key. Returns null on success, else a message. */
    fun start(k: ProxyKey): String? {
        try {
            if (!envReady) {
                // Xray needs a writable dir for its env; no geo files are used for a plain proxy.
                Libv2ray.initCoreEnv((vpn as Context).filesDir.absolutePath, "")
                envReady = true
            }
            val cb = object : CoreCallbackHandler {
                override fun startup(): Long = 0
                override fun shutdown(): Long = 0
                override fun onEmitStatus(code: Long, msg: String?): Long {
                    if (!msg.isNullOrEmpty()) Log.i(TAG, "xray: $msg"); return 0
                }
            }
            val c = Libv2ray.newCoreController(cb)
            // Note: libv2ray-lite does not protect Xray's own sockets, so its connection to the
            // server would loop back into our TUN. sing-box instead routes that server's host
            // direct (see SingBoxConfig). No ProcessFinder needed (it is only for stats).
            val json = configFor(k)
            c.startLoop(json, 0)  // 2nd arg is a TUN fd for a tun inbound; we use SOCKS, so none
            controller = c
            Log.i(TAG, "started on socks 127.0.0.1:$LOCAL_PORT for ${k.host}")
            return null
        } catch (e: Throwable) {
            Log.w(TAG, "start failed", e)
            return "Не удалось запустить Xray-ядро: ${e.message}"
        }
    }

    fun stop() {
        try { controller?.stopLoop() } catch (_: Exception) {}
        controller = null
    }
}
