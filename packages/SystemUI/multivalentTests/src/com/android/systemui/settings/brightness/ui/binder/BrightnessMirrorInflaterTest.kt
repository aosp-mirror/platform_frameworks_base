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

package com.android.systemui.settings.brightness.ui.binder

import android.content.applicationContext
import android.view.ContextThemeWrapper
import android.view.View
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.systemui.SysuiTestCase
import com.android.systemui.res.R
import com.android.systemui.settings.brightnessSliderControllerFactory
import com.android.systemui.testKosmos
import com.android.systemui.util.Assert
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@SmallTest
@RunWith(AndroidJUnit4::class)
class BrightnessMirrorInflaterTest : SysuiTestCase() {
    private val kosmos = testKosmos()

    private val themedContext =
        ContextThemeWrapper(kosmos.applicationContext, R.style.Theme_SystemUI_QuickSettings)

    @Test
    fun inflate_sliderViewAddedToFrame() {
        Assert.setTestThread(Thread.currentThread())

        val (frame, sliderController) =
            BrightnessMirrorInflater.inflate(
                themedContext,
                kosmos.brightnessSliderControllerFactory
            )

        assertThat(sliderController.rootView.parent).isSameInstanceAs(frame)

        Assert.setTestThread(null)
    }

    @Test
    fun inflate_frameAndSliderViewVisible() {
        Assert.setTestThread(Thread.currentThread())

        val (frame, sliderController) =
            BrightnessMirrorInflater.inflate(
                themedContext,
                kosmos.brightnessSliderControllerFactory,
            )

        assertThat(frame.visibility).isEqualTo(View.VISIBLE)
        assertThat(sliderController.rootView.visibility).isEqualTo(View.VISIBLE)

        Assert.setTestThread(null)
    }
}
