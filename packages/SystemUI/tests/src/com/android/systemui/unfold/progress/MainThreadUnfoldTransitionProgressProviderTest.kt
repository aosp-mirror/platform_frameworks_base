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

import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.testing.TestableLooper.RunWithLooper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.FakeUnfoldTransitionProvider
import com.android.systemui.utils.os.FakeHandler
import kotlin.test.Test
import kotlinx.coroutines.test.runTest
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
@RunWithLooper(setAsMainLooper = true)
class MainThreadUnfoldTransitionProgressProviderTest : SysuiTestCase() {

    private val wrappedProgressProvider = FakeUnfoldTransitionProvider()
    private val fakeHandler = FakeHandler(Looper.getMainLooper())
    private val listener = TestUnfoldProgressListener()

    private val progressProvider =
        MainThreadUnfoldTransitionProgressProvider(fakeHandler, wrappedProgressProvider)

    @Test
    fun onTransitionStarted_propagated() {
        progressProvider.addCallback(listener)

        wrappedProgressProvider.onTransitionStarted()
        fakeHandler.dispatchQueuedMessages()

        listener.assertStarted()
    }

    @Test
    fun onTransitionProgress_propagated() {
        progressProvider.addCallback(listener)

        wrappedProgressProvider.onTransitionStarted()
        wrappedProgressProvider.onTransitionProgress(0.5f)
        fakeHandler.dispatchQueuedMessages()

        listener.assertLastProgress(0.5f)
    }

    @Test
    fun onTransitionFinished_propagated() {
        progressProvider.addCallback(listener)

        wrappedProgressProvider.onTransitionStarted()
        wrappedProgressProvider.onTransitionProgress(0.5f)
        wrappedProgressProvider.onTransitionFinished()
        fakeHandler.dispatchQueuedMessages()

        listener.ensureTransitionFinished()
    }

    @Test
    fun onTransitionFinishing_propagated() {
        progressProvider.addCallback(listener)

        wrappedProgressProvider.onTransitionStarted()
        wrappedProgressProvider.onTransitionProgress(0.5f)
        wrappedProgressProvider.onTransitionFinished()
        fakeHandler.dispatchQueuedMessages()

        listener.ensureTransitionFinished()
    }

    @Test
    fun onTransitionStarted_afterCallbackRemoved_notPropagated() {
        progressProvider.addCallback(listener)
        progressProvider.removeCallback(listener)

        wrappedProgressProvider.onTransitionStarted()
        fakeHandler.dispatchQueuedMessages()

        listener.assertNotStarted()
    }

    @Test
    fun addCallback_fromBackgroundThread_succeeds() = runTest {
        val bgHandler = Handler(HandlerThread("TestBgThread").apply { start() }.looper)
        bgHandler.runWithScissors({ progressProvider.addCallback(listener) }, 5000L)

        wrappedProgressProvider.onTransitionStarted()
        listener.assertStarted()
    }
}
