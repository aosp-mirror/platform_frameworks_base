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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.hardware.input.InputManager
import android.hardware.input.KeyboardLayout
import android.util.TypedValue
import kotlin.math.roundToInt

object LayoutPreview {
    fun createLayoutPreview(context: Context, layout: KeyboardLayout?): Bitmap {
        val im = context.getSystemService(InputManager::class.java)!!
        val width = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                600.0F, context.getResources().getDisplayMetrics()).roundToInt()
        val height = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP,
                200.0F, context.getResources().getDisplayMetrics()).roundToInt()
        val drawable = im.getKeyboardLayoutPreview(layout, width, height)!!
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight())
        drawable.draw(canvas)
        return bitmap
    }
}