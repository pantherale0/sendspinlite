package com.sendspinlite

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import com.sendspinlite.update.UpdateReceiver
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

@RunWith(RobolectricTestRunner::class)
class UpdateReceiverTest {
    @Test
    fun packageReplaced_relaunchesLauncherInFreshTask() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = UpdateReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_MY_PACKAGE_REPLACED))

        val startedIntent = shadowOf(context).nextStartedActivity
        assertThat(startedIntent).isNotNull()
        assertThat(startedIntent.action).isEqualTo(Intent.ACTION_MAIN)
        assertThat(startedIntent.categories).contains(Intent.CATEGORY_LAUNCHER)
        assertThat(startedIntent.flags and Intent.FLAG_ACTIVITY_NEW_TASK).isNotEqualTo(0)
        assertThat(startedIntent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isNotEqualTo(0)
        assertThat(startedIntent.component?.className).isEqualTo("com.sendspinlite.ui.MainActivity")
    }

    @Test
    fun unrelatedBroadcast_doesNotLaunchActivity() {
        val context = RuntimeEnvironment.getApplication()
        val receiver = UpdateReceiver()

        receiver.onReceive(context, Intent(Intent.ACTION_BOOT_COMPLETED))

        assertThat(shadowOf(context).nextStartedActivity).isNull()
    }
}
