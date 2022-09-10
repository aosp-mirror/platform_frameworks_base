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
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.ActivityLaunchAnimator
import com.android.systemui.containeddrawable.ContainedDrawable
import com.android.systemui.doze.util.BurnInHelperWrapper
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardBottomAreaInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardQuickAffordanceInteractor
import com.android.systemui.keyguard.domain.model.KeyguardQuickAffordancePosition
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceConfig
import com.android.systemui.keyguard.domain.quickaffordance.FakeKeyguardQuickAffordanceRegistry
import com.android.systemui.keyguard.domain.quickaffordance.KeyguardQuickAffordanceConfig
import com.android.systemui.plugins.ActivityStarter
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.mock
import com.google.common.truth.Truth.assertThat
import kotlin.math.max
import kotlin.math.min
import kotlin.reflect.KClass
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runBlockingTest
import kotlinx.coroutines.yield
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito
import org.mockito.Mockito.verifyZeroInteractions
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(JUnit4::class)
class KeyguardBottomAreaViewModelTest : SysuiTestCase() {

    @Mock private lateinit var animationController: ActivityLaunchAnimator.Controller
    @Mock private lateinit var burnInHelperWrapper: BurnInHelperWrapper
    @Mock private lateinit var lockPatternUtils: LockPatternUtils
    @Mock private lateinit var keyguardStateController: KeyguardStateController
    @Mock private lateinit var userTracker: UserTracker
    @Mock private lateinit var activityStarter: ActivityStarter

    private lateinit var underTest: KeyguardBottomAreaViewModel

    private lateinit var repository: FakeKeyguardRepository
    private lateinit var registry: FakeKeyguardQuickAffordanceRegistry
    private lateinit var homeControlsQuickAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var quickAccessWalletAffordanceConfig: FakeKeyguardQuickAffordanceConfig
    private lateinit var qrCodeScannerAffordanceConfig: FakeKeyguardQuickAffordanceConfig

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        whenever(burnInHelperWrapper.burnInOffset(anyInt(), any()))
            .thenReturn(RETURNED_BURN_IN_OFFSET)

        homeControlsQuickAffordanceConfig = object : FakeKeyguardQuickAffordanceConfig() {}
        quickAccessWalletAffordanceConfig = object : FakeKeyguardQuickAffordanceConfig() {}
        qrCodeScannerAffordanceConfig = object : FakeKeyguardQuickAffordanceConfig() {}
        registry =
            FakeKeyguardQuickAffordanceRegistry(
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
            )
        repository = FakeKeyguardRepository()

        val keyguardInteractor = KeyguardInteractor(repository = repository)
        whenever(userTracker.userHandle).thenReturn(mock())
        whenever(lockPatternUtils.getStrongAuthForUser(anyInt()))
            .thenReturn(LockPatternUtils.StrongAuthTracker.STRONG_AUTH_NOT_REQUIRED)
        underTest =
            KeyguardBottomAreaViewModel(
                keyguardInteractor = keyguardInteractor,
                quickAffordanceInteractor =
                    KeyguardQuickAffordanceInteractor(
                        keyguardInteractor = keyguardInteractor,
                        registry = registry,
                        lockPatternUtils = lockPatternUtils,
                        keyguardStateController = keyguardStateController,
                        userTracker = userTracker,
                        activityStarter = activityStarter,
                    ),
                bottomAreaInteractor = KeyguardBottomAreaInteractor(repository = repository),
                burnInHelperWrapper = burnInHelperWrapper,
            )
    }

    @Test
    fun `startButton - present - visible model - starts activity on click`() = runBlockingTest {
        repository.setKeyguardShowing(true)
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)

        val testConfig =
            TestConfig(
                isVisible = true,
                isClickable = true,
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
    fun `endButton - present - visible model - do nothing on click`() = runBlockingTest {
        repository.setKeyguardShowing(true)
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.endButton.onEach { latest = it }.launchIn(this)

        val config =
            TestConfig(
                isVisible = true,
                isClickable = true,
                icon = mock(),
                canShowWhileLocked = false,
                intent = null, // This will cause it to tell the system that the click was handled.
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
    fun `startButton - not present - model is hidden`() = runBlockingTest {
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)

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
    fun animateButtonReveal() = runBlockingTest {
        repository.setKeyguardShowing(true)
        val testConfig =
            TestConfig(
                isVisible = true,
                isClickable = true,
                icon = mock(),
                canShowWhileLocked = false,
                intent = Intent("action"),
            )

        setUpQuickAffordanceModel(
            position = KeyguardQuickAffordancePosition.BOTTOM_START,
            testConfig = testConfig,
        )

        val values = mutableListOf<Boolean>()
        val job = underTest.startButton.onEach { values.add(it.animateReveal) }.launchIn(this)

        repository.setAnimateDozingTransitions(true)
        yield()
        repository.setAnimateDozingTransitions(false)
        yield()

        // Note the extra false value in the beginning. This is to cover for the initial value
        // inserted by the quick affordance interactor which it does to cover for config
        // implementations that don't emit an initial value.
        assertThat(values).isEqualTo(listOf(false, false, true, false))
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
        repository.setKeyguardShowing(true)
        val values = mutableListOf<Boolean>()
        val job = underTest.isIndicationAreaPadded.onEach(values::add).launchIn(this)

        setUpQuickAffordanceModel(
            position = KeyguardQuickAffordancePosition.BOTTOM_START,
            testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
                    icon = mock(),
                    canShowWhileLocked = true,
                )
        )
        setUpQuickAffordanceModel(
            position = KeyguardQuickAffordancePosition.BOTTOM_END,
            testConfig =
                TestConfig(
                    isVisible = true,
                    isClickable = true,
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

    @Test
    fun `isClickable - true when alpha at threshold`() = runBlockingTest {
        repository.setKeyguardShowing(true)
        repository.setBottomAreaAlpha(
            KeyguardBottomAreaViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD
        )

        val testConfig =
            TestConfig(
                isVisible = true,
                isClickable = true,
                icon = mock(),
                canShowWhileLocked = false,
                intent = Intent("action"),
            )
        val configKey =
            setUpQuickAffordanceModel(
                position = KeyguardQuickAffordancePosition.BOTTOM_START,
                testConfig = testConfig,
            )

        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)
        // The interactor has an onStart { emit(Hidden) } to cover for upstream configs that don't
        // produce an initial value. We yield to give the coroutine time to emit the first real
        // value from our config.
        yield()

        assertQuickAffordanceViewModel(
            viewModel = latest,
            testConfig = testConfig,
            configKey = configKey,
        )
        job.cancel()
    }

    @Test
    fun `isClickable - true when alpha above threshold`() = runBlockingTest {
        repository.setKeyguardShowing(true)
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)
        repository.setBottomAreaAlpha(
            min(1f, KeyguardBottomAreaViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD + 0.1f),
        )

        val testConfig =
            TestConfig(
                isVisible = true,
                isClickable = true,
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
    fun `isClickable - false when alpha below threshold`() = runBlockingTest {
        repository.setKeyguardShowing(true)
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)
        repository.setBottomAreaAlpha(
            max(0f, KeyguardBottomAreaViewModel.AFFORDANCE_FULLY_OPAQUE_ALPHA_THRESHOLD - 0.1f),
        )

        val testConfig =
            TestConfig(
                isVisible = true,
                isClickable = false,
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
    fun `isClickable - false when alpha at zero`() = runBlockingTest {
        repository.setKeyguardShowing(true)
        var latest: KeyguardQuickAffordanceViewModel? = null
        val job = underTest.startButton.onEach { latest = it }.launchIn(this)
        repository.setBottomAreaAlpha(0f)

        val testConfig =
            TestConfig(
                isVisible = true,
                isClickable = false,
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

    private suspend fun setDozeAmountAndCalculateExpectedTranslationY(dozeAmount: Float): Float {
        repository.setDozeAmount(dozeAmount)
        return dozeAmount * (RETURNED_BURN_IN_OFFSET - DEFAULT_BURN_IN_OFFSET)
    }

    private suspend fun setUpQuickAffordanceModel(
        position: KeyguardQuickAffordancePosition,
        testConfig: TestConfig,
    ): KClass<out FakeKeyguardQuickAffordanceConfig> {
        val config =
            when (position) {
                KeyguardQuickAffordancePosition.BOTTOM_START -> homeControlsQuickAffordanceConfig
                KeyguardQuickAffordancePosition.BOTTOM_END -> quickAccessWalletAffordanceConfig
            }

        val state =
            if (testConfig.isVisible) {
                if (testConfig.intent != null) {
                    config.onClickedResult =
                        KeyguardQuickAffordanceConfig.OnClickedResult.StartActivity(
                            intent = testConfig.intent,
                            canShowWhileLocked = testConfig.canShowWhileLocked,
                        )
                }
                KeyguardQuickAffordanceConfig.State.Visible(
                    icon = testConfig.icon ?: error("Icon is unexpectedly null!"),
                    contentDescriptionResourceId = CONTENT_DESCRIPTION_RESOURCE_ID,
                )
            } else {
                KeyguardQuickAffordanceConfig.State.Hidden
            }
        config.setState(state)
        return config::class
    }

    private fun assertQuickAffordanceViewModel(
        viewModel: KeyguardQuickAffordanceViewModel?,
        testConfig: TestConfig,
        configKey: KClass<out FakeKeyguardQuickAffordanceConfig>,
    ) {
        checkNotNull(viewModel)
        assertThat(viewModel.isVisible).isEqualTo(testConfig.isVisible)
        assertThat(viewModel.isClickable).isEqualTo(testConfig.isClickable)
        if (testConfig.isVisible) {
            assertThat(viewModel.icon).isEqualTo(testConfig.icon)
            viewModel.onClicked.invoke(
                KeyguardQuickAffordanceViewModel.OnClickedParameters(
                    configKey = configKey,
                    animationController = animationController,
                )
            )
            if (testConfig.intent != null) {
                assertThat(Mockito.mockingDetails(activityStarter).invocations).hasSize(1)
            } else {
                verifyZeroInteractions(activityStarter)
            }
        } else {
            assertThat(viewModel.isVisible).isFalse()
        }
    }

    private data class TestConfig(
        val isVisible: Boolean,
        val isClickable: Boolean = false,
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
