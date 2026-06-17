package com.example

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AppCrashTest {
    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun testAppLaunch() {
        ShadowLog.stream = System.out
        try {
            val scenario = activityRule.scenario
            scenario.onActivity { activity ->
                println("Activity launched successfully!")
            }
        } catch (e: Exception) {
            e.printStackTrace()
            throw e
        }
    }
}
