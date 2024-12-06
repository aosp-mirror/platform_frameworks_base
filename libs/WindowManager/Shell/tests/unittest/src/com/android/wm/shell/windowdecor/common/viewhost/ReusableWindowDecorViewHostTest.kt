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
package com.android.wm.shell.windowdecor.common.viewhost

import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.SurfaceControl
import android.view.View
import android.view.WindowManager
import androidx.test.filters.SmallTest
import com.android.wm.shell.ShellTestCase
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.mockito.kotlin.spy
import org.mockito.kotlin.verify

/**
 * Tests for [ReusableWindowDecorViewHost].
 *
 * Build/Install/Run: atest WMShellUnitTests:ReusableWindowDecorViewHostTest
 */
@SmallTest
@TestableLooper.RunWithLooper
@RunWith(AndroidTestingRunner::class)
class ReusableWindowDecorViewHostTest : ShellTestCase() {

    @Test
    fun update_differentView_replacesView() = runTest {
        val view = View(context)
        val lp = WindowManager.LayoutParams()
        val reusableVH = createReusableViewHost()
        reusableVH.updateView(view, lp, context.resources.configuration, null)

        assertThat(reusableVH.rootView.childCount).isEqualTo(1)
        assertThat(reusableVH.rootView.getChildAt(0)).isEqualTo(view)

        val newView = View(context)
        val newLp = WindowManager.LayoutParams()
        reusableVH.updateView(newView, newLp, context.resources.configuration, null)

        assertThat(reusableVH.rootView.childCount).isEqualTo(1)
        assertThat(reusableVH.rootView.getChildAt(0)).isEqualTo(newView)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun updateView_clearsPendingAsyncJob() = runTest {
        val reusableVH = createReusableViewHost()
        val asyncView = View(context)
        val syncView = View(context)
        val asyncAttrs = WindowManager.LayoutParams(100, 100)
        val syncAttrs = WindowManager.LayoutParams(200, 200)

        reusableVH.updateViewAsync(
            view = asyncView,
            attrs = asyncAttrs,
            configuration = context.resources.configuration,
        )

        // No view host yet, since the coroutine hasn't run.
        assertThat(reusableVH.viewHostAdapter.isInitialized()).isFalse()

        reusableVH.updateView(
            view = syncView,
            attrs = syncAttrs,
            configuration = context.resources.configuration,
            onDrawTransaction = null,
        )

        // Would run coroutine if it hadn't been cancelled.
        advanceUntilIdle()

        assertThat(reusableVH.viewHostAdapter.isInitialized()).isTrue()
        // View host view/attrs should match the ones from the sync call.
        assertThat(reusableVH.rootView.getChildAt(0)).isEqualTo(syncView)
        assertThat(reusableVH.view()!!.layoutParams.width).isEqualTo(syncAttrs.width)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun updateViewAsync() = runTest {
        val reusableVH = createReusableViewHost()
        val view = View(context)
        val attrs = WindowManager.LayoutParams(100, 100)

        reusableVH.updateViewAsync(
            view = view,
            attrs = attrs,
            configuration = context.resources.configuration,
        )

        assertThat(reusableVH.viewHostAdapter.isInitialized()).isFalse()

        advanceUntilIdle()

        assertThat(reusableVH.viewHostAdapter.isInitialized()).isTrue()
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun updateViewAsync_clearsPendingAsyncJob() = runTest {
        val reusableVH = createReusableViewHost()

        val view = View(context)
        reusableVH.updateViewAsync(
            view = view,
            attrs = WindowManager.LayoutParams(100, 100),
            configuration = context.resources.configuration,
        )
        val otherView = View(context)
        reusableVH.updateViewAsync(
            view = otherView,
            attrs = WindowManager.LayoutParams(100, 100),
            configuration = context.resources.configuration,
        )

        advanceUntilIdle()

        assertThat(reusableVH.viewHostAdapter.isInitialized()).isTrue()
        assertThat(reusableVH.rootView.getChildAt(0)).isEqualTo(otherView)
    }

    @Test
    fun release() = runTest {
        val reusableVH = createReusableViewHost()

        val view = View(context)
        reusableVH.updateView(
            view = view,
            attrs = WindowManager.LayoutParams(100, 100),
            configuration = context.resources.configuration,
            onDrawTransaction = null,
        )

        val t = mock(SurfaceControl.Transaction::class.java)
        reusableVH.release(t)

        verify(reusableVH.viewHostAdapter).release(t)
    }

    private fun CoroutineScope.createReusableViewHost() =
        ReusableWindowDecorViewHost(
            context = context,
            mainScope = this,
            display = context.display,
            id = 1,
            viewHostAdapter = spy(SurfaceControlViewHostAdapter(context, context.display)),
        )

    private fun ReusableWindowDecorViewHost.view(): View? = viewHostAdapter.viewHost?.view
}
