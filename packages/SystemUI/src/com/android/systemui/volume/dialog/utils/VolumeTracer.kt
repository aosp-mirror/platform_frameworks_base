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

package com.android.systemui.volume.dialog.utils

import android.os.Trace
import com.android.systemui.volume.dialog.dagger.scope.VolumeDialogPluginScope
import com.android.systemui.volume.dialog.shared.model.VolumeDialogVisibilityModel
import javax.inject.Inject

/** Traces the async sections for the Volume Dialog. */
interface VolumeTracer {

    fun traceVisibilityStart(model: VolumeDialogVisibilityModel)

    fun traceVisibilityEnd(model: VolumeDialogVisibilityModel)
}

@VolumeDialogPluginScope
class VolumeTracerImpl @Inject constructor() : VolumeTracer {

    override fun traceVisibilityStart(model: VolumeDialogVisibilityModel) =
        with(model) { Trace.beginAsyncSection(methodName, tracingCookie) }

    override fun traceVisibilityEnd(model: VolumeDialogVisibilityModel) =
        with(model) { Trace.endAsyncSection(methodName, tracingCookie) }

    private val VolumeDialogVisibilityModel.tracingCookie
        get() = this.hashCode()

    private val VolumeDialogVisibilityModel.methodName
        get() =
            when (this) {
                is VolumeDialogVisibilityModel.Visible -> "VolumeDialog#show"
                is VolumeDialogVisibilityModel.Dismissed -> "VolumeDialog#dismiss"
                is VolumeDialogVisibilityModel.Invisible -> error("Invisible is unsupported")
            }
}
