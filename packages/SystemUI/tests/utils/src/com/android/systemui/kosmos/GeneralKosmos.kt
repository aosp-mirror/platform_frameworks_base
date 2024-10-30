package com.android.systemui.kosmos

import com.android.systemui.SysuiTestCase
import com.android.systemui.kosmos.Kosmos.Fixture
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

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
var Kosmos.applicationCoroutineScope by Fixture { testScope.backgroundScope }
var Kosmos.testCase: SysuiTestCase by Fixture()
var Kosmos.backgroundCoroutineContext: CoroutineContext by Fixture {
    testScope.backgroundScope.coroutineContext
}
var Kosmos.mainCoroutineContext: CoroutineContext by Fixture { testScope.coroutineContext }

/**
 * Run this test body with a [Kosmos] as receiver, and using the [testScope] currently installed in
 * that kosmos instance
 */
fun Kosmos.runTest(testBody: suspend Kosmos.() -> Unit) =
    testScope.runTest { this@runTest.testBody() }

fun Kosmos.runCurrent() = testScope.runCurrent()
