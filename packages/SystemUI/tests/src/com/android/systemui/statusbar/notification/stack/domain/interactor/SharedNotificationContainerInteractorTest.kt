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
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.common.ui.data.repository.FakeConfigurationRepository
import com.android.systemui.coroutines.collectLastValue
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class SharedNotificationContainerInteractorTest : SysuiTestCase() {
    private lateinit var configurationRepository: FakeConfigurationRepository
    private lateinit var underTest: SharedNotificationContainerInteractor

    @Before
    fun setUp() {
        configurationRepository = FakeConfigurationRepository()
        underTest =
            SharedNotificationContainerInteractor(
                configurationRepository,
                mContext,
            )
    }

    @Test
    fun validateConfigValues() = runTest {
        overrideResource(R.bool.config_use_split_notification_shade, true)
        overrideResource(R.bool.config_use_large_screen_shade_header, false)
        overrideResource(R.dimen.notification_panel_margin_horizontal, 0)
        overrideResource(R.dimen.notification_panel_margin_bottom, 10)
        overrideResource(R.dimen.notification_panel_margin_top, 10)
        overrideResource(R.dimen.large_screen_shade_header_height, 0)

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
    }
}
