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

package com.android.keyguard

import android.view.LayoutInflater
import com.android.systemui.R
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test

class PinShapeHintingViewTest : SysuiTestCase() {
    lateinit var mPinShapeHintingView: PinShapeHintingView

    @Before
    fun setup() {
        mPinShapeHintingView =
            LayoutInflater.from(context).inflate(R.layout.keyguard_pin_shape_six_digit_view, null)
                as PinShapeHintingView
    }

    @Test
    fun testAppend() {
        // Add more when animation part is complete
        mPinShapeHintingView.append()
    }

    @Test
    fun testDelete() {
        mPinShapeHintingView.delete()
    }
}
