package prisynx.sync

import android.app.AlertDialog
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * TV & Mobile friendly UI for PriSynx Zero-Knowledge Cloud Sync.
 */
object SyncUi {
    private const val CARD = 0xFF211833.toInt()
    private const val CARD_FOCUS = 0xFF3B2566.toInt()
    private const val ACCENT = 0xFF9B5CFF.toInt()
    private const val TEXT = 0xFFF3EEFF.toInt()
    private const val SUB = 0xFFAFA2C9.toInt()

    private fun dp(c: Context, v: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), c.resources.displayMetrics).toInt()

    private fun buttonBg(context: Context) = StateListDrawable().apply {
        fun shape(fill: Int, stroke: Int?) = GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(context, 12).toFloat()
            if (stroke != null) setStroke(dp(context, 3), stroke)
        }
        addState(intArrayOf(android.R.attr.state_focused), shape(CARD_FOCUS, ACCENT))
        addState(intArrayOf(android.R.attr.state_pressed), shape(CARD_FOCUS, ACCENT))
        addState(intArrayOf(), shape(CARD, null))
    }

    fun renderSyncSection(
        context: Context,
        container: LinearLayout,
        heading: (String, String?) -> Unit,
        toast: (String) -> Unit
    ) {
        val sp = context.getSharedPreferences("prisynx_sync", Context.MODE_PRIVATE)
        val defaultUrl = sp.getString("server_url", SyncClient.getDefaultServerUrl()) ?: SyncClient.getDefaultServerUrl()
        val savedPin = sp.getString("last_pin", "") ?: ""

        heading("Sincronizzazione Cloud (Crittografata E2EE)",
            "Salva e ripristina preferenze, repository, plugin terzi, chiavi debrid e cronologia usando un PIN a 6 cifre.")

        // URL Server
        val serverField = EditText(context).apply {
            setText(defaultUrl)
            hint = "URL Server Vault (es. https://prisync.stremio-italia.eu)"
            setTextColor(TEXT)
            setHintTextColor(SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            isSingleLine = true
            background = buttonBg(context)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            isFocusable = true
        }
        container.addView(serverField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 10)
        })

        // PIN Field
        val pinField = EditText(context).apply {
            setText(savedPin)
            hint = "PIN a 6 cifre (es. 481920)"
            setTextColor(TEXT)
            setHintTextColor(SUB)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER_HORIZONTAL
            inputType = InputType.TYPE_CLASS_NUMBER
            isSingleLine = true
            background = buttonBg(context)
            setPadding(dp(context, 16), dp(context, 12), dp(context, 16), dp(context, 12))
            isFocusable = true
        }
        container.addView(pinField, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 14)
        })

        fun createBtn(label: String, onClick: () -> Unit) = TextView(context).apply {
            text = label
            setTextColor(TEXT)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            isFocusable = true
            isClickable = true
            background = buttonBg(context)
            setPadding(dp(context, 16), dp(context, 14), dp(context, 16), dp(context, 14))
            setOnClickListener { onClick() }
        }

        // Action 1: Genera PIN casuale
        val btnGenPin = createBtn("🎲 Genera nuovo PIN casuale") {
            val randomPin = CryptoEngine.generatePin()
            pinField.setText(randomPin)
            toast("Nuovo PIN generato: $randomPin")
        }
        container.addView(btnGenPin, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 8)
        })

        // Action 2: Invia Backup Cifrato
        val btnPush = createBtn("📤 Cifra e Invia Backup alla VPS") {
            val pin = pinField.text.toString().trim()
            val url = serverField.text.toString().trim()
            if (!pin.matches(Regex("^\\d{6}$"))) {
                toast("Inserisci un PIN numerico di esattamente 6 cifre!")
                return@createBtn
            }
            if (url.isEmpty()) {
                toast("Inserisci un URL valido per il server!")
                return@createBtn
            }

            sp.edit().putString("server_url", url).putString("last_pin", pin).apply()
            toast("Cifratura AES-256 e caricamento in corso...")

            CoroutineScope(Dispatchers.IO).launch {
                val fullJson = FullBackupManager.createFullBackupJson(context)
                val (ok, msg) = SyncClient.pushVault(url, pin, fullJson)
                withContext(Dispatchers.Main) {
                    AlertDialog.Builder(context)
                        .setTitle(if (ok) "Backup Inviato con Successo! ✅" else "Errore Caricamento ❌")
                        .setMessage(if (ok) "Il backup cifrato è pronto sulla VPS!\n\nPer ripristinare su un altro dispositivo, inserisci questo PIN:\n👉 $pin" else msg)
                        .setPositiveButton("OK", null)
                        .show()
                }
            }
        }
        container.addView(btnPush, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 8)
        })

        // Action 3: Scarica e Ripristina Backup
        val btnPull = createBtn("📥 Scarica e Ripristina da questo PIN") {
            val pin = pinField.text.toString().trim()
            val url = serverField.text.toString().trim()
            if (!pin.matches(Regex("^\\d{6}$"))) {
                toast("Inserisci il PIN di 6 cifre da cui ripristinare!")
                return@createBtn
            }
            if (url.isEmpty()) {
                toast("Inserisci un URL valido per il server!")
                return@createBtn
            }

            AlertDialog.Builder(context)
                .setTitle("Ripristinare la configurazione?")
                .setMessage("Tutte le preferenze, cronologia, chiavi Debrid e plugin verranno sovrascritti con quelli salvati nel PIN $pin.")
                .setPositiveButton("Ripristina Ora") { _, _ ->
                    sp.edit().putString("server_url", url).putString("last_pin", pin).apply()
                    toast("Download e decifratura in corso...")

                    CoroutineScope(Dispatchers.IO).launch {
                        val (ok, res) = SyncClient.pullVault(url, pin)
                        if (!ok) {
                            withContext(Dispatchers.Main) {
                                AlertDialog.Builder(context)
                                    .setTitle("Errore Ripristino ❌")
                                    .setMessage(res)
                                    .setPositiveButton("OK", null)
                                    .show()
                            }
                            return@launch
                        }

                        val restored = FullBackupManager.restoreFullBackupJson(context, res)
                        withContext(Dispatchers.Main) {
                            if (restored) {
                                runCatching {
                                    val clazz = Class.forName("com.lagradost.cloudstream3.MainActivity")
                                    val method = clazz.getMethod("bookmarksUpdatedEvent", Boolean::class.javaPrimitiveType)
                                    method.invoke(null, true)
                                }
                                AlertDialog.Builder(context)
                                    .setTitle("Ripristino Completato! ✅")
                                    .setMessage(buildString {
                                        val r = FullBackupManager.lastReport
                                        append("Gruppi di impostazioni ripristinati: ${FullBackupManager.lastPrefsRestored}\n")
                                        if (r != null) {
                                            append("Repository: ${r.repos}\n")
                                            append("Plugin nell'elenco: ${r.plugins}\n")
                                            if (r.downloaded.isNotEmpty()) append("\nScaricati ora: ${r.downloaded.joinToString()}\n")
                                            if (r.failed.isNotEmpty()) append("\nDa scaricare al riavvio: ${r.failed.joinToString()}\n")
                                        } else append("Elenco plugin non letto.\n")
                                        append("\nChiudi del tutto CloudStream (anche dalle app recenti) e riaprilo.")
                                    })
                                    .setPositiveButton("Perfetto", null)
                                    .show()
                            } else {
                                toast("Errore durante l'applicazione dei dati.")
                            }
                        }
                    }
                }
                .setNegativeButton("Annulla", null)
                .show()
        }
        container.addView(btnPull, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            bottomMargin = dp(context, 16)
        })
    }
}
