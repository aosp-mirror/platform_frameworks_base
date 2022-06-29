/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.animation

import android.graphics.drawable.Drawable
import android.testing.AndroidTestingRunner
import android.testing.TestableLooper
import android.view.View
import android.view.ViewGroup
import android.view.ViewParent
import androidx.test.filters.SmallTest
import com.android.internal.jank.InteractionJankMonitor
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@SmallTest
@RunWith(AndroidTestingRunner::class)
@TestableLooper.RunWithLooper
class GhostedViewLaunchAnimatorControllerTest : SysuiTestCase() {
    @Mock lateinit var interactionJankMonitor: InteractionJankMonitor
    @Mock lateinit var view: View
    @Mock lateinit var rootView: ViewGroup
    @Mock lateinit var viewParent: ViewParent
    @Mock lateinit var drawable: Drawable
    lateinit var controller: GhostedViewLaunchAnimatorController

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        whenever(view.rootView).thenReturn(rootView)
        whenever(view.background).thenReturn(drawable)
        whenever(view.height).thenReturn(0)
        whenever(view.width).thenReturn(0)
        whenever(view.parent).thenReturn(viewParent)
        whenever(view.visibility).thenReturn(View.VISIBLE)
        whenever(view.invalidate()).then { /* NO-OP */ }
        whenever(view.getLocationOnScreen(any())).then { /* NO-OP */ }
        whenever(interactionJankMonitor.begin(any(), anyInt())).thenReturn(true)
        whenever(interactionJankMonitor.end(anyInt())).thenReturn(true)
        controller = GhostedViewLaunchAnimatorController(view, 0, interactionJankMonitor)
    }

    @Test
    fun animatingOrphanViewDoesNotCrash() {
        val state = LaunchAnimator.State(top = 0, bottom = 0, left = 0, right = 0)

        controller.onIntentStarted(willAnimate = true)
        controller.onLaunchAnimationStart(isExpandingFullyAbove = true)
        controller.onLaunchAnimationProgress(state, progress = 0f, linearProgress = 0f)
        controller.onLaunchAnimationEnd(isExpandingFullyAbove = true)
    }
}