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

package com.android.systemui.statusbar.pipeline.wifi.shared

import android.content.Context
import com.android.systemui.Dumpable
import com.android.systemui.R
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.statusbar.pipeline.shared.ConnectivityPipelineLogger.Companion.SB_LOGGING_TAG
import java.io.PrintWriter
import javax.inject.Inject

/**
 * An object storing constants that we use for calculating the wifi icon. Stored in a class for
 * logging purposes.
 */
@SysUISingleton
class WifiConstants @Inject constructor(
        context: Context,
        dumpManager: DumpManager,
) : Dumpable {
    init {
        dumpManager.registerDumpable("$SB_LOGGING_TAG:WifiConstants", this)
    }

    /** True if we should show the activityIn/activityOut icons and false otherwise. */
    val shouldShowActivityConfig = context.resources.getBoolean(R.bool.config_showActivity)

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.apply {
            println("shouldShowActivityConfig=$shouldShowActivityConfig")
        }
    }
}
