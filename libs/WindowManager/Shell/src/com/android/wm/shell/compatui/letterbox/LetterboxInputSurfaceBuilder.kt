/*
 * Copyright 2025 The Android Open Source Project
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

package com.android.wm.shell.compatui.letterbox

import android.view.SurfaceControl
import android.view.SurfaceControl.Transaction
import com.android.wm.shell.common.transition.SurfaceBuilderSupplier
import com.android.wm.shell.dagger.WMSingleton
import javax.inject.Inject

/**
 * Component responsible for the actual creation of the Letterbox surfaces.
 */
@WMSingleton
class LetterboxInputSurfaceBuilder @Inject constructor(
    private val surfaceBuilderSupplier: SurfaceBuilderSupplier
) {

    companion object {
        /*
         * Letterbox spy surfaces need to stay above the activity layer which is 0.
         */
        // TODO(b/378673153): Consider adding this to [TaskConstants].
        @JvmStatic
        private val TASK_CHILD_LAYER_LETTERBOX_SPY = 1000
    }

    fun createInputSurface(
        tx: Transaction,
        parentLeash: SurfaceControl,
        surfaceName: String,
        callSite: String
    ) = surfaceBuilderSupplier.get()
        .setName(surfaceName)
        .setContainerLayer()
        .setParent(parentLeash)
        .setCallsite(callSite)
        .build().apply {
            tx.setLayer(this, TASK_CHILD_LAYER_LETTERBOX_SPY)
                .setTrustedOverlay(this, true)
                .show(this)
                .apply()
        }
}
