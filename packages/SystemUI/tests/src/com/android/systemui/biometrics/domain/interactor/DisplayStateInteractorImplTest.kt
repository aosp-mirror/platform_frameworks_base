package com.android.systemui.biometrics.domain.interactor

import android.view.Display
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.data.repository.FakeDisplayStateRepository
import com.android.systemui.biometrics.shared.model.DisplayRotation
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.display.data.repository.FakeDisplayRepository
import com.android.systemui.display.data.repository.display
import com.android.systemui.unfold.compat.ScreenSizeFoldProvider
import com.android.systemui.unfold.updates.FoldProvider
import com.android.systemui.util.concurrency.FakeExecutor
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.withArgCaptor
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
import org.mockito.Mockito.verify
import org.mockito.junit.MockitoJUnit

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class DisplayStateInteractorImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private val fakeExecutor = FakeExecutor(FakeSystemClock())
    private val testScope = TestScope(StandardTestDispatcher())
    private lateinit var displayStateRepository: FakeDisplayStateRepository
    private lateinit var displayRepository: FakeDisplayRepository

    @Mock private lateinit var screenSizeFoldProvider: ScreenSizeFoldProvider
    private lateinit var interactor: DisplayStateInteractorImpl

    @Before
    fun setup() {
        displayStateRepository = FakeDisplayStateRepository()
        displayRepository = FakeDisplayRepository()
        interactor =
            DisplayStateInteractorImpl(
                testScope.backgroundScope,
                mContext,
                fakeExecutor,
                displayStateRepository,
                displayRepository,
            )
        interactor.setScreenSizeFoldProvider(screenSizeFoldProvider)
    }

    @Test
    fun isInRearDisplayModeChanges() =
        testScope.runTest {
            val isInRearDisplayMode by collectLastValue(interactor.isInRearDisplayMode)

            displayStateRepository.setIsInRearDisplayMode(false)
            assertThat(isInRearDisplayMode).isFalse()

            displayStateRepository.setIsInRearDisplayMode(true)
            assertThat(isInRearDisplayMode).isTrue()
        }

    @Test
    fun currentRotationChanges() =
        testScope.runTest {
            val currentRotation by collectLastValue(interactor.currentRotation)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_180)
            assertThat(currentRotation).isEqualTo(DisplayRotation.ROTATION_180)

            displayStateRepository.setCurrentRotation(DisplayRotation.ROTATION_90)
            assertThat(currentRotation).isEqualTo(DisplayRotation.ROTATION_90)
        }

    @Test
    fun isFoldedChanges() =
        testScope.runTest {
            val isFolded by collectLastValue(interactor.isFolded)
            runCurrent()
            val callback = screenSizeFoldProvider.captureCallback()

            callback.onFoldUpdated(isFolded = true)
            assertThat(isFolded).isTrue()

            callback.onFoldUpdated(isFolded = false)
            assertThat(isFolded).isFalse()
        }

    @Test
    fun isDefaultDisplayOffChanges() =
        testScope.runTest {
            val isDefaultDisplayOff by collectLastValue(interactor.isDefaultDisplayOff)
            runCurrent()

            displayRepository.emit(setOf(display(0, 0, Display.DEFAULT_DISPLAY, Display.STATE_OFF)))
            displayRepository.emitDisplayChangeEvent(Display.DEFAULT_DISPLAY)
            assertThat(isDefaultDisplayOff).isTrue()

            displayRepository.emit(setOf(display(0, 0, Display.DEFAULT_DISPLAY, Display.STATE_ON)))
            displayRepository.emitDisplayChangeEvent(Display.DEFAULT_DISPLAY)
            assertThat(isDefaultDisplayOff).isFalse()
        }
}

private fun FoldProvider.captureCallback() =
    withArgCaptor<FoldProvider.FoldCallback> {
        verify(this@captureCallback).registerCallback(capture(), any())
    }
