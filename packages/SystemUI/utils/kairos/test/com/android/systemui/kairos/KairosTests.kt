/*
 * Copyright (C) 2024 The Android Open Source Project
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

@file:OptIn(ExperimentalCoroutinesApi::class, ExperimentalFrpApi::class)

package com.android.systemui.kairos

import com.android.systemui.kairos.util.Either
import com.android.systemui.kairos.util.Left
import com.android.systemui.kairos.util.Maybe
import com.android.systemui.kairos.util.None
import com.android.systemui.kairos.util.Right
import com.android.systemui.kairos.util.just
import com.android.systemui.kairos.util.map
import com.android.systemui.kairos.util.maybe
import com.android.systemui.kairos.util.none
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit
import kotlin.time.measureTime
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.toCollection
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KairosTests {

    @Test
    fun basic() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Int>()
        var result: Int? = null
        activateSpec(network) { emitter.observe { result = it } }
        runCurrent()
        emitter.emit(3)
        runCurrent()
        assertEquals(3, result)
        runCurrent()
    }

    @Test
    fun basicTFlow() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Int>()
        println("starting network")
        val result = activateSpecWithResult(network) { emitter.nextDeferred() }
        runCurrent()
        println("emitting")
        emitter.emit(3)
        runCurrent()
        println("awaiting")
        assertEquals(3, result.await())
        runCurrent()
    }

    @Test
    fun basicTState() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Int>()
        val result = activateSpecWithResult(network) { emitter.hold(0).stateChanges.nextDeferred() }
        runCurrent()

        emitter.emit(3)
        runCurrent()

        assertEquals(3, result.await())
    }

    @Test
    fun basicEvent() = runFrpTest { network ->
        val emitter = MutableSharedFlow<Int>()
        val result = activateSpecWithResult(network) { async { emitter.first() } }
        runCurrent()
        emitter.emit(1)
        runCurrent()
        assertTrue("Result eventual has not completed.", result.isCompleted)
        assertEquals(1, result.await())
    }

    @Test
    fun basicTransactional() = runFrpTest { network ->
        var value: Int? = null
        var bSource = 1
        val emitter = network.mutableTFlow<Unit>()
        // Sampling this transactional will increment the source count.
        val transactional = transactionally { bSource++ }
        measureTime {
                activateSpecWithResult(network) {
                        // Two different flows that sample the same transactional.
                        (0 until 2).map {
                            val sampled = emitter.sample(transactional) { _, v -> v }
                            sampled.toSharedFlow()
                        }
                    }
                    .forEach { backgroundScope.launch { it.collect { value = it } } }
                runCurrent()
            }
            .also { println("setup: ${it.toString(DurationUnit.MILLISECONDS, 2)}") }

        measureTime {
                emitter.emit(Unit)
                runCurrent()
            }
            .also { println("emit 1: ${it.toString(DurationUnit.MILLISECONDS, 2)}") }

        // Even though the transactional would be sampled twice, the first result is cached.
        assertEquals(2, bSource)
        assertEquals(1, value)

        measureTime {
                bSource = 10
                emitter.emit(Unit)
                runCurrent()
            }
            .also { println("emit 2: ${it.toString(DurationUnit.MILLISECONDS, 2)}") }

        assertEquals(11, bSource)
        assertEquals(10, value)
    }

    @Test
    fun diamondGraph() = runFrpTest { network ->
        val flow = network.mutableTFlow<Int>()
        val outFlow =
            activateSpecWithResult(network) {
                // map TFlow like we map Flow
                val left = flow.map { "left" to it }.onEach { println("left: $it") }
                val right = flow.map { "right" to it }.onEach { println("right: $it") }

                // convert TFlows to TStates so that they can be combined
                val combined =
                    left.hold("left" to 0).combineWith(right.hold("right" to 0)) { l, r -> l to r }
                combined.stateChanges // get TState changes
                    .onEach { println("merged: $it") }
                    .toSharedFlow() // convert back to Flow
            }
        runCurrent()

        val results = mutableListOf<Pair<Pair<String, Int>, Pair<String, Int>>>()
        backgroundScope.launch { outFlow.toCollection(results) }
        runCurrent()

        flow.emit(1)
        runCurrent()

        flow.emit(2)
        runCurrent()

        assertEquals(
            listOf(("left" to 1) to ("right" to 1), ("left" to 2) to ("right" to 2)),
            results,
        )
    }

    @Test
    fun staticNetwork() = runFrpTest { network ->
        var finalSum: Int? = null

        val intEmitter = network.mutableTFlow<Int>()
        val sampleEmitter = network.mutableTFlow<Unit>()

        activateSpecWithResult(network) {
                val updates = intEmitter.map { a -> { b: Int -> a + b } }

                val sumD =
                    TStateLoop<Int>().apply {
                        loopback =
                            updates
                                .sample(this) { f, sum -> f(sum) }
                                .onEach { println("sum update: $it") }
                                .hold(0)
                    }
                sampleEmitter
                    .onEach { println("sampleEmitter emitted") }
                    .sample(sumD) { _, sum -> sum }
                    .onEach { println("sampled: $it") }
                    .nextDeferred()
            }
            .let { launch { finalSum = it.await() } }

        runCurrent()

        (1..5).forEach { i ->
            println("emitting: $i")
            intEmitter.emit(i)
            runCurrent()
        }
        runCurrent()

        sampleEmitter.emit(Unit)
        runCurrent()

        assertEquals(15, finalSum)
    }

    @Test
    fun recursiveDefinition() = runFrpTest { network ->
        var wasSold = false
        var currentAmt: Int? = null

        val coin = network.mutableTFlow<Unit>()
        val price = 50
        val frpSpec = frpSpec {
            val eSold = TFlowLoop<Unit>()

            val eInsert =
                coin.map {
                    { runningTotal: Int ->
                        println("TEST: $runningTotal - 10 = ${runningTotal - 10}")
                        runningTotal - 10
                    }
                }

            val eReset =
                eSold.map {
                    { _: Int ->
                        println("TEST: Resetting")
                        price
                    }
                }

            val eUpdate = eInsert.mergeWith(eReset) { f, g -> { a -> g(f(a)) } }

            val dTotal = TStateLoop<Int>()
            dTotal.loopback = eUpdate.sample(dTotal) { f, total -> f(total) }.hold(price)

            val eAmt = dTotal.stateChanges
            val bAmt = transactionally { dTotal.sample() }
            eSold.loopback =
                coin
                    .sample(bAmt) { coin, total -> coin to total }
                    .mapMaybe { (_, total) -> maybe { guard { total <= 10 } } }

            val amts = eAmt.filter { amt -> amt >= 0 }

            amts.observe { currentAmt = it }
            eSold.observe { wasSold = true }

            eSold.nextDeferred()
        }

        activateSpec(network) { frpSpec.applySpec() }

        runCurrent()

        println()
        println()
        coin.emit(Unit)
        runCurrent()

        assertEquals(40, currentAmt)

        println()
        println()
        coin.emit(Unit)
        runCurrent()

        assertEquals(30, currentAmt)

        println()
        println()
        coin.emit(Unit)
        runCurrent()

        assertEquals(20, currentAmt)

        println()
        println()
        coin.emit(Unit)
        runCurrent()

        assertEquals(10, currentAmt)
        assertEquals(false, wasSold)

        println()
        println()
        coin.emit(Unit)
        runCurrent()

        assertEquals(true, wasSold)
        assertEquals(50, currentAmt)
    }

    @Test
    fun promptCleanup() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Int>()
        val stopper = network.mutableTFlow<Unit>()

        var result: Int? = null

        val flow = activateSpecWithResult(network) { emitter.takeUntil(stopper).toSharedFlow() }
        backgroundScope.launch { flow.collect { result = it } }
        runCurrent()

        emitter.emit(2)
        runCurrent()

        assertEquals(2, result)

        stopper.emit(Unit)
        runCurrent()
    }

    @Test
    fun switchTFlow() = runFrpTest { network ->
        var currentSum: Int? = null

        val switchHandler = network.mutableTFlow<Pair<TFlow<Int>, String>>()
        val aHandler = network.mutableTFlow<Int>()
        val stopHandler = network.mutableTFlow<Unit>()
        val bHandler = network.mutableTFlow<Int>()

        val sumFlow =
            activateSpecWithResult(network) {
                val switchE = TFlowLoop<TFlow<Int>>()
                switchE.loopback =
                    switchHandler.mapStateful { (intFlow, name) ->
                        println("[onEach] Switching to: $name")
                        val nextSwitch =
                            switchE.skipNext().onEach { println("[onEach] switched-out") }
                        val stopEvent =
                            stopHandler
                                .onEach { println("[onEach] stopped") }
                                .mergeWith(nextSwitch) { _, b -> b }
                        intFlow.takeUntil(stopEvent)
                    }

                val adderE: TFlow<(Int) -> Int> =
                    switchE.hold(emptyTFlow).switch().map { a ->
                        println("[onEach] new number $a")
                        ({ sum: Int ->
                            println("$a+$sum=${a + sum}")
                            sum + a
                        })
                    }

                val sumD = TStateLoop<Int>()
                sumD.loopback =
                    adderE
                        .sample(sumD) { f, sum -> f(sum) }
                        .onEach { println("[onEach] writing sum: $it") }
                        .hold(0)
                val sumE = sumD.stateChanges

                sumE.toSharedFlow()
            }

        runCurrent()

        backgroundScope.launch { sumFlow.collect { currentSum = it } }

        runCurrent()

        switchHandler.emit(aHandler to "A")
        runCurrent()

        aHandler.emit(1)
        runCurrent()

        assertEquals(1, currentSum)

        aHandler.emit(2)
        runCurrent()

        assertEquals(3, currentSum)

        aHandler.emit(3)
        runCurrent()

        assertEquals(6, currentSum)

        aHandler.emit(4)
        runCurrent()

        assertEquals(10, currentSum)

        aHandler.emit(5)
        runCurrent()

        assertEquals(15, currentSum)

        switchHandler.emit(bHandler to "B")
        runCurrent()

        aHandler.emit(6)
        runCurrent()

        assertEquals(15, currentSum)

        bHandler.emit(6)
        runCurrent()

        assertEquals(21, currentSum)

        bHandler.emit(7)
        runCurrent()

        assertEquals(28, currentSum)

        bHandler.emit(8)
        runCurrent()

        assertEquals(36, currentSum)

        bHandler.emit(9)
        runCurrent()

        assertEquals(45, currentSum)

        bHandler.emit(10)
        runCurrent()

        assertEquals(55, currentSum)

        println()
        println("Stopping: B")
        stopHandler.emit(Unit) // bHandler.complete()
        runCurrent()

        bHandler.emit(20)
        runCurrent()

        assertEquals(55, currentSum)

        println()
        println("Switching to: A2")
        switchHandler.emit(aHandler to "A2")
        runCurrent()

        println("aHandler.emit(11)")
        aHandler.emit(11)
        runCurrent()

        assertEquals(66, currentSum)

        aHandler.emit(12)
        runCurrent()

        assertEquals(78, currentSum)

        aHandler.emit(13)
        runCurrent()

        assertEquals(91, currentSum)

        aHandler.emit(14)
        runCurrent()

        assertEquals(105, currentSum)

        aHandler.emit(15)
        runCurrent()

        assertEquals(120, currentSum)

        stopHandler.emit(Unit)
        runCurrent()

        aHandler.emit(100)
        runCurrent()

        assertEquals(120, currentSum)
    }

    @Test
    fun switchIndirect() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Unit>()
        activateSpec(network) {
            emptyTFlow.map { emitter.map { 1 } }.flatten().map { "$it" }.observe()
        }
        runCurrent()
    }

    @Test
    fun switchInWithResult() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Unit>()
        val out =
            activateSpecWithResult(network) {
                emitter.map { emitter.map { 1 } }.flatten().toSharedFlow()
            }
        val result = out.stateIn(backgroundScope, SharingStarted.Eagerly, null)
        runCurrent()
        emitter.emit(Unit)
        runCurrent()
        assertEquals(null, result.value)
    }

    @Test
    fun switchInCompleted() = runFrpTest { network ->
        val outputs = mutableListOf<Int>()

        val switchAH = network.mutableTFlow<Unit>()
        val intAH = network.mutableTFlow<Int>()
        val stopEmitter = network.mutableTFlow<Unit>()

        val top = frpSpec {
            val intS = intAH.takeUntil(stopEmitter)
            val switched = switchAH.map { intS }.flatten()
            switched.toSharedFlow()
        }
        val flow = activateSpecWithResult(network) { top.applySpec() }
        backgroundScope.launch { flow.collect { outputs.add(it) } }
        runCurrent()

        switchAH.emit(Unit)
        runCurrent()

        stopEmitter.emit(Unit)
        runCurrent()

        // assertEquals(0, intAH.subscriptionCount.value)
        intAH.emit(10)
        runCurrent()

        assertEquals(true, outputs.isEmpty())

        switchAH.emit(Unit)
        runCurrent()

        // assertEquals(0, intAH.subscriptionCount.value)
        intAH.emit(10)
        runCurrent()

        assertEquals(true, outputs.isEmpty())
    }

    @Test
    fun switchTFlow_outerCompletesFirst() = runFrpTest { network ->
        var stepResult: Int? = null

        val switchAH = network.mutableTFlow<Unit>()
        val switchStopEmitter = network.mutableTFlow<Unit>()
        val intStopEmitter = network.mutableTFlow<Unit>()
        val intAH = network.mutableTFlow<Int>()
        val flow =
            activateSpecWithResult(network) {
                val intS = intAH.takeUntil(intStopEmitter)
                val switchS = switchAH.takeUntil(switchStopEmitter)

                val switched = switchS.map { intS }.flatten()
                switched.toSharedFlow()
            }
        backgroundScope.launch { flow.collect { stepResult = it } }
        runCurrent()

        // assertEquals(0, intAH.subscriptionCount.value)
        intAH.emit(100)
        runCurrent()

        assertEquals(null, stepResult)

        switchAH.emit(Unit)
        runCurrent()

        //            assertEquals(1, intAH.subscriptionCount.value)

        intAH.emit(5)
        runCurrent()

        assertEquals(5, stepResult)

        println("stop outer")
        switchStopEmitter.emit(Unit) // switchAH.complete()
        runCurrent()

        // assertEquals(1, intAH.subscriptionCount.value)
        // assertEquals(0, switchAH.subscriptionCount.value)

        intAH.emit(10)
        runCurrent()

        assertEquals(10, stepResult)

        println("stop inner")
        intStopEmitter.emit(Unit) // intAH.complete()
        runCurrent()

        // assertEquals(just(10), network.await())
    }

    @Test
    fun mapTFlow() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Int>()
        var stepResult: Int? = null

        val flow =
            activateSpecWithResult(network) {
                val mappedS = emitter.map { it * it }
                mappedS.toSharedFlow()
            }

        backgroundScope.launch { flow.collect { stepResult = it } }
        runCurrent()

        emitter.emit(1)
        runCurrent()

        assertEquals(1, stepResult)

        emitter.emit(2)
        runCurrent()

        assertEquals(4, stepResult)

        emitter.emit(10)
        runCurrent()

        assertEquals(100, stepResult)
    }

    @Test
    fun mapTransactional() = runFrpTest { network ->
        var doubledResult: Int? = null
        var pullValue = 0
        val a = transactionally { pullValue }
        val b = transactionally { a.sample() * 2 }
        val emitter = network.mutableTFlow<Unit>()
        val flow =
            activateSpecWithResult(network) {
                val sampleB = emitter.sample(b) { _, b -> b }
                sampleB.toSharedFlow()
            }

        backgroundScope.launch { flow.collect { doubledResult = it } }

        runCurrent()

        emitter.emit(Unit)
        runCurrent()

        assertEquals(0, doubledResult)

        pullValue = 5
        emitter.emit(Unit)
        runCurrent()

        assertEquals(10, doubledResult)
    }

    @Test
    fun mapTState() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Int>()
        var stepResult: Int? = null
        val flow =
            activateSpecWithResult(network) {
                val state = emitter.hold(0).map { it + 2 }
                val stateCurrent = transactionally { state.sample() }
                val stateChanges = state.stateChanges
                val sampleState = emitter.sample(stateCurrent) { _, b -> b }
                val merge = stateChanges.mergeWith(sampleState) { a, b -> a + b }
                merge.toSharedFlow()
            }
        backgroundScope.launch { flow.collect { stepResult = it } }
        runCurrent()

        emitter.emit(1)
        runCurrent()

        assertEquals(5, stepResult)

        emitter.emit(10)
        runCurrent()

        assertEquals(15, stepResult)
    }

    @Test
    fun partitionEither() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Either<Int, Int>>()
        val result =
            activateSpecWithResult(network) {
                val (l, r) = emitter.partitionEither()
                val pDiamond =
                    l.map { it * 2 }
                        .mergeWith(r.map { it * -1 }) { _, _ -> error("unexpected coincidence") }
                pDiamond.hold(null).toStateFlow()
            }
        runCurrent()

        emitter.emit(Left(10))
        runCurrent()

        assertEquals(20, result.value)

        emitter.emit(Right(30))
        runCurrent()

        assertEquals(-30, result.value)
    }

    @Test
    fun accumTState() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Int>()
        val sampler = network.mutableTFlow<Unit>()
        var stepResult: Int? = null
        val flow =
            activateSpecWithResult(network) {
                val sumState = emitter.map { a -> { b: Int -> a + b } }.fold(0) { f, a -> f(a) }

                sumState.stateChanges
                    .mergeWith(sampler.sample(sumState) { _, sum -> sum }) { _, _ ->
                        error("Unexpected coincidence")
                    }
                    .toSharedFlow()
            }

        backgroundScope.launch { flow.collect { stepResult = it } }
        runCurrent()

        emitter.emit(5)
        runCurrent()
        assertEquals(5, stepResult)

        emitter.emit(10)
        runCurrent()
        assertEquals(15, stepResult)

        sampler.emit(Unit)
        runCurrent()
        assertEquals(15, stepResult)
    }

    @Test
    fun mergeTFlows() = runFrpTest { network ->
        val first = network.mutableTFlow<Int>()
        val stopFirst = network.mutableTFlow<Unit>()
        val second = network.mutableTFlow<Int>()
        val stopSecond = network.mutableTFlow<Unit>()
        var stepResult: Int? = null

        val flow: SharedFlow<Int>
        val setupDuration = measureTime {
            flow =
                activateSpecWithResult(network) {
                    val firstS = first.takeUntil(stopFirst)
                    val secondS = second.takeUntil(stopSecond)
                    val mergedS =
                        firstS.mergeWith(secondS) { _, _ -> error("Unexpected coincidence") }
                    mergedS.toSharedFlow()
                    // mergedS.last("onComplete")
                }
            backgroundScope.launch { flow.collect { stepResult = it } }
            runCurrent()
        }

        //            assertEquals(1, first.subscriptionCount.value)
        //            assertEquals(1, second.subscriptionCount.value)

        val firstEmitDuration = measureTime {
            first.emit(1)
            runCurrent()
        }

        assertEquals(1, stepResult)

        val secondEmitDuration = measureTime {
            second.emit(2)
            runCurrent()
        }

        assertEquals(2, stepResult)

        val stopFirstDuration = measureTime {
            stopFirst.emit(Unit)
            runCurrent()
        }

        // assertEquals(0, first.subscriptionCount.value)
        val testDeadEmitFirstDuration = measureTime {
            first.emit(10)
            runCurrent()
        }

        assertEquals(2, stepResult)

        //            assertEquals(1, second.subscriptionCount.value)

        val secondEmitDuration2 = measureTime {
            second.emit(3)
            runCurrent()
        }

        assertEquals(3, stepResult)

        val stopSecondDuration = measureTime {
            stopSecond.emit(Unit)
            runCurrent()
        }

        // assertEquals(0, second.subscriptionCount.value)
        val testDeadEmitSecondDuration = measureTime {
            second.emit(10)
            runCurrent()
        }

        assertEquals(3, stepResult)

        println(
            """
                setupDuration: ${setupDuration.toString(DurationUnit.MILLISECONDS, 2)}
                firstEmitDuration: ${firstEmitDuration.toString(DurationUnit.MILLISECONDS, 2)}
                secondEmitDuration: ${secondEmitDuration.toString(DurationUnit.MILLISECONDS, 2)}
                stopFirstDuration: ${stopFirstDuration.toString(DurationUnit.MILLISECONDS, 2)}
                testDeadEmitFirstDuration: ${
                    testDeadEmitFirstDuration.toString(
                        DurationUnit.MILLISECONDS,
                        2,
                    )
                }
                secondEmitDuration2: ${secondEmitDuration2.toString(DurationUnit.MILLISECONDS, 2)}
                stopSecondDuration: ${stopSecondDuration.toString(DurationUnit.MILLISECONDS, 2)}
                testDeadEmitSecondDuration: ${
                    testDeadEmitSecondDuration.toString(
                        DurationUnit.MILLISECONDS,
                        2,
                    )
                }
            """
                .trimIndent()
        )
    }

    @Test
    fun sampleCancel() = runFrpTest { network ->
        val updater = network.mutableTFlow<Int>()
        val stopUpdater = network.mutableTFlow<Unit>()
        val sampler = network.mutableTFlow<Unit>()
        val stopSampler = network.mutableTFlow<Unit>()
        var stepResult: Int? = null
        val flow =
            activateSpecWithResult(network) {
                val stopSamplerFirst = stopSampler
                val samplerS = sampler.takeUntil(stopSamplerFirst)
                val stopUpdaterFirst = stopUpdater
                val updaterS = updater.takeUntil(stopUpdaterFirst)
                val sampledS = samplerS.sample(updaterS.hold(0)) { _, b -> b }
                sampledS.toSharedFlow()
            }

        backgroundScope.launch { flow.collect { stepResult = it } }
        runCurrent()

        updater.emit(1)
        runCurrent()

        sampler.emit(Unit)
        runCurrent()

        assertEquals(1, stepResult)

        stopSampler.emit(Unit)
        runCurrent()

        // assertEquals(0, updater.subscriptionCount.value)
        // assertEquals(0, sampler.subscriptionCount.value)
        updater.emit(10)
        runCurrent()

        sampler.emit(Unit)
        runCurrent()

        assertEquals(1, stepResult)
    }

    @Test
    fun combineStates_differentUpstreams() = runFrpTest { network ->
        val a = network.mutableTFlow<Int>()
        val b = network.mutableTFlow<Int>()
        var observed: Pair<Int, Int>? = null
        val tState =
            activateSpecWithResult(network) {
                val state = combine(a.hold(0), b.hold(0)) { a, b -> Pair(a, b) }
                state.stateChanges.observe { observed = it }
                state
            }
        assertEquals(0 to 0, network.transact { tState.sample() })
        assertEquals(null, observed)
        a.emit(5)
        assertEquals(5 to 0, observed)
        assertEquals(5 to 0, network.transact { tState.sample() })
        b.emit(3)
        assertEquals(5 to 3, observed)
        assertEquals(5 to 3, network.transact { tState.sample() })
    }

    @Test
    fun sampleCombinedStates() = runFrpTest { network ->
        val updater = network.mutableTFlow<Int>()
        val emitter = network.mutableTFlow<Unit>()

        val result =
            activateSpecWithResult(network) {
                val bA = updater.map { it * 2 }.hold(0)
                val bB = updater.hold(0)
                val combineD: TState<Pair<Int, Int>> = bA.combineWith(bB) { a, b -> a to b }
                val sampleS = emitter.sample(combineD) { _, b -> b }
                sampleS.nextDeferred()
            }
        println("launching")
        runCurrent()

        println("emitting update")
        updater.emit(10)
        runCurrent()

        println("emitting sampler")
        emitter.emit(Unit)
        runCurrent()

        println("asserting")
        assertEquals(20 to 10, result.await())
    }

    @Test
    fun switchMapPromptly() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Unit>()
        val result =
            activateSpecWithResult(network) {
                emitter
                    .map { emitter.map { 1 }.map { it + 1 }.map { it * 2 } }
                    .hold(emptyTFlow)
                    .switchPromptly()
                    .nextDeferred()
            }
        runCurrent()

        emitter.emit(Unit)
        runCurrent()

        assertTrue("Not complete", result.isCompleted)
        assertEquals(4, result.await())
    }

    @Test
    fun switchDeeper() = runFrpTest { network ->
        val emitter = network.mutableTFlow<Unit>()
        val e2 = network.mutableTFlow<Unit>()
        val result =
            activateSpecWithResult(network) {
                val tres =
                    merge(e2.map { 1 }, e2.map { 2 }, transformCoincidence = { a, b -> a + b })
                tres.observeBuild()
                val switch = emitter.map { tres }.flatten()
                merge(switch, e2.map { null }, transformCoincidence = { a, _ -> a })
                    .filterNotNull()
                    .nextDeferred()
            }
        runCurrent()

        emitter.emit(Unit)
        runCurrent()

        e2.emit(Unit)
        runCurrent()

        assertTrue("Not complete", result.isCompleted)
        assertEquals(3, result.await())
    }

    @Test
    fun recursionBasic() = runFrpTest { network ->
        val add1 = network.mutableTFlow<Unit>()
        val sub1 = network.mutableTFlow<Unit>()
        val stepResult: StateFlow<Int> =
            activateSpecWithResult(network) {
                val dSum = TStateLoop<Int>()
                val sAdd1 = add1.sample(dSum) { _, sum -> sum + 1 }
                val sMinus1 = sub1.sample(dSum) { _, sum -> sum - 1 }
                dSum.loopback = sAdd1.mergeWith(sMinus1) { a, _ -> a }.hold(0)
                dSum.toStateFlow()
            }
        runCurrent()

        add1.emit(Unit)
        runCurrent()

        assertEquals(1, stepResult.value)

        add1.emit(Unit)
        runCurrent()

        assertEquals(2, stepResult.value)

        sub1.emit(Unit)
        runCurrent()

        assertEquals(1, stepResult.value)
    }

    @Test
    fun recursiveTState() = runFrpTest { network ->
        val e = network.mutableTFlow<Unit>()
        var changes = 0
        val state =
            activateSpecWithResult(network) {
                val s = TFlowLoop<Unit>()
                val deferred = s.map { tStateOf(null) }
                val e3 = e.map { tStateOf(Unit) }
                val flattened = e3.mergeWith(deferred) { a, _ -> a }.hold(tStateOf(null)).flatten()
                s.loopback = emptyTFlow
                flattened.toStateFlow()
            }

        backgroundScope.launch { state.collect { changes++ } }
        runCurrent()
    }

    @Test
    fun fanOut() = runFrpTest { network ->
        val e = network.mutableTFlow<Map<String, Int>>()
        val (fooFlow, barFlow) =
            activateSpecWithResult(network) {
                val selector = e.groupByKey()
                val foos = selector.eventsForKey("foo")
                val bars = selector.eventsForKey("bar")
                foos.toSharedFlow() to bars.toSharedFlow()
            }
        val stateFlow = fooFlow.stateIn(backgroundScope, SharingStarted.Eagerly, null)
        backgroundScope.launch { barFlow.collect { error("unexpected bar") } }
        runCurrent()

        assertEquals(null, stateFlow.value)

        e.emit(mapOf("foo" to 1))
        runCurrent()

        assertEquals(1, stateFlow.value)
    }

    @Test
    fun fanOutLateSubscribe() = runFrpTest { network ->
        val e = network.mutableTFlow<Map<String, Int>>()
        val barFlow =
            activateSpecWithResult(network) {
                val selector = e.groupByKey()
                selector
                    .eventsForKey("foo")
                    .map { selector.eventsForKey("bar") }
                    .hold(emptyTFlow)
                    .switchPromptly()
                    .toSharedFlow()
            }
        val stateFlow = barFlow.stateIn(backgroundScope, SharingStarted.Eagerly, null)
        runCurrent()

        assertEquals(null, stateFlow.value)

        e.emit(mapOf("foo" to 0, "bar" to 1))
        runCurrent()

        assertEquals(1, stateFlow.value)
    }

    @Test
    fun inputFlowCompleted() = runFrpTest { network ->
        val results = mutableListOf<Int>()
        val e = network.mutableTFlow<Int>()
        activateSpec(network) { e.nextOnly().observe { results.add(it) } }
        runCurrent()

        e.emit(10)
        runCurrent()

        assertEquals(listOf(10), results)

        e.emit(20)
        runCurrent()
        assertEquals(listOf(10), results)
    }

    @Test
    fun fanOutThenMergeIncrementally() = runFrpTest { network ->
        // A tflow of group updates, where a group is a tflow of child updates, where a child is a
        // stateflow
        val e = network.mutableTFlow<Map<Int, Maybe<TFlow<Map<Int, Maybe<StateFlow<String>>>>>>>()
        println("fanOutMergeInc START")
        val state =
            activateSpecWithResult(network) {
                // Convert nested Flows to nested TFlow/TState
                val emitter: TFlow<Map<Int, Maybe<TFlow<Map<Int, Maybe<TState<String>>>>>>> =
                    e.mapBuild { m ->
                        m.mapValues { (_, mFlow) ->
                            mFlow.map {
                                it.mapBuild { m2 ->
                                    m2.mapValues { (_, mState) ->
                                        mState.map { stateFlow -> stateFlow.toTState() }
                                    }
                                }
                            }
                        }
                    }
                // Accumulate all of our updates into a single TState
                val accState: TState<Map<Int, Map<Int, String>>> =
                    emitter
                        .mapStateful {
                            changeMap: Map<Int, Maybe<TFlow<Map<Int, Maybe<TState<String>>>>>> ->
                            changeMap.mapValues { (groupId, mGroupChanges) ->
                                mGroupChanges.map {
                                    groupChanges: TFlow<Map<Int, Maybe<TState<String>>>> ->
                                    // New group
                                    val childChangeById = groupChanges.groupByKey()
                                    val map: TFlow<Map<Int, Maybe<TFlow<Maybe<TState<String>>>>>> =
                                        groupChanges.mapStateful {
                                            gChangeMap: Map<Int, Maybe<TState<String>>> ->
                                            gChangeMap.mapValues { (childId, mChild) ->
                                                mChild.map { child: TState<String> ->
                                                    println("new child $childId in the house")
                                                    // New child
                                                    val eRemoved =
                                                        childChangeById
                                                            .eventsForKey(childId)
                                                            .filter { it === None }
                                                            .nextOnly()

                                                    val addChild: TFlow<Maybe<TState<String>>> =
                                                        now.map { mChild }
                                                            .onEach {
                                                                println(
                                                                    "addChild (groupId=$groupId, childId=$childId) ${child.sample()}"
                                                                )
                                                            }

                                                    val removeChild: TFlow<Maybe<TState<String>>> =
                                                        eRemoved
                                                            .onEach {
                                                                println(
                                                                    "removeChild (groupId=$groupId, childId=$childId)"
                                                                )
                                                            }
                                                            .map { none() }

                                                    addChild.mergeWith(removeChild) { _, _ ->
                                                        error("unexpected coincidence")
                                                    }
                                                }
                                            }
                                        }
                                    val mergeIncrementally: TFlow<Map<Int, Maybe<TState<String>>>> =
                                        map.onEach { println("merge patch: $it") }
                                            .mergeIncrementallyPromptly()
                                    mergeIncrementally
                                        .onEach { println("patch: $it") }
                                        .foldMapIncrementally()
                                        .flatMap { it.combine() }
                                }
                            }
                        }
                        .foldMapIncrementally()
                        .flatMap { it.combine() }

                accState.toStateFlow()
            }
        runCurrent()

        assertEquals(emptyMap(), state.value)

        val emitter2 = network.mutableTFlow<Map<Int, Maybe<StateFlow<String>>>>()
        println()
        println("init outer 0")
        e.emit(mapOf(0 to just(emitter2.onEach { println("emitter2 emit: $it") })))
        runCurrent()

        assertEquals(mapOf(0 to emptyMap()), state.value)

        println()
        println("init inner 10")
        emitter2.emit(mapOf(10 to just(MutableStateFlow("(0, 10)"))))
        runCurrent()

        assertEquals(mapOf(0 to mapOf(10 to "(0, 10)")), state.value)

        // replace
        println()
        println("replace inner 10")
        emitter2.emit(mapOf(10 to just(MutableStateFlow("(1, 10)"))))
        runCurrent()

        assertEquals(mapOf(0 to mapOf(10 to "(1, 10)")), state.value)

        // remove
        emitter2.emit(mapOf(10 to none()))
        runCurrent()

        assertEquals(mapOf(0 to emptyMap()), state.value)

        // add again
        emitter2.emit(mapOf(10 to just(MutableStateFlow("(2, 10)"))))
        runCurrent()

        assertEquals(mapOf(0 to mapOf(10 to "(2, 10)")), state.value)

        // batch update
        emitter2.emit(
            mapOf(
                10 to none(),
                11 to just(MutableStateFlow("(0, 11)")),
                12 to just(MutableStateFlow("(0, 12)")),
            )
        )
        runCurrent()

        assertEquals(mapOf(0 to mapOf(11 to "(0, 11)", 12 to "(0, 12)")), state.value)
    }

    @Test
    fun applyLatestNetworkChanges() = runFrpTest { network ->
        val newCount = network.mutableTFlow<FrpSpec<Flow<Int>>>()
        val flowOfFlows: Flow<Flow<Int>> =
            activateSpecWithResult(network) { newCount.applyLatestSpec().toSharedFlow() }
        runCurrent()

        val incCount = network.mutableTFlow<Unit>()
        fun newFlow(): FrpSpec<SharedFlow<Int>> = frpSpec {
            launchEffect {
                try {
                    println("new flow!")
                    awaitCancellation()
                } finally {
                    println("cancelling old flow")
                }
            }
            lateinit var count: TState<Int>
            count =
                incCount
                    .onEach { println("incrementing ${count.sample()}") }
                    .fold(0) { _, c -> c + 1 }
            count.stateChanges.toSharedFlow()
        }

        var outerCount = 0
        val lastFlows: StateFlow<Pair<StateFlow<Int?>, StateFlow<Int?>>> =
            flowOfFlows
                .map { it.stateIn(backgroundScope, SharingStarted.Eagerly, null) }
                .pairwise(MutableStateFlow(null))
                .onEach { outerCount++ }
                .stateIn(
                    backgroundScope,
                    SharingStarted.Eagerly,
                    MutableStateFlow(null) to MutableStateFlow(null),
                )

        runCurrent()

        newCount.emit(newFlow())
        runCurrent()

        assertEquals(1, outerCount)
        //        assertEquals(1, incCount.subscriptionCount)
        assertNull(lastFlows.value.second.value)

        incCount.emit(Unit)
        runCurrent()

        println("checking")
        assertEquals(1, lastFlows.value.second.value)

        incCount.emit(Unit)
        runCurrent()

        assertEquals(2, lastFlows.value.second.value)

        newCount.emit(newFlow())
        runCurrent()
        incCount.emit(Unit)
        runCurrent()

        // verify old flow is not getting updates
        assertEquals(2, lastFlows.value.first.value)
        // but the new one is
        assertEquals(1, lastFlows.value.second.value)
    }

    @Test
    fun effect() = runFrpTest { network ->
        val input = network.mutableTFlow<Unit>()
        var effectRunning = false
        var count = 0
        activateSpec(network) {
            val j = launchEffect {
                effectRunning = true
                try {
                    awaitCancellation()
                } finally {
                    effectRunning = false
                }
            }
            merge(emptyTFlow, input.nextOnly()).observe {
                count++
                j.cancel()
            }
        }
        runCurrent()
        assertEquals(true, effectRunning)
        assertEquals(0, count)

        println("1")
        input.emit(Unit)
        assertEquals(false, effectRunning)
        assertEquals(1, count)

        println("2")
        input.emit(Unit)
        assertEquals(1, count)
        println("3")
        input.emit(Unit)
        assertEquals(1, count)
    }

    private fun runFrpTest(
        timeout: Duration = 3.seconds,
        block: suspend TestScope.(FrpNetwork) -> Unit,
    ) {
        runTest(timeout = timeout) {
            val network = backgroundScope.newFrpNetwork()
            runCurrent()
            block(network)
        }
    }

    private fun TestScope.activateSpec(network: FrpNetwork, spec: FrpSpec<*>) =
        backgroundScope.launch { network.activateSpec(spec) }

    private suspend fun <R> TestScope.activateSpecWithResult(
        network: FrpNetwork,
        spec: FrpSpec<R>,
    ): R =
        CompletableDeferred<R>()
            .apply { activateSpec(network) { complete(spec.applySpec()) } }
            .await()
}

private fun <T> assertEquals(expected: T, actual: T) =
    org.junit.Assert.assertEquals(expected, actual)

private fun <A> Flow<A>.pairwise(init: A): Flow<Pair<A, A>> = flow {
    var prev = init
    collect {
        emit(prev to it)
        prev = it
    }
}
