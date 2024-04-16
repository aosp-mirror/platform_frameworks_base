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

package com.android.systemui.media.controls.ui.binder

import android.widget.ImageButton
import androidx.constraintlayout.widget.ConstraintSet
import com.android.systemui.res.R

object MediaControlViewBinder {

    fun setVisibleAndAlpha(set: ConstraintSet, resId: Int, visible: Boolean) {
        setVisibleAndAlpha(set, resId, visible, ConstraintSet.GONE)
    }

    private fun setVisibleAndAlpha(
        set: ConstraintSet,
        resId: Int,
        visible: Boolean,
        notVisibleValue: Int
    ) {
        set.setVisibility(resId, if (visible) ConstraintSet.VISIBLE else notVisibleValue)
        set.setAlpha(resId, if (visible) 1.0f else 0.0f)
    }

    fun updateSeekBarVisibility(constraintSet: ConstraintSet, isSeekBarEnabled: Boolean) {
        if (isSeekBarEnabled) {
            constraintSet.setVisibility(R.id.media_progress_bar, ConstraintSet.VISIBLE)
            constraintSet.setAlpha(R.id.media_progress_bar, 1.0f)
        } else {
            constraintSet.setVisibility(R.id.media_progress_bar, ConstraintSet.INVISIBLE)
            constraintSet.setAlpha(R.id.media_progress_bar, 0.0f)
        }
    }

    fun setSemanticButtonVisibleAndAlpha(
        button: ImageButton,
        expandedSet: ConstraintSet,
        collapsedSet: ConstraintSet,
        visible: Boolean,
        notVisibleValue: Int,
        showInCollapsed: Boolean
    ) {
        if (notVisibleValue == ConstraintSet.INVISIBLE) {
            // Since time views should appear instead of buttons.
            button.isFocusable = visible
            button.isClickable = visible
        }
        setVisibleAndAlpha(expandedSet, button.id, visible, notVisibleValue)
        setVisibleAndAlpha(collapsedSet, button.id, visible = visible && showInCollapsed)
    }
}
