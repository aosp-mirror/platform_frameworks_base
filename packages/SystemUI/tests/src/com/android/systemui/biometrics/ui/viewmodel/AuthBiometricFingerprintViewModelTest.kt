package com.android.systemui.biometrics.ui.viewmodel

import android.content.res.Configuration
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeRearDisplayStateRepository
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractor
import com.android.systemui.biometrics.domain.interactor.DisplayStateInteractorImpl
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
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class AuthBiometricFingerprintViewModelTest : SysuiTestCase() {

    private val rearDisplayStateRepository = FakeRearDisplayStateRepository()
    private val testScope = TestScope(StandardTestDispatcher())
    private val fakeExecutor = FakeExecutor(FakeSystemClock())

    private lateinit var interactor: DisplayStateInteractor
    private lateinit var viewModel: AuthBiometricFingerprintViewModel

    @Before
    fun setup() {
        interactor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                mContext,
                fakeExecutor,
                rearDisplayStateRepository
            )
        viewModel = AuthBiometricFingerprintViewModel(interactor)
    }

    @Test
    fun iconUpdates_onConfigurationChanged() {
        testScope.runTest {
            runCurrent()
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
}

internal const val INNER_SCREEN_SMALLEST_SCREEN_WIDTH_THRESHOLD_DP = 600
