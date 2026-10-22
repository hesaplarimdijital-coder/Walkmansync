package com.walkmansync.platform

import android.util.Log
import com.walkmansync.core.runtime.Logger

/** [Logger] portunun android.util.Log uygulaması. Etiket 23 karakter sınırına göre kırpılır. */
object AndroidLogger : Logger {
    private fun tag(raw: String): String = "WS/$raw".take(23)

    override fun d(tag: String, message: String) {
        Log.d(tag(tag), message)
    }

    override fun w(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.w(tag(tag), message, error) else Log.w(tag(tag), message)
    }

    override fun e(tag: String, message: String, error: Throwable?) {
        if (error != null) Log.e(tag(tag), message, error) else Log.e(tag(tag), message)
    }
}
