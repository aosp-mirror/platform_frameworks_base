/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.flicker.pip.tv

import android.app.Notification
import android.service.notification.StatusBarNotification
import android.view.Surface
import androidx.test.filters.RequiresDevice
import com.android.wm.shell.flicker.NotificationListener.Companion.startNotificationListener
import com.android.wm.shell.flicker.NotificationListener.Companion.stopNotificationListener
import com.android.wm.shell.flicker.NotificationListener.Companion.waitForNotificationToAppear
import com.android.wm.shell.flicker.NotificationListener.Companion.waitForNotificationToDisappear
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.junit.runners.Parameterized

/**
 * Test Pip Notifications on TV.
 * To run this test: `atest WMShellFlickerTests:TvPipNotificationTests`
 */
@RequiresDevice
@RunWith(Parameterized::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class TvPipNotificationTests(rotationName: String, rotation: Int)
    : TvPipTestBase(rotationName, rotation) {

    @Before
    override fun setUp() {
        super.setUp()
        val started = startNotificationListener()
        if (!started) {
            error("NotificationListener hasn't started")
        }
    }

    @After
    override fun tearDown() {
        stopNotificationListener()
        testApp.forceStop()
        super.tearDown()
    }

    @Test
    fun pipNotification_postedAndDismissed() {
        testApp.launchViaIntent()
        testApp.clickEnterPipButton()

        assertTrue("Pip notification should have been posted",
                waitForNotificationToAppear { it.isPipNotificationWithTitle(testApp.label) })

        testApp.closePipWindow()

        assertTrue("Pip notification should have been dismissed",
                waitForNotificationToDisappear { it.isPipNotificationWithTitle(testApp.label) })
    }

    companion object {
        @Parameterized.Parameters(name = "{0}")
        @JvmStatic
        fun getParams(): Collection<Array<Any>> {
            val supportedRotations = intArrayOf(Surface.ROTATION_0)
            return supportedRotations.map { arrayOf(Surface.rotationToString(it), it) }
        }
    }
}

private const val PIP_NOTIFICATION_TAG = "PipNotification"

private val StatusBarNotification.title: String
    get() = notification?.extras?.getString(Notification.EXTRA_TITLE) ?: ""

private fun StatusBarNotification.isPipNotificationWithTitle(expectedTitle: String): Boolean =
    tag == PIP_NOTIFICATION_TAG && title == expectedTitle