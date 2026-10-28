package com.youraicqa.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.util.AttributeSet
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.widget.EditText
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat

class RichContentEditText @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = android.R.attr.editTextStyle
) : EditText(context, attrs, defStyleAttr) {

    var onRichContent: ((Uri, String) -> Boolean)? = null

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        val base = super.onCreateInputConnection(outAttrs) ?: return null
        if (onRichContent == null) return base
        EditorInfoCompat.setContentMimeTypes(
            outAttrs,
            arrayOf("image/gif", "image/png", "image/jpeg", "image/webp", "image/*")
        )
        return InputConnectionCompat.createWrapper(base, outAttrs) { info, flags, _ ->
            val handler = onRichContent
            if (handler == null) {
                false
            } else {
                var granted = true
                if (Build.VERSION.SDK_INT >= 25 &&
                    (flags and InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION) != 0
                ) {
                    granted = try { info.requestPermission(); true } catch (_: Exception) { false }
                }
                if (!granted) {
                    false
                } else {
                    val mime = info.description.getMimeType(0) ?: "image/*"
                    val ok = try { handler(info.contentUri, mime) } catch (_: Exception) { false }
                    try { info.releasePermission() } catch (_: Exception) { }
                    ok
                }
            }
        }
    }
}
