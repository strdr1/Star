package com.amsales.vpn.data

import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL

/**
 * Загрузка подписок (список ключей) и парсинг happ://-ссылок.
 *
 * Поддерживаемые форматы:
 *   - vless:// vmess:// trojan:// ss:// hysteria2:// hy2:// tuic://
 *                                           → один или несколько ключей
 *                                              (по одному на строку)
 *   - https://example.com/sub               → подписка (base64 список ИЛИ
 *                                              JSON outbounds[])
 *   - happ://add/<URL>                      → подписка с обёрткой
 *   - happ://add/<base64-encoded URL>       → то же, но закодировано
 *   - happ://crypt5/...                     → не поддерживается (требует
 *                                              реверса формата)
 *
 * Возвращает список найденных ключей (может быть пустым).
 */
object SubscriptionLoader {

    /** How many xhttp keys the last subscription load rebuilt from Happ-format JSON. */
    @Volatile var lastHappXhttp = 0

    /** Парсит вход — либо ключ, либо подписка/happ. Делает сетевой запрос
     *  если требуется. Возвращает список ключей. */
    fun loadOrParse(input: String): Result<List<String>> {
        lastHappXhttp = 0
        val trimmed = input.trim()
        if (trimmed.isEmpty()) return Result.failure(IllegalArgumentException("пустой ввод"))

        // 1) Ключ (или несколько, через пробел/перевод строки)
        if (ProxyKey.looksLikeKey(trimmed)) {
            val keys = trimmed.split(Regex("\\s+")).filter { ProxyKey.parse(it) != null }
            return if (keys.isNotEmpty())
                Result.success(keys)
            else
                Result.failure(IllegalArgumentException("не удалось распознать ключ"))
        }

        // 2) happ://
        if (trimmed.startsWith("happ://", ignoreCase = true)) {
            return parseHapp(trimmed)
        }

        // 3) Голая HTTP-ссылка
        if (trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true)) {
            return loadSubscription(trimmed)
        }

        return Result.failure(IllegalArgumentException(
            "Поддерживаются: vless, vmess, trojan, ss, hysteria2, tuic, happ://add/, ссылка на подписку (https://...)"
        ))
    }

    private fun parseHapp(uri: String): Result<List<String>> {
        val rest = uri.substring(7)   // отрезаем "happ://"
        if (rest.startsWith("add/", ignoreCase = true)) {
            val payload = rest.substring(4)
            // URL внутри
            if (payload.startsWith("http", ignoreCase = true)) {
                return loadSubscription(payload)
            }
            // base64-URL
            try {
                val decoded = Base64.decode(
                    payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
                ).toString(Charsets.UTF_8).trim()
                if (decoded.startsWith("http", ignoreCase = true)) {
                    return loadSubscription(decoded)
                }
                if (ProxyKey.looksLikeKey(decoded)) {
                    return Result.success(listOf(decoded))
                }
            } catch (_: Exception) {}
            return Result.failure(IllegalArgumentException(
                "happ://-ссылка: не удалось расшифровать содержимое"
            ))
        }
        if (rest.startsWith("crypt", ignoreCase = true)) {
            return Result.failure(IllegalArgumentException(
                "happ://crypt-ссылки пока не поддерживаются"
            ))
        }
        return Result.failure(IllegalArgumentException("незнакомый формат happ://"))
    }

    /** Скачивает подписку и парсит её во все ключи которые сможет найти. */
    private fun loadSubscription(url: String): Result<List<String>> {
        return try {
            val conn = URL(url).openConnection()
            // UA клиента v2rayN: панели (Remnawave/Marzban/3x-ui) отдают на него
            // обычный base64-список ссылок, а не JSON/YAML под конкретный клиент.
            conn.setRequestProperty("User-Agent", "v2rayN/7.14.4")
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            val raw = conn.getInputStream().readBytes()

            // Подписки часто закодированы base64 целиком.
            var data = raw
            if (!String(raw).contains("://")) {
                val decoded = ProxyKey.decodeB64(String(raw).trim())
                if (String(decoded).contains("://")) data = decoded
            }
            val text = String(data, Charsets.UTF_8)
            val found = mutableListOf<String>()

            // Plain-список (по строкам)
            text.split('\n', '\r').forEach { line ->
                val s = line.trim()
                if (ProxyKey.looksLikeKey(s) && ProxyKey.parse(s) != null && s !in found) found += s
            }

            // JSON-конфиг (xray/sing-box format от Happ-style сервисов)
            if (found.isEmpty()) {
                try {
                    val obj = if (text.trim().startsWith("[")) {
                        JSONArray(text).optJSONObject(0) ?: JSONObject()
                    } else JSONObject(text)
                    val outbounds = obj.optJSONArray("outbounds") ?: JSONArray()
                    for (i in 0 until outbounds.length()) {
                        val o = outbounds.optJSONObject(i) ?: continue
                        if (o.optString("protocol") != "vless" &&
                            o.optString("type") != "vless") continue
                        val settings = o.optJSONObject("settings") ?: o
                        val vnext = settings.optJSONArray("vnext")
                        if (vnext != null && vnext.length() > 0) {
                            val node = vnext.getJSONObject(0)
                            val host = node.optString("address")
                            val port = node.optInt("port", 443)
                            val users = node.optJSONArray("users")
                            val uuid = users?.optJSONObject(0)?.optString("id") ?: ""
                            if (host.isNotEmpty() && uuid.isNotEmpty()) {
                                found += "vless://$uuid@$host:$port?type=tcp&security=none#sub"
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // Happ-style panels (boltproxy etc.) put the real xhttp settings (uplink placement,
            // post sizes, padding) only into the Xray JSON they serve to Happ; their plain links
            // carry a broken `extra` (server answers 413/502). If the panel serves that JSON,
            // replace the plain xhttp links with links rebuilt from it.
            lastHappXhttp = 0
            happXhttpLinks(url)?.takeIf { it.isNotEmpty() }?.let { fixed ->
                lastHappXhttp = fixed.size
                found.removeAll { ProxyKey.parse(it)?.type == "xhttp" }
                fixed.forEach { if (it !in found) found += it }
            }

            // Детект заглушки (все @0.0.0.0)
            val real = found.filter {
                !it.contains("@0.0.0.0:") && !it.contains("@127.0.0.1:")
            }
            if (found.isNotEmpty() && real.isEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "Подписка не активна или истекла (вернулся ключ-заглушка)"
                ))
            }
            if (real.isEmpty()) {
                return Result.failure(IllegalArgumentException(
                    "В подписке не найдено ключей"
                ))
            }
            Result.success(real)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun enc(s: String) = java.net.URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    /**
     * Fetches the subscription as Happ does and turns every vless/xhttp outbound of the
     * returned Xray configs into a vless:// link (with the full `extra` JSON). Returns null if
     * the panel does not serve Xray JSON to Happ.
     */
    private fun happXhttpLinks(url: String): List<String>? = try {
        val conn = URL(url).openConnection()
        conn.setRequestProperty("User-Agent", "Happ/3.5.0")
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        val text = String(conn.getInputStream().readBytes(), Charsets.UTF_8).trim()
        if (!text.startsWith("[")) null else {
            val configs = JSONArray(text)
            val out = mutableListOf<String>()
            val seen = HashSet<String>()
            // Pass 0: single-server configs (outbound tagged "proxy") keep their own name;
            // pass 1: the rest (balancer configs) add only servers not seen yet.
            for (pass in 0..1) for (i in 0 until configs.length()) {
                val c = configs.optJSONObject(i) ?: continue
                val remarks = c.optString("remarks")
                val obs = c.optJSONArray("outbounds") ?: continue
                for (j in 0 until obs.length()) {
                    val o = obs.optJSONObject(j) ?: continue
                    val isProxy = o.optString("tag") == "proxy"
                    if ((pass == 0) != isProxy || o.optString("protocol") != "vless") continue
                    val ss = o.optJSONObject("streamSettings") ?: continue
                    val net = ss.optString("network")
                    if (net != "xhttp" && net != "splithttp") continue
                    val node = o.optJSONObject("settings")?.optJSONArray("vnext")?.optJSONObject(0) ?: continue
                    val user = node.optJSONArray("users")?.optJSONObject(0) ?: continue
                    val x = ss.optJSONObject("xhttpSettings") ?: ss.optJSONObject("xHTTPSettings") ?: JSONObject()
                    val sec = ss.optString("security", "none")
                    val t = ss.optJSONObject(if (sec == "reality") "realitySettings" else "tlsSettings") ?: JSONObject()
                    val host = node.optString("address"); val port = node.optInt("port", 443)
                    val id = user.optString("id")
                    if (host.isEmpty() || id.isEmpty()) continue
                    val dedup = "$host|$port|${x.optString("path")}|${x.optString("host")}|$id"
                    if (!seen.add(dedup)) continue
                    val q = linkedMapOf(
                        "type" to "xhttp", "encryption" to "none", "security" to sec,
                        "path" to x.optString("path", "/"), "host" to x.optString("host"),
                        "mode" to x.optString("mode", "auto"),
                        "sni" to t.optString("serverName"), "fp" to t.optString("fingerprint"),
                    )
                    t.optJSONArray("alpn")?.let { a -> q["alpn"] = (0 until a.length()).joinToString(",") { a.optString(it) } }
                    if (sec == "reality") { q["pbk"] = t.optString("publicKey"); q["sid"] = t.optString("shortId") }
                    user.optString("flow").takeIf { it.isNotEmpty() }?.let { q["flow"] = it }
                    x.optJSONObject("extra")?.let { q["extra"] = it.toString() }
                    val name = if (isProxy) remarks else "$remarks · ${o.optString("tag")}"
                    out += "vless://$id@$host:$port?" +
                        q.filterValues { it.isNotEmpty() }.entries.joinToString("&") { "${it.key}=${enc(it.value)}" } +
                        "#" + enc(name)
                }
            }
            out
        }
    } catch (_: Exception) { null }
}
