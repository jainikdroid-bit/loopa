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
 * Анонимная статистика — включена по умолчанию, но после уведомления при первом запуске (кнопка «Выключить» там же)
 * и с переключателем в Настройки → О приложении. Что уходит: случайный номер установки, версия приложения и Android, модель
 * устройства, язык, тип сети, время работы приложения на экране (начало/конец сеансов), счётчики открытия функций.
 * Страну и регион сервер берёт из метаданных соединения; IP-адреса не сохраняются. НЕ уходит: поисковые запросы, теги,
 * лайки, история, адреса роликов, содержимое графа вкусов, аккаунты. Сервер — Cloudflare Worker (stats-worker/).
 */
object Stats {
    private const val URL_PING = "https://loopa-stats.dom-dcbb13.workers.dev/v1/ping"
    /** Ключ приёма: только отсекает случайный мусор, читать им статистику нельзя. */
    private const val KEY = "DK_12z4Qu_USvFxkXBCiKA"
    private const val HEARTBEAT_MS = 60_000L
    private const val MIN_SESSION_MS = 3_000L
    private const val MAX_PENDING = 100

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var app: Application
    private val prefs get() = app.getSharedPreferences("loopa", Context.MODE_PRIVATE)
    private var started = 0
    private var sessionStart = 0L
    private val beat = object : Runnable { override fun run() { ping(); ui.postDelayed(this, HEARTBEAT_MS) } }

    /** Включена по умолчанию, но не раньше, чем пользователь увидел уведомление ([askOnce]); выключается там же и в Настройках. */
    val enabled get() = ::app.isInitialized && prefs.getBoolean("stats_asked", false) && prefs.getBoolean("stats_on", true)
    val asked get() = ::app.isInitialized && prefs.getBoolean("stats_asked", false)

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

    fun setEnabled(on: Boolean) {
        prefs.edit().putBoolean("stats_on", on).putBoolean("stats_asked", true).apply()
        if (!on) { ui.removeCallbacks(beat); prefs.edit().remove("stats_pending").remove("stats_events").apply() }
        else if (started > 0) { sessionStart = System.currentTimeMillis(); ui.removeCallbacks(beat); ui.post(beat) }
    }

    /** Счётчик открытия функции («graph», «player», «download»): только число, без содержимого. */
    fun event(name: String) {
        if (!enabled) return
        synchronized(this) {
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
    fun resetId() { prefs.edit().remove("stats_iid").apply() }

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
        val sent: String
        val evSent: String
        synchronized(this) {
            sent = prefs.getString("stats_pending", "[]") ?: "[]"
            evSent = prefs.getString("stats_events", "{}") ?: "{}"
        }
        val body = JSONObject().put("iid", installId()).put("ver", BuildConfig.VERSION_NAME).put("os", "Android " + Build.VERSION.RELEASE)
            .put("dev", (Build.MANUFACTURER + " " + Build.MODEL).take(60)).put("lang", Locale.getDefault().language).put("net", netType())
            .put("fg", fg).put("sessions", JSONArray(sent)).put("events", JSONObject(evSent))
        Thread {
            val ok = runCatching {
                val c = URL(URL_PING).openConnection() as HttpURLConnection
                c.connectTimeout = 10_000; c.readTimeout = 10_000; c.requestMethod = "POST"; c.doOutput = true
                c.setRequestProperty("x-key", KEY); c.setRequestProperty("content-type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
                (c.responseCode in 200..299).also { c.disconnect() }
            }.getOrDefault(false)
            if (ok) synchronized(this) { // убрать только ушедшее: за время запроса могло накопиться новое
                val left = JSONArray()
                val cur = JSONArray(prefs.getString("stats_pending", "[]") ?: "[]")
                val gone = JSONArray(sent)
                for (i in gone.length() until cur.length()) left.put(cur.get(i))
                val ev = JSONObject(prefs.getString("stats_events", "{}") ?: "{}")
                val es = JSONObject(evSent)
                es.keys().forEach { k -> val n = ev.optInt(k) - es.optInt(k); if (n > 0) ev.put(k, n) else ev.remove(k) }
                prefs.edit().putString("stats_pending", left.toString()).putString("stats_events", ev.toString()).apply()
            }
        }.start()
    }

    /** Окно согласия при первом запуске (один раз); без ответа статистика остаётся выключенной. */
    fun askOnce(a: Activity) {
        if (asked) return
        prefs.edit().putBoolean("stats_asked", true).apply()
        if (prefs.contains("stats_on")) return
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
