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

package android.text

import android.graphics.Paint
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

const val LEFT_EDGE = Paint.TEXT_RUN_FLAG_LEFT_EDGE
const val RIGHT_EDGE = Paint.TEXT_RUN_FLAG_RIGHT_EDGE
const val MIDDLE_OF_LINE = 0
const val WHOLE_LINE = LEFT_EDGE or RIGHT_EDGE

@SmallTest
@RunWith(AndroidJUnit4::class)
class TextLineLetterSpacingTest {

    @Rule
    @JvmField
    val mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule()

    @Test
    fun calculateRunFlagTest() {
        // Only one Bidi run
        assertThat(TextLine.calculateRunFlag(0, 1, Layout.DIR_LEFT_TO_RIGHT))
                .isEqualTo(WHOLE_LINE)
        assertThat(TextLine.calculateRunFlag(0, 1, Layout.DIR_RIGHT_TO_LEFT))
                .isEqualTo(WHOLE_LINE)

        // Two BiDi Runs.
        // If the layout is LTR, the first run is the left most run.
        assertThat(TextLine.calculateRunFlag(0, 2, Layout.DIR_LEFT_TO_RIGHT))
                .isEqualTo(LEFT_EDGE)
        // If the layout is LTR, the last run is the right most run.
        assertThat(TextLine.calculateRunFlag(1, 2, Layout.DIR_LEFT_TO_RIGHT))
                .isEqualTo(RIGHT_EDGE)
        // If the layout is RTL, the first run is the right most run.
        assertThat(TextLine.calculateRunFlag(0, 2, Layout.DIR_RIGHT_TO_LEFT))
                .isEqualTo(RIGHT_EDGE)
        // If the layout is RTL, the last run is the left most run.
        assertThat(TextLine.calculateRunFlag(1, 2, Layout.DIR_RIGHT_TO_LEFT))
                .isEqualTo(LEFT_EDGE)

        // Three BiDi Runs.
        // If the layout is LTR, the first run is the left most run.
        assertThat(TextLine.calculateRunFlag(0, 3, Layout.DIR_LEFT_TO_RIGHT))
                .isEqualTo(LEFT_EDGE)
        // Regardless of the context direction, the middle run must not have any flags.
        assertThat(TextLine.calculateRunFlag(1, 3, Layout.DIR_LEFT_TO_RIGHT))
                .isEqualTo(MIDDLE_OF_LINE)
        // If the layout is LTR, the last run is the right most run.
        assertThat(TextLine.calculateRunFlag(2, 3, Layout.DIR_LEFT_TO_RIGHT))
                .isEqualTo(RIGHT_EDGE)
        // If the layout is RTL, the first run is the right most run.
        assertThat(TextLine.calculateRunFlag(0, 3, Layout.DIR_RIGHT_TO_LEFT))
                .isEqualTo(RIGHT_EDGE)
        // Regardless of the context direction, the middle run must not have any flags.
        assertThat(TextLine.calculateRunFlag(1, 3, Layout.DIR_RIGHT_TO_LEFT))
                .isEqualTo(MIDDLE_OF_LINE)
        // If the layout is RTL, the last run is the left most run.
        assertThat(TextLine.calculateRunFlag(2, 3, Layout.DIR_RIGHT_TO_LEFT))
                .isEqualTo(LEFT_EDGE)
    }

    @Test
    fun resolveRunFlagForSubSequenceTest() {
        val runStart = 5
        val runEnd = 15
        // Regardless of the run directions, if the span covers entire Bidi run, the same runFlag
        // should be returned.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, false, runStart, runEnd, runStart, runEnd))
                .isEqualTo(LEFT_EDGE)
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, true, runStart, runEnd, runStart, runEnd))
                .isEqualTo(LEFT_EDGE)
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, false, runStart, runEnd, runStart, runEnd))
                .isEqualTo(RIGHT_EDGE)
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, true, runStart, runEnd, runStart, runEnd))
                .isEqualTo(RIGHT_EDGE)
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, false, runStart, runEnd, runStart, runEnd))
                .isEqualTo(WHOLE_LINE)
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, true, runStart, runEnd, runStart, runEnd))
                .isEqualTo(WHOLE_LINE)
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, false, runStart, runEnd, runStart, runEnd))
                .isEqualTo(MIDDLE_OF_LINE)
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, true, runStart, runEnd, runStart, runEnd))
                .isEqualTo(MIDDLE_OF_LINE)



        // Left edge of LTR text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, false, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(LEFT_EDGE)
        // Left edge of RTL text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, true, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Right edge of LTR text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, false, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Right edge of RTL text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, true, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(RIGHT_EDGE)
        // Whole line of LTR text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, false, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(LEFT_EDGE)
        // Whole line of RTL text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, true, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(RIGHT_EDGE)
        // Middle of LTR text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, false, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Middle of RTL text, span start from run start offset but not cover the run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, true, runStart, runEnd, runStart, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)



        // Left edge of LTR text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, false, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(MIDDLE_OF_LINE)
        // Left edge of RTL text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, true, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(LEFT_EDGE)
        // Right edge of LTR text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, false, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(RIGHT_EDGE)
        // Right edge of RTL text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, true, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(MIDDLE_OF_LINE)
        // Whole line of LTR text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, false, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(RIGHT_EDGE)
        // Whole line of RTL text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, true, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(LEFT_EDGE)
        // Middle of LTR text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, false, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(MIDDLE_OF_LINE)
        // Middle of RTL text, span start from middle of run start offset until end of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, true, runStart, runEnd, runStart + 1, runEnd))
                .isEqualTo(MIDDLE_OF_LINE)



        // Left edge of LTR text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, false, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Left edge of RTL text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                LEFT_EDGE, true, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Right edge of LTR text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, false, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Right edge of RTL text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                RIGHT_EDGE, true, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Whole line of LTR text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, false, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Whole line of RTL text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                WHOLE_LINE, true, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Middle of LTR text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, false, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
        // Middle of RTL text, span start from middle of run.
        assertThat(TextLine.resolveRunFlagForSubSequence(
                MIDDLE_OF_LINE, true, runStart, runEnd, runStart + 1, runEnd - 1))
                .isEqualTo(MIDDLE_OF_LINE)
    }
}
