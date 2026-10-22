package com.soum91.reynardtv

import android.app.Application
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

class ReynardApp : Application() {
    /** GeckoView allows exactly one runtime per process. */
    val runtime: GeckoRuntime by lazy {
        val settings = GeckoRuntimeSettings.Builder()
            .aboutConfigEnabled(true)
            .consoleOutput(false)
            .build()
        GeckoRuntime.create(this, settings)
    }
}
