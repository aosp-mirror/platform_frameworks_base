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

package com.android.systemui.qs.panels.data.repository

import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import com.android.systemui.backup.BackupHelper
import com.android.systemui.backup.BackupHelper.Companion.ACTION_RESTORE_FINISHED
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.qs.panels.shared.model.PanelsLog
import com.android.systemui.qs.pipeline.shared.TileSpec
import com.android.systemui.settings.UserFileManager
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.SharedPreferencesExt.observe
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach

/** Repository for QS user preferences. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class QSPreferencesRepository
@Inject
constructor(
    private val userFileManager: UserFileManager,
    private val userRepository: UserRepository,
    private val defaultLargeTilesRepository: DefaultLargeTilesRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
    @PanelsLog private val logBuffer: LogBuffer,
    broadcastDispatcher: BroadcastDispatcher,
) {
    private val logger by lazy { Logger(logBuffer, TAG) }

    private val backupRestorationEvents: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(
                filter = IntentFilter(ACTION_RESTORE_FINISHED),
                flags = Context.RECEIVER_NOT_EXPORTED,
                permission = BackupHelper.PERMISSION_SELF,
            )
            .onEach { logger.i("Restored state for QS preferences.") }
            .emitOnStart()

    /** Set of [TileSpec] to display as large tiles for the current user. */
    val largeTilesSpecs: Flow<Set<TileSpec>> =
        combine(backupRestorationEvents, userRepository.selectedUserInfo, ::Pair)
            .flatMapLatest { (_, userInfo) ->
                val prefs = getSharedPrefs(userInfo.id)
                prefs.observe().emitOnStart().map {
                    prefs
                        .getStringSet(
                            LARGE_TILES_SPECS_KEY,
                            defaultLargeTilesRepository.defaultLargeTiles.map { it.spec }.toSet(),
                        )
                        ?.map { TileSpec.create(it) }
                        ?.toSet() ?: defaultLargeTilesRepository.defaultLargeTiles
                }
            }
            .flowOn(backgroundDispatcher)

    /** Sets for the current user the set of [TileSpec] to display as large tiles. */
    fun setLargeTilesSpecs(specs: Set<TileSpec>) {
        with(getSharedPrefs(userRepository.getSelectedUserInfo().id)) {
            edit().putStringSet(LARGE_TILES_SPECS_KEY, specs.map { it.spec }.toSet()).apply()
        }
    }

    private fun getSharedPrefs(userId: Int): SharedPreferences {
        return userFileManager.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE, userId)
    }

    companion object {
        private const val TAG = "QSPreferencesRepository"
        private const val LARGE_TILES_SPECS_KEY = "large_tiles_specs"
        const val FILE_NAME = "quick_settings_prefs"
    }
}
