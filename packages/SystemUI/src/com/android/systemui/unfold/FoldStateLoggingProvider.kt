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

package com.android.systemui.unfold

import android.annotation.IntDef
import com.android.systemui.statusbar.policy.CallbackController
import com.android.systemui.unfold.FoldStateLoggingProvider.FoldStateLoggingListener
import com.android.systemui.unfold.FoldStateLoggingProvider.LoggedFoldedStates

/** Reports device fold states for logging purposes. */
// TODO(b/198305865): Log state changes.
interface FoldStateLoggingProvider : CallbackController<FoldStateLoggingListener> {

    fun init()
    fun uninit()

    interface FoldStateLoggingListener {
        fun onFoldUpdate(foldStateUpdate: FoldStateChange)
    }

    @IntDef(prefix = ["LOGGED_FOLD_STATE_"], value = [FULLY_OPENED, FULLY_CLOSED, HALF_OPENED])
    @Retention(AnnotationRetention.SOURCE)
    annotation class LoggedFoldedStates
}

data class FoldStateChange(
    @LoggedFoldedStates val previous: Int,
    @LoggedFoldedStates val current: Int,
    val dtMillis: Long
)

const val FULLY_OPENED = 1
const val FULLY_CLOSED = 2
const val HALF_OPENED = 3
