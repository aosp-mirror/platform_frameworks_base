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
import android.net.NetworkIdentity.OEM_NONE
import android.net.NetworkIdentity.OEM_PAID
import android.net.NetworkIdentity.OEM_PRIVATE
import android.net.NetworkIdentity.buildNetworkIdentity
import android.net.NetworkStats.DEFAULT_NETWORK_ALL
import android.net.NetworkStats.METERED_ALL
import android.net.NetworkStats.ROAMING_ALL
import android.net.NetworkTemplate.MATCH_MOBILE
import android.net.NetworkTemplate.MATCH_MOBILE_WILDCARD
import android.net.NetworkTemplate.MATCH_WIFI
import android.net.NetworkTemplate.MATCH_WIFI_WILDCARD
import android.net.NetworkTemplate.WIFI_NETWORKID_ALL
import android.net.NetworkTemplate.NETWORK_TYPE_5G_NSA
import android.net.NetworkTemplate.NETWORK_TYPE_ALL
import android.net.NetworkTemplate.OEM_MANAGED_ALL
import android.net.NetworkTemplate.OEM_MANAGED_NO
import android.net.NetworkTemplate.OEM_MANAGED_YES
import android.net.NetworkTemplate.SUBSCRIBER_ID_MATCH_RULE_EXACT
import android.net.NetworkTemplate.buildTemplateWifi
import android.net.NetworkTemplate.buildTemplateWifiWildcard
import android.net.NetworkTemplate.buildTemplateCarrierMetered
import android.net.NetworkTemplate.buildTemplateMobileWithRatType
import android.telephony.TelephonyManager
import com.android.testutils.assertParcelSane
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mockito.mock
import org.mockito.MockitoAnnotations
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private const val TEST_IMSI1 = "imsi1"
private const val TEST_IMSI2 = "imsi2"
private const val TEST_SSID1 = "ssid1"
private const val TEST_SSID2 = "ssid2"

@RunWith(JUnit4::class)
class NetworkTemplateTest {
    private val mockContext = mock(Context::class.java)

    private fun buildMobileNetworkState(subscriberId: String): NetworkStateSnapshot =
            buildNetworkState(TYPE_MOBILE, subscriberId = subscriberId)
    private fun buildWifiNetworkState(subscriberId: String?, ssid: String?): NetworkStateSnapshot =
            buildNetworkState(TYPE_WIFI, subscriberId = subscriberId, ssid = ssid)

    private fun buildNetworkState(
        type: Int,
        subscriberId: String? = null,
        ssid: String? = null,
        oemManaged: Int = OEM_NONE,
        metered: Boolean = true
    ): NetworkStateSnapshot {
        val lp = LinkProperties()
        val caps = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED, !metered)
            setCapability(NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING, true)
            setSSID(ssid)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID,
                    (oemManaged and OEM_PAID) == OEM_PAID)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE,
                    (oemManaged and OEM_PRIVATE) == OEM_PRIVATE)
        }
        return NetworkStateSnapshot(mock(Network::class.java), caps, lp, subscriberId, type)
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
    fun testWifiWildcardMatches() {
        val templateWifiWildcard = buildTemplateWifiWildcard()

        val identMobileImsi1 = buildNetworkIdentity(mockContext,
                buildMobileNetworkState(TEST_IMSI1),
                false, TelephonyManager.NETWORK_TYPE_UMTS)
        val identWifiImsiNullSsid1 = buildNetworkIdentity(
                mockContext, buildWifiNetworkState(null, TEST_SSID1), true, 0)
        val identWifiImsi1Ssid1 = buildNetworkIdentity(
                mockContext, buildWifiNetworkState(TEST_IMSI1, TEST_SSID1), true, 0)

        templateWifiWildcard.assertDoesNotMatch(identMobileImsi1)
        templateWifiWildcard.assertMatches(identWifiImsiNullSsid1)
        templateWifiWildcard.assertMatches(identWifiImsi1Ssid1)
    }

    @Test
    fun testWifiMatches() {
        val templateWifiSsid1 = buildTemplateWifi(TEST_SSID1)
        val templateWifiSsid1ImsiNull = buildTemplateWifi(TEST_SSID1, null)
        val templateWifiSsid1Imsi1 = buildTemplateWifi(TEST_SSID1, TEST_IMSI1)
        val templateWifiSsidAllImsi1 = buildTemplateWifi(WIFI_NETWORKID_ALL, TEST_IMSI1)

        val identMobile1 = buildNetworkIdentity(mockContext, buildMobileNetworkState(TEST_IMSI1),
                false, TelephonyManager.NETWORK_TYPE_UMTS)
        val identWifiImsiNullSsid1 = buildNetworkIdentity(
                mockContext, buildWifiNetworkState(null, TEST_SSID1), true, 0)
        val identWifiImsi1Ssid1 = buildNetworkIdentity(
                mockContext, buildWifiNetworkState(TEST_IMSI1, TEST_SSID1), true, 0)
        val identWifiImsi2Ssid1 = buildNetworkIdentity(
                mockContext, buildWifiNetworkState(TEST_IMSI2, TEST_SSID1), true, 0)
        val identWifiImsi1Ssid2 = buildNetworkIdentity(
                mockContext, buildWifiNetworkState(TEST_IMSI1, TEST_SSID2), true, 0)

        // Verify that template with SSID only matches any subscriberId and specific SSID.
        templateWifiSsid1.assertDoesNotMatch(identMobile1)
        templateWifiSsid1.assertMatches(identWifiImsiNullSsid1)
        templateWifiSsid1.assertMatches(identWifiImsi1Ssid1)
        templateWifiSsid1.assertMatches(identWifiImsi2Ssid1)
        templateWifiSsid1.assertDoesNotMatch(identWifiImsi1Ssid2)

        // Verify that template with SSID1 and null imsi matches any network with
        // SSID1 and null imsi.
        templateWifiSsid1ImsiNull.assertDoesNotMatch(identMobile1)
        templateWifiSsid1ImsiNull.assertMatches(identWifiImsiNullSsid1)
        templateWifiSsid1ImsiNull.assertDoesNotMatch(identWifiImsi1Ssid1)
        templateWifiSsid1ImsiNull.assertDoesNotMatch(identWifiImsi2Ssid1)
        templateWifiSsid1ImsiNull.assertDoesNotMatch(identWifiImsi1Ssid2)

        // Verify that template with SSID1 and imsi1 matches any network with
        // SSID1 and imsi1.
        templateWifiSsid1Imsi1.assertDoesNotMatch(identMobile1)
        templateWifiSsid1Imsi1.assertDoesNotMatch(identWifiImsiNullSsid1)
        templateWifiSsid1Imsi1.assertMatches(identWifiImsi1Ssid1)
        templateWifiSsid1Imsi1.assertDoesNotMatch(identWifiImsi2Ssid1)
        templateWifiSsid1Imsi1.assertDoesNotMatch(identWifiImsi1Ssid2)

        // Verify that template with SSID all and imsi1 matches any network with
        // any SSID and imsi1.
        templateWifiSsidAllImsi1.assertDoesNotMatch(identMobile1)
        templateWifiSsidAllImsi1.assertDoesNotMatch(identWifiImsiNullSsid1)
        templateWifiSsidAllImsi1.assertMatches(identWifiImsi1Ssid1)
        templateWifiSsidAllImsi1.assertDoesNotMatch(identWifiImsi2Ssid1)
        templateWifiSsidAllImsi1.assertMatches(identWifiImsi1Ssid2)
    }

    @Test
    fun testCarrierMeteredMatches() {
        val templateCarrierImsi1Metered = buildTemplateCarrierMetered(TEST_IMSI1)

        val mobileImsi1 = buildMobileNetworkState(TEST_IMSI1)
        val mobileImsi1Unmetered = buildNetworkState(TYPE_MOBILE, TEST_IMSI1, null /* ssid */,
                OEM_NONE, false /* metered */)
        val mobileImsi2 = buildMobileNetworkState(TEST_IMSI2)
        val wifiSsid1 = buildWifiNetworkState(null /* subscriberId */, TEST_SSID1)
        val wifiImsi1Ssid1 = buildWifiNetworkState(TEST_IMSI1, TEST_SSID1)
        val wifiImsi1Ssid1Unmetered = buildNetworkState(TYPE_WIFI, TEST_IMSI1, TEST_SSID1,
                OEM_NONE, false /* metered */)

        val identMobileImsi1Metered = buildNetworkIdentity(mockContext,
                mobileImsi1, false /* defaultNetwork */, TelephonyManager.NETWORK_TYPE_UMTS)
        val identMobileImsi1Unmetered = buildNetworkIdentity(mockContext,
                mobileImsi1Unmetered, false /* defaultNetwork */,
                TelephonyManager.NETWORK_TYPE_UMTS)
        val identMobileImsi2Metered = buildNetworkIdentity(mockContext,
                mobileImsi2, false /* defaultNetwork */, TelephonyManager.NETWORK_TYPE_UMTS)
        val identWifiSsid1Metered = buildNetworkIdentity(
                mockContext, wifiSsid1, true /* defaultNetwork */, 0 /* subType */)
        val identCarrierWifiImsi1Metered = buildNetworkIdentity(
                mockContext, wifiImsi1Ssid1, true /* defaultNetwork */, 0 /* subType */)
        val identCarrierWifiImsi1NonMetered = buildNetworkIdentity(mockContext,
                wifiImsi1Ssid1Unmetered, true /* defaultNetwork */, 0 /* subType */)

        templateCarrierImsi1Metered.assertMatches(identMobileImsi1Metered)
        templateCarrierImsi1Metered.assertDoesNotMatch(identMobileImsi1Unmetered)
        templateCarrierImsi1Metered.assertDoesNotMatch(identMobileImsi2Metered)
        templateCarrierImsi1Metered.assertDoesNotMatch(identWifiSsid1Metered)
        templateCarrierImsi1Metered.assertMatches(identCarrierWifiImsi1Metered)
        templateCarrierImsi1Metered.assertDoesNotMatch(identCarrierWifiImsi1NonMetered)
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
                mockContext, buildWifiNetworkState(null, TEST_SSID1), true, 0)

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
                ROAMING_ALL, DEFAULT_NETWORK_ALL, TelephonyManager.NETWORK_TYPE_LTE,
                OEM_MANAGED_ALL, SUBSCRIBER_ID_MATCH_RULE_EXACT)
        val templateWifi = NetworkTemplate(MATCH_WIFI, null, null, TEST_SSID1, METERED_ALL,
                ROAMING_ALL, DEFAULT_NETWORK_ALL, 0, OEM_MANAGED_ALL,
                SUBSCRIBER_ID_MATCH_RULE_EXACT)
        val templateOem = NetworkTemplate(MATCH_MOBILE, null, null, null, METERED_ALL,
                ROAMING_ALL, DEFAULT_NETWORK_ALL, 0, OEM_MANAGED_YES,
                SUBSCRIBER_ID_MATCH_RULE_EXACT)
        assertParcelSane(templateMobile, 10)
        assertParcelSane(templateWifi, 10)
        assertParcelSane(templateOem, 10)
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

    @Test
    fun testOemNetworkConstants() {
        val constantValues = arrayOf(OEM_MANAGED_YES, OEM_MANAGED_ALL, OEM_MANAGED_NO,
                OEM_PAID, OEM_PRIVATE, OEM_PAID or OEM_PRIVATE)

        // Verify that "not OEM managed network" constants are equal.
        assertEquals(OEM_MANAGED_NO, OEM_NONE)

        // Verify the constants don't conflict.
        assertEquals(constantValues.size, constantValues.distinct().count())
    }

    /**
     * Helper to enumerate and assert OEM managed wifi and mobile {@code NetworkTemplate}s match
     * their the appropriate OEM managed {@code NetworkIdentity}s.
     *
     * @param networkType {@code TYPE_MOBILE} or {@code TYPE_WIFI}
     * @param matchType A match rule from {@code NetworkTemplate.MATCH_*} corresponding to the
     *         networkType.
     * @param subscriberId To be populated with {@code TEST_IMSI*} only if networkType is
     *         {@code TYPE_MOBILE}. May be left as null when matchType is
     *         {@link NetworkTemplate.MATCH_MOBILE_WILDCARD}.
     * @param templateSsid Top be populated with {@code TEST_SSID*} only if networkType is
     *         {@code TYPE_WIFI}. May be left as null when matchType is
     *         {@link NetworkTemplate.MATCH_WIFI_WILDCARD}.
     * @param identSsid If networkType is {@code TYPE_WIFI}, this value must *NOT* be null. Provide
     *         one of {@code TEST_SSID*}.
     */
    private fun matchOemManagedIdent(
        networkType: Int,
        matchType: Int,
        subscriberId: String? = null,
        templateSsid: String? = null,
        identSsid: String? = null
    ) {
        val oemManagedStates = arrayOf(OEM_NONE, OEM_PAID, OEM_PRIVATE, OEM_PAID or OEM_PRIVATE)
        val matchSubscriberIds = arrayOf(subscriberId)

        val templateOemYes = NetworkTemplate(matchType, subscriberId, matchSubscriberIds,
                templateSsid, METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL,
                OEM_MANAGED_YES, SUBSCRIBER_ID_MATCH_RULE_EXACT)
        val templateOemAll = NetworkTemplate(matchType, subscriberId, matchSubscriberIds,
                templateSsid, METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL, NETWORK_TYPE_ALL,
                OEM_MANAGED_ALL, SUBSCRIBER_ID_MATCH_RULE_EXACT)

        for (identityOemManagedState in oemManagedStates) {
            val ident = buildNetworkIdentity(mockContext, buildNetworkState(networkType,
                    subscriberId, identSsid, identityOemManagedState), /*defaultNetwork=*/false,
                    /*subType=*/0)

            // Create a template with each OEM managed type and match it against the NetworkIdentity
            for (templateOemManagedState in oemManagedStates) {
                val template = NetworkTemplate(matchType, subscriberId, matchSubscriberIds,
                        templateSsid, METERED_ALL, ROAMING_ALL, DEFAULT_NETWORK_ALL,
                        NETWORK_TYPE_ALL, templateOemManagedState, SUBSCRIBER_ID_MATCH_RULE_EXACT)
                if (identityOemManagedState == templateOemManagedState) {
                    template.assertMatches(ident)
                } else {
                    template.assertDoesNotMatch(ident)
                }
            }
            // OEM_MANAGED_ALL ignores OEM state.
            templateOemAll.assertMatches(ident)
            if (identityOemManagedState == OEM_NONE) {
                // OEM_MANAGED_YES matches everything except OEM_NONE.
                templateOemYes.assertDoesNotMatch(ident)
            } else {
                templateOemYes.assertMatches(ident)
            }
        }
    }

    @Test
    fun testOemManagedMatchesIdent() {
        matchOemManagedIdent(TYPE_MOBILE, MATCH_MOBILE, subscriberId = TEST_IMSI1)
        matchOemManagedIdent(TYPE_MOBILE, MATCH_MOBILE_WILDCARD)
        matchOemManagedIdent(TYPE_WIFI, MATCH_WIFI, templateSsid = TEST_SSID1,
                identSsid = TEST_SSID1)
        matchOemManagedIdent(TYPE_WIFI, MATCH_WIFI_WILDCARD, identSsid = TEST_SSID1)
    }
}
