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
package com.android.systemui.contrast

import android.app.Activity
import android.app.UiModeManager
import android.content.Context
import android.os.Bundle
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.settings.UserTracker
import com.android.systemui.util.settings.SecureSettings
import java.util.concurrent.Executor
import javax.inject.Inject

/** Trampoline activity responsible for creating a [ContrastDialog] */
class ContrastDialogActivity
@Inject
constructor(
    private val context: Context,
    @Main private val mainExecutor: Executor,
    private val uiModeManager: UiModeManager,
    private val userTracker: UserTracker,
    private val secureSettings: SecureSettings
) : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val contrastDialog =
            ContrastDialog(context, mainExecutor, uiModeManager, userTracker, secureSettings)
        contrastDialog.show()
        finish()
    }
}
