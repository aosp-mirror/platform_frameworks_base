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

package com.android.server.connectivity

import android.net.NetworkAgentConfig
import android.net.NetworkCapabilities
import android.net.NetworkScore.KEEP_CONNECTED_NONE
import android.text.TextUtils
import android.util.ArraySet
import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4
import com.android.server.connectivity.FullScore.MAX_CS_MANAGED_POLICY
import com.android.server.connectivity.FullScore.POLICY_ACCEPT_UNVALIDATED
import com.android.server.connectivity.FullScore.POLICY_EVER_USER_SELECTED
import com.android.server.connectivity.FullScore.POLICY_IS_VALIDATED
import com.android.server.connectivity.FullScore.POLICY_IS_VPN
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.collections.minOfOrNull
import kotlin.collections.maxOfOrNull
import kotlin.reflect.full.staticProperties
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@RunWith(AndroidJUnit4::class)
@SmallTest
class FullScoreTest {
    // Convenience methods
    fun FullScore.withPolicies(
        validated: Boolean = false,
        vpn: Boolean = false,
        onceChosen: Boolean = false,
        acceptUnvalidated: Boolean = false
    ): FullScore {
        val nac = NetworkAgentConfig.Builder().apply {
            setUnvalidatedConnectivityAcceptable(acceptUnvalidated)
            setExplicitlySelected(onceChosen)
        }.build()
        val nc = NetworkCapabilities.Builder().apply {
            if (vpn) addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            if (validated) addCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }.build()
        return mixInScore(nc, nac, validated, false /* yieldToBadWifi */)
    }

    @Test
    fun testGetLegacyInt() {
        val ns = FullScore(50, 0L /* policy */, KEEP_CONNECTED_NONE)
        assertEquals(10, ns.legacyInt) // -40 penalty for not being validated
        assertEquals(50, ns.legacyIntAsValidated)

        val vpnNs = FullScore(101, 0L /* policy */, KEEP_CONNECTED_NONE).withPolicies(vpn = true)
        assertEquals(101, vpnNs.legacyInt) // VPNs are not subject to unvalidation penalty
        assertEquals(101, vpnNs.legacyIntAsValidated)
        assertEquals(101, vpnNs.withPolicies(validated = true).legacyInt)
        assertEquals(101, vpnNs.withPolicies(validated = true).legacyIntAsValidated)

        val validatedNs = ns.withPolicies(validated = true)
        assertEquals(50, validatedNs.legacyInt) // No penalty, this is validated
        assertEquals(50, validatedNs.legacyIntAsValidated)

        val chosenNs = ns.withPolicies(onceChosen = true)
        assertEquals(10, chosenNs.legacyInt)
        assertEquals(100, chosenNs.legacyIntAsValidated)
        assertEquals(10, chosenNs.withPolicies(acceptUnvalidated = true).legacyInt)
        assertEquals(50, chosenNs.withPolicies(acceptUnvalidated = true).legacyIntAsValidated)
    }

    @Test
    fun testToString() {
        val string = FullScore(10, 0L /* policy */, KEEP_CONNECTED_NONE)
                .withPolicies(vpn = true, acceptUnvalidated = true).toString()
        assertTrue(string.contains("Score(10"), string)
        assertTrue(string.contains("ACCEPT_UNVALIDATED"), string)
        assertTrue(string.contains("IS_VPN"), string)
        assertFalse(string.contains("IS_VALIDATED"), string)
        val foundNames = ArraySet<String>()
        getAllPolicies().forEach {
            val name = FullScore.policyNameOf(it.get() as Int)
            assertFalse(TextUtils.isEmpty(name))
            assertFalse(foundNames.contains(name))
            foundNames.add(name)
        }
        assertFailsWith<IllegalArgumentException> {
            FullScore.policyNameOf(MAX_CS_MANAGED_POLICY + 1)
        }
    }

    fun getAllPolicies() = Regex("POLICY_.*").let { nameRegex ->
        FullScore::class.staticProperties.filter { it.name.matches(nameRegex) }
    }

    @Test
    fun testHasPolicy() {
        val ns = FullScore(50, 0L /* policy */, KEEP_CONNECTED_NONE)
        assertFalse(ns.hasPolicy(POLICY_IS_VALIDATED))
        assertFalse(ns.hasPolicy(POLICY_IS_VPN))
        assertFalse(ns.hasPolicy(POLICY_EVER_USER_SELECTED))
        assertFalse(ns.hasPolicy(POLICY_ACCEPT_UNVALIDATED))
        assertTrue(ns.withPolicies(validated = true).hasPolicy(POLICY_IS_VALIDATED))
        assertTrue(ns.withPolicies(vpn = true).hasPolicy(POLICY_IS_VPN))
        assertTrue(ns.withPolicies(onceChosen = true).hasPolicy(POLICY_EVER_USER_SELECTED))
        assertTrue(ns.withPolicies(acceptUnvalidated = true).hasPolicy(POLICY_ACCEPT_UNVALIDATED))
    }

    @Test
    fun testMinMaxPolicyConstants() {
        val policies = getAllPolicies()

        policies.forEach { policy ->
            assertTrue(policy.get() as Int >= FullScore.MIN_CS_MANAGED_POLICY)
            assertTrue(policy.get() as Int <= FullScore.MAX_CS_MANAGED_POLICY)
        }
        assertEquals(FullScore.MIN_CS_MANAGED_POLICY,
                policies.minOfOrNull { it.get() as Int })
        assertEquals(FullScore.MAX_CS_MANAGED_POLICY,
                policies.maxOfOrNull { it.get() as Int })
    }
}
