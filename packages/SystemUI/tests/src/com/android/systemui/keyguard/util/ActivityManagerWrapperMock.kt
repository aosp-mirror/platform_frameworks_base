/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package com.android.systemui.keyguard.util

import android.app.ActivityManager
import android.content.ComponentName
import com.android.systemui.shared.system.ActivityManagerWrapper
import com.android.systemui.util.mockito.mock
import com.android.systemui.util.mockito.whenever

/**
 * Configures an ActivityManagerWrapper mock to return the given class name whenever we ask for the
 * running task's top activity class name.
 */
fun ActivityManagerWrapper.mockTopActivityClassName(name: String) {
    val topActivityMock = mock<ComponentName>().apply { whenever(className).thenReturn(name) }

    whenever(runningTask)
        .thenReturn(ActivityManager.RunningTaskInfo().apply { topActivity = topActivityMock })
}
