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

package com.android.systemui.statusbar.notification.shelf

import android.content.Context
import android.util.AttributeSet
import android.view.View
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.statusbar.notification.shared.NotificationMinimalism
import com.android.systemui.statusbar.phone.NotificationIconContainer
import kotlin.math.max

/** The NotificationIconContainer for the NotificationShelf. */
class NotificationShelfIconContainer
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) :
    NotificationIconContainer(context, attrs) {

    /** Whether the notification shelf is aligned to end. */
    var alignToEnd = false

    /**
     * @return The left boundary (not the RTL compatible start) of the area that icons can be added.
     */
    @VisibleForTesting
    public override fun getLeftBound(): Float {
        if (!NotificationMinimalism.isEnabled) {
            return super.getLeftBound()
        }

        if (isAlignedToRight) {
            return (max(width - actualWidth, 0) + actualPaddingStart)
        }
        return actualPaddingStart
    }

    /**
     * @return The right boundary (not the RTL compatible end) of the area that icons can be added.
     */
    @VisibleForTesting
    public override fun getRightBound(): Float {
        if (!NotificationMinimalism.isEnabled) {
            return super.getRightBound()
        }

        if (isAlignedToRight) {
            return width - actualPaddingEnd
        }
        return actualWidth - actualPaddingEnd
    }

    /**
     * For RTL, the icons' x positions should be mirrored around the middle of the shelf so that the
     * icons are also added to the shelf from right to left. This function should only be called
     * when RTL.
     */
    override fun getRtlIconTranslationX(iconState: IconState, iconView: View): Float {
        if (!NotificationMinimalism.isEnabled) {
            return super.getRtlIconTranslationX(iconState, iconView)
        }

        if (!isLayoutRtl) {
            return iconState.xTranslation
        }

        if (isAlignedToRight) {
            return width * 2 - actualWidth - iconState.xTranslation - iconView.width
        }
        return actualWidth - iconState.xTranslation - iconView.width
    }

    @VisibleForTesting
    val isAlignedToRight: Boolean
        get() {
            if (!NotificationMinimalism.isEnabled) {
                return isLayoutRtl
            }
            return alignToEnd xor isLayoutRtl
        }
}
