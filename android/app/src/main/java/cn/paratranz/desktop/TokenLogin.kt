package cn.paratranz.desktop

import android.webkit.CookieManager
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

// 与桌面端同一条路：用个人中心那枚 32 位 Token 在客户端会话里换回 sid cookie。
// Android 上没有分账号的会话分区，所以只维护一份登录状态。
object TokenLogin {
    private const val BASE = "https://paratranz.cn"
    private const val TOKEN_URL = "$BASE/oauth/token"
    private const val ACCOUNT_PROBE = "$BASE/api/account"

    data class Result(
        val ok: Boolean,
        val username: String = "",
        val nickname: String = "",
        val error: String = "",
    )

    data class Who(val logged: Boolean, val username: String = "", val nickname: String = "", val error: String = "")

    private class Res(
        val code: Int,
        val text: String,
        val location: String?,
        val setCookies: List<String>,
    )

    fun login(token: String): Result {
        val value = token.trim()
        if (!Regex("^[A-Za-z0-9]{32}$").matches(value)) {
            return Result(false, error = "Token 应该是 32 位字母数字，检查一下有没有漏掉或多了空格")
        }
        val jar = LinkedHashMap<String, String>()
        val allSetCookies = ArrayList<String>()
        var url = TOKEN_URL
        var method = "POST"
        var body: String? = "token=" + URLEncoder.encode(value, "UTF-8")
        var contentType: String? = "application/x-www-form-urlencoded"
        var hops = 0
        while (hops++ < 6) {
            val res = request(url, method, body, contentType, jar)
                ?: return Result(false, error = "请求失败：网络不可用")
            allSetCookies.addAll(res.setCookies)
            rememberCookies(res.setCookies, jar)
            if (res.code in 300..399) {
                val loc = res.location
                if (loc.isNullOrEmpty()) break
                url = if (loc.startsWith("http")) loc else BASE + loc
                method = "GET"
                body = null
                contentType = null
                continue
            }
            break
        }

        val probe = try {
            request(ACCOUNT_PROBE, "GET", null, null, jar)
        } catch (e: Exception) {
            null
        } ?: return Result(false, error = "登录状态校验失败：网络不可用")

        val text = probe.text.trim()
        if (text.isEmpty()) {
            return Result(false, error = "站点没有接受这枚 Token（会话里仍然是未登录）。去个人中心重新生成一枚再试")
        }
        val user = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Result(false, error = "登录状态校验失败：账号接口返回无法解析")
        }
        val username = user.optString("username", "")
        if (username.isEmpty()) return Result(false, error = "登录状态校验失败：账号接口返回里没有用户名")

        applyCookies(allSetCookies)
        return Result(
            ok = true,
            username = username,
            nickname = user.optString("nickname", "").ifEmpty { username },
        )
    }

    fun whoami(): Who {
        val raw = try {
            CookieManager.getInstance().getCookie(BASE)
        } catch (e: Exception) {
            null
        }
        val jar = LinkedHashMap<String, String>()
        raw?.split(';')?.forEach { part ->
            val pair = part.trim()
            val eq = pair.indexOf('=')
            if (eq > 0) jar[pair.substring(0, eq)] = pair
        }
        val res = try {
            request(ACCOUNT_PROBE, "GET", null, null, jar)
        } catch (e: Exception) {
            return Who(false, error = e.message ?: "网络不可用")
        } ?: return Who(false)
        val text = res.text.trim()
        if (text.isEmpty()) return Who(false)
        val user = try {
            JSONObject(text)
        } catch (e: Exception) {
            return Who(false)
        }
        val username = user.optString("username", "")
        return if (username.isEmpty()) {
            Who(false)
        } else {
            Who(true, username, user.optString("nickname", "").ifEmpty { username })
        }
    }

    private fun rememberCookies(setCookies: List<String>, jar: MutableMap<String, String>) {
        for (raw in setCookies) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            jar[pair.substring(0, eq).trim()] = pair
        }
    }

    private fun applyCookies(setCookies: List<String>) {
        if (setCookies.isEmpty()) return
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        for (raw in setCookies) manager.setCookie(BASE + "/", raw)
        manager.flush()
    }

    private fun request(
        url: String,
        method: String,
        body: String?,
        contentType: String?,
        jar: Map<String, String>,
    ): Res? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15_000
                readTimeout = 20_000
                requestMethod = method
                setRequestProperty("accept", "application/json, text/html")
                if (jar.isNotEmpty()) setRequestProperty("Cookie", jar.values.joinToString("; "))
                if (body != null) {
                    doOutput = true
                    if (contentType != null) setRequestProperty("content-type", contentType)
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val location = conn.getHeaderField("Location")
            val setCookies = ArrayList<String>()
            for ((key, values) in conn.headerFields) {
                if (key != null && key.equals("Set-Cookie", true) && values != null) setCookies.addAll(values)
            }
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            Res(code, text, location, setCookies)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }
}
