package cn.paratranz.desktop

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Menu
import android.view.MenuItem
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.lang.ref.WeakReference

class SiteActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private lateinit var injector: Injector
    private lateinit var storeListener: () -> Unit
    private var selftest = false
    private var selftestStage = 0

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(applicationContext)
        selftest = intent?.getBooleanExtra("selftest", false) == true

        webView = WebView(this)
        webView.layoutParams = ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )
        setContentView(webView)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            mediaPlaybackRequiresUserGesture = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)
        WebView.setWebContentsDebuggingEnabled(
            (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        )

        webView.addJavascriptInterface(NativeBridge(), "__ptNative")
        webView.setOnLongClickListener {
            val link = webView.hitTestResult
            val href = link.extra
            if (link.type == WebView.HitTestResult.SRC_ANCHOR_TYPE && !href.isNullOrEmpty()) {
                showLinkMenu(href)
                true
            } else {
                false
            }
        }
        webView.webViewClient = siteClient()
        webView.webChromeClient = chromeClient()

        injector = Injector(applicationContext, webView)
        injector.install()
        Log.i(TAG, "docStart supported=${injector.docStartSupported} active=${injector.docStartActive}")

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack() else finish()
            }
        })

        val restored = savedInstanceState?.getBundle("webview")
        if (restored != null) webView.restoreState(restored) else webView.loadUrl(Injector.SITE)

        current = WeakReference(this)

        storeListener = {
            runOnUiThread {
                if (!isFinishing && !isDestroyed) {
                    injector.updateRules()
                    NotifyService.sync(this@SiteActivity)
                }
            }
        }
        Store.onChange(storeListener)

        ensureNotificationPermission()
        if (Store.boolSetting("notifications", true)) NotifyService.start(this)
    }

    override fun onResume() {
        super.onResume()
        current = WeakReference(this)
        if (::injector.isInitialized) injector.updateRules()
        onSelftestResume()
        NotifyService.sync(this)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        val state = Bundle()
        webView.saveState(state)
        outState.putBundle("webview", state)
    }

    override fun onDestroy() {
        if (::storeListener.isInitialized) Store.offChange(storeListener)
        if (current?.get() === this) current = null
        if (::webView.isInitialized) {
            webView.removeJavascriptInterface("__ptNative")
            webView.destroy()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menu.add(0, 1, 0, "屏蔽管理")
        menu.add(0, 2, 1, "屏蔽当前页面")
        menu.add(0, 3, 2, "重新加载")
        menu.add(0, 4, 3, "在浏览器打开")
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            1 -> startActivity(Intent(this, ManageActivity::class.java))
            2 -> blockCurrentPage()
            3 -> webView.reload()
            4 -> openExternal(webView.url ?: Injector.SITE)
            else -> return super.onOptionsItemSelected(item)
        }
        return true
    }

    private fun siteClient(): WebViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val uri = request.url
            val scheme = uri.scheme.orEmpty()
            if (scheme != "http" && scheme != "https") {
                openExternal(uri.toString())
                return true
            }
            val host = uri.host.orEmpty()
            if (host != "paratranz.cn" && !host.endsWith(".paratranz.cn")) {
                openExternal(uri.toString())
                return true
            }
            val hit = Store.rules().urlTargetsBlockedProject(uri.toString())
            if (hit != null) {
                askRevealBlockedProject(hit, uri.toString())
                return true
            }
            return false
        }

        override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
            super.onPageStarted(view, url, favicon)
            // 没有 document-start 能力的老 WebView 只能在这里补一次，首屏可能闪一下
            if (!injector.docStartActive) {
                view.evaluateJavascript(injector.bootstrap(), null)
                view.evaluateJavascript(injector.updateCode(), null)
            }
        }

        override fun onPageFinished(view: WebView, url: String?) {
            super.onPageFinished(view, url)
            // document-start 理论上一定跑在页面脚本之前；万一没落地（老内核、注册失败），
            // 这里补一枪，代价是首屏已经被站点自己的请求跑过一遍。
            view.evaluateJavascript("!!(window.__ptShieldInfo && window.__ptShieldInfo())") { hasEngine ->
                Log.i(TAG, "page $url engine=$hasEngine")
                if (hasEngine == "false" || hasEngine == "null") {
                    view.evaluateJavascript(injector.bootstrap(), null)
                }
                view.evaluateJavascript(injector.updateCode(), null)
                view.evaluateJavascript(PROBE) { Log.i(TAG, "probe $it") }
                maybeSelftest(url)
            }
        }
    }

    // 仅 adb 自检用：屏蔽一个真实项目页，确认页面墙出现后再撤掉，避免污染用户名单
    private fun maybeSelftest(url: String?) {
        if (!selftest) return
        if (selftestStage == 0) {
            selftestStage = 1
            Thread {
                val who = TokenLogin.whoami()
                Log.i(TAG, "whoami logged=${who.logged} user=${who.username} nick=${who.nickname} err=${who.error}")
                NotifyService.pollNow(this)
                val list = Store.rawExport().optJSONObject("blocklist") ?: JSONObject()
                Log.i(TAG, "store projects=${list.optJSONArray("projects")?.length() ?: 0} users=${list.optJSONArray("users")?.length() ?: 0}")
                Store.addProject(JSONObject().put("id", SELFTEST_PROJECT).put("name", "selftest"))
                runOnUiThread {
                    injector.updateRules {
                        webView.loadUrl(Injector.SITE_ORIGIN + "/projects/" + SELFTEST_PROJECT)
                    }
                }
            }.start()
        } else if (selftestStage == 1 && url != null && url.contains("/projects/$SELFTEST_PROJECT")) {
            selftestStage = 2
            webView.evaluateJavascript(
                "JSON.stringify({wall:!!document.querySelector('.pt-shield-wall')," +
                    "marked:document.querySelectorAll('[data-pt-shielded]').length})"
            ) { Log.i(TAG, "blocked-probe $it") }
            Log.i(TAG, "opening manage to remove")
            startActivity(Intent(this, ManageActivity::class.java).putExtra("selftestAction", "reveal10709"))
        }
    }

    // 管理页关掉、站点窗口回到前台后看它有没有真的解除
    private fun onSelftestResume() {
        if (!selftest || selftestStage != 2) return
        selftestStage = 3
        Handler(Looper.getMainLooper()).postDelayed({
            webView.evaluateJavascript(PROBE) { Log.i(TAG, "after-manage-probe $it") }
            Thread {
                Store.removeProject(SELFTEST_PROJECT)
                Log.i(TAG, "selftest cleanup done")
                runOnUiThread { webView.loadUrl(Injector.SITE_ORIGIN + "/projects") }
            }.start()
        }, 3500)
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun chromeClient(): WebChromeClient = object : WebChromeClient() {
        override fun onPermissionRequest(request: PermissionRequest) {
            request.deny()
        }

        override fun onConsoleMessage(msg: ConsoleMessage): Boolean {
            if (msg.messageLevel() >= ConsoleMessage.MessageLevel.WARNING) {
                Log.w(TAG, "console ${msg.message()} @${msg.sourceId()}:${msg.lineNumber()}")
            }
            return super.onConsoleMessage(msg)
        }

        override fun onJsAlert(
            view: WebView,
            url: String?,
            message: String?,
            result: android.webkit.JsResult,
        ): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            AlertDialog.Builder(this@SiteActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsConfirm(
            view: WebView,
            url: String?,
            message: String?,
            result: android.webkit.JsResult,
        ): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            AlertDialog.Builder(this@SiteActivity)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }

        override fun onJsPrompt(
            view: WebView,
            url: String?,
            message: String?,
            defaultValue: String?,
            result: android.webkit.JsPromptResult,
        ): Boolean {
            if (isFinishing) {
                result.cancel()
                return true
            }
            val input = EditText(this@SiteActivity)
            input.setText(defaultValue.orEmpty())
            AlertDialog.Builder(this@SiteActivity)
                .setMessage(message)
                .setView(input)
                .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
                .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
                .setOnCancelListener { result.cancel() }
                .show()
            return true
        }
    }

    private inner class NativeBridge {        @JavascriptInterface
        fun intent(json: String) {
            val data = try {
                JSONObject(json)
            } catch (e: Exception) {
                return
            }
            runOnUiThread { handleIntent(data) }
        }
    }

    private fun handleIntent(msg: JSONObject) {
        if (isFinishing || isDestroyed) return
        when (msg.optString("type")) {
            "activity" -> NotifyService.pollNow(this)
            "block" -> {
                val payload = msg.optJSONObject("payload") ?: return
                val kind = payload.optString("kind")
                val id = Rules.num(payload.opt("id")) ?: return
                if (id <= 0 || (kind != "project" && kind != "user")) return
                val label = if (kind == "project") "项目 $id" else "用户 $id"
                val title = payload.optString("title", "")
                val url = payload.optString("url", "")
                AlertDialog.Builder(this)
                    .setTitle("加入屏蔽名单")
                    .setMessage("屏蔽$label？\n$title\n$url")
                    .setPositiveButton("屏蔽") { _, _ -> addTarget(kind, id, title, url) }
                    .setNegativeButton("取消", null)
                    .show()
            }
            "reveal" -> {
                val payload = msg.optJSONObject("payload") ?: return
                val kind = payload.optString("kind")
                val id = Rules.num(payload.opt("id")) ?: return
                if (id <= 0) return
                AlertDialog.Builder(this)
                    .setTitle("临时解除屏蔽")
                    .setMessage("临时显示" + (if (kind == "project") "该项目" else "该用户") + "？\n临时解除只维持到本次运行，重启后恢复屏蔽。")
                    .setPositiveButton("临时显示") { _, _ ->
                        if (kind == "project") Store.revealProject(id) else Store.revealUser("id:$id")
                    }
                    .setNegativeButton("继续屏蔽", null)
                    .show()
            }
        }
    }

    private fun addTarget(kind: String, id: Long, title: String, url: String) {
        val res = if (kind == "project") {
            Store.addProject(JSONObject().put("id", id).put("name", title).put("url", url))
        } else {
            Store.addUser(JSONObject().put("id", id).put("url", url))
        }
        if (!res.optBoolean("ok", false)) {
            AlertDialog.Builder(this).setMessage(res.optString("error", "添加失败")).setPositiveButton("知道了", null).show()
        }
    }

    private fun askRevealBlockedProject(projectId: Long, url: String) {
        AlertDialog.Builder(this)
            .setTitle("已被屏蔽")
            .setMessage("项目 $projectId 已被本客户端屏蔽。\n$url")
            .setPositiveButton("临时显示") { _, _ ->
                Store.revealProject(projectId)
                // 先把新规则推进当前页，再导航，避免新文档带着旧规则启动
                injector.updateRules { webView.loadUrl(url) }
            }
            .setNegativeButton("保持屏蔽", null)
            .show()
    }

    private fun blockCurrentPage() {
        val target = currentTarget() ?: run {
            Toast.makeText(this, "当前页面不在项目页或用户页上", Toast.LENGTH_SHORT).show()
            return
        }
        val kind = target.optString("kind")
        val id = Rules.num(target.opt("id")) ?: return
        val title = target.optString("title", "")
        val url = target.optString("url", "")
        AlertDialog.Builder(this)
            .setTitle("加入屏蔽名单")
            .setMessage((if (kind == "project") "屏蔽项目 " else "屏蔽用户 ") + id + "？\n" + title + "\n" + url)
            .setPositiveButton("屏蔽") { _, _ -> addTarget(kind, id, title, url) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun showLinkMenu(url: String) {
        if (url.isEmpty()) return
        val target = Store.rules().targetOfUrl(url)
        val labels = ArrayList<String>()
        val actions = ArrayList<() -> Unit>()
        if (target != null) {
            labels.add(if (target.kind == "project") "屏蔽项目 ${target.id}" else "屏蔽用户 ${target.id}")
            actions.add { addTarget(target.kind, target.id, "", url) }
        }
        labels.add("在浏览器打开")
        actions.add { openExternal(url) }
        labels.add("复制链接")
        actions.add {
            val clip = getSystemService(ClipboardManager::class.java)
            clip.setPrimaryClip(ClipData.newPlainText("url", url))
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
        }
        AlertDialog.Builder(this)
            .setTitle(url)
            .setItems(labels.toTypedArray()) { _, which -> actions[which]() }
            .show()
    }

    private fun openExternal(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, "没有可打开这个链接的应用", Toast.LENGTH_SHORT).show()
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    companion object {
        private const val TAG = "PtAndroid"
        private const val SELFTEST_PROJECT = 10709L
        private const val PROBE =
            "(function(){try{var i=window.__ptShieldInfo?window.__ptShieldInfo():null;" +
                "return JSON.stringify({href:location.href,title:document.title,engine:!!i," +
                "mode:i&&i.mode,patchedFetch:!!(i&&i.patchedFetch),patchedXhr:!!(i&&i.patchedXhr)," +
                "projects:i?i.projects.length:-1,users:i?i.users.length:-1,fab:!!document.querySelector('.pt-shield-fab')," +
                "wall:!!document.querySelector('.pt-shield-wall')," +
                "marked:document.querySelectorAll('[data-pt-shielded]').length," +
                "links:document.querySelectorAll('a[href*=\"/projects/\"]').length," +
                "text:document.body?document.body.innerText.length:-1," +
                "dark:matchMedia('(prefers-color-scheme: dark)').matches," +
                "bg:getComputedStyle(document.body).backgroundColor," +
                "bootError:window.__ptBootError||null})}catch(e){return 'probe-error:'+e}})()"
        @Volatile
        private var current: WeakReference<SiteActivity>? = null

        fun refreshPlugins() {
            current?.get()?.let { it.injector.refreshPlugins() }
        }

        fun reloadSite() {
            current?.get()?.let { it.webView.reload() }
        }

        fun currentTarget(): JSONObject? {
            val activity = current?.get() ?: return null
            val web = activity.webView
            val url = web.url ?: return null
            val target = Store.rules().targetOfUrl(url) ?: return null
            return JSONObject()
                .put("kind", target.kind)
                .put("id", target.id)
                .put("title", web.title.orEmpty())
                .put("url", url)
                .put("account", "default")
        }
    }
}
