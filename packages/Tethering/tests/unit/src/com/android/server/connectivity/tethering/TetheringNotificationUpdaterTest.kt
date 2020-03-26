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

package com.android.server.connectivity.tethering

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager.TETHERING_BLUETOOTH
import android.net.ConnectivityManager.TETHERING_USB
import android.net.ConnectivityManager.TETHERING_WIFI
import android.os.UserHandle
import android.telephony.SubscriptionManager.INVALID_SUBSCRIPTION_ID
import androidx.test.InstrumentationRegistry
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.internal.util.test.BroadcastInterceptingContext
import com.android.networkstack.tethering.R
import com.android.server.connectivity.tethering.TetheringNotificationUpdater.DOWNSTREAM_NONE
import org.junit.Assert.assertEquals
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
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

const val TEST_SUBID = 1
const val WIFI_ICON_ID = 1
const val USB_ICON_ID = 2
const val BT_ICON_ID = 3
const val GENERAL_ICON_ID = 4
const val WIFI_MASK = 1 shl TETHERING_WIFI
const val USB_MASK = 1 shl TETHERING_USB
const val BT_MASK = 1 shl TETHERING_BLUETOOTH
const val TITTLE = "Tethering active"
const val MESSAGE = "Tap here to set up."
const val TEST_TITTLE = "Hotspot active"
const val TEST_MESSAGE = "Tap to set up hotspot."

@RunWith(AndroidJUnit4::class)
@SmallTest
class TetheringNotificationUpdaterTest {
    // lateinit used here for mocks as they need to be reinitialized between each test and the test
    // should crash if they are used before being initialized.
    @Mock private lateinit var mockContext: Context
    @Mock private lateinit var notificationManager: NotificationManager
    @Mock private lateinit var defaultResources: Resources
    @Mock private lateinit var testResources: Resources

    // lateinit for this class under test, as it should be reset to a different instance for every
    // tests but should always be initialized before use (or the test should crash).
    private lateinit var notificationUpdater: TetheringNotificationUpdater

    private val ENABLE_ICON_CONFIGS = arrayOf(
            "USB;android.test:drawable/usb", "BT;android.test:drawable/bluetooth",
            "WIFI|BT;android.test:drawable/general", "WIFI|USB;android.test:drawable/general",
            "USB|BT;android.test:drawable/general", "WIFI|USB|BT;android.test:drawable/general")

    private inner class TestContext(c: Context) : BroadcastInterceptingContext(c) {
        override fun createContextAsUser(user: UserHandle, flags: Int) =
                if (user == UserHandle.ALL) mockContext else this
    }

    private inner class WrappedNotificationUpdater(c: Context) : TetheringNotificationUpdater(c) {
        override fun getResourcesForSubId(context: Context, subId: Int) =
                if (subId == TEST_SUBID) testResources else defaultResources
    }

    private fun setupResources() {
        doReturn(ENABLE_ICON_CONFIGS).`when`(defaultResources)
                .getStringArray(R.array.tethering_notification_icons)
        doReturn(arrayOf("WIFI;android.test:drawable/wifi")).`when`(testResources)
                .getStringArray(R.array.tethering_notification_icons)
        doReturn(TITTLE).`when`(defaultResources).getString(R.string.tethering_notification_title)
        doReturn(MESSAGE).`when`(defaultResources)
                .getString(R.string.tethering_notification_message)
        doReturn(TEST_TITTLE).`when`(testResources).getString(R.string.tethering_notification_title)
        doReturn(TEST_MESSAGE).`when`(testResources)
                .getString(R.string.tethering_notification_message)
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
        val context = TestContext(InstrumentationRegistry.getContext())
        doReturn(notificationManager).`when`(mockContext)
                .getSystemService(Context.NOTIFICATION_SERVICE)
        notificationUpdater = WrappedNotificationUpdater(context)
        setupResources()
    }

    private fun Notification.title() = this.extras.getString(Notification.EXTRA_TITLE)
    private fun Notification.text() = this.extras.getString(Notification.EXTRA_TEXT)

    private fun verifyNotification(iconId: Int = 0, title: String = "", text: String = "") {
        verify(notificationManager, never()).cancel(any(), anyInt())

        val notificationCaptor = ArgumentCaptor.forClass(Notification::class.java)
        verify(notificationManager, times(1)).notify(any(), anyInt(), notificationCaptor.capture())

        val notification = notificationCaptor.getValue()
        assertEquals(iconId, notification.smallIcon.resId)
        assertEquals(title, notification.title())
        assertEquals(text, notification.text())

        reset(notificationManager)
    }

    private fun verifyNoNotification() {
        verify(notificationManager, times(1)).cancel(any(), anyInt())
        verify(notificationManager, never()).notify(any(), anyInt(), any())

        reset(notificationManager)
    }

    @Test
    fun testNotificationWithDownstreamChanged() {
        // Wifi downstream. No notification.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyNoNotification()

        // Same downstream changed. Nothing happened.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyZeroInteractions(notificationManager)

        // Wifi and usb downstreams. Show enable notification
        notificationUpdater.onDownstreamChanged(WIFI_MASK or USB_MASK)
        verifyNotification(GENERAL_ICON_ID, TITTLE, MESSAGE)

        // Usb downstream. Still show enable notification.
        notificationUpdater.onDownstreamChanged(USB_MASK)
        verifyNotification(USB_ICON_ID, TITTLE, MESSAGE)

        // No downstream. No notification.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyNoNotification()
    }

    @Test
    fun testNotificationWithActiveDataSubscriptionIdChanged() {
        // Usb downstream. Showed enable notification with default resource.
        notificationUpdater.onDownstreamChanged(USB_MASK)
        verifyNotification(USB_ICON_ID, TITTLE, MESSAGE)

        // Same subId changed. Nothing happened.
        notificationUpdater.onActiveDataSubscriptionIdChanged(INVALID_SUBSCRIPTION_ID)
        verifyZeroInteractions(notificationManager)

        // Set test sub id. Clear notification with test resource.
        notificationUpdater.onActiveDataSubscriptionIdChanged(TEST_SUBID)
        verifyNoNotification()

        // Wifi downstream. Show enable notification with test resource.
        notificationUpdater.onDownstreamChanged(WIFI_MASK)
        verifyNotification(WIFI_ICON_ID, TEST_TITTLE, TEST_MESSAGE)

        // No downstream. No notification.
        notificationUpdater.onDownstreamChanged(DOWNSTREAM_NONE)
        verifyNoNotification()
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
}