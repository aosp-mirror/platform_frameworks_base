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

package com.android.systemui.keyguard.ui.view.layout.sections

import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.Flags as AConfigFlags
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.ui.viewmodel.KeyguardIndicationAreaViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.KeyguardIndicationController
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class DefaultIndicationAreaSectionTest : SysuiTestCase() {

    @Mock private lateinit var keyguardIndicationAreaViewModel: KeyguardIndicationAreaViewModel
    @Mock private lateinit var indicationController: KeyguardIndicationController

    private lateinit var underTest: DefaultIndicationAreaSection

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        underTest =
            DefaultIndicationAreaSection(
                context,
                keyguardIndicationAreaViewModel,
                indicationController,
            )
    }

    @Test
    fun addViewsConditionally() {
        mSetFlagsRule.enableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
        val constraintLayout = ConstraintLayout(context, null)
        underTest.addViews(constraintLayout)
        assertThat(constraintLayout.childCount).isGreaterThan(0)
    }

    @Test
    fun addViewsConditionally_migrateFlagOff() {
        mSetFlagsRule.disableFlags(AConfigFlags.FLAG_KEYGUARD_BOTTOM_AREA_REFACTOR)
        val constraintLayout = ConstraintLayout(context, null)
        underTest.addViews(constraintLayout)
        assertThat(constraintLayout.childCount).isEqualTo(0)
    }

    @Test
    fun applyConstraints() {
        val cs = ConstraintSet()
        underTest.applyConstraints(cs)

        val constraint = cs.getConstraint(R.id.keyguard_indication_area)

        assertThat(constraint.layout.bottomToBottom).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.startToStart).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.endToEnd).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(constraint.layout.mWidth).isEqualTo(ViewGroup.LayoutParams.MATCH_PARENT)
        assertThat(constraint.layout.mHeight).isEqualTo(ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
