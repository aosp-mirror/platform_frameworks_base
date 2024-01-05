package com.android.compose.test

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.TestMonotonicFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext

/**
 * This method creates a [CoroutineScope] that can be used in animations created in a composable
 * function.
 *
 * The [TestCoroutineScheduler] is passed to provide the functionality to wait for idle.
 */
@OptIn(ExperimentalTestApi::class)
fun runMonotonicClockTest(block: suspend MonotonicClockTestScope.() -> Unit) = runTest {
    // We need a CoroutineScope (like a TestScope) to create a TestMonotonicFrameClock.
    withContext(TestMonotonicFrameClock(this)) {
        MonotonicClockTestScope(coroutineScope = this, testScheduler = testScheduler).block()
    }
}

class MonotonicClockTestScope(
    coroutineScope: CoroutineScope,
    val testScheduler: TestCoroutineScheduler
) : CoroutineScope by coroutineScope
