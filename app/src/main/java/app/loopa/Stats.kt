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
 * устройства, язык, тип сети, время работы приложения на экране (начало/конец сеансов), счётчики открытия функций.
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
        generation++
        prefs.edit().putBoolean("stats_on", on).putBoolean(ASKED, true).apply()
        if (!on) { ui.removeCallbacks(beat); prefs.edit().remove("stats_pending").remove("stats_events").remove(BATCH).apply() }
        else if (started > 0) { sessionStart = System.currentTimeMillis(); ui.removeCallbacks(beat); ui.post(beat) }
    }

    /** Счётчик открытия функции («graph», «player», «download»): только число, без содержимого. */
    fun event(name: String) {
        if (!enabled) return
        synchronized(this) {
            if (!enabled) return
            val o = JSONObject(prefs.getString("stats_events", "{}") ?: "{}")
            o.put(name, o.optInt(name) + 1)
            prefs.edit().putString("stats_events", o.toString()).apply()
        }
    }

    private fun ping() { if (enabled && sessionStart > 0) send(fg = sessionStart) }

    @Synchronized private fun addPending(a: Long, b: Long) {
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
        generation++
        prefs.edit().remove("stats_iid").remove("stats_pending").remove("stats_events").remove(BATCH).apply()
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
    private fun send(fg: Long) {
        val body: String
        val epoch: Long
        synchronized(this) {
            if (!enabled || sending) return
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
                events.keys().asSequence().sorted().forEach { k ->
                    val n = events.optInt(k)
                    if (n > 0) {
                        if (evSent.length() < 30) {
                            val chunk = n.coerceAtMost(999_999)
                            evSent.put(k, chunk)
                            if (n > chunk) evLeft.put(k, n - chunk)
                        } else evLeft.put(k, n)
                    }
                }
                body = JSONObject().put("iid", installId()).put("batch", UUID.randomUUID().toString())
                    .put("ver", BuildConfig.VERSION_NAME).put("os", "Android " + Build.VERSION.RELEASE)
                    .put("dev", (Build.MANUFACTURER + " " + Build.MODEL).take(60))
                    .put("lang", Locale.getDefault().language).put("net", netType())
                    .put("fg", fg).put("sessions", sent).put("events", evSent).toString()
                // Move only the transmitted chunk into a durable retry envelope.
                prefs.edit().putString(BATCH, body).putString("stats_pending", left.toString())
                    .putString("stats_events", evLeft.toString()).apply()
            }
            epoch = generation
            sending = true
        }
        Thread {
            val ok = runCatching {
                if (!synchronized(this) { enabled && generation == epoch }) return@runCatching false
                val c = URL(URL_PING).openConnection() as HttpURLConnection
                try {
                    c.connectTimeout = 10_000; c.readTimeout = 10_000
                    c.requestMethod = "POST"; c.doOutput = true
                    c.setRequestProperty("x-key", KEY); c.setRequestProperty("content-type", "application/json")
                    c.outputStream.use { it.write(body.toByteArray()) }
                    c.responseCode in 200..299 && c.inputStream.bufferedReader().use {
                        JSONObject(it.readText()).optString("batch") == JSONObject(body).getString("batch")
                    }
                } finally { c.disconnect() }
            }.getOrDefault(false)
            synchronized(this) {
                if (ok && epoch == generation && prefs.getString(BATCH, null) == body) {
                    prefs.edit().remove(BATCH).apply()
                }
                sending = false
            }
        }.start()
    }

    /** Окно согласия при первом запуске (один раз); без ответа статистика остаётся выключенной. */
    fun askOnce(a: Activity) {
        if (asked) return
        AlertDialog.Builder(a).setTitle("Анонимная статистика / Anonymous stats")
            .setCancelable(false).setMessage(
                "Приложение отправляет разработчику анонимные данные: случайный номер установки, версию, модель устройства, " +
                    "страну, время работы приложения и счётчики функций. Без IP, запросов, тегов, истории и ссылок. " +
                    "Это можно выключить сейчас или позже: Настройки → О приложении.\n\n" +
                    "The app sends the developer anonymous data: a random install ID, version, device model, country, " +
                    "time spent in the app and feature counters. No IP, searches, tags, history or links. You can turn it off now or later in Settings → About."
            )
            .setPositiveButton("ОК / OK") { _, _ -> setEnabled(true) }
            .setNegativeButton("Выключить / Turn off") { _, _ -> setEnabled(false) }
            .show()
    }
}
