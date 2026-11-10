package prisynx.live

import java.net.URLEncoder

/** Portrait (2:3) text cards in PriSynx colours, same style as the Vavoo channel placeholders. */
object Posters {
    fun portrait(vararg lines: String?): String {
        val text = lines.filterNotNull().map { it.replace(Regex("[^\\p{L}\\p{N} .:&+'/-]"), "").replace(Regex("\\s+"), " ").trim() }
            .filter { it.isNotBlank() }
            .joinToString("\\n") { it.uppercase() }
            .ifBlank { "LIVE" }
        return "https://placehold.co/400x600/12052b/00FFD1.png?font=montserrat&text=" + URLEncoder.encode(text, "UTF-8")
    }

    fun match(home: String?, away: String?, title: String, extra: String? = null): String =
        if (!home.isNullOrBlank() && !away.isNullOrBlank()) portrait(home, "vs", away, extra)
        else portrait(title, extra)
}
