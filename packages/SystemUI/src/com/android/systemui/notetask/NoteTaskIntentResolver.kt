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

package com.android.systemui.notetask

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import javax.inject.Inject

internal class NoteTaskIntentResolver
@Inject
constructor(
    private val context: Context,
    private val roleManager: RoleManager,
) {

    fun resolveIntent(): Intent? {
        val packageName = roleManager.getRoleHoldersAsUser(ROLE_NOTES, context.user).firstOrNull()

        if (packageName.isNullOrEmpty()) return null

        return Intent(ACTION_CREATE_NOTE)
            .setPackage(packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            // EXTRA_USE_STYLUS_MODE does not mean a stylus is in-use, but a stylus entrypoint was
            // used to start it.
            .putExtra(INTENT_EXTRA_USE_STYLUS_MODE, true)
    }

    companion object {
        // TODO(b/265912743): Use Intent.ACTION_CREATE_NOTE instead.
        const val ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE"

        // TODO(b/265912743): Use RoleManager.NOTES_ROLE instead.
        const val ROLE_NOTES = "android.app.role.NOTES"

        // TODO(b/265912743): Use Intent.INTENT_EXTRA_USE_STYLUS_MODE instead.
        const val INTENT_EXTRA_USE_STYLUS_MODE = "android.intent.extra.USE_STYLUS_MODE"
    }
}
