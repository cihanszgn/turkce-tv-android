package dev.turkce.olcum

import android.content.Context
import android.content.SharedPreferences
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

private const val VISIBLE_MS = 5_000L
private const val BOTTOM_GAP_PX = 70
private const val MIN_LINE_PX = 600
private const val PANELS = 3
private const val PANEL_PAD_X_PX = 12
private const val PANEL_PAD_Y_PX = 5
private const val CORNER_PX = 6f
private const val FRAME_WIDTH = 1920
private const val FRAME_HEIGHT = 1080
private const val PLACE_SAMPLE = "Türkçe altyazı burada görünecek."

internal fun typefaceOf(font: SubtitleFont): Typeface = when (font) {
    SubtitleFont.BOLD -> Typeface.DEFAULT_BOLD
    SubtitleFont.PLAIN -> Typeface.DEFAULT
    SubtitleFont.SERIF -> Typeface.SERIF
    SubtitleFont.MONO -> Typeface.MONOSPACE
}

/** Paints a label the way the player chose. */
internal fun TextView.applyStyle(style: OverlayStyle, size: Int) {
    typeface = typefaceOf(style.font)
    setTextColor(style.textArgb)
    background = GradientDrawable().apply { setColor(style.boxArgb); cornerRadius = CORNER_PX }
    setTextSize(TypedValue.COMPLEX_UNIT_SP, size.toFloat())
}

/** A trial subtitle per subtitle area and info-box panels over the game, so a person can judge them. Not the product overlay. */
internal class LiveSubtitle(context: Context) {
    private val handler = Handler(Looper.getMainLooper())
    private val manager = context.getSystemService(WindowManager::class.java)
    private val prefs = SettingsStore.prefs(context)
    private var settings = SettingsStore.load(prefs)
    private val root = FrameLayout(context)

    /** One slot per possible subtitle area; the slot spans the area so the line centres under it. */
    private val slots = List(MAX_AREAS) { FrameLayout(context) }
    private val lines = List(MAX_AREAS) { label(context, Gravity.CENTER) }
    private val hides = List(MAX_AREAS) { index -> Runnable { lines[index].visibility = View.GONE } }
    private val panels = List(PANELS) {
        label(context, Gravity.START).apply { setPadding(PANEL_PAD_X_PX, PANEL_PAD_Y_PX, PANEL_PAD_X_PX, PANEL_PAD_Y_PX) }
    }
    private val changed = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
        val before = settings.turkish
        settings = SettingsStore.load(prefs)
        applySettings()
        // A line where the Turkish will go, so the player sees the new place on the TV straight away.
        if (settings.turkish != before && settings.subtitleAreas.isNotEmpty()) show(0, PLACE_SAMPLE)
    }

    init {
        slots.forEachIndexed { index, slot ->
            slot.addView(lines[index], FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL,
            ))
            root.addView(slot, FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        panels.forEach { panel ->
            root.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT,
        )
        root.addOnLayoutChangeListener { _, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom ->
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) handler.post { placeSlots() }
        }
        handler.post {
            applySettings()
            prefs.registerOnSharedPreferenceChangeListener(changed)
            manager.addView(root, params)
        }
    }

    private fun label(context: Context, textGravity: Int) = TextView(context).apply {
        gravity = textGravity
        setPadding(24, 10, 24, 10)
        visibility = View.GONE
    }

    private fun applySettings() {
        lines.forEach { it.applyStyle(settings.style, settings.style.subtitleSize) }
        panels.forEach { it.applyStyle(settings.style, settings.style.boxSize) }
        placeSlots()
    }

    /**
     * Each line sits at the bottom of its area, centred on it, over the game's own text; or, when the player chose
     * a place for the Turkish, at the bottom of that place, one above the other when several areas are read.
     */
    private fun placeSlots() {
        if (root.width == 0) return
        val scaleX = root.width.toFloat() / FRAME_WIDTH
        val scaleY = root.height.toFloat() / FRAME_HEIGHT
        val areas = settings.subtitleAreas
        val chosen = settings.turkish
        slots.forEachIndexed { index, slot ->
            val read = areas.getOrNull(index)
            if (read == null) {
                slot.visibility = View.GONE
                return@forEachIndexed
            }
            val area = chosen ?: read
            val raise = if (chosen == null) BOTTOM_GAP_PX else chosen.height * index / areas.size
            val width = min(max(area.width, MIN_LINE_PX), FRAME_WIDTH)
            val left = (area.left + area.width / 2 - width / 2).coerceIn(0, FRAME_WIDTH - width)
            slot.visibility = View.VISIBLE
            slot.layoutParams = (slot.layoutParams as FrameLayout.LayoutParams).apply {
                this.width = (width * scaleX).toInt()
                leftMargin = (left * scaleX).toInt()
                bottomMargin = ((FRAME_HEIGHT - area.bottom + raise) * scaleY).toInt()
            }
            lines[index].maxWidth = (width * scaleX).toInt()
        }
    }

    /** Takes everything off the screen at once, as when the player pauses the translation. */
    fun hideAll() {
        handler.post {
            hides.forEach { handler.removeCallbacks(it); it.run() }
            panels.forEach { it.visibility = View.GONE }
        }
    }

    /** [slot] is the area's place among the subtitle areas. */
    fun show(slot: Int, text: String) {
        if (slot !in lines.indices) return
        handler.post {
            lines[slot].text = text
            lines[slot].visibility = View.VISIBLE
            handler.removeCallbacks(hides[slot])
            handler.postDelayed(hides[slot], VISIBLE_MS)
        }
    }

    /** The line is still on the game's screen, so the Turkish stays too. */
    fun keep(slot: Int) {
        if (slot !in lines.indices) return
        handler.post {
            if (lines[slot].visibility == View.VISIBLE) {
                handler.removeCallbacks(hides[slot])
                handler.postDelayed(hides[slot], VISIBLE_MS)
            }
        }
    }

    /** Puts each Turkish text next to its box; boxes are in game-picture pixels. */
    fun showBoxes(boxes: List<Pair<ScreenBlock, String>>) {
        handler.post {
            val scaleX = root.width.toFloat() / FRAME_WIDTH
            val scaleY = root.height.toFloat() / FRAME_HEIGHT
            panels.forEachIndexed { index, panel ->
                val box = boxes.getOrNull(index)
                if (box == null) {
                    panel.visibility = View.GONE
                } else {
                    val place = ScreenText.placePanel(box.first, FRAME_WIDTH, FRAME_HEIGHT)
                    panel.text = box.second
                    panel.maxWidth = (place.maxWidth * scaleX).toInt()
                    panel.layoutParams = (panel.layoutParams as FrameLayout.LayoutParams).apply {
                        gravity = Gravity.START or if (place.fromBottom) Gravity.BOTTOM else Gravity.TOP
                        leftMargin = (place.x * scaleX).toInt()
                        topMargin = if (place.fromBottom) 0 else (place.y * scaleY).toInt()
                        bottomMargin = if (place.fromBottom) (place.y * scaleY).toInt() else 0
                    }
                    panel.visibility = View.VISIBLE
                }
            }
        }
    }

    fun close() {
        handler.post {
            hides.forEach(handler::removeCallbacks)
            prefs.unregisterOnSharedPreferenceChangeListener(changed)
            manager.removeView(root)
        }
    }
}
