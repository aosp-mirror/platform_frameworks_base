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

package com.android.systemui.qs

import com.google.common.truth.Truth.assertThat

import androidx.test.filters.SmallTest

import android.testing.AndroidTestingRunner
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.android.systemui.SysuiTestCase
import com.android.systemui.util.children
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidTestingRunner::class)
@SmallTest
class QSPanelSwitchToParentTest : SysuiTestCase() {

    private lateinit var parent1: FrameLayout
    private lateinit var parent2: FrameLayout

    private lateinit var movingView: View

    private lateinit var view1A: View
    private lateinit var view1B: View
    private lateinit var view1C: View

    private lateinit var view2A: View
    private lateinit var view2B: View
    private lateinit var view2C: View

    @Before
    fun setUp() {
        parent1 = FrameLayout(mContext)
        parent2 = FrameLayout(mContext)

        movingView = View(mContext)

        view1A = View(mContext)
        parent1.addView(view1A)
        view1B = View(mContext)
        parent1.addView(view1B)
        view1C = View(mContext)
        parent1.addView(view1C)

        view2A = View(mContext)
        parent2.addView(view2A)
        view2B = View(mContext)
        parent2.addView(view2B)
        view2C = View(mContext)
        parent2.addView(view2C)
    }

    @Test
    fun testNullTargetNoInteractions() {
        QSPanel.switchToParent(movingView, null, -1, "")

        assertThat(movingView.parent).isNull()
    }

    @Test
    fun testMoveToEndNoParent() {
        QSPanel.switchToParent(movingView, parent2, -1, "")

        assertThat(parent1.childrenList).containsExactly(
                view1A, view1B, view1C
        )

        assertThat(parent2.childrenList).containsExactly(
                view2A, view2B, view2C, movingView
        )
    }

    @Test
    fun testMoveToEndDifferentParent() {
        parent1.addView(movingView, 0)

        QSPanel.switchToParent(movingView, parent2, -1, "")

        assertThat(parent1.childrenList).containsExactly(
                view1A, view1B, view1C
        )
        assertThat(parent2.childrenList).containsExactly(
                view2A, view2B, view2C, movingView
        )
    }

    @Test
    fun testMoveToEndSameParent() {
        parent2.addView(movingView, 0)

        QSPanel.switchToParent(movingView, parent2, -1, "")

        assertThat(parent1.childrenList).containsExactly(
                view1A, view1B, view1C
        )
        assertThat(parent2.childrenList).containsExactly(
                view2A, view2B, view2C, movingView
        )
    }

    @Test
    fun testMoveToMiddleFromNoParent() {
        QSPanel.switchToParent(movingView, parent2, 1, "")

        assertThat(parent1.childrenList).containsExactly(
                view1A, view1B, view1C
        )
        assertThat(parent2.childrenList).containsExactly(
                view2A, movingView, view2B, view2C
        )
    }

    @Test
    fun testMoveToMiddleDifferentParent() {
        parent1.addView(movingView, 1)

        QSPanel.switchToParent(movingView, parent2, 2, "")

        assertThat(parent1.childrenList).containsExactly(
                view1A, view1B, view1C
        )
        assertThat(parent2.childrenList).containsExactly(
                view2A, view2B, movingView, view2C
        )
    }

    @Test
    fun testMoveToMiddleSameParent() {
        parent2.addView(movingView, 0)

        QSPanel.switchToParent(movingView, parent2, 1, "")

        assertThat(parent1.childrenList).containsExactly(
                view1A, view1B, view1C
        )
        assertThat(parent2.childrenList).containsExactly(
                view2A, movingView, view2B, view2C
        )
    }

    private val ViewGroup.childrenList: List<View>
        get() = children.toList()
}