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

import android.net.NetworkIdentity.OEM_NONE
import android.net.NetworkIdentity.OEM_PAID
import android.net.NetworkIdentity.OEM_PRIVATE
import android.net.NetworkIdentity.getOemBitfield
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import kotlin.test.assertEquals

@RunWith(JUnit4::class)
class NetworkIdentityTest {
    @Test
    fun testGetOemBitfield() {
        val oemNone = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, false)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, false)
        }
        val oemPaid = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, true)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, false)
        }
        val oemPrivate = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, false)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, true)
        }
        val oemAll = NetworkCapabilities().apply {
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PAID, true)
            setCapability(NetworkCapabilities.NET_CAPABILITY_OEM_PRIVATE, true)
        }

        assertEquals(getOemBitfield(oemNone), OEM_NONE)
        assertEquals(getOemBitfield(oemPaid), OEM_PAID)
        assertEquals(getOemBitfield(oemPrivate), OEM_PRIVATE)
        assertEquals(getOemBitfield(oemAll), OEM_PAID or OEM_PRIVATE)
    }
}
