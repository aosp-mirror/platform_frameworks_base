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

package com.android.systemui.keyguard.domain.usecase

import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.keyguard.data.quickaffordance.HomeControlsKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.repository.FakeKeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePosition
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@SmallTest
@RunWith(JUnit4::class)
class ObserveKeyguardQuickAffordanceUseCaseTest : SysuiTestCase() {

    private lateinit var underTest: ObserveKeyguardQuickAffordanceUseCase

    private lateinit var repository: FakeKeyguardRepository
    private lateinit var quickAffordanceRepository: FakeKeyguardQuickAffordanceRepository
    private lateinit var isDozingUseCase: ObserveIsDozingUseCase
    private lateinit var isKeyguardShowingUseCase: ObserveIsKeyguardShowingUseCase

    @Before
    fun setUp() {
        repository = FakeKeyguardRepository()
        repository.setKeyguardShowing(true)
        isDozingUseCase = ObserveIsDozingUseCase(repository)
        isKeyguardShowingUseCase = ObserveIsKeyguardShowingUseCase(repository)
        quickAffordanceRepository = FakeKeyguardQuickAffordanceRepository()

        underTest =
            ObserveKeyguardQuickAffordanceUseCase(
                repository = quickAffordanceRepository,
                isDozingUseCase = isDozingUseCase,
                isKeyguardShowingUseCase = isKeyguardShowingUseCase,
            )
    }

    @Test
    fun `invoke - affordance is visible`() = runBlockingTest {
        val configKey = HomeControlsKeyguardQuickAffordanceConfig::class
        val model =
            KeyguardQuickAffordanceModel.Visible(
                configKey = configKey,
                icon = ICON,
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
            )
        quickAffordanceRepository.setModel(
            KeyguardQuickAffordancePosition.BOTTOM_END,
            model,
        )

        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest(KeyguardQuickAffordancePosition.BOTTOM_END)
                .onEach { latest = it }
                .launchIn(this)

        assertThat(latest).isInstanceOf(KeyguardQuickAffordanceModel.Visible::class.java)
        val visibleModel = latest as KeyguardQuickAffordanceModel.Visible
        assertThat(visibleModel.configKey).isEqualTo(configKey)
        assertThat(visibleModel.icon).isEqualTo(ICON)
        assertThat(visibleModel.contentDescriptionResourceId)
            .isEqualTo(CONTENT_DESCRIPTION_RESOURCE_ID)
        job.cancel()
    }

    @Test
    fun `invoke - affordance not visible while dozing`() = runBlockingTest {
        repository.setDozing(true)
        val configKey = HomeControlsKeyguardQuickAffordanceConfig::class
        val model =
            KeyguardQuickAffordanceModel.Visible(
                configKey = configKey,
                icon = ICON,
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
            )
        quickAffordanceRepository.setModel(
            KeyguardQuickAffordancePosition.BOTTOM_END,
            model,
        )

        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest(KeyguardQuickAffordancePosition.BOTTOM_END)
                .onEach { latest = it }
                .launchIn(this)
        assertThat(latest).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        job.cancel()
    }

    @Test
    fun `invoke - affordance not visible when lockscreen is not showing`() = runBlockingTest {
        repository.setKeyguardShowing(false)
        val configKey = HomeControlsKeyguardQuickAffordanceConfig::class
        val model =
            KeyguardQuickAffordanceModel.Visible(
                configKey = configKey,
                icon = ICON,
                contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
            )
        quickAffordanceRepository.setModel(
            KeyguardQuickAffordancePosition.BOTTOM_END,
            model,
        )

        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest(KeyguardQuickAffordancePosition.BOTTOM_END)
                .onEach { latest = it }
                .launchIn(this)
        assertThat(latest).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        job.cancel()
    }

    @Test
    fun `invoke - affordance is none`() = runBlockingTest {
        quickAffordanceRepository.setModel(
            KeyguardQuickAffordancePosition.BOTTOM_START,
            KeyguardQuickAffordanceModel.Hidden,
        )

        var latest: KeyguardQuickAffordanceModel? = null
        val job =
            underTest(KeyguardQuickAffordancePosition.BOTTOM_START)
                .onEach { latest = it }
                .launchIn(this)
        assertThat(latest).isEqualTo(KeyguardQuickAffordanceModel.Hidden)
        job.cancel()
    }

    companion object {
        private val ICON: ContainedDrawable = mock()
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337
    }
}
