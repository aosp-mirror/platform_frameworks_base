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

package com.android.systemui.communal.data.repository

import android.content.Context
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.UserInfo
import com.android.systemui.backup.BackupHelper
import com.android.systemui.broadcast.BroadcastDispatcher
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.log.LogBuffer
import com.android.systemui.log.core.Logger
import com.android.systemui.log.dagger.CommunalLog
import com.android.systemui.settings.UserFileManager
import com.android.systemui.util.kotlin.SharedPreferencesExt.observe
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.withContext

/**
 * Stores simple preferences for the current user in communal hub. For use cases like "has the CTA
 * tile been dismissed?"
 */
interface CommunalPrefsRepository {

    /** Whether the CTA tile has been dismissed. */
    fun isCtaDismissed(user: UserInfo): Flow<Boolean>

    /** Whether the lock screen widget disclaimer has been dismissed by the user. */
    fun isDisclaimerDismissed(user: UserInfo): Flow<Boolean>

    /** Save the CTA tile dismissed state for the current user. */
    suspend fun setCtaDismissed(user: UserInfo)

    /** Save the lock screen widget disclaimer dismissed state for the current user. */
    suspend fun setDisclaimerDismissed(user: UserInfo)
}

@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class CommunalPrefsRepositoryImpl
@Inject
constructor(
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val userFileManager: UserFileManager,
    broadcastDispatcher: BroadcastDispatcher,
    @CommunalLog logBuffer: LogBuffer,
) : CommunalPrefsRepository {
    private val logger by lazy { Logger(logBuffer, TAG) }

    override fun isCtaDismissed(user: UserInfo): Flow<Boolean> =
        readKeyForUser(user, CTA_DISMISSED_STATE)

    override fun isDisclaimerDismissed(user: UserInfo): Flow<Boolean> =
        readKeyForUser(user, DISCLAIMER_DISMISSED_STATE)

    /**
     * Emits an event each time a Backup & Restore restoration job is completed, and once at the
     * start of collection.
     */
    private val backupRestorationEvents: Flow<Unit> =
        broadcastDispatcher
            .broadcastFlow(
                filter = IntentFilter(BackupHelper.ACTION_RESTORE_FINISHED),
                flags = Context.RECEIVER_NOT_EXPORTED,
                permission = BackupHelper.PERMISSION_SELF,
            )
            .onEach { logger.i("Restored state for communal preferences.") }
            .emitOnStart()

    override suspend fun setCtaDismissed(user: UserInfo) =
        withContext(bgDispatcher) {
            getSharedPrefsForUser(user).edit().putBoolean(CTA_DISMISSED_STATE, true).apply()
            logger.i("Dismissed CTA tile")
        }

    override suspend fun setDisclaimerDismissed(user: UserInfo) =
        withContext(bgDispatcher) {
            getSharedPrefsForUser(user).edit().putBoolean(DISCLAIMER_DISMISSED_STATE, true).apply()
            logger.i("Dismissed widget disclaimer")
        }

    private fun getSharedPrefsForUser(user: UserInfo): SharedPreferences {
        return userFileManager.getSharedPreferences(
            FILE_NAME,
            Context.MODE_PRIVATE,
            user.id,
        )
    }

    private fun readKeyForUser(user: UserInfo, key: String): Flow<Boolean> {
        return backupRestorationEvents
            .flatMapLatest {
                val sharedPrefs = getSharedPrefsForUser(user)
                sharedPrefs.observe().emitOnStart().map { sharedPrefs.getBoolean(key, false) }
            }
            .flowOn(bgDispatcher)
    }

    companion object {
        const val TAG = "CommunalPrefsRepository"
        const val FILE_NAME = "communal_hub_prefs"
        const val CTA_DISMISSED_STATE = "cta_dismissed"
        const val DISCLAIMER_DISMISSED_STATE = "disclaimer_dismissed"
    }
}
