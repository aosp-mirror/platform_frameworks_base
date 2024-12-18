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

package com.android.systemui.statusbar.notification.icon.ui.viewbinder

import com.android.app.tracing.traceSection
import com.android.internal.util.ContrastColorUtil
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.NO_COLOR
import com.android.systemui.statusbar.notification.NotificationUtils
import com.android.systemui.statusbar.notification.icon.ui.viewmodel.NotificationIconColors
import com.android.systemui.util.view.viewBoundsOnScreen
import kotlinx.coroutines.flow.Flow

object StatusBarIconViewBinder {

    // TODO(b/305739416): Once StatusBarIconView has its own Recommended Architecture stack, these
    //  methods can become private and we can have a single bind() method for SBIV and its
    //  view-model (which, at the time of this writing, does not yet exist).

    suspend fun bindColor(view: StatusBarIconView, color: Flow<Int>) {
        color.collectTracingEach("SBIV#bindColor") { color ->
            // Set the color for the icons
            view.staticDrawableColor = color
            // Set the color for the overflow dot
            view.setDecorColor(color)
        }
    }

    suspend fun bindTintAlpha(view: StatusBarIconView, tintAlpha: Flow<Float>) {
        tintAlpha.collectTracingEach("SBIV#bindTintAlpha") { amt -> view.setTintAlpha(amt) }
    }

    suspend fun bindAnimationsEnabled(view: StatusBarIconView, allowAnimation: Flow<Boolean>) {
        allowAnimation.collectTracingEach("SBIV#bindAnimationsEnabled", view::setAllowAnimation)
    }

    suspend fun bindIconColors(
        view: StatusBarIconView,
        iconColors: Flow<NotificationIconColors>,
        contrastColorUtil: ContrastColorUtil,
    ) {
        iconColors.collectTracingEach("SBIV#bindIconColors") { colors ->
            // Set the icon color
            val isPreL = java.lang.Boolean.TRUE == view.getTag(R.id.icon_is_pre_L)
            val isColorized = !isPreL || NotificationUtils.isGrayscale(view, contrastColorUtil)
            view.staticDrawableColor =
                if (isColorized) colors.staticDrawableColor(view.viewBoundsOnScreen()) else NO_COLOR
            // Set the color for the overflow dot
            view.setDecorColor(colors.tint)
        }
    }
}

private suspend inline fun <T> Flow<T>.collectTracingEach(
    tag: String,
    crossinline collector: (T) -> Unit,
) = collect { traceSection(tag) { collector(it) } }
