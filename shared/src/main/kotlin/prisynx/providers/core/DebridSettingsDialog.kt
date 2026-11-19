package prisynx.providers.core

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.runBlocking

/**
 * Debrid settings for the stand-alone IlCorsaroViola extension.
 *
 * The hub shows the same values in its "Debrid" tab; both read and write
 * [DebridSettings], so a key entered in one place is immediately visible in the
 * other. The key is stored in the app-private SharedPreferences and never
 * shipped with the plugin.
 */
object DebridSettingsDialog {
    fun show(context: Context) {
        DebridSettings.attach(context)
        val density = context.resources.displayMetrics.density
        fun dp(value: Int): Int = (value * density).toInt()

        val content = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        content.addView(TextView(context).apply {
            text = "Con un servizio debrid i magnet di IlCorsaroViola diventano link " +
                "diretti. I torrent gia' in cache sono immediati; gli altri vengono " +
                "inviati al servizio e restano magnet finche' il download non e' pronto. " +
                "La chiave resta salvata sul dispositivo."
            textSize = 13f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(8))
        })

        val enabled = CheckBox(context).apply {
            text = "Usa il servizio debrid"
            isChecked = DebridSettings.enabled
            setPadding(0, dp(6), 0, dp(6))
        }
        content.addView(enabled)

        val group = RadioGroup(context)
        DebridSettings.services.forEach { service ->
            val button = RadioButton(context).apply {
                id = View.generateViewId()
                text = service.label
                isChecked = service.id == DebridSettings.service
                setPadding(0, dp(4), 0, dp(4))
            }
            button.tag = service.id
            group.addView(button)
        }
        content.addView(group)

        val keyField = EditText(context).apply {
            setText(DebridSettings.apiKey)
            hint = "Chiave API del servizio selezionato"
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(dp(8), dp(6), dp(8), dp(6))
        }
        content.addView(
            keyField,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(6) }
        )

        // Switching service saves the typed key on the previous one and loads
        // the key already stored for the new one.
        var current = DebridSettings.service
        group.setOnCheckedChangeListener { _, checkedId ->
            val next = group.findViewById<RadioButton>(checkedId)?.tag as? String
                ?: return@setOnCheckedChangeListener
            if (next == current) return@setOnCheckedChangeListener
            DebridSettings.service = current
            DebridSettings.setApiKey(keyField.text.toString())
            current = next
            DebridSettings.service = next
            keyField.setText(DebridSettings.apiKey)
        }

        val cacheOnly = RadioGroup(context)
        val onlyCached = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Mostra tutti, riproduci solo quelli in cache " +
                "(RD non ha cache check: prova ad avviare, se non parte esce con " +
                "errore e cancella il torrent)"
            isChecked = DebridSettings.cacheOnly
        }
        val p2p = RadioButton(context).apply {
            id = View.generateViewId()
            text = "Se non in cache avvia in P2P (magnet)"
            isChecked = !DebridSettings.cacheOnly
        }
        cacheOnly.addView(onlyCached)
        cacheOnly.addView(p2p)
        content.addView(cacheOnly)

        content.addView(Button(context).apply {
            text = "Apri pagina delle chiavi"
            setOnClickListener {
                val service = DebridSettings.services.firstOrNull { it.id == current }
                    ?: DebridSettings.services.first()
                try {
                    context.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(service.keyPage))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                } catch (e: Exception) {
                    Toast.makeText(context, service.keyPage, Toast.LENGTH_LONG).show()
                }
            }
        })

        fun waitLabel(): String {
            val seconds = DebridSettings.waitSeconds
            return if (seconds == 0) {
                "Attesa: nessuna (solo torrent in cache)"
            } else {
                "Attesa massima: $seconds s"
            }
        }

        val wait = Button(context).apply {
            text = waitLabel()
            setOnClickListener {
                DebridSettings.waitSeconds = DebridSettings.nextWaitSeconds()
                text = waitLabel()
            }
        }
        content.addView(wait)

        content.addView(TextView(context).apply {
            text = "(Solo in modalita' P2P) Con l'attesa il magnet viene inviato al servizio: se il download non " +
                "e' pronto entro il tempo scelto resta il magnet e al tentativo successivo " +
                "il link e' diretto."
            textSize = 12f
            setTextColor(Color.GRAY)
            setPadding(0, 0, 0, dp(6))
        })

        val verify = Button(context).apply {
            text = "Verifica chiave"
            setOnClickListener {
                val typed = keyField.text.toString().trim()
                if (typed.isEmpty()) {
                    Toast.makeText(context, "Inserisci la chiave", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                isEnabled = false
                text = "Verifica in corso..."
                Thread {
                    val message = runBlocking {
                        Debrid.check(selectedService(group), typed)
                    }
                    Handler(Looper.getMainLooper()).post {
                        Toast.makeText(context, message, Toast.LENGTH_LONG).show()
                        isEnabled = true
                        text = "Verifica chiave"
                    }
                }.apply { isDaemon = true }.start()
            }
        }
        content.addView(verify)

        val scroll = ScrollView(context).apply { addView(content) }

        AlertDialog.Builder(context)
            .setTitle("IlCorsaroViola · Debrid")
            .setView(scroll)
            .setPositiveButton("Salva") { _, _ ->
                DebridSettings.enabled = enabled.isChecked
                DebridSettings.cacheOnly = onlyCached.isChecked
                DebridSettings.service = selectedService(group)
                DebridSettings.setApiKey(keyField.text.toString())
                Debrid.clearCache()
                Toast.makeText(context, "Impostazioni debrid salvate", Toast.LENGTH_SHORT).show()
            }
            .setNeutralButton("Elimina chiave") { _, _ ->
                DebridSettings.service = selectedService(group)
                DebridSettings.setApiKey("")
                Debrid.clearCache()
                Toast.makeText(context, "Chiave eliminata", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Annulla", null)
            .show()
    }

    private fun selectedService(group: RadioGroup): String {
        val checked = group.checkedRadioButtonId
        if (checked == -1) return DebridSettings.service
        val button = group.findViewById<RadioButton>(checked)
        return button?.tag as? String ?: DebridSettings.service
    }
}
