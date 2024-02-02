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

package com.android.systemui.statusbar.notification.stack.ui.viewmodel

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.statusbar.notification.stack.domain.interactor.SharedNotificationContainerInteractor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SharedNotificationContainerViewModelTest : SysuiTestCase() {
    private lateinit var configurationRepository: FakeConfigurationRepository
    private lateinit var sharedNotificationContainerInteractor:
        SharedNotificationContainerInteractor
    private lateinit var underTest: SharedNotificationContainerViewModel

    @Before
    fun setUp() {
        configurationRepository = FakeConfigurationRepository()
        sharedNotificationContainerInteractor =
            SharedNotificationContainerInteractor(
                configurationRepository,
                mContext,
            )
        underTest = SharedNotificationContainerViewModel(sharedNotificationContainerInteractor)
    }

    @Test
    fun validateMarginStartInSplitShade() = runTest {
        overrideResource(R.bool.config_use_split_notification_shade, true)
        overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

        val dimens = collectLastValue(underTest.configurationBasedDimensions)

        configurationRepository.onAnyConfigurationChange()
        runCurrent()

        val lastDimens = dimens()!!

        assertThat(lastDimens.marginStart).isEqualTo(0)
    }

    @Test
    fun validateMarginStart() = runTest {
        overrideResource(R.bool.config_use_split_notification_shade, false)
        overrideResource(R.dimen.notification_panel_margin_horizontal, 20)

        val dimens = collectLastValue(underTest.configurationBasedDimensions)

        configurationRepository.onAnyConfigurationChange()
        runCurrent()

        val lastDimens = dimens()!!

        assertThat(lastDimens.marginStart).isEqualTo(20)
    }

    @Test
    fun validateMarginEnd() = runTest {
        overrideResource(R.dimen.notification_panel_margin_horizontal, 50)

        val dimens = collectLastValue(underTest.configurationBasedDimensions)

        configurationRepository.onAnyConfigurationChange()
        runCurrent()

        val lastDimens = dimens()!!

        assertThat(lastDimens.marginEnd).isEqualTo(50)
    }

    @Test
    fun validateMarginBottom() = runTest {
        overrideResource(R.dimen.notification_panel_margin_bottom, 50)

        val dimens = collectLastValue(underTest.configurationBasedDimensions)

        configurationRepository.onAnyConfigurationChange()
        runCurrent()

        val lastDimens = dimens()!!

        assertThat(lastDimens.marginBottom).isEqualTo(50)
    }

    @Test
    fun validateMarginTopWithLargeScreenHeader() = runTest {
        overrideResource(R.bool.config_use_large_screen_shade_header, true)
        overrideResource(R.dimen.large_screen_shade_header_height, 50)
        overrideResource(R.dimen.notification_panel_margin_top, 0)

        val dimens = collectLastValue(underTest.configurationBasedDimensions)

        configurationRepository.onAnyConfigurationChange()
        runCurrent()

        val lastDimens = dimens()!!

        assertThat(lastDimens.marginTop).isEqualTo(50)
    }

    @Test
    fun validateMarginTop() = runTest {
        overrideResource(R.bool.config_use_large_screen_shade_header, false)
        overrideResource(R.dimen.large_screen_shade_header_height, 50)
        overrideResource(R.dimen.notification_panel_margin_top, 0)

        val dimens = collectLastValue(underTest.configurationBasedDimensions)

        configurationRepository.onAnyConfigurationChange()
        runCurrent()

        val lastDimens = dimens()!!

        assertThat(lastDimens.marginTop).isEqualTo(0)
    }
}
