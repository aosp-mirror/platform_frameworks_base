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

package com.android.systemui.statusbar.phone

import android.graphics.Color
import android.graphics.Rect
import android.testing.AndroidTestingRunner
import android.view.WindowInsetsController
import android.view.WindowInsetsController.APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS
import androidx.test.filters.SmallTest
import com.android.internal.statusbar.LetterboxDetails
import com.android.internal.view.AppearanceRegion
import com.android.systemui.SysuiTestCase
import com.android.systemui.dump.DumpManager
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.Mockito.`when` as whenever
import org.mockito.MockitoAnnotations

@RunWith(AndroidTestingRunner::class)
@SmallTest
class LetterboxAppearanceCalculatorTest : SysuiTestCase() {

    companion object {
        private const val DEFAULT_APPEARANCE = 0
        private const val TEST_APPEARANCE = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        private val TEST_APPEARANCE_REGION_BOUNDS = Rect(0, 0, 20, 100)
        private val TEST_APPEARANCE_REGION =
            AppearanceRegion(TEST_APPEARANCE, TEST_APPEARANCE_REGION_BOUNDS)
        private val TEST_APPEARANCE_REGIONS = listOf(TEST_APPEARANCE_REGION)
        private val TEST_WINDOW_BOUNDS = Rect(0, 0, 500, 500)
    }

    @get:Rule var expect = Expect.create()

    @Mock private lateinit var dumpManager: DumpManager
    @Mock private lateinit var letterboxBackgroundProvider: LetterboxBackgroundProvider

    private lateinit var calculator: LetterboxAppearanceCalculator

    @Before
    fun setUp() {
        MockitoAnnotations.initMocks(this)
        calculator =
            LetterboxAppearanceCalculator(context, dumpManager, letterboxBackgroundProvider)
        whenever(letterboxBackgroundProvider.letterboxBackgroundColor).thenReturn(Color.BLACK)
        whenever(letterboxBackgroundProvider.isLetterboxBackgroundMultiColored).thenReturn(false)
    }

    @Test
    fun getLetterboxAppearance_overlapStartSide_returnsOriginalWithScrim() {
        val start = Rect(0, 0, 100, 100)
        val end = Rect(200, 0, 300, 100)
        val letterbox = letterboxWithInnerBounds(Rect(50, 50, 150, 150))

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, listOf(letterbox), BoundsPair(start, end))

        expect
            .that(letterboxAppearance.appearance)
            .isEqualTo(TEST_APPEARANCE or APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS)
        expect.that(letterboxAppearance.appearanceRegions).isEqualTo(TEST_APPEARANCE_REGIONS)
    }

    @Test
    fun getLetterboxAppearance_overlapEndSide_returnsOriginalWithScrim() {
        val start = Rect(0, 0, 100, 100)
        val end = Rect(200, 0, 300, 100)
        val letterbox = letterboxWithInnerBounds(Rect(150, 50, 250, 150))

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, listOf(letterbox), BoundsPair(start, end))

        expect
            .that(letterboxAppearance.appearance)
            .isEqualTo(TEST_APPEARANCE or APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS)
        expect.that(letterboxAppearance.appearanceRegions).isEqualTo(TEST_APPEARANCE_REGIONS)
    }

    /** Regression test for b/287508741 */
    @Test
    fun getLetterboxAppearance_withOverlap_doesNotMutateOriginalBounds() {
        val statusBarStartSideBounds = Rect(left = 0, top = 0, right = 100, bottom = 100)
        val statusBarEndSideBounds = Rect(left = 200, top = 0, right = 300, bottom = 100)
        val letterBoxInnerBounds = Rect(left = 150, top = 50, right = 250, bottom = 150)
        val statusBarStartSideBoundsCopy = Rect(statusBarStartSideBounds)
        val statusBarEndSideBoundsCopy = Rect(statusBarEndSideBounds)
        val letterBoxInnerBoundsCopy = Rect(letterBoxInnerBounds)

        calculator.getLetterboxAppearance(
                TEST_APPEARANCE,
                TEST_APPEARANCE_REGIONS,
            listOf(letterboxWithInnerBounds(letterBoxInnerBounds)),
            BoundsPair(statusBarStartSideBounds, statusBarEndSideBounds)
        )

        expect.that(statusBarStartSideBounds).isEqualTo(statusBarStartSideBoundsCopy)
        expect.that(statusBarEndSideBounds).isEqualTo(statusBarEndSideBoundsCopy)
        expect.that(letterBoxInnerBounds).isEqualTo(letterBoxInnerBoundsCopy)
    }

    @Test
    fun getLetterboxAppearance_noOverlap_BackgroundMultiColor_returnsAppearanceWithScrim() {
        whenever(letterboxBackgroundProvider.isLetterboxBackgroundMultiColored).thenReturn(true)
        val start = Rect(0, 0, 100, 100)
        val end = Rect(200, 0, 300, 100)
        val letterbox = letterboxWithInnerBounds(Rect(101, 0, 199, 100))

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, listOf(letterbox), BoundsPair(start, end))

        expect
                .that(letterboxAppearance.appearance)
                .isEqualTo(TEST_APPEARANCE or APPEARANCE_SEMI_TRANSPARENT_STATUS_BARS)
        expect.that(letterboxAppearance.appearanceRegions).isEqualTo(TEST_APPEARANCE_REGIONS)
    }

    @Test
    fun getLetterboxAppearance_noOverlap_returnsAppearanceWithoutScrim() {
        val start = Rect(0, 0, 100, 100)
        val end = Rect(200, 0, 300, 100)
        val letterbox = letterboxWithInnerBounds(Rect(101, 0, 199, 100))

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, listOf(letterbox), BoundsPair(start, end))

        assertThat(letterboxAppearance.appearance).isEqualTo(TEST_APPEARANCE)
    }

    @Test
    fun getLetterboxAppearance_letterboxContainsStartSide_returnsAppearanceWithoutScrim() {
        val start = Rect(0, 0, 100, 100)
        val end = Rect(200, 0, 300, 100)
        val letterbox = letterboxWithInnerBounds(Rect(0, 0, 101, 101))

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, listOf(letterbox), BoundsPair(start, end))

        assertThat(letterboxAppearance.appearance).isEqualTo(TEST_APPEARANCE)
    }

    @Test
    fun getLetterboxAppearance_letterboxContainsEndSide_returnsAppearanceWithoutScrim() {
        val start = Rect(0, 0, 100, 100)
        val end = Rect(200, 0, 300, 100)
        val letterbox = letterboxWithInnerBounds(Rect(199, 0, 301, 101))

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, listOf(letterbox), BoundsPair(start, end))

        assertThat(letterboxAppearance.appearance).isEqualTo(TEST_APPEARANCE)
    }

    @Test
    fun getLetterboxAppearance_letterboxContainsEntireStatusBar_returnsAppearanceWithoutScrim() {
        val start = Rect(0, 0, 100, 100)
        val end = Rect(200, 0, 300, 100)
        val letterbox = letterboxWithInnerBounds(Rect(0, 0, 300, 100))

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, TEST_APPEARANCE_REGIONS, listOf(letterbox), BoundsPair(start, end))

        assertThat(letterboxAppearance.appearance).isEqualTo(TEST_APPEARANCE)
    }

    @Test
    fun getLetterboxAppearance_returnsAdaptedAppearanceRegions_basedOnLetterboxInnerBounds() {
        val start = Rect(0, 0, 0, 0)
        val end = Rect(0, 0, 0, 0)
        val letterbox = letterboxWithInnerBounds(Rect(150, 0, 300, 800))
        val letterboxRegion = TEST_APPEARANCE_REGION.copy(bounds = letterbox.letterboxFullBounds)

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, listOf(letterboxRegion), listOf(letterbox), BoundsPair(start, end))

        val letterboxAdaptedRegion = letterboxRegion.copy(bounds = letterbox.letterboxInnerBounds)
        assertThat(letterboxAppearance.appearanceRegions.toList()).contains(letterboxAdaptedRegion)
        assertThat(letterboxAppearance.appearanceRegions.toList()).doesNotContain(letterboxRegion)
    }

    @Test
    fun getLetterboxAppearance_returnsDefaultAppearanceRegions_basedOnLetterboxOuterBounds() {
        val start = Rect(0, 0, 0, 0)
        val end = Rect(0, 0, 0, 0)
        val letterbox =
            letterboxWithBounds(
                innerBounds = Rect(left = 25, top = 0, right = 75, bottom = 100),
                fullBounds = Rect(left = 0, top = 0, right = 100, bottom = 100))
        val letterboxRegion = TEST_APPEARANCE_REGION.copy(bounds = letterbox.letterboxFullBounds)

        val letterboxAppearance =
            calculator.getLetterboxAppearance(
                TEST_APPEARANCE, listOf(letterboxRegion), listOf(letterbox), BoundsPair(start, end))

        val outerRegions =
            listOf(
                AppearanceRegion(
                    DEFAULT_APPEARANCE,
                    Rect(left = 0, top = 0, right = 25, bottom = 100),
                ),
                AppearanceRegion(
                    DEFAULT_APPEARANCE,
                    Rect(left = 75, top = 0, right = 100, bottom = 100),
                ),
            )
        assertThat(letterboxAppearance.appearanceRegions)
            .containsAtLeastElementsIn(outerRegions)
    }

    private fun letterboxWithBounds(innerBounds: Rect, fullBounds: Rect) =
        LetterboxDetails(innerBounds, fullBounds, TEST_APPEARANCE)

    private fun letterboxWithInnerBounds(innerBounds: Rect) =
        letterboxWithBounds(innerBounds, fullBounds = TEST_WINDOW_BOUNDS)
}

private fun AppearanceRegion.copy(appearance: Int = this.appearance, bounds: Rect = this.bounds) =
    AppearanceRegion(appearance, bounds)

private fun Rect(left: Int, top: Int, right: Int, bottom: Int) = Rect(left, top, right, bottom)
