/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.shade.domain.startable

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.shade.domain.interactor.shadeInteractor
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ShadeStartableTest : SysuiTestCase() {

    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val shadeInteractor = kosmos.shadeInteractor
    private val fakeConfigurationRepository = kosmos.fakeConfigurationRepository

    private val underTest = kosmos.shadeStartable

    @Test
    fun hydrateSplitShade() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, false)
            val isSplitShade by collectLastValue(shadeInteractor.isSplitShade)

            underTest.start()
            assertThat(isSplitShade).isFalse()

            overrideResource(R.bool.config_use_split_notification_shade, true)
            fakeConfigurationRepository.onAnyConfigurationChange()
            assertThat(isSplitShade).isTrue()

            overrideResource(R.bool.config_use_split_notification_shade, false)
            fakeConfigurationRepository.onAnyConfigurationChange()
            assertThat(isSplitShade).isFalse()
        }
}
