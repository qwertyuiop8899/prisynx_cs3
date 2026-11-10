package prisynx.live

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import prisynx.vavoo.VavooOptions

/** Native list dialogs only: fully usable with a TV remote. */
object LiveSettingsDialog {
    fun show(ctx: Context) {
        val items = arrayOf(
            "📺 TV Vavoo: nazioni",
            "🏆 SportzX: sport da mostrare",
            "⚙️ SportzX: opzioni",
            "⚽ Partite.cc: sezioni",
            "🏆 Partite.cc: campionati",
            "📺 Partite.cc: canali TV",
            "🔑 Partite.cc: account (solo per In diretta)"
        )
        AlertDialog.Builder(ctx)
            .setTitle("PriSynx Live")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> countries(ctx)
                    1 -> sports(ctx)
                    2 -> options(ctx)
                    3 -> paSections(ctx)
                    4 -> leagueGroups(ctx, PartiteCatalog.leagues)
                    5 -> channelGroups(ctx, PartiteCatalog.channels)
                    6 -> account(ctx)
                }
            }
            .setNegativeButton("Chiudi", null)
            .show()
    }

    private fun countries(ctx: Context) {
        val list = VavooOptions.countries
        val checked = list.map { LiveSettings.isCountryEnabled(it.group) }.toBooleanArray()
        AlertDialog.Builder(ctx)
            .setTitle("Nazioni TV")
            .setMultiChoiceItems(list.map { it.label }.toTypedArray(), checked) { _, i, on ->
                LiveSettings.setCountryEnabled(list[i].group, on)
            }
            .setPositiveButton("OK") { _, _ -> show(ctx) }
            .show()
    }

    private fun sports(ctx: Context) {
        // Refresh in background so newly seen categories are remembered, but show the full list now.
        ioSafe { runCatching { SportzXProvider.events() } }
        val cats = LiveSettings.allSports()
        val checked = cats.map { LiveSettings.isSportEnabled(it) }.toBooleanArray()
        AlertDialog.Builder(ctx)
            .setTitle("Sport da mostrare")
            .setMultiChoiceItems(cats.toTypedArray(), checked) { _, i, on ->
                LiveSettings.setSportEnabled(cats[i], on)
            }
            .setPositiveButton("OK") { _, _ -> show(ctx) }
            .show()
    }

    private fun options(ctx: Context) {
        val labels = arrayOf(
            "Mostra solo eventi in diretta",
            "Mostra canali protetti (ClearKey DASH)"
        )
        val checked = booleanArrayOf(LiveSettings.sportOnlyLive, LiveSettings.sportShowDrm)
        AlertDialog.Builder(ctx)
            .setTitle("Opzioni SportzX")
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                if (i == 0) LiveSettings.sportOnlyLive = on else LiveSettings.sportShowDrm = on
            }
            .setPositiveButton("OK") { _, _ -> show(ctx) }
            .show()
    }

    private fun paSections(ctx: Context) {
        val labels = arrayOf(
            "🔴 In diretta ora (solo partite in corso)",
            "🗓️ In programma oggi (per sport)",
            "🏆 Campionati scelti",
            "📺 Canali TV scelti"
        )
        val checked = booleanArrayOf(LiveSettings.paLive, LiveSettings.paToday, LiveSettings.paLeagues, LiveSettings.paChannels)
        AlertDialog.Builder(ctx)
            .setTitle("Sezioni Partite.cc")
            .setMultiChoiceItems(labels, checked) { _, i, on ->
                when (i) {
                    0 -> LiveSettings.paLive = on
                    1 -> LiveSettings.paToday = on
                    2 -> LiveSettings.paLeagues = on
                    else -> LiveSettings.paChannels = on
                }
            }
            .setPositiveButton("OK") { _, _ -> show(ctx) }
            .show()
    }

    private fun leagueGroups(ctx: Context, all: List<PartiteEngine.LeagueInfo>) {
        val groups = all.groupBy { it.group }
        // Italy first, then countries with something selected, then alphabetical.
        val keys = groups.keys.sortedWith(compareBy<String>(
            { !it.contains("Italia") },
            { k -> groups[k]!!.none { LiveSettings.leagueSelected(it.key) } },
            { it.lowercase() }
        ))
        val labels = keys.map { k ->
            val n = groups[k]!!.count { LiveSettings.leagueSelected(it.key) }
            if (n > 0) "$k  ✅ $n" else k
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Campionati: scegli il paese")
            .setItems(labels) { _, i -> leagues(ctx, all, groups[keys[i]]!!) }
            .setPositiveButton("Fatto") { _, _ -> show(ctx) }
            .show()
    }

    private fun leagues(ctx: Context, all: List<PartiteEngine.LeagueInfo>, list: List<PartiteEngine.LeagueInfo>) {
        val checked = list.map { LiveSettings.leagueSelected(it.key) }.toBooleanArray()
        AlertDialog.Builder(ctx)
            .setTitle(list.first().group)
            .setMultiChoiceItems(list.map { it.name }.toTypedArray(), checked) { _, i, on ->
                LiveSettings.setLeague(list[i].key, on)
            }
            .setPositiveButton("OK") { _, _ -> leagueGroups(ctx, all) }
            .show()
    }

    private fun channelGroups(ctx: Context, all: List<PartiteEngine.ChannelInfo>) {
        val groups = all.groupBy { it.country }
        val keys = groups.keys.sortedWith(compareBy<String>({ !it.equals("Italia", true) }, { it.lowercase() }))
        val labels = keys.map { k ->
            val list = groups[k]!!
            val n = list.count { LiveSettings.channelSelected(it.slug, it.country) }
            "$k  ($n/${list.size})"
        }.toTypedArray()
        AlertDialog.Builder(ctx)
            .setTitle("Canali TV: scegli il paese")
            .setItems(labels) { _, i -> channels(ctx, all, groups[keys[i]]!!) }
            .setPositiveButton("Fatto") { _, _ -> show(ctx) }
            .show()
    }

    private fun channels(ctx: Context, all: List<PartiteEngine.ChannelInfo>, list: List<PartiteEngine.ChannelInfo>) {
        val checked = list.map { LiveSettings.channelSelected(it.slug, it.country) }.toBooleanArray()
        AlertDialog.Builder(ctx)
            .setTitle(list.first().country)
            .setMultiChoiceItems(list.map { it.name }.toTypedArray(), checked) { _, i, on ->
                LiveSettings.setChannel(all, list[i].slug, on)
            }
            .setPositiveButton("OK") { _, _ -> channelGroups(ctx, all) }
            .show()
    }

    private fun account(ctx: Context) {
        val user = EditText(ctx).apply { hint = "Username"; setText(LiveSettings.partiteUser) }
        val pass = EditText(ctx).apply {
            hint = "Password"; setText(LiveSettings.partitePass)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val box = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 16, 48, 0)
            addView(user); addView(pass)
        }
        AlertDialog.Builder(ctx)
            .setTitle("Account Partite.cc")
            .setView(box)
            .setPositiveButton("Salva") { _, _ ->
                LiveSettings.partiteUser = user.text.toString().trim()
                LiveSettings.partitePass = pass.text.toString()
                show(ctx)
            }
            .setNegativeButton("Annulla") { _, _ -> show(ctx) }
            .show()
    }
}
