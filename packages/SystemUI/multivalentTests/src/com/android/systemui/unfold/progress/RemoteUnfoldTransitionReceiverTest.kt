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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
class RemoteUnfoldTransitionReceiverTest : SysuiTestCase() {

    private val progressProvider =
        RemoteUnfoldTransitionReceiver(useReceivingFilter = true) { runOnMainSync(it) }
    private val progressProviderWithoutFilter =
        RemoteUnfoldTransitionReceiver(useReceivingFilter = false) { it.run() }
    private val listener = TestUnfoldProgressListener()

    @Before
    fun setUp() {
        progressProvider.addCallback(listener)
        progressProviderWithoutFilter.addCallback(listener)
    }

    @Test
    fun onTransitionStarted_withFilter_propagated() {
        progressProvider.onTransitionStarted()

        listener.assertStarted()
    }

    @Test
    fun onTransitionProgress_withFilter_propagated() {
        progressProvider.onTransitionStarted()

        progressProvider.onTransitionProgress(0.5f)

        listener.assertLastProgress(0.5f)
    }

    @Test
    fun onTransitionEnded_withFilter_propagated() {
        progressProvider.onTransitionStarted()
        progressProvider.onTransitionProgress(0.5f)

        progressProvider.onTransitionFinished()

        listener.ensureTransitionFinished()
    }

    @Test
    fun onTransitionStarted_withFilter_afterCallbackRemoved_notPropagated() {
        progressProvider.removeCallback(listener)

        progressProvider.onTransitionStarted()

        listener.assertNotStarted()
    }

    @Test
    fun onTransitionStarted_withoutFilter_propagated() {
        progressProviderWithoutFilter.onTransitionStarted()

        listener.assertStarted()
    }

    @Test
    fun onTransitionProgress_withoutFilter_propagated() {
        progressProviderWithoutFilter.onTransitionStarted()

        progressProviderWithoutFilter.onTransitionProgress(0.5f)

        listener.assertLastProgress(0.5f)
    }

    @Test
    fun onTransitionEnded_withoutFilter_propagated() {
        progressProviderWithoutFilter.onTransitionStarted()
        progressProviderWithoutFilter.onTransitionProgress(0.5f)

        progressProviderWithoutFilter.onTransitionFinished()

        listener.ensureTransitionFinished()
    }

    @Test
    fun onTransitionStarted_withoutFilter_afterCallbackRemoved_notPropagated() {
        progressProviderWithoutFilter.removeCallback(listener)

        progressProviderWithoutFilter.onTransitionStarted()

        listener.assertNotStarted()
    }

    private fun runOnMainSync(f: Runnable) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync { f.run() }
        // Sleep as the animator used from the filter has a callback that happens at every frame.
        Thread.sleep(60)
    }
}
