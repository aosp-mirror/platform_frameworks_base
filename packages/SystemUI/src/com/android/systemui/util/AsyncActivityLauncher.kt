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

package com.android.systemui.util

import android.app.IActivityTaskManager
import android.app.WaitResult
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.UserHandle
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.dagger.qualifiers.UiBackground
import java.util.concurrent.Executor
import javax.inject.Inject

/**
 * Helper class that allows to launch an activity and asynchronously wait
 * for it to be launched. This class uses application context, so the intent
 * will be launched with FLAG_ACTIVITY_NEW_TASK.
 */
class AsyncActivityLauncher @Inject constructor(
    private val context: Context,
    private val activityTaskManager: IActivityTaskManager,
    @UiBackground private val backgroundExecutor: Executor,
    @Main private val mainExecutor: Executor
) {

    private var pendingCallback: ((WaitResult) -> Unit)? = null

    /**
     * Starts activity and notifies about the result using the provided [callback].
     * If there is already pending activity launch the call will be ignored.
     *
     * @return true if launch has started, false otherwise
     */
    fun startActivityAsUser(intent: Intent, userHandle: UserHandle,
                            activityOptions: Bundle? = null,
                            callback: (WaitResult) -> Unit): Boolean {
        if (pendingCallback != null) return false

        pendingCallback = callback

        intent.flags = intent.flags or Intent.FLAG_ACTIVITY_NEW_TASK

        backgroundExecutor.execute {
            val waitResult = activityTaskManager.startActivityAndWait(
                /* caller = */ null,
                /* callingPackage = */ context.packageName,
                /* callingFeatureId = */ context.attributionTag,
                /* intent = */ intent,
                /* resolvedType = */ null,
                /* resultTo = */ null,
                /* resultWho = */ null,
                /* requestCode = */ 0,
                /* flags = */ 0,
                /* profilerInfo = */ null,
                /* options = */ activityOptions,
                /* userId = */ userHandle.identifier
            )
            mainExecutor.execute {
                pendingCallback?.invoke(waitResult)
            }
        }

        return true
    }

    /**
     * Cancels pending activity launches. It guarantees that the callback won't be fired
     * but the activity will be launched anyway.
     */
    fun destroy() {
        pendingCallback = null
    }
}
