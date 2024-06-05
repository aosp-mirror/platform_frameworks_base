package com.android.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * This method creates a [CoroutineScope] that can be used in animations created in a composable
 * function.
 *
 * The [TestCoroutineScheduler] is passed to provide the functionality to wait for idle.
 *
 * Note: Please refer to the documentation for [runTest], as this feature utilizes it. This will
 * provide a comprehensive understanding of all its behaviors.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalCoroutinesApi::class)
fun runMonotonicClockTest(block: suspend MonotonicClockTestScope.() -> Unit) = runTest {
    val testScope: TestScope = this

    withContext(TestMonotonicFrameClock(coroutineScope = testScope)) {
        val testScopeWithMonotonicFrameClock: CoroutineScope = this

        val scope =
            MonotonicClockTestScope(
                testScope = testScopeWithMonotonicFrameClock,
                testScheduler = testScope.testScheduler,
                backgroundScope = backgroundScope,
            )

        // Run the test
        scope.block()
    }
}

/**
 * A coroutine scope that for launching test coroutines for Compose.
 *
 * @param testScheduler The delay-skipping scheduler used by the test dispatchers running the code
 *   in this scope (see [TestScope.testScheduler]).
 * @param backgroundScope A scope for background work (see [TestScope.backgroundScope]).
 */
class MonotonicClockTestScope(
    testScope: CoroutineScope,
    val testScheduler: TestCoroutineScheduler,
    val backgroundScope: CoroutineScope,
) : CoroutineScope by testScope
