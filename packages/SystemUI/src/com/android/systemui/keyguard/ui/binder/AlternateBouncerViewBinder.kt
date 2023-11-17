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

package com.android.systemui.keyguard.ui.binder

import android.view.View
import android.view.ViewGroup
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.android.systemui.deviceentry.shared.DeviceEntryUdfpsRefactor
import com.android.systemui.keyguard.ui.viewmodel.AlternateBouncerViewModel
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.scrim.ScrimView
import com.android.systemui.statusbar.NotificationShadeWindowController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch

/**
 * Binds the alternate bouncer view to its view-model.
 *
 * To use this properly, users should maintain a one-to-one relationship between the [View] and the
 * view-binding, binding each view only once. It is okay and expected for the same instance of the
 * view-model to be reused for multiple view/view-binder bindings.
 */
@ExperimentalCoroutinesApi
object AlternateBouncerViewBinder {

    /** Binds the view to the view-model, continuing to update the former based on the latter. */
    @JvmStatic
    fun bind(
        view: ViewGroup,
        viewModel: AlternateBouncerViewModel,
        scope: CoroutineScope,
        notificationShadeWindowController: NotificationShadeWindowController,
    ) {
        DeviceEntryUdfpsRefactor.isUnexpectedlyInLegacyMode()
        scope.launch {
            // forcePluginOpen is necessary to show over occluded apps.
            // This cannot be tied to the view's lifecycle because setting this allows the view
            // to be started in the first place.
            viewModel.forcePluginOpen.collect {
                notificationShadeWindowController.setForcePluginOpen(it, this)
            }
        }

        val scrim = view.requireViewById(R.id.alternate_bouncer_scrim) as ScrimView
        view.repeatWhenAttached { alternateBouncerViewContainer ->
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                scrim.viewAlpha = 0f

                launch {
                    viewModel.onClickListener.collect {
                        // TODO (b/287599719): Support swiping to dismiss altBouncer
                        alternateBouncerViewContainer.setOnClickListener(it)
                    }
                }

                launch {
                    viewModel.scrimAlpha.collect {
                        alternateBouncerViewContainer.visibility =
                            if (it < .1f) View.INVISIBLE else View.VISIBLE
                        scrim.viewAlpha = it
                    }
                }

                launch { viewModel.scrimColor.collect { scrim.tint = it } }
            }
        }
    }
}
