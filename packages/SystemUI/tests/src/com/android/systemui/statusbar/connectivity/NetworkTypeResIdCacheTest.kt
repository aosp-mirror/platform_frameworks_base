/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.statusbar.connectivity

import android.test.suitebuilder.annotation.SmallTest
import android.testing.AndroidTestingRunner
import com.android.settingslib.SignalIcon.MobileIconGroup
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class NetworkTypeResIdCacheTest : SysuiTestCase() {
    private lateinit var cache: NetworkTypeResIdCache
    private var overrides = MobileIconCarrierIdOverridesFake()

    @Before
    fun setUp() {
        cache = NetworkTypeResIdCache(overrides)
    }

    @Test
    fun carrier1_noOverride_usesDefault() {
        assertThat(cache.get(group1, CARRIER_1, context)).isEqualTo(iconDefault1)
    }

    @Test
    fun carrier1_overridden_usesOverride() {
        overrides.overriddenIds.add(CARRIER_1)
        overrides.overridesByCarrierId[CARRIER_1] = mapOf(NET_TYPE_1 to iconOverride1)

        assertThat(cache.get(group1, CARRIER_1, context)).isEqualTo(iconOverride1)
    }

    @Test
    fun carrier1_override_carrier2UsesDefault() {
        overrides.overriddenIds.add(CARRIER_1)
        overrides.overridesByCarrierId[CARRIER_1] = mapOf(NET_TYPE_1 to iconOverride1)

        assertThat(cache.get(group1, CARRIER_2, context)).isEqualTo(iconDefault1)
    }

    @Test
    fun carrier1_overrideType1_type2UsesDefault() {
        overrides.overriddenIds.add(CARRIER_1)
        overrides.overridesByCarrierId[CARRIER_1] = mapOf(NET_TYPE_1 to iconOverride1)

        assertThat(cache.get(group2, CARRIER_1, context)).isEqualTo(iconDefault2)
    }

    companion object {
        // Simplified icon overrides here
        const val CARRIER_1 = 1
        const val CARRIER_2 = 2

        const val NET_TYPE_1 = "one"
        const val iconDefault1 = 123
        const val iconOverride1 = 321
        val group1 = MobileIconGroup(NET_TYPE_1, /* dataContentDesc */ 0, iconDefault1)

        const val NET_TYPE_2 = "two"
        const val iconDefault2 = 234

        val group2 = MobileIconGroup(NET_TYPE_2, /* dataContentDesc*/ 0, iconDefault2)
    }
}
