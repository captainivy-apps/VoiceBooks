package com.dafei.voicebook.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.widget.Toast

object AppToast {
    private var currentToast: Toast? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    fun cancel() {
        mainHandler.post {
            currentToast?.cancel()
            currentToast = null
        }
    }

    fun show(context: Context, message: CharSequence, duration: Int = Toast.LENGTH_SHORT) {
        val appContext = context.applicationContext
        mainHandler.post {
            currentToast?.cancel()
            currentToast = Toast.makeText(appContext, message, duration).also { it.show() }
        }
    }
}
