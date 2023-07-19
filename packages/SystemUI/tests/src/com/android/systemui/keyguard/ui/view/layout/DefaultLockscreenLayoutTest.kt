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
 *
 */

package com.android.systemui.keyguard.ui.view.layout

import android.graphics.Point
import android.view.ViewGroup
import android.view.WindowManager
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import com.android.systemui.biometrics.AuthController
import com.android.systemui.keyguard.ui.view.KeyguardRootView
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Answers
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class DefaultLockscreenLayoutTest : SysuiTestCase() {
    private lateinit var defaultLockscreenLayout: DefaultLockscreenLayout
    private lateinit var rootView: KeyguardRootView
    @Mock private lateinit var authController: AuthController
    @Mock private lateinit var keyguardUpdateMonitor: KeyguardUpdateMonitor
    @Mock(answer = Answers.RETURNS_DEEP_STUBS) private lateinit var windowManager: WindowManager

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        rootView = KeyguardRootView(context, null)
        defaultLockscreenLayout =
            DefaultLockscreenLayout(authController, keyguardUpdateMonitor, windowManager, context)
    }

    @Test
    fun testLayoutViews_KeyguardIndicationArea() {
        defaultLockscreenLayout.layoutViews(rootView)
        val constraint = getViewConstraint(R.id.keyguard_indication_area)
        assertThat(constraint.layout.bottomToBottom).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.endToEnd).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.mWidth).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(constraint.layout.mHeight).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    @Test
    fun testLayoutViews_lockIconView() {
        defaultLockscreenLayout.layoutViews(rootView)
        val constraint = getViewConstraint(R.id.lock_icon_view)
        assertThat(constraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
    }

    @Test
    fun testCenterLockIcon() {
        defaultLockscreenLayout.centerLockIcon(Point(5, 6), 1F, 5, rootView)
        val constraint = getViewConstraint(R.id.lock_icon_view)

        assertThat(constraint.layout.mWidth).isEqualTo(2)
        assertThat(constraint.layout.mHeight).isEqualTo(2)
        assertThat(constraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.topMargin).isEqualTo(5)
        assertThat(constraint.layout.startMargin).isEqualTo(4)
    }

    /** Get the ConstraintLayout constraint of the view. */
    private fun getViewConstraint(viewId: Int): ConstraintSet.Constraint {
        val constraintSet = ConstraintSet()
        constraintSet.clone(rootView)
        return constraintSet.getConstraint(viewId)
    }
}
