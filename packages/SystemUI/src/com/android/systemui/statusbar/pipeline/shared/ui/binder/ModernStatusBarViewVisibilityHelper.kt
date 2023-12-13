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
 */

package com.android.systemui.statusbar.pipeline.shared.ui.binder

import android.view.View
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder
import com.android.systemui.statusbar.pipeline.wifi.ui.binder.WifiViewBinder

/**
 * The helper to update the groupView and dotView visibility based on given visibility state, only
 * used for [MobileIconBinder] and [WifiViewBinder] now.
 */
class ModernStatusBarViewVisibilityHelper {
    companion object {

        fun setVisibilityState(
            @StatusBarIconView.VisibleState state: Int,
            groupView: View,
            dotView: View,
        ) {
            when (state) {
                StatusBarIconView.STATE_ICON -> {
                    groupView.visibility = View.VISIBLE
                    dotView.visibility = View.GONE
                }
                StatusBarIconView.STATE_DOT -> {
                    groupView.visibility = View.INVISIBLE
                    dotView.visibility = View.VISIBLE
                }
                StatusBarIconView.STATE_HIDDEN -> {
                    groupView.visibility = View.INVISIBLE
                    dotView.visibility = View.INVISIBLE
                }
            }
        }
    }
}
