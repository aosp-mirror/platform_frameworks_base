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
import android.content.SharedPreferences
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserFileManager
import com.android.systemui.user.data.repository.UserRepository
import com.android.systemui.util.kotlin.SharedPreferencesExt.observe
import com.android.systemui.util.kotlin.emitOnStart
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/** Repository for QS user preferences. */
@OptIn(ExperimentalCoroutinesApi::class)
@SysUISingleton
class QSPreferencesRepository
@Inject
constructor(
    private val userFileManager: UserFileManager,
    private val userRepository: UserRepository,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) {
    /** Whether to show the labels on icon tiles for the current user. */
    val showLabels: Flow<Boolean> =
        userRepository.selectedUserInfo
            .flatMapLatest { userInfo ->
                val prefs = getSharedPrefs(userInfo.id)
                prefs.observe().emitOnStart().map { prefs.getBoolean(ICON_LABELS_KEY, false) }
            }
            .flowOn(backgroundDispatcher)

    /** Sets for the current user whether to show the labels on icon tiles. */
    fun setShowLabels(showLabels: Boolean) {
        with(getSharedPrefs(userRepository.getSelectedUserInfo().id)) {
            edit().putBoolean(ICON_LABELS_KEY, showLabels).apply()
        }
    }

    private fun getSharedPrefs(userId: Int): SharedPreferences {
        return userFileManager.getSharedPreferences(
            FILE_NAME,
            Context.MODE_PRIVATE,
            userId,
        )
    }

    companion object {
        private const val ICON_LABELS_KEY = "show_icon_labels"
        const val FILE_NAME = "quick_settings_prefs"
    }
}
