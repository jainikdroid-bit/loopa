package app.loopa

/** Export only coarse operational counts. Never include search terms, media, labels or model features. */
internal object DiagnosticPolicy {
    val technicalKinds = setOf(
        "first_frame", "buffering", "playback_error", "player_background",
        "preview_start", "preview_error", "preview_release", "prefetch_cancel"
    )
    private const val MAX_FEATURES = 128
    private const val MAX_NAME = 512
    private const val MAX_WEIGHT = 100f

    fun validSample(features: List<Pair<String, Float>>, y: Float, weight: Float, p: Double): Boolean =
        features.isNotEmpty() && features.size <= MAX_FEATURES &&
        features.all { (name, value) -> name.isNotBlank() && name.length <= MAX_NAME && value.isFinite() && kotlin.math.abs(value) <= 100f } &&
        features.map { it.first }.distinct().size == features.size &&
        y.isFinite() && y in 0f..1f && weight.isFinite() && weight > 0 && weight <= MAX_WEIGHT &&
        p.isFinite() && p in 0.0..1.0

    fun validKind(kind: String): Boolean = kind.length in 1..48 && kind.all { it in 'a'..'z' || it == '_' || it in '0'..'9' }
}
