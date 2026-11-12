package cn.paratranz.desktop

import org.json.JSONArray
import org.json.JSONObject

// src/shared/notify.js 的 Kotlin 版：提醒接口的返回结构做了兼容取值，
// 只认「发信人」判定自己发出的消息，收信人字段不参与，避免把自己发的也提醒一遍。
object NotifyShape {
    fun items(value: Any?): List<JSONObject> {
        if (value is JSONArray) return objects(value)
        val obj = value as? JSONObject ?: return emptyList()
        for (key in listOf("results", "data", "items", "list", "rows", "records")) {
            val arr = obj.optJSONArray(key)
            if (arr != null) return objects(arr)
        }
        return emptyList()
    }

    fun keyOf(item: JSONObject, index: Int): String {
        val id = idOf(item, index)
        val stamp = firstString(item, listOf("updatedAt", "lastAt", "lastTime", "sentAt", "createdAt", "time", "date"))
        val body = firstString(item, listOf("lastContent", "lastMessage", "content", "body", "text", "message", "subject", "title"))
        val digest = if (body.isNotEmpty()) body.length.toString() + ":" + hash(body.take(120)) else ""
        if (stamp.isEmpty() && digest.isEmpty()) return id
        return id + "|" + stamp + "|" + digest
    }

    fun shouldSkip(item: JSONObject, rules: Rules): Boolean {
        for (id in projectIdsOf(item)) if (rules.projectBlocked(id)) return true
        for (actor in actors(item)) if (rules.userEntityBlocked(actor)) return true
        for (raw in actorIds(item)) {
            val n = Rules.num(raw) ?: continue
            if (n in 1..1_000_000_000L && rules.userEntityBlocked(JSONObject().put("id", n))) return true
        }
        return false
    }

    fun isSelfAuthored(item: JSONObject, myId: Long?): Boolean {
        val mine = myId ?: return false
        for (key in listOf("from", "fromUser", "sender", "author", "creator")) {
            if (!item.has(key) || item.isNull(key)) continue
            val v = item.opt(key)
            val id = if (v is JSONObject) Rules.num(v.opt("id")) else Rules.num(v)
            if (id == mine) return true
        }
        return false
    }

    fun titleOf(item: JSONObject, feedId: String): String {
        val project = item.optString("projectName", "")
            .ifEmpty { item.optJSONObject("project")?.optString("name", "").orEmpty() }
        val who = actors(item).firstNotNullOfOrNull {
            it.optString("nickname", "").ifEmpty { it.optString("username", "") }
        }.orEmpty()
        val parts = ArrayList<String>()
        if (project.isNotEmpty()) parts.add("[$project]")
        if (who.isNotEmpty()) parts.add(who)
        parts.add(KIND_LABEL[feedId] ?: "新消息")
        return parts.joinToString(" ")
    }

    fun bodyOf(item: JSONObject): String {
        when (val content = item.opt("content")) {
            is String -> if (content.isNotBlank()) return flatten(content)
            is JSONObject -> {
                for (key in listOf("text", "content", "body", "title")) {
                    val v = content.optString(key, "")
                    if (v.isNotBlank()) return flatten(v)
                }
            }
        }
        for (key in listOf("title", "subject", "body", "text", "message")) {
            val v = item.optString(key, "")
            if (v.isNotBlank()) return flatten(v)
        }
        val link = item.optJSONObject("link")
        val linkTitle = link?.optString("title", "").orEmpty()
        return if (linkTitle.isNotBlank()) flatten(linkTitle) else ""
    }

    // 开关打开时：只处理「我收到的、未读、发信人被屏蔽」的行，返回要标已读的对方 uid
    fun blockedUnreadConversations(items: List<JSONObject>, rules: Rules, myId: Long?): List<Long> {
        val out = LinkedHashSet<Long>()
        for (item in items) {
            if (myId != null && Rules.num(item.opt("to")) != myId) continue
            if (item.optInt("status", 0) == 1) continue
            val fromUser = item.optJSONObject("fromUser")
            val sender = Rules.num(fromUser?.opt("id"))
                ?: Rules.num(item.optJSONObject("user")?.opt("id"))
                ?: Rules.num(item.opt("from"))
                ?: Rules.num(item.opt("uid"))
                ?: continue
            val probe = JSONObject().put("id", sender)
            fromUser?.optString("username", "")?.let { if (it.isNotEmpty()) probe.put("username", it) }
            if (rules.userEntityBlocked(probe)) out.add(sender)
        }
        return out.toList()
    }

    private fun objects(arr: JSONArray): List<JSONObject> {
        val out = ArrayList<JSONObject>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { out.add(it) }
        return out
    }

    private fun idOf(item: JSONObject, index: Int): String {
        if (item.has("id")) {
            val v = item.opt("id")
            if (v is Number || v is String) return v.toString()
        }
        return "idx-$index"
    }

    private fun firstString(item: JSONObject, keys: List<String>): String {
        for (key in keys) {
            val v = item.opt(key) ?: continue
            if (v is String && v.isNotBlank()) return v.trim()
            if (v is Number) return v.toString()
        }
        return ""
    }

    private fun hash(text: String): String {
        var h = 5381
        for (ch in text) h = ((h * 33) xor ch.code) and 0xFFFFFFFF.toInt()
        return Integer.toUnsignedString(h, 36)
    }

    private fun actors(item: JSONObject): List<JSONObject> {
        val out = ArrayList<JSONObject>()
        for (key in listOf("fromUser", "user", "toUser", "from", "author", "creator", "sender")) {
            item.optJSONObject(key)?.let { out.add(it) }
        }
        if (item.optString("username", "").isNotEmpty()) out.add(item)
        return out
    }

    private fun actorIds(item: JSONObject): List<Any> {
        val out = ArrayList<Any>()
        for (key in listOf("from", "uid", "userId", "user_id")) {
            if (item.has(key)) item.opt(key)?.let { out.add(it) }
        }
        for (actor in actors(item)) {
            val id = actor.opt("id")
            if (id is Number || (id is String && id.toLongOrNull() != null)) out.add(id)
        }
        return out
    }

    private fun projectIdsOf(item: JSONObject): List<Any> {
        val out = ArrayList<Any>()
        val re = Regex("^project_?id$|^project$", RegexOption.IGNORE_CASE)
        for (key in item.keys()) {
            if (!re.matches(key)) continue
            val v = item.opt(key)
            if (v is Number || v is String) out.add(v)
        }
        item.optJSONObject("project")?.opt("id")?.let { out.add(it) }
        val link = item.optJSONObject("link")
        if (link != null) {
            val p = link.opt("project")
            if (p is Number || p is String) out.add(p)
            if (p is JSONObject) p.opt("id")?.let { out.add(it) }
        }
        return out
    }

    private fun flatten(value: String): String {
        var text = Regex("<[^>]{1,80}>").replace(value, "").replace(Regex("\\s+"), " ").trim()
        if (text.length > 120) text = text.take(120) + "…"
        return text
    }

    private val KIND_LABEL = mapOf("mails" to "站内信", "messages" to "消息", "broadcasts" to "公告")
}
