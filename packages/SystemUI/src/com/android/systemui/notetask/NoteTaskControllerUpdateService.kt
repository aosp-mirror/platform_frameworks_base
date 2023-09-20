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

package com.android.systemui.notetask

import android.content.Context
import android.content.Intent
import androidx.lifecycle.LifecycleService
import javax.inject.Inject

/**
 * A fire & forget service for updating note task shortcuts.
 *
 * The main use is to update shortcuts in different user by launching it using `startServiceAsUser`.
 * The service will open with access to a context from that user, trigger
 * [NoteTaskController.updateNoteTaskAsUser] and [stopSelf] immediately.
 *
 * The fire and forget approach was created due to its simplicity but may use unnecessary resources
 * by recreating the services. We will investigate its impacts and consider to move to a bounded
 * services - the implementation is more complex as a bounded service is asynchronous by default.
 *
 * TODO(b/278729185): Replace fire and forget service with a bounded service.
 */
@InternalNoteTaskApi
class NoteTaskControllerUpdateService
@Inject
constructor(
    val controller: NoteTaskController,
) : LifecycleService() {

    override fun onCreate() {
        super.onCreate()
        // TODO(b/278729185): Replace fire and forget service with a bounded service.
        controller.launchUpdateNoteTaskAsUser(user)
        stopSelf()
    }

    companion object {
        fun createIntent(context: Context): Intent =
            Intent(context, NoteTaskControllerUpdateService::class.java)
    }
}
