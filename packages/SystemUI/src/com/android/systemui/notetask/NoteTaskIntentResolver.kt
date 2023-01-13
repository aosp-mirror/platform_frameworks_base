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

import android.content.ComponentName
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.pm.PackageManager.ResolveInfoFlags
import com.android.systemui.notetask.NoteTaskIntentResolver.Companion.ACTION_CREATE_NOTE
import javax.inject.Inject

/**
 * Class responsible to query all apps and find one that can handle the [ACTION_CREATE_NOTE]. If
 * found, an [Intent] ready for be launched will be returned. Otherwise, returns null.
 *
 * TODO(b/248274123): should be revisited once the notes role is implemented.
 */
internal class NoteTaskIntentResolver
@Inject
constructor(
    private val packageManager: PackageManager,
) {

    fun resolveIntent(): Intent? {
        val intent = Intent(ACTION_CREATE_NOTE)
        val flags = ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val infoList = packageManager.queryIntentActivities(intent, flags)

        for (info in infoList) {
            val packageName = info.activityInfo.applicationInfo.packageName ?: continue
            val activityName = resolveActivityNameForNotesAction(packageName) ?: continue

            return Intent(ACTION_CREATE_NOTE)
                .setPackage(packageName)
                .setComponent(ComponentName(packageName, activityName))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return null
    }

    private fun resolveActivityNameForNotesAction(packageName: String): String? {
        val intent = Intent(ACTION_CREATE_NOTE).setPackage(packageName)
        val flags = ResolveInfoFlags.of(PackageManager.MATCH_DEFAULT_ONLY.toLong())
        val resolveInfo = packageManager.resolveActivity(intent, flags)

        val activityInfo = resolveInfo?.activityInfo ?: return null
        if (activityInfo.name.isNullOrBlank()) return null
        if (!activityInfo.exported) return null
        if (!activityInfo.enabled) return null
        if (!activityInfo.showWhenLocked) return null
        if (!activityInfo.turnScreenOn) return null

        return activityInfo.name
    }

    companion object {
        // TODO(b/254606432): Use Intent.ACTION_CREATE_NOTE instead.
        const val ACTION_CREATE_NOTE = "android.intent.action.CREATE_NOTE"
    }
}

private val ActivityInfo.showWhenLocked: Boolean
    get() = flags and ActivityInfo.FLAG_SHOW_WHEN_LOCKED != 0

private val ActivityInfo.turnScreenOn: Boolean
    get() = flags and ActivityInfo.FLAG_TURN_SCREEN_ON != 0
