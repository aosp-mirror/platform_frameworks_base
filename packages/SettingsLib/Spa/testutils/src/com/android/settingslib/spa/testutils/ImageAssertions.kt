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

package com.android.settingslib.spa.testutils

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap

/**
 * Asserts that the expected color is present in this bitmap.
 *
 * @throws AssertionError if the expected color is not present.
 */
fun ImageBitmap.assertContainsColor(expectedColor: Color) {
    assert(containsColor(expectedColor)) {
        "The given color $expectedColor was not found in the bitmap."
    }
}

private fun ImageBitmap.containsColor(expectedColor: Color): Boolean {
    val pixels = toPixelMap()
    for (x in 0 until width) {
        for (y in 0 until height) {
            if (pixels[x, y] == expectedColor) return true
        }
    }
    return false
}
