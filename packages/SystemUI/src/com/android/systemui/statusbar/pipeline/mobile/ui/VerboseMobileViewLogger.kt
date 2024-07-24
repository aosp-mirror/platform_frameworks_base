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
import com.android.systemui.common.shared.model.Icon
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.LogLevel
import com.android.systemui.statusbar.pipeline.dagger.VerboseMobileViewLog
import com.android.systemui.statusbar.pipeline.mobile.domain.model.SignalIconModel
import com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger.Companion.getIdForLogging
import javax.inject.Inject

/**
 * Logs for **verbose** changes with the new mobile views.
 *
 * This is a hopefully temporary log until we resolve some open bugs (b/267236367, b/269565345,
 * b/270300839).
 */
@SysUISingleton
class VerboseMobileViewLogger
@Inject
constructor(
    @VerboseMobileViewLog private val buffer: LogBuffer,
) {
    fun logBinderReceivedVisibility(parentView: View, subId: Int, visibility: Boolean) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = parentView.getIdForLogging()
                int1 = subId
                bool1 = visibility
            },
            { "Binder[subId=$int1, viewId=$str1] received visibility: $bool1" },
        )
    }

    fun logBinderReceivedSignalIcon(parentView: View, subId: Int, icon: SignalIconModel) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = parentView.getIdForLogging()
                int1 = subId
                int2 = icon.level
                bool1 = if (icon is SignalIconModel.Cellular) icon.showExclamationMark else false
            },
            {
                "Binder[subId=$int1, viewId=$str1] received new signal icon: " +
                    "level=$int2 showExclamation=$bool1"
            },
        )
    }

    fun logBinderReceivedNetworkTypeIcon(parentView: View, subId: Int, icon: Icon.Resource?) {
        buffer.log(
            TAG,
            LogLevel.VERBOSE,
            {
                str1 = parentView.getIdForLogging()
                int1 = subId
                bool1 = icon != null
                int2 = icon?.res ?: -1
            },
            {
                "Binder[subId=$int1, viewId=$str1] received new network type icon: " +
                    if (bool1) "resId=$int2" else "null"
            },
        )
    }
}

private const val TAG = "VerboseMobileViewLogger"
