package prisynx.live

import android.content.Context
import android.content.SharedPreferences
import prisynx.vavoo.VavooOptions

object LiveSettings {
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences("prisynx_live", Context.MODE_PRIVATE)
        syncCountries()
    }

    private fun bool(key: String, def: Boolean) = prefs?.getBoolean(key, def) ?: def
    private fun setBool(key: String, v: Boolean) { prefs?.edit()?.putBoolean(key, v)?.apply() }

    fun isCountryEnabled(group: String) = bool("country_$group", group == "Italy")
    fun setCountryEnabled(group: String, on: Boolean) {
        setBool("country_$group", on)
        syncCountries()
    }

    private fun syncCountries() {
        VavooOptions.enabled = VavooOptions.countries.map { it.group }.filter { isCountryEnabled(it) }.toSet()
    }

    var sportOnlyLive: Boolean
        get() = bool("sport_only_live", false)
        set(v) = setBool("sport_only_live", v)

    var sportShowDrm: Boolean
        get() = bool("sport_show_drm", true)
        set(v) = setBool("sport_show_drm", v)

    /** Sports are stored as "disabled" so new categories from the feed show up by default. */
    fun isSportEnabled(cat: String) = !bool("sport_off_${cat.lowercase()}", false)
    fun setSportEnabled(cat: String, on: Boolean) = setBool("sport_off_${cat.lowercase()}", !on)

    /** Every sport SportzX is known to use; categories seen later in the feed are added and kept. */
    private val BASE_SPORTS = listOf(
        "Serie A 🇮🇹", "UEFA Champions", "UEFA Europa", "UEFA Conference", "EPL 🏴󠁧󠁢󠁥󠁮󠁧󠁿", "LaLiga 🇪🇸",
        "Bundesliga 🇩🇪", "2. Bundesliga", "Ligue 1 🇫🇷", "Liga Portugal", "Eredivisie", "Super Lig",
        "Championship 🏴󠁧󠁢󠁥󠁮󠁧󠁿", "FA Cup", "Brasileirão", "MLS", "ISL", "NPL", "SAFF Championship",
        "F1", "MotoGP", "Tennis", "NBA", "NFL", "NHL", "MLB", "UFC", "Boxing", "WWE",
        "Cricket", "IPL", "WBBL", "Asia Cup"
    )

    fun allSports(): List<String> {
        val seen = prefs?.getStringSet("seen_sports", emptySet()).orEmpty()
        return (BASE_SPORTS + seen).distinctBy { it.lowercase() }.sortedBy { it.lowercase() }
    }

    fun rememberSports(cats: Collection<String>) {
        val p = prefs ?: return
        val old = p.getStringSet("seen_sports", emptySet()).orEmpty()
        val merged = old + cats.filter { c -> BASE_SPORTS.none { it.equals(c, true) } }
        if (merged.size != old.size) p.edit().putStringSet("seen_sports", HashSet(merged)).apply()
    }
}
