/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.shared.condition

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.shared.condition.Condition.START_EAGERLY
import com.android.systemui.shared.condition.Condition.START_LAZILY
import com.android.systemui.shared.condition.Condition.START_WHEN_NEEDED
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CombinedConditionTest : SysuiTestCase() {

    class FakeCondition
    constructor(
        scope: CoroutineScope,
        initialValue: Boolean?,
        overriding: Boolean = false,
        @StartStrategy private val startStrategy: Int = START_WHEN_NEEDED,
    ) : Condition(scope, initialValue, overriding) {
        private var _started = false
        val started: Boolean
            get() = _started

        override fun start() {
            _started = true
        }

        override fun stop() {
            _started = false
        }

        override fun getStartStrategy(): Int {
            return startStrategy
        }

        fun setValue(value: Boolean?) {
            value?.also { updateCondition(value) } ?: clearCondition()
        }
    }

    @Test
    fun testOrOperatorWithMixedConditions() = runSelfCancelingTest {
        val startWhenNeededCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_WHEN_NEEDED)
        val eagerCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_EAGERLY)
        val lazyCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_LAZILY)

        val combinedCondition =
            CombinedCondition(
                scope = this,
                conditions =
                    listOf(
                        eagerCondition,
                        lazyCondition,
                        startWhenNeededCondition,
                    ),
                operand = Evaluator.OP_OR
            )

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)

        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isTrue()
        assertThat(lazyCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isTrue()

        eagerCondition.setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(eagerCondition.started).isTrue()
        assertThat(lazyCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isFalse()

        startWhenNeededCondition.setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(eagerCondition.started).isTrue()
        assertThat(lazyCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isFalse()

        startWhenNeededCondition.setValue(false)
        eagerCondition.setValue(false)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isTrue()
        assertThat(lazyCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isTrue()
    }

    @Test
    fun testAndOperatorWithMixedConditions() = runSelfCancelingTest {
        val startWhenNeededCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_WHEN_NEEDED)
        val eagerCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_EAGERLY)
        val lazyCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_LAZILY)

        val combinedCondition =
            CombinedCondition(
                scope = this,
                conditions =
                    listOf(
                        startWhenNeededCondition,
                        lazyCondition,
                        eagerCondition,
                    ),
                operand = Evaluator.OP_AND
            )

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)

        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isTrue()
        assertThat(lazyCondition.started).isFalse()
        assertThat(startWhenNeededCondition.started).isFalse()

        eagerCondition.setValue(true)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isTrue()
        assertThat(lazyCondition.started).isFalse()

        startWhenNeededCondition.setValue(true)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isFalse()
        assertThat(lazyCondition.started).isTrue()

        startWhenNeededCondition.setValue(false)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isFalse()
        assertThat(lazyCondition.started).isTrue()

        startWhenNeededCondition.setValue(true)
        lazyCondition.setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(eagerCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isTrue()
        assertThat(lazyCondition.started).isTrue()
    }

    @Test
    fun testAndOperatorWithStartWhenNeededConditions() = runSelfCancelingTest {
        val conditions =
            0.rangeTo(2)
                .map {
                    FakeCondition(
                        scope = this,
                        initialValue = false,
                        startStrategy = START_WHEN_NEEDED
                    )
                }
                .toList()

        val combinedCondition =
            CombinedCondition(scope = this, conditions = conditions, operand = Evaluator.OP_AND)

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)
        assertThat(combinedCondition.isConditionMet).isFalse()
        // Only the first condition should be started
        assertThat(areStarted(conditions)).containsExactly(true, false, false).inOrder()

        conditions[0].setValue(true)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(areStarted(conditions)).containsExactly(false, true, false).inOrder()

        conditions[1].setValue(true)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(areStarted(conditions)).containsExactly(false, false, true).inOrder()

        conditions[2].setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(areStarted(conditions)).containsExactly(true, true, true).inOrder()

        conditions[0].setValue(false)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(areStarted(conditions)).containsExactly(true, false, false).inOrder()
    }

    @Test
    fun testOrOperatorWithStartWhenNeededConditions() = runSelfCancelingTest {
        val conditions =
            0.rangeTo(2)
                .map {
                    FakeCondition(
                        scope = this,
                        initialValue = false,
                        startStrategy = START_WHEN_NEEDED
                    )
                }
                .toList()

        val combinedCondition =
            CombinedCondition(scope = this, conditions = conditions, operand = Evaluator.OP_OR)

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)
        // Default is to monitor all conditions when false
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(areStarted(conditions)).containsExactly(true, true, true).inOrder()

        // Condition 2 is true, so we should only monitor condition 2
        conditions[1].setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(areStarted(conditions)).containsExactly(false, true, false).inOrder()

        // Condition 2 becomes false, so we go back to monitoring all conditions
        conditions[1].setValue(false)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(areStarted(conditions)).containsExactly(true, true, true).inOrder()

        // Condition 3 becomes true, so we only monitor condition 3
        conditions[2].setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(areStarted(conditions)).containsExactly(false, false, true).inOrder()

        // Condition 2 becomes true, but we are still only monitoring condition 3
        conditions[1].setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(areStarted(conditions)).containsExactly(false, false, true).inOrder()

        // Condition 3 becomes false, so we should now only be monitoring condition 2
        conditions[2].setValue(false)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(areStarted(conditions)).containsExactly(false, true, false).inOrder()
    }

    @Test
    fun testRemovingCallbackWillStopMonitoringAllConditions() = runSelfCancelingTest {
        val conditions =
            0.rangeTo(2)
                .map {
                    FakeCondition(
                        scope = this,
                        initialValue = false,
                        startStrategy = START_WHEN_NEEDED
                    )
                }
                .toList()

        val combinedCondition =
            CombinedCondition(scope = this, conditions = conditions, operand = Evaluator.OP_OR)

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)
        assertThat(areStarted(conditions)).containsExactly(true, true, true)

        combinedCondition.removeCallback(callback)
        assertThat(areStarted(conditions)).containsExactly(false, false, false)
    }

    @Test
    fun testOverridingConditionSkipsOtherConditions() = runSelfCancelingTest {
        val startWhenNeededCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_WHEN_NEEDED)
        val eagerCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_EAGERLY)
        val lazyCondition =
            FakeCondition(scope = this, initialValue = false, startStrategy = START_LAZILY)
        val overridingCondition1 =
            FakeCondition(scope = this, initialValue = null, overriding = true)
        val overridingCondition2 =
            FakeCondition(scope = this, initialValue = null, overriding = true)

        val combinedCondition =
            CombinedCondition(
                scope = this,
                conditions =
                    listOf(
                        eagerCondition,
                        overridingCondition1,
                        lazyCondition,
                        startWhenNeededCondition,
                        overridingCondition2
                    ),
                operand = Evaluator.OP_OR
            )

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isTrue()
        assertThat(lazyCondition.started).isTrue()
        assertThat(startWhenNeededCondition.started).isTrue()
        assertThat(overridingCondition1.started).isTrue()

        // Overriding condition is true, so we should stop monitoring all other conditions
        overridingCondition1.setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(eagerCondition.started).isFalse()
        assertThat(lazyCondition.started).isFalse()
        assertThat(startWhenNeededCondition.started).isFalse()
        assertThat(overridingCondition1.started).isTrue()
        assertThat(overridingCondition2.started).isFalse()

        // Overriding condition is false, so we should only monitor other overriding conditions
        overridingCondition1.setValue(false)
        eagerCondition.setValue(true)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(eagerCondition.started).isFalse()
        assertThat(lazyCondition.started).isFalse()
        assertThat(startWhenNeededCondition.started).isFalse()
        assertThat(overridingCondition1.started).isTrue()
        assertThat(overridingCondition2.started).isTrue()

        // Second overriding condition is true, condition 1 is still true
        overridingCondition2.setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(eagerCondition.started).isFalse()
        assertThat(lazyCondition.started).isFalse()
        assertThat(startWhenNeededCondition.started).isFalse()
        assertThat(overridingCondition1.started).isFalse()
        assertThat(overridingCondition2.started).isTrue()

        // Overriding condition is cleared, condition 1 is still true
        overridingCondition1.setValue(null)
        overridingCondition2.setValue(null)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(eagerCondition.started).isTrue()
        assertThat(lazyCondition.started).isFalse()
        assertThat(startWhenNeededCondition.started).isFalse()
        assertThat(overridingCondition1.started).isTrue()
        assertThat(overridingCondition2.started).isTrue()
    }

    @Test
    fun testAndOperatorCorrectlyHandlesUnknownValues() = runSelfCancelingTest {
        val conditions =
            0.rangeTo(2)
                .map {
                    FakeCondition(scope = this, initialValue = false, startStrategy = START_EAGERLY)
                }
                .toList()

        val combinedCondition =
            CombinedCondition(scope = this, conditions = conditions, operand = Evaluator.OP_AND)

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)

        conditions[0].setValue(null)
        conditions[1].setValue(true)
        conditions[2].setValue(false)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(combinedCondition.isConditionSet).isTrue()

        // The condition should not be set since the value is unknown
        conditions[2].setValue(true)
        assertThat(combinedCondition.isConditionMet).isFalse()
        assertThat(combinedCondition.isConditionSet).isFalse()

        conditions[0].setValue(true)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(combinedCondition.isConditionSet).isTrue()
    }

    @Test
    fun testOrOperatorCorrectlyHandlesUnknownValues() = runSelfCancelingTest {
        val conditions =
            0.rangeTo(2)
                .map {
                    FakeCondition(scope = this, initialValue = false, startStrategy = START_EAGERLY)
                }
                .toList()

        val combinedCondition =
            CombinedCondition(scope = this, conditions = conditions, operand = Evaluator.OP_OR)

        val callback = Condition.Callback {}
        combinedCondition.addCallback(callback)

        conditions[0].setValue(null)
        conditions[1].setValue(true)
        conditions[2].setValue(false)
        assertThat(combinedCondition.isConditionMet).isTrue()
        assertThat(combinedCondition.isConditionSet).isTrue()

        conditions[1].setValue(false)
        assertThat(combinedCondition.isConditionMet).isFalse()
        // The condition should not be set since the value is unknown
        assertThat(combinedCondition.isConditionSet).isFalse()
    }

    @Test
    fun testEmptyConditions() = runSelfCancelingTest {
        for (operand in intArrayOf(Evaluator.OP_OR, Evaluator.OP_AND)) {
            val combinedCondition =
                CombinedCondition(
                    scope = this,
                    conditions = emptyList(),
                    operand = operand,
                )

            val callback = Condition.Callback {}
            combinedCondition.addCallback(callback)
            assertThat(combinedCondition.isConditionMet).isFalse()
            assertThat(combinedCondition.isConditionSet).isFalse()
        }
    }

    private fun areStarted(conditions: List<FakeCondition>): List<Boolean> {
        return conditions.map { it.started }
    }

    /**
     * Executes the given block of execution within the scope of a dedicated [CoroutineScope] which
     * is then automatically canceled and cleaned-up.
     */
    private fun runSelfCancelingTest(
        block: suspend CoroutineScope.() -> Unit,
    ) =
        runBlocking(IMMEDIATE) {
            val scope = CoroutineScope(coroutineContext + Job())
            block(scope)
            scope.cancel()
        }

    companion object {
        private val IMMEDIATE = Dispatchers.Main.immediate
    }
}
