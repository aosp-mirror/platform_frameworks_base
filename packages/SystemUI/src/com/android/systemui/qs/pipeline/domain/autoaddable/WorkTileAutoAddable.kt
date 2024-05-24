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

import android.content.pm.UserInfo
import android.os.UserHandle
import com.android.systemui.common.coroutine.ConflatedCallbackFlow.conflatedCallbackFlow
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.qs.pipeline.data.restoreprocessors.WorkTileRestoreProcessor
import com.android.systemui.qs.pipeline.domain.model.AutoAddSignal
import com.android.systemui.qs.pipeline.domain.model.AutoAddTracking
import com.android.systemui.qs.pipeline.domain.model.AutoAddable
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.qs.tiles.WorkModeTile
import com.android.systemui.settings.UserTracker
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.merge

/**
 * [AutoAddable] for [WorkModeTile.TILE_SPEC].
 *
 * It will send a signal to add the tile when there is a managed profile for the current user, and a
 * signal to remove it if there is not.
 */
@SysUISingleton
class WorkTileAutoAddable
@Inject
constructor(
    private val userTracker: UserTracker,
    private val workTileRestoreProcessor: WorkTileRestoreProcessor,
) : AutoAddable {

    private val spec = TileSpec.create(WorkModeTile.TILE_SPEC)

    override fun autoAddSignal(userId: Int): Flow<AutoAddSignal> {
        val removeTrackingDueToRestore: Flow<AutoAddSignal> =
            workTileRestoreProcessor.removeTrackingForUser(UserHandle.of(userId)).mapNotNull {
                val profiles = userTracker.userProfiles
                if (profiles.any { it.id == userId } && !profiles.any { it.isManagedProfile }) {
                    // Only remove auto-added if there are no managed profiles for this user
                    AutoAddSignal.RemoveTracking(spec)
                } else {
                    null
                }
            }
        val signalsFromCallback = conflatedCallbackFlow {
            fun maybeSend(profiles: List<UserInfo>) {
                if (profiles.any { it.id == userId }) {
                    // We are looking at the profiles of the correct user.
                    // They need to be a managed enabled profile.
                    if (profiles.any { it.isManagedProfile && it.isEnabled }) {
                        trySend(
                            AutoAddSignal.Add(
                                spec,
                                workTileRestoreProcessor.pollLastPosition(userId),
                            )
                        )
                    } else {
                        trySend(AutoAddSignal.Remove(spec))
                    }
                }
            }

            val callback =
                object : UserTracker.Callback {
                    override fun onProfilesChanged(profiles: List<UserInfo>) {
                        maybeSend(profiles)
                    }
                }

            userTracker.addCallback(callback) { it.run() }
            maybeSend(userTracker.userProfiles)

            awaitClose { userTracker.removeCallback(callback) }
        }
        return merge(removeTrackingDueToRestore, signalsFromCallback)
    }

    override val autoAddTracking = AutoAddTracking.Always

    override val description = "WorkTileAutoAddable ($autoAddTracking)"
}
