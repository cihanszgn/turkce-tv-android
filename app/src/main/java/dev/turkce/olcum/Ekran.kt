package dev.turkce.olcum

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.widget.Button
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter

/** The look the app's TV screens share. */
internal val SCREEN_BACKGROUND = Color.rgb(16, 24, 32)
internal val SCREEN_BUTTON = Color.rgb(44, 62, 80)
internal val SCREEN_DIM_TEXT = Color.rgb(159, 176, 192)
private const val QR_CELL_PX = 10

/** A QR code for [text], black on white, [cell] pixels per module. */
internal fun qrBitmap(text: String, cell: Int = QR_CELL_PX): Bitmap {
    val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 2))
    val size = matrix.width * cell
    val pixels = IntArray(size * size) { i ->
        if (matrix.get(i % size / cell, i / size / cell)) Color.BLACK else Color.WHITE
    }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

/** A button that shows clearly when the remote's focus is on it: white with dark text, else dark with white. */
internal fun Context.tvButton(text: String, onClick: () -> Unit) = Button(this).apply {
    this.text = text
    isAllCaps = false
    textSize = 20f
    isFocusable = true
    val lit = intArrayOf(android.R.attr.state_focused)
    val pressed = intArrayOf(android.R.attr.state_pressed)
    background = StateListDrawable().apply {
        addState(lit, rounded(Color.WHITE))
        addState(pressed, rounded(Color.WHITE))
        addState(intArrayOf(), rounded(SCREEN_BUTTON))
    }
    setTextColor(ColorStateList(arrayOf(lit, pressed, intArrayOf()), intArrayOf(SCREEN_BACKGROUND, SCREEN_BACKGROUND, Color.WHITE)))
    setPadding(48, 20, 48, 20)
    setOnClickListener { onClick() }
}

internal fun rounded(color: Int) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = 16f
}
