package prisynx.providers.core

import android.content.Context
import android.content.SharedPreferences
import com.lagradost.cloudstream3.SearchResponse

/** A home-page row offered by a native site catalogue. */
data class CatalogRow(
    val id: String,
    val label: String,
    /** Landscape cards (hero/banner rows) instead of portrait posters. */
    val horizontal: Boolean = false
)

/** What the user asked to see: every edition, subbed only or dubbed only. */
enum class EditionFilter { ALL, SUB, DUB }

/** Local type filter, for the sites whose cards carry their type. */
enum class TypeFilter { ALL, TV, MOVIE, OVA, ONA, SPECIAL }

/**
 * A catalogue card plus the metadata used by the local filters.
 *
 * The site tabs (Sub-ITA/Dub-ITA/Tendenze) are applied client-side by the
 * sites themselves: the cards are already in the home HTML with their badges,
 * so filtering here needs no extra request.
 */
data class CatalogCard(
    val card: SearchResponse,
    val dub: Boolean,
    val type: String? = null
)

fun CatalogCard.matches(edition: EditionFilter, type: TypeFilter): Boolean {
    if (edition == EditionFilter.SUB && dub) return false
    if (edition == EditionFilter.DUB && !dub) return false
    if (type == TypeFilter.ALL) return true
    val known = normalizeType(this.type) ?: return true
    return known == type
}

private fun normalizeType(raw: String?): TypeFilter? {
    val value = raw.orEmpty().trim().lowercase()
    if (value.isBlank()) return null
    return when {
        value == "tv" || value.startsWith("serie") -> TypeFilter.TV
        value == "movie" || value == "film" -> TypeFilter.MOVIE
        value == "ova" -> TypeFilter.OVA
        value == "ona" -> TypeFilter.ONA
        value == "special" || value == "specials" || value == "music" -> TypeFilter.SPECIAL
        else -> null
    }
}

/**
 * Rows and filters of a stand-alone catalogue extension.
 *
 * Every `.cs3` is loaded in its own class loader, so this singleton is really
 * one instance per plugin: [attach] receives the preference file to use.
 * The hub compiles the same class but never attaches it, and it builds its own
 * rows, so the provider defaults stay untouched there.
 */
object CatalogSettings {
    private const val KEY_ORDER = "catalog_order"
    private const val KEY_EDITION = "catalog_edition"
    private const val KEY_TYPE = "catalog_type"

    @Volatile
    private var prefs: SharedPreferences? = null

    fun attach(context: Context, name: String) {
        prefs = context.applicationContext.getSharedPreferences(name, Context.MODE_PRIVATE)
    }

    private fun prefs(): SharedPreferences? = prefs

    // ------------------------------------------------------------ rows

    fun isEnabled(row: CatalogRow): Boolean =
        prefs()?.getBoolean("catalog_on_${row.id}", true) ?: true

    fun setEnabled(row: CatalogRow, on: Boolean) {
        prefs()?.edit()?.putBoolean("catalog_on_${row.id}", on)?.apply()
    }

    /** Saved order first, unknown rows (new sections) appended in site order. */
    fun ordered(rows: List<CatalogRow>): List<CatalogRow> {
        val saved = prefs()?.getString(KEY_ORDER, null)
            ?.split(',')?.filter { it.isNotBlank() }.orEmpty()
        val byId = rows.associateBy { it.id }
        val head = saved.mapNotNull { byId[it] }
        return head + rows.filter { it.id !in saved }
    }

    fun saveOrder(rows: List<CatalogRow>) {
        prefs()?.edit()?.putString(KEY_ORDER, rows.joinToString(",") { it.id })?.apply()
    }

    /** Rows to show, in the user's order. */
    fun visible(rows: List<CatalogRow>): List<CatalogRow> =
        ordered(rows).filter { isEnabled(it) }

    // ------------------------------------------------------------ filters

    var edition: EditionFilter
        get() = runCatching {
            EditionFilter.valueOf(prefs()?.getString(KEY_EDITION, null).orEmpty())
        }.getOrDefault(EditionFilter.ALL)
        set(value) {
            prefs()?.edit()?.putString(KEY_EDITION, value.name)?.apply()
        }

    var type: TypeFilter
        get() = runCatching {
            TypeFilter.valueOf(prefs()?.getString(KEY_TYPE, null).orEmpty())
        }.getOrDefault(TypeFilter.ALL)
        set(value) {
            prefs()?.edit()?.putString(KEY_TYPE, value.name)?.apply()
        }

    fun reset() {
        prefs()?.edit()?.clear()?.apply()
    }
}
