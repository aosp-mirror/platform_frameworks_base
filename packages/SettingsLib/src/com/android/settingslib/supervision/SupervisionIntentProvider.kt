/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.settingslib.supervision

import android.app.supervision.SupervisionManager
import android.content.Context
import android.content.Intent

/** Helper class meant to provide an intent to launch the supervision settings page. */
object SupervisionIntentProvider {
    private const val ACTION_SHOW_PARENTAL_CONTROLS = "android.settings.SHOW_PARENTAL_CONTROLS"

    /**
     * Returns an [Intent] to the supervision settings page or null if supervision is disabled or
     * the intent is not resolvable.
     */
    @JvmStatic
    fun getSettingsIntent(context: Context): Intent? {
        val supervisionManager = context.getSystemService(SupervisionManager::class.java)
        val supervisionAppPackage = supervisionManager?.activeSupervisionAppPackage ?: return null

        val intent =
            Intent(ACTION_SHOW_PARENTAL_CONTROLS)
                .setPackage(supervisionAppPackage)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val activities =
            context.packageManager.queryIntentActivitiesAsUser(intent, 0, context.userId)
        return if (activities.isNotEmpty()) intent else null
    }
}
