package com.android.systemui.biometrics.domain.interactor

import android.hardware.biometrics.AuthenticateOptions
import android.hardware.biometrics.IBiometricContextListener
import android.hardware.biometrics.IBiometricContextListener.FoldState
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractorFactory
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_CLOSED
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_FULL_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_FINISH_HALF_OPEN
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_CLOSING
import com.android.systemui.unfold.updates.FOLD_UPDATE_START_OPENING
import com.android.systemui.unfold.updates.FoldStateProvider
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
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

    @Mock private lateinit var foldProvider: FoldStateProvider
    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var selectedUserInteractor: SelectedUserInteractor

    private lateinit var udfpsOverlayInteractor: UdfpsOverlayInteractor
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository

    private lateinit var interactor: LogContextInteractorImpl

    @Before
    fun setup() {
        keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        udfpsOverlayInteractor =
            UdfpsOverlayInteractor(
                context,
                authController,
                selectedUserInteractor,
                testScope.backgroundScope,
            )
        interactor =
            LogContextInteractorImpl(
                testScope.backgroundScope,
                foldProvider,
                KeyguardTransitionInteractorFactory.create(
                        repository = keyguardTransitionRepository,
                        scope = testScope.backgroundScope,
                    )
                    .keyguardTransitionInteractor,
                udfpsOverlayInteractor,
            )
    }

    @Test
    fun isAodChanges() =
        testScope.runTest {
            val isAod = collectLastValue(interactor.isAod)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OFF)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DOZING)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DREAMING)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            assertThat(isAod()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.GONE)
            assertThat(isAod()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OCCLUDED)
            assertThat(isAod()).isFalse()
        }

    @Test
    fun isAwakeChanges() =
        testScope.runTest {
            val isAwake = collectLastValue(interactor.isAwake)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OFF)
            assertThat(isAwake()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DOZING)
            assertThat(isAwake()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DREAMING)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            assertThat(isAwake()).isFalse()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.GONE)
            assertThat(isAwake()).isTrue()

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OCCLUDED)
            assertThat(isAwake()).isTrue()
        }

    @Test
    fun displayStateChanges() =
        testScope.runTest {
            val displayState = collectLastValue(interactor.displayState)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OFF)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_NO_UI)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DOZING)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_NO_UI)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.DREAMING)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_SCREENSAVER)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_AOD)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.GONE)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_UNKNOWN)

            keyguardTransitionRepository.startTransitionTo(KeyguardState.OCCLUDED)
            assertThat(displayState()).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)
        }

    @Test
    fun isHardwareIgnoringTouchesChanges() =
        testScope.runTest {
            val isHardwareIgnoringTouches by collectLastValue(interactor.isHardwareIgnoringTouches)

            udfpsOverlayInteractor.setHandleTouches(true)
            assertThat(isHardwareIgnoringTouches).isFalse()

            udfpsOverlayInteractor.setHandleTouches(false)
            assertThat(isHardwareIgnoringTouches).isTrue()
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
            runCurrent()
            val foldListener = foldProvider.captureListener()
            foldListener.onFoldUpdate(FOLD_UPDATE_START_CLOSING)
            foldListener.onFoldUpdate(FOLD_UPDATE_FINISH_CLOSED)
            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)

            var folded: Int? = null
            var displayState: Int? = null
            var ignoreTouches: Boolean? = null
            val job =
                interactor.addBiometricContextListener(
                    object : IBiometricContextListener.Stub() {
                        override fun onFoldChanged(foldState: Int) {
                            folded = foldState
                        }

                        override fun onDisplayStateChanged(newDisplayState: Int) {
                            displayState = newDisplayState
                        }

                        override fun onHardwareIgnoreTouchesChanged(newIgnoreTouches: Boolean) {
                            ignoreTouches = newIgnoreTouches
                        }
                    }
                )
            runCurrent()

            assertThat(folded).isEqualTo(FoldState.FULLY_CLOSED)
            assertThat(displayState).isEqualTo(AuthenticateOptions.DISPLAY_STATE_AOD)
            assertThat(ignoreTouches).isFalse()

            foldListener.onFoldUpdate(FOLD_UPDATE_START_OPENING)
            foldListener.onFoldUpdate(FOLD_UPDATE_FINISH_HALF_OPEN)
            keyguardTransitionRepository.startTransitionTo(KeyguardState.LOCKSCREEN)
            runCurrent()

            assertThat(folded).isEqualTo(FoldState.HALF_OPENED)
            assertThat(displayState).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)

            udfpsOverlayInteractor.setHandleTouches(false)
            runCurrent()

            assertThat(ignoreTouches).isTrue()

            job.cancel()

            // stale updates should be ignored
            foldListener.onFoldUpdate(FOLD_UPDATE_FINISH_FULL_OPEN)
            keyguardTransitionRepository.startTransitionTo(KeyguardState.AOD)
            runCurrent()

            assertThat(folded).isEqualTo(FoldState.HALF_OPENED)
            assertThat(displayState).isEqualTo(AuthenticateOptions.DISPLAY_STATE_LOCKSCREEN)
        }
}

private suspend fun FakeKeyguardTransitionRepository.startTransitionTo(newState: KeyguardState) =
    sendTransitionStep(TransitionStep(to = newState, transitionState = TransitionState.STARTED))

private fun FoldStateProvider.captureListener() =
    withArgCaptor<FoldStateProvider.FoldUpdatesListener> {
        verify(this@captureListener).addCallback(capture())
    }
