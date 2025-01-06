package com.android.systemui.kosmos

import com.android.systemui.SysuiTestCase
import com.android.systemui.coroutines.FlowValue
import com.android.systemui.coroutines.collectLastValue
import com.android.systemui.coroutines.collectValues
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.settings.brightness.ui.BrightnessWarningToast
import com.android.systemui.util.mockito.mock
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.mockito.kotlin.verify

var Kosmos.testDispatcher by Fixture { StandardTestDispatcher() }

/**
 * Force this Kosmos to use a [StandardTestDispatcher], regardless of the current Kosmos default. In
 * short, no launch blocks will be run on this dispatcher until `TestCoroutineScheduler.runCurrent`
 * is called. See [StandardTestDispatcher] for details.
 *
 * For details on this migration, see http://go/thetiger
 */
fun Kosmos.useStandardTestDispatcher() = apply { testDispatcher = StandardTestDispatcher() }

/**
 * Force this Kosmos to use an [UnconfinedTestDispatcher], regardless of the current Kosmos default.
 * In short, launch blocks will be executed eagerly without waiting for
 * `TestCoroutineScheduler.runCurrent`. See [UnconfinedTestDispatcher] for details.
 *
 * For details on this migration, see http://go/thetiger
 */
fun Kosmos.useUnconfinedTestDispatcher() = apply { testDispatcher = UnconfinedTestDispatcher() }

var Kosmos.testScope by Fixture { TestScope(testDispatcher) }
var Kosmos.backgroundScope by Fixture { testScope.backgroundScope }
var Kosmos.applicationCoroutineScope by Fixture { backgroundScope }
var Kosmos.testCase: SysuiTestCase by Fixture()
var Kosmos.backgroundCoroutineContext: CoroutineContext by Fixture {
    backgroundScope.coroutineContext
}
var Kosmos.mainCoroutineContext: CoroutineContext by Fixture { testScope.coroutineContext }
var Kosmos.brightnessWarningToast: BrightnessWarningToast by
    Kosmos.Fixture { mock<BrightnessWarningToast>() }

/**
 * Run this test body with a [Kosmos] as receiver, and using the [testScope] currently installed in
 * that kosmos instance
 */
fun Kosmos.runTest(testBody: suspend Kosmos.() -> Unit) =
    testScope.runTest testBody@{ this@runTest.testBody() }

fun Kosmos.runCurrent() = testScope.runCurrent()

fun <T> Kosmos.collectLastValue(flow: Flow<T>) = testScope.collectLastValue(flow)

fun <T> Kosmos.collectValues(flow: Flow<T>): FlowValue<List<T>> = testScope.collectValues(flow)

/**
 * Retrieve the current value of this [StateFlow] safely. Needs a [TestScope] in order to make sure
 * that all pending tasks have run before returning a value. Tests that directly access
 * [StateFlow.value] may be incorrect, since the value returned may be stale if the current test
 * dispatcher is a [StandardTestDispatcher].
 *
 * If you want to assert on a [Flow] that is not a [StateFlow], please use
 * [TestScope.collectLastValue], to make sure that the desired value is captured when emitted.
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.currentValue(stateFlow: StateFlow<T>): T {
    val values = mutableListOf<T>()
    val job = backgroundScope.launch { stateFlow.collect(values::add) }
    runCurrent()
    job.cancel()
    // StateFlow should always have at least one value
    return values.last()
}

/** Retrieve the current value of this [StateFlow] safely. See `currentValue(TestScope)`. */
fun <T> Kosmos.currentValue(fn: () -> T) = testScope.currentValue(fn)

/**
 * Retrieve the result of [fn] after running all pending tasks. Do not use to retrieve the value of
 * a flow directly; for that, use either `currentValue(StateFlow)` or [collectLastValue]
 */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.currentValue(fn: () -> T): T {
    runCurrent()
    return fn()
}

/** Retrieve the result of [fn] after running all pending tasks. See `TestScope.currentValue(fn)` */
fun <T> Kosmos.currentValue(stateFlow: StateFlow<T>): T {
    return testScope.currentValue(stateFlow)
}

/** Safely verify that a mock has been called after the test scope has caught up */
@OptIn(ExperimentalCoroutinesApi::class)
fun <T> TestScope.verifyCurrent(mock: T): T {
    runCurrent()
    return verify(mock)
}

/**
 * Safely verify that a mock has been called after the test scope has caught up. See
 * `TestScope.verifyCurrent`
 */
fun <T> Kosmos.verifyCurrent(mock: T) = testScope.verifyCurrent(mock)
