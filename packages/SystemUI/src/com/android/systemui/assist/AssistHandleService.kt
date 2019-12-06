/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.assist

import android.app.Service
import android.content.Intent
import android.os.IBinder
import dagger.Lazy
import javax.inject.Inject

class AssistHandleService @Inject constructor(private val assistManager: Lazy<AssistManager>)
    : Service() {

    private val binder = object : IAssistHandleService.Stub() {
        override fun requestAssistHandles() {
            assistManager.get().requestAssistHandles()
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return binder
    }
}