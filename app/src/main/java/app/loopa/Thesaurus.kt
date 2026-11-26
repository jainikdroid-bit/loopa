package app.loopa

/**
 * Свой словарь синонимов для нишевых тем: на RedGIFs у них нет тегов, текстовый поиск нечёткий, а связи с больших сайтов
 * по коротким словам — мусор («knob» → «japanese sex»). Ключ — корень слова (или слитная фраза), значения — фразы для поиска.
 * Дополнять по мере надобности.
 */
object Thesaurus {
    private val MAP: Map<String, List<String>> = mapOf(
        "knob" to listOf("doorknob", "door knob", "gear knob", "gearshift", "gear shift", "stick shift", "bedpost", "object insertion"),
        "gearknob" to listOf("gearshift", "gear shift", "gear stick", "stick shift", "shifter", "gear lever"),
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

    /** Сокращения — равны полной фразе (ролик с «ass to pussy» на запрос «atp» — точное совпадение). */
    val ABBR: Map<String, List<String>> = mapOf(
        "atp" to listOf("ass to pussy", "a2p", "anal to pussy"),
        "a2p" to listOf("ass to pussy", "atp"),
        "atm" to listOf("ass to mouth", "a2m"),
        "a2m" to listOf("ass to mouth", "atm"),
        "dp" to listOf("double penetration", "dp sex", "double penetrated"),
        "dap" to listOf("double anal", "double anal penetration"),
        "dvp" to listOf("double vaginal", "double vaginal penetration", "double pussy"),
        "tp" to listOf("triple penetration"),
        "bj" to listOf("blowjob", "blow job"),
        "hj" to listOf("handjob", "hand job"),
        "fj" to listOf("footjob", "foot job"),
        "tj" to listOf("titjob", "titty fuck", "tit job"),
        "joi" to listOf("jerk off instruction", "jerk off instructions"),
        "cei" to listOf("cum eating instruction", "cum eating"),
        "cim" to listOf("cum in mouth"),
        "cip" to listOf("cum in pussy", "creampie"),
        "ffm" to listOf("ffm threesome", "two girls one guy"),
        "mmf" to listOf("mmf threesome", "two guys one girl"),
        "bbc" to listOf("big black cock"),
        "bwc" to listOf("big white cock"),
        "pawg" to listOf("phat ass white girl"),
        "milf" to listOf("mom", "mature"),
        "gf" to listOf("girlfriend"),
        "pov" to listOf("point of view"),
        "bdsm" to listOf("bondage", "domination"),
        "cfnm" to listOf("clothed female naked male"),
        "jav" to listOf("japanese"),
        "asmr" to listOf("asmr moaning"),
    )

    /** Полные фразы сокращения (запрос целиком — сокращение) или пусто. */
    fun expandAbbr(q: String): List<String> = ABBR[q.trim().lowercase()].orEmpty()

    /** Синонимы запроса: по слитной фразе и по корням слов (без окончаний). */
    fun of(q: String): List<String> {
        val n = q.lowercase().replace(Regex("[^a-z0-9]"), "")
        val words = q.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 3 }
            .map { it.replace(Regex("(ers|er|ings|ing|es|s)$"), "").takeIf { r -> r.length >= 3 } ?: it }
        val keys = (listOf(n) + words).distinct()
        // сокращения внутри запроса («dp anal», «atp pov») — по словам целиком
        val abbr = q.lowercase().split(Regex("[^a-z0-9]+")).flatMap { ABBR[it].orEmpty() }
        return keys.flatMap { k -> MAP[k] ?: MAP.entries.firstOrNull { (key, _) -> key.length >= 4 && (k.startsWith(key) || key.startsWith(k) && k.length >= 4) }?.value ?: emptyList() }
            .plus(abbr).filter { it.replace(" ", "") != n }.distinct()
    }

    // ---- русские запросы -> английские (теги и названия на источниках английские) ----

    /** Целиком слово (после приведения к нижнему регистру, «ё» = «е»): служебные — пропускаются. */
    private val RU_STOP = setOf("в", "на", "с", "со", "и", "для", "по", "к", "у", "от", "из", "за", "над", "под", "о", "об", "а", "но", "или", "как", "мой", "моя", "её", "ее", "его")

    /** Слова точным совпадением (короткие — по префиксу нельзя: «поп» поймал бы «попытка»). */
    private val RU_WORD = mapOf(
        "поп" to "ass", "попа" to "ass", "попка" to "ass", "попку" to "ass", "жопа" to "ass", "жопу" to "ass",
        "мама" to "mom", "маму" to "mom", "сестра" to "sister", "сестру" to "sister", "брат" to "brother", "жена" to "wife", "жену" to "wife",
        "муж" to "husband", "пара" to "couple", "пары" to "couple", "двое" to "couple", "порно" to "porn",
        "рот" to "mouth", "рта" to "mouth", "ног" to "feet", "ноги" to "feet", "ножки" to "feet", "ступни" to "feet", "стопы" to "feet",
        "сиськи" to "tits", "сиськами" to "tits", "грудь" to "tits", "груди" to "tits", "дом" to "homemade", "душ" to "shower", "душе" to "shower",
        "кино" to "movie", "лицо" to "face", "лицу" to "face", "лице" to "face", "очки" to "glasses", "очках" to "glasses",
        "секс" to "sex", "анал" to "anal", "пов" to "pov", "бдсм" to "bdsm", "милф" to "milf", "гей" to "gay", "геи" to "gay",
    )

    /** Начала слов (длиннее трёх букв): склонения и числа — «блондинка», «блондинку», «блондинки». */
    private val RU_STEM = mapOf(
        "девушк" to "girl", "девочк" to "girl", "женщин" to "woman", "мужчин" to "man", "парн" to "guy", "парень" to "guy",
        "мамоч" to "mom", "мамаш" to "mom", "зрел" to "mature", "студентк" to "student", "школьниц" to "schoolgirl", "медсестр" to "nurse",
        "секретарш" to "secretary", "учительниц" to "teacher", "училк" to "teacher", "русск" to "russian", "азиат" to "asian",
        "японк" to "japanese", "япон" to "japanese", "китаян" to "chinese", "кореянк" to "korean", "латин" to "latina",
        "темнокож" to "ebony", "негритянк" to "ebony", "блондинк" to "blonde", "брюнетк" to "brunette", "рыж" to "redhead",
        "больш" to "big", "огромн" to "huge", "маленьк" to "small", "толст" to "bbw", "пухл" to "chubby", "худощав" to "skinny", "худая" to "skinny", "худые" to "skinny", "стройн" to "slim",
        "сисек" to "tits", "сиськ" to "tits", "задниц" to "ass", "минет" to "blowjob", "отсос" to "blowjob", "вагин" to "pussy", "киск" to "pussy",
        "член" to "cock", "дроч" to "handjob", "мастурб" to "masturbation", "трах" to "fuck", "кончи" to "cumshot", "кончал" to "cumshot",
        "сперм" to "cum", "камшот" to "cumshot", "групп" to "group", "тройнич" to "threesome", "свинг" to "swinger", "лесби" to "lesbian",
        "домашн" to "homemade", "любител" to "amateur", "ролев" to "roleplay", "массаж" to "massage", "ванн" to "bath", "офис" to "office",
        "кухн" to "kitchen", "машин" to "car", "публичн" to "public", "пляж" to "beach", "бассейн" to "pool", "связан" to "bondage",
        "госпож" to "femdom", "чулк" to "stockings", "колгот" to "pantyhose", "белье" to "lingerie", "бель" to "lingerie", "каблук" to "heels",
        "косплей" to "cosplay", "танц" to "dance", "стриптиз" to "striptease", "мокр" to "wet", "сквирт" to "squirt", "оргазм" to "orgasm",
        "стон" to "moaning", "крупн" to "closeup", "медленн" to "slow", "жестк" to "hardcore", "нежн" to "gentle", "сзади" to "doggystyle",
        "наездниц" to "cowgirl", "миссионер" to "missionary", "горяч" to "hot", "красив" to "beautiful", "красотк" to "beauty", "молод" to "young",
        "соседк" to "neighbor", "подруг" to "girlfriend", "тещ" to "mother in law", "мачех" to "stepmom", "падчериц" to "stepdaughter",
        "скрыт" to "hidden cam", "тату" to "tattoo", "пирсинг" to "piercing", "волосат" to "hairy", "бритая" to "shaved", "бритой" to "shaved",
    )

    private val CYRILLIC = Regex("[а-яё]")

    /**
     * Русский запрос -> английский («большие сиськи блондинка» -> «big tits blonde»). Латинские слова остаются как есть,
     * незнакомые русские отбрасываются; null — запрос не русский или ни одно слово не узнано. Чистая функция.
     */
    fun translate(q: String): String? {
        val lower = q.lowercase().replace('ё', 'е')
        if (!CYRILLIC.containsMatchIn(lower)) return null
        var known = 0
        val out = ArrayList<String>()
        for (w in lower.split(Regex("[^\\p{L}0-9]+")).filter { it.isNotEmpty() }) {
            if (!CYRILLIC.containsMatchIn(w)) { out += w; continue }
            if (w in RU_STOP) continue
            val en = RU_WORD[w] ?: RU_STEM.entries.filter { w.startsWith(it.key) }.maxByOrNull { it.key.length }?.value
            if (en != null) { known++; out += en }
        }
        return if (known == 0) null else out.distinct().joinToString(" ")
    }
}
