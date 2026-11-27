package app.loopa

import android.app.Activity
import android.app.AlertDialog
import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.UUID

/**
 * Анонимная статистика — выключена до явного согласия при первом запуске
 * и с переключателем в Настройки → О приложении. Что уходит: случайный номер установки, версия приложения и Android, модель
 * устройства, язык, тип сети, время работы приложения на экране (начало/конец сеансов), счётчики функций и качества воспроизведения.
 * Страну и регион сервер берёт из метаданных соединения; IP-адреса не сохраняются. НЕ уходит: поисковые запросы, теги,
 * лайки, история, адреса роликов, содержимое графа вкусов, аккаунты. Сервер — Cloudflare Worker (stats-worker/).
 */
object Stats {
    private const val URL_PING = "https://loopa-stats.dom-dcbb13.workers.dev/v2/ping"
    /** Ключ приёма: только отсекает случайный мусор, читать им статистику нельзя. */
    private const val KEY = "DK_12z4Qu_USvFxkXBCiKA"
    private const val HEARTBEAT_MS = 60_000L
    private const val MIN_SESSION_MS = 3_000L
    private const val MAX_PENDING = 100

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var app: Application
    private val prefs get() = app.getSharedPreferences("loopa", Context.MODE_PRIVATE)
    private var started = 0
    private var sending = false
    private var queuedForeground: Long? = null
    private var connection: HttpURLConnection? = null
    private var generation = 0L
    private const val BATCH = "stats_batch_v2"
    private const val ASKED = "stats_asked_v2"
    private var sessionStart = 0L
    private val beat = object : Runnable { override fun run() { ping(); ui.postDelayed(this, HEARTBEAT_MS) } }

    /** Включается только после явного согласия; выключается в Настройках. */
    val enabled get() = ::app.isInitialized && prefs.getBoolean(ASKED, false) && prefs.getBoolean("stats_on", false)
    val asked get() = ::app.isInitialized && prefs.getBoolean(ASKED, false)

    fun init(a: Application) {
        if (::app.isInitialized) return
        app = a
        LocalTechnicalDiagnostics.init(a)
        // Do not upload the old queue under a new consent policy.
        if (!asked) {
            val sp = prefs
            val edit = sp.edit().remove("stats_pending").remove("stats_events").remove(BATCH).remove("stats_iid")
            // Preserve an existing explicit opt-out; opt-in under the old default is not consent.
            if (sp.contains("stats_on") && !sp.getBoolean("stats_on", false)) edit.putBoolean(ASKED, true)
            else edit.remove("stats_on")
            edit.apply()
        }
        a.registerActivityLifecycleCallbacks(object : Application.ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: Activity) { if (started++ == 0) foreground() }
            override fun onActivityStopped(activity: Activity) { if (--started <= 0) { started = 0; background() } }
            override fun onActivityCreated(activity: Activity, s: Bundle?) {}
            override fun onActivityResumed(activity: Activity) {}
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivitySaveInstanceState(activity: Activity, s: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })
    }

    private fun foreground() {
        sessionStart = System.currentTimeMillis()
        if (enabled) { ui.removeCallbacks(beat); ui.post(beat) }
    }

    private fun background() {
        ui.removeCallbacks(beat)
        val a = sessionStart; sessionStart = 0
        if (!enabled || a == 0L) return
        val b = System.currentTimeMillis()
        if (b - a >= MIN_SESSION_MS) addPending(a, b)
        send(fg = 0L)
    }

    @Synchronized fun setEnabled(on: Boolean) {
        cancelDelivery()
        val edit = prefs.edit().putBoolean("stats_on", on).putBoolean(ASKED, true)
        if (!on) {
            ui.removeCallbacks(beat)
            sessionStart = 0
            edit.remove("stats_pending").remove("stats_events").remove(BATCH).remove("stats_iid")
        }
        edit.apply()
        if (on && started > 0) {
            sessionStart = System.currentTimeMillis()
            ui.removeCallbacks(beat)
            ui.post(beat)
        }
    }

    enum class Event {
        GRAPH, PLAYER, DOWNLOAD, SEARCH_SUBMIT, FEED_FORYOU, FEED_TRENDING, FEED_LIKES, FEED_REDDIT, FEED_OTHER,
        PLAYER_FIRST_FRAME, PLAYER_ERROR, PLAYER_START_LT_1S, PLAYER_START_1_3S, PLAYER_START_3_8S, PLAYER_START_GE_8S,
        DOWNLOAD_QUEUED, DOWNLOAD_SAVED, DOWNLOAD_FAILED, SETTINGS_OPEN, SOURCE_CHECK_OPEN,
        SEARCH_PAGE_RESULTS, SEARCH_PAGE_EMPTY, SEARCH_PAGE_ERROR, FEED_LOAD_ERROR, PLAYER_STALL_1S, PLAYER_AUTO_SD;

        internal val key = name.lowercase(Locale.ROOT)
    }

    /** Имена фиксированы. В событии передаётся счётчик без дополнительных полей. */
    fun event(vararg events: Event) {
        synchronized(this) {
            if (!enabled) return
            val o = JSONObject(prefs.getString("stats_events", "{}") ?: "{}")
            events.forEach { event -> o.put(event.key, o.optInt(event.key) + 1) }
            prefs.edit().putString("stats_events", o.toString()).apply()
        }
    }

    fun firstFrame(elapsedMs: Long) {
        val bucket = when {
            elapsedMs < 1_000L -> Event.PLAYER_START_LT_1S
            elapsedMs < 3_000L -> Event.PLAYER_START_1_3S
            elapsedMs < 8_000L -> Event.PLAYER_START_3_8S
            else -> Event.PLAYER_START_GE_8S
        }
        event(Event.PLAYER_FIRST_FRAME, bucket)
    }

    private fun ping() { if (enabled && sessionStart > 0) send(fg = sessionStart) }

    @Synchronized private fun addPending(a: Long, b: Long) {
        if (!enabled) return
        val arr = JSONArray(prefs.getString("stats_pending", "[]") ?: "[]")
        arr.put(JSONObject().put("a", a).put("b", b))
        val keep = JSONArray()
        for (i in maxOf(0, arr.length() - MAX_PENDING) until arr.length()) keep.put(arr.get(i))
        prefs.edit().putString("stats_pending", keep.toString()).apply()
    }

    private fun installId(): String =
        prefs.getString("stats_iid", null) ?: UUID.randomUUID().toString().also { prefs.edit().putString("stats_iid", it).apply() }

    /** Сброс номера установки: старая статистика с новой не связывается. */
    @Synchronized fun resetId() {
        cancelDelivery()
        prefs.edit().remove("stats_iid").remove("stats_pending").remove("stats_events").remove(BATCH).apply()
        sessionStart = if (enabled && started > 0) System.currentTimeMillis() else 0L
    }

    private fun cancelDelivery() {
        generation++
        queuedForeground = null
        connection?.let { stale ->
            connection = null
            Thread({ runCatching { stale.disconnect() } }, "loopa-stats-cancel").start()
        }
    }

    private fun netType(): String = runCatching {
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val c = cm.getNetworkCapabilities(cm.activeNetwork) ?: return "none"
        when {
            c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            c.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
    }.getOrDefault("none")

    /** Отправка в фоне; не вышло (нет сети) — сеансы остаются в очереди и уйдут при следующем сигнале. */
    @Synchronized private fun send(fg: Long) {
        if (!enabled) return
        queuedForeground = fg
        if (sending) return
        sending = true
        Thread(::drain, "loopa-stats").start()
    }

    private fun drain() {
        while (true) {
            val (fg, epoch) = synchronized(this) {
                val next = queuedForeground
                if (!enabled || next == null) {
                    sending = false
                    return
                }
                queuedForeground = null
                next to generation
            }
            val delivered = try {
                sendBatch(fg, epoch)
            } catch (e: Exception) {
                android.util.Log.w("Loopa", "Telemetry delivery failed: " + e.javaClass.simpleName)
                false
            }
            synchronized(this) {
                connection = null
                if (delivered && enabled && generation == epoch && queuedForeground == null &&
                    (prefs.getString("stats_pending", "[]") != "[]" || prefs.getString("stats_events", "{}") != "{}")) {
                    queuedForeground = fg
                }
            }
        }
    }

    private fun sendBatch(fg: Long, epoch: Long): Boolean {
        val body: String
        synchronized(this) {
            if (!enabled || generation != epoch) return false
            val existing = prefs.getString(BATCH, null)
            if (existing != null) body = existing
            else {
                val pending = JSONArray(prefs.getString("stats_pending", "[]") ?: "[]")
                val events = JSONObject(prefs.getString("stats_events", "{}") ?: "{}")
                val sent = JSONArray()
                val left = JSONArray()
                for (i in 0 until pending.length()) {
                    if (i < 50) sent.put(pending.get(i)) else left.put(pending.get(i))
                }
                val evSent = JSONObject()
                val evLeft = JSONObject()
                for (event in Event.entries) {
                    val key = event.key
                    val n = events.optInt(key)
                    if (n <= 0) continue
                    val chunk = n.coerceAtMost(999_999)
                    evSent.put(key, chunk)
                    if (n > chunk) evLeft.put(key, n - chunk)
                }
                body = JSONObject().put("iid", installId()).put("batch", UUID.randomUUID().toString())
                    .put("ver", BuildConfig.VERSION_NAME).put("os", "Android " + Build.VERSION.RELEASE)
                    .put("dev", (Build.MANUFACTURER + " " + Build.MODEL).take(60))
                    .put("lang", Locale.getDefault().language).put("net", netType())
                    .put("fg", fg).put("sessions", sent).put("events", evSent).toString()
                // Move only the transmitted chunk into a durable retry envelope.
                if (!prefs.edit().putString(BATCH, body).putString("stats_pending", left.toString())
                    .putString("stats_events", evLeft.toString()).commit()) return false
            }
        }
        val ok = runCatching {
                val c = URL(URL_PING).openConnection() as HttpURLConnection
                try {
                    synchronized(this) {
                        if (!enabled || generation != epoch) return@runCatching false
                        connection = c
                    }
                    c.connectTimeout = 10_000; c.readTimeout = 10_000
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("x-key", KEY); c.setRequestProperty("content-type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                    c.responseCode in 200..299 && c.inputStream.bufferedReader().use {
                        JSONObject(it.readText()).optString("batch") == JSONObject(body).getString("batch")
                    }
                } finally { c.disconnect() }
        }.getOrDefault(false)
        return synchronized(this) {
            ok && epoch == generation && prefs.getString(BATCH, null) == body &&
                prefs.edit().remove(BATCH).commit()
        }
    }

    /** Окно согласия при первом запуске (один раз); без ответа статистика остаётся выключенной. */
    fun askOnce(a: Activity) {
        if (asked) return
        AlertDialog.Builder(a).setTitle("Анонимная статистика / Anonymous stats")
            .setCancelable(false).setMessage(
                "При вашем согласии приложение отправляет разработчику технические данные: случайный номер установки, версии приложения и Android, " +
                    "модель устройства, язык, тип сети, страну и регион, время в приложении и счётчики функций, ошибок и задержек воспроизведения. " +
                    "Время запуска видео учитывается по диапазонам. IP-адреса не сохраняются. Запросы, теги, история и ссылки не отправляются. " +
                    "Согласие можно отозвать в разделе «Настройки → О приложении».\n\n" +
                    "If you opt in, the app sends the developer technical data: a random install ID, app and Android versions, " +
                    "device model, language, network type, country and region, time in the app, and counters for feature use, playback errors and delays. " +
                    "Video startup times are counted in ranges. IP addresses are not stored. Searches, tags, history and links are not sent. " +
                    "You can withdraw consent in Settings → About."
            )
            .setPositiveButton("Разрешить / Allow") { _, _ -> setEnabled(true) }
            .setNegativeButton("Не разрешать / Decline") { _, _ -> setEnabled(false) }
            .show()
    }
}
