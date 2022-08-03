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

package com.android.systemui.keyguard.ui.viewmodel

import android.content.Intent
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.data.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.repository.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.data.repository.FakeKeyguardQuickAffordanceConfigs
import com.android.systemui.keyguard.data.repository.FakeKeyguardQuickAffordanceRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.usecase.FakeLaunchKeyguardQuickAffordanceUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveAnimateBottomAreaTransitionsUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveBottomAreaAlphaUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveClockPositionUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveDozeAmountUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveIsDozingUseCase
import com.android.systemui.keyguard.domain.usecase.ObserveKeyguardQuickAffordanceUseCase
import com.android.systemui.keyguard.domain.usecase.OnKeyguardQuickAffordanceClickedUseCase
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordanceModel
import com.android.systemui.keyguard.shared.model.KeyguardQuickAffordancePosition
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardBottomAreaViewModelTest : SysuiTestCase() {

    @Mock private lateinit var animationController: ActivityLaunchAnimator.Controller
    @Mock private lateinit var burnInHelperWrapper: BurnInHelperWrapper

    private lateinit var underTest: KeyguardBottomAreaViewModel

    private lateinit var affordanceRepository: FakeKeyguardQuickAffordanceRepository
    private lateinit var repository: FakeKeyguardRepository
    private lateinit var isDozingUseCase: ObserveIsDozingUseCase
    private lateinit var dozeAmountUseCase: ObserveDozeAmountUseCase
    private lateinit var launchQuickAffordanceUseCase: FakeLaunchKeyguardQuickAffordanceUseCase
    private lateinit var homeControlsQuickAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWalletAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScannerAffordanceConfig: FakeKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(burnInHelperWrapper.burnInOffset(anyInt(), any()))
            .thenReturn(RETURNED_BURN_IN_OFFSET)

        affordanceRepository = FakeKeyguardQuickAffordanceRepository()
        repository = FakeKeyguardRepository()
        isDozingUseCase =
            ObserveIsDozingUseCase(
                repository = repository,
            )
        dozeAmountUseCase =
            ObserveDozeAmountUseCase(
                repository = repository,
            )
        launchQuickAffordanceUseCase = FakeLaunchKeyguardQuickAffordanceUseCase()
        homeControlsQuickAffordanceConfig = object : FakeKeyguardQuickAffordanceConfig() {}
        quickAccessWalletAffordanceConfig = object : FakeKeyguardQuickAffordanceConfig() {}
        qrCodeScannerAffordanceConfig = object : FakeKeyguardQuickAffordanceConfig() {}

        underTest =
            KeyguardBottomAreaViewModel(
                observeQuickAffordanceUseCase =
                    ObserveKeyguardQuickAffordanceUseCase(
                        repository = affordanceRepository,
                        isDozingUseCase = isDozingUseCase,
                        dozeAmountUseCase = dozeAmountUseCase,
                    ),
                onQuickAffordanceClickedUseCase =
                    OnKeyguardQuickAffordanceClickedUseCase(
                        configs =
                            FakeKeyguardQuickAffordanceConfigs(
                                mapOf(
                                    KeyguardQuickAffordancePosition.BOTTOM_START to
                                        listOf(
                                            homeControlsQuickAffordanceConfig,
                                        ),
                                    KeyguardQuickAffordancePosition.BOTTOM_END to
                                        listOf(
                                            quickAccessWalletAffordanceConfig,
                                            qrCodeScannerAffordanceConfig,
                                        ),
                                ),
                            ),
                        launchAffordanceUseCase = launchQuickAffordanceUseCase,
                    ),
                observeBottomAreaAlphaUseCase =
                    ObserveBottomAreaAlphaUseCase(
                        repository = repository,
                    ),
                observeIsDozingUseCase = isDozingUseCase,
                observeAnimateBottomAreaTransitionsUseCase =
                    ObserveAnimateBottomAreaTransitionsUseCase(
                        repository = repository,
                    ),
                observeDozeAmountUseCase =
                    ObserveDozeAmountUseCase(
                        repository = repository,
                    ),
                observeClockPositionUseCase =
                    ObserveClockPositionUseCase(
                        repository = repository,
                    ),
                burnInHelperWrapper = burnInHelperWrapper,
            )
    }

    @Test
    fun `startButton - present and not dozing - visible model - starts activity on click`() =
        runBlockingTest {
            var latest: KeyguardQuickAffordanceViewModel? = null
            val job = underTest.startButton.onEach { latest = it }.launchIn(this)

            repository.setDozing(false)
            val testConfig =
                TestConfig(
                    isVisible = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent = Intent("action"),
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_START,
                    testConfig = testConfig,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest,
                testConfig = testConfig,
                configKey = configKey,
            )
            job.cancel()
        }

    @Test
    fun `endButton - present and not dozing - visible model - do nothing on click`() =
        runBlockingTest {
            var latest: KeyguardQuickAffordanceViewModel? = null
            val job = underTest.endButton.onEach { latest = it }.launchIn(this)

            repository.setDozing(false)
            val config =
                TestConfig(
                    isVisible = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                    intent =
                        null, // This will cause it to tell the system that the click was handled.
                )
            val configKey =
                setUpQuickAffordanceModel(
                    position = KeyguardQuickAffordancePosition.BOTTOM_END,
                    testConfig = config,
                )

            assertQuickAffordanceViewModel(
                viewModel = latest,
                testConfig = config,
                configKey = configKey,
            )
            job.cancel()
        }

    @Test
    fun `startButton - not present and not dozing - model is none`() = runBlockingTest {
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)

        repository.setDozing(false)
        val config =
            TestConfig(
                isVisible = false,
            )
        val configKey =
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_START,
                testConfig = config,
            )

        assertQuickAffordanceViewModel(
            viewModel = latest,
            testConfig = config,
            configKey = configKey,
        )
        job.cancel()
    }

    @Test
    fun `startButton - present but dozing - model is none`() = runBlockingTest {
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)

        repository.setDozing(true)
        val config =
            TestConfig(
                isVisible = true,
                icon = mock(),
                canShowWhileLocked = false,
                intent = Intent("action"),
            )
        val configKey =
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_START,
                testConfig = config,
            )

        assertQuickAffordanceViewModel(
            viewModel = latest,
            testConfig = TestConfig(isVisible = false),
            configKey = configKey,
        )
        job.cancel()
    }

    @Test
    fun animateButtonReveal() = runBlockingTest {
        val values = mutableListOf<Boolean>()
        val job = underTest.animateButtonReveal.onEach(values::add).launchIn(this)

        repository.setAnimateDozingTransitions(true)
        repository.setAnimateDozingTransitions(false)

        assertThat(values).isEqualTo(listOf(false, true, false))
        job.cancel()
    }

    @Test
    fun isOverlayContainerVisible() = runBlockingTest {
        val values = mutableListOf<Boolean>()
        val job = underTest.isOverlayContainerVisible.onEach(values::add).launchIn(this)

        repository.setDozing(true)
        repository.setDozing(false)

        assertThat(values).isEqualTo(listOf(true, false, true))
        job.cancel()
    }

    @Test
    fun alpha() = runBlockingTest {
        val values = mutableListOf<Float>()
        val job = underTest.alpha.onEach(values::add).launchIn(this)

        repository.setBottomAreaAlpha(0.1f)
        repository.setBottomAreaAlpha(0.5f)
        repository.setBottomAreaAlpha(0.2f)
        repository.setBottomAreaAlpha(0f)

        assertThat(values).isEqualTo(listOf(1f, 0.1f, 0.5f, 0.2f, 0f))
        job.cancel()
    }

    @Test
    fun isIndicationAreaPadded() = runBlockingTest {
        val values = mutableListOf<Boolean>()
        val job = underTest.isIndicationAreaPadded.onEach(values::add).launchIn(this)

        setUpQuickAffordanceModel(
            position = KeyguardQuickAffordancePosition.BOTTOM_START,
            testConfig =
                TestConfig(
                    isVisible = true,
                    icon = mock(),
                    canShowWhileLocked = true,
                )
        )
        setUpQuickAffordanceModel(
            position = KeyguardQuickAffordancePosition.BOTTOM_END,
            testConfig =
                TestConfig(
                    isVisible = true,
                    icon = mock(),
                    canShowWhileLocked = false,
                )
        )
        setUpQuickAffordanceModel(
            position = KeyguardQuickAffordancePosition.BOTTOM_START,
            testConfig =
                TestConfig(
                    isVisible = false,
                )
        )
        setUpQuickAffordanceModel(
            position = KeyguardQuickAffordancePosition.BOTTOM_END,
            testConfig =
                TestConfig(
                    isVisible = false,
                )
        )

        assertThat(values)
            .isEqualTo(
                listOf(
                    // Initially, no button is visible so the indication area is not padded.
                    false,
                    // Once we add the first visible button, the indication area becomes padded.
                    // This
                    // continues to be true after we add the second visible button and even after we
                    // make the first button not visible anymore.
                    true,
                    // Once both buttons are not visible, the indication area is, again, not padded.
                    false,
                )
            )
        job.cancel()
    }

    @Test
    fun indicationAreaTranslationX() = runBlockingTest {
        val values = mutableListOf<Float>()
        val job = underTest.indicationAreaTranslationX.onEach(values::add).launchIn(this)

        repository.setClockPosition(100, 100)
        repository.setClockPosition(200, 100)
        repository.setClockPosition(200, 200)
        repository.setClockPosition(300, 100)

        assertThat(values).isEqualTo(listOf(0f, 100f, 200f, 300f))
        job.cancel()
    }

    @Test
    fun indicationAreaTranslationY() = runBlockingTest {
        val values = mutableListOf<Float>()
        val job =
            underTest
                .indicationAreaTranslationY(DEFAULT_BURN_IN_OFFSET)
                .onEach(values::add)
                .launchIn(this)

        val expectedTranslationValues =
            listOf(
                -0f, // Negative 0 - apparently there's a difference in floating point arithmetic -
                // FML
                setDozeAmountAndCalculateExpectedTranslationY(0.1f),
                setDozeAmountAndCalculateExpectedTranslationY(0.2f),
                setDozeAmountAndCalculateExpectedTranslationY(0.5f),
                setDozeAmountAndCalculateExpectedTranslationY(1f),
            )

        assertThat(values).isEqualTo(expectedTranslationValues)
        job.cancel()
    }

    private fun setDozeAmountAndCalculateExpectedTranslationY(dozeAmount: Float): Float {
        repository.setDozeAmount(dozeAmount)
        return dozeAmount * (RETURNED_BURN_IN_OFFSET - DEFAULT_BURN_IN_OFFSET)
    }

    private suspend fun setUpQuickAffordanceModel(
        position: KeyguardQuickAffordancePosition,
        testConfig: TestConfig,
    ): KClass<*> {
        val config =
            when (position) {
                KeyguardQuickAffordancePosition.BOTTOM_START -> homeControlsQuickAffordanceConfig
                KeyguardQuickAffordancePosition.BOTTOM_END -> quickAccessWalletAffordanceConfig
            }

        affordanceRepository.setModel(
            position = position,
            model =
                if (testConfig.isVisible) {
                    if (testConfig.intent != null) {
                        config.onClickedResult =
                            KeyguardQuickAffordanceConfig.OnClickedResult.StartActivity(
                                intent = testConfig.intent,
                                canShowWhileLocked = testConfig.canShowWhileLocked,
                            )
                    }
                    KeyguardQuickAffordanceModel.Visible(
                        configKey = config::class,
                        icon = testConfig.icon ?: error("Icon is unexpectedly null!"),
                        contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
                    )
                } else {
                    KeyguardQuickAffordanceModel.Hidden
                }
        )
        return config::class
    }

    private fun assertQuickAffordanceViewModel(
        viewModel: KeyguardQuickAffordanceViewModel?,
        testConfig: TestConfig,
        configKey: KClass<*>,
    ) {
        checkNotNull(viewModel)
        assertThat(viewModel.isVisible).isEqualTo(testConfig.isVisible)
        if (testConfig.isVisible) {
            assertThat(viewModel.icon).isEqualTo(testConfig.icon)
            viewModel.onClicked.invoke(
                KeyguardQuickAffordanceViewModel.OnClickedParameters(
                    configKey = configKey,
                    animationController = animationController,
                )
            )
            testConfig.intent?.let { intent ->
                assertThat(launchQuickAffordanceUseCase.invocations)
                    .isEqualTo(
                        listOf(
                            FakeLaunchKeyguardQuickAffordanceUseCase.Invocation(
                                intent = intent,
                                canShowWhileLocked = testConfig.canShowWhileLocked,
                                animationController = animationController,
                            )
                        )
                    )
            }
                ?: run { assertThat(launchQuickAffordanceUseCase.invocations).isEmpty() }
        } else {
            assertThat(viewModel.isVisible).isFalse()
        }
    }

    private data class TestConfig(
        val isVisible: Boolean,
        val icon: ContainedDrawable? = null,
        val canShowWhileLocked: Boolean = false,
        val intent: Intent? = null,
    ) {
        init {
            check(!isVisible || icon != null) { "Must supply non-null icon if visible!" }
        }
    }

    companion object {
        private const val DEFAULT_BURN_IN_OFFSET = 5
        private const val RETURNED_BURN_IN_OFFSET = 3
        private const val CONTENT_DESCRIPTION_RESOURCE_ID = 1337
    }
}
