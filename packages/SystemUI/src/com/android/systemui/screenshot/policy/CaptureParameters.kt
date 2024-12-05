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

package com.android.systemui.screenshot.policy

import android.os.UserHandle

data class CaptureParameters(
    /** Describes how the image should be obtained. */
    val type: CaptureType,
    /** Which user to receive the image. */
    val owner: UserHandle,
    /**
     * The task which represents the main content or focal point of the screenshot. This is the task
     * used for retrieval of [AssistContent][android.app.assist.AssistContent] as well as
     * [Scroll Capture][android.view.IWindowManager.requestScrollCapture].
     */
    val contentTask: TaskReference,
)
