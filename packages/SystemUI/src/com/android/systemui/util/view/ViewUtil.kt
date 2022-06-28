package com.android.systemui.util.view

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
}
