package app.loopa

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import org.json.JSONObject

/** Класс железа телефона: решает, насколько подробно рисовать 3D-граф. Логика графа от него не зависит. */
enum class Tier { LOW, MID, HIGH }

/** Параметры отрисовки уровня; [thinLinks] — связи тонкими GL-линиями (без цилиндров), подсветка только цветом. */
data class RenderParams(
    val maxNodes: Int, val maxLinks: Int, val labels: Int, val nodeResolution: Int,
    val pixelRatio: Double, val cooldownTicks: Int, val warmupTicks: Int, val thinLinks: Boolean,
)

object DeviceTier {
    const val LOW_RAM_MB = 3072L
    const val MID_RAM_MB = 6144L
    const val LOW_CORES = 4
    const val MID_CORES = 6

    /** Чистая функция (без Android) — пороги проверяются тестами. */
    fun tier(ramMb: Long, cores: Int, lowRam: Boolean, is64: Boolean): Tier = when {
        lowRam || ramMb < LOW_RAM_MB || cores <= LOW_CORES || !is64 -> Tier.LOW
        ramMb < MID_RAM_MB || cores <= MID_CORES -> Tier.MID
        else -> Tier.HIGH
    }

    fun params(t: Tier): RenderParams = when (t) {
        Tier.LOW -> RenderParams(300, 600, 15, 4, 1.0, 60, 30, true)
        Tier.MID -> RenderParams(800, 2000, 25, 6, 1.25, 100, 50, false)
        Tier.HIGH -> RenderParams(2000, 6000, 30, 8, 1.5, 150, 60, false)
    }

    /** Параметры всех уровней для страницы: понижение на ходу берёт нужный из этой таблицы. */
    fun paramsJson(): JSONObject = JSONObject().also { o ->
        Tier.values().forEach { t ->
            val p = params(t)
            o.put(t.name, JSONObject().put("maxNodes", p.maxNodes).put("maxLinks", p.maxLinks).put("labels", p.labels)
                .put("nodeResolution", p.nodeResolution).put("pixelRatio", p.pixelRatio)
                .put("cooldownTicks", p.cooldownTicks).put("warmupTicks", p.warmupTicks).put("thinLinks", p.thinLinks))
        }
    }

    /** Железо этого телефона. */
    fun detect(ctx: Context): Tier = tier(ramMb(ctx), Runtime.getRuntime().availableProcessors(), lowRam(ctx), is64())

    fun ramMb(ctx: Context): Long {
        val mi = ActivityManager.MemoryInfo()
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(mi)
        return mi.totalMem / (1024 * 1024)
    }

    fun lowRam(ctx: Context) = (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).isLowRamDevice
    fun is64() = Build.SUPPORTED_64_BIT_ABIS.isNotEmpty()

    /**
     * Сколько живых превью в сетке тянет железо: по классу (слабый 3, средний 5, мощный 8) и по числу аппаратных
     * декодеров H.264 [avc] (0 — не узнали) за вычетом [PLAYER_DECODERS] на плеер. Не меньше 1.
     */
    fun previewLimit(t: Tier, avc: Int): Int {
        val byTier = when (t) { Tier.LOW -> 3; Tier.MID -> 5; Tier.HIGH -> 8 }
        return if (avc > 0) minOf(byTier, (avc - PLAYER_DECODERS).coerceAtLeast(1)) else byTier
    }
    const val PLAYER_DECODERS = 2

    /**
     * Сколько превью играет разом всего: аппаратных хватает на [previewLimit], остальные — с программным декодером
     * (SD-превью ему по силам). На слабом телефоне программный декодер тяжёл для процессора — там не больше 3.
     */
    fun previewTotal(t: Tier): Int = when (t) { Tier.LOW -> 3; Tier.MID -> 6; Tier.HIGH -> 8 }

    @Volatile private var avcCache = -1

    /** Значение [avcInstances], если фоновый опрос уже закончился, иначе 0 («не узнали» — лимит берётся по классу телефона). */
    fun avcInstancesCached(): Int = avcCache.coerceAtLeast(0)

    /** Один раз в фоне опросить декодеры ([avcInstances], MediaCodecList небыстр): вызывать при старте приложения. */
    fun warmAvc() {
        if (avcCache >= 0) return
        kotlin.concurrent.thread(name = "avc-probe", isDaemon = true) { if (avcCache < 0) avcCache = avcInstances() }
    }

    /**
     * Сколько экземпляров аппаратного декодера H.264 даёт телефон: наибольшее из аппаратных декодеров (у нишевого —
     * например, для защищённого или специального видео — предел мал, минимум дал бы ложную единицу). 0 — не узнали.
     */
    fun avcInstances(): Int = runCatching {
        val hw = android.media.MediaCodecList(android.media.MediaCodecList.REGULAR_CODECS).codecInfos.filter { ci ->
            !ci.isEncoder && !ci.name.endsWith(".secure") && ci.supportedTypes.any { it.equals("video/avc", true) } &&
                if (Build.VERSION.SDK_INT >= 29) ci.isHardwareAccelerated else !ci.name.startsWith("OMX.google.") && !ci.name.startsWith("c2.android.")
        }
        hw.maxOfOrNull { it.getCapabilitiesForType("video/avc").maxSupportedInstances } ?: 0
    }.getOrDefault(0)
}
