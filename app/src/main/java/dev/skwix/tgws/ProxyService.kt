package dev.skwix.tgws
import android.app.*
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.chaquo.python.PyObject
import com.chaquo.python.Python
import com.chaquo.python.android.AndroidPlatform
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
object ProxyState {
    val status = MutableStateFlow("stopped")
    val error = MutableStateFlow("")
    val logs = MutableStateFlow("")
    @Volatile var readLogs = false
}
class ProxyService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var monitoring = false
    @Volatile private var stopRequested = false
    @Volatile private var engine: PyObject? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("proxy", "Работа прокси", NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == "STOP") {
            stopRequested = true
            ProxyState.status.value = "stopping"
            scope.launch { engine?.callAttr("stop") }
            if (!monitoring) stopSelf()
            return START_NOT_STICKY
        }
        startForeground(1, notification("Запускается…"))
        if (monitoring) return START_NOT_STICKY
        monitoring = true
        stopRequested = false
        ProxyState.status.value = "starting"
        scope.launch { runProxy() }
        return START_NOT_STICKY
    }
    private suspend fun runProxy() {
        try {
            if (!Python.isStarted()) Python.start(AndroidPlatform(applicationContext))
            val bridge = Python.getInstance().getModule("android_bridge")
            engine = bridge
            val prefs = getSharedPreferences("settings", MODE_PRIVATE)
            bridge.callAttr("start", prefs.getInt("port", 1443), prefs.getString("secret", ""),
                prefs.getBoolean("verbose", false), prefs.getBoolean("reconnect", true),
                prefs.getString("routing", "dc4"))
            if (stopRequested) bridge.callAttr("stop")
            var previous = ""
            while (currentCoroutineContext().isActive) {
                val state = bridge.callAttr("status").toString()
                ProxyState.status.value = state
                if (state != previous) {
                    ProxyState.error.value = bridge.callAttr("error").toString()
                    val message = when (state) {
                        "running" -> "Прокси запущен"
                        "starting" -> "Запускается…"
                        else -> "Остановка…"
                    }
                    getSystemService(NotificationManager::class.java).notify(1, notification(message))
                    previous = state
                }
                if (ProxyState.readLogs || state == "error" || state == "stopped") {
                    ProxyState.logs.value = bridge.callAttr("logs").toString()
                }
                if (state == "stopped" || state == "error") break
                delay(if (state == "running") 1000L else 200L)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ProxyState.error.value = e.message ?: "Ошибка запуска"
            ProxyState.status.value = "error"
        } finally {
            engine?.callAttr("stop")
            monitoring = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }
    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, ProxyService::class.java).setAction("STOP"), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, "proxy")
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle("TG WS Proxy").setContentText(text).setContentIntent(open)
            .setOngoing(true).addAction(0, "Остановить", stop).build()
    }
    override fun onDestroy() {
        stopRequested = true
        scope.cancel()
        super.onDestroy()
    }
}
