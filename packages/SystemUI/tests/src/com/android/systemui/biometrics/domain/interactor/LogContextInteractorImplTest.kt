package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.IBiometricContextListener
import android.hardware.biometrics.IBiometricContextListener.FoldState
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.WakefulnessLifecycle
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_OPENING
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
class LogContextInteractorImplTest : SysuiTestCase() {

    @JvmField @Rule var mockitoRule = MockitoJUnit.rule()

    private val testScope = TestScope()

    @Mock private lateinit var statusBarStateController: StatusBarStateController
    @Mock private lateinit var wakefulnessLifecycle: WakefulnessLifecycle
    @Mock private lateinit var foldProvider: FoldStateProvider

    private lateinit var interactor: LogContextInteractorImpl

    @Before
    fun setup() {
        interactor =
            LogContextInteractorImpl(
                testScope.backgroundScope,
                statusBarStateController,
                wakefulnessLifecycle,
                foldProvider
            )
    }

    @Test
    fun isDozingChanges() =
        testScope.runTest {
            whenever(statusBarStateController.isDozing).thenReturn(true)

            val isDozing = collectLastValue(interactor.isDozing)
            runCurrent()
            val listener = statusBarStateController.captureListener()

            assertThat(isDozing()).isTrue()

            listener.onDozingChanged(true)
            listener.onDozingChanged(true)
            listener.onDozingChanged(false)

            assertThat(isDozing()).isFalse()
        }

    @Test
    fun isAwakeChanges() =
        testScope.runTest {
            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE)

            val isAwake = collectLastValue(interactor.isAwake)
            runCurrent()
            val listener = wakefulnessLifecycle.captureObserver()

            assertThat(isAwake()).isTrue()

            listener.onStartedGoingToSleep()
            listener.onFinishedGoingToSleep()
            listener.onStartedWakingUp()

            assertThat(isAwake()).isFalse()

            listener.onFinishedWakingUp()
            listener.onPostFinishedWakingUp()

            assertThat(isAwake()).isTrue()
        }

    @Test
    fun foldStateChanges() =
        testScope.runTest {
            val foldState = collectLastValue(interactor.foldState)
            runCurrent()
            val listener = foldProvider.captureListener()

            listener.onFoldUpdate(FOLD_UPDATE_START_OPENING)
            assertThat(foldState()).isEqualTo(FoldState.UNKNOWN)

            listener.onFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN)
            assertThat(foldState()).isEqualTo(FoldState.HALF_OPENED)

            listener.onFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)
            assertThat(foldState()).isEqualTo(FoldState.FULLY_OPENED)

            listener.onFoldUpdate(FOLD_UPDATE_START_CLOSING)
            assertThat(foldState()).isEqualTo(FoldState.FULLY_OPENED)

            listener.onFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)
            assertThat(foldState()).isEqualTo(FoldState.FULLY_CLOSED)
        }

    @Test
    fun contextSubscriberChanges() =
        testScope.runTest {
            whenever(statusBarStateController.isDozing).thenReturn(false)
            whenever(wakefulnessLifecycle.wakefulness)
                .thenReturn(WakefulnessLifecycle.WAKEFULNESS_AWAKE)
            runCurrent()

            val foldListener = foldProvider.captureListener()
            foldListener.onFoldUpdate(FOLD_UPDATE_START_CLOSING)
            foldListener.onFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)

            var dozing: Boolean? = null
            var awake: Boolean? = null
            var folded: Int? = null
            val job =
                interactor.addBiometricContextListener(
                    object : IBiometricContextListener.Stub() {
                        override fun onDozeChanged(isDozing: Boolean, isAwake: Boolean) {
                            dozing = isDozing
                            awake = isAwake
                        }

                        override fun onFoldChanged(foldState: Int) {
                            folded = foldState
                        }
                    }
                )
            runCurrent()

            val statusBarStateListener = statusBarStateController.captureListener()
            val wakefullnessObserver = wakefulnessLifecycle.captureObserver()

            assertThat(dozing).isFalse()
            assertThat(awake).isTrue()
            assertThat(folded).isEqualTo(FoldState.FULLY_CLOSED)

            statusBarStateListener.onDozingChanged(true)
            wakefullnessObserver.onStartedGoingToSleep()
            foldListener.onFoldUpdate(FOLD_UPDATE_START_OPENING)
            foldListener.onFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN)
            wakefullnessObserver.onFinishedGoingToSleep()
            runCurrent()

            assertThat(dozing).isTrue()
            assertThat(awake).isFalse()
            assertThat(folded).isEqualTo(FoldState.HALF_OPENED)

            job.cancel()

            // stale updates should be ignored
            statusBarStateListener.onDozingChanged(false)
            wakefullnessObserver.onFinishedWakingUp()
            foldListener.onFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)
            runCurrent()

            assertThat(dozing).isTrue()
            assertThat(awake).isFalse()
            assertThat(folded).isEqualTo(FoldState.HALF_OPENED)
        }
}

private fun StatusBarStateController.captureListener() =
    withArgCaptor<StatusBarStateController.StateListener> {
        verify(this@captureListener).addCallback(capture())
    }

private fun WakefulnessLifecycle.captureObserver() =
    withArgCaptor<WakefulnessLifecycle.Observer> {
        verify(this@captureObserver).addObserver(capture())
    }

private fun FoldStateProvider.captureListener() =
    withArgCaptor<FoldStateProvider.FoldUpdatesListener> {
        verify(this@captureListener).addCallback(capture())
    }
