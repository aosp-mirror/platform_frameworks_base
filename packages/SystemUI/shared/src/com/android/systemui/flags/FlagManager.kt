/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.flags

import android.content.Context
import android.content.Intent
import androidx.concurrent.futures.CallbackToFutureAdapter
import com.google.common.util.concurrent.ListenableFuture

class FlagManager constructor(val context: Context) {
    companion object {
        const val RECEIVING_PACKAGE = "com.android.systemui"
        const val ACTION_SET_FLAG = "com.android.systemui.action.SET_FLAG"
        const val FLAGS_PERMISSION = "com.android.systemui.permission.FLAGS"
        const val FIELD_ID = "id"
        const val FIELD_VALUE = "value"
    }

    fun getFlagsFuture(): ListenableFuture<Collection<Flag<*>>> {
        val knownFlagMap = Flags.collectFlags()
        // Possible todo in the future: query systemui async to actually get the known flag ids.
        return CallbackToFutureAdapter.getFuture(
            CallbackToFutureAdapter.Resolver {
                completer: CallbackToFutureAdapter.Completer<Collection<Flag<*>>> ->
                completer.set(knownFlagMap.values as Collection<Flag<*>>)
                "Retrieving Flags"
            })
    }

    fun setFlagValue(id: Int, enabled: Boolean) {
        val intent = createIntent(id)
        intent.putExtra(FIELD_VALUE, enabled)

        context.sendBroadcast(intent)
    }

    fun eraseFlag(id: Int) {
        val intent = createIntent(id)

        context.sendBroadcast(intent)
    }

    private fun createIntent(id: Int): Intent {
        val intent = Intent(ACTION_SET_FLAG)
        intent.setPackage(RECEIVING_PACKAGE)
        intent.putExtra(FIELD_ID, id)

        return intent
    }
}