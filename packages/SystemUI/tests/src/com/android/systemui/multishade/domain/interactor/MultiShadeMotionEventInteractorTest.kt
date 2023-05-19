/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.multishade.domain.interactor

import android.view.MotionEvent
import android.view.ViewConfiguration
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.classifier.FalsingManagerFake
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.flags.FakeFeatureFlags
import com.android.systemui.flags.Flags
import com.android.systemui.keyguard.data.repository.FakeKeyguardTransitionRepository
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.keyguard.shared.model.TransitionState
import com.android.systemui.keyguard.shared.model.TransitionStep
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.data.repository.MultiShadeRepository
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeId
import com.android.systemui.shade.ShadeController
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.Mockito.anyBoolean
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

@OptIn(ExperimentalCoroutinesApi::class)
@SmallTest
@RunWith(JUnit4::class)
class MultiShadeMotionEventInteractorTest : SysuiTestCase() {

    private lateinit var underTest: MultiShadeMotionEventInteractor

    private lateinit var testScope: TestScope
    private lateinit var motionEvents: MutableSet<MotionEvent>
    private lateinit var repository: MultiShadeRepository
    private lateinit var interactor: MultiShadeInteractor
    private val touchSlop: Int = ViewConfiguration.get(context).scaledTouchSlop
    private lateinit var keyguardTransitionRepository: FakeKeyguardTransitionRepository
    private lateinit var falsingManager: FalsingManagerFake
    @Mock private lateinit var shadeController: ShadeController

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        testScope = TestScope()
        motionEvents = mutableSetOf()

        val inputProxy = MultiShadeInputProxy()
        repository =
            MultiShadeRepository(
                applicationContext = context,
                inputProxy = inputProxy,
            )
        interactor =
            MultiShadeInteractor(
                applicationScope = testScope.backgroundScope,
                repository = repository,
                inputProxy = inputProxy,
            )
        val featureFlags = FakeFeatureFlags()
        featureFlags.set(Flags.DUAL_SHADE, true)
        keyguardTransitionRepository = FakeKeyguardTransitionRepository()
        falsingManager = FalsingManagerFake()

        underTest =
            MultiShadeMotionEventInteractor(
                applicationContext = context,
                applicationScope = testScope.backgroundScope,
                multiShadeInteractor = interactor,
                featureFlags = featureFlags,
                keyguardTransitionInteractor =
                    KeyguardTransitionInteractor(
                        repository = keyguardTransitionRepository,
                        scope = testScope.backgroundScope
                    ),
                falsingManager = falsingManager,
                shadeController = shadeController,
            )
    }

    @After
    fun tearDown() {
        motionEvents.forEach { motionEvent -> motionEvent.recycle() }
    }

    @Test
    fun listenForIsAnyShadeExpanded_expanded_makesWindowViewVisible() =
        testScope.runTest {
            whenever(shadeController.isKeyguard).thenReturn(false)
            repository.setExpansion(ShadeId.LEFT, 0.1f)
            val expanded by collectLastValue(interactor.isAnyShadeExpanded)
            assertThat(expanded).isTrue()

            verify(shadeController).makeExpandedVisible(anyBoolean())
        }

    @Test
    fun listenForIsAnyShadeExpanded_collapsed_makesWindowViewInvisible() =
        testScope.runTest {
            whenever(shadeController.isKeyguard).thenReturn(false)
            repository.setForceCollapseAll(true)
            val expanded by collectLastValue(interactor.isAnyShadeExpanded)
            assertThat(expanded).isFalse()

            verify(shadeController).makeExpandedInvisible()
        }

    @Test
    fun listenForIsAnyShadeExpanded_collapsedOnKeyguard_makesWindowViewVisible() =
        testScope.runTest {
            whenever(shadeController.isKeyguard).thenReturn(true)
            repository.setForceCollapseAll(true)
            val expanded by collectLastValue(interactor.isAnyShadeExpanded)
            assertThat(expanded).isFalse()

            verify(shadeController).makeExpandedVisible(anyBoolean())
        }

    @Test
    fun shouldIntercept_initialDown_returnsFalse() =
        testScope.runTest {
            assertThat(underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))).isFalse()
        }

    @Test
    fun shouldIntercept_moveBelowTouchSlop_returnsFalse() =
        testScope.runTest {
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))

            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            y = touchSlop - 1f,
                        )
                    )
                )
                .isFalse()
        }

    @Test
    fun shouldIntercept_moveAboveTouchSlop_returnsTrue() =
        testScope.runTest {
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))

            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            y = touchSlop + 1f,
                        )
                    )
                )
                .isTrue()
        }

    @Test
    fun shouldIntercept_moveAboveTouchSlop_butHorizontalFirst_returnsFalse() =
        testScope.runTest {
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))

            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            x = touchSlop + 1f,
                        )
                    )
                )
                .isFalse()
            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            y = touchSlop + 1f,
                        )
                    )
                )
                .isFalse()
        }

    @Test
    fun shouldIntercept_up_afterMovedAboveTouchSlop_returnsTrue() =
        testScope.runTest {
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_MOVE, y = touchSlop + 1f))

            assertThat(underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_UP))).isTrue()
        }

    @Test
    fun shouldIntercept_cancel_afterMovedAboveTouchSlop_returnsTrue() =
        testScope.runTest {
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_MOVE, y = touchSlop + 1f))

            assertThat(underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_CANCEL))).isTrue()
        }

    @Test
    fun shouldIntercept_moveAboveTouchSlopAndUp_butShadeExpanded_returnsFalse() =
        testScope.runTest {
            repository.setExpansion(ShadeId.LEFT, 0.1f)
            runCurrent()

            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))

            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            y = touchSlop + 1f,
                        )
                    )
                )
                .isFalse()
            assertThat(underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_UP))).isFalse()
        }

    @Test
    fun shouldIntercept_moveAboveTouchSlopAndCancel_butShadeExpanded_returnsFalse() =
        testScope.runTest {
            repository.setExpansion(ShadeId.LEFT, 0.1f)
            runCurrent()

            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))

            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            y = touchSlop + 1f,
                        )
                    )
                )
                .isFalse()
            assertThat(underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_CANCEL))).isFalse()
        }

    @Test
    fun shouldIntercept_moveAboveTouchSlopAndUp_butBouncerShowing_returnsFalse() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    value = 0.1f,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))

            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            y = touchSlop + 1f,
                        )
                    )
                )
                .isFalse()
            assertThat(underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_UP))).isFalse()
        }

    @Test
    fun shouldIntercept_moveAboveTouchSlopAndCancel_butBouncerShowing_returnsFalse() =
        testScope.runTest {
            keyguardTransitionRepository.sendTransitionStep(
                TransitionStep(
                    from = KeyguardState.LOCKSCREEN,
                    to = KeyguardState.PRIMARY_BOUNCER,
                    value = 0.1f,
                    transitionState = TransitionState.STARTED,
                )
            )
            runCurrent()

            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))

            assertThat(
                    underTest.shouldIntercept(
                        motionEvent(
                            MotionEvent.ACTION_MOVE,
                            y = touchSlop + 1f,
                        )
                    )
                )
                .isFalse()
            assertThat(underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_CANCEL))).isFalse()
        }

    @Test
    fun tap_doesNotSendProxiedInput() =
        testScope.runTest {
            val leftShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.LEFT))
            val rightShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.RIGHT))
            val singleShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.SINGLE))

            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_UP))

            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()
        }

    @Test
    fun dragBelowTouchSlop_doesNotSendProxiedInput() =
        testScope.runTest {
            val leftShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.LEFT))
            val rightShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.RIGHT))
            val singleShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.SINGLE))

            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_DOWN))
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_MOVE, y = touchSlop - 1f))
            underTest.shouldIntercept(motionEvent(MotionEvent.ACTION_UP))

            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()
        }

    @Test
    fun dragShadeAboveTouchSlopAndUp() =
        testScope.runTest {
            val leftShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.LEFT))
            val rightShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.RIGHT))
            val singleShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.SINGLE))

            underTest.shouldIntercept(
                motionEvent(
                    MotionEvent.ACTION_DOWN,
                    x = 100f, // left shade
                )
            )
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()

            val yDragAmountPx = touchSlop + 1f
            val moveEvent =
                motionEvent(
                    MotionEvent.ACTION_MOVE,
                    x = 100f, // left shade
                    y = yDragAmountPx,
                )
            assertThat(underTest.shouldIntercept(moveEvent)).isTrue()
            underTest.onTouchEvent(moveEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput)
                .isEqualTo(
                    ProxiedInputModel.OnDrag(
                        xFraction = 0.1f,
                        yDragAmountPx = yDragAmountPx,
                    )
                )
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()

            val upEvent = motionEvent(MotionEvent.ACTION_UP)
            assertThat(underTest.shouldIntercept(upEvent)).isTrue()
            underTest.onTouchEvent(upEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()
        }

    @Test
    fun dragShadeAboveTouchSlopAndCancel() =
        testScope.runTest {
            val leftShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.LEFT))
            val rightShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.RIGHT))
            val singleShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.SINGLE))

            underTest.shouldIntercept(
                motionEvent(
                    MotionEvent.ACTION_DOWN,
                    x = 900f, // right shade
                )
            )
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()

            val yDragAmountPx = touchSlop + 1f
            val moveEvent =
                motionEvent(
                    MotionEvent.ACTION_MOVE,
                    x = 900f, // right shade
                    y = yDragAmountPx,
                )
            assertThat(underTest.shouldIntercept(moveEvent)).isTrue()
            underTest.onTouchEvent(moveEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput)
                .isEqualTo(
                    ProxiedInputModel.OnDrag(
                        xFraction = 0.9f,
                        yDragAmountPx = yDragAmountPx,
                    )
                )
            assertThat(singleShadeProxiedInput).isNull()

            val cancelEvent = motionEvent(MotionEvent.ACTION_CANCEL)
            assertThat(underTest.shouldIntercept(cancelEvent)).isTrue()
            underTest.onTouchEvent(cancelEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()
        }

    @Test
    fun dragUp_withUp_doesNotShowShade() =
        testScope.runTest {
            val leftShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.LEFT))
            val rightShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.RIGHT))
            val singleShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.SINGLE))

            underTest.shouldIntercept(
                motionEvent(
                    MotionEvent.ACTION_DOWN,
                    x = 100f, // left shade
                )
            )
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()

            val yDragAmountPx = -(touchSlop + 1f) // dragging up
            val moveEvent =
                motionEvent(
                    MotionEvent.ACTION_MOVE,
                    x = 100f, // left shade
                    y = yDragAmountPx,
                )
            assertThat(underTest.shouldIntercept(moveEvent)).isFalse()
            underTest.onTouchEvent(moveEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()

            val upEvent = motionEvent(MotionEvent.ACTION_UP)
            assertThat(underTest.shouldIntercept(upEvent)).isFalse()
            underTest.onTouchEvent(upEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()
        }

    @Test
    fun dragUp_withCancel_falseTouch_showsThenHidesBouncer() =
        testScope.runTest {
            val leftShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.LEFT))
            val rightShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.RIGHT))
            val singleShadeProxiedInput by collectLastValue(interactor.proxiedInput(ShadeId.SINGLE))

            underTest.shouldIntercept(
                motionEvent(
                    MotionEvent.ACTION_DOWN,
                    x = 900f, // right shade
                )
            )
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()

            val yDragAmountPx = -(touchSlop + 1f) // drag up
            val moveEvent =
                motionEvent(
                    MotionEvent.ACTION_MOVE,
                    x = 900f, // right shade
                    y = yDragAmountPx,
                )
            assertThat(underTest.shouldIntercept(moveEvent)).isFalse()
            underTest.onTouchEvent(moveEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()

            falsingManager.setIsFalseTouch(true)
            val cancelEvent = motionEvent(MotionEvent.ACTION_CANCEL)
            assertThat(underTest.shouldIntercept(cancelEvent)).isFalse()
            underTest.onTouchEvent(cancelEvent, viewWidthPx = 1000)
            assertThat(leftShadeProxiedInput).isNull()
            assertThat(rightShadeProxiedInput).isNull()
            assertThat(singleShadeProxiedInput).isNull()
        }

    private fun TestScope.motionEvent(
        action: Int,
        downTime: Long = currentTime,
        eventTime: Long = currentTime,
        x: Float = 0f,
        y: Float = 0f,
    ): MotionEvent {
        val motionEvent = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        motionEvents.add(motionEvent)
        return motionEvent
    }
}
