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

package com.android.systemui.statusbar.phone

import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow

/** Whether dialogs are requesting for affordances to be hidden or not. */
val SystemUIDialogManager.hideAffordancesRequest: Flow<Boolean>
    get() = conflatedCallbackFlow {
        val callback =
            SystemUIDialogManager.Listener { hideAffordance ->
                trySendWithFailureLogging(hideAffordance, "dialogHideAffordancesRequest")
            }
        registerListener(callback)
        trySendWithFailureLogging(shouldHideAffordance(), "dialogHideAffordancesRequestInitial")
        awaitClose { unregisterListener(callback) }
    }
