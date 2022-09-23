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
import android.view.Gravity
import android.view.LayoutInflater
import com.android.systemui.R
import com.android.systemui.statusbar.BaseStatusBarWifiView
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.STATE_DOT
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.phone.StatusBarLocation
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
    private lateinit var binding: WifiViewBinder.Binding

    @StatusBarIconView.VisibleState
    private var iconVisibleState: Int = STATE_HIDDEN
        set(value) {
            if (field == value) {
                return
            }
            field = value
            binding.onVisibilityStateChanged(value)
        }

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

    override fun setVisibleState(@StatusBarIconView.VisibleState state: Int, animate: Boolean) {
        iconVisibleState = state
    }

    @StatusBarIconView.VisibleState
    override fun getVisibleState(): Int {
        return iconVisibleState
    }

    override fun isIconVisible(): Boolean {
        return binding.getShouldIconBeVisible()
    }

    private fun initView(
        slotName: String,
        wifiViewModel: WifiViewModel,
        location: StatusBarLocation,
    ) {
        slot = slotName
        initDotView()
        binding = WifiViewBinder.bind(this, wifiViewModel, location)
    }

    // Mostly duplicated from [com.android.systemui.statusbar.StatusBarWifiView].
    private fun initDotView() {
        // TODO(b/238425913): Could we just have this dot view be part of
        //   R.layout.new_status_bar_wifi_group with a dot drawable so we don't need to inflate it
        //   manually? Would that not work with animations?
        val dotView = StatusBarIconView(mContext, slot, null).also {
            it.id = R.id.status_bar_dot
            // Hard-code this view to always be in the DOT state so that whenever it's visible it
            // will show a dot
            it.visibleState = STATE_DOT
        }

        val width = mContext.resources.getDimensionPixelSize(R.dimen.status_bar_icon_size)
        val lp = LayoutParams(width, width)
        lp.gravity = Gravity.CENTER_VERTICAL or Gravity.START
        addView(dotView, lp)
    }

    companion object {
        /**
         * Inflates a new instance of [ModernStatusBarWifiView], binds it to a view model, and
         * returns it.
         */
        @JvmStatic
        fun constructAndBind(
            context: Context,
            slot: String,
            wifiViewModel: WifiViewModel,
            location: StatusBarLocation,
        ): ModernStatusBarWifiView {
            return (
                LayoutInflater.from(context).inflate(R.layout.new_status_bar_wifi_group, null)
                    as ModernStatusBarWifiView
                ).also {
                    it.initView(slot, wifiViewModel, location)
                }
        }
    }
}
