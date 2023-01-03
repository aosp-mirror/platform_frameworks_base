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

import android.animation.ValueAnimator
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.animation.Interpolators
import com.android.systemui.keyguard.data.repository.FakeKeyguardRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepository
import com.android.systemui.keyguard.data.repository.KeyguardTransitionRepositoryImpl
import com.android.systemui.keyguard.shared.model.DozeStateModel
import com.android.systemui.keyguard.shared.model.DozeTransitionModel
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionInfo
import com.android.systemui.keyguard.shared.model.WakeSleepReason
import com.android.systemui.keyguard.shared.model.WakefulnessModel
import com.android.systemui.keyguard.shared.model.WakefulnessState
import com.android.systemui.keyguard.util.KeyguardTransitionRunner
import com.android.systemui.shade.data.repository.FakeShadeRepository
import com.android.systemui.shade.data.repository.ShadeRepository
import com.android.systemui.statusbar.CommandQueue
import com.android.systemui.util.mockito.withArgCaptor
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
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
    private lateinit var shadeRepository: ShadeRepository

    // Used to issue real transition steps for test input
    private lateinit var runner: KeyguardTransitionRunner
    private lateinit var transitionRepository: KeyguardTransitionRepository

    // Used to verify transition requests for test output
    @Mock private lateinit var mockTransitionRepository: KeyguardTransitionRepository
    @Mock private lateinit var commandQueue: CommandQueue

    private lateinit var fromLockscreenTransitionInteractor: FromLockscreenTransitionInteractor
    private lateinit var fromDreamingTransitionInteractor: FromDreamingTransitionInteractor
    private lateinit var fromDozingTransitionInteractor: FromDozingTransitionInteractor
    private lateinit var fromOccludedTransitionInteractor: FromOccludedTransitionInteractor
    private lateinit var fromGoneTransitionInteractor: FromGoneTransitionInteractor
    private lateinit var fromAodTransitionInteractor: FromAodTransitionInteractor

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()

        keyguardRepository = FakeKeyguardRepository()
        shadeRepository = FakeShadeRepository()

        /* Used to issue full transition steps, to better simulate a real device */
        transitionRepository = KeyguardTransitionRepositoryImpl()
        runner = KeyguardTransitionRunner(transitionRepository)

        fromLockscreenTransitionInteractor =
            FromLockscreenTransitionInteractor(
                scope = testScope,
                keyguardInteractor = KeyguardInteractor(keyguardRepository, commandQueue),
                shadeRepository = shadeRepository,
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor = KeyguardTransitionInteractor(transitionRepository),
            )
        fromLockscreenTransitionInteractor.start()

        fromDreamingTransitionInteractor =
            FromDreamingTransitionInteractor(
                scope = testScope,
                keyguardInteractor = KeyguardInteractor(keyguardRepository, commandQueue),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor = KeyguardTransitionInteractor(transitionRepository),
            )
        fromDreamingTransitionInteractor.start()

        fromAodTransitionInteractor =
            FromAodTransitionInteractor(
                scope = testScope,
                keyguardInteractor = KeyguardInteractor(keyguardRepository, commandQueue),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor = KeyguardTransitionInteractor(transitionRepository),
            )
        fromAodTransitionInteractor.start()

        fromGoneTransitionInteractor =
            FromGoneTransitionInteractor(
                scope = testScope,
                keyguardInteractor = KeyguardInteractor(keyguardRepository, commandQueue),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor = KeyguardTransitionInteractor(transitionRepository),
            )
        fromGoneTransitionInteractor.start()

        fromDozingTransitionInteractor =
            FromDozingTransitionInteractor(
                scope = testScope,
                keyguardInteractor = KeyguardInteractor(keyguardRepository, commandQueue),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor = KeyguardTransitionInteractor(transitionRepository),
            )
        fromDozingTransitionInteractor.start()

        fromOccludedTransitionInteractor =
            FromOccludedTransitionInteractor(
                scope = testScope,
                keyguardInteractor = KeyguardInteractor(keyguardRepository, commandQueue),
                keyguardTransitionRepository = mockTransitionRepository,
                keyguardTransitionInteractor = KeyguardTransitionInteractor(transitionRepository),
            )
        fromOccludedTransitionInteractor.start()
    }

    @Test
    fun `DREAMING to LOCKSCREEN`() =
        testScope.runTest {
            // GIVEN a device is dreaming and occluded
            keyguardRepository.setDreamingWithOverlay(true)
            keyguardRepository.setKeyguardOccluded(true)
            runCurrent()

            // GIVEN a prior transition has run to DREAMING
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.DREAMING,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN doze is complete
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            // AND dreaming has stopped
            keyguardRepository.setDreamingWithOverlay(false)
            // AND occluded has stopped
            keyguardRepository.setKeyguardOccluded(false)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to BOUNCER should occur
            assertThat(info.ownerName).isEqualTo("FromDreamingTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.DREAMING)
            assertThat(info.to).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `LOCKSCREEN to BOUNCER via bouncer showing call`() =
        testScope.runTest {
            // GIVEN a device that has at least woken up
            keyguardRepository.setWakefulnessModel(startingToWake())
            runCurrent()

            // GIVEN a prior transition has run to LOCKSCREEN
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.OFF,
                    to = KeyguardState.LOCKSCREEN,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()

            // WHEN the bouncer is set to show
            keyguardRepository.setBouncerShowing(true)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to BOUNCER should occur
            assertThat(info.ownerName).isEqualTo("FromLockscreenTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.to).isEqualTo(KeyguardState.BOUNCER)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `OCCLUDED to DOZING`() =
        testScope.runTest {
            // GIVEN a device with AOD not available
            keyguardRepository.setAodAvailable(false)
            runCurrent()

            // GIVEN a prior transition has run to OCCLUDED
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.OCCLUDED,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromOccludedTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `OCCLUDED to AOD`() =
        testScope.runTest {
            // GIVEN a device with AOD available
            keyguardRepository.setAodAvailable(true)
            runCurrent()

            // GIVEN a prior transition has run to OCCLUDED
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.OCCLUDED,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromOccludedTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.OCCLUDED)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `LOCKSCREEN to DOZING`() =
        testScope.runTest {
            // GIVEN a device with AOD not available
            keyguardRepository.setAodAvailable(false)
            runCurrent()

            // GIVEN a prior transition has run to LOCKSCREEN
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromLockscreenTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `LOCKSCREEN to AOD`() =
        testScope.runTest {
            // GIVEN a device with AOD available
            keyguardRepository.setAodAvailable(true)
            runCurrent()

            // GIVEN a prior transition has run to LOCKSCREEN
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.GONE,
                    to = KeyguardState.LOCKSCREEN,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromLockscreenTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `DOZING to LOCKSCREEN`() =
        testScope.runTest {
            // GIVEN a prior transition has run to DOZING
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.DOZING,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to wake
            keyguardRepository.setWakefulnessModel(startingToWake())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromDozingTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.DOZING)
            assertThat(info.to).isEqualTo(KeyguardState.LOCKSCREEN)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `GONE to DOZING`() =
        testScope.runTest {
            // GIVEN a device with AOD not available
            keyguardRepository.setAodAvailable(false)
            runCurrent()

            // GIVEN a prior transition has run to GONE
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to DOZING should occur
            assertThat(info.ownerName).isEqualTo("FromGoneTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.GONE)
            assertThat(info.to).isEqualTo(KeyguardState.DOZING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `GONE to AOD`() =
        testScope.runTest {
            // GIVEN a device with AOD available
            keyguardRepository.setAodAvailable(true)
            runCurrent()

            // GIVEN a prior transition has run to GONE
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to sleep
            keyguardRepository.setWakefulnessModel(startingToSleep())
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to AOD should occur
            assertThat(info.ownerName).isEqualTo("FromGoneTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.GONE)
            assertThat(info.to).isEqualTo(KeyguardState.AOD)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    @Test
    fun `GONE to DREAMING`() =
        testScope.runTest {
            // GIVEN a device that is not dreaming or dozing
            keyguardRepository.setDreamingWithOverlay(false)
            keyguardRepository.setDozeTransitionModel(
                DozeTransitionModel(from = DozeStateModel.DOZE, to = DozeStateModel.FINISH)
            )
            runCurrent()

            // GIVEN a prior transition has run to GONE
            runner.startTransition(
                testScope,
                TransitionInfo(
                    ownerName = "",
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.GONE,
                    animator =
                        ValueAnimator().apply {
                            duration = 10
                            interpolator = Interpolators.LINEAR
                        },
                )
            )
            runCurrent()
            reset(mockTransitionRepository)

            // WHEN the device begins to dream
            keyguardRepository.setDreamingWithOverlay(true)
            runCurrent()

            val info =
                withArgCaptor<TransitionInfo> {
                    verify(mockTransitionRepository).startTransition(capture())
                }
            // THEN a transition to DREAMING should occur
            assertThat(info.ownerName).isEqualTo("FromGoneTransitionInteractor")
            assertThat(info.from).isEqualTo(KeyguardState.GONE)
            assertThat(info.to).isEqualTo(KeyguardState.DREAMING)
            assertThat(info.animator).isNotNull()

            coroutineContext.cancelChildren()
        }

    private fun startingToWake() =
        WakefulnessModel(
            WakefulnessState.STARTING_TO_WAKE,
            true,
            WakeSleepReason.OTHER,
            WakeSleepReason.OTHER
        )

    private fun startingToSleep() =
        WakefulnessModel(
            WakefulnessState.STARTING_TO_SLEEP,
            true,
            WakeSleepReason.OTHER,
            WakeSleepReason.OTHER
        )
}
