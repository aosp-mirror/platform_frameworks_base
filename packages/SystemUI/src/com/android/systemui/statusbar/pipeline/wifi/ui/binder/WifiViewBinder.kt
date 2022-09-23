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

package com.android.systemui.statusbar.pipeline.wifi.ui.binder

import android.content.res.ColorStateList
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.R
import com.android.systemui.common.ui.binder.IconViewBinder
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.statusbar.StatusBarIconView
import com.android.systemui.statusbar.StatusBarIconView.STATE_DOT
import com.android.systemui.statusbar.StatusBarIconView.STATE_HIDDEN
import com.android.systemui.statusbar.StatusBarIconView.STATE_ICON
import com.android.systemui.statusbar.phone.StatusBarLocation
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.LocationBasedWifiViewModel
import com.android.systemui.statusbar.pipeline.wifi.ui.viewmodel.WifiViewModel
import kotlinx.coroutines.InternalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Binds a wifi icon in the status bar to its view-model.
 *
 * To use this properly, users should maintain a one-to-one relationship between the [View] and the
 * view-binding, binding each view only once. It is okay and expected for the same instance of the
 * view-model to be reused for multiple view/view-binder bindings.
 */
@OptIn(InternalCoroutinesApi::class)
object WifiViewBinder {

    /**
     * Defines interface for an object that acts as the binding between the view and its view-model.
     *
     * Users of the [WifiViewBinder] class should use this to control the binder after it is bound.
     */
    interface Binding {
        /** Returns true if the wifi icon should be visible and false otherwise. */
        fun getShouldIconBeVisible(): Boolean

        /** Notifies that the visibility state has changed. */
        fun onVisibilityStateChanged(@StatusBarIconView.VisibleState state: Int)
    }

    /**
     * Binds the view to the appropriate view-model based on the given location. The view will
     * continue to be updated following updates from the view-model.
     */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        wifiViewModel: WifiViewModel,
        location: StatusBarLocation,
    ): Binding {
        return when (location) {
            StatusBarLocation.HOME -> bind(view, wifiViewModel.home)
            StatusBarLocation.KEYGUARD -> bind(view, wifiViewModel.keyguard)
            StatusBarLocation.QS -> bind(view, wifiViewModel.qs)
        }
    }

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    private fun bind(
        view: ViewGroup,
        viewModel: LocationBasedWifiViewModel,
    ): Binding {
        val groupView = view.requireViewById<ViewGroup>(R.id.wifi_group)
        val iconView = view.requireViewById<ImageView>(R.id.wifi_signal)
        val dotView = view.requireViewById<StatusBarIconView>(R.id.status_bar_dot)
        val activityInView = view.requireViewById<ImageView>(R.id.wifi_in)
        val activityOutView = view.requireViewById<ImageView>(R.id.wifi_out)
        val activityContainerView = view.requireViewById<View>(R.id.inout_container)

        view.isVisible = true
        iconView.isVisible = true

        // TODO(b/238425913): We should log this visibility state.
        @StatusBarIconView.VisibleState
        val visibilityState: MutableStateFlow<Int> = MutableStateFlow(STATE_HIDDEN)

        view.repeatWhenAttached {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    visibilityState.collect { visibilityState ->
                        groupView.isVisible = visibilityState == STATE_ICON
                        dotView.isVisible = visibilityState == STATE_DOT
                    }
                }

                launch {
                    viewModel.wifiIcon.collect { wifiIcon ->
                        view.isVisible = wifiIcon != null
                        wifiIcon?.let { IconViewBinder.bind(wifiIcon, iconView) }
                    }
                }

                launch {
                    viewModel.tint.collect { tint ->
                        val tintList = ColorStateList.valueOf(tint)
                        iconView.imageTintList = tintList
                        activityInView.imageTintList = tintList
                        activityOutView.imageTintList = tintList
                        dotView.setDecorColor(tint)
                    }
                }

                launch {
                    viewModel.isActivityInViewVisible.distinctUntilChanged().collect { visible ->
                        activityInView.isVisible = visible
                    }
                }

                launch {
                    viewModel.isActivityOutViewVisible.distinctUntilChanged().collect { visible ->
                        activityOutView.isVisible = visible
                    }
                }

                launch {
                    viewModel.isActivityContainerVisible.distinctUntilChanged().collect { visible ->
                        activityContainerView.isVisible = visible
                    }
                }
            }
        }

        return object : Binding {
            override fun getShouldIconBeVisible(): Boolean {
                return viewModel.wifiIcon.value != null
            }

            override fun onVisibilityStateChanged(@StatusBarIconView.VisibleState state: Int) {
                visibilityState.value = state
            }
        }
    }
}
