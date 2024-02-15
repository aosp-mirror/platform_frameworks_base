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

package com.android.systemui.statusbar.pipeline.mobile.ui

import android.view.View
import com.android.systemui.Dumpable
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dump.DumpManager
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.pipeline.dagger.MobileViewLog
import com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel
import java.io.PrintWriter
import javax.inject.Inject

/** Logs for changes with the new mobile views. */
@SysUISingleton
class MobileViewLogger
@Inject
constructor(
    @MobileViewLog private val buffer: LogBuffer,
    dumpManager: DumpManager,
) : Dumpable {
    init {
        dumpManager.registerNormalDumpable(this)
    }

    private val collectionStatuses = mutableMapOf<String, Boolean>()

    fun logUiAdapterSubIdsSentToIconController(subs: List<Int>) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            { str1 = subs.toString() },
            { "Sub IDs in MobileUiAdapter being sent to icon controller: $str1" },
        )
    }

    fun logNewViewBinding(view: View, viewModel: LocationBasedMobileViewModel) {
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = view.getIdForLogging()
                str2 = viewModel.getIdForLogging()
                str3 = viewModel.location.name
            },
            { "New view binding. viewId=$str1, viewModelId=$str2, viewModelLocation=$str3" },
        )
    }

    fun logCollectionStarted(view: View, viewModel: LocationBasedMobileViewModel) {
        collectionStatuses[view.getIdForLogging()] = true
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = view.getIdForLogging()
                str2 = viewModel.getIdForLogging()
                str3 = viewModel.location.name
            },
            { "Collection started. viewId=$str1, viewModelId=$str2, viewModelLocation=$str3" },
        )
    }

    fun logCollectionStopped(view: View, viewModel: LocationBasedMobileViewModel) {
        collectionStatuses[view.getIdForLogging()] = false
        buffer.log(
            TAG,
            LogLevel.INFO,
            {
                str1 = view.getIdForLogging()
                str2 = viewModel.getIdForLogging()
                str3 = viewModel.location.name
            },
            { "Collection stopped. viewId=$str1, viewModelId=$str2, viewModelLocation=$str3" },
        )
    }

    override fun dump(pw: PrintWriter, args: Array<out String>) {
        pw.println("Collection statuses per view:---")
        collectionStatuses.forEach { viewId, isCollecting ->
            pw.println("viewId=$viewId, isCollecting=$isCollecting")
        }
    }

    companion object {
        fun Any.getIdForLogging(): String {
            // The identityHashCode is guaranteed to be constant for the lifetime of the object.
            return Integer.toHexString(System.identityHashCode(this))
        }
    }
}

private const val TAG = "MobileViewLogger"
