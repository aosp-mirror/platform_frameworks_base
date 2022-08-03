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

package android.net

import android.net.NetworkStats.METERED_YES
import android.net.NetworkTemplate.MATCH_BLUETOOTH
import android.net.NetworkTemplate.MATCH_CARRIER
import android.net.NetworkTemplate.MATCH_ETHERNET
import android.net.NetworkTemplate.MATCH_MOBILE
import android.net.NetworkTemplate.MATCH_WIFI
import androidx.test.runner.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val TEST_IMSI1 = "TESTIMSI1"
private const val TEST_WIFI_NETWORK_KEY1 = "TESTKEY1"

@RunWith(AndroidJUnit4::class)
class NetworkPolicyTest {
    @Test
    fun testTemplateBackupRestore() {
        assertPolicyBackupRestore(createTestPolicyForTemplate(
                NetworkTemplate.Builder(MATCH_WIFI)
                    .setWifiNetworkKeys(setOf(TEST_WIFI_NETWORK_KEY1))
                    .build()))
        assertPolicyBackupRestore(createTestPolicyForTemplate(
                NetworkTemplate.Builder(MATCH_MOBILE)
                    .setSubscriberIds(setOf(TEST_IMSI1))
                    .setMeteredness(METERED_YES)
                    .build()))
        assertPolicyBackupRestore(createTestPolicyForTemplate(
                NetworkTemplate.Builder(MATCH_CARRIER)
                    .setSubscriberIds(setOf(TEST_IMSI1))
                    .setMeteredness(METERED_YES)
                    .build()))
    }

    private fun createTestPolicyForTemplate(template: NetworkTemplate): NetworkPolicy {
        return NetworkPolicy(template, NetworkPolicy.buildRule(5, ZoneId.of("UTC")),
                NetworkPolicy.WARNING_DISABLED, NetworkPolicy.LIMIT_DISABLED,
                NetworkPolicy.SNOOZE_NEVER, NetworkPolicy.SNOOZE_NEVER, NetworkPolicy.SNOOZE_NEVER,
                /*metered*/ false, /*inferred*/ true)
    }

    private fun assertPolicyBackupRestore(policy: NetworkPolicy) {
        val bytes = policy.bytesForBackup
        val stream = DataInputStream(ByteArrayInputStream(bytes))
        val restored = NetworkPolicy.getNetworkPolicyFromBackup(stream)
        assertEquals(policy, restored)
    }

    @Test
    fun testIsTemplatePersistable() {
        listOf(MATCH_MOBILE, MATCH_WIFI).forEach {
            // Verify wildcard templates cannot be persistable.
            assertFalse(NetworkPolicy.isTemplatePersistable(NetworkTemplate.Builder(it).build()))

            // Verify mobile/wifi templates can be persistable if the Subscriber Id is supplied.
            assertTrue(NetworkPolicy.isTemplatePersistable(NetworkTemplate.Builder(it)
                    .setSubscriberIds(setOf(TEST_IMSI1)).build()))
        }

        // Verify bluetooth and ethernet templates can be persistable without any other
        // field is supplied.
        listOf(MATCH_BLUETOOTH, MATCH_ETHERNET).forEach {
            assertTrue(NetworkPolicy.isTemplatePersistable(NetworkTemplate.Builder(it).build()))
        }

        // Verify wifi template can be persistable if the Wifi Network Key is supplied.
        assertTrue(NetworkPolicy.isTemplatePersistable(NetworkTemplate.Builder(MATCH_WIFI)
                .setWifiNetworkKeys(setOf(TEST_WIFI_NETWORK_KEY1)).build()))
    }
}
