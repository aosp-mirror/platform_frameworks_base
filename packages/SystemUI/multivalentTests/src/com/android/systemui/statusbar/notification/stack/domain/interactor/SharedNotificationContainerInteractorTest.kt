/*
 * Copyright (C) 2023 The Android Open Source Project
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
 *
 */

package com.android.systemui.statusbar.notification.stack.domain.interactor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.fingerprintPropertyRepository
import com.android.systemui.common.ui.data.repository.fakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.fakeKeyguardRepository
import com.android.systemui.kosmos.testScope
import com.android.systemui.res.R
import com.android.systemui.testKosmos
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SharedNotificationContainerInteractorTest : SysuiTestCase() {
    private val kosmos = testKosmos()
    private val testScope = kosmos.testScope
    private val keyguardRepository = kosmos.fakeKeyguardRepository
    private val configurationRepository = kosmos.fakeConfigurationRepository
    private val fingerprintPropertyRepository = kosmos.fingerprintPropertyRepository
    private val underTest = kosmos.sharedNotificationContainerInteractor

    @Test
    fun validateConfigValues() =
        testScope.runTest {
            overrideResource(R.bool.config_use_split_notification_shade, true)
            overrideResource(R.bool.config_use_large_screen_shade_header, false)
            overrideResource(R.dimen.notification_panel_margin_horizontal, 0)
            overrideResource(R.dimen.notification_panel_margin_bottom, 10)
            overrideResource(R.dimen.notification_panel_margin_top, 10)
            overrideResource(R.dimen.large_screen_shade_header_height, 0)
            overrideResource(R.dimen.keyguard_split_shade_top_margin, 55)

            val dimens = collectLastValue(underTest.configurationBasedDimensions)

            configurationRepository.onAnyConfigurationChange()
            runCurrent()

            val lastDimens = dimens()!!

            assertThat(lastDimens.useSplitShade).isTrue()
            assertThat(lastDimens.useLargeScreenHeader).isFalse()
            assertThat(lastDimens.marginHorizontal).isEqualTo(0)
            assertThat(lastDimens.marginBottom).isGreaterThan(0)
            assertThat(lastDimens.marginTop).isGreaterThan(0)
            assertThat(lastDimens.marginTopLargeScreen).isEqualTo(0)
            assertThat(lastDimens.keyguardSplitShadeTopMargin).isEqualTo(55)
        }

    @Test
    fun useExtraShelfSpaceIsTrueWithUdfps() =
        testScope.runTest {
            val useExtraShelfSpace by collectLastValue(underTest.useExtraShelfSpace)

            keyguardRepository.ambientIndicationVisible.value = true
            fingerprintPropertyRepository.supportsUdfps()

            assertThat(useExtraShelfSpace).isEqualTo(true)
        }

    @Test
    fun useExtraShelfSpaceIsTrueWithRearFpsAndNoAmbientIndicationArea() =
        testScope.runTest {
            val useExtraShelfSpace by collectLastValue(underTest.useExtraShelfSpace)

            keyguardRepository.ambientIndicationVisible.value = false
            fingerprintPropertyRepository.supportsRearFps()

            assertThat(useExtraShelfSpace).isEqualTo(true)
        }

    @Test
    fun useExtraShelfSpaceIsFalseWithRearFpsAndAmbientIndicationArea() =
        testScope.runTest {
            val useExtraShelfSpace by collectLastValue(underTest.useExtraShelfSpace)

            keyguardRepository.ambientIndicationVisible.value = true
            fingerprintPropertyRepository.supportsRearFps()

            assertThat(useExtraShelfSpace).isEqualTo(false)
        }
}
