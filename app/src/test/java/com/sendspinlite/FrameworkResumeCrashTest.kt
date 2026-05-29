package com.sendspinlite.ui

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FrameworkResumeCrashTest {
    @Test
    fun isAndroidTopOfTaskResumeFailure_detectsFrameworkResumeCrash() {
        val throwable =
            IllegalArgumentException().apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("android.os.Parcel", "readException", "Parcel.java", 1959),
                        StackTraceElement("android.app.IActivityManager\$Stub\$Proxy", "isTopOfTask", "IActivityManager.java", 8367),
                        StackTraceElement("android.app.Activity", "isTopOfTask", "Activity.java", 6301),
                        StackTraceElement("android.app.Activity", "onResume", "Activity.java", 1314),
                    )
            }

        assertThat(throwable.isAndroidTopOfTaskResumeFailure()).isTrue()
    }

    @Test
    fun isAndroidTopOfTaskResumeFailure_rejectsOtherIllegalArgumentException() {
        val throwable =
            IllegalArgumentException().apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("com.sendspinlite.ui.MainActivity", "onResume", "MainActivity.kt", 75),
                    )
            }

        assertThat(throwable.isAndroidTopOfTaskResumeFailure()).isFalse()
    }

    @Test
    fun isAndroidTopOfTaskResumeFailure_rejectsNonIllegalArgumentException() {
        val throwable =
            RuntimeException().apply {
                stackTrace =
                    arrayOf(
                        StackTraceElement("android.app.Activity", "isTopOfTask", "Activity.java", 6301),
                        StackTraceElement("android.app.Activity", "onResume", "Activity.java", 1314),
                    )
            }

        assertThat(throwable.isAndroidTopOfTaskResumeFailure()).isFalse()
    }
}
