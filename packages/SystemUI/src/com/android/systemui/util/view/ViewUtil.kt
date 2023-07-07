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

package com.android.systemui.util.view

import android.graphics.Rect
import android.view.View
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/**
 * A class with generic view utility methods.
 *
 * Doesn't use static methods so that it can be easily mocked out in tests.
 */
@SysUISingleton
class ViewUtil @Inject constructor() {
    /**
     * Returns true if the given (x, y) point (in screen coordinates) is within the status bar
     * view's range and false otherwise.
     */
    fun touchIsWithinView(view: View, x: Float, y: Float): Boolean {
        val left = view.locationOnScreen[0]
        val top = view.locationOnScreen[1]
        return left <= x &&
                x <= left + view.width &&
                top <= y &&
                y <= top + view.height
    }

    /**
     * Sets [outRect] to be the view's location within its window.
     */
    fun setRectToViewWindowLocation(view: View, outRect: Rect) {
        val locInWindow = IntArray(2)
        view.getLocationInWindow(locInWindow)

        val x = locInWindow[0]
        val y = locInWindow[1]

        outRect.set(
            x,
            y,
            x + view.width,
            y + view.height,
        )
    }
}
