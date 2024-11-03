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
import android.os.Bundle
import android.view.MotionEvent
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.volume.Events
import com.android.systemui.volume.dialog.domain.interactor.VolumeDialogVisibilityInteractor
import com.android.systemui.volume.dialog.ui.binder.VolumeDialogViewBinder
import javax.inject.Inject

class VolumeDialog
@Inject
constructor(
    @Application context: Context,
    private val viewBinder: VolumeDialogViewBinder,
    private val visibilityInteractor: VolumeDialogVisibilityInteractor,
) : Dialog(context) {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewBinder.bind(this)
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
