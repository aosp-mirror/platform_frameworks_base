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

package com.android.systemui.statusbar.pipeline.wifi.data.model

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel.Active.Companion.MAX_VALID_LEVEL
import com.android.systemui.statusbar.pipeline.wifi.data.model.WifiNetworkModel.Active.Companion.MIN_VALID_LEVEL
import org.junit.Test

@SmallTest
class WifiNetworkModelTest : SysuiTestCase() {
    @Test
    fun active_levelsInValidRange_noException() {
        (MIN_VALID_LEVEL..MAX_VALID_LEVEL).forEach { level ->
            WifiNetworkModel.Active(NETWORK_ID, level = level)
            // No assert, just need no crash
        }
    }

    @Test
    fun active_levelNull_noException() {
        WifiNetworkModel.Active(NETWORK_ID, level = null)
        // No assert, just need no crash
    }

    @Test(expected = IllegalArgumentException::class)
    fun active_levelNegative_exceptionThrown() {
        WifiNetworkModel.Active(NETWORK_ID, level = MIN_VALID_LEVEL - 1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun active_levelTooHigh_exceptionThrown() {
        WifiNetworkModel.Active(NETWORK_ID, level = MAX_VALID_LEVEL + 1)
    }

    companion object {
        private const val NETWORK_ID = 2
    }
}
