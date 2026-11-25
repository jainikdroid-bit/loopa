package app.loopa

fun main() {
    val base = "https://example.test/catalog/item"
    fun parse(html: String) = PublicPageMetadata.parse(html, base)
    val cases = listOf<Pair<String, () -> Boolean>>(
        "ordered attributes and relative canonical" to { parse("<META content='A &amp; B' property='og:title'><link href='/item?session=secret#x' rel='canonical'>") == PublicPageMetadata.Metadata("A & B", "https://example.test/item") },
        "title fallback" to { parse("<title>Sample &#65;</title>")?.title == "Sample A" },
        "ignore comments and scripts" to { parse("<!-- <meta property='og:title' content='bad'> --><script>const a=\"<meta property='og:title' content='bad'>\";</script><title>Good</title>")?.title == "Good" },
        "reject external canonical" to { parse("<title>A</title><link rel='canonical' href='https://evil.test/a'>")?.canonical == null },
        "reject alternate port" to { parse("<title>A</title><link rel='canonical' href='https://example.test:444/a'>")?.canonical == null },
        "reject javascript canonical" to { parse("<title>A</title><link rel='canonical' href='javascript:alert(1)'>")?.canonical == null },
        "reject missing title" to { parse("<p>Missing metadata</p>") == null },
        "reject oversized snapshot" to { parse("x".repeat(2_000_001)) == null },
        "reject non-HTTPS origin" to { PublicPageMetadata.parse("<title>A</title>", "http://example.test") == null },
        "reject embedded userinfo" to { parse("<title>A</title><link rel='canonical' href='https://user@example.test/a'>")?.canonical == null }
    )
    for ((name, checkCase) in cases) { check(checkCase()) { name }; println("PASS $name") }
    println("${cases.size}/${cases.size} passed")
}
