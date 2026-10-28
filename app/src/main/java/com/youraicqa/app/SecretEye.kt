package com.youraicqa.app

import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import androidx.core.content.ContextCompat

fun EditText.attachEyeToggle() {
    val density = resources.displayMetrics.density
    val iconSize = (24 * density).toInt()
    var visible = false

    fun applyIcon() {
        val showIcon = !text.isNullOrEmpty()
        val icon = if (showIcon) {
            ContextCompat.getDrawable(context, if (visible) R.drawable.ic_eye else R.drawable.ic_eye_off)?.mutate()?.apply {
                setBounds(0, 0, iconSize, iconSize)
                setTint(Th.fg(0xFFCCCCCC.toInt()))
            }
        } else null
        compoundDrawablePadding = (8 * density).toInt()
        setCompoundDrawablesRelative(null, null, icon, null)
    }

    addTextChangedListener(object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        override fun afterTextChanged(s: Editable?) { applyIcon() }
    })

    setOnTouchListener { v, ev ->
        if (ev.action == MotionEvent.ACTION_UP) {
            val icon = compoundDrawablesRelative[2]
            if (icon != null) {
                val hitStart = v.width - v.paddingRight - icon.bounds.width() - (12 * density)
                if (ev.x >= hitStart) {
                    visible = !visible
                    inputType = InputType.TYPE_CLASS_TEXT or (
                        if (visible) InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                        else InputType.TYPE_TEXT_VARIATION_PASSWORD)
                    setSelection(text?.length ?: 0)
                    applyIcon()
                    v.performClick()
                    return@setOnTouchListener true
                }
            }
        }
        false
    }
    applyIcon()
}
