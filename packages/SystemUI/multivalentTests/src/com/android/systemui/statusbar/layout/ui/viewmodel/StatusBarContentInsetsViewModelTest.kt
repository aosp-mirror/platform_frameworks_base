/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.statusbar.layout.ui.viewmodel

import android.content.res.Configuration
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.collectValues
import com.android.systemui.kosmos.runTest
import com.android.systemui.kosmos.useUnconfinedTestDispatcher
import com.android.systemui.statusbar.core.StatusBarConnectedDisplays
import com.android.systemui.statusbar.layout.statusBarContentInsetsProvider
import com.android.systemui.statusbar.policy.configurationController
import com.android.systemui.statusbar.policy.fake
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
@EnableFlags(StatusBarConnectedDisplays.FLAG_NAME)
class StatusBarContentInsetsViewModelTest : SysuiTestCase() {
    private val kosmos = testKosmos().useUnconfinedTestDispatcher()
    private val configuration = Configuration()

    private val Kosmos.underTest by Kosmos.Fixture { statusBarContentInsetsViewModel }

    @Test
    fun contentArea_onMaxBoundsChanged_emitsNewValue() =
        kosmos.runTest {
            statusBarContentInsetsProvider.start()

            val values by collectValues(underTest.contentArea)

            // WHEN the content area changes
            configurationController.fake.notifyLayoutDirectionChanged(isRtl = true)
            configurationController.fake.notifyDensityOrFontScaleChanged()

            // THEN the flow emits the new bounds
            assertThat(values[0]).isNotEqualTo(values[1])
        }

    @Test
    fun contentArea_onDensityOrFontScaleChanged_emitsLastBounds() =
        kosmos.runTest {
            configuration.densityDpi = 12
            statusBarContentInsetsProvider.start()

            val values by collectValues(underTest.contentArea)

            // WHEN a change happens but it doesn't affect content area
            configuration.densityDpi = 20
            configurationController.onConfigurationChanged(configuration)
            configurationController.fake.notifyDensityOrFontScaleChanged()

            // THEN it still has the last bounds
            assertThat(values).hasSize(1)
        }
}
