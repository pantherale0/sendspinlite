package com.sendspinlite

import android.app.Application
import com.sendspinlite.diagnostics.CrashReportingManager
import java.util.concurrent.Executors

/**
 * Custom Application class.
 * Responsible for early initialisation of cross-cutting concerns such as crash reporting.
 */
class SendspinApplication : Application() {
    private val initExecutor = Executors.newSingleThreadExecutor()

    override fun onCreate() {
        super.onCreate()
        // Defer Sentry init off the main thread so kiosk cold-starts stay responsive under memory pressure.
        initExecutor.execute {
            CrashReportingManager.initIfEnabled(this@SendspinApplication)
        }
    }
}
