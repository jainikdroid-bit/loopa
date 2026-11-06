package app.loopa

/**
 * Свой словарь синонимов для нишевых тем: на RedGIFs у них нет тегов, текстовый поиск нечёткий, а связи с больших сайтов
 * по коротким словам — мусор («knob» → «japanese sex»). Ключ — корень слова (или слитная фраза), значения — фразы для поиска.
 * Дополнять по мере надобности.
 */
object Thesaurus {
    private val MAP: Map<String, List<String>> = mapOf(
        "knob" to listOf("doorknob", "door knob", "gear knob", "gearshift", "gear shift", "stick shift", "bedpost", "object insertion"),
        "gearshift" to listOf("gear shift", "gear stick", "stick shift", "gear knob", "shifter", "gear lever"),
        "gearstick" to listOf("gearshift", "gear shift", "stick shift", "gear knob"),
        "stickshift" to listOf("gearshift", "gear shift", "gear stick", "gear knob"),
        "shifter" to listOf("gearshift", "gear shift", "gear stick", "stick shift"),
        "bedpost" to listOf("bed post", "bedpost riding", "furniture humping", "object insertion", "riding furniture"),
        "doorknob" to listOf("door knob", "doorknob insertion", "object insertion"),
        "midget" to listOf("dwarf", "little people", "little person", "short stack"),
        "dwarf" to listOf("midget", "little people", "little person"),
        "pillow" to listOf("pillow humping", "pillow riding", "humping pillow"),
        "humping" to listOf("pillow humping", "furniture humping", "dry humping", "grinding"),
        "insertion" to listOf("object insertion", "household objects", "vegetable insertion"),
        "cucumber" to listOf("vegetable insertion", "zucchini", "eggplant"),
        "bottle" to listOf("bottle insertion", "bottle fuck"),
        "squirt" to listOf("squirting", "gushing", "female ejaculation"),
        "facesitting" to listOf("face sitting", "smothering", "queening"),
        "feet" to listOf("foot fetish", "soles", "toes"),
        "armpit" to listOf("armpits", "armpit fetish", "hairy armpits"),
        "tickle" to listOf("tickling", "tickled", "tickle torture"),
        "wedgie" to listOf("wedgies", "doorknob wedgie", "atomic wedgie"),
    )

    /** Синонимы запроса: по слитной фразе и по корням слов (без окончаний). */
    fun of(q: String): List<String> {
        val n = q.lowercase().replace(Regex("[^a-z0-9]"), "")
        val words = q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
            .map { it.replace(Regex("(ers|er|ings|ing|es|s)$"), "").takeIf { r -> r.length >= 3 } ?: it }
        val keys = (listOf(n) + words).distinct()
        return keys.flatMap { k -> MAP[k] ?: MAP.entries.firstOrNull { (key, _) -> key.length >= 4 && (k.startsWith(key) || key.startsWith(k) && k.length >= 4) }?.value ?: emptyList() }
            .filter { it.replace(" ", "") != n }.distinct()
    }
}
