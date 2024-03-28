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

package com.android.settingslib.spa.framework.theme

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import androidx.compose.ui.graphics.Color

@RunWith(AndroidJUnit4::class)
class SettingsColorsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun testDynamicTheme() {
        // The dynamic color could be different in different device, just check basic restrictions:
        // 1. text color is different with background color
        // 2. primary / spinner color is different with its on-item color
        val ls = dynamicLightColorScheme(context)
        assertThat(ls.categoryTitle).isNotEqualTo(ls.background)
        assertThat(ls.secondaryText).isNotEqualTo(ls.background)
        assertThat(ls.primaryContainer).isNotEqualTo(ls.onPrimaryContainer)

        val ds = dynamicDarkColorScheme(context)
        assertThat(ds.categoryTitle).isNotEqualTo(ds.background)
        assertThat(ds.secondaryText).isNotEqualTo(ds.background)
        assertThat(ds.primaryContainer).isNotEqualTo(ds.onPrimaryContainer)
    }

    @Test
    fun testStaticTheme() {
        val ls = lightColorScheme()
        assertThat(ls.background).isEqualTo(Color(red = 244, green = 239, blue = 244))
        assertThat(ls.categoryTitle).isEqualTo(Color(red = 103, green = 80, blue = 164))
        assertThat(ls.surface).isEqualTo(Color(red = 255, green = 251, blue = 254))
        assertThat(ls.surfaceHeader).isEqualTo(Color(red = 230, green = 225, blue = 229))
        assertThat(ls.secondaryText).isEqualTo(Color(red = 73, green = 69, blue = 79))
        assertThat(ls.primaryContainer).isEqualTo(Color(red = 234, green = 221, blue = 255))
        assertThat(ls.onPrimaryContainer).isEqualTo(Color(red = 28, green = 27, blue = 31))

        val ds = darkColorScheme()
        assertThat(ds.background).isEqualTo(Color(red = 28, green = 27, blue = 31))
        assertThat(ds.categoryTitle).isEqualTo(Color(red = 234, green = 221, blue = 255))
        assertThat(ds.surface).isEqualTo(Color(red = 49, green = 48, blue = 51))
        assertThat(ds.surfaceHeader).isEqualTo(Color(red = 72, green = 70, blue = 73))
        assertThat(ds.secondaryText).isEqualTo(Color(red = 202, green = 196, blue = 208))
        assertThat(ds.primaryContainer).isEqualTo(Color(red = 232, green = 222, blue = 248))
        assertThat(ds.onPrimaryContainer).isEqualTo(Color(red = 28, green = 27, blue = 31))
    }
}
