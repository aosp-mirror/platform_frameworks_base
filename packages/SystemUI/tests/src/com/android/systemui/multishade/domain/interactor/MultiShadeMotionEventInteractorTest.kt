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
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.multishade.data.remoteproxy.MultiShadeInputProxy
import com.android.systemui.multishade.data.repository.MultiShadeRepository
import com.android.systemui.multishade.shared.model.ProxiedInputModel
import com.android.systemui.multishade.shared.model.ShadeId
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

    @Before
    fun setUp() {
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
        underTest =
            MultiShadeMotionEventInteractor(
                applicationContext = context,
                applicationScope = testScope.backgroundScope,
                interactor = interactor,
            )
    }

    @After
    fun tearDown() {
        motionEvents.forEach { motionEvent -> motionEvent.recycle() }
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
    fun dragAboveTouchSlopAndUp() =
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
    fun dragAboveTouchSlopAndCancel() =
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
