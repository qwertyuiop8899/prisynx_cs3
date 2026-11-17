package prisync

import android.app.AlertDialog
import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import prisynx.sync.SyncUi

@CloudstreamPlugin
class PriSyncPlugin : Plugin() {
    override fun load(context: Context) {
        openSettings = { ctx -> show(ctx) }
    }

    private fun show(c: Context) {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()
        val content = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(16))
            setBackgroundColor(0xFF140E20.toInt())
        }
        SyncUi.renderSyncSection(
            context = c,
            container = content,
            heading = { title, desc ->
                content.addView(TextView(c).apply {
                    text = title
                    setTextColor(0xFFF3EEFF.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                    typeface = Typeface.DEFAULT_BOLD
                    setPadding(0, dp(8), 0, dp(4))
                })
                if (desc != null) content.addView(TextView(c).apply {
                    text = desc
                    setTextColor(0xFFAFA2C9.toInt())
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setPadding(0, 0, 0, dp(12))
                })
            },
            toast = { msg -> Toast.makeText(c, msg, Toast.LENGTH_SHORT).show() }
        )
        AlertDialog.Builder(c)
            .setTitle("PriSync")
            .setView(ScrollView(c).apply { addView(content) })
            .setPositiveButton("Chiudi", null)
            .show()
    }
}
