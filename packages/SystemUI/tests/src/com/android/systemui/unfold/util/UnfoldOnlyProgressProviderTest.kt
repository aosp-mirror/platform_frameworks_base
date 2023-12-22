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
package com.android.systemui.unfold.util

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.unfold.TestUnfoldTransitionProvider
import com.android.systemui.unfold.progress.TestUnfoldProgressListener
import com.google.common.util.concurrent.MoreExecutors
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
@TestableLooper.RunWithLooper
class UnfoldOnlyProgressProviderTest : SysuiTestCase() {

    private val listener = TestUnfoldProgressListener()
    private val sourceProvider = TestUnfoldTransitionProvider()

    private val foldProvider = TestFoldProvider()

    private lateinit var progressProvider: UnfoldOnlyProgressProvider

    @Before
    fun setUp() {
        progressProvider =
            UnfoldOnlyProgressProvider(foldProvider, MoreExecutors.directExecutor(), sourceProvider)

        progressProvider.addCallback(listener)
    }

    @Test
    fun unfolded_unfoldAnimationFinished_propagatesEvents() {
        foldProvider.onFoldUpdate(isFolded = true)
        foldProvider.onFoldUpdate(isFolded = false)

        // Unfold animation
        sourceProvider.onTransitionStarted()
        sourceProvider.onTransitionProgress(0.5f)
        sourceProvider.onTransitionFinished()

        with(listener.ensureTransitionFinished()) { assertLastProgress(0.5f) }
    }

    @Test
    fun unfoldedWithAnimation_foldAnimation_doesNotPropagateEvents() {
        foldProvider.onFoldUpdate(isFolded = true)
        foldProvider.onFoldUpdate(isFolded = false)
        // Unfold animation
        sourceProvider.onTransitionStarted()
        sourceProvider.onTransitionProgress(0.5f)
        sourceProvider.onTransitionFinished()
        listener.clear()

        // Fold animation
        sourceProvider.onTransitionStarted()
        sourceProvider.onTransitionProgress(0.2f)
        sourceProvider.onTransitionFinished()

        listener.assertNotStarted()
    }

    @Test
    fun unfoldedWithAnimation_foldAnimationSeveralTimes_doesNotPropagateEvents() {
        foldProvider.onFoldUpdate(isFolded = true)
        foldProvider.onFoldUpdate(isFolded = false)
        // Unfold animation
        sourceProvider.onTransitionStarted()
        sourceProvider.onTransitionProgress(0.5f)
        sourceProvider.onTransitionFinished()
        listener.clear()

        // Start and stop fold animation several times
        repeat(3) {
            sourceProvider.onTransitionStarted()
            sourceProvider.onTransitionProgress(0.2f)
            sourceProvider.onTransitionFinished()
        }

        listener.assertNotStarted()
    }

    @Test
    fun unfoldedAgainAfterFolding_propagatesEvents() {
        foldProvider.onFoldUpdate(isFolded = true)
        foldProvider.onFoldUpdate(isFolded = false)

        // Unfold animation
        sourceProvider.onTransitionStarted()
        sourceProvider.onTransitionProgress(0.5f)
        sourceProvider.onTransitionFinished()

        // Fold animation
        sourceProvider.onTransitionStarted()
        sourceProvider.onTransitionProgress(0.2f)
        sourceProvider.onTransitionFinished()
        foldProvider.onFoldUpdate(isFolded = true)

        listener.clear()

        // Second unfold animation after folding
        foldProvider.onFoldUpdate(isFolded = false)
        sourceProvider.onTransitionStarted()
        sourceProvider.onTransitionProgress(0.1f)
        sourceProvider.onTransitionFinished()

        with(listener.ensureTransitionFinished()) { assertLastProgress(0.1f) }
    }
}
