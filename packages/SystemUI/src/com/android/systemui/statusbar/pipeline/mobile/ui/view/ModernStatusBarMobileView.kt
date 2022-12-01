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

package com.android.systemui.statusbar.pipeline.mobile.ui.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import com.android.systemui.R
import com.android.systemui.statusbar.BaseStatusBarFrameLayout
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.pipeline.mobile.ui.binder.MobileIconBinder
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.MobileIconViewModel
import java.util.ArrayList

class ModernStatusBarMobileView(
    context: Context,
    attrs: AttributeSet?,
) : BaseStatusBarFrameLayout(context, attrs) {

    var subId: Int = -1

    private lateinit var slot: String
    override fun getSlot() = slot

    override fun onDarkChanged(areas: ArrayList<Rect>?, darkIntensity: Float, tint: Int) {
        // TODO
    }

    override fun setStaticDrawableColor(color: Int) {
        // TODO
    }

    override fun setDecorColor(color: Int) {
        // TODO
    }

    override fun setVisibleState(state: Int, animate: Boolean) {
        // TODO
    }

    override fun getVisibleState(): Int {
        return STATE_ICON
    }

    override fun isIconVisible(): Boolean {
        return true
    }

    companion object {

        /**
         * Inflates a new instance of [ModernStatusBarMobileView], binds it to [viewModel], and
         * returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            slot: String,
            viewModel: MobileIconViewModel,
        ): ModernStatusBarMobileView {
            return (LayoutInflater.from(context)
                    .inflate(R.layout.status_bar_mobile_signal_group_new, null)
                    as ModernStatusBarMobileView)
                .also {
                    it.slot = slot
                    it.subId = viewModel.subscriptionId
                    MobileIconBinder.bind(it, viewModel)
                }
        }
    }
}
