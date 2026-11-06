package prisynx.live

import android.app.AlertDialog
import android.content.Context
import com.lagradost.cloudstream3.utils.Coroutines.ioSafe
import prisynx.vavoo.VavooOptions

/** Native list dialogs only: fully usable with a TV remote. */
object LiveSettingsDialog {
    fun show(ctx: Context) {
        val items = arrayOf(
            "📺 TV Vavoo: nazioni",
            "🏆 SportzX: sport da mostrare",
            "⚙️ SportzX: opzioni"
        )
        AlertDialog.Builder(ctx)
            .setTitle("PriSynx Live")
            .setItems(items) { _, which ->
                when (which) {
                    0 -> countries(ctx)
                    1 -> sports(ctx)
                    2 -> options(ctx)
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
}
