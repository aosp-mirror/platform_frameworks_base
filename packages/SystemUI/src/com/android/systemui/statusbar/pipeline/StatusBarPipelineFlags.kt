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

package com.android.systemui.statusbar.pipeline

import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.FeatureFlags
import com.android.systemui.flags.Flags
import javax.inject.Inject

/** All flagging methods related to the new status bar pipeline (see b/238425913). */
@SysUISingleton
class StatusBarPipelineFlags @Inject constructor(private val featureFlags: FeatureFlags) {
    /**
     * Returns true if we should run the new pipeline backend.
     *
     * The new pipeline backend hooks up to all our external callbacks, logs those callback inputs,
     * and logs the output state.
     */
    fun isNewPipelineBackendEnabled(): Boolean =
        featureFlags.isEnabled(Flags.NEW_STATUS_BAR_PIPELINE_BACKEND)

    /**
     * Returns true if we should run the new pipeline frontend *and* backend.
     *
     * The new pipeline frontend will use the outputted state from the new backend and will make the
     * correct changes to the UI.
     */
    fun isNewPipelineFrontendEnabled(): Boolean =
        isNewPipelineBackendEnabled() &&
            featureFlags.isEnabled(Flags.NEW_STATUS_BAR_PIPELINE_FRONTEND)

    /**
     * Returns true if we should apply some coloring to icons that were rendered with the new
     * pipeline to help with debugging.
     */
    // For now, just always apply the debug coloring if we've enabled frontend rendering.
    fun useNewPipelineDebugColoring(): Boolean = isNewPipelineFrontendEnabled()
}
