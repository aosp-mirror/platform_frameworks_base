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

package com.android.systemui.statusbar.notification.row.ui.view

import android.annotation.DrawableRes
import android.content.Context
import android.graphics.BlendMode
import android.util.AttributeSet
import com.android.internal.widget.EmphasizedNotificationButton

class TimerButtonView
@JvmOverloads
constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
    defStyleRes: Int = 0,
) : EmphasizedNotificationButton(context, attrs, defStyleAttr, defStyleRes) {

    private val Int.dp: Int
        get() = (this * context.resources.displayMetrics.density).toInt()

    fun setIcon(@DrawableRes icon: Int) {
        val drawable = context.getDrawable(icon)

        drawable?.mutate()
        drawable?.setTintList(textColors)
        drawable?.setTintBlendMode(BlendMode.SRC_IN)
        drawable?.setBounds(0, 0, 24.dp, 24.dp)

        setCompoundDrawablesRelative(drawable, null, null, null)
    }
}
