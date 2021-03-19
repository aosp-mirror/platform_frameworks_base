/*
 * Copyright (C) 2019 The Android Open Source Project
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
import android.net.ConnectivityResources
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.MAX_TRANSPORT
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import androidx.test.filters.SmallTest
import com.android.internal.R
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.any
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

/**
 * Tests for [KeepaliveUtils].
 *
 * Build, install and run with:
 * atest android.net.util.KeepaliveUtilsTest
 */
@RunWith(JUnit4::class)
@SmallTest
class KeepaliveUtilsTest {

    // Prepare mocked context with given resource strings.
    private fun getMockedContextWithStringArrayRes(
        id: Int,
        name: String,
        res: Array<out String?>?
    ): Context {
        val mockRes = mock(Resources::class.java)
        doReturn(res).`when`(mockRes).getStringArray(eq(id))
        doReturn(id).`when`(mockRes).getIdentifier(eq(name), any(), any())

        return mock(Context::class.java).apply {
            doReturn(mockRes).`when`(this).getResources()
            ConnectivityResources.setResourcesContextForTest(this)
        }
    }

    @After
    fun tearDown() {
        ConnectivityResources.setResourcesContextForTest(null)
    }

    @Test
    fun testGetSupportedKeepalives() {
        fun assertRunWithException(res: Array<out String?>?) {
            try {
                val mockContext = getMockedContextWithStringArrayRes(
                        R.array.config_networkSupportedKeepaliveCount,
                        "config_networkSupportedKeepaliveCount", res)
                KeepaliveUtils.getSupportedKeepalives(mockContext)
                fail("Expected KeepaliveDeviceConfigurationException")
            } catch (expected: KeepaliveUtils.KeepaliveDeviceConfigurationException) {
            }
        }

        // Check resource with various invalid format.
        assertRunWithException(null)
        assertRunWithException(arrayOf<String?>(null))
        assertRunWithException(arrayOfNulls<String?>(10))
        assertRunWithException(arrayOf(""))
        assertRunWithException(arrayOf("3,ABC"))
        assertRunWithException(arrayOf("6,3,3"))
        assertRunWithException(arrayOf("5"))

        // Check resource with invalid slots value.
        assertRunWithException(arrayOf("3,-1"))

        // Check resource with invalid transport type.
        assertRunWithException(arrayOf("-1,3"))
        assertRunWithException(arrayOf("10,3"))

        // Check valid customization generates expected array.
        val validRes = arrayOf("0,3", "1,0", "4,4")
        val expectedValidRes = intArrayOf(3, 0, 0, 0, 4, 0, 0, 0)

        val mockContext = getMockedContextWithStringArrayRes(
                R.array.config_networkSupportedKeepaliveCount,
                "config_networkSupportedKeepaliveCount", validRes)
        val actual = KeepaliveUtils.getSupportedKeepalives(mockContext)
        assertArrayEquals(expectedValidRes, actual)
    }

    @Test
    fun testGetSupportedKeepalivesForNetworkCapabilities() {
        // Mock customized supported keepalives for each transport type, and assuming:
        //   3 for cellular,
        //   6 for wifi,
        //   0 for others.
        val cust = IntArray(MAX_TRANSPORT + 1).apply {
            this[TRANSPORT_CELLULAR] = 3
            this[TRANSPORT_WIFI] = 6
        }

        val nc = NetworkCapabilities()
        // Check supported keepalives with single transport type.
        nc.transportTypes = intArrayOf(TRANSPORT_CELLULAR)
        assertEquals(3, KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(cust, nc))

        // Check supported keepalives with multiple transport types.
        nc.transportTypes = intArrayOf(TRANSPORT_WIFI, TRANSPORT_VPN)
        assertEquals(0, KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(cust, nc))

        // Check supported keepalives with non-customized transport type.
        nc.transportTypes = intArrayOf(TRANSPORT_ETHERNET)
        assertEquals(0, KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(cust, nc))

        // Check supported keepalives with undefined transport type.
        nc.transportTypes = intArrayOf(MAX_TRANSPORT + 1)
        try {
            KeepaliveUtils.getSupportedKeepalivesForNetworkCapabilities(cust, nc)
            fail("Expected ArrayIndexOutOfBoundsException")
        } catch (expected: ArrayIndexOutOfBoundsException) {
        }
    }
}
