package com.android.systemui.biometrics.ui.viewmodel

import android.content.res.Configuration
import androidx.test.filters.SmallTest
import com.android.internal.widget.LockPatternUtils
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.data.repository.FakeFingerprintPropertyRepository
import com.android.systemui.biometrics.data.repository.FakePromptRepository
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractor
import com.android.systemui.biometrics.domain.interactor.PromptSelectorInteractorImpl
import com.android.systemui.biometrics.shared.model.FingerprintSensorType
import com.android.systemui.biometrics.shared.model.SensorStrength
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.time.FakeSystemClock
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class PromptFingerprintIconViewModelTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var lockPatternUtils: LockPatternUtils

    private val fingerprintRepository = FakeFingerprintPropertyRepository()
    private val promptRepository = FakePromptRepository()
    private val displayStateRepository = FakeDisplayStateRepository()

    private val testScope = TestScope(StandardTestDispatcher())
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    private lateinit var promptSelectorInteractor: PromptSelectorInteractor
    private lateinit var displayStateInteractor: DisplayStateInteractor
    private lateinit var viewModel: PromptFingerprintIconViewModel

    @Before
    fun setup() {
        promptSelectorInteractor =
            PromptSelectorInteractorImpl(fingerprintRepository, promptRepository, lockPatternUtils)
        displayStateInteractor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                mContext,
                fakeExecutor,
                displayStateRepository
            )
        viewModel = PromptFingerprintIconViewModel(displayStateInteractor, promptSelectorInteractor)
    }

    @Test
    fun sfpsIconUpdates_onConfigurationChanged() {
        testScope.runTest {
            runCurrent()
            configureFingerprintPropertyRepository(FingerprintSensorType.POWER_BUTTON)
            val testConfig = Configuration()
            val folded = INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP - 1
            val unfolded = INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP + 1
            val currentIcon = collectLastValue(viewModel.iconAsset)

            testConfig.smallestScreenWidthDp = folded
            viewModel.onConfigurationChanged(testConfig)
            val foldedIcon = currentIcon()

            testConfig.smallestScreenWidthDp = unfolded
            viewModel.onConfigurationChanged(testConfig)
            val unfoldedIcon = currentIcon()

            assertThat(foldedIcon).isNotEqualTo(unfoldedIcon)
        }
    }

    private fun configureFingerprintPropertyRepository(sensorType: FingerprintSensorType) {
        fingerprintRepository.setProperties(0, SensorStrength.STRONG, sensorType, mapOf())
    }
}

internal const val INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP = 600
