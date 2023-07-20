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

package com.android.systemui.unfold.progress

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.SysuiTestCase
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class UnfoldRemoteFilterTest : SysuiTestCase() {
    private val listener = TestUnfoldProgressListener()

    private val progressProvider = UnfoldRemoteFilter(listener)

    @Test
    fun onTransitionStarted_propagated() {
        runOnMainThreadWithInterval({ progressProvider.onTransitionStarted() })
        listener.assertStarted()
    }

    @Test
    fun onTransitionProgress_withInterval_propagated() {
        runOnMainThreadWithInterval(
            { progressProvider.onTransitionStarted() },
            { progressProvider.onTransitionProgress(0.5f) }
        )

        listener.assertLastProgress(0.5f)
    }

    @Test
    fun onTransitionEnded_propagated() {
        runOnMainThreadWithInterval(
            { progressProvider.onTransitionStarted() },
            { progressProvider.onTransitionProgress(0.5f) },
            { progressProvider.onTransitionFinished() },
        )

        listener.ensureTransitionFinished()
    }

    private fun runOnMainThreadWithInterval(
        vararg blocks: () -> Unit,
        interval: Duration = 60.milliseconds
    ) {
        blocks.forEach {
            InstrumentationRegistry.getInstrumentation().runOnMainSync { it() }
            Thread.sleep(interval.inWholeMilliseconds)
        }
    }
}
