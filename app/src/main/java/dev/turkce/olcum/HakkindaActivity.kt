package dev.turkce.olcum

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.concurrent.thread

private const val PAD_X = 96
private const val PAD_Y = 40
private const val QR_CELL_PX = 8

/**
 * Hakkında ve lisanslar: what the app is, what it does with the picture, where its source and privacy policy are
 * (as QR codes for the phone), its licence, the components it ships and the trademark notice. The remote's
 * up and down keys scroll the page; the one button opens the full licence texts.
 */
class HakkindaActivity : Activity() {
    private lateinit var components: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val full = tvButton("Tam lisans metinleri") { startActivity(Intent(this, LisansActivity::class.java)) }
        components = paragraph("Yükleniyor…", 18f)
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(PAD_X, PAD_Y, PAD_X, PAD_Y)
            addView(label(About.NAME, 34f, bold = true))
            addView(label("Sürüm ${BuildConfig.VERSION_NAME}", 18f).apply { setTextColor(SCREEN_DIM_TEXT) })
            addView(full, wrap().apply { topMargin = 16 })
            addView(paragraph(About.DESCRIPTION))
            addView(heading("Gizlilik"))
            addView(paragraph(About.PRIVACY))
            addView(LinearLayout(this@HakkindaActivity).apply {
                addView(qr("Gizlilik politikası", Baglantilar.PRIVACY), wrap().apply { marginEnd = 64 })
                addView(qr("Kaynak kodu", Baglantilar.SOURCE), wrap())
            }, wrap().apply { topMargin = 16 })
            addView(heading("Lisans"))
            addView(paragraph(About.LICENSE))
            addView(heading("Üçüncü taraf bileşenleri"))
            addView(paragraph(About.THIRD_PARTY_NOTE, 18f).apply { setTextColor(SCREEN_DIM_TEXT) })
            addView(components)
            addView(heading("Marka"))
            addView(paragraph(About.TRADEMARK))
        }
        val root = ScrollView(this).apply {
            setBackgroundColor(SCREEN_BACKGROUND)
            // With no button below, the arrows scroll the page itself.
            isFocusable = true
            isSmoothScrollingEnabled = true
            addView(content)
        }
        // Android 15 and later draw apps under the system bars; keep the content clear of them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { _, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                content.setPadding(PAD_X + bars.left, PAD_Y + bars.top, PAD_X + bars.right, PAD_Y + bars.bottom)
                insets
            }
        }
        setContentView(root)
        full.requestFocus()
        loadComponents()
    }

    override fun onResume() {
        super.onResume()
        Translation.screenOpen = true
    }

    override fun onPause() {
        Translation.screenOpen = false
        super.onPause()
    }

    /** The component list comes from the licence file in the APK, read off the main thread. */
    private fun loadComponents() {
        thread(isDaemon = true, name = "hakkinda") {
            val list = runCatching { summary(About.groups(this)) }.getOrElse { "Bileşen listesi okunamadı." }
            runOnUiThread { if (!isDestroyed) components.text = list }
        }
    }

    /** Each group as a bold title, each component as "ad sürüm · lisans" with its copyright dimmed below. */
    private fun summary(groups: List<ComponentGroup>): CharSequence = SpannableStringBuilder().apply {
        for (group in groups) {
            if (isNotEmpty()) append("\n")
            span(group.title + "\n", StyleSpan(Typeface.BOLD), RelativeSizeSpan(1.15f))
            for (item in group.items) {
                append("• ").append(About.describe(item)).append("\n")
                span("   " + item.copyright + "\n", ForegroundColorSpan(SCREEN_DIM_TEXT), RelativeSizeSpan(0.85f))
            }
        }
    }

    private fun SpannableStringBuilder.span(text: String, vararg spans: Any) {
        val start = length
        append(text)
        spans.forEach { setSpan(it, start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }
    }

    /** A QR code for [url] with its name and the address written under it, for typing when the camera fails. */
    private fun qr(name: String, url: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(ImageView(this@HakkindaActivity).apply {
            setImageBitmap(qrBitmap(url, QR_CELL_PX))
            contentDescription = "$name QR kodu"
        })
        addView(label(name, 20f, bold = true).apply { gravity = Gravity.CENTER })
        addView(label(url, 16f).apply {
            gravity = Gravity.CENTER
            setTextColor(SCREEN_DIM_TEXT)
        })
    }

    private fun heading(text: String) = label(text, 24f, bold = true).apply { setPadding(0, 36, 0, 4) }

    private fun paragraph(text: String, size: Float = 20f) = label(text, size).apply { setLineSpacing(0f, 1.15f) }

    private fun label(value: String, size: Float, bold: Boolean = false) = TextView(this).apply {
        text = value
        textSize = size
        if (bold) typeface = Typeface.DEFAULT_BOLD
        setTextColor(Color.WHITE)
        setPadding(0, 8, 0, 8)
    }

    private fun wrap() = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
}
