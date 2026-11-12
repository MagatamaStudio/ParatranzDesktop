package cn.paratranz.desktop

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.provider.OpenableColumns
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import org.json.JSONTokener
import java.util.concurrent.Executors

// 复用桌面端的 manage.html / manage.js / manage.css，通过 bridge.js 把 ptManage
// 映射到原生。页面走 WebViewAssetLoader 的 https 域名，bridge.js 才能稳定地
// 在页面脚本之前注入。
class ManageActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private val net = Executors.newSingleThreadExecutor()
    private var pendingExport: String? = null
    private var pendingImport: String? = null
    private var selftestRan = false
    private lateinit var storeListener: () -> Unit

    private val createDoc =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
            val id = pendingExport
            pendingExport = null
            if (uri == null) {
                reply(id, errJson("已取消"))
                return@registerForActivityResult
            }
            try {
                contentResolver.openOutputStream(uri)?.use {
                    it.write(Store.rawExport().toString(2).toByteArray(Charsets.UTF_8))
                }
                reply(id, JSONObject().put("ok", true).put("path", uri.toString()))
            } catch (e: Exception) {
                reply(id, errJson("写入失败：" + (e.message ?: "")))
            }
        }

    private val openImport = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val id = pendingImport
        pendingImport = null
        if (uri == null) {
            reply(id, errJson("已取消"))
            return@registerForActivityResult
        }
        try {
            val text = contentResolver.openInputStream(uri)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val added = Store.importBlocklist(JSONObject(text))
            if (added < 0) reply(id, errJson("文件里没有 blocklist 结构"))
            else reply(id, JSONObject().put("ok", true).put("added", added))
        } catch (e: Exception) {
            reply(id, errJson("文件不是合法 JSON"))
        }
    }

    private val openPlugins =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNullOrEmpty()) return@registerForActivityResult
            val files = ArrayList<Pair<String, String>>()
            for (uri in uris) {
                val text = try {
                    contentResolver.openInputStream(uri)
                        ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }
                } catch (e: Exception) {
                    null
                } ?: continue
                files.add(displayName(uri) to text)
            }
            val count = Plugins.importFiles(this, files)
            Toast.makeText(this, "已导入 $count 个插件文件，重载站点窗口后生效", Toast.LENGTH_LONG).show()
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        title = getString(R.string.manage_title)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
        }
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )
        CookieManager.getInstance().setAcceptCookie(true)

        val loader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()
        val bridge = readAsset("bridge.js")
        val origin = setOf(ASSET_ORIGIN)

        webView.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? {
                val url = request?.url ?: return null
                return loader.shouldInterceptRequest(url)
            }

            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                    view?.evaluateJavascript(bridge, null)
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                view?.evaluateJavascript(
                    "JSON.stringify({ptManage:typeof window.ptManage,rows:document.querySelectorAll('tbody tr').length," +
                        "projects:document.querySelectorAll('#projectTable tbody tr').length,href:location.href})"
                ) { Log.i("PtAndroid", "manage loaded $it") }
                if (!selftestRan && intent?.getBooleanExtra("selftest", false) == true) {
                    selftestRan = true
                    view?.evaluateJavascript(SELFTEST_JS, null)
                    view?.postDelayed({
                        view.evaluateJavascript("window.__stResult||'pending'") {
                            Log.i("PtAndroid", "manage bridge $it")
                        }
                    }, 2000)
                }
                if (!selftestRan && intent?.getStringExtra("selftestAction") == "remove10709") {
                    selftestRan = true
                    view?.evaluateJavascript(
                        "window.ptManage.removeProject(10709).then(function(){window.__stResult='removed'})" +
                            ".catch(function(e){window.__stResult='ERR:'+e})", null
                    )
                    view?.postDelayed({
                        view.evaluateJavascript("window.__stResult||'pending'") {
                            Log.i("PtAndroid", "manage remove $it")
                        }
                        finish()
                    }, 2000)
                } else if (!selftestRan && intent?.getStringExtra("selftestAction") == "reveal10709") {
                    selftestRan = true
                    view?.evaluateJavascript(
                        "window.ptManage.revealProject(10709).then(function(){window.__stResult='revealed'})" +
                            ".catch(function(e){window.__stResult='ERR:'+e})", null
                    )
                    view?.postDelayed({
                        view.evaluateJavascript("window.__stResult||'pending'") {
                            Log.i("PtAndroid", "manage reveal $it")
                        }
                        finish()
                    }, 2000)
                }
            }
        }
        webView.addJavascriptInterface(Bridge(), "__ptNative")
        webView.webChromeClient = dialogChrome()
        if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
            WebViewCompat.addDocumentStartJavaScript(webView, bridge, origin)
        }
        webView.loadUrl("$ASSET_ORIGIN/assets/manage.html")

        storeListener = { runOnUiThread { pushSnapshot() } }
        Store.onChange(storeListener)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == android.R.id.home) {
            finish()
            return true
        }
        return super.onOptionsItemSelected(item)
    }

    override fun onDestroy() {
        if (::storeListener.isInitialized) Store.offChange(storeListener)
        net.shutdownNow()
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("__ptNative")
            webView.destroy()
        }
        super.onDestroy()
    }

    private fun readAsset(name: String): String =
        assets.open(name).bufferedReader(Charsets.UTF_8).use { it.readText() }

    private fun dialogChrome(): WebChromeClient = object : WebChromeClient() {
        override fun onJsConfirm(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult,
        ): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            AlertDialog.Builder(this@ManageActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsAlert(
            view: WebView?,
            url: String?,
            message: String?,
            result: android.webkit.JsResult,
        ): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            AlertDialog.Builder(this@ManageActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView?,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult,
        ): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            val input = EditText(this@ManageActivity).apply { setText(defaultValue.orEmpty()) }
            AlertDialog.Builder(this@ManageActivity)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private inner class Bridge {
        @JavascriptInterface
        fun call(method: String, argJson: String, requestId: String): String {
            val arg = parse(argJson)
            return try {
                when (method) {
                    "get" -> Store.snapshotJson().toString()
                    "addProject" -> Store.addProject(obj(arg)).toString()
                    "removeProject" -> Store.removeProject(idOf(arg)).toString()
                    "addUser" -> Store.addUser(obj(arg)).toString()
                    "removeUser" -> Store.removeUser(arg).toString()
                    "revealProject" -> Store.revealProject(idOf(arg)).toString()
                    "revealUser" -> Store.revealUser(arg).toString()
                    "setRevealAll" -> Store.setRevealAll(arg == true).toString()
                    "setSettings" -> Store.updateSettings(obj(arg)).toString()
                    "currentTarget" -> SiteActivity.currentTarget()?.toString() ?: "null"
                    "blockCurrent" -> {
                        runOnUiThread { confirmBlock(requestId) }
                        "null"
                    }
                    "openSite" -> {
                        runOnUiThread { openSite() }
                        "{\"ok\":true}"
                    }
                    "accountAdd", "accountRename", "accountRemove" -> Store.snapshotJson().toString()
                    "exportList" -> {
                        runOnUiThread {
                            pendingExport = requestId
                            createDoc.launch("paratranz-blocklist.json")
                        }
                        "null"
                    }
                    "importList" -> {
                        runOnUiThread {
                            pendingImport = requestId
                            openImport.launch(arrayOf("*/*"))
                        }
                        "null"
                    }
                    "loginWithToken" -> {
                        loginAsync(obj(arg), requestId)
                        "null"
                    }
                    "whoami" -> {
                        whoamiAsync(requestId)
                        "null"
                    }
                    "openTokenPage" -> {
                        runOnUiThread { openExternal("https://paratranz.cn/account") }
                        "null"
                    }
                    "plugins" -> Plugins.listJson(this@ManageActivity).toString()
                    "togglePlugin" -> {
                        val o = obj(arg)
                        Plugins.toggle(this@ManageActivity, o.optString("file"), o.optBoolean("enabled", true))
                        SiteActivity.refreshPlugins()
                        "{\"ok\":true}"
                    }
                    "importPlugins" -> {
                        runOnUiThread { openPlugins.launch(arrayOf("*/*")) }
                        "{\"ok\":true}"
                    }
                    else -> errJson("未知方法 $method").toString()
                }
            } catch (e: Exception) {
                errJson(e.message ?: "操作失败").toString()
            }
        }
    }

    private fun confirmBlock(requestId: String) {
        if (isFinishing) return
        val target = SiteActivity.currentTarget()
        if (target == null) {
            reply(requestId, errJson("站点窗口当前不在项目页或用户页上"))
            Toast.makeText(this, "站点窗口当前不在项目页或用户页上", Toast.LENGTH_SHORT).show()
            return
        }
        val kind = target.optString("kind")
        val id = Rules.num(target.opt("id")) ?: return
        val title = target.optString("title", "")
        val url = target.optString("url", "")
        AlertDialog.Builder(this)
            .setTitle("加入屏蔽名单")
            .setMessage((if (kind == "project") "屏蔽项目 " else "屏蔽用户 ") + id + "？\n" + title + "\n" + url)
            .setPositiveButton("屏蔽") { _, _ ->
                addTarget(kind, id, title, url)
                reply(requestId, Store.snapshotJson())
            }
            .setNegativeButton("取消") { _, _ -> reply(requestId, Store.snapshotJson()) }
            .setOnCancelListener { reply(requestId, Store.snapshotJson()) }
            .show()
    }

    private fun addTarget(kind: String, id: Long, title: String, url: String) {
        val res = if (kind == "project") {
            Store.addProject(JSONObject().put("id", id).put("name", title).put("url", url))
        } else {
            Store.addUser(JSONObject().put("id", id).put("url", url))
        }
        if (!res.optBoolean("ok", false)) {
            AlertDialog.Builder(this).setMessage(res.optString("error", "添加失败"))
                .setPositiveButton("知道了", null).show()
        }
    }

    private fun loginAsync(entry: JSONObject, requestId: String) {
        net.execute {
            val result = TokenLogin.login(entry.optString("token"))
            val out = if (result.ok) {
                JSONObject().put("ok", true)
                    .put("username", result.username)
                    .put("nickname", result.nickname)
                    .put("account", "default")
            } else {
                errJson(result.error)
            }
            reply(requestId, out)
            if (result.ok) {
                // 之前打开的站点窗口还带着未登录的页面，换完 cookie 必须重载才算真的登录
                SiteActivity.reloadSite()
                NotifyService.pollNow(this)
            }
        }
    }

    private fun whoamiAsync(requestId: String) {
        net.execute {
            val who = TokenLogin.whoami()
            reply(requestId, JSONObject()
                .put("logged", who.logged)
                .put("username", who.username)
                .put("nickname", who.nickname))
        }
    }

    private fun pushSnapshot() {
        if (isFinishing || isDestroyed) return
        webView.evaluateJavascript(
            "if(window.__ptManageChanged)window.__ptManageChanged(" +
                JSONObject.quote(Store.snapshotJson().toString()) + ")",
            null,
        )
    }

    private fun reply(requestId: String?, body: JSONObject) {
        if (requestId.isNullOrBlank()) return
        runOnUiThread {
            if (isFinishing || isDestroyed) return@runOnUiThread
            webView.evaluateJavascript(
                "window.__ptReply(" + JSONObject.quote(requestId) + "," +
                    JSONObject.quote(body.toString()) + ")",
                null,
            )
        }
    }

    private fun openSite() {
        try {
            startActivity(Intent(this, SiteActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        } catch (e: Exception) {
        }
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可打开这个链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun displayName(uri: Uri): String {
        var name = uri.lastPathSegment ?: "plugin.js"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx)
                }
            }
        } catch (e: Exception) {
        }
        return name
    }

    private fun parse(argJson: String): Any? = try {
        if (argJson.isBlank()) null else JSONTokener(argJson).nextValue()
    } catch (e: Exception) {
        null
    }

    private fun obj(value: Any?): JSONObject = value as? JSONObject ?: JSONObject()

    private fun idOf(value: Any?): Long? = when (value) {
        is Number -> value.toLong()
        is String -> value.toLongOrNull()
        is JSONObject -> Rules.num(value.opt("id"))
        else -> null
    }

    private fun errJson(message: String) = JSONObject().put("ok", false).put("error", message)

    companion object {
        private const val ASSET_ORIGIN = "https://appassets.androidplatform.net"
        private const val SELFTEST_JS =
            "(async function(){try{" +
                "var s=await window.ptManage.get();var before=((s.blocklist||{}).projects||[]).length;" +
                "var a=await window.ptManage.addProject({id:10710,name:'selftest'});" +
                "var s2=await window.ptManage.get();var added=((s2.blocklist||{}).projects||[]).length;" +
                "await new Promise(function(r){setTimeout(r,300)});" +
                "var rowsAfterAdd=document.querySelectorAll('#projectTable tbody tr').length;" +
                "await window.ptManage.removeProject(10710);" +
                "await new Promise(function(r){setTimeout(r,300)});" +
                "var rowsAfterRemove=document.querySelectorAll('#projectTable tbody tr').length;" +
                "var s3=await window.ptManage.get();var after=((s3.blocklist||{}).projects||[]).length;" +
                "await window.ptManage.addUser({id:1,username:'selftestuser'});" +
                "var s4=await window.ptManage.get();var usersAdded=((s4.blocklist||{}).users||[]).length;" +
                "await window.ptManage.revealUser('id:1');" +
                "var s5=await window.ptManage.get();var userReveal=((s5.reveal||{}).userKeys||[]).indexOf('id:1')>=0;" +
                "await window.ptManage.removeUser('id:1');" +
                "var s6=await window.ptManage.get();var usersAfter=((s6.blocklist||{}).users||[]).length;" +
                "window.__stResult=JSON.stringify({before:before,addOk:!!(a&&a.ok!==false),added:added,rowsAfterAdd:rowsAfterAdd,after:after,rowsAfterRemove:rowsAfterRemove," +
                "usersAdded:usersAdded,userReveal:userReveal,usersAfter:usersAfter," +
                "rows:document.querySelectorAll('#projectTable tbody tr').length,settings:!!s.settings})" +
                "}catch(e){window.__stResult='ERR:'+e}})()"
    }
}
