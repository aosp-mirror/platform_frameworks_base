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

package com.android.systemui.communal.widgets

import android.appwidget.AppWidgetHostView
import android.content.Context
import android.graphics.Outline
import android.graphics.Rect
import android.view.View
import android.view.ViewOutlineProvider
import com.android.systemui.animation.LaunchableView
import com.android.systemui.animation.LaunchableViewDelegate

/** AppWidgetHostView that displays in communal hub with support for rounded corners. */
class CommunalAppWidgetHostView(context: Context) : AppWidgetHostView(context), LaunchableView {
    private val launchableViewDelegate =
        LaunchableViewDelegate(
            this,
            superSetVisibility = { super.setVisibility(it) },
        )

    // Mutable corner radius.
    var enforcedCornerRadius: Float

    // Mutable `Rect`. The size will be mutated when the widget is reapplied.
    var enforcedRectangle: Rect

    init {
        enforcedCornerRadius = RoundedCornerEnforcement.computeEnforcedRadius(context)
        enforcedRectangle = Rect()
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        super.onLayout(changed, l, t, r, b)

        enforceRoundedCorners()
    }

    private val cornerRadiusEnforcementOutline: ViewOutlineProvider =
        object : ViewOutlineProvider() {
            override fun getOutline(view: View?, outline: Outline) {
                if (enforcedRectangle.isEmpty || enforcedCornerRadius <= 0) {
                    outline.setEmpty()
                } else {
                    outline.setRoundRect(enforcedRectangle, enforcedCornerRadius)
                }
            }
        }

    private fun enforceRoundedCorners() {
        if (enforcedCornerRadius <= 0) {
            resetRoundedCorners()
            return
        }
        val background: View? = RoundedCornerEnforcement.findBackground(this)
        if (background == null || RoundedCornerEnforcement.hasAppWidgetOptedOut(this, background)) {
            resetRoundedCorners()
            return
        }
        RoundedCornerEnforcement.computeRoundedRectangle(this, background, enforcedRectangle)
        outlineProvider = cornerRadiusEnforcementOutline
        clipToOutline = true
        invalidateOutline()
    }

    private fun resetRoundedCorners() {
        outlineProvider = ViewOutlineProvider.BACKGROUND
        clipToOutline = false
    }

    override fun setShouldBlockVisibilityChanges(block: Boolean) =
        launchableViewDelegate.setShouldBlockVisibilityChanges(block)

    override fun setVisibility(visibility: Int) = launchableViewDelegate.setVisibility(visibility)
}
