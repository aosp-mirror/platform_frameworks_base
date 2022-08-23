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

package com.android.systemui.statusbar.pipeline.wifi.ui.view

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.LayoutInflater
import com.android.systemui.R
import com.android.systemui.statusbar.BaseStatusBarWifiView
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.pipeline.wifi.ui.binder.WifiViewBinder
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel

/**
 * A new and more modern implementation of [com.android.systemui.statusbar.StatusBarWifiView] that
 * is updated by [WifiViewBinder].
 */
class ModernStatusBarWifiView(
    context: Context,
    attrs: AttributeSet?
) : BaseStatusBarWifiView(context, attrs) {

    private lateinit var slot: String

    override fun onDarkChanged(areas: ArrayList<Rect>?, darkIntensity: Float, tint: Int) {
        // TODO(b/238425913)
    }

    override fun getSlot() = slot

    override fun setStaticDrawableColor(color: Int) {
        // TODO(b/238425913)
    }

    override fun setDecorColor(color: Int) {
        // TODO(b/238425913)
    }

    override fun setVisibleState(state: Int, animate: Boolean) {
        // TODO(b/238425913)
    }

    override fun getVisibleState(): Int {
        // TODO(b/238425913)
        return STATE_ICON
    }

    override fun isIconVisible(): Boolean {
        // TODO(b/238425913)
        return true
    }

    /** Set the slot name for this view. */
    private fun setSlot(slotName: String) {
        this.slot = slotName
    }

    companion object {
        /**
         * Inflates a new instance of [ModernStatusBarWifiView], binds it to [viewModel], and
         * returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            slot: String,
            viewModel: WifiViewModel,
        ): ModernStatusBarWifiView {
            return (
                LayoutInflater.from(context).inflate(R.layout.new_status_bar_wifi_group, null)
                    as ModernStatusBarWifiView
                ).also {
                    it.setSlot(slot)
                    WifiViewBinder.bind(it, viewModel)
                }
        }
    }
}
