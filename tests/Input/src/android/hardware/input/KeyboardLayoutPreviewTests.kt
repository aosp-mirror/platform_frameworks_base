/*
 * Copyright 2023 The Android Open Source Project
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

package android.hardware.input

import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import android.content.ContextWrapper
import android.graphics.drawable.Drawable
import android.platform.test.annotations.Presubmit
import android.platform.test.flag.junit.SetFlagsRule
import androidx.test.platform.app.InstrumentationRegistry
import com.android.hardware.input.Flags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.junit.MockitoJUnitRunner

/**
 * Tests for Keyboard layout preview
 *
 * Build/Install/Run:
 * atest InputTests:KeyboardLayoutPreviewTests
 */
@Presubmit
@RunWith(MockitoJUnitRunner::class)
class KeyboardLayoutPreviewTests {

    companion object {
        const val WIDTH = 100
        const val HEIGHT = 100
    }

    @get:Rule
    val setFlagsRule = SetFlagsRule()

    private fun createDrawable(): Drawable? {
        val context = ContextWrapper(InstrumentationRegistry.getInstrumentation().getContext())
        val inputManager = context.getSystemService(InputManager::class.java)!!
        return inputManager.getKeyboardLayoutPreview(null, WIDTH, HEIGHT)
    }

    @Test
    @EnableFlags(Flags.FLAG_KEYBOARD_LAYOUT_PREVIEW_FLAG)
    fun testKeyboardLayoutDrawable_hasCorrectDimensions() {
        val drawable = createDrawable()!!
        assertEquals(WIDTH, drawable.intrinsicWidth)
        assertEquals(HEIGHT, drawable.intrinsicHeight)
    }

    @Test
    @DisableFlags(Flags.FLAG_KEYBOARD_LAYOUT_PREVIEW_FLAG)
    fun testKeyboardLayoutDrawable_isNull_ifFlagOff() {
        assertNull(createDrawable())
    }
}