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

import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.constraintlayout.widget.ConstraintSet
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.keyguard.domain.interactor.KeyguardBlueprintInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardClockInteractor
import com.android.systemui.keyguard.ui.viewmodel.KeyguardClockViewModel
import com.android.systemui.res.R
import com.android.systemui.statusbar.policy.SplitShadeStateController
import com.android.systemui.util.Utils
import com.android.systemui.util.mockito.eq
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.MockitoAnnotations

@RunWith(JUnit4::class)
@SmallTest
class ClockSectionTest : SysuiTestCase() {
    @Mock private lateinit var keyguardClockInteractor: KeyguardClockInteractor
    @Mock private lateinit var keyguardClockViewModel: KeyguardClockViewModel
    @Mock private lateinit var splitShadeStateController: SplitShadeStateController
    @Mock private lateinit var blueprintInteractor: Lazy<KeyguardBlueprintInteractor>
    private val clockShouldBeCentered: MutableStateFlow<Boolean> = MutableStateFlow(true)

    private lateinit var underTest: ClockSection

    private val SMALL_CLOCK_TOP_SPLIT_SHADE =
        context.resources.getDimensionPixelSize(R.dimen.keyguard_split_shade_top_margin)

    private val SMALL_CLOCK_TOP_NON_SPLIT_SHADE =
        context.resources.getDimensionPixelSize(R.dimen.keyguard_clock_top_margin) +
            Utils.getStatusBarHeaderHeightKeyguard(context)

    private val LARGE_CLOCK_TOP =
        context.resources.getDimensionPixelSize(R.dimen.status_bar_height) +
            context.resources.getDimensionPixelSize(
                com.android.systemui.customization.R.dimen.small_clock_padding_top
            ) +
            context.resources.getDimensionPixelSize(R.dimen.keyguard_smartspace_top_offset) +
            SMART_SPACE_DATE_WEATHER_HEIGHT +
            ENHANCED_SMART_SPACE_HEIGHT

    private val CLOCK_FADE_TRANSLATION_Y =
        context.resources.getDimensionPixelSize(
            com.android.systemui.customization.R.dimen.small_clock_height
        )

    @Before
    fun setup() {
        MockitoAnnotations.initMocks(this)
        val remoteResources = mock<Resources>()
        whenever(
                remoteResources.getIdentifier(
                    anyString(),
                    eq("dimen"),
                    anyString(),
                )
            )
            .then { invocation ->
                val name = invocation.arguments[0] as String
                val index = DIMENSION_BY_IDENTIFIER_NAME.indexOfFirst { (key, _) -> key == name }
                if (index == -1) {
                    error(
                        "No entry for a dimension named \"$name\", please add it to the list above."
                    )
                }
                index
            }
        whenever(
                remoteResources.getDimensionPixelSize(
                    anyInt(),
                )
            )
            .then { invocation ->
                val id = invocation.arguments[0] as Int
                DIMENSION_BY_IDENTIFIER_NAME[id].second
            }
        val packageManager = mock<PackageManager>()
        whenever(packageManager.getResourcesForApplication(anyString())).thenReturn(remoteResources)
        mContext.setMockPackageManager(packageManager)

        whenever(keyguardClockViewModel.clockShouldBeCentered).thenReturn(clockShouldBeCentered)

        underTest =
            ClockSection(
                keyguardClockInteractor,
                keyguardClockViewModel,
                mContext,
                splitShadeStateController,
                blueprintInteractor
            )
    }

    @Test
    fun testApplyDefaultConstraints_LargeClock_SplitShade() {
        setLargeClock(true)
        setSplitShade(true)
        val cs = ConstraintSet()
        underTest.applyDefaultConstraints(cs)

        val expectedLargeClockTopMargin = LARGE_CLOCK_TOP
        assetLargeClockTop(cs, expectedLargeClockTopMargin)

        val expectedSmallClockTopMargin = SMALL_CLOCK_TOP_SPLIT_SHADE - CLOCK_FADE_TRANSLATION_Y
        assetSmallClockTop(cs, expectedSmallClockTopMargin)
    }

    @Test
    fun testApplyDefaultConstraints_LargeClock_NonSplitShade() {
        setLargeClock(true)
        setSplitShade(false)
        val cs = ConstraintSet()
        underTest.applyDefaultConstraints(cs)

        val expectedLargeClockTopMargin = LARGE_CLOCK_TOP
        assetLargeClockTop(cs, expectedLargeClockTopMargin)

        val expectedSmallClockTopMargin = SMALL_CLOCK_TOP_NON_SPLIT_SHADE - CLOCK_FADE_TRANSLATION_Y
        assetSmallClockTop(cs, expectedSmallClockTopMargin)
    }

    @Test
    fun testApplyDefaultConstraints_SmallClock_SplitShade() {
        setLargeClock(false)
        setSplitShade(true)
        val cs = ConstraintSet()
        underTest.applyDefaultConstraints(cs)

        val expectedLargeClockTopMargin = LARGE_CLOCK_TOP - CLOCK_FADE_TRANSLATION_Y
        assetLargeClockTop(cs, expectedLargeClockTopMargin)

        val expectedSmallClockTopMargin = SMALL_CLOCK_TOP_SPLIT_SHADE
        assetSmallClockTop(cs, expectedSmallClockTopMargin)
    }

    @Test
    fun testApplyDefaultConstraints_SmallClock_NonSplitShade() {
        setLargeClock(false)
        setSplitShade(false)
        val cs = ConstraintSet()
        underTest.applyDefaultConstraints(cs)
        val expectedLargeClockTopMargin = LARGE_CLOCK_TOP - CLOCK_FADE_TRANSLATION_Y
        assetLargeClockTop(cs, expectedLargeClockTopMargin)

        val expectedSmallClockTopMargin = SMALL_CLOCK_TOP_NON_SPLIT_SHADE
        assetSmallClockTop(cs, expectedSmallClockTopMargin)
    }

    private fun setLargeClock(useLargeClock: Boolean) {
        whenever(keyguardClockViewModel.useLargeClock).thenReturn(useLargeClock)
    }

    private fun setSplitShade(isInSplitShade: Boolean) {
        whenever(splitShadeStateController.shouldUseSplitNotificationShade(context.resources))
            .thenReturn(isInSplitShade)
    }

    private fun assetLargeClockTop(cs: ConstraintSet, expectedLargeClockTopMargin: Int) {
        val largeClockConstraint = cs.getConstraint(R.id.lockscreen_clock_view_large)
        assertThat(largeClockConstraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(largeClockConstraint.layout.topMargin).isEqualTo(expectedLargeClockTopMargin)
    }

    private fun assetSmallClockTop(cs: ConstraintSet, expectedSmallClockTopMargin: Int) {
        val smallClockConstraint = cs.getConstraint(R.id.lockscreen_clock_view)
        assertThat(smallClockConstraint.layout.topToTop).isEqualTo(ConstraintSet.PARENT_ID)
        assertThat(smallClockConstraint.layout.topMargin).isEqualTo(expectedSmallClockTopMargin)
    }

    companion object {
        private val SMART_SPACE_DATE_WEATHER_HEIGHT = 10
        private val ENHANCED_SMART_SPACE_HEIGHT = 11
        private val DIMENSION_BY_IDENTIFIER_NAME =
            listOf(
                "date_weather_view_height" to SMART_SPACE_DATE_WEATHER_HEIGHT,
                "enhanced_smartspace_height" to ENHANCED_SMART_SPACE_HEIGHT,
            )
    }
}
