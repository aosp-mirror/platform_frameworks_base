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
import android.net.ConnectivityManager.TETHERING_BLUETOOTH
import android.net.ConnectivityManager.TETHERING_USB
import android.net.ConnectivityManager.TETHERING_WIFI
import android.net.Network
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.UserHandle
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import android.telephony.TelephonyManager
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.internal.util.test.BroadcastInterceptingContext
import com.android.networkstack.tethering.TetheringNotificationUpdater.DOWNSTREAM_NONE
import com.android.networkstack.tethering.TetheringNotificationUpdater.ENABLE_NOTIFICATION_ID
import com.android.networkstack.tethering.TetheringNotificationUpdater.EVENT_SHOW_NO_UPSTREAM
import com.android.networkstack.tethering.TetheringNotificationUpdater.NO_UPSTREAM_NOTIFICATION_ID
import com.android.networkstack.tethering.TetheringNotificationUpdater.RESTRICTED_NOTIFICATION_ID
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
const val WIFI_ICON_ID = 1
const val USB_ICON_ID = 2
const val BT_ICON_ID = 3
const val GENERAL_ICON_ID = 4
const val WIFI_MASK = 1 shl TETHERING_WIFI
const val USB_MASK = 1 shl TETHERING_USB
const val BT_MASK = 1 shl TETHERING_BLUETOOTH
const val TITLE = "Tethering active"
const val MESSAGE = "Tap here to set up."
const val TEST_TITLE = "Hotspot active"
const val TEST_MESSAGE = "Tap to set up hotspot."
const val TEST_NO_UPSTREAM_TITLE = "Hotspot has no internet access"
const val TEST_NO_UPSTREAM_MESSAGE = "Device cannot connect to internet."
const val TEST_NO_UPSTREAM_BUTTON = "Turn off hotspot"

@RunWith(AndroidJUnit4::class)
@SmallTest
class TetheringNotificationUpdaterTest {
    // lateinit used here for mocks as they need to be reinitialized between each test and the test
    // should crash if they are used before being initialized.
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var telephonyManager: TelephonyManager
    @Mock private lateinit var defaultResources: Resources
    @Mock private lateinit var testResources: Resources

    // lateinit for these classes under test, as they should be reset to a different instance for
    // every test but should always be initialized before use (or the test should crash).
    private lateinit var context: TestContext
    private lateinit var notificationUpdater: TetheringNotificationUpdater
    private lateinit var fakeTetheringThread: HandlerThread

    private val ENABLE_ICON_CONFIGS = arrayOf(
            "USB;android.test:drawable/usb", "BT;android.test:drawable/bluetooth",
            "WIFI|BT;android.test:drawable/general", "WIFI|USB;android.test:drawable/general",
            "USB|BT;android.test:drawable/general", "WIFI|USB|BT;android.test:drawable/general")

    private inner class TestContext(c: Context) : BroadcastInterceptingContext(c) {
        override fun createContextAsUser(user: UserHandle, flags: Int) =
                if (user == UserHandle.ALL) mockContext else this
        override fun getSystemService(name: String) =
                if (name == Context.TELEPHONY_SERVICE) telephonyManager
                else super.getSystemService(name)
    }

    private inner class WrappedNotificationUpdater(c: Context, looper: Looper)
        : TetheringNotificationUpdater(c, looper) {
        override fun getResourcesForSubId(context: Context, subId: Int) =
                when (subId) {
                    TEST_SUBID -> testResources
                    INVALID_SUBSCRIPTION_ID -> defaultResources
                    else -> super.getResourcesForSubId(context, subId)
                }
    }

    private fun setupResources() {
        doReturn(ENABLE_ICON_CONFIGS).`when`(defaultResources)
                .getStringArray(R.array.tethering_notification_icons)
        doReturn(arrayOf("WIFI;android.test:drawable/wifi")).`when`(testResources)
                .getStringArray(R.array.tethering_notification_icons)
        doReturn(5).`when`(testResources)
                .getInteger(R.integer.delay_to_show_no_upstream_after_no_backhaul)
        doReturn(TITLE).`when`(defaultResources).getString(R.string.tethering_notification_title)
        doReturn(MESSAGE).`when`(defaultResources)
                .getString(R.string.tethering_notification_message)
        doReturn(TEST_TITLE).`when`(testResources).getString(R.string.tethering_notification_title)
        doReturn(TEST_MESSAGE).`when`(testResources)
                .getString(R.string.tethering_notification_message)
        doReturn(TEST_NO_UPSTREAM_TITLE).`when`(testResources)
                .getString(R.string.no_upstream_notification_title)
        doReturn(TEST_NO_UPSTREAM_MESSAGE).`when`(testResources)
                .getString(R.string.no_upstream_notification_message)
        doReturn(TEST_NO_UPSTREAM_BUTTON).`when`(testResources)
                .getString(R.string.no_upstream_notification_disable_button)
        doReturn(USB_ICON_ID).`when`(defaultResources)
                .getIdentifier(eq("android.test:drawable/usb"), any(), any())
        doReturn(BT_ICON_ID).`when`(defaultResources)
                .getIdentifier(eq("android.test:drawable/bluetooth"), any(), any())
        doReturn(GENERAL_ICON_ID).`when`(defaultResources)
                .getIdentifier(eq("android.test:drawable/general"), any(), any())
        doReturn(WIFI_ICON_ID).`when`(testResources)
                .getIdentifier(eq("android.test:drawable/wifi"), any(), any())
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
    }

    private fun verifyNotificationCancelled(id: Int) =
        verify(notificationManager, times(1)).cancel(any(), eq(id))

    private val tetheringActiveNotifications =
            listOf(NO_UPSTREAM_NOTIFICATION_ID, ENABLE_NOTIFICATION_ID)

    private fun verifyCancelAllTetheringActiveNotifications() {
        tetheringActiveNotifications.forEach {
            verifyNotificationCancelled(it)
        }
        reset(notificationManager)
    }

    private fun verifyOnlyTetheringActiveNotification(
        notifyId: Int,
        iconId: Int,
        title: String,
        text: String
    ) {
        tetheringActiveNotifications.forEach {
            when (it) {
                notifyId -> verifyNotification(iconId, title, text, notifyId)
                else -> verifyNotificationCancelled(it)
            }
        }
        reset(notificationManager)
    }

    @Test
    fun testNotificationWithDownstreamChanged() {
        // Wifi downstream. No notification.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyCancelAllTetheringActiveNotifications()

        // Same downstream changed. Nothing happened.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyZeroInteractions(notificationManager)

        // Wifi and usb downstreams. Show enable notification
        notificationUpdater.onDownstreamChanged(WIFI_MASK or USB_MASK)
        verifyOnlyTetheringActiveNotification(
                ENABLE_NOTIFICATION_ID, GENERAL_ICON_ID, TITLE, MESSAGE)

        // Usb downstream. Still show enable notification.
        notificationUpdater.onDownstreamChanged(USB_MASK)
        verifyOnlyTetheringActiveNotification(ENABLE_NOTIFICATION_ID, USB_ICON_ID, TITLE, MESSAGE)

        // No downstream. No notification.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyCancelAllTetheringActiveNotifications()
    }

    @Test
    fun testNotificationWithActiveDataSubscriptionIdChanged() {
        // Usb downstream. Showed enable notification with default resource.
        notificationUpdater.onDownstreamChanged(USB_MASK)
        verifyOnlyTetheringActiveNotification(ENABLE_NOTIFICATION_ID, USB_ICON_ID, TITLE, MESSAGE)

        // Same subId changed. Nothing happened.
        notificationUpdater.onActiveDataSubscriptionIdChanged(INVALID_SUBSCRIPTION_ID)
        verifyZeroInteractions(notificationManager)

        // Set test sub id. Clear notification with test resource.
        notificationUpdater.onActiveDataSubscriptionIdChanged(TEST_SUBID)
        verifyCancelAllTetheringActiveNotifications()

        // Wifi downstream. Show enable notification with test resource.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyOnlyTetheringActiveNotification(
                ENABLE_NOTIFICATION_ID, WIFI_ICON_ID, TEST_TITLE, TEST_MESSAGE)

        // No downstream. No notification.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyCancelAllTetheringActiveNotifications()
    }

    private fun assertIconNumbers(number: Int, configs: Array<String?>) {
        doReturn(configs).`when`(defaultResources)
                .getStringArray(R.array.tethering_notification_icons)
        assertEquals(number, notificationUpdater.getIcons(
                R.array.tethering_notification_icons, defaultResources).size())
    }

    @Test
    fun testGetIcons() {
        assertIconNumbers(0, arrayOfNulls<String>(0))
        assertIconNumbers(0, arrayOf(null, ""))
        assertIconNumbers(3, arrayOf(
                // These configurations are invalid with wrong strings or symbols.
                ";", ",", "|", "|,;", "WIFI", "1;2", " U SB; ", "bt;", "WIFI;USB;BT", "WIFI|USB|BT",
                "WIFI,BT,USB", " WIFI| |  | USB, test:drawable/test",
                // This configuration is valid with two downstream types (USB, BT).
                "USB|,,,,,|BT;drawable/test ",
                // This configuration is valid with one downstream types (WIFI).
                "     WIFI     ; android.test:drawable/xxx "))
    }

    @Test
    fun testGetDownstreamTypesMask() {
        assertEquals(DOWNSTREAM_NONE, notificationUpdater.getDownstreamTypesMask(""))
        assertEquals(DOWNSTREAM_NONE, notificationUpdater.getDownstreamTypesMask("1"))
        assertEquals(DOWNSTREAM_NONE, notificationUpdater.getDownstreamTypesMask("WIFI_P2P"))
        assertEquals(DOWNSTREAM_NONE, notificationUpdater.getDownstreamTypesMask("usb"))
        assertEquals(WIFI_MASK, notificationUpdater.getDownstreamTypesMask(" WIFI "))
        assertEquals(USB_MASK, notificationUpdater.getDownstreamTypesMask("USB | B T"))
        assertEquals(BT_MASK, notificationUpdater.getDownstreamTypesMask(" WIFI: | BT"))
        assertEquals(WIFI_MASK or USB_MASK,
                notificationUpdater.getDownstreamTypesMask("1|2|USB|WIFI|BLUETOOTH||"))
    }

    @Test
    fun testSetupRestrictedNotification() {
        val title = context.resources.getString(R.string.disable_tether_notification_title)
        val message = context.resources.getString(R.string.disable_tether_notification_message)
        val disallowTitle = "Tether function is disallowed"
        val disallowMessage = "Please contact your admin"
        doReturn(title).`when`(defaultResources)
                .getString(R.string.disable_tether_notification_title)
        doReturn(message).`when`(defaultResources)
                .getString(R.string.disable_tether_notification_message)
        doReturn(disallowTitle).`when`(testResources)
                .getString(R.string.disable_tether_notification_title)
        doReturn(disallowMessage).`when`(testResources)
                .getString(R.string.disable_tether_notification_message)

        // User restrictions on. Show restricted notification.
        notificationUpdater.notifyTetheringDisabledByRestriction()
        verifyNotification(R.drawable.stat_sys_tether_general, title, message,
                RESTRICTED_NOTIFICATION_ID)
        reset(notificationManager)

        // User restrictions off. Clear notification.
        notificationUpdater.tetheringRestrictionLifted()
        verifyNotificationCancelled(RESTRICTED_NOTIFICATION_ID)
        reset(notificationManager)

        // Set test sub id. No notification.
        notificationUpdater.onActiveDataSubscriptionIdChanged(TEST_SUBID)
        verifyCancelAllTetheringActiveNotifications()

        // User restrictions on again. Show restricted notification with test resource.
        notificationUpdater.notifyTetheringDisabledByRestriction()
        verifyNotification(R.drawable.stat_sys_tether_general, disallowTitle, disallowMessage,
                RESTRICTED_NOTIFICATION_ID)
        reset(notificationManager)
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
    fun testNotificationWithUpstreamNetworkChanged() {
        // Set test sub id. No notification.
        notificationUpdater.onActiveDataSubscriptionIdChanged(TEST_SUBID)
        verifyCancelAllTetheringActiveNotifications()

        // Wifi downstream. Show enable notification with test resource.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyOnlyTetheringActiveNotification(
                ENABLE_NOTIFICATION_ID, WIFI_ICON_ID, TEST_TITLE, TEST_MESSAGE)

        // There is no upstream. Show no upstream notification.
        notificationUpdater.onUpstreamNetworkChanged(null)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, 500L)
        verifyNotification(R.drawable.stat_sys_tether_general, TEST_NO_UPSTREAM_TITLE,
                TEST_NO_UPSTREAM_MESSAGE, NO_UPSTREAM_NOTIFICATION_ID)
        reset(notificationManager)

        // Same upstream network changed. Nothing happened.
        notificationUpdater.onUpstreamNetworkChanged(null)
        verifyZeroInteractions(notificationManager)

        // Upstream come back. Clear no upstream notification.
        notificationUpdater.onUpstreamNetworkChanged(Network(1000))
        verifyNotificationCancelled(NO_UPSTREAM_NOTIFICATION_ID)
        reset(notificationManager)

        // No upstream again. Show no upstream notification.
        notificationUpdater.onUpstreamNetworkChanged(null)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, 500L)
        verifyNotification(R.drawable.stat_sys_tether_general, TEST_NO_UPSTREAM_TITLE,
                TEST_NO_UPSTREAM_MESSAGE, NO_UPSTREAM_NOTIFICATION_ID)
        reset(notificationManager)

        // No downstream. No notification.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyCancelAllTetheringActiveNotifications()

        // Set R.integer.delay_to_show_no_upstream_after_no_backhaul to 0 and have wifi downstream
        // again. Show enable notification only.
        doReturn(-1).`when`(testResources)
                .getInteger(R.integer.delay_to_show_no_upstream_after_no_backhaul)
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        notificationUpdater.handler.waitForDelayedMessage(EVENT_SHOW_NO_UPSTREAM, 500L)
        verifyOnlyTetheringActiveNotification(
                ENABLE_NOTIFICATION_ID, WIFI_ICON_ID, TEST_TITLE, TEST_MESSAGE)
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
}
