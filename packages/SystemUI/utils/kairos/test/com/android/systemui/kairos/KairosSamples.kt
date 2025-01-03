/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.systemui.kairos

import com.android.systemui.kairos.util.MapPatch
import com.android.systemui.kairos.util.These
import com.android.systemui.kairos.util.maybeOf
import com.android.systemui.kairos.util.toMaybe
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class KairosSamples {

    @Test fun test_mapMaybe() = runSample { mapMaybe() }

    fun BuildScope.mapMaybe() {
        val emitter = MutableEvents<String>()
        val ints = emitter.mapMaybe { it.toIntOrNull().toMaybe() }

        var observedInput: String? = null
        emitter.observe { observedInput = it }

        var observedInt: Int? = null
        ints.observe { observedInt = it }

        launchEffect {
            // parse succeeds
            emitter.emit("6")
            assertEquals(observedInput, "6")
            assertEquals(observedInt, 6)

            // parse fails
            emitter.emit("foo")
            assertEquals(observedInput, "foo")
            assertEquals(observedInt, 6)

            // parse succeeds
            emitter.emit("500")
            assertEquals(observedInput, "500")
            assertEquals(observedInt, 500)
        }
    }

    @Test fun test_mapCheap() = runSample { mapCheap() }

    fun BuildScope.mapCheap() {
        val emitter = MutableEvents<Int>()

        var invocationCount = 0
        val squared =
            emitter.mapCheap {
                invocationCount++
                it * it
            }

        var observedSquare: Int? = null
        squared.observe { observedSquare = it }

        launchEffect {
            emitter.emit(10)
            assertTrue(invocationCount >= 1)
            assertEquals(observedSquare, 100)

            emitter.emit(2)
            assertTrue(invocationCount >= 2)
            assertEquals(observedSquare, 4)
        }
    }

    @Test fun test_mapEvents() = runSample { mapEvents() }

    fun BuildScope.mapEvents() {
        val emitter = MutableEvents<Int>()

        val squared = emitter.map { it * it }

        var observedSquare: Int? = null
        squared.observe { observedSquare = it }

        launchEffect {
            emitter.emit(10)
            assertEquals(observedSquare, 100)

            emitter.emit(2)
            assertEquals(observedSquare, 4)
        }
    }

    @Test fun test_eventsLoop() = runSample { eventsLoop() }

    fun BuildScope.eventsLoop() {
        val emitter = MutableEvents<Unit>()
        var newCount: Events<Int> by EventsLoop()
        val count = newCount.holdState(0)
        newCount = emitter.map { count.sample() + 1 }

        var observedCount = 0
        count.observe { observedCount = it }

        launchEffect {
            emitter.emit(Unit)
            assertEquals(observedCount, expected = 1)

            emitter.emit(Unit)
            assertEquals(observedCount, expected = 2)
        }
    }

    @Test fun test_stateLoop() = runSample { stateLoop() }

    fun BuildScope.stateLoop() {
        val emitter = MutableEvents<Unit>()
        var count: State<Int> by StateLoop()
        count = emitter.map { count.sample() + 1 }.holdState(0)

        var observedCount = 0
        count.observe { observedCount = it }

        launchEffect {
            emitter.emit(Unit)
            assertEquals(observedCount, expected = 1)

            emitter.emit(Unit)
            assertEquals(observedCount, expected = 2)
        }
    }

    @Test fun test_changes() = runSample { changes() }

    fun BuildScope.changes() {
        val emitter = MutableEvents<Int>()
        val state = emitter.holdState(0)

        var numEmissions = 0
        emitter.observe { numEmissions++ }

        var observedState = 0
        var numChangeEmissions = 0
        state.changes.observe {
            observedState = it
            numChangeEmissions++
        }

        launchEffect {
            emitter.emit(0)
            assertEquals(numEmissions, expected = 1)
            assertEquals(numChangeEmissions, expected = 0)
            assertEquals(observedState, expected = 0)

            emitter.emit(5)
            assertEquals(numEmissions, expected = 2)
            assertEquals(numChangeEmissions, expected = 1)
            assertEquals(observedState, expected = 5)

            emitter.emit(3)
            assertEquals(numEmissions, expected = 3)
            assertEquals(numChangeEmissions, expected = 2)
            assertEquals(observedState, expected = 3)

            emitter.emit(3)
            assertEquals(numEmissions, expected = 4)
            assertEquals(numChangeEmissions, expected = 2)
            assertEquals(observedState, expected = 3)

            emitter.emit(5)
            assertEquals(numEmissions, expected = 5)
            assertEquals(numChangeEmissions, expected = 3)
            assertEquals(observedState, expected = 5)
        }
    }

    @Test fun test_partitionThese() = runSample { partitionThese() }

    fun BuildScope.partitionThese() {
        val emitter = MutableEvents<These<Int, String>>()
        val (lefts, rights) = emitter.partitionThese()

        var observedLeft: Int? = null
        lefts.observe { observedLeft = it }

        var observedRight: String? = null
        rights.observe { observedRight = it }

        launchEffect {
            emitter.emit(These.first(10))
            assertEquals(observedLeft, 10)
            assertEquals(observedRight, null)

            emitter.emit(These.both(2, "foo"))
            assertEquals(observedLeft, 2)
            assertEquals(observedRight, "foo")

            emitter.emit(These.second("bar"))
            assertEquals(observedLeft, 2)
            assertEquals(observedRight, "bar")
        }
    }

    @Test fun test_merge() = runSample { merge() }

    fun BuildScope.merge() {
        val emitter = MutableEvents<Int>()
        val fizz = emitter.mapNotNull { if (it % 3 == 0) "Fizz" else null }
        val buzz = emitter.mapNotNull { if (it % 5 == 0) "Buzz" else null }
        val fizzbuzz = fizz.mergeWith(buzz) { _, _ -> "Fizz Buzz" }
        val output = mergeLeft(fizzbuzz, emitter.mapCheap { it.toString() })

        var observedOutput: String? = null
        output.observe { observedOutput = it }

        launchEffect {
            emitter.emit(1)
            assertEquals(observedOutput, "1")
            emitter.emit(2)
            assertEquals(observedOutput, "2")
            emitter.emit(3)
            assertEquals(observedOutput, "Fizz")
            emitter.emit(4)
            assertEquals(observedOutput, "4")
            emitter.emit(5)
            assertEquals(observedOutput, "Buzz")
            emitter.emit(6)
            assertEquals(observedOutput, "Fizz")
            emitter.emit(15)
            assertEquals(observedOutput, "Fizz Buzz")
        }
    }

    @Test fun test_groupByKey() = runSample { groupByKey() }

    fun BuildScope.groupByKey() {
        val emitter = MutableEvents<Map<String, Int>>()
        val grouped = emitter.groupByKey()
        val groupA = grouped["A"]
        val groupB = grouped["B"]

        var numEmissions = 0
        emitter.observe { numEmissions++ }

        var observedA: Int? = null
        groupA.observe { observedA = it }

        var observedB: Int? = null
        groupB.observe { observedB = it }

        launchEffect {
            // emit to group A
            emitter.emit(mapOf("A" to 3))
            assertEquals(numEmissions, 1)
            assertEquals(observedA, 3)
            assertEquals(observedB, null)

            // emit to groups B and C, even though there are no observers of C
            emitter.emit(mapOf("B" to 9, "C" to 100))
            assertEquals(numEmissions, 2)
            assertEquals(observedA, 3)
            assertEquals(observedB, 9)

            // emit to groups A and B
            emitter.emit(mapOf("B" to 6, "A" to 14))
            assertEquals(numEmissions, 3)
            assertEquals(observedA, 14)
            assertEquals(observedB, 6)

            // emit to group with no listeners
            emitter.emit(mapOf("Q" to -66))
            assertEquals(numEmissions, 4)
            assertEquals(observedA, 14)
            assertEquals(observedB, 6)

            // no-op emission
            emitter.emit(emptyMap())
            assertEquals(numEmissions, 5)
            assertEquals(observedA, 14)
            assertEquals(observedB, 6)
        }
    }

    @Test fun test_switchEvents() = runSample { switchEvents() }

    fun BuildScope.switchEvents() {
        val negator = MutableEvents<Unit>()
        val emitter = MutableEvents<Int>()
        val negate = negator.foldState(false) { _, negate -> !negate }
        val output =
            negate.map { negate -> if (negate) emitter.map { it * -1 } else emitter }.switchEvents()

        var observed: Int? = null
        output.observe { observed = it }

        launchEffect {
            // emit like normal
            emitter.emit(10)
            assertEquals(observed, 10)

            // enable negation
            observed = null
            negator.emit(Unit)
            assertEquals(observed, null)

            emitter.emit(99)
            assertEquals(observed, -99)

            // disable negation
            observed = null
            negator.emit(Unit)
            emitter.emit(7)
            assertEquals(observed, 7)
        }
    }

    @Test fun test_switchEventsPromptly() = runSample { switchEventsPromptly() }

    fun BuildScope.switchEventsPromptly() {
        val emitter = MutableEvents<Int>()
        val enabled = emitter.map { it > 10 }.holdState(false)
        val switchedIn = enabled.map { enabled -> if (enabled) emitter else emptyEvents }
        val deferredSwitch = switchedIn.switchEvents()
        val promptSwitch = switchedIn.switchEventsPromptly()

        var observedDeferred: Int? = null
        deferredSwitch.observe { observedDeferred = it }

        var observedPrompt: Int? = null
        promptSwitch.observe { observedPrompt = it }

        launchEffect {
            emitter.emit(3)
            assertEquals(observedDeferred, null)
            assertEquals(observedPrompt, null)

            emitter.emit(20)
            assertEquals(observedDeferred, null)
            assertEquals(observedPrompt, 20)

            emitter.emit(30)
            assertEquals(observedDeferred, 30)
            assertEquals(observedPrompt, 30)

            emitter.emit(8)
            assertEquals(observedDeferred, 8)
            assertEquals(observedPrompt, 8)

            emitter.emit(1)
            assertEquals(observedDeferred, 8)
            assertEquals(observedPrompt, 8)
        }
    }

    @Test fun test_sampleTransactional() = runSample { sampleTransactional() }

    fun BuildScope.sampleTransactional() {
        var store = 0
        val transactional = transactionally { store++ }

        effect {
            assertEquals(store, 0)
            assertEquals(transactional.sample(), 0)
            assertEquals(store, 1)
            assertEquals(transactional.sample(), 0)
            assertEquals(store, 1)
        }
    }

    @Test fun test_states() = runSample { states() }

    fun BuildScope.states() {
        val constantState = stateOf(10)
        effect { assertEquals(constantState.sample(), 10) }

        val mappedConstantState: State<Int> = constantState.map { it * 2 }
        effect { assertEquals(mappedConstantState.sample(), 20) }

        val emitter = MutableEvents<Int>()
        val heldState: State<Int?> = emitter.holdState(null)
        effect { assertEquals(heldState.sample(), null) }

        var observed: Int? = null
        var wasObserved = false
        heldState.observe {
            observed = it
            wasObserved = true
        }
        launchEffect {
            assertTrue(wasObserved)
            emitter.emit(4)
            assertEquals(observed, 4)
        }

        val combinedStates: State<Pair<Int, Int?>> =
            combine(mappedConstantState, heldState) { a, b -> Pair(a, b) }

        effect { assertEquals(combinedStates.sample(), 20 to null) }

        var observedPair: Pair<Int, Int?>? = null
        combinedStates.observe { observedPair = it }
        launchEffect {
            emitter.emit(12)
            assertEquals(observedPair, 20 to 12)
        }
    }

    @Test fun test_holdState() = runSample { holdState() }

    fun BuildScope.holdState() {
        val emitter = MutableEvents<Int>()
        val heldState: State<Int?> = emitter.holdState(null)
        effect { assertEquals(heldState.sample(), null) }

        var observed: Int? = null
        var wasObserved = false
        heldState.observe {
            observed = it
            wasObserved = true
        }
        launchEffect {
            // observation of the initial state took place immediately
            assertTrue(wasObserved)

            // state changes are also observed
            emitter.emit(4)
            assertEquals(observed, 4)

            emitter.emit(20)
            assertEquals(observed, 20)
        }
    }

    @Test fun test_mapState() = runSample { mapState() }

    fun BuildScope.mapState() {
        val emitter = MutableEvents<Int>()
        val held: State<Int> = emitter.holdState(0)
        val squared: State<Int> = held.map { it * it }

        var observed: Int? = null
        squared.observe { observed = it }

        launchEffect {
            assertEquals(observed, 0)

            emitter.emit(10)
            assertEquals(observed, 100)
        }
    }

    @Test fun test_combineState() = runSample { combineState() }

    fun BuildScope.combineState() {
        val emitter = MutableEvents<Int>()
        val state = emitter.holdState(0)
        val squared = state.map { it * it }
        val negated = state.map { -it }
        val combined = squared.combine(negated) { a, b -> Pair(a, b) }

        val observed = mutableListOf<Pair<Int, Int>>()
        combined.observe { observed.add(it) }

        launchEffect {
            emitter.emit(10)
            emitter.emit(20)
            emitter.emit(3)

            assertEquals(observed, listOf(0 to 0, 100 to -10, 400 to -20, 9 to -3))
        }
    }

    @Test fun test_flatMap() = runSample { flatMap() }

    fun BuildScope.flatMap() {
        val toggler = MutableEvents<Unit>()
        val firstEmitter = MutableEvents<Unit>()
        val secondEmitter = MutableEvents<Unit>()

        val firstCount: State<Int> = firstEmitter.foldState(0) { _, count -> count + 1 }
        val secondCount: State<Int> = secondEmitter.foldState(0) { _, count -> count + 1 }
        val toggleState: State<Boolean> = toggler.foldState(true) { _, state -> !state }

        val activeCount: State<Int> =
            toggleState.flatMap { b -> if (b) firstCount else secondCount }

        var observed: Int? = null
        activeCount.observe { observed = it }

        launchEffect {
            assertEquals(observed, 0)

            firstEmitter.emit(Unit)
            assertEquals(observed, 1)

            secondEmitter.emit(Unit)
            assertEquals(observed, 1)

            secondEmitter.emit(Unit)
            assertEquals(observed, 1)

            toggler.emit(Unit)
            assertEquals(observed, 2)

            toggler.emit(Unit)
            assertEquals(observed, 1)
        }
    }

    @Test fun test_incrementals() = runSample { incrementals() }

    fun BuildScope.incrementals() {
        val patchEmitter = MutableEvents<MapPatch<String, Int>>()
        val incremental: Incremental<String, Int> = patchEmitter.foldStateMapIncrementally()
        val squared = incremental.mapValues { (key, value) -> value * value }

        var observedUpdate: MapPatch<String, Int>? = null
        squared.updates.observe { observedUpdate = it }

        var observedState: Map<String, Int>? = null
        squared.observe { observedState = it }

        launchEffect {
            assertEquals(observedState, emptyMap())
            assertEquals(observedUpdate, null)

            // add entry: A => 10
            patchEmitter.emit(mapOf("A" to maybeOf(10)))
            assertEquals(observedState, mapOf("A" to 100))
            assertEquals(observedUpdate, mapOf("A" to maybeOf(100)))

            // update entry: A => 5
            // add entry: B => 6
            patchEmitter.emit(mapOf("A" to maybeOf(5), "B" to maybeOf(6)))
            assertEquals(observedState, mapOf("A" to 25, "B" to 36))
            assertEquals(observedUpdate, mapOf("A" to maybeOf(25), "B" to maybeOf(36)))

            // remove entry: A
            // add entry: C => 9
            // remove non-existent entry: F
            patchEmitter.emit(mapOf("A" to maybeOf(), "C" to maybeOf(9), "F" to maybeOf()))
            assertEquals(observedState, mapOf("B" to 36, "C" to 81))
            // non-existent entry is filtered from the update
            assertEquals(observedUpdate, mapOf("A" to maybeOf(), "C" to maybeOf(81)))
        }
    }

    @Test fun test_mergeEventsIncrementally() = runSample(block = mergeEventsIncrementally())

    fun mergeEventsIncrementally(): BuildSpec<Unit> = buildSpec {
        val patchEmitter = MutableEvents<MapPatch<String, Events<Int>>>()
        val incremental: Incremental<String, Events<Int>> = patchEmitter.foldStateMapIncrementally()
        val merged: Events<Map<String, Int>> = incremental.mergeEventsIncrementally()

        var observed: Map<String, Int>? = null
        merged.observe { observed = it }

        launchEffect {
            // add events entry: A
            val emitterA = MutableEvents<Int>()
            patchEmitter.emit(mapOf("A" to maybeOf(emitterA)))

            emitterA.emit(100)
            assertEquals(observed, mapOf("A" to 100))

            // add events entry: B
            val emitterB = MutableEvents<Int>()
            patchEmitter.emit(mapOf("B" to maybeOf(emitterB)))

            // merged emits from both A and B
            emitterB.emit(5)
            assertEquals(observed, mapOf("B" to 5))

            emitterA.emit(20)
            assertEquals(observed, mapOf("A" to 20))

            // remove entry: A
            patchEmitter.emit(mapOf("A" to maybeOf()))
            emitterA.emit(0)
            // event is not emitted now that A has been removed
            assertEquals(observed, mapOf("A" to 20))

            // but B still works
            emitterB.emit(3)
            assertEquals(observed, mapOf("B" to 3))
        }
    }

    @Test
    fun test_mergeEventsIncrementallyPromptly() =
        runSample(block = mergeEventsIncrementallyPromptly())

    fun mergeEventsIncrementallyPromptly(): BuildSpec<Unit> = buildSpec {
        val patchEmitter = MutableEvents<MapPatch<String, Events<Int>>>()
        val incremental: Incremental<String, Events<Int>> = patchEmitter.foldStateMapIncrementally()
        val deferredMerge: Events<Map<String, Int>> = incremental.mergeEventsIncrementally()
        val promptMerge: Events<Map<String, Int>> = incremental.mergeEventsIncrementallyPromptly()

        var observedDeferred: Map<String, Int>? = null
        deferredMerge.observe { observedDeferred = it }

        var observedPrompt: Map<String, Int>? = null
        promptMerge.observe { observedPrompt = it }

        launchEffect {
            val emitterA = MutableEvents<Int>()
            patchEmitter.emit(mapOf("A" to maybeOf(emitterA)))

            emitterA.emit(100)
            assertEquals(observedDeferred, mapOf("A" to 100))
            assertEquals(observedPrompt, mapOf("A" to 100))

            val emitterB = patchEmitter.map { 5 }
            patchEmitter.emit(mapOf("B" to maybeOf(emitterB)))

            assertEquals(observedDeferred, mapOf("A" to 100))
            assertEquals(observedPrompt, mapOf("B" to 5))
        }
    }

    @Test fun test_applyLatestStateful() = runSample(block = applyLatestStateful())

    fun applyLatestStateful(): BuildSpec<Unit> = buildSpec {
        val reset = MutableEvents<Unit>()
        val emitter = MutableEvents<Unit>()
        val stateEvents: Events<State<Int>> =
            reset
                .map { statefully { emitter.foldState(0) { _, count -> count + 1 } } }
                .applyLatestStateful()
        val activeState: State<State<Int>?> = stateEvents.holdState(null)

        launchEffect {
            // nothing is active yet
            kairosNetwork.transact { assertEquals(activeState.sample(), null) }

            // activate the counter
            reset.emit(Unit)
            val firstState =
                kairosNetwork.transact {
                    assertEquals(activeState.sample()?.sample(), 0)
                    activeState.sample()!!
                }

            // emit twice
            emitter.emit(Unit)
            emitter.emit(Unit)
            kairosNetwork.transact { assertEquals(firstState.sample(), 2) }

            // start a new counter, disabling the old one
            reset.emit(Unit)
            val secondState =
                kairosNetwork.transact {
                    assertEquals(activeState.sample()?.sample(), 0)
                    activeState.sample()!!
                }
            kairosNetwork.transact { assertEquals(firstState.sample(), 2) }

            // emit: the new counter updates, but the old one does not
            emitter.emit(Unit)
            kairosNetwork.transact { assertEquals(secondState.sample(), 1) }
            kairosNetwork.transact { assertEquals(firstState.sample(), 2) }
        }
    }

    @Test fun test_applyLatestStatefulForKey() = runSample(block = applyLatestStatefulForKey())

    fun applyLatestStatefulForKey(): BuildSpec<Unit> = buildSpec {
        val reset = MutableEvents<String>()
        val emitter = MutableEvents<String>()
        val stateEvents: Events<MapPatch<String, State<Int>>> =
            reset
                .map { key ->
                    mapOf(
                        key to
                            maybeOf(
                                statefully {
                                    emitter
                                        .filter { it == key }
                                        .foldState(0) { _, count -> count + 1 }
                                }
                            )
                    )
                }
                .applyLatestStatefulForKey()
        val activeStatesByKey: Incremental<String, State<Int>> =
            stateEvents.foldStateMapIncrementally(emptyMap())

        launchEffect {
            // nothing is active yet
            kairosNetwork.transact { assertEquals(activeStatesByKey.sample(), emptyMap()) }

            // activate a new entry A
            reset.emit("A")
            val firstStateA =
                kairosNetwork.transact {
                    val stateMap: Map<String, State<Int>> = activeStatesByKey.sample()
                    assertEquals(stateMap.keys, setOf("A"))
                    stateMap.getValue("A").also { assertEquals(it.sample(), 0) }
                }

            // emit twice to A
            emitter.emit("A")
            emitter.emit("A")
            kairosNetwork.transact { assertEquals(firstStateA.sample(), 2) }

            // active a new entry B
            reset.emit("B")
            val firstStateB =
                kairosNetwork.transact {
                    val stateMap: Map<String, State<Int>> = activeStatesByKey.sample()
                    assertEquals(stateMap.keys, setOf("A", "B"))
                    stateMap.getValue("B").also {
                        assertEquals(it.sample(), 0)
                        assertEquals(firstStateA.sample(), 2)
                    }
                }

            // emit once to B
            emitter.emit("B")
            kairosNetwork.transact {
                assertEquals(firstStateA.sample(), 2)
                assertEquals(firstStateB.sample(), 1)
            }

            // activate a new entry for A, disabling the old entry
            reset.emit("A")
            val secondStateA =
                kairosNetwork.transact {
                    val stateMap: Map<String, State<Int>> = activeStatesByKey.sample()
                    assertEquals(stateMap.keys, setOf("A", "B"))
                    stateMap.getValue("A").also {
                        assertEquals(it.sample(), 0)
                        assertEquals(firstStateB.sample(), 1)
                    }
                }

            // emit to A: the new A state updates, but the old one does not
            emitter.emit("A")
            kairosNetwork.transact {
                assertEquals(firstStateA.sample(), 2)
                assertEquals(secondStateA.sample(), 1)
            }
        }
    }

    private fun runSample(
        dispatcher: TestDispatcher = UnconfinedTestDispatcher(),
        block: BuildScope.() -> Unit,
    ) {
        runTest(dispatcher, timeout = 1.seconds) {
            val kairosNetwork = backgroundScope.launchKairosNetwork()
            backgroundScope.launch { kairosNetwork.activateSpec { block() } }
        }
    }
}

private fun <T> assertEquals(actual: T, expected: T) = Assert.assertEquals(expected, actual)
