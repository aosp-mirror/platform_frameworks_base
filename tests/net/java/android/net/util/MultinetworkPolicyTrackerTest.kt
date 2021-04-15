/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.util

import android.content.Context
import android.content.res.Resources
import android.net.ConnectivityManager.MULTIPATH_PREFERENCE_HANDOVER
import android.net.ConnectivityManager.MULTIPATH_PREFERENCE_PERFORMANCE
import android.net.ConnectivityManager.MULTIPATH_PREFERENCE_RELIABILITY
import android.net.ConnectivityResources
import android.net.ConnectivitySettingsManager.NETWORK_AVOID_BAD_WIFI
import android.net.ConnectivitySettingsManager.NETWORK_METERED_MULTIPATH_PREFERENCE
import android.net.util.MultinetworkPolicyTracker.ActiveDataSubscriptionIdListener
import android.provider.Settings
import android.telephony.SubscriptionInfo
import android.telephony.SubscriptionManager
import android.telephony.TelephonyManager
import android.test.mock.MockContentResolver
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.connectivity.resources.R
import com.android.internal.util.test.FakeSettingsProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.argThat
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

/**
 * Tests for [MultinetworkPolicyTracker].
 *
 * Build, install and run with:
 * atest android.net.util.MultinetworkPolicyTrackerTest
 */
@RunWith(AndroidJUnit4::class)
@SmallTest
class MultinetworkPolicyTrackerTest {
    private val resources = mock(Resources::class.java).also {
        doReturn(R.integer.config_networkAvoidBadWifi).`when`(it).getIdentifier(
                eq("config_networkAvoidBadWifi"), eq("integer"), any())
        doReturn(0).`when`(it).getInteger(R.integer.config_networkAvoidBadWifi)
    }
    private val telephonyManager = mock(TelephonyManager::class.java)
    private val subscriptionManager = mock(SubscriptionManager::class.java).also {
        doReturn(null).`when`(it).getActiveSubscriptionInfo(anyInt())
    }
    private val resolver = MockContentResolver().apply {
        addProvider(Settings.AUTHORITY, FakeSettingsProvider()) }
    private val context = mock(Context::class.java).also {
        doReturn(Context.TELEPHONY_SERVICE).`when`(it)
                .getSystemServiceName(TelephonyManager::class.java)
        doReturn(telephonyManager).`when`(it).getSystemService(Context.TELEPHONY_SERVICE)
        doReturn(subscriptionManager).`when`(it)
                .getSystemService(Context.TELEPHONY_SUBSCRIPTION_SERVICE)
        doReturn(resolver).`when`(it).contentResolver
        doReturn(resources).`when`(it).resources
        doReturn(it).`when`(it).createConfigurationContext(any())
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "1")
        ConnectivityResources.setResourcesContextForTest(it)
    }
    private val tracker = MultinetworkPolicyTracker(context, null /* handler */)

    private fun assertMultipathPreference(preference: Int) {
        Settings.Global.putString(resolver, NETWORK_METERED_MULTIPATH_PREFERENCE,
                preference.toString())
        tracker.updateMeteredMultipathPreference()
        assertEquals(preference, tracker.meteredMultipathPreference)
    }

    @After
    fun tearDown() {
        ConnectivityResources.setResourcesContextForTest(null)
    }

    @Test
    fun testUpdateMeteredMultipathPreference() {
        assertMultipathPreference(MULTIPATH_PREFERENCE_HANDOVER)
        assertMultipathPreference(MULTIPATH_PREFERENCE_RELIABILITY)
        assertMultipathPreference(MULTIPATH_PREFERENCE_PERFORMANCE)
    }

    @Test
    fun testUpdateAvoidBadWifi() {
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "0")
        assertTrue(tracker.updateAvoidBadWifi())
        assertFalse(tracker.avoidBadWifi)

        doReturn(1).`when`(resources).getInteger(R.integer.config_networkAvoidBadWifi)
        assertTrue(tracker.updateAvoidBadWifi())
        assertTrue(tracker.avoidBadWifi)
    }

    @Test
    fun testOnActiveDataSubscriptionIdChanged() {
        val testSubId = 1000
        val subscriptionInfo = SubscriptionInfo(testSubId, ""/* iccId */, 1/* iccId */,
                "TMO"/* displayName */, "TMO"/* carrierName */, 1/* nameSource */, 1/* iconTint */,
                "123"/* number */, 1/* roaming */, null/* icon */, "310"/* mcc */, "210"/* mnc */,
                ""/* countryIso */, false/* isEmbedded */, null/* nativeAccessRules */,
                "1"/* cardString */)
        doReturn(subscriptionInfo).`when`(subscriptionManager).getActiveSubscriptionInfo(testSubId)

        // Modify avoidBadWifi and meteredMultipathPreference settings value and local variables in
        // MultinetworkPolicyTracker should be also updated after subId changed.
        Settings.Global.putString(resolver, NETWORK_AVOID_BAD_WIFI, "0")
        Settings.Global.putString(resolver, NETWORK_METERED_MULTIPATH_PREFERENCE,
                MULTIPATH_PREFERENCE_PERFORMANCE.toString())

        val listenerCaptor = ArgumentCaptor.forClass(
                ActiveDataSubscriptionIdListener::class.java)
        verify(telephonyManager, times(1))
                .registerTelephonyCallback(any(), listenerCaptor.capture())
        val listener = listenerCaptor.value
        listener.onActiveDataSubscriptionIdChanged(testSubId)

        // Check it get resource value with test sub id.
        verify(subscriptionManager, times(1)).getActiveSubscriptionInfo(testSubId)
        verify(context).createConfigurationContext(argThat { it.mcc == 310 && it.mnc == 210 })

        // Check if avoidBadWifi and meteredMultipathPreference values have been updated.
        assertFalse(tracker.avoidBadWifi)
        assertEquals(MULTIPATH_PREFERENCE_PERFORMANCE, tracker.meteredMultipathPreference)
    }
}
