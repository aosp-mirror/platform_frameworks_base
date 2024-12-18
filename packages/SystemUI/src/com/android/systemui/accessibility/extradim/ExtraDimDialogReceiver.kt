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
package com.android.systemui.accessibility.extradim

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.android.server.display.feature.flags.Flags
import javax.inject.Inject

/**
 * BroadcastReceiver for handling [ExtraDimDialogDelegate] intent.
 *
 * This is not exported. Need to call from framework and use SYSTEM user to send the intent.
 */
class ExtraDimDialogReceiver
@Inject
constructor(
    private val extraDimDialogManager: ExtraDimDialogManager,
) : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (
            !Flags.evenDimmer() ||
                !context
                    .getResources()
                    .getBoolean(com.android.internal.R.bool.config_evenDimmerEnabled)
        ) {
            return
        }

        if (ACTION == intent.action) {
            extraDimDialogManager.dismissKeyguardIfNeededAndShowDialog()
        }
    }

    companion object {
        const val ACTION = "com.android.systemui.action.LAUNCH_REMOVE_EXTRA_DIM_DIALOG"
    }
}
