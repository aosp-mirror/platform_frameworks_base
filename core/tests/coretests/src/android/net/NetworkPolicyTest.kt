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

import android.text.format.Time.TIMEZONE_UTC
import androidx.test.runner.AndroidJUnit4
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.time.ZoneId
import kotlin.test.assertEquals

private const val TEST_IMSI1 = "TESTIMSI1"
private const val TEST_SSID1 = "TESTISSID1"

@RunWith(AndroidJUnit4::class)
class NetworkPolicyTest {
    @Test
    fun testTemplateBackupRestore() {
        assertPolicyBackupRestore(createTestPolicyForTemplate(
                NetworkTemplate.buildTemplateWifi(TEST_SSID1)))
        assertPolicyBackupRestore(createTestPolicyForTemplate(
                NetworkTemplate.buildTemplateMobileAll(TEST_IMSI1)))
        assertPolicyBackupRestore(createTestPolicyForTemplate(
                NetworkTemplate.buildTemplateCarrierMetered(TEST_IMSI1)))
    }

    private fun createTestPolicyForTemplate(template: NetworkTemplate): NetworkPolicy {
        return NetworkPolicy(template, NetworkPolicy.buildRule(5, ZoneId.of(TIMEZONE_UTC)),
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
}