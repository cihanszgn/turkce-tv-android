package dev.turkce.olcum

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.BaseAdapter
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import kotlin.concurrent.thread

private const val PAD_X = 72
private const val PAD_Y = 32

/**
 * The full licence texts the app carries, in their own language: the third-party list with its licences, the
 * notices inside Google's libraries and the AGPL-3.0. Up and down scroll; left and right jump to the previous or
 * next licence. A list of short rows keeps the 1.5 MB of text from being laid out at once.
 */
class LisansActivity : Activity() {
    private lateinit var list: ListView
    private lateinit var status: TextView
    private var parts: List<TextPart> = emptyList()

    private val adapter = object : BaseAdapter() {
        override fun getCount() = parts.size
        override fun getItem(position: Int) = parts[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val row = convertView as? TextView ?: TextView(this@LisansActivity).apply {
                typeface = Typeface.MONOSPACE
                textSize = 15f
                setTextColor(Color.WHITE)
                setPadding(16, 0, 16, 0)
            }
            row.text = parts[position].text
            return row
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        status = TextView(this).apply {
            text = "Yükleniyor…"
            textSize = 18f
            setTextColor(SCREEN_DIM_TEXT)
            setPadding(16, 0, 16, 16)
        }
        list = ListView(this).apply {
            adapter = this@LisansActivity.adapter
            divider = null
            selector = ColorDrawable(Color.argb(36, 255, 255, 255))
            isFocusable = true
            setOnKeyListener { _, code, event -> event.action == KeyEvent.ACTION_DOWN && jump(code) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(SCREEN_BACKGROUND)
            setPadding(PAD_X, PAD_Y, PAD_X, PAD_Y)
            addView(TextView(this@LisansActivity).apply {
                text = "Tam lisans metinleri"
                textSize = 26f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                setPadding(16, 0, 16, 4)
            })
            addView(status)
            addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        // Android 15 and later draw apps under the system bars; keep the content clear of them.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            root.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(PAD_X + bars.left, PAD_Y + bars.top, PAD_X + bars.right, PAD_Y + bars.bottom)
                insets
            }
        }
        setContentView(root)
        list.requestFocus()
        thread(isDaemon = true, name = "lisanslar") {
            val loaded = runCatching { About.parts(About.fullTexts(this)) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                parts = loaded.getOrDefault(emptyList())
                adapter.notifyDataSetChanged()
                status.text = if (loaded.isSuccess) "Yukarı/aşağı ok: kaydır · Sağ/sol ok: sonraki/önceki lisans" else "Lisans metinleri okunamadı."
                list.setSelection(0)
            }
        }
    }

    /** Right goes to the next licence, left to the start of this one or the one before. */
    private fun jump(code: Int): Boolean {
        val here = list.selectedItemPosition.coerceAtLeast(0)
        val target = when (code) {
            KeyEvent.KEYCODE_DPAD_RIGHT -> (here + 1 until parts.size).firstOrNull { parts[it].starts }
            KeyEvent.KEYCODE_DPAD_LEFT -> (here - 1 downTo 0).firstOrNull { parts[it].starts }
            else -> return false
        } ?: return true
        list.setSelection(target)
        return true
    }
}
