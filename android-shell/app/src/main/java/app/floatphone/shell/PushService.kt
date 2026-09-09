package app.floatphone.shell

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * 推送前台服务：不依赖 Google 服务的自建长连接。
 *
 * 原理：用安装时生成的随机设备令牌长轮询腾讯云同源接口。
 * 个人 Supabase 只负责生成消息并把通知 POST 给腾讯云，手机不直接访问
 * Supabase、Google 或其他境外推送服务。
 */
class PushService : Service() {

    companion object {
        private const val TAG = "FloatShellPush"
        private const val CH_KEEPALIVE = "shell_keepalive"
        // 使用新渠道 ID，让从旧 APK 升级的设备也能获得 HIGH 级横幅默认值；
        // Android 不允许应用提高一个已由系统创建过的旧渠道等级。
        private const val CH_MESSAGES = "shell_messages_popup_v2"
        private const val CH_CALLS = "shell_calls"
        private const val NOTIF_FG_ID = 1
        private const val PUSH_PREFS = "shell_push"
        private const val PUSH_TOKEN_KEY = "device_token"
        private const val LAST_START_KEY = "last_start_ms"
        private const val LAST_POLL_ATTEMPT_KEY = "last_poll_attempt_ms"
        private const val LAST_POLL_OK_KEY = "last_poll_ok_ms"
        private const val LAST_MESSAGE_KEY = "last_message_ms"
        private const val LAST_ERROR_KEY = "last_error"
        fun start(context: Context) {
            // 不用进程内 running 门控：国产 ROM 可能直接杀掉服务而不回调 onDestroy，
            // 静态标记会永久残留，导致用户再次打开 App 时无法重新拉起推送服务。
            val intent = Intent(context, PushService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        @Synchronized
        fun getOrCreatePushToken(context: Context): String {
            val prefs = context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
            val existing = prefs.getString(PUSH_TOKEN_KEY, null).orEmpty()
            if (existing.matches(Regex("^[a-f0-9]{64}$"))) return existing
            val bytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
            val created = bytes.joinToString("") { "%02x".format(it) }
            prefs.edit().putString(PUSH_TOKEN_KEY, created).apply()
            return created
        }

        fun getStatus(context: Context): JSONObject {
            val prefs = context.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
            val permissionGranted = Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            return JSONObject()
                .put("notificationPermission", permissionGranted)
                .put("notificationsEnabled", NotificationManagerCompat.from(context).areNotificationsEnabled())
                .put("lastStartAt", prefs.getLong(LAST_START_KEY, 0L))
                .put("lastPollAttemptAt", prefs.getLong(LAST_POLL_ATTEMPT_KEY, 0L))
                .put("lastPollOkAt", prefs.getLong(LAST_POLL_OK_KEY, 0L))
                .put("lastMessageAt", prefs.getLong(LAST_MESSAGE_KEY, 0L))
                .put("lastError", prefs.getString(LAST_ERROR_KEY, "").orEmpty())
                .put("tokenSuffix", getOrCreatePushToken(context).takeLast(8))
        }
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(35, TimeUnit.SECONDS)
        .callTimeout(40, TimeUnit.SECONDS)
        .build()

    private var stopped = false
    private var notifId = 100

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        stopped = false
        pushPrefs().edit().putLong(LAST_START_KEY, System.currentTimeMillis()).apply()
        createChannels()
        startForeground(NOTIF_FG_ID, buildKeepAliveNotification("等待连接…"))
        thread(name = "shell-push-loop") { connectionLoop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        stopped = true
        client.dispatcher.cancelAll()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // 用户划掉最近任务不应同时失去后台消息；START_STICKY 负责系统重启，
        // 这里再主动触发一次可覆盖部分国产 ROM 的任务清理行为。
        if (!stopped) runCatching { start(applicationContext) }
        super.onTaskRemoved(rootIntent)
    }

    // 腾讯云每次保持约 25 秒长轮询；断网时退避，恢复后自动续上。
    private fun connectionLoop() {
        var backoffSec = 5L
        val token = getOrCreatePushToken(applicationContext)
        while (!stopped) {
            try {
                val message = pollTencent(token)
                updateKeepAlive("已连接，等待角色消息")
                backoffSec = 5
                if (message != null) showRelayMessage(message)
            } catch (error: Throwable) {
                if (stopped) break
                Log.w(TAG, "push relay connection failed; retry in ${backoffSec}s", error)
                pushPrefs().edit().putString(LAST_ERROR_KEY, error.message.orEmpty().take(240)).apply()
                updateKeepAlive("连接断开，重连中…")
                sleepSec(backoffSec)
                backoffSec = (backoffSec * 2).coerceAtMost(120)
            }
        }
    }

    private fun pollTencent(token: String): JSONObject? {
        pushPrefs().edit().putLong(LAST_POLL_ATTEMPT_KEY, System.currentTimeMillis()).apply()
        val body = JSONObject().put("token", token).toString()
            .toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url("${MainActivity.SITE_URL}/api/push/tencent/poll")
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 204) {
                markPollHealthy()
                return null
            }
            if (!response.isSuccessful) {
                Log.w(TAG, "push relay HTTP ${response.code}")
                error("push relay HTTP ${response.code}")
            }
            markPollHealthy()
            val root = JSONObject(response.body?.string().orEmpty())
            return root.optJSONObject("message")
        }
    }

    private fun showRelayMessage(message: JSONObject) {
        val title = message.optString("title").ifEmpty { "小手机" }
        val body = message.optString("body").ifEmpty { "有新消息" }
        Log.i(TAG, "received push type=${message.optString("type", "message")}")
        pushPrefs().edit().putLong(LAST_MESSAGE_KEY, System.currentTimeMillis()).apply()
        if (message.optString("type") == "incoming_call") {
            val shown = runCatching {
                showIncomingCallNotification(
                    title.removePrefix("📞 "),
                    message.optString("sessionId"),
                    message.optLong("callTs", System.currentTimeMillis()),
                )
            }.isSuccess
            if (shown) return
        }
        showMessageNotification(title, body)
    }

    // ── 通知 ──
    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CH_KEEPALIVE, "后台连接", NotificationManager.IMPORTANCE_MIN).apply {
                description = "维持角色消息接收通道（可在此关闭常驻通知的显示）"
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_MESSAGES, "角色消息", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "角色发来的离线消息（横幅弹窗）"
                setShowBadge(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 180, 120, 180)
                setSound(
                    Settings.System.DEFAULT_NOTIFICATION_URI,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build(),
                )
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CH_CALLS, "角色来电", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "角色打来的语音电话（只振动，不响铃）"
                setSound(null, null)
                enableVibration(false) // 振动由 CallAlert 循环控制，渠道自带的一次性振动关掉
            },
        )
    }

    private fun contentIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0,
        Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun buildKeepAliveNotification(text: String): Notification =
        NotificationCompat.Builder(this, CH_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("小手机")
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent())
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()

    private fun updateKeepAlive(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIF_FG_ID, buildKeepAliveNotification(text))
    }

    /**
     * 全屏来电通知：锁屏/熄屏直接弹 IncomingCallActivity，亮屏时是带
     * 接听/拒接按钮的 heads-up。振动循环 + 55s 超时未接由 CallAlert 管。
     */
    private fun showIncomingCallNotification(characterName: String, sessionId: String, callTs: Long) {
        val fullScreen = Intent(this, IncomingCallActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(IncomingCallActivity.EXTRA_SESSION_ID, sessionId)
            putExtra(IncomingCallActivity.EXTRA_CHARACTER_NAME, characterName)
            putExtra(IncomingCallActivity.EXTRA_CALL_TS, callTs)
        }
        val fullScreenPending = PendingIntent.getActivity(
            this, 60, fullScreen,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        fun buildAction(actionName: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
            this, code,
            Intent(this, CallActionReceiver::class.java).apply {
                action = actionName
                putExtra(CallActionReceiver.EXTRA_SESSION_ID, sessionId)
                putExtra(CallActionReceiver.EXTRA_CALL_TS, callTs)
            },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(this, CH_CALLS)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(characterName)
            .setContentText("语音来电…")
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setOngoing(true)
            .setAutoCancel(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setFullScreenIntent(fullScreenPending, true)
            .setContentIntent(fullScreenPending)
            .addAction(0, "拒接", buildAction(CallActionReceiver.ACTION_DECLINE, 61))
            .addAction(0, "接听", buildAction(CallActionReceiver.ACTION_ANSWER, 62))
            .build()
        getSystemService(NotificationManager::class.java).notify(CallAlert.NOTIF_CALL_ID, notification)
        CallAlert.start(this, sessionId, characterName) {
            // 超时未接：收场 + 换一条"未接来电"普通通知（正文消息本来就会进聊天）
            CallAlert.stop(this)
            runCatching { showMissedCallNotification(characterName) }
        }
    }

    private fun showMissedCallNotification(characterName: String) {
        val notification = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(characterName)
            .setContentText("未接来电")
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        getSystemService(NotificationManager::class.java).notify(CallAlert.NOTIF_MISSED_ID, notification)
    }

    private fun showMessageNotification(title: String, body: String) {
        val notification = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setAutoCancel(true)
            .setContentIntent(contentIntent())
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .build()
        getSystemService(NotificationManager::class.java).notify(notifId++, notification)
        if (notifId > 400) notifId = 100
    }

    private fun sleepSec(sec: Long) {
        runCatching { Thread.sleep(sec * 1000) }
    }

    private fun pushPrefs() = getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)

    private fun markPollHealthy() {
        pushPrefs().edit()
            .putLong(LAST_POLL_OK_KEY, System.currentTimeMillis())
            .remove(LAST_ERROR_KEY)
            .apply()
    }
}
