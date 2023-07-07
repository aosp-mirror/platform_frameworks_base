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

package com.android.server.permission.access.appop

import android.app.AppOpsManager

object AppOpModes {
    const val MODE_ALLOWED = AppOpsManager.MODE_ALLOWED
    const val MODE_IGNORED = AppOpsManager.MODE_IGNORED
    const val MODE_ERRORED = AppOpsManager.MODE_ERRORED
    const val MODE_DEFAULT = AppOpsManager.MODE_DEFAULT
    const val MODE_FOREGROUND = AppOpsManager.MODE_FOREGROUND
}
