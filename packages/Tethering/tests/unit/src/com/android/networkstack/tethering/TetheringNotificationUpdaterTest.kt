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

package com.android.networkstack.tethering

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager.TETHERING_WIFI
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.os.UserHandle
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.internal.util.test.BroadcastInterceptingContext
import com.android.networkstack.tethering.TetheringNotificationUpdater.DOWNSTREAM_NONE
import com.android.networkstack.tethering.TetheringNotificationUpdater.EVENT_SHOW_NO_UPSTREAM
import com.android.networkstack.tethering.TetheringNotificationUpdater.NO_UPSTREAM_NOTIFICATION_ID
import com.android.networkstack.tethering.TetheringNotificationUpdater.RESTRICTED_NOTIFICATION_ID
import com.android.networkstack.tethering.TetheringNotificationUpdater.ROAMING_NOTIFICATION_ID
import com.android.networkstack.tethering.TetheringNotificationUpdater.VERIZON_CARRIER_ID
import com.android.testutils.waitForIdle
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.MockitoAnnotations

const val TEST_SUBID = 1
const val WIFI_MASK = 1 shl TETHERING_WIFI
const val TEST_DISALLOW_TITLE = "Tether function is disallowed"
const val TEST_DISALLOW_MESSAGE = "Please contact your admin"
const val TEST_NO_UPSTREAM_TITLE = "Hotspot has no internet access"
const val TEST_NO_UPSTREAM_MESSAGE = "Device cannot connect to internet."
const val TEST_NO_UPSTREAM_BUTTON = "Turn off hotspot"
const val TEST_ROAMING_TITLE = "Hotspot is on"
const val TEST_ROAMING_MESSAGE = "Additional charges may apply while roaming."

@RunWith(AndroidJUnit4::class)
@SmallTest
class TetheringNotificationUpdaterTest {
    // lateinit used here for mocks as they need to be reinitialized between each test and the test
    // should crash if they are used before being initialized.
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var testResources: Resources

    // lateinit for these classes under test, as they should be reset to a different instance for
    // every test but should always be initialized before use (or the test should crash).
    private lateinit var context: TestContext
    private lateinit var notificationUpdater: TetheringNotificationUpdater
    private lateinit var fakeTetheringThread: HandlerThread

    private val ROAMING_CAPABILITIES = NetworkCapabilities()
    private val HOME_CAPABILITIES = NetworkCapabilities().addCapability(NET_CAPABILITY_NOT_ROAMING)
    private val NOTIFICATION_ICON_ID = R.drawable.stat_sys_tether_general
    private val TIMEOUT_MS = 500L

    private inner class TestContext(c: Context) : BroadcastInterceptingContext(c) {
        override fun createContextAsUser(user: UserHandle, flags: Int) =
                if (user == UserHandle.ALL) mockContext else this
        override fun getSystemService(name: String) =
                if (name == Context.TELEPHONY_SERVICE) telephonyManager
                else super.getSystemService(name)
    }

    private inner class WrappedNotificationUpdater(c: Context, looper: Looper)
        : TetheringNotificationUpdater(c, looper) {
        override fun getResourcesForSubId(c: Context, subId: Int) =
                if (subId == TEST_SUBID) testResources else super.getResourcesForSubId(c, subId)
    }

    private fun setupResources() {
        doReturn(5).`when`(testResources)
                .getInteger(R.integer.delay_to_show_no_upstream_after_no_backhaul)
        doReturn(true).`when`(testResources)
                .getBoolean(R.bool.config_upstream_roaming_notification)
        doReturn(TEST_DISALLOW_TITLE).`when`(testResources)
                .getString(R.string.disable_tether_notification_title)
        doReturn(TEST_DISALLOW_MESSAGE).`when`(testResources)
                .getString(R.string.disable_tether_notification_message)
        doReturn(TEST_NO_UPSTREAM_TITLE).`when`(testResources)
                .getString(R.string.no_upstream_notification_title)
        doReturn(TEST_NO_UPSTREAM_MESSAGE).`when`(testResources)
                .getString(R.string.no_upstream_notification_message)
        doReturn(TEST_NO_UPSTREAM_BUTTON).`when`(testResources)
                .getString(R.string.no_upstream_notification_disable_button)
        doReturn(TEST_ROAMING_TITLE).`when`(testResources)
                .getString(R.string.upstream_roaming_notification_title)
        doReturn(TEST_ROAMING_MESSAGE).`when`(testResources)
                .getString(R.string.upstream_roaming_notification_message)
    }

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        context = TestContext(InstrumentationRegistry.getInstrumentation().context)
        doReturn(notificationManager).`when`(mockContext)
                .getSystemService(Context.NOTIFICATION_SERVICE)
        fakeTetheringThread = HandlerThread(this::class.simpleName)
        fakeTetheringThread.start()
        notificationUpdater = WrappedNotificationUpdater(context, fakeTetheringThread.looper)
        setupResources()
    }

    @After
    fun tearDown() {
        fakeTetheringThread.quitSafely()
    }

    private fun Notification.title() = this.extras.getString(Notification.EXTRA_TITLE)
    private fun Notification.text() = this.extras.getString(Notification.EXTRA_TEXT)

    private fun verifyNotification(iconId: Int, title: String, text: String, id: Int) {
        verify(notificationManager, never()).cancel(any(), eq(id))

        val notificationCaptor = ArgumentCaptor.forClass(Notification::class.java)
        verify(notificationManager, times(1))
                .notify(any(), eq(id), notificationCaptor.capture())

        val notification = notificationCaptor.getValue()
        assertEquals(iconId, notification.smallIcon.resId)
        assertEquals(title, notification.title())
        assertEquals(text, notification.text())

        reset(notificationManager)
    }

    private fun verifyNotificationCancelled(
        notificationIds: List<Int>,
        resetAfterVerified: Boolean = true
    ) {
        notificationIds.forEach {
            verify(notificationManager, times(1)).cancel(any(), eq(it))
        }
        if (resetAfterVerified) reset(notificationManager)
    }

    @Test
    fun testRestrictedNotification() {
        // Set test sub id.
        notificationUpdater.onActiveDataSubscriptionIdChanged(TEST_SUBID)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // User restrictions on. Show restricted notification.
        notificationUpdater.notifyTetheringDisabledByRestriction()
        verifyNotification(NOTIFICATION_ICON_ID, TEST_DISALLOW_TITLE, TEST_DISALLOW_MESSAGE,
                RESTRICTED_NOTIFICATION_ID)

        // User restrictions off. Clear notification.
        notificationUpdater.tetheringRestrictionLifted()
        verifyNotificationCancelled(listOf(RESTRICTED_NOTIFICATION_ID))

        // No downstream.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyZeroInteractions(notificationManager)

        // User restrictions on again. Show restricted notification.
        notificationUpdater.notifyTetheringDisabledByRestriction()
        verifyNotification(NOTIFICATION_ICON_ID, TEST_DISALLOW_TITLE, TEST_DISALLOW_MESSAGE,
                RESTRICTED_NOTIFICATION_ID)
    }

    val MAX_BACKOFF_MS = 200L
    /**
     * Waits for all messages, including delayed ones, to be processed.
     *
     * This will wait until the handler has no more messages to be processed including
     * delayed ones, or the timeout has expired. It uses an exponential backoff strategy
     * to wait longer and longer to consume less CPU, with the max granularity being
     * MAX_BACKOFF_MS.
     *
     * @return true if all messages have been processed including delayed ones, false if timeout
     *
     * TODO: Move this method to com.android.testutils.HandlerUtils.kt.
     */
    private fun Handler.waitForDelayedMessage(what: Int?, timeoutMs: Long) {
        fun hasMatchingMessages() =
                if (what == null) hasMessagesOrCallbacks() else hasMessages(what)
        val expiry = System.currentTimeMillis() + timeoutMs
        var delay = 5L
        while (System.currentTimeMillis() < expiry && hasMatchingMessages()) {
            // None of Handler, Looper, Message and MessageQueue expose any way to retrieve
            // the time when the next (let alone the last) message will be processed, so
            // short of examining the internals with reflection sleep() is the only solution.
            Thread.sleep(delay)
            delay = (delay * 2)
                    .coerceAtMost(expiry - System.currentTimeMillis())
                    .coerceAtMost(MAX_BACKOFF_MS)
        }

        val timeout = expiry - System.currentTimeMillis()
        if (timeout <= 0) fail("Delayed message did not process yet after ${timeoutMs}ms")
        waitForIdle(timeout)
    }

    @Test
    fun testNoUpstreamNotification() {
        // Set test sub id.
        notificationUpdater.onActiveDataSubscriptionIdChanged(TEST_SUBID)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // Wifi downstream.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // There is no upstream. Show no upstream notification.
        notificationUpdater.onUpstreamCapabilitiesChanged(null)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, TIMEOUT_MS)
        verifyNotification(NOTIFICATION_ICON_ID, TEST_NO_UPSTREAM_TITLE, TEST_NO_UPSTREAM_MESSAGE,
                NO_UPSTREAM_NOTIFICATION_ID)

        // Same capabilities changed. Nothing happened.
        notificationUpdater.onUpstreamCapabilitiesChanged(null)
        verifyZeroInteractions(notificationManager)

        // Upstream come back. Clear no upstream notification.
        notificationUpdater.onUpstreamCapabilitiesChanged(HOME_CAPABILITIES)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID))

        // No upstream again. Show no upstream notification.
        notificationUpdater.onUpstreamCapabilitiesChanged(null)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, TIMEOUT_MS)
        verifyNotification(NOTIFICATION_ICON_ID, TEST_NO_UPSTREAM_TITLE, TEST_NO_UPSTREAM_MESSAGE,
                NO_UPSTREAM_NOTIFICATION_ID)

        // No downstream.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // Wifi downstream and home capabilities.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        notificationUpdater.onUpstreamCapabilitiesChanged(HOME_CAPABILITIES)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // Set R.integer.delay_to_show_no_upstream_after_no_backhaul to -1 and change to no upstream
        // again. Don't put up no upstream notification.
        doReturn(-1).`when`(testResources)
                .getInteger(R.integer.delay_to_show_no_upstream_after_no_backhaul)
        notificationUpdater.onUpstreamCapabilitiesChanged(null)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, TIMEOUT_MS)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID))
    }

    @Test
    fun testGetResourcesForSubId() {
        doReturn(telephonyManager).`when`(telephonyManager).createForSubscriptionId(anyInt())
        doReturn(1234).`when`(telephonyManager).getSimCarrierId()
        doReturn("000000").`when`(telephonyManager).getSimOperator()

        val subId = -2 // Use invalid subId to avoid getting resource from cache or real subId.
        val config = context.resources.configuration
        var res = notificationUpdater.getResourcesForSubId(context, subId)
        assertEquals(config.mcc, res.configuration.mcc)
        assertEquals(config.mnc, res.configuration.mnc)

        doReturn(VERIZON_CARRIER_ID).`when`(telephonyManager).getSimCarrierId()
        res = notificationUpdater.getResourcesForSubId(context, subId)
        assertEquals(config.mcc, res.configuration.mcc)
        assertEquals(config.mnc, res.configuration.mnc)

        doReturn("20404").`when`(telephonyManager).getSimOperator()
        res = notificationUpdater.getResourcesForSubId(context, subId)
        assertEquals(311, res.configuration.mcc)
        assertEquals(480, res.configuration.mnc)
    }

    @Test
    fun testRoamingNotification() {
        // Set test sub id.
        notificationUpdater.onActiveDataSubscriptionIdChanged(TEST_SUBID)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // Wifi downstream.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // Upstream capabilities changed to roaming state. Show roaming notification.
        notificationUpdater.onUpstreamCapabilitiesChanged(ROAMING_CAPABILITIES)
        verifyNotification(NOTIFICATION_ICON_ID, TEST_ROAMING_TITLE, TEST_ROAMING_MESSAGE,
                ROAMING_NOTIFICATION_ID)

        // Same capabilities change. Nothing happened.
        notificationUpdater.onUpstreamCapabilitiesChanged(ROAMING_CAPABILITIES)
        verifyZeroInteractions(notificationManager)

        // Upstream capabilities changed to home state. Clear roaming notification.
        notificationUpdater.onUpstreamCapabilitiesChanged(HOME_CAPABILITIES)
        verifyNotificationCancelled(listOf(ROAMING_NOTIFICATION_ID))

        // Upstream capabilities changed to roaming state again. Show roaming notification.
        notificationUpdater.onUpstreamCapabilitiesChanged(ROAMING_CAPABILITIES)
        verifyNotification(NOTIFICATION_ICON_ID, TEST_ROAMING_TITLE, TEST_ROAMING_MESSAGE,
                ROAMING_NOTIFICATION_ID)

        // No upstream. Clear roaming notification and show no upstream notification.
        notificationUpdater.onUpstreamCapabilitiesChanged(null)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, TIMEOUT_MS)
        verifyNotificationCancelled(listOf(ROAMING_NOTIFICATION_ID), false)
        verifyNotification(NOTIFICATION_ICON_ID, TEST_NO_UPSTREAM_TITLE, TEST_NO_UPSTREAM_MESSAGE,
                NO_UPSTREAM_NOTIFICATION_ID)

        // No downstream.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))

        // Wifi downstream again.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, TIMEOUT_MS)
        verifyNotificationCancelled(listOf(ROAMING_NOTIFICATION_ID), false)
        verifyNotification(NOTIFICATION_ICON_ID, TEST_NO_UPSTREAM_TITLE, TEST_NO_UPSTREAM_MESSAGE,
                NO_UPSTREAM_NOTIFICATION_ID)

        // Set R.bool.config_upstream_roaming_notification to false and change upstream
        // network to roaming state again. No roaming notification.
        doReturn(false).`when`(testResources)
                .getBoolean(R.bool.config_upstream_roaming_notification)
        notificationUpdater.onUpstreamCapabilitiesChanged(ROAMING_CAPABILITIES)
        verifyNotificationCancelled(listOf(NO_UPSTREAM_NOTIFICATION_ID, ROAMING_NOTIFICATION_ID))
    }
}
