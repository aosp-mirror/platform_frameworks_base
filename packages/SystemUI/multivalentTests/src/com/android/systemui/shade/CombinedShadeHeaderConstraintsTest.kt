/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.shade

import android.view.ViewGroup
import androidx.constraintlayout.widget.ConstraintSet
import androidx.constraintlayout.widget.ConstraintSet.PARENT_ID
import androidx.constraintlayout.widget.ConstraintSet.START
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.res.R
import com.android.systemui.SysuiTestCase
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class CombinedShadeHeaderConstraintsTest : SysuiTestCase() {

    private lateinit var qqsConstraint: ConstraintSet
    private lateinit var qsConstraint: ConstraintSet
    private lateinit var largeScreenConstraint: ConstraintSet

    @get:Rule
    val expect: Expect = Expect.create()

    @Before
    fun setUp() {
        qqsConstraint = ConstraintSet().apply {
            load(context, context.resources.getXml(R.xml.qqs_header))
        }
        qsConstraint = ConstraintSet().apply {
            load(context, context.resources.getXml(R.xml.qs_header))
        }
        largeScreenConstraint = ConstraintSet().apply {
            load(context, context.resources.getXml(R.xml.large_screen_shade_header))
        }
    }

    @Test
    fun testEdgeElementsAlignedWithGuide_qqs() {
        with(qqsConstraint) {
            assertThat(getConstraint(R.id.clock).layout.startToStart).isEqualTo(R.id.begin_guide)
            assertThat(getConstraint(R.id.clock).layout.horizontalBias).isEqualTo(0f)

            assertThat(getConstraint(R.id.shade_header_system_icons).layout.endToEnd)
                .isEqualTo(R.id.end_guide)
            assertThat(getConstraint(R.id.shade_header_system_icons).layout.horizontalBias)
                .isEqualTo(1f)

            assertThat(getConstraint(R.id.privacy_container).layout.endToEnd)
                .isEqualTo(R.id.end_guide)
            assertThat(getConstraint(R.id.privacy_container).layout.horizontalBias)
                .isEqualTo(1f)
        }
    }

    @Test
    fun testClockScale() {
        with(qqsConstraint.getConstraint(R.id.clock)) {
            assertThat(transform.scaleX).isEqualTo(1f)
            assertThat(transform.scaleY).isEqualTo(1f)
        }
        with(qsConstraint.getConstraint(R.id.clock)) {
            assertThat(transform.scaleX).isGreaterThan(1f)
            assertThat(transform.scaleY).isGreaterThan(1f)
        }
    }

    @Test
    fun testEdgeElementsAlignedWithEdgeOrGuide_qs() {
        with(qsConstraint) {
            assertThat(getConstraint(R.id.clock).layout.startToStart).isEqualTo(PARENT_ID)
            assertThat(getConstraint(R.id.clock).layout.horizontalBias).isEqualTo(0.5f)

            assertThat(getConstraint(R.id.date).layout.startToStart).isEqualTo(PARENT_ID)
            assertThat(getConstraint(R.id.date).layout.horizontalBias).isEqualTo(0.5f)

            assertThat(getConstraint(R.id.shade_header_system_icons).layout.endToEnd)
                .isEqualTo(PARENT_ID)
            assertThat(getConstraint(R.id.shade_header_system_icons).layout.horizontalBias)
                .isEqualTo(0.5f)

            assertThat(getConstraint(R.id.privacy_container).layout.endToEnd)
                .isEqualTo(R.id.end_guide)
            assertThat(getConstraint(R.id.privacy_container).layout.horizontalBias).isEqualTo(1f)
        }
    }

    @Test
    fun testEdgeElementsAlignedWithEdge_largeScreen() {
        with(largeScreenConstraint) {
            assertThat(getConstraint(R.id.clock).layout.startToEnd).isEqualTo(R.id.begin_guide)
            assertThat(getConstraint(R.id.clock).layout.horizontalBias).isEqualTo(0.5f)

            assertThat(getConstraint(R.id.privacy_container).layout.endToStart)
                .isEqualTo(R.id.end_guide)
            assertThat(getConstraint(R.id.privacy_container).layout.horizontalBias).isEqualTo(0.5f)
        }
    }

    @Test
    fun testCarrierAlpha() {
        assertThat(qqsConstraint.getConstraint(R.id.carrier_group).propertySet.alpha).isEqualTo(0f)
        assertThat(qsConstraint.getConstraint(R.id.carrier_group).propertySet.alpha).isEqualTo(1f)
        assertThat(largeScreenConstraint.getConstraint(R.id.carrier_group).propertySet.alpha)
            .isEqualTo(1f)
    }

    @Test
    fun testPrivacyChipVisibilityConstraints_notVisible() {
        val changes = CombinedShadeHeadersConstraintManagerImpl
            .privacyChipVisibilityConstraints(false)
        changes()

        with(qqsConstraint) {
            assertThat(systemIconsAlphaConstraint).isEqualTo(1f)
        }

        with(qsConstraint) {
            assertThat(systemIconsAlphaConstraint).isEqualTo(1f)
        }

        with(largeScreenConstraint) {
            assertThat(systemIconsAlphaConstraint).isEqualTo(1f)
        }
    }

    @Test
    fun testPrivacyChipVisibilityConstraints_visible() {
        val changes = CombinedShadeHeadersConstraintManagerImpl
            .privacyChipVisibilityConstraints(true)
        changes()

        with(qqsConstraint) {
            assertThat(systemIconsAlphaConstraint).isEqualTo(0f)
        }

        with(qsConstraint) {
            assertThat(systemIconsAlphaConstraint).isEqualTo(1f)
        }

        with(largeScreenConstraint) {
            assertThat(systemIconsAlphaConstraint).isEqualTo(1f)
        }
    }

    @Test
    fun testEmptyCutoutConstraints() {
        val changes = CombinedShadeHeadersConstraintManagerImpl.emptyCutoutConstraints()
        changes()

        // QS and Large Screen don't change with cutouts.
        assertThat(changes.qsConstraintsChanges).isNull()
        assertThat(changes.largeScreenConstraintsChanges).isNull()

        with(qqsConstraint) {
            // In this case, the date is constrained on the end by a Barrier determined by either
            // privacy or clickableIcons
            assertThat(getConstraint(R.id.date).layout.endToStart).isEqualTo(R.id.barrier)
            assertThat(getConstraint(R.id.shade_header_system_icons).layout.startToEnd)
                .isEqualTo(R.id.date)
            assertThat(getConstraint(R.id.privacy_container).layout.startToEnd).isEqualTo(R.id.date)
            assertThat(getConstraint(R.id.barrier).layout.mReferenceIds).asList().containsExactly(
                R.id.shade_header_system_icons,
                R.id.privacy_container
            )
            assertThat(getConstraint(R.id.barrier).layout.mBarrierDirection).isEqualTo(START)
        }
    }

    @Test
    fun testGuidesAreSetInCorrectPosition_largeCutoutSmallerPadding() {
        val cutoutStart = 100
        val padding = 10
        val cutoutEnd = 30
        val changes = CombinedShadeHeadersConstraintManagerImpl.edgesGuidelinesConstraints(
            cutoutStart,
            padding,
            cutoutEnd,
            padding
        )
        changes()

        with(qqsConstraint) {
            assertThat(getConstraint(R.id.begin_guide).layout.guideBegin)
                .isEqualTo(cutoutStart - padding)
            assertThat(getConstraint(R.id.end_guide).layout.guideEnd)
                .isEqualTo(cutoutEnd - padding)
        }

        with(qsConstraint) {
            assertThat(getConstraint(R.id.begin_guide).layout.guideBegin)
                .isEqualTo(cutoutStart - padding)
            assertThat(getConstraint(R.id.end_guide).layout.guideEnd)
                .isEqualTo(cutoutEnd - padding)
        }

        with(largeScreenConstraint) {
            assertThat(getConstraint(R.id.begin_guide).layout.guideBegin)
                .isEqualTo(cutoutStart - padding)
            assertThat(getConstraint(R.id.end_guide).layout.guideEnd)
                .isEqualTo(cutoutEnd - padding)
        }
    }

    @Test
    fun testGuidesAreSetInCorrectPosition_smallCutoutLargerPadding() {
        val cutoutStart = 5
        val padding = 10
        val cutoutEnd = 10

        val changes = CombinedShadeHeadersConstraintManagerImpl.edgesGuidelinesConstraints(
            cutoutStart,
            padding,
            cutoutEnd,
            padding
        )
        changes()

        with(qqsConstraint) {
            assertThat(getConstraint(R.id.begin_guide).layout.guideBegin).isEqualTo(0)
            assertThat(getConstraint(R.id.end_guide).layout.guideEnd).isEqualTo(0)
        }

        with(qsConstraint) {
            assertThat(getConstraint(R.id.begin_guide).layout.guideBegin).isEqualTo(0)
            assertThat(getConstraint(R.id.end_guide).layout.guideEnd).isEqualTo(0)
        }

        with(largeScreenConstraint) {
            assertThat(getConstraint(R.id.begin_guide).layout.guideBegin).isEqualTo(0)
            assertThat(getConstraint(R.id.end_guide).layout.guideEnd).isEqualTo(0)
        }
    }

    @Test
    fun testCenterCutoutConstraints_ltr() {
        val offsetFromEdge = 400
        val rtl = false

        val changes = CombinedShadeHeadersConstraintManagerImpl
            .centerCutoutConstraints(rtl, offsetFromEdge)
        changes()

        // In LTR, center_left is towards the start and center_right is towards the end
        with(qqsConstraint) {
            assertThat(getConstraint(R.id.center_left).layout.guideBegin).isEqualTo(offsetFromEdge)
            assertThat(getConstraint(R.id.center_right).layout.guideEnd).isEqualTo(offsetFromEdge)
            assertThat(getConstraint(R.id.date).layout.endToStart).isEqualTo(R.id.center_left)
            assertThat(getConstraint(R.id.shade_header_system_icons).layout.startToEnd)
                .isEqualTo(R.id.center_right)
            assertThat(getConstraint(R.id.privacy_container).layout.startToEnd)
                .isEqualTo(R.id.center_right)
        }

        with(qsConstraint) {
            assertThat(getConstraint(R.id.center_left).layout.guideBegin).isEqualTo(offsetFromEdge)
            assertThat(getConstraint(R.id.center_right).layout.guideEnd).isEqualTo(offsetFromEdge)

            assertThat(getConstraint(R.id.date).layout.endToStart).isNotEqualTo(R.id.center_left)
            assertThat(getConstraint(R.id.date).layout.endToStart).isNotEqualTo(R.id.center_right)

            assertThat(getConstraint(R.id.shade_header_system_icons).layout.startToEnd)
                .isNotEqualTo(R.id.center_left)
            assertThat(getConstraint(R.id.shade_header_system_icons).layout.startToEnd)
                .isNotEqualTo(R.id.center_right)

            assertThat(getConstraint(R.id.privacy_container).layout.startToEnd)
                .isEqualTo(R.id.center_right)
        }

        assertThat(changes.largeScreenConstraintsChanges).isNull()
    }

    @Test
    fun testCenterCutoutConstraints_rtl() {
        val offsetFromEdge = 400
        val rtl = true

        val changes = CombinedShadeHeadersConstraintManagerImpl
            .centerCutoutConstraints(rtl, offsetFromEdge)
        changes()

        // In RTL, center_left is towards the end and center_right is towards the start
        with(qqsConstraint) {
            assertThat(getConstraint(R.id.center_left).layout.guideEnd).isEqualTo(offsetFromEdge)
            assertThat(getConstraint(R.id.center_right).layout.guideBegin).isEqualTo(offsetFromEdge)
            assertThat(getConstraint(R.id.date).layout.endToStart).isEqualTo(R.id.center_right)
            assertThat(getConstraint(R.id.shade_header_system_icons).layout.startToEnd)
                .isEqualTo(R.id.center_left)
            assertThat(getConstraint(R.id.privacy_container).layout.startToEnd)
                .isEqualTo(R.id.center_left)
        }

        with(qsConstraint) {
            assertThat(getConstraint(R.id.center_left).layout.guideEnd).isEqualTo(offsetFromEdge)
            assertThat(getConstraint(R.id.center_right).layout.guideBegin).isEqualTo(offsetFromEdge)

            assertThat(getConstraint(R.id.date).layout.endToStart).isNotEqualTo(R.id.center_left)
            assertThat(getConstraint(R.id.date).layout.endToStart).isNotEqualTo(R.id.center_right)

            assertThat(getConstraint(R.id.shade_header_system_icons).layout.startToEnd)
                .isNotEqualTo(R.id.center_left)
            assertThat(getConstraint(R.id.shade_header_system_icons).layout.startToEnd)
                .isNotEqualTo(R.id.center_right)

            assertThat(getConstraint(R.id.privacy_container).layout.startToEnd)
                .isEqualTo(R.id.center_left)
        }

        assertThat(changes.largeScreenConstraintsChanges).isNull()
    }

    @Test
    fun testRelevantViewsAreNotMatchConstraints() {
        val views = mapOf(
                R.id.clock to "clock",
                R.id.date to "date",
                R.id.privacy_container to "privacy",
        )
        views.forEach { (id, name) ->
            assertWithMessage("$name has 0 height in qqs")
                    .that(qqsConstraint.getConstraint(id).layout.mHeight).isNotEqualTo(0)
            assertWithMessage("$name has 0 width in qqs")
                    .that(qqsConstraint.getConstraint(id).layout.mWidth).isNotEqualTo(0)
            assertWithMessage("$name has 0 height in qs")
                    .that(qsConstraint.getConstraint(id).layout.mHeight).isNotEqualTo(0)
            assertWithMessage("$name has 0 width in qs")
                    .that(qsConstraint.getConstraint(id).layout.mWidth).isNotEqualTo(0)
        }
    }

    @Test
    fun testCheckViewsDontChangeSizeBetweenAnimationConstraints() {
        val views = mapOf(
                R.id.clock to "clock",
                R.id.privacy_container to "privacy",
        )
        views.forEach { (id, name) ->
            expect.withMessage("$name changes height")
                    .that(qqsConstraint.getConstraint(id).layout.mHeight.fromConstraint())
                    .isEqualTo(qsConstraint.getConstraint(id).layout.mHeight.fromConstraint())
            expect.withMessage("$name changes width")
                    .that(qqsConstraint.getConstraint(id).layout.mWidth.fromConstraint())
                    .isEqualTo(qsConstraint.getConstraint(id).layout.mWidth.fromConstraint())
        }
    }

    private fun Int.fromConstraint() = when (this) {
        ViewGroup.LayoutParams.MATCH_PARENT -> "MATCH_PARENT"
        ViewGroup.LayoutParams.WRAP_CONTENT -> "WRAP_CONTENT"
        else -> toString()
    }

    @Test
    fun testEmptyCutoutDateIconsAreConstrainedWidth() {
        CombinedShadeHeadersConstraintManagerImpl.emptyCutoutConstraints()()

        assertThat(qqsConstraint.getConstraint(R.id.date).layout.constrainedWidth).isTrue()
        val shadeHeaderConstraint = qqsConstraint.getConstraint(R.id.shade_header_system_icons)
        assertThat(shadeHeaderConstraint.layout.constrainedWidth).isTrue()
    }

    @Test
    fun testCenterCutoutDateIconsAreConstrainedWidth() {
        CombinedShadeHeadersConstraintManagerImpl.centerCutoutConstraints(false, 10)()

        assertThat(qqsConstraint.getConstraint(R.id.date).layout.constrainedWidth).isTrue()
        val shadeHeaderConstraint = qqsConstraint.getConstraint(R.id.shade_header_system_icons)
        assertThat(shadeHeaderConstraint.layout.constrainedWidth).isTrue()
    }

    private val ConstraintSet.systemIconsAlphaConstraint
        get() = getConstraint(R.id.shade_header_system_icons).propertySet.alpha

    private operator fun ConstraintsChanges.invoke() {
        qqsConstraintsChanges?.invoke(qqsConstraint)
        qsConstraintsChanges?.invoke(qsConstraint)
        largeScreenConstraintsChanges?.invoke(largeScreenConstraint)
    }
}
