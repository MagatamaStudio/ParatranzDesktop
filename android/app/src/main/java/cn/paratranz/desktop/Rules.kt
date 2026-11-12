package cn.paratranz.desktop

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

data class Target(val kind: String, val id: Long)

data class SiteUrl(
    val projectId: Long? = null,
    val userId: Long? = null,
    val apiPath: String? = null,
    val pagePath: String? = null,
    val foreign: Boolean = false,
)

// 和 src/shared/pure.js 同源的判定，只保留 Android 壳真正用到的部分：
// 导航拦截、屏蔽墙判定、当前页目标、提醒过滤。页面内的批量过滤仍由注入的引擎负责。
data class Rules(
    val projectIds: Set<Long> = emptySet(),
    val userIds: Set<Long> = emptySet(),
    val userNames: Set<String> = emptySet(),
    val revealProjects: Set<Long> = emptySet(),
    val revealUsers: Set<String> = emptySet(),
    val revealAll: Boolean = false,
    val clearBlockedMail: Boolean = false,
    val showPageFab: Boolean = false,
) {
    val empty: Boolean
        get() = projectIds.isEmpty() && userIds.isEmpty() && userNames.isEmpty()

    fun projectBlocked(id: Any?): Boolean {
        val n = num(id) ?: return false
        if (revealAll || revealProjects.contains(n)) return false
        return projectIds.contains(n)
    }

    fun userEntityBlocked(entity: JSONObject?): Boolean {
        if (entity == null) return false
        val rawId = entity.opt("id") ?: entity.opt("userId")
        val id = num(rawId)
        if (id != null && (revealAll || revealUsers.contains("id:$id"))) return false
        val name = entity.optString("username", "").trim().lowercase()
        if (name.isNotEmpty() && (revealAll || revealUsers.contains("name:$name"))) return false
        if (id != null && userIds.contains(id)) return true
        return name.isNotEmpty() && userNames.contains(name)
    }

    fun userNameBlocked(name: String?): Boolean {
        val n = name?.trim()?.lowercase().orEmpty()
        if (n.isEmpty()) return false
        if (revealAll || revealUsers.contains("name:$n")) return false
        return userNames.contains(n)
    }

    fun userBlockedById(id: Any?): Boolean {
        val n = num(id) ?: return false
        if (n <= 0 || n > 1_000_000_000L) return false
        if (revealAll || revealUsers.contains("id:$n")) return false
        return userIds.contains(n)
    }

    fun targetOfUrl(href: String?): Target? {
        val info = parseSiteUrl(href)
        if (info.foreign || info.apiPath != null) return null
        info.projectId?.let { return Target("project", it) }
        info.userId?.let { return Target("user", it) }
        return null
    }

    fun urlTargetsBlockedProject(href: String?): Long? {
        val info = parseSiteUrl(href)
        info.projectId?.let { if (projectBlocked(it)) return it }
        val api = info.apiPath
        if (api != null) {
            val m = Regex("^/projects/(\\d+)(?:/|$)").find(api)
            if (m != null) {
                val id = m.groupValues[1].toLongOrNull()
                if (id != null && projectBlocked(id)) return id
            }
        }
        return null
    }

    fun pageWallKey(href: String?): String {
        val info = parseSiteUrl(href)
        if (info.foreign) return ""
        info.projectId?.let { if (projectBlocked(it)) return "project:$it" }
        val uid = info.userId
        if (uid != null && userIds.contains(uid) && !revealAll && !revealUsers.contains("id:$uid")) {
            return "user:$uid"
        }
        return ""
    }

    fun stateJson(): JSONObject = JSONObject()
        .put("projectIds", JSONArray(projectIds.sorted()))
        .put("userIds", JSONArray(userIds.sorted()))
        .put("userNames", JSONArray(userNames.sorted()))
        .put("revealAll", revealAll)
        .put("clearBlockedMail", clearBlockedMail)
        .put("showPageFab", showPageFab)
        .put("revealProjectIds", JSONArray(revealProjects.sorted()))
        .put("revealUserKeys", JSONArray(revealUsers.sorted()))

    companion object {
        fun num(v: Any?): Long? {
            val n = when (v) {
                is Number -> v.toLong()
                is String -> v.trim().toLongOrNull()
                else -> null
            } ?: return null
            return n
        }

        fun parseSiteUrl(href: String?): SiteUrl {
            val raw = href?.trim().orEmpty()
            if (raw.isEmpty()) return SiteUrl()
            val uri = try {
                if (raw.startsWith("/")) URI("https://paratranz.cn$raw") else URI(raw)
            } catch (e: Exception) {
                return SiteUrl()
            }
            val host = uri.host.orEmpty()
            if (host != "paratranz.cn" && !host.endsWith(".paratranz.cn")) {
                return SiteUrl(foreign = true)
            }
            val path = uri.path.orEmpty()
            val out = SiteUrl()
            val api = Regex("^/api(/.*)$").find(path)?.groupValues?.get(1)
            val project = Regex("^/api/projects/(\\d+)(?:/|$)").find(path)
                ?: Regex("^/projects/(\\d+)(?:/|$)").find(path)
            val user = Regex("^/api/users/(\\d+)(?:/|$)").find(path)
                ?: Regex("^/users/(\\d+)(?:/|$)").find(path)
            return out.copy(
                projectId = project?.groupValues?.get(1)?.toLongOrNull(),
                userId = user?.groupValues?.get(1)?.toLongOrNull(),
                apiPath = api,
                pagePath = if (api == null) path else null,
            )
        }
    }
}
