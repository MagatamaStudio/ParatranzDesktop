package cn.paratranz.desktop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

// 设置与名单落盘到 filesDir/paratranz-desktop.json，结构跟桌面端一致，导出文件可以互导。
// 临时显示只活在内存里：重启后自动恢复屏蔽。
object Store {
    private val lock = Any()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    private var file: File? = null
    private var doc: JSONObject = defaultDoc()
    private var loaded = false

    private var revealAll = false
    private val revealProjects = HashSet<Long>()
    private val revealUsers = HashSet<String>()

    fun init(context: Context) {
        synchronized(lock) {
            if (loaded) return
            file = File(context.filesDir, "paratranz-desktop.json")
            load()
            loaded = true
        }
    }

    fun onChange(fn: () -> Unit) {
        listeners.add(fn)
    }

    fun offChange(fn: () -> Unit) {
        listeners.remove(fn)
    }

    fun snapshotJson(): JSONObject = synchronized(lock) {
        val blocklist = doc.optJSONObject("blocklist") ?: JSONObject()
        JSONObject()
            .put("accounts", doc.optJSONArray("accounts") ?: JSONArray())
            .put("settings", JSONObject(settings().toString()))
            .put("blocklist", JSONObject(blocklist.toString()))
            .put("reveal", JSONObject()
                .put("all", revealAll)
                .put("projectIds", JSONArray(revealProjects.sorted()))
                .put("userKeys", JSONArray(revealUsers.sorted())))
            .put("rulesState", rules().stateJson())
    }

    fun rules(): Rules = synchronized(lock) {
        val blocklist = doc.optJSONObject("blocklist") ?: JSONObject()
        val projects = blocklist.optJSONArray("projects") ?: JSONArray()
        val users = blocklist.optJSONArray("users") ?: JSONArray()

        val projectIds = HashSet<Long>()
        for (i in 0 until projects.length()) {
            val id = Rules.num(projects.optJSONObject(i)?.opt("id"))
            if (id != null && id > 0) projectIds.add(id)
        }
        val userIds = HashSet<Long>()
        val userNames = HashSet<String>()
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            val id = Rules.num(u.opt("id"))
            if (id != null && id > 0) userIds.add(id)
            val name = u.optString("username", "").trim().lowercase()
            if (name.isNotEmpty()) userNames.add(name)
        }
        val s = settings()
        Rules(
            projectIds = projectIds,
            userIds = userIds,
            userNames = userNames,
            revealProjects = HashSet(revealProjects),
            revealUsers = HashSet(revealUsers),
            revealAll = revealAll,
            clearBlockedMail = s.optBoolean("autoClearBlockedMail", false),
            showPageFab = s.optBoolean("showPageFab", false),
        )
    }

    fun settings(): JSONObject = synchronized(lock) {
        doc.optJSONObject("settings") ?: JSONObject()
    }

    fun boolSetting(key: String, def: Boolean): Boolean = settings().optBoolean(key, def)

    fun intSetting(key: String, def: Int): Int {
        val n = settings().optInt(key, def)
        return if (n > 0) n else def
    }

    fun pluginsDisabled(): Set<String> {
        val out = HashSet<String>()
        val arr = settings().optJSONArray("pluginsDisabled") ?: return out
        for (i in 0 until arr.length()) out.add(arr.optString(i))
        return out
    }

    fun setPluginsDisabled(disabled: Set<String>) {
        updateSettings(JSONObject().put("pluginsDisabled", JSONArray(disabled.sorted())))
    }

    fun addProject(entry: JSONObject?): JSONObject = synchronized(lock) {
        val e = entry ?: return err("无效项目 id")
        val id = Rules.num(e.opt("id")) ?: return err("无效项目 id")
        val blocklist = blocklist()
        val projects = blocklist.getJSONArray("projects")
        if (findProject(projects, id) != null) return err("已在名单中")
        projects.put(JSONObject()
            .put("id", id)
            .put("name", e.optString("name", "").take(200))
            .put("url", e.optString("url", "").take(500))
            .put("note", e.optString("note", "").take(500))
            .put("addedAt", System.currentTimeMillis()))
        sortNewestFirst(projects)
        save()
        ok()
    }

    fun removeProject(id: Any?): JSONObject = synchronized(lock) {
        val n = Rules.num(id)
        val projects = blocklist().getJSONArray("projects")
        val next = JSONArray()
        for (i in 0 until projects.length()) {
            val p = projects.optJSONObject(i) ?: continue
            if (n != null && Rules.num(p.opt("id")) == n) continue
            next.put(p)
        }
        blocklist().put("projects", next)
        if (n != null) revealProjects.remove(n)
        save()
        ok()
    }

    fun addUser(entry: JSONObject?): JSONObject = synchronized(lock) {
        val id = Rules.num(entry?.opt("id"))
        val username = entry?.optString("username", "")?.trim().orEmpty()
        if (id == null && username.isEmpty()) return err("需要用户 id 或用户名")
        val users = blocklist().getJSONArray("users")
        if (findUser(users, if (id != null) id else username) != null) return err("已在名单中")
        users.put(JSONObject()
            .put("id", if (id != null && id > 0) id else JSONObject.NULL)
            .put("username", username)
            .put("nickname", entry?.optString("nickname", "")?.take(200) ?: "")
            .put("url", entry?.optString("url", "")?.take(500) ?: "")
            .put("note", entry?.optString("note", "")?.take(500) ?: "")
            .put("addedAt", System.currentTimeMillis()))
        sortNewestFirst(users)
        save()
        ok()
    }

    fun removeUser(key: Any?): JSONObject = synchronized(lock) {
        val target = normalizeUserKey(key)
        val users = blocklist().getJSONArray("users")
        val next = JSONArray()
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            if (userKeyOf(u) == target) continue
            next.put(u)
        }
        blocklist().put("users", next)
        revealUsers.remove(target)
        save()
        ok()
    }

    fun setRevealAll(on: Boolean): JSONObject = synchronized(lock) {
        revealAll = on
        if (!on) {
            revealProjects.clear()
            revealUsers.clear()
        }
        emit()
        snapshotJson()
    }

    fun revealProject(id: Any?): JSONObject = synchronized(lock) {
        val n = Rules.num(id)
        if (n != null && n > 0) {
            revealProjects.add(n)
            emit()
        }
        snapshotJson()
    }

    fun revealUser(key: Any?): JSONObject = synchronized(lock) {
        val target = normalizeUserKey(key)
        if (target.isNotEmpty()) {
            revealUsers.add(target)
            emit()
        }
        snapshotJson()
    }

    fun updateSettings(patch: JSONObject?): JSONObject = synchronized(lock) {
        if (patch != null) {
            val s = settings()
            for (k in patch.keys()) s.put(k, patch.get(k))
        }
        save()
        snapshotJson()
    }

    fun rawExport(): JSONObject = synchronized(lock) {
        val blocklist = doc.optJSONObject("blocklist") ?: JSONObject()
        JSONObject()
            .put("version", 1)
            .put("exportedAt", Instant.now().toString())
            .put("blocklist", JSONObject(blocklist.toString()))
    }

    // 导入与桌面端同构：接受 { blocklist: {...} } 或裸的 { projects, users }。
    fun importBlocklist(parsed: JSONObject): Int = synchronized(lock) {
        val list = parsed.optJSONObject("blocklist") ?: parsed
        val projects = list.optJSONArray("projects")
        val users = list.optJSONArray("users")
        if (projects == null && users == null) return -1
        var added = 0
        if (projects != null) {
            for (i in 0 until projects.length()) {
                if (addProject(projects.optJSONObject(i)).optBoolean("ok", false)) added++
            }
        }
        if (users != null) {
            for (i in 0 until users.length()) {
                if (addUser(users.optJSONObject(i)).optBoolean("ok", false)) added++
            }
        }
        added
    }

    fun userKeyOf(u: JSONObject?): String {
        if (u == null) return ""
        val id = Rules.num(u.opt("id"))
        if (id != null && id > 0) return "id:$id"
        val name = u.optString("username", "").trim().lowercase()
        return if (name.isNotEmpty()) "name:$name" else ""
    }

    private fun normalizeUserKey(key: Any?): String {
        val text = key?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return ""
        if (text.startsWith("id:") || text.startsWith("name:")) return text
        val id = Rules.num(text)
        return if (id != null && id > 0) "id:$id" else "name:${text.lowercase()}"
    }

    private fun findProject(projects: JSONArray, id: Long): JSONObject? {
        for (i in 0 until projects.length()) {
            val p = projects.optJSONObject(i) ?: continue
            if (Rules.num(p.opt("id")) == id) return p
        }
        return null
    }

    private fun findUser(users: JSONArray, key: Any): JSONObject? {
        val target = if (key is Long) "id:$key" else "name:${key.toString().trim().lowercase()}"
        for (i in 0 until users.length()) {
            val u = users.optJSONObject(i) ?: continue
            if (userKeyOf(u) == target) return u
        }
        return null
    }

    private fun sortNewestFirst(arr: JSONArray) {
        if (arr.length() < 2) return
        val list = ArrayList<JSONObject>()
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { list.add(it) }
        list.sortByDescending { it.optLong("addedAt", 0) }
        for (i in 0 until arr.length()) arr.remove(0)
        for (o in list) arr.put(o)
    }

    private fun blocklist(): JSONObject {
        val bl = doc.optJSONObject("blocklist")
        if (bl != null) {
            if (bl.optJSONArray("projects") == null) bl.put("projects", JSONArray())
            if (bl.optJSONArray("users") == null) bl.put("users", JSONArray())
            return bl
        }
        val next = JSONObject().put("projects", JSONArray()).put("users", JSONArray())
        doc.put("blocklist", next)
        return next
    }

    private fun load() {
        val f = file ?: return
        val raw = try {
            if (f.exists()) JSONObject(f.readText(Charsets.UTF_8)) else null
        } catch (e: Exception) {
            null
        }
        val next = defaultDoc()
        if (raw != null) {
            raw.optJSONArray("accounts")?.let { next.put("accounts", it) }
            val bl = raw.optJSONObject("blocklist")
            if (bl != null) {
                val target = next.getJSONObject("blocklist")
                bl.optJSONArray("projects")?.let { target.put("projects", it) }
                bl.optJSONArray("users")?.let { target.put("users", it) }
            }
            val s = raw.optJSONObject("settings")
            if (s != null) {
                val target = next.getJSONObject("settings")
                for (k in s.keys()) target.put(k, s.get(k))
            }
        }
        doc = next
        // 兼容手工编辑过的文件
        blocklist()
        if (settings().optJSONArray("pluginsDisabled") == null) {
            settings().put("pluginsDisabled", JSONArray())
        }
        // 旧版本默认开着右下角悬浮屏蔽按钮，Android 端与桌面端行为对齐后默认关掉；
        // 只迁移一次，之后仍可在屏蔽管理里手动打开
        if (settings().optInt("fabDefaultMigrated", 0) < 1) {
            settings().put("showPageFab", false)
            settings().put("fabDefaultMigrated", 1)
            save()
        }
    }

    private fun save() {
        val f = file ?: return
        try {
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(doc.toString(2), Charsets.UTF_8)
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    f.toPath(),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: Exception) {
                if (f.exists()) f.delete()
                tmp.renameTo(f)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        emit()
    }

    private fun emit() {
        for (fn in listeners) {
            try {
                fn()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun ok() = JSONObject().put("ok", true)

    private fun err(message: String) = JSONObject().put("ok", false).put("error", message)

    private fun defaultDoc(): JSONObject = JSONObject()
        .put("version", 1)
        .put("accounts", JSONArray())
        .put("blocklist", JSONObject().put("projects", JSONArray()).put("users", JSONArray()))
        .put("settings", JSONObject()
            .put("notifications", true)
            .put("notifyIntervalSec", 45)
            .put("closeToTray", true)
            .put("revealScope", "global")
            .put("showPageFab", false)
            .put("autoClearBlockedMail", false)
            .put("pluginsDisabled", JSONArray()))
}
