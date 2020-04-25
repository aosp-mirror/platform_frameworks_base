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

import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.Q)
class NetworkSpecifierTest {
    private class TestNetworkSpecifier(
        val intData: Int = 123,
        val stringData: String = "init"
    ) : NetworkSpecifier() {
        override fun canBeSatisfiedBy(other: NetworkSpecifier?): Boolean =
                other != null &&
                other is TestNetworkSpecifier &&
                other.intData >= intData &&
                stringData.equals(other.stringData)

        override fun redact(): NetworkSpecifier = TestNetworkSpecifier(intData, "redact")
    }

    @Test
    fun testRedact() {
        val ns: TestNetworkSpecifier = TestNetworkSpecifier()
        val redactNs = ns.redact()
        assertTrue(redactNs is TestNetworkSpecifier)
        assertEquals(ns.intData, redactNs.intData)
        assertNotEquals(ns.stringData, redactNs.stringData)
        assertTrue("redact".equals(redactNs.stringData))
    }

    @Test
    fun testcanBeSatisfiedBy() {
        val target: TestNetworkSpecifier = TestNetworkSpecifier()
        assertFalse(target.canBeSatisfiedBy(null))
        assertTrue(target.canBeSatisfiedBy(TestNetworkSpecifier()))
        val otherNs = TelephonyNetworkSpecifier.Builder().setSubscriptionId(123).build()
        assertFalse(target.canBeSatisfiedBy(otherNs))
        assertTrue(target.canBeSatisfiedBy(TestNetworkSpecifier(intData = 999)))
        assertFalse(target.canBeSatisfiedBy(TestNetworkSpecifier(intData = 1)))
        assertFalse(target.canBeSatisfiedBy(TestNetworkSpecifier(stringData = "diff")))
    }
}