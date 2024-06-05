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

import android.annotation.SuppressLint
import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.ViewGroup
import android.widget.ImageView
import com.android.settingslib.flags.Flags.newStatusBarIcons
import com.android.systemui.res.R
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.pipeline.shared.ui.view.ModernStatusBarView
import com.android.systemui.statusbar.pipeline.wifi.ui.binder.WifiViewBinder
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel

/**
 * A new and more modern implementation of [com.android.systemui.statusbar.StatusBarWifiView] that
 * is updated by [WifiViewBinder].
 */
class ModernStatusBarWifiView(
    context: Context,
    attrs: AttributeSet?,
) : ModernStatusBarView(context, attrs) {

    override fun toString(): String {
        return "ModernStatusBarWifiView(" +
            "slot='$slot', " +
            "isCollecting=${binding.isCollecting()}, " +
            "visibleState=${StatusBarIconView.getVisibleStateString(visibleState)}); " +
            "viewString=${super.toString()}"
    }

    companion object {
        /**
         * Inflates a new instance of [ModernStatusBarWifiView], binds it to a view model, and
         * returns it.
         */
        @SuppressLint("InflateParams")
        @JvmStatic
        fun constructAndBind(
            context: Context,
            slot: String,
            wifiViewModel: LocationBasedWifiViewModel,
        ): ModernStatusBarWifiView {
            return (LayoutInflater.from(context).inflate(R.layout.new_status_bar_wifi_group, null)
                    as ModernStatusBarWifiView)
                .also {
                    // Flag-specific configuration
                    if (newStatusBarIcons()) {
                        // The newer asset does not embed whitespace around it, and is therefore
                        // rectangular. Use wrap_content for the width in this case
                        val iconView = it.requireViewById<ImageView>(R.id.wifi_signal)
                        val lp = iconView.layoutParams
                        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT
                    }

                    it.initView(slot) { WifiViewBinder.bind(it, wifiViewModel) }
                }
        }
    }
}
