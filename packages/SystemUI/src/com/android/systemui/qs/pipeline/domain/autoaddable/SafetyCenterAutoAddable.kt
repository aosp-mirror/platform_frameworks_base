/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.qs.pipeline.domain.autoaddable

import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.res.Resources
import android.text.TextUtils
import com.android.systemui.res.R
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.statusbar.policy.SafetyController
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext

/**
 * [AutoAddable] for the safety tile.
 *
 * It will send a signal to add the tile when the feature is enabled, indicating the component
 * corresponding to the tile. If the feature is disabled, it will send a signal to remove the tile.
 */
@SysUISingleton
class SafetyCenterAutoAddable
@Inject
constructor(
    private val safetyController: SafetyController,
    private val packageManager: PackageManager,
    @Main private val resources: Resources,
    @Background private val bgDispatcher: CoroutineDispatcher,
) : AutoAddable {

    private suspend fun getSpec(): TileSpec? {
        val specClass = resources.getString(R.string.safety_quick_settings_tile_class)
        return if (TextUtils.isEmpty(specClass)) {
            null
        } else {
            val packageName =
                withContext(bgDispatcher) { packageManager.permissionControllerPackageName }
            TileSpec.create(ComponentName(packageName, specClass))
        }
    }

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        return conflatedCallbackFlow {
            val spec = getSpec()
            if (spec != null) {
                // If not added, we always try to add it
                trySend(AutoAddSignal.Add(spec))
                val listener =
                    SafetyController.Listener { isSafetyCenterEnabled ->
                        if (isSafetyCenterEnabled) {
                            trySend(AutoAddSignal.Add(spec))
                        } else {
                            trySend(AutoAddSignal.Remove(spec))
                        }
                    }

                safetyController.addCallback(listener)

                awaitClose { safetyController.removeCallback(listener) }
            } else {
                awaitClose {}
            }
        }
    }

    override val autoAddTracking: AutoAddTracking
        get() = AutoAddTracking.Always

    override val description = "SafetyCenterAutoAddable ($autoAddTracking)"
}
