/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.controls.ui

import android.service.controls.templates.RangeTemplate
import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class ToggleRangeTemplateTest : SysuiTestCase() {

    @Test
    fun testLargeRangeNearestStep() {
        val trt = ToggleRangeBehavior()
        trt.rangeTemplate = RangeTemplate("range", -100000f, 100000f, 0f, 5f, null)

        assertEquals(255f, trt.findNearestStep(253f), 0.1f)
    }

    @Test
    fun testLargeRangeNearestStepWithNegativeValues() {
        val trt = ToggleRangeBehavior()
        trt.rangeTemplate = RangeTemplate("range", -100000f, 100000f, 0f, 5f, null)

        assertEquals(-7855f, trt.findNearestStep(-7853.2f), 0.1f)
    }

    @Test
    fun testFractionalRangeNearestStep() {
        val trt = ToggleRangeBehavior()
        trt.rangeTemplate = RangeTemplate("range", 10f, 11f, 10f, .01f, null)

        assertEquals(10.54f, trt.findNearestStep(10.543f), 0.01f)
    }
}
