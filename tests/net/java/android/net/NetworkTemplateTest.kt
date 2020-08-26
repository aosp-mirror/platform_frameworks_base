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

package android.net

import android.content.Context
import android.net.ConnectivityManager.TYPE_MOBILE
import android.net.ConnectivityManager.TYPE_WIFI
import android.net.NetworkIdentity.SUBTYPE_COMBINED
import android.net.NetworkIdentity.buildNetworkIdentity
import android.net.NetworkStats.DEFAULT_NETWORK_ALL
import android.net.NetworkStats.METERED_ALL
import android.net.NetworkStats.ROAMING_ALL
import android.net.NetworkTemplate.MATCH_MOBILE
import android.net.NetworkTemplate.MATCH_WIFI
import android.net.NetworkTemplate.NETWORK_TYPE_5G_NSA
import android.net.NetworkTemplate.NETWORK_TYPE_ALL
import android.net.NetworkTemplate.buildTemplateMobileWithRatType
import android.telephony.TelephonyManager
import com.android.testutils.assertParcelSane
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val TEST_IMSI1 = "imsi1"
private const val TEST_IMSI2 = "imsi2"
private const val TEST_SSID1 = "ssid1"

@RunWith(JUnit4::class)
class NetworkTemplateTest {
    private val mockContext = mock(Context::class.java)

    private fun buildMobileNetworkState(subscriberId: String): NetworkState =
            buildNetworkState(TYPE_MOBILE, subscriberId = subscriberId)
    private fun buildWifiNetworkState(ssid: String): NetworkState =
            buildNetworkState(TYPE_WIFI, ssid = ssid)

    private fun buildNetworkState(
        type: Int,
        subscriberId: String? = null,
        ssid: String? = null
    ): NetworkState {
        val info = mock(NetworkInfo::class.java)
        doReturn(type).`when`(info).type
        doReturn(NetworkInfo.State.CONNECTED).`when`(info).state
        val lp = LinkProperties()
        val caps = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, false)
            setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, true)
        }
        return NetworkState(info, lp, caps, mock(Network::class.java), subscriberId, ssid)
    }

    private fun NetworkTemplate.assertMatches(ident: NetworkIdentity) =
            assertTrue(matches(ident), "$this does not match $ident")

    private fun NetworkTemplate.assertDoesNotMatch(ident: NetworkIdentity) =
            assertFalse(matches(ident), "$this should match $ident")

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
    }

    @Test
    fun testRatTypeGroupMatches() {
        val stateMobile = buildMobileNetworkState(TEST_IMSI1)
        // Build UMTS template that matches mobile identities with RAT in the same
        // group with any IMSI. See {@link NetworkTemplate#getCollapsedRatType}.
        val templateUmts = buildTemplateMobileWithRatType(null, TelephonyManager.NETWORK_TYPE_UMTS)
        // Build normal template that matches mobile identities with any RAT and IMSI.
        val templateAll = buildTemplateMobileWithRatType(null, NETWORK_TYPE_ALL)
        // Build template with UNKNOWN RAT that matches mobile identities with RAT that
        // cannot be determined.
        val templateUnknown =
                buildTemplateMobileWithRatType(null, TelephonyManager.NETWORK_TYPE_UNKNOWN)

        val identUmts = buildNetworkIdentity(
                mockContext, stateMobile, false, TelephonyManager.NETWORK_TYPE_UMTS)
        val identHsdpa = buildNetworkIdentity(
                mockContext, stateMobile, false, TelephonyManager.NETWORK_TYPE_HSDPA)
        val identLte = buildNetworkIdentity(
                mockContext, stateMobile, false, TelephonyManager.NETWORK_TYPE_LTE)
        val identCombined = buildNetworkIdentity(
                mockContext, stateMobile, false, SUBTYPE_COMBINED)
        val identImsi2 = buildNetworkIdentity(mockContext, buildMobileNetworkState(TEST_IMSI2),
                false, TelephonyManager.NETWORK_TYPE_UMTS)
        val identWifi = buildNetworkIdentity(
                mockContext, buildWifiNetworkState(TEST_SSID1), true, 0)

        // Assert that identity with the same RAT matches.
        templateUmts.assertMatches(identUmts)
        templateAll.assertMatches(identUmts)
        templateUnknown.assertDoesNotMatch(identUmts)
        // Assert that identity with the RAT within the same group matches.
        templateUmts.assertMatches(identHsdpa)
        templateAll.assertMatches(identHsdpa)
        templateUnknown.assertDoesNotMatch(identHsdpa)
        // Assert that identity with the RAT out of the same group only matches template with
        // NETWORK_TYPE_ALL.
        templateUmts.assertDoesNotMatch(identLte)
        templateAll.assertMatches(identLte)
        templateUnknown.assertDoesNotMatch(identLte)
        // Assert that identity with combined RAT only matches with template with NETWORK_TYPE_ALL
        // and NETWORK_TYPE_UNKNOWN.
        templateUmts.assertDoesNotMatch(identCombined)
        templateAll.assertMatches(identCombined)
        templateUnknown.assertMatches(identCombined)
        // Assert that identity with different IMSI matches.
        templateUmts.assertMatches(identImsi2)
        templateAll.assertMatches(identImsi2)
        templateUnknown.assertDoesNotMatch(identImsi2)
        // Assert that wifi identity does not match.
        templateUmts.assertDoesNotMatch(identWifi)
        templateAll.assertDoesNotMatch(identWifi)
        templateUnknown.assertDoesNotMatch(identWifi)
    }

    @Test
    fun testParcelUnparcel() {
        val templateMobile = NetworkTemplate(MATCH_MOBILE, TEST_IMSI1, null, null, METERED_ALL,
                ROAMING_ALL, DEFAULT_NETWORK_ALL, TelephonyManager.NETWORK_TYPE_LTE)
        val templateWifi = NetworkTemplate(MATCH_WIFI, null, null, TEST_SSID1, METERED_ALL,
                ROAMING_ALL, DEFAULT_NETWORK_ALL, 0)
        assertParcelSane(templateMobile, 8)
        assertParcelSane(templateWifi, 8)
    }

    // Verify NETWORK_TYPE_* constants in NetworkTemplate do not conflict with
    // TelephonyManager#NETWORK_TYPE_* constants.
    @Test
    fun testNetworkTypeConstants() {
        for (ratType in TelephonyManager.getAllNetworkTypes()) {
            assertNotEquals(NETWORK_TYPE_ALL, ratType)
            assertNotEquals(NETWORK_TYPE_5G_NSA, ratType)
        }
    }
}
