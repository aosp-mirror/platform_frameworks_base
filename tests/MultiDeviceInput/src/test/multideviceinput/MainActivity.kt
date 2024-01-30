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

package test.multideviceinput

import android.app.Activity
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets.Type
import android.view.WindowManager


enum class PointerState {
    DOWN, // One or more pointer(s) down, lines are being drawn
    HOVER, // Pointer is hovering
    NONE, // Nothing is touching or hovering
}

data class SharedScaledPointerSize(
        var lineSize: Float,
        var circleSize: Float,
        var state: PointerState
)

class MainActivity : Activity() {
    val TAG = "MultiDeviceInput"
    private val leftState = SharedScaledPointerSize(5f, 20f, PointerState.NONE)
    private val rightState = SharedScaledPointerSize(5f, 20f, PointerState.NONE)
    private lateinit var left: View
    private lateinit var right: View

    override fun onResume() {
        super.onResume()

        val wm = getSystemService(WindowManager::class.java)
        val wmlp = WindowManager.LayoutParams(WindowManager.LayoutParams.TYPE_APPLICATION)
        wmlp.flags = (wmlp.flags or
                      WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                      WindowManager.LayoutParams.FLAG_SPLIT_TOUCH)

        val windowMetrics = windowManager.currentWindowMetrics
        val insets = windowMetrics.windowInsets.getInsetsIgnoringVisibility(Type.systemBars())
        val width = windowMetrics.bounds.width() - insets.left - insets.right
        val height = windowMetrics.bounds.height() - insets.top - insets.bottom

        wmlp.width = width * 24 / 50
        wmlp.height = height * 35 / 50

        val vglp = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        )

        wmlp.setTitle("Left -- " + getPackageName())
        wmlp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        left = DrawingView(this, leftState, rightState)
        left.setBackgroundColor(Color.LTGRAY)
        left.setLayoutParams(vglp)
        wm.addView(left, wmlp)

        wmlp.setTitle("Right -- " + getPackageName())
        wmlp.gravity = Gravity.CENTER_VERTICAL or Gravity.END
        right = DrawingView(this, rightState, leftState)
        right.setBackgroundColor(Color.LTGRAY)
        right.setLayoutParams(vglp)
        wm.addView(right, wmlp)
    }
}
