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

package com.android.systemui.statusbar.policy

import android.app.NotificationManager
import com.android.systemui.CoreStartable
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.modes.shared.ModesUi
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import com.android.app.tracing.coroutines.launchTraced as launch
import kotlinx.coroutines.withContext

/**
 * Cleanup task that deletes the obsolete "Gaming" AutomaticZenRule that was created by SystemUI in
 * the faraway past, and still exists on some devices through upgrades or B&R.
 */
// TODO: b/372874878 - Remove this thing once it has run long enough
class ZenModesCleanupStartable
@Inject
constructor(
    @Application private val applicationCoroutineScope: CoroutineScope,
    @Background private val bgContext: CoroutineContext,
    val notificationManager: NotificationManager,
) : CoreStartable {

    override fun start() {
        if (!ModesUi.isEnabled) {
            return
        }
        applicationCoroutineScope.launch { deleteObsoleteGamingMode() }
    }

    private suspend fun deleteObsoleteGamingMode() {
        withContext(bgContext) {
            val allRules = notificationManager.automaticZenRules
            val gamingModeEntry =
                allRules.entries.firstOrNull { entry ->
                    entry.value.packageName == "com.android.systemui" &&
                        entry.value.conditionId?.toString() ==
                            "android-app://com.android.systemui/game-mode-dnd-controller"
                }
            if (gamingModeEntry != null) {
                notificationManager.removeAutomaticZenRule(gamingModeEntry.key)
            }
        }
    }
}
