/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.systemui.volume.dialog.ui.binder

import android.app.Dialog
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.ColorDrawable
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import com.android.systemui.res.R
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialog
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogScope
import com.android.systemui.volume.dialog.ringer.ui.binder.VolumeDialogRingerViewBinder
import com.android.systemui.volume.dialog.settings.ui.binder.VolumeDialogSettingsButtonViewBinder
import com.android.systemui.volume.dialog.sliders.ui.VolumeDialogSlidersViewBinder
import com.android.systemui.volume.dialog.ui.viewmodel.VolumeDialogGravityViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/** Binds the Volume Dialog itself. */
@VolumeDialogScope
class VolumeDialogBinder
@Inject
constructor(
    @VolumeDialog private val coroutineScope: CoroutineScope,
    private val volumeDialogViewBinder: VolumeDialogViewBinder,
    private val slidersViewBinder: VolumeDialogSlidersViewBinder,
    private val volumeDialogRingerViewBinder: VolumeDialogRingerViewBinder,
    private val settingsButtonViewBinder: VolumeDialogSettingsButtonViewBinder,
    private val gravityViewModel: VolumeDialogGravityViewModel,
) {

    fun bind(dialog: Dialog) {
        with(dialog) {
            setupWindow(window!!)
            dialog.setContentView(R.layout.volume_dialog)
            dialog.setCanceledOnTouchOutside(true)

            with(dialog.requireViewById<View>(R.id.volume_dialog_container)) {
                volumeDialogRingerViewBinder.bind(this)
                slidersViewBinder.bind(this)
                settingsButtonViewBinder.bind(this)
                volumeDialogViewBinder.bind(dialog, this)
            }
        }
    }

    /** Configures [Window] for the [Dialog]. */
    private fun setupWindow(window: Window) =
        with(window) {
            clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)

            requestFeature(Window.FEATURE_NO_TITLE)
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)
            setFormat(PixelFormat.TRANSLUCENT)

            attributes =
                attributes.apply {
                    title = "VolumeDialog" // Not the same as Window#setTitle
                }
            setLayout(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)

            gravityViewModel.dialogGravity.onEach { window.setGravity(it) }.launchIn(coroutineScope)
        }
}
