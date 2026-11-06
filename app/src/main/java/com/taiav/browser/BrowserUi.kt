package com.taiav.browser

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView

object Palette {
    val background = Color.rgb(16, 17, 20)
    val surface = Color.rgb(25, 27, 32)
    val elevated = Color.rgb(34, 37, 44)
    val accent = Color.rgb(255, 117, 93)
    val text = Color.rgb(243, 243, 245)
    val muted = Color.rgb(164, 168, 179)
    val border = Color.rgb(47, 50, 59)
    val green = Color.rgb(119, 217, 162)
}

fun Context.dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()

fun shape(color: Int, radius: Float = 16f, border: Int? = null): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
    border?.let { setStroke(1, it) }
}

fun Context.label(value: String, size: Float = 14f, color: Int = Palette.text, bold: Boolean = false): TextView =
    TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        if (bold) typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        includeFontPadding = false
    }

fun Context.iconButton(drawable: Int, description: String, action: () -> Unit): ImageButton = ImageButton(this).apply {
    setImageResource(drawable)
    imageTintList = ColorStateList.valueOf(Palette.muted)
    contentDescription = description
    setPadding(dp(12), dp(12), dp(12), dp(12))
    background = RippleDrawable(ColorStateList.valueOf(0x22FFFFFF), shape(Color.TRANSPARENT, dp(16).toFloat()), null)
    layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
    setOnClickListener { action() }
}

fun Context.actionButton(title: String, primary: Boolean = true, action: () -> Unit): TextView = label(title, 15f,
    if (primary) Palette.background else Palette.text, true).apply {
    gravity = Gravity.CENTER
    minHeight = dp(52)
    setPadding(dp(20), dp(14), dp(20), dp(14))
    background = RippleDrawable(ColorStateList.valueOf(0x33FFFFFF),
        shape(if (primary) Palette.accent else Palette.elevated, dp(14).toFloat()), null)
    isClickable = true
    isFocusable = true
    setOnClickListener { action() }
}

fun Context.column(padding: Int = 0): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
}

fun TextView.singleLine() {
    maxLines = 1
    ellipsize = TextUtils.TruncateAt.END
}

fun LinearLayout.space(height: Int) { addView(View(context), LinearLayout.LayoutParams(1, context.dp(height))) }
