package cn.paratranz.desktop

import android.content.Context
import android.webkit.WebView
import androidx.webkit.ScriptHandler
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.util.UUID

// 注入时机是唯一硬约束：引擎必须赶在站点自己的脚本之前接管 fetch / XMLHttpRequest。
// Android 上首选 androidx.webkit 的 document-start 注入（主世界、文档开始前执行）；
// 老 WebView 不支持时退回 onPageStarted 再打一次，代价是首屏可能闪一下。
class Injector(private val ctx: Context, private val webView: WebView) {
    private val token: String = UUID.randomUUID().toString().replace("-", "")
    private val handle: String = "__ptApply_" + UUID.randomUUID().toString().replace("-", "")
    private var state: JSONObject = Store.rules().stateJson()
    private var handler: ScriptHandler? = null

    val docStartSupported: Boolean = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
    @Volatile
    var docStartActive: Boolean = false
        private set

    fun install() {
        if (!docStartSupported) return
        try {
            register()
            docStartActive = true
        } catch (e: Exception) {
            docStartActive = false
        }
    }

    fun bootstrap(): String {
        val config = JSONObject()
            .put("token", token)
            .put("handleName", handle)
            .put("mode", "full")
            .put("rules", state)
        val engineCall = "(" + EngineSource.functionSource(ctx) + ")(" + config + ");"
        val plugins = try {
            Plugins.injectedSource(ctx)
        } catch (e: Exception) {
            ""
        }
        return SHIM + "window.__ptBootError=null;try{" + engineCall +
            (if (plugins.isNotEmpty()) "\n$plugins" else "") +
            "}catch(e){window.__ptBootError=String((e&&e.stack)||e)}"
    }

    fun updateCode(): String =
        "window[${JSONObject.quote(handle)}] && window[${JSONObject.quote(handle)}]($state, ${JSONObject.quote(token)});"

    fun updateRules(onPushed: (() -> Unit)? = null) {
        val next = Store.rules().stateJson()
        val changed = next.toString() != state.toString()
        state = next
        if (changed && docStartActive) {
            try {
                register()
            } catch (e: Exception) {
                docStartActive = false
            }
        }
        webView.evaluateJavascript(updateCode()) { onPushed?.invoke() }
    }

    // 插件增删后重登记 document-start；已经打开的这一页要用户自己重载才生效，
    // 与桌面端一致，不替用户刷新以免打断正在编辑的译文。
    fun refreshPlugins() {
        if (docStartActive) {
            try {
                register()
            } catch (e: Exception) {
                docStartActive = false
            }
        }
        webView.evaluateJavascript(updateCode(), null)
    }

    private fun register() {
        handler?.remove()
        handler = null
        handler = WebViewCompat.addDocumentStartJavaScript(
            webView,
            bootstrap(),
            setOf(SITE_ORIGIN)
        )
    }

    companion object {
        const val SITE = "https://paratranz.cn/"
        const val SITE_ORIGIN = "https://paratranz.cn"

        // 页面脚本能监听 postMessage，所以这里只当作「请求」转交原生，
        // 是否真的改名单一律由原生确认框决定。
        private const val SHIM = """(function(){
try{
  var raw = window.postMessage;
  window.postMessage = function(msg, origin){
    try{
      if (msg && msg.channel === "pt-shield-intent" && window.__ptNative && window.__ptNative.intent) {
        window.__ptNative.intent(JSON.stringify({type: msg.type, payload: msg.payload, href: location.href}));
        return;
      }
    }catch(e){}
    return raw.apply(window, arguments);
  };
}catch(e){}
})();"""
    }
}
