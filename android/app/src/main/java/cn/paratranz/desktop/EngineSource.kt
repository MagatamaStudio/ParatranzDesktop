package cn.paratranz.desktop

import android.content.Context

// engine.js 保持单个函数字面量的写法，注入前只切掉 module.exports 前缀。
// 这段源码在桌面端由 CDP document-start 注入，在 Android 由 WebViewCompat 的
// addDocumentStartJavaScript 注入，同一份文件，不复制到别处。
object EngineSource {
    private var cached: String? = null

    fun functionSource(ctx: Context): String {
        cached?.let { return it }
        val text = ctx.assets.open("engine.js").bufferedReader(Charsets.UTF_8).use { it.readText() }
        val marker = "module.exports = "
        val at = text.indexOf(marker + "function")
        require(at >= 0) { "engine.js 不再是 module.exports = function 形式，注入会失败" }
        val src = text.substring(at + marker.length).trimEnd()
        require(src.startsWith("function")) { "引擎源码切片跑偏：" + src.take(40) }
        cached = src
        return src
    }
}
