package app.loopa

import java.net.URI

/** Offline parser for explicitly authorized public HTML snapshots. No networking or media URL extraction. */
internal object PublicPageMetadata {
    data class Metadata(val title: String, val canonical: String?)
    private const val MAX_HTML = 2_000_000
    private val tag = Regex("""(?is)<(meta|link)\b((?:"[^"]*"|'[^']*'|[^'">])*)>""")
    private val attr = Regex("""(?is)([a-z][a-z0-9_:-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val ignored = Regex("""(?is)<!--.*?-->|<(script|style|template)\b[^>]*>.*?</\1\s*>""")
    private val titleTag = Regex("""(?is)<title\b[^>]*>([^<]{0,4096})</title\s*>""")
    private val entity = Regex("""&(?:amp|quot|apos|lt|gt|#\d{1,7}|#x[0-9a-fA-F]{1,6});""")

    private fun decode(s: String): String = entity.replace(s) { m ->
        when (m.value) {
            "&amp;" -> "&"; "&quot;" -> "\""; "&apos;" -> "'"; "&lt;" -> "<"; "&gt;" -> ">"
            else -> runCatching {
                val raw = m.value.removePrefix("&#").removeSuffix(";")
                val cp = if (raw.startsWith("x")) raw.drop(1).toInt(16) else raw.toInt()
                if (!Character.isValidCodePoint(cp) || cp in 0xD800..0xDFFF || Character.isISOControl(cp)) "" else String(Character.toChars(cp))
            }.getOrDefault("")
        }
    }

    private fun sameOrigin(base: URI, raw: String): String? = runCatching {
        val u = base.resolve(decode(raw)).normalize()
        if (u.scheme != "https" || u.userInfo != null || u.host == null || !u.host.equals(base.host, true) ||
            (if (u.port == -1) 443 else u.port) != (if (base.port == -1) 443 else base.port)) null
        else URI(u.scheme, u.authority, u.path, null, null).toASCIIString()
    }.getOrNull()

    fun parse(html: String, pageUrl: String): Metadata? {
        if (html.length > MAX_HTML) return null
        val base = runCatching { URI(pageUrl) }.getOrNull() ?: return null
        if (base.scheme != "https" || base.host == null || base.userInfo != null) return null
        val clean = ignored.replace(html, "")
        var title: String? = null
        var canonical: String? = null
        for (m in tag.findAll(clean)) {
            val a = attr.findAll(m.groupValues[2]).associate { it.groupValues[1].lowercase() to (it.groups[2]?.value ?: it.groupValues[3]) }
            if (m.groupValues[1].equals("meta", true) && a["property"].equals("og:title", true) && title == null)
                title = a["content"]
            if (m.groupValues[1].equals("link", true) && a["rel"].equals("canonical", true) && canonical == null)
                canonical = a["href"]?.let { sameOrigin(base, it) }
        }
        val name = decode(title ?: titleTag.find(clean)?.groupValues?.get(1).orEmpty()).trim().take(512)
        if (name.isBlank()) return null
        return Metadata(name, canonical)
    }
}
