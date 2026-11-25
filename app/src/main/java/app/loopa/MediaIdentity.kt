package app.loopa

/** Conservative identity: metadata resemblance is not proof that two files are identical. */
internal class MediaIdentity {
    private val ids = HashSet<String>()
    private val urls = HashSet<String>()

    fun accept(id: String, url: String): Boolean {
        if (id.isNotBlank() && id in ids || url.isNotBlank() && url in urls) return false
        if (id.isNotBlank()) ids.add(id)
        if (url.isNotBlank()) urls.add(url)
        return true
    }

    fun remember(id: String, url: String) {
        if (id.isNotBlank()) ids.add(id)
        if (url.isNotBlank()) urls.add(url)
    }
}
