package com.example.ui

object Toast {
    const val LENGTH_SHORT = 0
    const val LENGTH_LONG = 1

    fun makeText(context: Any?, text: CharSequence?, duration: Int): Toast {
        return this
    }

    fun show() {
        // Do nothing to suppress the toast
    }
}
