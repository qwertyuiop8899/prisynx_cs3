package prisynx.providers.core

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.util.Collections

/**
 * Settings UI shared by the stand-alone catalogue extensions (AnimeUnity,
 * AnimeWorld, AnimeSaturn).
 *
 * Only platform widgets are used: the extensions do not ship resources. The
 * dialog lets the user decide which site sections appear on the home page,
 * their order, and the local edition/type filters. The app exposes it through
 * [com.lagradost.cloudstream3.plugins.Plugin.openSettings].
 */
object CatalogSettingsDialog {
    /**
     * @param title extension name shown in the dialog title and toasts
     * @param rows every section the provider can build, in site order
     * @param showType enable the local type filter (only for the sites whose
     *        cards carry their type: AnimeUnity)
     */
    fun show(
        context: Context,
        title: String,
        rows: List<CatalogRow>,
        showType: Boolean = false
    ) {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        fun addLabel(text: String) {
            content.addView(
                TextView(context).apply {
                    this.text = text
                    textSize = 13f
                    setTextColor(Color.GRAY)
                    setPadding(0, dp(10), 0, dp(2))
                }
            )
        }

        content.addView(
            TextView(context).apply {
                text = "Scegli quali sezioni del sito mostrare nella home e in che " +
                    "ordine. I filtri sono applicati in locale a tutte le sezioni, " +
                    "senza richieste aggiuntive ai siti."
                textSize = 13f
                setTextColor(Color.GRAY)
                setPadding(0, 0, 0, dp(4))
            }
        )

        addLabel("Sezioni")

        val order = CatalogSettings.ordered(rows).toMutableList()
        val enabled = order.associateWith { CatalogSettings.isEnabled(it) }.toMutableMap()
        val rowsBox = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(rowsBox)

        fun rebuildRows() {
            rowsBox.removeAllViews()
            order.forEachIndexed { index, row ->
                val line = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, dp(2), 0, dp(2))
                }
                val check = CheckBox(context).apply {
                    text = row.label
                    isChecked = enabled[row] == true
                    setOnCheckedChangeListener { _, value -> enabled[row] = value }
                }
                line.addView(
                    check,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                )
                line.addView(smallButton(context, "Su", index > 0) {
                    Collections.swap(order, index, index - 1)
                    rebuildRows()
                })
                line.addView(smallButton(context, "Giu", index < order.size - 1) {
                    Collections.swap(order, index, index + 1)
                    rebuildRows()
                })
                rowsBox.addView(line)
            }
        }
        rebuildRows()

        addLabel("Filtri")

        var edition = CatalogSettings.edition
        val editionButton = Button(context).apply {
            setPadding(dp(8), dp(2), dp(8), dp(2))
            text = "Edizione: ${editionLabel(edition)}"
            setOnClickListener {
                edition = when (edition) {
                    EditionFilter.ALL -> EditionFilter.SUB
                    EditionFilter.SUB -> EditionFilter.DUB
                    EditionFilter.DUB -> EditionFilter.ALL
                }
                text = "Edizione: ${editionLabel(edition)}"
            }
        }
        content.addView(editionButton)

        var type = CatalogSettings.type
        if (showType) {
            val typeButton = Button(context).apply {
                setPadding(dp(8), dp(2), dp(8), dp(2))
                text = "Tipo: ${typeLabel(type)}"
                setOnClickListener {
                    type = nextType(type)
                    text = "Tipo: ${typeLabel(type)}"
                }
            }
            content.addView(typeButton)
        }

        val scroll = ScrollView(context).apply { addView(content) }

        AlertDialog.Builder(context)
            .setTitle(title)
            .setView(scroll)
            .setPositiveButton("Salva") { _, _ ->
                CatalogSettings.saveOrder(order)
                order.forEach { CatalogSettings.setEnabled(it, enabled[it] == true) }
                CatalogSettings.edition = edition
                CatalogSettings.type = type
                Toast.makeText(context, "Impostazioni $title salvate", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .setNeutralButton("Ripristina") { _, _ ->
                CatalogSettings.reset()
                Toast.makeText(context, "Impostazioni $title ripristinate", Toast.LENGTH_SHORT)
                    .show()
            }
            .show()
    }

    private fun smallButton(
        context: Context,
        label: String,
        enabled: Boolean,
        onClick: () -> Unit
    ): Button = Button(context).apply {
        text = label
        textSize = 12f
        isEnabled = enabled
        minWidth = 0
        minimumWidth = 0
        val pad = (10 * context.resources.displayMetrics.density).toInt()
        setPadding(pad, pad / 3, pad, pad / 3)
        setOnClickListener { onClick() }
    }

    private fun nextType(type: TypeFilter): TypeFilter = when (type) {
        TypeFilter.ALL -> TypeFilter.TV
        TypeFilter.TV -> TypeFilter.MOVIE
        TypeFilter.MOVIE -> TypeFilter.OVA
        TypeFilter.OVA -> TypeFilter.ONA
        TypeFilter.ONA -> TypeFilter.SPECIAL
        TypeFilter.SPECIAL -> TypeFilter.ALL
    }

    private fun editionLabel(filter: EditionFilter): String = when (filter) {
        EditionFilter.ALL -> "tutte"
        EditionFilter.SUB -> "solo SUB-ITA"
        EditionFilter.DUB -> "solo DUB-ITA"
    }

    private fun typeLabel(filter: TypeFilter): String = when (filter) {
        TypeFilter.ALL -> "tutti"
        TypeFilter.TV -> "TV"
        TypeFilter.MOVIE -> "film"
        TypeFilter.OVA -> "OVA"
        TypeFilter.ONA -> "ONA"
        TypeFilter.SPECIAL -> "special"
    }
}
