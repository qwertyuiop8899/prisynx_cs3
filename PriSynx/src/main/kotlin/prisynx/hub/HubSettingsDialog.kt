package prisynx.hub

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.runBlocking
import prisynx.providers.core.Debrid
import prisynx.providers.core.DebridSettings

/**
 * Full screen settings for the PriSynx hub, designed for the D-pad:
 * a tab bar on top, big focusable rows (click = toggle) and a clear
 * focus highlight. Plain Android widgets only (the plugin has no AppCompat).
 */
@Suppress("SetTextI18n")
object HubSettingsDialog {
    private const val BG = 0xFF120D1C.toInt()
    private const val BAR = 0xFF1A1229.toInt()
    private const val CARD = 0xFF211833.toInt()
    private const val CARD_FOCUS = 0xFF3B2566.toInt()
    private const val ACCENT = 0xFF9B5CFF.toInt()
    private const val ACCENT_SOFT = 0xFF2E2050.toInt()
    private const val TEXT = 0xFFF3EEFF.toInt()
    private const val SUB = 0xFFAFA2C9.toInt()
    private const val ON = 0xFF8B5CF6.toInt()
    private const val OFF = 0xFF3A3347.toInt()

    private val tabs = listOf("Provider", "Home", "TopX", "Anime", "KissKH", "RaiPlay", "Debrid", "Altro")

    private class Ui(val context: Context, val content: LinearLayout, val scroll: ScrollView) {
        val tabViews = mutableListOf<TextView>()
        var current = -1
        var generation = 0
        var pendingFocus: String? = null
        val main = Handler(Looper.getMainLooper())
    }

    fun show(context: Context) {
        val dialog = Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        // header
        val header = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(BAR)
            setPadding(dp(context, 24), dp(context, 14), dp(context, 24), dp(context, 6))
        }
        header.addView(TextView(context).apply {
            text = "PriSynx"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.DEFAULT_BOLD
        })
        header.addView(TextView(context).apply {
            text = "  Impostazioni"
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val close = pill(context, "✕  Chiudi") { dialog.dismiss() }
        header.addView(close)
        root.addView(header)

        // tab bar
        val tabRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(context, 20), dp(context, 6), dp(context, 20), dp(context, 12))
        }
        val tabScroll = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            setBackgroundColor(BAR)
            addView(tabRow)
        }
        root.addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        // content
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(context, 24), dp(context, 16), dp(context, 24), dp(context, 32))
        }
        val scroll = ScrollView(context).apply {
            isFillViewport = true
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        root.addView(TextView(context).apply {
            text = "OK/Invio = attiva o disattiva  ·  ▲ ▼ = sposta  ·  Indietro = chiudi  ·  le modifiche sono salvate subito (ricarica la home per vederle)"
            setTextColor(SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            setBackgroundColor(BAR)
            setPadding(dp(context, 24), dp(context, 8), dp(context, 24), dp(context, 8))
        })

        val ui = Ui(context, content, scroll)
        tabs.forEachIndexed { index, name ->
            val tab = TextView(context).apply {
                text = name
                setTextColor(TEXT)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
                typeface = Typeface.DEFAULT_BOLD
                gravity = Gravity.CENTER
                minWidth = dp(context, 110)
                setPadding(dp(context, 22), dp(context, 10), dp(context, 22), dp(context, 10))
                isFocusable = true
                isClickable = true
                background = tabBackground(context)
                id = View.generateViewId()
                setOnClickListener { select(ui, index) }
                setOnFocusChangeListener { _, focused -> if (focused) select(ui, index) }
            }
            ui.tabViews.add(tab)
            tabRow.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                rightMargin = dp(context, 10)
            })
        }
        close.nextFocusDownId = ui.tabViews.first().id
        ui.tabViews.forEach { it.nextFocusUpId = close.id.takeIf { id -> id != View.NO_ID } ?: View.NO_ID }

        dialog.setContentView(root)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(GradientDrawable().apply { setColor(BG) })
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN)
        }
        select(ui, 0)
        dialog.show()
        ui.tabViews.first().post { ui.tabViews.first().requestFocus() }
    }

    // ------------------------------------------------------------ tabs

    private fun select(ui: Ui, index: Int, force: Boolean = false) {
        if (ui.current == index && !force) return
        ui.current = index
        ui.generation++
        ui.tabViews.forEachIndexed { i, t -> t.isSelected = i == index }
        ui.content.removeAllViews()
        when (index) {
            0 -> providerTab(ui)
            1 -> homeTab(ui)
            2 -> topxTab(ui)
            3 -> animeTab(ui)
            4 -> kisskhTab(ui)
            5 -> raiTab(ui)
            6 -> debridTab(ui)
            else -> otherTab(ui)
        }
        ui.scroll.scrollTo(0, 0)
        linkTabsToContent(ui)
    }

    /** Rebuilds the page, keeping the scroll and focusing the first focusable of [focusTags]. */
    private fun refresh(ui: Ui, vararg focusTags: String) {
        val y = ui.scroll.scrollY
        select(ui, ui.current, force = true)
        ui.scroll.post {
            ui.scroll.scrollTo(0, y)
            val target = focusTags.asSequence()
                .mapNotNull { ui.content.findViewWithTag<View>(it) }
                .firstOrNull { it.isFocusable }
            // Rows of async lists do not exist yet: focus them once loaded.
            if (target != null) target.requestFocus() else ui.pendingFocus = focusTags.firstOrNull()
        }
    }

    /** D-pad down from the tab bar lands on the first row of the page. */
    private fun linkTabsToContent(ui: Ui) {
        val first = firstFocusable(ui.content) ?: return
        if (first.id == View.NO_ID) first.id = View.generateViewId()
        ui.tabViews.forEach { it.nextFocusDownId = first.id }
        first.nextFocusUpId = ui.tabViews[ui.current].id
    }

    private fun firstFocusable(group: ViewGroup): View? {
        for (i in 0 until group.childCount) {
            val child = group.getChildAt(i)
            if (child.isFocusable && child.visibility == View.VISIBLE) return child
            if (child is ViewGroup) firstFocusable(child)?.let { return it }
        }
        return null
    }

    // ------------------------------------------------------------ Provider

    private fun providerTab(ui: Ui) {
        val c = ui.context
        heading(ui, "Provider", "Attiva/disattiva con OK. Usa ▲ ▼ per l'ordine: i link arrivano in quest'ordine.")
        val bulk = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
        bulk.addView(actionRow(c, "Attiva tutti", null) {
            HubSettings.sources.forEach { HubSettings.setSourceEnabled(it.id, true) }
            refresh(ui, "bulk_all")
        }.apply { tag = "bulk_all" }, weightParams(c, right = 8))
        bulk.addView(actionRow(c, "Disattiva tutti", null) {
            HubSettings.sources.forEach { HubSettings.setSourceEnabled(it.id, false) }
            refresh(ui, "bulk_none")
        }.apply { tag = "bulk_none" }, weightParams(c, left = 8))
        ui.content.addView(bulk, rowParams(c))

        val order = HubSettings.orderedSources()
        order.forEachIndexed { index, source ->
            ui.content.addView(orderRow(
                ui,
                tagPrefix = source.id,
                title = "${index + 1}.  ${source.label}",
                on = { HubSettings.isSourceEnabled(source.id) },
                toggle = { HubSettings.setSourceEnabled(source.id, !HubSettings.isSourceEnabled(source.id)) },
                canUp = index > 0,
                canDown = index < order.lastIndex,
                move = { delta ->
                    val list = HubSettings.orderedSources().toMutableList()
                    val from = list.indexOfFirst { it.id == source.id }
                    val to = from + delta
                    if (from >= 0 && to in list.indices) {
                        list.add(to, list.removeAt(from))
                        HubSettings.saveOrder(list)
                    }
                }
            ), rowParams(c))
        }
    }

    // ------------------------------------------------------------ Home

    private fun homeTab(ui: Ui) {
        val c = ui.context
        heading(ui, "Ordine delle sezioni", "Cosa compare per primo nella home di PriSynx.")
        val order = HubSettings.homeOrder()
        val labels = HubSettings.homeGroups.toMap()
        order.forEachIndexed { index, id ->
            ui.content.addView(orderRow(
                ui,
                tagPrefix = "group_$id",
                title = "${index + 1}.  ${labels[id] ?: id}",
                on = null,
                toggle = null,
                canUp = index > 0,
                canDown = index < order.lastIndex,
                move = { delta ->
                    val list = HubSettings.homeOrder().toMutableList()
                    val from = list.indexOf(id)
                    val to = from + delta
                    if (from >= 0 && to in list.indices) {
                        list.add(to, list.removeAt(from))
                        HubSettings.saveHomeOrder(list)
                    }
                }
            ), rowParams(c))
        }

        heading(ui, "Cataloghi TMDB", "Righe della home basate su TMDB.")
        HubSettings.tmdbRows.forEach { row ->
            ui.content.addView(toggleRow(c, row.row.name, null,
                get = { HubSettings.isTmdbRowEnabled(row) },
                set = { HubSettings.setTmdbRowEnabled(row, it) }
            ), rowParams(c))
        }
    }

    // ------------------------------------------------------------ TopX

    private fun topxTab(ui: Ui) {
        val c = ui.context
        heading(ui, "TopX", "Cataloghi da topx.stremio-italia.eu")
        ui.content.addView(toggleRow(c, "Mostra cataloghi TopX", null,
            get = { HubSettings.topxEnabled },
            set = { HubSettings.topxEnabled = it }
        ), rowParams(c))
        val landscape = HubSettings.topxLandscape
        ui.content.addView(actionRow(c,
            "Stile copertine: " + if (landscape) "Landscape (orizzontali)" else "Portrait (verticali)",
            "OK per passare a " + if (landscape) "Portrait" else "Landscape"
        ) {
            HubSettings.topxLandscape = !HubSettings.topxLandscape
            TopX.clearCache()
            refresh(ui, "topx_style")
        }.apply { tag = "topx_style" }, rowParams(c))
        val url = HubSettings.topxUrl
        val custom = url != HubSettings.defaultTopxUrl()
        val styleName = if (landscape) "landscape" else "portrait"
        ui.content.addView(actionRow(c, "Modifica URL manifest $styleName",
            if (custom) "Personalizzato: ${shorten(url)}" else "Predefinito PriSynx") {
            editUrl(ui)
        }, rowParams(c))
        if (custom) {
            ui.content.addView(actionRow(c, "Ripristina URL $styleName predefinito", null) {
                HubSettings.topxUrl = HubSettings.defaultTopxUrl()
                TopX.clearCache()
                refresh(ui)
            }, rowParams(c))
        }
        ui.content.addView(actionRow(c, "Ricarica elenco cataloghi", null) {
            TopX.clearCache()
            refresh(ui)
        }, rowParams(c))

        heading(ui, "Cataloghi", "OK = mostra/nascondi · ▲ ▼ = ordine in home")
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(note(c, "Caricamento…"))
        ui.content.addView(box)
        val gen = ui.generation
        Thread {
            val catalogs = try {
                runBlocking { TopX.catalogs(HubSettings.topxUrl) }
            } catch (e: Exception) {
                emptyList()
            }
            ui.main.post {
                if (gen != ui.generation) return@post
                box.removeAllViews()
                if (catalogs.isEmpty()) {
                    box.addView(note(c, "Nessun catalogo trovato: controlla l'URL o la connessione."))
                    return@post
                }
                val ordered = HubSettings.orderTopx(catalogs) { it.key }
                val bulk = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                bulk.addView(actionRow(c, "Mostra tutti", null) {
                    catalogs.forEach { HubSettings.setTopxCatalogEnabled(it.key, true) }
                    refresh(ui, "topx_all")
                }.apply { tag = "topx_all" }, weightParams(c, right = 8))
                bulk.addView(actionRow(c, "Nascondi tutti", null) {
                    catalogs.forEach { HubSettings.setTopxCatalogEnabled(it.key, false) }
                    refresh(ui, "topx_none")
                }.apply { tag = "topx_none" }, weightParams(c, left = 8))
                box.addView(bulk, rowParams(c))
                ordered.forEachIndexed { index, catalog ->
                    box.addView(orderRow(
                        ui,
                        tagPrefix = "topx_${catalog.key}",
                        title = "${index + 1}.  ${catalog.label}",
                        on = { HubSettings.isTopxCatalogEnabled(catalog.key) },
                        toggle = {
                            HubSettings.setTopxCatalogEnabled(catalog.key, !HubSettings.isTopxCatalogEnabled(catalog.key))
                        },
                        canUp = index > 0,
                        canDown = index < ordered.lastIndex,
                        move = { delta ->
                            val keys = ordered.map { it.key }.toMutableList()
                            val from = keys.indexOf(catalog.key)
                            val to = from + delta
                            if (from >= 0 && to in keys.indices) {
                                keys.add(to, keys.removeAt(from))
                                HubSettings.saveTopxOrder(keys)
                            }
                        }
                    ), rowParams(c))
                }
                linkTabsToContent(ui)
                ui.pendingFocus?.let { tag ->
                    ui.pendingFocus = null
                    box.findViewWithTag<View>(tag)?.takeIf { it.isFocusable }?.requestFocus()
                }
            }
        }.start()
    }

    private fun editUrl(ui: Ui) {
        val c = ui.context
        val input = EditText(c).apply {
            setText(HubSettings.topxUrl)
            setSingleLine(true)
            inputType = InputType.TYPE_TEXT_VARIATION_URI
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(c).apply {
            setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0)
            addView(input)
        }
        AlertDialog.Builder(c)
            .setTitle(if (HubSettings.topxLandscape) "URL manifest TopX landscape" else "URL manifest TopX portrait")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                HubSettings.topxUrl = input.text.toString().ifBlank { HubSettings.defaultTopxUrl() }
                TopX.clearCache()
                refresh(ui)
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    // ------------------------------------------------------------ Anime

    private fun animeTab(ui: Ui) {
        val c = ui.context
        heading(ui, "Anime da Kitsu",
            "Kitsu ha una scheda per ogni stagione con la numerazione degli episodi usata da AnimeUnity, AnimeSaturn e AnimeWorld: gli episodi corrispondono sempre.")
        ui.content.addView(toggleRow(c, "Righe anime Kitsu in home", null,
            get = { HubSettings.kitsuHome },
            set = { HubSettings.kitsuHome = it }
        ), rowParams(c))
        ui.content.addView(toggleRow(c, "Anime Kitsu nella ricerca", null,
            get = { HubSettings.kitsuSearch },
            set = { HubSettings.kitsuSearch = it }
        ), rowParams(c))
        ui.content.addView(toggleRow(c, "Nascondi gli anime da TMDB",
            "Toglie l'animazione giapponese dai cataloghi e dalla ricerca TMDB",
            get = { HubSettings.hideTmdbAnime },
            set = { HubSettings.hideTmdbAnime = it }
        ), rowParams(c))
        heading(ui, "Righe Kitsu", null)
        prisynx.providers.core.Kitsu.rows.forEach { row ->
            ui.content.addView(toggleRow(c, row.name, null,
                get = { HubSettings.isKitsuRowEnabled(row.id) },
                set = { HubSettings.setKitsuRowEnabled(row.id, it) }
            ), rowParams(c))
        }
    }

    // ------------------------------------------------------------ KissKH

    private fun kisskhTab(ui: Ui) {
        val c = ui.context
        heading(ui, "KissKH",
            "Drama e film asiatici con sottotitoli ITA/ENG e traduzione DeepL opzionale.")
        ui.content.addView(toggleRow(c, "Righe KissKH in home", null,
            get = { HubSettings.kisskhHome },
            set = {
                HubSettings.kisskhHome = it
                KissKHHub.clearCache()
            }
        ), rowParams(c))
        ui.content.addView(toggleRow(c, "Risultati KissKH nella ricerca", null,
            get = { HubSettings.kisskhSearch },
            set = { HubSettings.kisskhSearch = it }
        ), rowParams(c))

        val deepl = if (prisynx.providers.KissKHSettings.translationActive()) {
            "DeepL attivo (${prisynx.providers.KissKHSettings.targetLang})"
        } else {
            "Solo sottotitoli nativi"
        }
        ui.content.addView(actionRow(c, "Impostazioni KissKH",
            "URL, chiave DeepL, lingua e filtro adulti · $deepl"
        ) {
            prisynx.providers.KissKHSettingsDialog.show(c)
        }, rowParams(c))

        heading(ui, "Cataloghi KissKH", "Scegli quali righe mostrare nella home.")
        prisynx.providers.KissKHProvider.catalogs.forEach { catalog ->
            ui.content.addView(toggleRow(c, catalog.label, null,
                get = { HubSettings.isKisskhRowEnabled(catalog.id) },
                set = { HubSettings.setKisskhRowEnabled(catalog.id, it) }
            ), rowParams(c))
        }
        ui.content.addView(actionRow(c, "Ricarica cataloghi KissKH", null) {
            KissKHHub.clearCache()
            refresh(ui)
        }, rowParams(c))
    }

    // ------------------------------------------------------------ RaiPlay

    private fun raiTab(ui: Ui) {
        val c = ui.context
        heading(ui, "RaiPlay", "Film, serie, documentari e dirette Rai dentro PriSynx (solo Italia/Europa).")
        ui.content.addView(toggleRow(c, "Righe RaiPlay in home", null,
            get = { HubSettings.raiHome },
            set = { HubSettings.raiHome = it }
        ), rowParams(c))
        ui.content.addView(toggleRow(c, "Risultati RaiPlay nella ricerca", null,
            get = { HubSettings.raiSearch },
            set = { HubSettings.raiSearch = it }
        ), rowParams(c))
        heading(ui, "Righe RaiPlay", "Scegli quali righe della home RaiPlay mostrare.")
        asyncList(ui,
            load = { runBlocking { RaiHub.rows() }.map { RaiHub.key(it) to it.name } },
            isOn = { HubSettings.isRaiRowEnabled(it) },
            setOn = { key, on -> HubSettings.setRaiRowEnabled(key, on) },
            empty = "RaiPlay non raggiungibile (serve una connessione italiana/europea)."
        )
    }

    // ------------------------------------------------------------ Debrid

    private fun debridTab(ui: Ui) {
        val c = ui.context
        heading(ui, "Debrid",
            "Sblocca i magnet di IlCorsaroViola in link diretti. I torrent gia' in cache " +
                "sono immediati; gli altri vengono inviati al servizio e restano magnet " +
                "finche' il download non e' pronto.")
        ui.content.addView(toggleRow(c, "Usa il servizio debrid", null,
            get = { DebridSettings.enabled },
            set = {
                DebridSettings.enabled = it
                Debrid.clearCache()
            }
        ), rowParams(c))

        heading(ui, "Servizio", "La chiave si prende dalla pagina indicata.")
        DebridSettings.services.forEach { service ->
            val on = service.id == DebridSettings.service
            val hasKey = DebridSettings.keyOf(service.id).isNotBlank()
            val row = actionRow(
                c,
                (if (on) "\u25CF " else "\u25CB ") + service.label,
                service.keyPage + (if (hasKey) " \u00B7 chiave salvata" else "")
            ) {
                DebridSettings.service = service.id
                Debrid.clearCache()
                refresh(ui, "debrid_srv_${service.id}")
            }
            row.tag = "debrid_srv_${service.id}"
            ui.content.addView(row, rowParams(c))
        }

        heading(ui, "Chiave API", null)
        val keyRow = actionRow(
            c,
            "Chiave ${DebridSettings.labelOf(DebridSettings.service)}",
            DebridSettings.maskedKey()
        ) { editDebridKey(ui) }
        keyRow.tag = "debrid_key"
        ui.content.addView(keyRow, rowParams(c))

        ui.content.addView(actionRow(c, "Apri pagina delle chiavi",
            "Apre ${DebridSettings.services.first { it.id == DebridSettings.service }.keyPage} nel browser"
        ) {
            openKeyPage(ui)
        }, rowParams(c))

        ui.content.addView(actionRow(c, "Verifica chiave",
            "Controlla la chiave sul servizio e mostra l'account"
        ) {
            verifyDebridKey(ui)
        }, rowParams(c))

        ui.content.addView(actionRow(c, "Elimina chiave salvata", null) {
            DebridSettings.setApiKey("")
            Debrid.clearCache()
            toast(c, "Chiave eliminata")
            refresh(ui, "debrid_key")
        }, rowParams(c))

        heading(ui, "Download", "Con i torrent non in cache il magnet viene inviato al servizio.")
        val wait = DebridSettings.waitSeconds
        val waitRow = actionRow(
            c,
            if (wait == 0) "Attesa: nessuna (solo torrent in cache)" else "Attesa massima: $wait s",
            if (wait == 0) {
                "Solo link diretti per i torrent gia' in cache; gli altri restano magnet"
            } else {
                "Il servizio scarica il torrent; se non e' pronto entro $wait s resta il magnet e al prossimo tentativo sara' diretto"
            }
        ) {
            DebridSettings.waitSeconds = DebridSettings.nextWaitSeconds()
            Debrid.clearCache()
            refresh(ui, "debrid_wait")
        }
        waitRow.tag = "debrid_wait"
        ui.content.addView(waitRow, rowParams(c))
    }

    private fun editDebridKey(ui: Ui) {
        val c = ui.context
        val service = DebridSettings.labelOf(DebridSettings.service)
        val input = EditText(c).apply {
            setText(DebridSettings.apiKey)
            hint = "Chiave API $service"
            setSingleLine(true)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setSelectAllOnFocus(true)
        }
        val box = FrameLayout(c).apply {
            setPadding(dp(c, 20), dp(c, 8), dp(c, 20), 0)
            addView(input)
        }
        AlertDialog.Builder(c)
            .setTitle("Chiave API $service")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                DebridSettings.setApiKey(input.text.toString())
                Debrid.clearCache()
                toast(c, "Chiave salvata")
                refresh(ui, "debrid_key")
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun openKeyPage(ui: Ui) {
        val c = ui.context
        val service = DebridSettings.services.firstOrNull { it.id == DebridSettings.service }
            ?: DebridSettings.services.first()
        try {
            c.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(service.keyPage))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            toast(c, "Nessun browser disponibile: ${service.keyPage}")
        }
    }

    private fun verifyDebridKey(ui: Ui) {
        val c = ui.context
        val service = DebridSettings.service
        val key = DebridSettings.apiKey
        if (key.isBlank()) {
            toast(c, "Inserisci prima la chiave")
            return
        }
        toast(c, "Verifica in corso...")
        Thread {
            val message = runBlocking { Debrid.check(service, key) }
            ui.main.post { toast(c, message) }
        }.apply { isDaemon = true }.start()
    }

    // ------------------------------------------------------------ Altro

    private fun otherTab(ui: Ui) {
        val c = ui.context
        heading(ui, "IlCorsaroViola", "Numero massimo di torrent tra i link (4K e 1080p con più seed per primi).")
        ui.content.addView(stepperRow(ui, "Risultati torrent", { HubSettings.torrentMax }) {
            HubSettings.torrentMax = HubSettings.torrentMax + it
        }, rowParams(c))

        heading(ui, "MovyITA e DualSync", "Audio italiano VixSrc sul video Movy/Cinejoy.")
        ui.content.addView(toggleRow(c, "Solo link sincronizzati",
            "Mostra solo i link ITA con offset calcolato e applicato (niente \"solo video\" né \"da misurare\")",
            get = { HubSettings.syncedOnly },
            set = { HubSettings.syncedOnly = it }
        ), rowParams(c))

        // Sincronizzazione Crittografata E2EE (PriSynx Vault)
        prisynx.sync.SyncUi.renderSyncSection(
            context = c,
            container = ui.content,
            heading = { title, desc -> heading(ui, title, desc) },
            toast = { msg -> toast(c, msg) }
        )

        heading(ui, "Ripristino", null)
        ui.content.addView(actionRow(c, "Ripristina impostazioni predefinite",
            "Provider, ordine, cataloghi, TopX, anime, RaiPlay, KissKH, TV e debrid") {
            AlertDialog.Builder(c)
                .setTitle("Ripristinare tutto?")
                .setMessage("Tutte le impostazioni di PriSynx torneranno ai valori predefiniti.")
                .setPositiveButton("Ripristina") { _, _ ->
                    HubSettings.resetAll()
                    TopX.clearCache()
                    KissKHHub.clearCache()
                    toast(c, "Impostazioni ripristinate")
                    refresh(ui)
                }
                .setNegativeButton("Annulla", null)
                .show()
        }, rowParams(c))
    }

    // ------------------------------------------------------------ async lists

    private fun asyncList(
        ui: Ui,
        load: () -> List<Pair<String, String>>,
        isOn: (String) -> Boolean,
        setOn: (String, Boolean) -> Unit,
        empty: String
    ) {
        val c = ui.context
        val box = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        box.addView(note(c, "Caricamento…"))
        ui.content.addView(box)
        val gen = ui.generation
        Thread {
            val items = try {
                load()
            } catch (e: Exception) {
                emptyList()
            }
            ui.main.post {
                if (gen != ui.generation) return@post
                box.removeAllViews()
                if (items.isEmpty()) {
                    box.addView(note(c, empty))
                    return@post
                }
                val repaints = mutableListOf<() -> Unit>()
                val bulk = LinearLayout(c).apply { orientation = LinearLayout.HORIZONTAL }
                bulk.addView(actionRow(c, "Mostra tutti", null) {
                    items.forEach { setOn(it.first, true) }
                    repaints.forEach { it() }
                }, weightParams(c, right = 8))
                bulk.addView(actionRow(c, "Nascondi tutti", null) {
                    items.forEach { setOn(it.first, false) }
                    repaints.forEach { it() }
                }, weightParams(c, left = 8))
                box.addView(bulk, rowParams(c))
                items.forEach { (key, label) ->
                    val (row, repaint) = toggle(c, label, null,
                        get = { isOn(key) },
                        set = { setOn(key, it) }
                    )
                    repaints.add(repaint)
                    box.addView(row, rowParams(c))
                }
                linkTabsToContent(ui)
            }
        }.start()
    }

    // ------------------------------------------------------------ rows

    private fun baseRow(context: Context): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(context, 60)
        setPadding(dp(context, 18), dp(context, 10), dp(context, 14), dp(context, 10))
        isFocusable = true
        isClickable = true
        background = rowBackground(context)
    }

    private fun labels(context: Context, row: LinearLayout, title: String, subtitle: String?) {
        val col = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        col.addView(TextView(context).apply {
            text = title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        if (subtitle != null) {
            col.addView(TextView(context).apply {
                text = subtitle
                setTextColor(SUB)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }
        row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
    }

    private fun toggleRow(
        context: Context,
        title: String,
        subtitle: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ): View = toggle(context, title, subtitle, get, set).first

    /** Toggle row plus a callback that repaints its ON/OFF state. */
    private fun toggle(
        context: Context,
        title: String,
        subtitle: String?,
        get: () -> Boolean,
        set: (Boolean) -> Unit
    ): Pair<View, () -> Unit> {
        val row = baseRow(context)
        labels(context, row, title, subtitle)
        val state = statePill(context)
        row.addView(state)
        paintState(state, get())
        row.setOnClickListener {
            val next = !get()
            set(next)
            paintState(state, next)
        }
        return row to { paintState(state, get()) }
    }

    private fun actionRow(context: Context, title: String, subtitle: String?, onClick: () -> Unit): View {
        val row = baseRow(context)
        labels(context, row, title, subtitle)
        row.addView(TextView(context).apply {
            text = "›"
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            setPadding(dp(context, 10), 0, dp(context, 4), 0)
        })
        row.setOnClickListener { onClick() }
        return row
    }

    /** Main row (toggle) followed by focusable ▲ ▼ buttons. */
    private fun orderRow(
        ui: Ui,
        tagPrefix: String,
        title: String,
        on: (() -> Boolean)?,
        toggle: (() -> Unit)?,
        canUp: Boolean,
        canDown: Boolean,
        move: (Int) -> Unit
    ): View {
        val c = ui.context
        val line = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val row = baseRow(c).apply { tag = "${tagPrefix}_row" }
        labels(c, row, title, null)
        if (on != null && toggle != null) {
            val state = statePill(c)
            row.addView(state)
            paintState(state, on())
            row.setOnClickListener {
                toggle()
                paintState(state, on())
            }
        } else {
            row.isFocusable = false
            row.isClickable = false
        }
        line.addView(row, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        line.addView(arrow(c, "▲", canUp, "${tagPrefix}_up") {
            move(-1)
            refresh(ui, "${tagPrefix}_up", "${tagPrefix}_down")
        }, arrowParams(c))
        line.addView(arrow(c, "▼", canDown, "${tagPrefix}_down") {
            move(1)
            refresh(ui, "${tagPrefix}_down", "${tagPrefix}_up")
        }, arrowParams(c))
        return line
    }

    private fun stepperRow(ui: Ui, title: String, value: () -> Int, change: (Int) -> Unit): View {
        val c = ui.context
        val line = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply { setColor(CARD); cornerRadius = dp(c, 12).toFloat() }
            setPadding(dp(c, 18), dp(c, 6), dp(c, 6), dp(c, 6))
            minimumHeight = dp(c, 60)
        }
        line.addView(TextView(c).apply {
            text = title
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val number = TextView(c).apply {
            text = value().toString()
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            minWidth = dp(c, 56)
        }
        line.addView(arrow(c, "−", true, "step_minus") {
            change(-1)
            number.text = value().toString()
        }, arrowParams(c))
        line.addView(number)
        line.addView(arrow(c, "+", true, "step_plus") {
            change(1)
            number.text = value().toString()
        }, arrowParams(c))
        return line
    }

    // ------------------------------------------------------------ widgets

    private fun heading(ui: Ui, title: String, description: String?) {
        val c = ui.context
        ui.content.addView(TextView(c).apply {
            text = title
            setTextColor(ACCENT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 19f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(c, 4), dp(c, if (ui.content.childCount == 0) 0 else 18), 0, dp(c, 2))
        })
        if (description != null) ui.content.addView(note(c, description))
    }

    private fun note(context: Context, value: String) = TextView(context).apply {
        text = value
        setTextColor(SUB)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setPadding(dp(context, 4), dp(context, 2), dp(context, 4), dp(context, 8))
    }

    private fun statePill(context: Context) = TextView(context).apply {
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        minWidth = dp(context, 64)
        setPadding(dp(context, 14), dp(context, 6), dp(context, 14), dp(context, 6))
    }

    private fun paintState(view: TextView, on: Boolean) {
        view.text = if (on) "ON" else "OFF"
        view.setTextColor(if (on) Color.WHITE else SUB)
        view.background = GradientDrawable().apply {
            setColor(if (on) ON else OFF)
            cornerRadius = dp(view.context, 20).toFloat()
        }
    }

    private fun arrow(context: Context, symbol: String, enabled: Boolean, tagValue: String, onClick: () -> Unit) =
        TextView(context).apply {
            text = symbol
            tag = tagValue
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            setTextColor(if (enabled) TEXT else OFF)
            isFocusable = enabled
            isClickable = enabled
            background = rowBackground(context)
            if (enabled) setOnClickListener { onClick() }
        }

    private fun pill(context: Context, label: String, onClick: () -> Unit) = TextView(context).apply {
        text = label
        id = View.generateViewId()
        setTextColor(TEXT)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setPadding(dp(context, 18), dp(context, 8), dp(context, 18), dp(context, 8))
        isFocusable = true
        isClickable = true
        background = rowBackground(context)
        setOnClickListener { onClick() }
    }

    private fun rowBackground(context: Context): Drawable {
        fun shape(fill: Int, stroke: Int?) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, 12).toFloat()
            if (stroke != null) setStroke(dp(context, 3), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(CARD_FOCUS, ACCENT))
            addState(intArrayOf(android.R.attr.state_pressed), shape(CARD_FOCUS, ACCENT))
            addState(intArrayOf(), shape(CARD, null))
        }
    }

    private fun tabBackground(context: Context): Drawable {
        fun shape(fill: Int, stroke: Int?) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, 22).toFloat()
            if (stroke != null) setStroke(dp(context, 3), stroke)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), shape(ACCENT, Color.WHITE))
            addState(intArrayOf(android.R.attr.state_selected), shape(ACCENT, null))
            addState(intArrayOf(android.R.attr.state_pressed), shape(ACCENT, null))
            addState(intArrayOf(), shape(ACCENT_SOFT, null))
        }
    }

    private fun rowParams(context: Context) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(context, 8) }

    private fun weightParams(context: Context, left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            leftMargin = dp(context, left / 2)
            rightMargin = dp(context, right / 2)
        }

    private fun arrowParams(context: Context) =
        LinearLayout.LayoutParams(dp(context, 60), dp(context, 60)).apply { leftMargin = dp(context, 8) }

    private fun shorten(url: String) = if (url.length > 48) url.take(45) + "…" else url

    private fun toast(context: Context, message: String) =
        Toast.makeText(context, message, Toast.LENGTH_SHORT).show()

    private fun dp(context: Context, value: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), context.resources.displayMetrics).toInt()
}
