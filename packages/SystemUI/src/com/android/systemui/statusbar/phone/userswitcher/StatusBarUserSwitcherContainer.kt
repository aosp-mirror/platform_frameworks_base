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

package com.android.systemui.statusbar.phone.userswitcher

import android.content.Context
import android.util.AttributeSet
import android.widget.ImageView
import android.widget.TextView
import com.android.systemui.res.R
import com.android.systemui.animation.view.LaunchableLinearLayout

class StatusBarUserSwitcherContainer(
    context: Context?,
    attrs: AttributeSet?
) : LaunchableLinearLayout(context, attrs) {
    lateinit var text: TextView
        private set
    lateinit var avatar: ImageView
        private set

    override fun onFinishInflate() {
        super.onFinishInflate()
        text = requireViewById(R.id.current_user_name)
        avatar = requireViewById(R.id.current_user_avatar)
    }
}