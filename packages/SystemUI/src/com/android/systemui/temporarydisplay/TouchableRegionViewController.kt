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

package com.android.systemui.temporarydisplay

import android.graphics.Rect
import android.view.View
import android.view.ViewTreeObserver
import com.android.systemui.util.ViewController

/**
 * A view controller that will notify the [ViewTreeObserver] about the touchable region for this
 * view. This will be used by WindowManager to decide which touch events go to the view and which
 * pass through to the window below.
 *
 * @param touchableRegionSetter a function that, given the view and an out rect, fills the rect with
 * the touchable region of this view.
 */
class TouchableRegionViewController(
    view: View,
    touchableRegionSetter: (View, Rect) -> Unit,
) : ViewController<View>(view) {

    private val tempRect = Rect()

    private val internalInsetsListener =
        ViewTreeObserver.OnComputeInternalInsetsListener { inoutInfo ->
            inoutInfo.setTouchableInsets(
                ViewTreeObserver.InternalInsetsInfo.TOUCHABLE_INSETS_REGION
            )

            tempRect.setEmpty()
            touchableRegionSetter.invoke(mView, tempRect)
            inoutInfo.touchableRegion.set(tempRect)
        }

    public override fun onViewAttached() {
        mView.viewTreeObserver.addOnComputeInternalInsetsListener(internalInsetsListener)
    }

    public override fun onViewDetached() {
        mView.viewTreeObserver.removeOnComputeInternalInsetsListener(internalInsetsListener)
    }
}
