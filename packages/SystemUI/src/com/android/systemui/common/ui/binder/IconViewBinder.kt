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

package com.android.systemui.common.ui.binder

import android.view.View
import android.widget.ImageView
import com.android.systemui.common.shared.model.Icon

object IconViewBinder {
    fun bind(
        icon: Icon,
        view: ImageView,
    ) {
        ContentDescriptionViewBinder.bind(icon.contentDescription, view)
        when (icon) {
            is Icon.Loaded -> view.setImageDrawable(icon.drawable)
            is Icon.Resource -> view.setImageResource(icon.res)
        }
    }

    fun bindNullable(icon: Icon?, view: ImageView) {
        if (icon != null) {
            view.visibility = View.VISIBLE
            bind(icon, view)
        } else {
            view.visibility = View.GONE
        }
    }
}
