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
 *
 */

package com.android.systemui.keyguard.ui.view

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import com.android.keyguard.LockIconView
import com.android.systemui.R

/** Provides a container for all keyguard ui content. */
class KeyguardRootView(
    context: Context,
    private val attrs: AttributeSet?,
) :
    FrameLayout(
        context,
        attrs,
    ) {

    init {
        addIndicationTextArea()
        addLockIconView()
    }

    private fun addIndicationTextArea() {
        val view = KeyguardIndicationArea(context, attrs)
        addView(
            view,
            FrameLayout.LayoutParams(
                    MATCH_PARENT,
                    WRAP_CONTENT,
                )
                .apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = R.dimen.keyguard_indication_margin_bottom.dp()
                }
        )
    }

    private fun addLockIconView() {
        val view = LockIconView(context, attrs).apply { id = R.id.lock_icon_view }
        addView(
            view,
            LayoutParams(
                WRAP_CONTENT,
                WRAP_CONTENT,
            )
        )
    }

    private fun Int.dp(): Int {
        return context.resources.getDimensionPixelSize(this)
    }
}
