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
package com.android.keyguard

import android.os.PowerManager
import android.os.SystemClock
import com.android.systemui.dagger.qualifiers.UiBackground
import java.util.concurrent.Executor
import javax.inject.Inject

/** Wrapper class for notifying the system about user activity in the background. */
class UserActivityNotifier
@Inject
constructor(
    @UiBackground private val uiBgExecutor: Executor,
    private val powerManager: PowerManager
) {

    fun notifyUserActivity() {
        uiBgExecutor.execute {
            powerManager.userActivity(
                SystemClock.uptimeMillis(),
                PowerManager.USER_ACTIVITY_EVENT_OTHER,
                0
            )
        }
    }
}
