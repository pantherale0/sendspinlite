package com.sendspinlite

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun launch_displaysPrimaryScreenSections() {
        composeRule.onNodeWithText("Sendspin Lite Player").assertIsDisplayed()
        composeRule.onNodeWithText("Connection Status").assertIsDisplayed()
    }
}
