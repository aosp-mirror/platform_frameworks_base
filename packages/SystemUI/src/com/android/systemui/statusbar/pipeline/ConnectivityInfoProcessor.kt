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

import android.content.Context
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject

/**
 * A processor that transforms raw connectivity information that we get from callbacks and turns it
 * into a list of displayable connectivity information.
 *
 * This will be used for the new status bar pipeline to calculate the list of icons that should be
 * displayed in the RHS of the status bar.
 */
@SysUISingleton
class ConnectivityInfoProcessor @Inject constructor(
        context: Context,
        private val statusBarPipelineFlags: StatusBarPipelineFlags,
) : CoreStartable(context) {
    override fun start() {
        if (statusBarPipelineFlags.isNewPipelineEnabled()) {
            init()
        }
    }

    /** Initializes this processor and everything it depends on. */
    private fun init() {
        // TODO(b/238425913): Register all the connectivity callbacks here.
    }
}
