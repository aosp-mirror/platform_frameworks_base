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

package com.android.systemui.shared.animation

import android.graphics.Paint
import android.widget.FrameLayout
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class DisableSubpixelTextTransitionListenerTest : SysuiTestCase() {

    private lateinit var disableSubpixelTextTransitionListener:
        DisableSubpixelTextTransitionListener
    private var rootViewWithNoTextView = FrameLayout(context)
    private var rootView = FrameLayout(context)
    private var childView = FrameLayout(context)
    private var childViewWithNoTextView = FrameLayout(context)
    private var childTextView = TextView(context)
    private var childOfChildTextView = TextView(context)

    @Before
    fun setup() {

        childView.addView(childOfChildTextView)
        rootView.addView(childTextView)
        rootView.addView(childView)
    }

    @Test
    fun onTransitionStarted_addsSubpixelFlagToChildTextView() {
        disableSubpixelTextTransitionListener = DisableSubpixelTextTransitionListener(rootView)

        disableSubpixelTextTransitionListener.onTransitionStarted()

        assertThat(childTextView.paintFlags and Paint.SUBPIXEL_TEXT_FLAG).isGreaterThan(0)
    }

    @Test
    fun onTransitionStarted_addsSupbixelFlagToChildOfChildTextView() {
        disableSubpixelTextTransitionListener = DisableSubpixelTextTransitionListener(rootView)

        disableSubpixelTextTransitionListener.onTransitionStarted()

        assertThat(childOfChildTextView.paintFlags and Paint.SUBPIXEL_TEXT_FLAG).isGreaterThan(0)
    }

    @Test
    fun onTransitionFinished_removeSupbixelFlagFromChildTextView() {
        disableSubpixelTextTransitionListener = DisableSubpixelTextTransitionListener(rootView)

        disableSubpixelTextTransitionListener.onTransitionStarted()
        disableSubpixelTextTransitionListener.onTransitionFinished()

        assertThat(childTextView.paintFlags and Paint.SUBPIXEL_TEXT_FLAG).isEqualTo(0)
    }

    @Test
    fun onTransitionFinished_removeSupbixelFlagFromChildOfChildTextView() {
        disableSubpixelTextTransitionListener = DisableSubpixelTextTransitionListener(rootView)

        disableSubpixelTextTransitionListener.onTransitionStarted()
        disableSubpixelTextTransitionListener.onTransitionFinished()

        assertThat(childOfChildTextView.paintFlags and Paint.SUBPIXEL_TEXT_FLAG).isEqualTo(0)
    }

    @Test
    fun whenRootViewIsNull_runWithoutExceptions() {
        disableSubpixelTextTransitionListener = DisableSubpixelTextTransitionListener(null)

        disableSubpixelTextTransitionListener.onTransitionStarted()
        disableSubpixelTextTransitionListener.onTransitionFinished()
    }

    @Test
    fun whenFlagAlreadyPresent_flagNotRemovedOnTransitionFinished() {
        childTextView.paintFlags = childTextView.paintFlags or Paint.SUBPIXEL_TEXT_FLAG
        disableSubpixelTextTransitionListener = DisableSubpixelTextTransitionListener(rootView)

        disableSubpixelTextTransitionListener.onTransitionStarted()
        disableSubpixelTextTransitionListener.onTransitionFinished()

        assertThat(childTextView.paintFlags and Paint.SUBPIXEL_TEXT_FLAG).isGreaterThan(0)
    }

    @Test
    fun whenFlagNotPresent_flagRemovedOnTransitionFinished() {
        childTextView.paintFlags = childTextView.paintFlags or Paint.SUBPIXEL_TEXT_FLAG
        disableSubpixelTextTransitionListener = DisableSubpixelTextTransitionListener(rootView)

        disableSubpixelTextTransitionListener.onTransitionStarted()
        disableSubpixelTextTransitionListener.onTransitionFinished()

        assertThat(childOfChildTextView.paintFlags and Paint.SUBPIXEL_TEXT_FLAG).isEqualTo(0)
    }

    @Test
    fun whenRootViewHasNoChildTextView_flagNotAddedToRelatedTextviews() {
        rootViewWithNoTextView.addView(childViewWithNoTextView)
        rootView.addView(rootViewWithNoTextView)
        disableSubpixelTextTransitionListener =
                DisableSubpixelTextTransitionListener(rootViewWithNoTextView)

        disableSubpixelTextTransitionListener.onTransitionStarted()

        assertThat(childTextView.paintFlags and Paint.SUBPIXEL_TEXT_FLAG).isEqualTo(0)
    }
}
