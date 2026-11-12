package cn.paratranz.desktop

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

// 与 src/shared/userscript.js 同源的插件逻辑：解析 Tampermonkey 头部、把 @match/@include
// 编译成纯字符串片段，再拼成一段直接打进页面主世界的源码。插件排在屏蔽引擎之后执行。
object Plugins {
    private val headerRe = Regex("//[ \\t]*==UserScript==[\\s\\S]*?//[ \\t]*==/UserScript==")
    private val metaRe = Regex("^//[ \\t]*@([A-Za-z-]+)[ \\t]+(.+?)[ \\t]*$")

    class Entry(
        val file: String,
        val name: String,
        val kind: String,
        val code: String,
        val matches: List<String>,
        val includes: List<String>,
        val bytes: Long,
        val enabled: Boolean,
    )

    fun dir(ctx: Context): File = File(ctx.filesDir, "plugins").apply { mkdirs() }

    fun readEntries(ctx: Context): List<Entry> {
        val disabled = Store.pluginsDisabled()
        val files = dir(ctx).listFiles()?.sortedBy { it.name } ?: return emptyList()
        val out = ArrayList<Entry>()
        for (f in files) {
            if (!f.isFile) continue
            val lower = f.name.lowercase()
            val isCss = lower.endsWith(".css")
            if (!isCss && !lower.endsWith(".js")) continue
            val code = try {
                f.readText(Charsets.UTF_8)
            } catch (e: Exception) {
                continue
            }
            val meta = if (isCss) Header("", emptyList(), emptyList()) else parseHeader(code)
            out.add(Entry(
                file = f.name,
                name = meta.name.ifEmpty { f.name },
                kind = if (isCss) "css" else "js",
                code = code,
                matches = meta.matches,
                includes = meta.includes,
                bytes = f.length(),
                enabled = !disabled.contains(f.name),
            ))
        }
        return out
    }

    fun listJson(ctx: Context): JSONArray {
        val arr = JSONArray()
        for (e in readEntries(ctx)) {
            arr.put(JSONObject()
                .put("file", e.file)
                .put("name", e.name)
                .put("kind", e.kind)
                .put("bytes", e.bytes)
                .put("enabled", e.enabled)
                .put("matches", JSONArray(e.matches + e.includes)))
        }
        return arr
    }

    fun toggle(ctx: Context, file: String, enabled: Boolean) {
        val set = Store.pluginsDisabled().toMutableSet()
        if (enabled) set.remove(file) else set.add(file)
        Store.setPluginsDisabled(set)
    }

    fun injectedSource(ctx: Context): String {
        val entries = readEntries(ctx).filter { it.enabled && it.code.isNotBlank() }
        if (entries.isEmpty()) return ""
        val js = buildInjectedSource(entries.filter { it.kind == "js" })
        val css = buildCssSource(entries.filter { it.kind == "css" })
        return listOf(js, css).filter { it.isNotEmpty() }.joinToString("\n")
    }

    fun importFiles(ctx: Context, files: List<Pair<String, String>>): Int {
        var count = 0
        val root = dir(ctx)
        for ((rawName, code) in files) {
            val name = File(rawName).name
            val lower = name.lowercase()
            if (!lower.endsWith(".js") && !lower.endsWith(".css")) continue
            if (code.isBlank()) continue
            try {
                File(root, name).writeText(code, Charsets.UTF_8)
                count++
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return count
    }

    private class Header(val name: String, val matches: List<String>, val includes: List<String>)

    private fun parseHeader(source: String): Header {
        val block = headerRe.find(source) ?: return Header("", emptyList(), emptyList())
        var name = ""
        val matches = ArrayList<String>()
        val includes = ArrayList<String>()
        for (line in block.value.split(Regex("\\r?\\n"))) {
            val m = metaRe.find(line) ?: continue
            val key = m.groupValues[1].lowercase()
            val value = m.groupValues[2].trim()
            when (key) {
                "name" -> if (name.isEmpty()) name = value
                "match" -> matches.add(value)
                "include" -> includes.add(value)
            }
        }
        return Header(name, matches, includes)
    }

    private class Spec(val prefix: String, val middle: List<String>, val suffix: String, val exact: Boolean, val star: Boolean)

    private fun toSpec(pattern: String): Spec? {
        if (pattern.isEmpty()) return null
        val segs = pattern.split("*")
        return Spec(
            prefix = segs[0],
            middle = if (segs.size > 1) segs.subList(1, segs.size - 1) else emptyList(),
            suffix = if (segs.size > 1) segs[segs.size - 1] else "",
            exact = segs.size == 1,
            star = pattern == "*",
        )
    }

    private const val RUNTIME = """
function __ptCompiledHit(c, href) {
  var at = 0;
  if (c.prefix) { if (href.indexOf(c.prefix) !== 0) return false; at = c.prefix.length; }
  for (var i = 0; i < c.middle.length; i++) {
    var seg = c.middle[i];
    if (!seg) continue;
    var k = href.indexOf(seg, at);
    if (k < 0) return false;
    at = k + seg.length;
  }
  if (c.exact) return href === c.prefix;
  if (c.suffix) { var tail = href.slice(at); return tail.length >= c.suffix.length && tail.lastIndexOf(c.suffix) === tail.length - c.suffix.length; }
  return true;
}
function __ptMatch(specs, href) {
  for (var j = 0; j < specs.length; j++) {
    var s = specs[j];
    if (s.star) return true;
    if (__ptCompiledHit(s, href)) return true;
  }
  return false;
}
"""

    private fun buildInjectedSource(entries: List<Entry>): String {
        val list = entries.filter { it.code.isNotBlank() }
        if (list.isEmpty()) return ""
        val parts = ArrayList<String>()
        parts.add("(function(){")
        parts.add("\"use strict\";")
        parts.add(RUNTIME)
        for (entry in list) {
            val patterns = (entry.matches + entry.includes).mapNotNull { toSpec(it) }
            val specs = JSONArray()
            for (p in patterns) {
                specs.put(JSONObject()
                    .put("prefix", p.prefix)
                    .put("middle", JSONArray(p.middle))
                    .put("suffix", p.suffix)
                    .put("exact", p.exact)
                    .put("star", p.star))
            }
            val label = entry.name.ifEmpty { entry.file }.replace(Regex("[^\\w.\\- ]"), "")
            val guard = if (patterns.isEmpty()) "true" else "__ptMatch(${specs}, location.href)"
            parts.add("try{if($guard){(function(){")
            parts.add(entry.code)
            parts.add("})();}}catch(e){console.warn(${JSONObject.quote("[插件 $label] 出错: ")}, e);}")
        }
        parts.add("})();")
        return parts.joinToString("\n")
    }

    private fun buildCssSource(entries: List<Entry>): String {
        val blocks = entries.filter { it.code.isNotBlank() }
        if (blocks.isEmpty()) return ""
        val css = blocks.joinToString("\n") { "/* ${it.name.ifEmpty { it.file }} */\n${it.code}" }
        return "(function(){var s=document.createElement(\"style\");s.id=\"pt-plugin-css\";s.textContent=" +
            JSONObject.quote(css) + ";(document.head||document.documentElement).appendChild(s);})();"
    }
}
