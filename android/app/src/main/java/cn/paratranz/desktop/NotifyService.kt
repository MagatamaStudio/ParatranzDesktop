package cn.paratranz.desktop

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.webkit.CookieManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.json.JSONTokener
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

// 桌面端靠托盘常驻轮询；Android 上等价物是前台服务。
// 每 10 秒探一次私信未读数，数涨了或到了轮询间隔就完整拉一遍三个源。
class NotifyService : Service() {
    private data class Feed(val id: String, val list: String)
    private class FeedState {
        val seen = HashSet<String>()
        var primed = false
    }

    private val feeds = listOf(
        Feed("mails", "https://paratranz.cn/api/mails?page=1&pageSize=20"),
        Feed("messages", "https://paratranz.cn/api/messages?page=1&pageSize=20"),
        Feed("broadcasts", "https://paratranz.cn/api/broadcasts?page=1&pageSize=20"),
    )

    private val feedState = HashMap<String, FeedState>()
    private val clearedUids = HashSet<Long>()
    private var executor: ScheduledExecutorService? = null
    private var myUid: Long? = null
    private var myUidKnown = false
    private var lastFullAt = 0L
    private var lastCount: Int? = null
    private var seq = 100

    override fun onCreate() {
        super.onCreate()
        Store.init(applicationContext)
        createChannels(this)
        ServiceCompat.startForeground(
            this,
            NOTIF_ONGOING,
            ongoingNotification(),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0,
        )
        executor = Executors.newSingleThreadScheduledExecutor()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                Store.updateSettings(JSONObject().put("notifications", false))
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_POLL_NOW -> executor?.execute { pollAll() }
        }
        scheduleTick(1000)
        return START_STICKY
    }

    override fun onDestroy() {
        executor?.shutdownNow()
        executor = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleTick(delayMs: Long) {
        val ex = executor ?: return
        try {
            ex.schedule({ tick() }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            // 已经 shutdown
        }
    }

    private fun tick() {
        if (!Store.boolSetting("notifications", true)) {
            stopSelf()
            return
        }
        val now = System.currentTimeMillis()
        val intervalSec = maxOf(15, Store.intSetting("notifyIntervalSec", 45))
        val fullDue = now - lastFullAt >= intervalSec * 1000L
        when {
            fullDue -> pollAll()
            else -> {
                val count = probeCount()
                if (count != null) {
                    val prev = lastCount
                    lastCount = count
                    if (prev != null && count > prev) pollAll()
                }
            }
        }
        scheduleTick(10_000)
    }

    private fun pollAll() {
        try {
            val rules = Store.rules()
            val mine = myUid()
            for (feed in feeds) {
                try {
                    pollFeed(feed, rules, mine)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            lastFullAt = System.currentTimeMillis()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun pollFeed(feed: Feed, rules: Rules, mine: Long?) {
        val res = fetch(feed.list) ?: run {
            Log.d(LOGTAG, "feed=${feed.id} fetch-failed")
            return
        }
        Log.d(LOGTAG, "feed=${feed.id} status=${res.first} bytes=${res.second.length}")
        if (res.first == 401 || res.first == 403) return
        if (res.first !in 200..299) return
        val json = parseJson(res.second) ?: return
        maybeDump(feed.id, res.second)
        val items = NotifyShape.items(json)

        if (feed.id == "mails" && rules.clearBlockedMail) {
            clearBlockedConversations(items, rules, mine)
        }

        val st = feedState.getOrPut(feed.id) { FeedState() }
        if (!st.primed) {
            items.forEachIndexed { i, item -> st.seen.add(NotifyShape.keyOf(item, i)) }
            st.primed = true
            return
        }
        for ((i, item) in items.withIndex()) {
            val key = NotifyShape.keyOf(item, i)
            if (!st.seen.add(key)) continue
            if (NotifyShape.shouldSkip(item, rules)) continue
            if (NotifyShape.isSelfAuthored(item, mine)) continue
            notifyUser(NotifyShape.titleOf(item, feed.id), NotifyShape.bodyOf(item))
        }
    }

    // 开关打开时才会走到这里：调用站点自己的接口把与对方的会话标记已读，
    // 效果等同于你在网页上点开那条会话。每个 uid 只做一次。
    private fun clearBlockedConversations(items: List<JSONObject>, rules: Rules, mine: Long?) {
        for (uid in NotifyShape.blockedUnreadConversations(items, rules, mine)) {
            if (!clearedUids.add(uid)) continue
            try {
                fetch(
                    "https://paratranz.cn/api/mails/conversations/$uid",
                    method = "POST",
                    body = "{\"op\":\"read\"}",
                    contentType = "application/json",
                )
            } catch (e: Exception) {
                clearedUids.remove(uid)
            }
        }
    }

    private fun probeCount(): Int? {
        val res = fetch("https://paratranz.cn/api/mails/count") ?: return null
        if (res.first !in 200..299) return null
        val json = parseJson(res.second) ?: return null
        if (json is Number) return json.toInt()
        val obj = json as? JSONObject ?: return null
        for (key in listOf("unread", "count", "total", "new", "noRead", "number")) {
            val v = obj.opt(key)
            if (v is Number) return v.toInt()
        }
        return null
    }

    private fun myUid(): Long? {
        if (myUidKnown) return myUid
        val res = fetch("https://paratranz.cn/api/account") ?: return null
        if (res.first !in 200..299) return null
        val obj = parseJson(res.second) as? JSONObject ?: return null
        val id = Rules.num(obj.opt("id"))
        if (id != null) {
            myUid = id
            myUidKnown = true
        }
        return id
    }

    private fun fetch(
        url: String,
        method: String = "GET",
        body: String? = null,
        contentType: String? = null,
    ): Pair<Int, String>? {
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("accept", "application/json")
                CookieManager.getInstance().getCookie("https://paratranz.cn")?.let {
                    setRequestProperty("Cookie", it)
                }
                if (body != null) {
                    doOutput = true
                    if (contentType != null) setRequestProperty("content-type", contentType)
                    outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            Pair(code, text)
        } catch (e: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseJson(text: String): Any? = try {
        JSONTokener(text).nextValue()
    } catch (e: Exception) {
        null
    }

    private fun maybeDump(feedId: String, text: String) {
        if (!Store.boolSetting("dumpShapes", false)) return
        try {
            val dir = File(filesDir, "shape-dump").apply { mkdirs() }
            val f = File(dir, "$feedId.json")
            if (!f.exists()) f.writeText(text, Charsets.UTF_8)
        } catch (e: Exception) {
        }
    }

    private fun notifyUser(title: String, body: String) {
        if (!canNotify()) return
        val notification = NotificationCompat.Builder(this, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(title)
            .setContentText(body.ifEmpty { "有新消息" })
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.ifEmpty { "有新消息" }))
            .setAutoCancel(true)
            .setContentIntent(openAppIntent())
            .build()
        try {
            NotificationManagerCompat.from(this).notify(seq++, notification)
        } catch (e: SecurityException) {
        }
    }

    private fun canNotify(): Boolean {
        if (!NotificationManagerCompat.from(this).areNotificationsEnabled()) return false
        return Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun openAppIntent(): PendingIntent {
        val intent = Intent(this, SiteActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(this, 1, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun ongoingNotification(): Notification {
        val stop = PendingIntent.getService(
            this,
            2,
            Intent(this, NotifyService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(getString(R.string.notify_service_title))
            .setContentText(getString(R.string.notify_service_text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .addAction(0, getString(R.string.notify_service_stop), stop)
            .build()
    }

    companion object {
        private const val LOGTAG = "PtNotify"
        private const val CHANNEL_SERVICE = "pt-service"
        private const val CHANNEL_MESSAGES = "pt-messages"
        private const val NOTIF_ONGOING = 1
        private const val ACTION_POLL_NOW = "cn.paratranz.desktop.POLL_NOW"
        private const val ACTION_STOP = "cn.paratranz.desktop.STOP"

        fun createChannels(ctx: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = ctx.getSystemService(NotificationManager::class.java) ?: return
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_SERVICE, "常驻提醒", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Paratranz 新消息监视运行中"
                setShowBadge(false)
            })
            manager.createNotificationChannel(NotificationChannel(
                CHANNEL_MESSAGES,
                ctx.getString(R.string.notify_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = ctx.getString(R.string.notify_channel_desc)
            })
        }

        fun start(ctx: Context) {
            if (!Store.boolSetting("notifications", true)) return
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, NotifyService::class.java))
            } catch (e: Exception) {
                // 后台启动受限时忽略，下次回到前台会再试
            }
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, NotifyService::class.java))
            } catch (e: Exception) {
            }
        }

        fun sync(ctx: Context) {
            if (Store.boolSetting("notifications", true)) start(ctx) else stop(ctx)
        }

        fun pollNow(ctx: Context) {
            try {
                ctx.startService(Intent(ctx, NotifyService::class.java).setAction(ACTION_POLL_NOW))
            } catch (e: Exception) {
            }
        }
    }
}
