/*
 * Copyright 2024 The Android Open Source Project
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

package com.android.systemui.education.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.education.dagger.ContextualEducationModule.EduDataStoreScope
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.shared.education.GestureType
import java.time.Instant
import javax.inject.Inject
import javax.inject.Provider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/**
 * A contextual education repository to:
 * 1) store education data per user
 * 2) provide methods to read and update data on model-level
 * 3) provide method to enable changing datastore when user is changed
 */
@SysUISingleton
class UserContextualEducationRepository
@Inject
constructor(
    @Application private val applicationContext: Context,
    @EduDataStoreScope private val dataStoreScopeProvider: Provider<CoroutineScope>
) {
    companion object {
        const val SIGNAL_COUNT_SUFFIX = "_SIGNAL_COUNT"
        const val NUMBER_OF_EDU_SHOWN_SUFFIX = "_NUMBER_OF_EDU_SHOWN"
        const val LAST_SHORTCUT_TRIGGERED_TIME_SUFFIX = "_LAST_SHORTCUT_TRIGGERED_TIME"

        const val DATASTORE_DIR = "education/USER%s_ContextualEducation"
    }

    private var dataStoreScope: CoroutineScope? = null

    private val datastore = MutableStateFlow<DataStore<Preferences>?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val prefData: Flow<Preferences> = datastore.filterNotNull().flatMapLatest { it.data }

    internal fun setUser(userId: Int) {
        dataStoreScope?.cancel()
        val newDsScope = dataStoreScopeProvider.get()
        datastore.value =
            PreferenceDataStoreFactory.create(
                produceFile = {
                    applicationContext.preferencesDataStoreFile(
                        String.format(DATASTORE_DIR, userId)
                    )
                },
                scope = newDsScope,
            )
        dataStoreScope = newDsScope
    }

    internal fun readGestureEduModelFlow(gestureType: GestureType): Flow<GestureEduModel> =
        prefData.map { preferences -> getGestureEduModel(gestureType, preferences) }

    private fun getGestureEduModel(
        gestureType: GestureType,
        preferences: Preferences
    ): GestureEduModel {
        return GestureEduModel(
            signalCount = preferences[getSignalCountKey(gestureType)] ?: 0,
            educationShownCount = preferences[getEducationShownCountKey(gestureType)] ?: 0,
            lastShortcutTriggeredTime =
                preferences[getLastShortcutTriggeredTimeKey(gestureType)]?.let {
                    Instant.ofEpochMilli(it)
                },
        )
    }

    internal suspend fun updateGestureEduModel(
        gestureType: GestureType,
        transform: (GestureEduModel) -> GestureEduModel
    ) {
        datastore.filterNotNull().first().edit { preferences ->
            val currentModel = getGestureEduModel(gestureType, preferences)
            val updatedModel = transform(currentModel)
            preferences[getSignalCountKey(gestureType)] = updatedModel.signalCount
            preferences[getEducationShownCountKey(gestureType)] = updatedModel.educationShownCount
            updateTimeByInstant(
                preferences,
                updatedModel.lastShortcutTriggeredTime,
                getLastShortcutTriggeredTimeKey(gestureType)
            )
        }
    }

    private fun getSignalCountKey(gestureType: GestureType): Preferences.Key<Int> =
        intPreferencesKey(gestureType.name + SIGNAL_COUNT_SUFFIX)

    private fun getEducationShownCountKey(gestureType: GestureType): Preferences.Key<Int> =
        intPreferencesKey(gestureType.name + NUMBER_OF_EDU_SHOWN_SUFFIX)

    private fun getLastShortcutTriggeredTimeKey(gestureType: GestureType): Preferences.Key<Long> =
        longPreferencesKey(gestureType.name + LAST_SHORTCUT_TRIGGERED_TIME_SUFFIX)

    private fun updateTimeByInstant(
        preferences: MutablePreferences,
        instant: Instant?,
        key: Preferences.Key<Long>
    ) {
        if (instant != null) {
            preferences[key] = instant.toEpochMilli()
        } else {
            preferences.remove(key)
        }
    }
}
