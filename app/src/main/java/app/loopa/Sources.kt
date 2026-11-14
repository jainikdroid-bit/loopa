package app.loopa

import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Сторонний сайт с роликами (Erome, Eporner, xVideos, XNXX, Pornhub, RedTube, xHamster, FikFap): своя лента и поиск.
 * Ролики такого сайта — [Gif] с site = [id]; лайк только у себя, автор и теги — поиском по тому же сайту.
 *
 * Многие сайты не дают адрес видео в выдаче: он лежит на странице ролика и часто подписан и истекает. Тогда в [Gif.hd] и
 * [Gif.sd] кладётся «ленивый» адрес [lazy] (loopa://{id}/{ключ}/{hd|sd}), а настоящий узнаётся при открытии через
 * [resolve] ([Video] подставляет его в запрос, [Downloads] — перед скачиванием). Ключ кэша видео — ленивый адрес, поэтому
 * кэш не теряется, когда подпись ссылки меняется.
 */
interface Source {
    /** Имя в данных и настройках: «xvideos» — это [Gif.site], ключ галочки и режим ленты. */
    val id: String
    /** Название для людей: «xVideos». */
    val title: String
    /** Значок на плитке, 2 буквы: «XV». */
    val badge: String
    /** Подпись в окне приветствия: что за ролики. */
    val about: String
    /** Галочка по умолчанию у нового пользователя. */
    val default: Boolean get() = false
    /** Ленивые адреса сайта — HLS-плейлисты: их не кэшировать (подпись в адресах сегментов истекает), см. [Sources.uncached]. */
    val hlsOnly: Boolean get() = false
    /** Сколько секунд в начале ролика идёт заставка/лого сайта; плеер и превью начинают после неё ([Sources.intro]). */
    val introSec: Double get() = 0.0

    /** Страница ленты: q пусто — популярное сайта, иначе поиск; fresh — сначала новые; p — номер страницы с 1. */
    fun page(q: String, fresh: Boolean, p: Int): Page

    /** Настоящий адрес видео по ключу из [lazy]; hd — лучшее качество, иначе лёгкое. null — сайт отдаёт адреса сразу. */
    fun resolve(key: String, hd: Boolean): Resolved? = null
}

/** Настоящий адрес видео и заголовки, без которых CDN отказывает (Referer и т. п.). */
class Resolved(val url: String, val headers: Map<String, String> = emptyMap())

object Sources {
    const val SCHEME = "loopa"
    /** Сколько помнить найденный адрес: подписи ссылок живут от часа, берём с запасом. */
    private const val TTL_MS = 30 * 60_000L

    /** Все сайты по порядку вкладок. Заполняется в одном месте — здесь. */
    val ALL: List<Source> by lazy { listOf(Erome, Eporner, XVideos, Xnxx, Pornhub, RedTube, XHamster, FikFap) }
    fun byId(id: String?): Source? = ALL.firstOrNull { it.id == id }

    /**
     * Где начинать ролик, мс: после заставки сайта ([Source.introSec]); 0 — у RedGIFs, Reddit и сайтов без заставки.
     * Не больше трети длительности, если она известна (в коротком ролике заставка — тоже часть сюжета).
     */
    fun intro(g: Gif): Long {
        val s = byId(g.site)?.introSec ?: return 0
        if (s <= 0) return 0
        val ms = (s * 1000).toLong()
        return if (g.duration > 0) minOf(ms, (g.duration * 1000 / 3).toLong()) else ms
    }

    /** Ленивый адрес ролика: loopa://{site}/{ключ}/{hd|sd}. */
    fun lazy(site: String, key: String, hd: Boolean) = "$SCHEME://$site/" + URLEncoder.encode(key, "UTF-8") + if (hd) "/hd" else "/sd"
    fun isLazy(url: String) = url.startsWith("$SCHEME://")

    private class Hit(val r: Resolved, val at: Long)
    private val memo = ConcurrentHashMap<String, Hit>()
    /** Замок на адрес: превью, плеер и предзагрузка одного ролика спрашивают сайт один раз, а не наперегонки. */
    private val locks = ConcurrentHashMap<String, Any>()

    /** Ленивый HLS-плейлист: в дисковый кэш не класть и заранее не качать — через час-три его сегменты недоступны. */
    fun uncached(url: String) = isLazy(url) && byId(url.removePrefix("$SCHEME://").substringBefore('/'))?.hlsOnly == true

    /** Настоящий адрес для ленивого (сеть, вызывать не в UI-потоке); обычный адрес — как есть. Не вышло — исключение. */
    fun resolve(url: String): Resolved {
        if (!isLazy(url)) return Resolved(url)
        memo[url]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.let { return it.r }
        return synchronized(locks.computeIfAbsent(url) { Any() }) {
            memo[url]?.takeIf { System.currentTimeMillis() - it.at < TTL_MS }?.r ?: fetch(url)
        }
    }

    private fun fetch(url: String): Resolved {
        val parts = url.removePrefix("$SCHEME://").split('/')
        require(parts.size == 3) { "плохой адрес $url" }
        val src = byId(parts[0]) ?: throw IllegalArgumentException("нет сайта ${parts[0]}")
        val r = src.resolve(URLDecoder.decode(parts[1], "UTF-8"), parts[2] == "hd") ?: throw IllegalStateException("${src.title}: нет видео")
        memo[url] = Hit(r, System.currentTimeMillis())
        return r
    }

    /** Забыть адрес (истёк, 403) — следующий [resolve] спросит сайт заново. */
    fun forget(url: String) { memo.remove(url) }
}
