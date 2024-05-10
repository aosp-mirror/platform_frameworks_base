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
package com.android.systemui.surfaceeffects.ripple

import android.testing.AndroidTestingRunner
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidTestingRunner::class)
class RippleViewTest : SysuiTestCase() {
    private lateinit var rippleView: RippleView

    @Before
    fun setup() {
        rippleView = RippleView(context, null)
    }

    @Test
    fun testSetupShader_compilesCircle() {
        rippleView.setupShader(RippleShader.RippleShape.CIRCLE)
    }

    @Test
    fun testSetupShader_compilesRoundedBox() {
        rippleView.setupShader(RippleShader.RippleShape.ROUNDED_BOX)
    }

    @Test
    fun testSetupShader_compilesEllipse() {
        rippleView.setupShader(RippleShader.RippleShape.ELLIPSE)
    }
}
