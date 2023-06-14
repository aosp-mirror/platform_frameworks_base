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

package com.android.systemui.keyguard.domain.interactor

import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardSecurityModel
import com.android.keyguard.KeyguardSecurityModel.SecurityMode.PIN
import com.android.systemui.SysuiTestCase
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardBouncerRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.shared.model.BiometricUnlockModel
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.any
import com.android.systemui.util.mockito.whenever
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

/**
 * Class for testing user journeys through the interactors. They will all be activated during setup,
 * to ensure the expected transitions are still triggered.
 */
@SmallTest
@RunWith(JUnit4::class)
class KeyguardTransitionScenariosTest : SysuiTestCase() {
    private lateinit var testScope: TestScope

    private lateinit var keyguardRepository: FakeKeyguardRepository
    private lateinit var bouncerRepository: FakeKeyguardBouncerRepository
    private lateinit var shadeRepository: ShadeRepository
    private lateinit var transitionRepository: FakeKeyguardTransitionRepository

    // Used to verify transition requests for test output
    @Mock private lateinit var mockTransitionRepository: KeyguardTransitionRepository
    @Mock private lateinit var commandQueue: CommandQueue
    @Mock private lateinit var keyguardSecurityModel: KeyguardSecurityModel

    private lateinit var fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor
    private lateinit var fromDreamingTransitionInteractor: FromDreamingTransitionInteractor
    private lateinit var fromDozingTransitionInteractor: FromDozingTransitionInteractor
    private lateinit var fromOccludedTransitionInteractor: FromOccludedTransitionInteractor
    private lateinit var fromGoneTransitionInteractor: FromGoneTransitionInteractor
    private lateinit var fromAodTransitionInteractor: FromAodTransitionInteractor
    private lateinit var fromAlternateBouncerTransitionInteractor:
        FromAlternateBouncerTransitionInteractor
    private lateinit var fromPrimaryBouncerTransitionInteractor:
        FromPrimaryBouncerTransitionInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()

        keyguardRepository = FakeKeyguardRepository()
        bouncerRepository = FakeKeyguardBouncerRepository()
        shadeRepository = FakeShadeRepository()
        transitionRepository = FakeKeyguardTransitionRepository()

        whenever(keyguardSecurityModel.getSecurityMode(anyInt())).thenReturn(PIN)

        val featureFlags = FakeFeatureFlags().apply { set(Flags.FACE_AUTH_REFACTOR, true) }
        fromLockscreenTransitionInteractor =
            FromLockscreenTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                shadeRepository = shadeRepository,
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
            )
        fromLockscreenTransitionInteractor.start()

        fromDreamingTransitionInteractor =
            FromDreamingTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
            )
        fromDreamingTransitionInteractor.start()

        fromAodTransitionInteractor =
            FromAodTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
            )
        fromAodTransitionInteractor.start()

        fromGoneTransitionInteractor =
            FromGoneTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
            )
        fromGoneTransitionInteractor.start()

        fromDozingTransitionInteractor =
            FromDozingTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
            )
        fromDozingTransitionInteractor.start()

        fromOccludedTransitionInteractor =
            FromOccludedTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
            )
        fromOccludedTransitionInteractor.start()

        fromAlternateBouncerTransitionInteractor =
            FromAlternateBouncerTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
            )
        fromAlternateBouncerTransitionInteractor.start()

        fromPrimaryBouncerTransitionInteractor =
            FromPrimaryBouncerTransitionInteractor(
                scope = testScope,
                keyguardInteractor = createKeyguardInteractor(featureFlags),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(transitionRepository, testScope.backgroundScope),
                keyguardSecurityModel = keyguardSecurityModel,
            )
        fromPrimaryBouncerTransitionInteractor.start()
    }

    @Test
    fun lockscreenToPrimaryBouncerViaBouncerShowingCall() =
        testScope.runTest {
            // GIVEN a device that has at least woken up
            keyguardRepository.setWakefulnessModel(startingToWake())
            runCurrent()

            // GIVEN a prior transition has run to LOCKSCREEN
            runTransition(KeyguardState.OFF, KeyguardState.LOCKSCREEN)

            // WHEN the primary bouncer is set to show
            bouncerRepository.setPrimaryShow(true)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to PRIMARY_BOUNCER should occur
            assertThat(info.ownerName).isEqualTo("FromLockscreenTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.to).isEqualTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun occludedToDozing() =
        testScope.runTest {
            // GIVEN a device with AOD not available
            keyguardRepository.setAodAvailable(false)
            runCurrent()

            // GIVEN a prior transition has run to OCCLUDED
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.OCCLUDED)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromOccludedTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun occludedToAod() =
        testScope.runTest {
            // GIVEN a device with AOD available
            keyguardRepository.setAodAvailable(true)
            runCurrent()

            // GIVEN a prior transition has run to OCCLUDED
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.OCCLUDED)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromOccludedTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun lockscreenToDreaming() =
        testScope.runTest {
            // GIVEN a device that is not dreaming or dozing
            keyguardRepository.setDreamingWithOverlay(false)
            keyguardRepository.setWakefulnessModel(startingToWake())
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            runCurrent()

            // GIVEN a prior transition has run to LOCKSCREEN
            runTransition(KeyguardState.GONE, KeyguardState.LOCKSCREEN)

            // WHEN the device begins to dream
            keyguardRepository.setDreamingWithOverlay(true)
            advanceUntilIdle()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DREAMING should occur
            assertThat(info.ownerName).isEqualTo("FromLockscreenTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.to).isEqualTo(KeyguardState.DREAMING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun lockscreenToDozing() =
        testScope.runTest {
            // GIVEN a device with AOD not available
            keyguardRepository.setAodAvailable(false)
            runCurrent()

            // GIVEN a prior transition has run to LOCKSCREEN
            runTransition(KeyguardState.GONE, KeyguardState.LOCKSCREEN)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromLockscreenTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun lockscreenToAod() =
        testScope.runTest {
            // GIVEN a device with AOD available
            keyguardRepository.setAodAvailable(true)
            runCurrent()

            // GIVEN a prior transition has run to LOCKSCREEN
            runTransition(KeyguardState.GONE, KeyguardState.LOCKSCREEN)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromLockscreenTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun dozingToLockscreen() =
        testScope.runTest {
            // GIVEN a prior transition has run to DOZING
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.DOZING)

            // WHEN the device begins to wake
            keyguardRepository.setWakefulnessModel(startingToWake())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromDozingTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.DOZING)
            assertThat(info.to).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun dozingToLockscreenCannotBeInterruptedByDreaming() =
        testScope.runTest {
            // GIVEN a prior transition has started to LOCKSCREEN
            transitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.DOZING,
                    to = KeyguardState.LOCKSCREEN,
                    value = 0.5f,
                    transitionState = TransitionState.RUNNING,
                    ownerName = "KeyguardTransitionScenariosTest",
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN a signal comes that dreaming is enabled
            keyguardRepository.setDreamingWithOverlay(true)
            advanceUntilIdle()

            // THEN the transition is ignored
            verify(mockTransitionRepository, never()).startTransition(any(), anyBoolean())

            coroutineContext.cancelChildren()
        }

    @Test
    fun dozingToGone() =
        testScope.runTest {
            // GIVEN a prior transition has run to DOZING
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.DOZING)

            // WHEN biometrics succeeds with wake and unlock mode
            keyguardRepository.setBiometricUnlockState(BiometricUnlockModel.WAKE_AND_UNLOCK)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromDozingTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.DOZING)
            assertThat(info.to).isEqualTo(KeyguardState.GONE)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun goneToDozing() =
        testScope.runTest {
            // GIVEN a device with AOD not available
            keyguardRepository.setAodAvailable(false)
            runCurrent()

            // GIVEN a prior transition has run to GONE
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.GONE)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromGoneTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.GONE)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun goneToAod() =
        testScope.runTest {
            // GIVEN a device with AOD available
            keyguardRepository.setAodAvailable(true)
            runCurrent()

            // GIVEN a prior transition has run to GONE
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.GONE)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to AOD should occur
            assertThat(info.ownerName).isEqualTo("FromGoneTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.GONE)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun goneToLockscreen() =
        testScope.runTest {
            // GIVEN a prior transition has run to GONE
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.GONE)

            // WHEN the keyguard starts to show
            keyguardRepository.setKeyguardShowing(true)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to AOD should occur
            assertThat(info.ownerName).isEqualTo("FromGoneTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.GONE)
            assertThat(info.to).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun goneToDreaming() =
        testScope.runTest {
            // GIVEN a device that is not dreaming or dozing
            keyguardRepository.setDreamingWithOverlay(false)
            keyguardRepository.setWakefulnessModel(startingToWake())
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            runCurrent()

            // GIVEN a prior transition has run to GONE
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.GONE)

            // WHEN the device begins to dream
            keyguardRepository.setDreamingWithOverlay(true)
            advanceUntilIdle()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DREAMING should occur
            assertThat(info.ownerName).isEqualTo("FromGoneTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.GONE)
            assertThat(info.to).isEqualTo(KeyguardState.DREAMING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun alternateBouncerToPrimaryBouncer() =
        testScope.runTest {
            // GIVEN a prior transition has run to ALTERNATE_BOUNCER
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.ALTERNATE_BOUNCER)

            // WHEN the alternateBouncer stops showing and then the primary bouncer shows
            bouncerRepository.setPrimaryShow(true)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to PRIMARY_BOUNCER should occur
            assertThat(info.ownerName).isEqualTo("FromAlternateBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun alternateBoucnerToAod() =
        testScope.runTest {
            // GIVEN a prior transition has run to ALTERNATE_BOUNCER
            bouncerRepository.setAlternateVisible(true)
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.ALTERNATE_BOUNCER)

            // GIVEN the primary bouncer isn't showing, aod available and starting to sleep
            bouncerRepository.setPrimaryShow(false)
            keyguardRepository.setAodAvailable(true)
            keyguardRepository.setWakefulnessModel(startingToSleep())

            // WHEN the alternateBouncer stops showing
            bouncerRepository.setAlternateVisible(false)
            advanceUntilIdle()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to AOD should occur
            assertThat(info.ownerName).isEqualTo("FromAlternateBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun alternateBouncerToDozing() =
        testScope.runTest {
            // GIVEN a prior transition has run to ALTERNATE_BOUNCER
            bouncerRepository.setAlternateVisible(true)
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.ALTERNATE_BOUNCER)

            // GIVEN the primary bouncer isn't showing, aod not available and starting to sleep
            // to sleep
            bouncerRepository.setPrimaryShow(false)
            keyguardRepository.setAodAvailable(false)
            keyguardRepository.setWakefulnessModel(startingToSleep())

            // WHEN the alternateBouncer stops showing
            bouncerRepository.setAlternateVisible(false)
            advanceUntilIdle()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromAlternateBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun alternateBouncerToLockscreen() =
        testScope.runTest {
            // GIVEN a prior transition has run to ALTERNATE_BOUNCER
            bouncerRepository.setAlternateVisible(true)
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.ALTERNATE_BOUNCER)

            // GIVEN the primary bouncer isn't showing and device not sleeping
            bouncerRepository.setPrimaryShow(false)
            keyguardRepository.setWakefulnessModel(startingToWake())

            // WHEN the alternateBouncer stops showing
            bouncerRepository.setAlternateVisible(false)
            advanceUntilIdle()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to LOCKSCREEN should occur
            assertThat(info.ownerName).isEqualTo("FromAlternateBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun primaryBouncerToAod() =
        testScope.runTest {
            // GIVEN a prior transition has run to PRIMARY_BOUNCER
            bouncerRepository.setPrimaryShow(true)
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.PRIMARY_BOUNCER)

            // GIVEN aod available and starting to sleep
            keyguardRepository.setAodAvailable(true)
            keyguardRepository.setWakefulnessModel(startingToSleep())

            // WHEN the primaryBouncer stops showing
            bouncerRepository.setPrimaryShow(false)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to AOD should occur
            assertThat(info.ownerName).isEqualTo("FromPrimaryBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun primaryBouncerToDozing() =
        testScope.runTest {
            // GIVEN a prior transition has run to PRIMARY_BOUNCER
            bouncerRepository.setPrimaryShow(true)
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.PRIMARY_BOUNCER)

            // GIVEN aod not available and starting to sleep to sleep
            keyguardRepository.setAodAvailable(false)
            keyguardRepository.setWakefulnessModel(startingToSleep())

            // WHEN the primaryBouncer stops showing
            bouncerRepository.setPrimaryShow(false)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromPrimaryBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun primaryBouncerToLockscreen() =
        testScope.runTest {
            // GIVEN a prior transition has run to PRIMARY_BOUNCER
            bouncerRepository.setPrimaryShow(true)
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.PRIMARY_BOUNCER)

            // GIVEN device not sleeping
            keyguardRepository.setWakefulnessModel(startingToWake())

            // WHEN the alternateBouncer stops showing
            bouncerRepository.setPrimaryShow(false)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to LOCKSCREEN should occur
            assertThat(info.ownerName).isEqualTo("FromPrimaryBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun occludedToGone() =
        testScope.runTest {
            // GIVEN a device on lockscreen
            keyguardRepository.setKeyguardShowing(true)
            runCurrent()

            // GIVEN a prior transition has run to OCCLUDED
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.OCCLUDED)
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            // WHEN keyguard goes away
            keyguardRepository.setKeyguardShowing(false)
            // AND occlusion ends
            keyguardRepository.setKeyguardOccluded(false)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to GONE should occur
            assertThat(info.ownerName).isEqualTo("FromOccludedTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.to).isEqualTo(KeyguardState.GONE)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun occludedToLockscreen() =
        testScope.runTest {
            // GIVEN a device on lockscreen
            keyguardRepository.setKeyguardShowing(true)
            runCurrent()

            // GIVEN a prior transition has run to OCCLUDED
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.OCCLUDED)
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            // WHEN occlusion ends
            keyguardRepository.setKeyguardOccluded(false)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to LOCKSCREEN should occur
            assertThat(info.ownerName).isEqualTo("FromOccludedTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.to).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun occludedToAlternateBouncer() =
        testScope.runTest {

            // GIVEN a prior transition has run to OCCLUDED
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.OCCLUDED)
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            // WHEN alternate bouncer shows
            bouncerRepository.setAlternateVisible(true)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to AlternateBouncer should occur
            assertThat(info.ownerName).isEqualTo("FromOccludedTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.to).isEqualTo(KeyguardState.ALTERNATE_BOUNCER)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun primaryBouncerToOccluded() =
        testScope.runTest {
            // GIVEN device not sleeping
            keyguardRepository.setWakefulnessModel(startingToWake())

            // GIVEN a prior transition has run to PRIMARY_BOUNCER
            runTransition(KeyguardState.LOCKSCREEN, KeyguardState.PRIMARY_BOUNCER)
            bouncerRepository.setPrimaryShow(true)
            runCurrent()

            // WHEN the keyguard is occluded and primary bouncer stops showing
            keyguardRepository.setKeyguardOccluded(true)
            bouncerRepository.setPrimaryShow(false)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture(), anyBoolean())
                }
            // THEN a transition to AlternateBouncer should occur
            assertThat(info.ownerName).isEqualTo("FromPrimaryBouncerTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.PRIMARY_BOUNCER)
            assertThat(info.to).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    private fun startingToWake() =
        WakefulnessModel(
            WakefulnessState.STARTING_TO_WAKE,
            WakeSleepReason.OTHER,
            WakeSleepReason.OTHER
        )

    private fun startingToSleep() =
        WakefulnessModel(
            WakefulnessState.STARTING_TO_SLEEP,
            WakeSleepReason.OTHER,
            WakeSleepReason.OTHER
        )

    private fun createKeyguardInteractor(featureFlags: FeatureFlags): KeyguardInteractor {
        return KeyguardInteractor(
            keyguardRepository,
            commandQueue,
            featureFlags,
            bouncerRepository,
        )
    }

    private suspend fun TestScope.runTransition(from: KeyguardState, to: KeyguardState) {
        transitionRepository.sendTransitionStep(
            TransitionStep(
                from = from,
                to = to,
                value = 0f,
                transitionState = TransitionState.STARTED,
            )
        )
        runCurrent()
        transitionRepository.sendTransitionStep(
            TransitionStep(
                from = from,
                to = to,
                value = 0.5f,
                transitionState = TransitionState.RUNNING,
            )
        )
        runCurrent()
        transitionRepository.sendTransitionStep(
            TransitionStep(
                from = from,
                to = to,
                value = 1f,
                transitionState = TransitionState.FINISHED,
            )
        )
        runCurrent()
        reset(mockTransitionRepository)
    }
}
