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

package com.android.systemui.statusbar.chips.ui.view

import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.google.common.truth.Truth.assertThat
import kotlin.test.Test
import org.junit.Before
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class ChipTextTruncationHelperTest : SysuiTestCase() {

    val underTest by lazy { ChipTextTruncationHelper(TextView(context)) }

    @Before
    fun setUp() {
        mContext.getOrCreateTestableResources().apply {
            this.addOverride(R.dimen.ongoing_activity_chip_max_text_width, MAX_WIDTH)
        }
    }

    @Test
    fun shouldShowText_desiredLessThanMax_true() {
        val result = underTest.shouldShowText(desiredTextWidthPx = MAX_WIDTH / 2)

        assertThat(result).isTrue()
    }

    @Test
    fun shouldShowText_desiredSlightlyLargerThanMax_true() {
        val result = underTest.shouldShowText(desiredTextWidthPx = (MAX_WIDTH * 1.1).toInt())

        assertThat(result).isTrue()
    }

    @Test
    fun shouldShowText_desiredMoreThanTwiceMax_false() {
        val result = underTest.shouldShowText(desiredTextWidthPx = (MAX_WIDTH * 2.2).toInt())

        assertThat(result).isFalse()
    }

    companion object {
        private const val MAX_WIDTH = 200
    }
}
