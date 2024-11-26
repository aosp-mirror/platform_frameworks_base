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

package com.android.systemui.volume.dialog

import android.app.Dialog
import android.content.Context
import android.graphics.PixelFormat
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.android.app.tracing.coroutines.coroutineScopeTraced
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.lifecycle.repeatWhenAttached
import com.android.systemui.res.R
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.dagger.VolumeDialogComponent
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import javax.inject.Inject
import kotlinx.coroutines.awaitCancellation

class VolumeDialog
@Inject
constructor(
    @Application context: Context,
    private val componentFactory: VolumeDialogComponent.Factory,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
) : Dialog(context, R.style.Theme_SystemUI_Dialog_Volume) {

    init {
        with(window!!) {
            addFlags(
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
            )
            addPrivateFlags(WindowManager.LayoutParams.PRIVATE_FLAG_TRUSTED_OVERLAY)

            setType(WindowManager.LayoutParams.TYPE_VOLUME_OVERLAY)
            setWindowAnimations(-1)
            setFormat(PixelFormat.TRANSLUCENT)

            attributes =
                attributes.apply {
                    title = "VolumeDialog" // Not the same as Window#setTitle
                }
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        }
        setCanceledOnTouchOutside(true)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.volume_dialog)
        requireViewById<View>(R.id.volume_dialog_root).repeatWhenAttached {
            coroutineScopeTraced("[Volume]dialog") {
                val component = componentFactory.create(this)
                with(component.volumeDialogViewBinder()) { bind(this@VolumeDialog) }

                awaitCancellation()
            }
        }
    }

    /**
     * NOTE: This will be called with ACTION_OUTSIDE MotionEvents for touches that occur outside of
     * the touchable region of the volume dialog (as returned by [.onComputeInternalInsets]) even if
     * those touches occurred within the bounds of the volume dialog.
     */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (isShowing) {
            if (event.action == MotionEvent.ACTION_OUTSIDE) {
                visibilityInteractor.dismissDialog(Events.DISMISS_REASON_TOUCH_OUTSIDE)
                return true
            }
        }
        return false
    }
}
