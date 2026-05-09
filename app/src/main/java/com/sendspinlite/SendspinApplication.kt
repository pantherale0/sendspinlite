package com.sendspinlite

import android.app.Application

/**
 * Custom Application class.
 * Responsible for early initialisation of cross-cutting concerns such as crash reporting.
 */
class SendspinApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Initialise Sentry crash/ANR reporting only if the user has previously opted in.
        CrashReportingManager.initIfEnabled(this)
    }
}
