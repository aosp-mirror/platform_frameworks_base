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
import android.hardware.input.InputManager
import android.hardware.input.KeyGestureEvent
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStoreFile
import com.android.systemui.common.coroutine.ChannelExt.trySendWithFailureLogging
import com.android.systemui.contextualeducation.GestureType
import com.android.systemui.contextualeducation.GestureType.ALL_APPS
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.education.dagger.ContextualEducationModule.EduDataStoreScope
import com.android.systemui.education.data.model.EduDeviceConnectionTime
import com.android.systemui.education.data.model.GestureEduModel
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import java.time.Instant
import java.util.concurrent.Executor
import javax.inject.Inject
import javax.inject.Provider
import kotlin.properties.Delegates.notNull
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

/**
 * Allows to:
 * 1) read and update data on model-level
 * 2) change data store when user is changed
 */
interface ContextualEducationRepository {
    fun setUser(userId: Int)

    fun readGestureEduModelFlow(gestureType: GestureType): Flow<GestureEduModel>

    fun readEduDeviceConnectionTime(): Flow<EduDeviceConnectionTime>

    suspend fun updateGestureEduModel(
        gestureType: GestureType,
        transform: (GestureEduModel) -> GestureEduModel
    )

    suspend fun updateEduDeviceConnectionTime(
        transform: (EduDeviceConnectionTime) -> EduDeviceConnectionTime
    )

    val keyboardShortcutTriggered: Flow<GestureType>
}

/**
 * Implementation of [ContextualEducationRepository] that uses [androidx.datastore.preferences.core]
 * for storage. Data is stored per user.
 */
@SysUISingleton
class UserContextualEducationRepository
@Inject
constructor(
    @Application private val applicationContext: Context,
    @EduDataStoreScope private val dataStoreScopeProvider: Provider<CoroutineScope>,
    private val inputManager: InputManager,
    @Background private val backgroundDispatcher: CoroutineDispatcher,
) : ContextualEducationRepository {
    companion object {
        const val TAG = "UserContextualEducationRepository"

        const val SIGNAL_COUNT_SUFFIX = "_SIGNAL_COUNT"
        const val NUMBER_OF_EDU_SHOWN_SUFFIX = "_NUMBER_OF_EDU_SHOWN"
        const val LAST_SHORTCUT_TRIGGERED_TIME_SUFFIX = "_LAST_SHORTCUT_TRIGGERED_TIME"
        const val USAGE_SESSION_START_TIME_SUFFIX = "_USAGE_SESSION_START_TIME"
        const val LAST_EDUCATION_TIME_SUFFIX = "_LAST_EDUCATION_TIME"
        const val KEYBOARD_FIRST_CONNECTION_TIME = "KEYBOARD_FIRST_CONNECTION_TIME"
        const val TOUCHPAD_FIRST_CONNECTION_TIME = "TOUCHPAD_FIRST_CONNECTION_TIME"

        const val DATASTORE_DIR = "education/USER%s_ContextualEducation"
    }

    private var userId by notNull<Int>()

    private var dataStoreScope: CoroutineScope? = null

    private val datastore = MutableStateFlow<DataStore<Preferences>?>(null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val prefData: Flow<Preferences> = datastore.filterNotNull().flatMapLatest { it.data }

    override val keyboardShortcutTriggered: Flow<GestureType> =
        conflatedCallbackFlow {
                val listener =
                    InputManager.KeyGestureEventListener { event ->
                        // Only store keyboard shortcut time for gestures providing keyboard
                        // education
                        val shortcutType =
                            when (event.keyGestureType) {
                                KeyGestureEvent.KEY_GESTURE_TYPE_ACCESSIBILITY_ALL_APPS,
                                KeyGestureEvent.KEY_GESTURE_TYPE_ALL_APPS -> ALL_APPS

                                else -> null
                            }

                        if (shortcutType != null) {
                            trySendWithFailureLogging(shortcutType, TAG)
                        }
                    }

                inputManager.registerKeyGestureEventListener(Executor(Runnable::run), listener)
                awaitClose { inputManager.unregisterKeyGestureEventListener(listener) }
            }
            .flowOn(backgroundDispatcher)

    override fun setUser(userId: Int) {
        dataStoreScope?.cancel()
        val newDsScope = dataStoreScopeProvider.get()
        this.userId = userId
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

    override fun readGestureEduModelFlow(gestureType: GestureType): Flow<GestureEduModel> =
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
                    Instant.ofEpochSecond(it)
                },
            usageSessionStartTime =
                preferences[getUsageSessionStartTimeKey(gestureType)]?.let {
                    Instant.ofEpochSecond(it)
                },
            lastEducationTime =
                preferences[getLastEducationTimeKey(gestureType)]?.let {
                    Instant.ofEpochSecond(it)
                },
            userId = userId,
            gestureType = gestureType,
        )
    }

    override suspend fun updateGestureEduModel(
        gestureType: GestureType,
        transform: (GestureEduModel) -> GestureEduModel
    ) {
        datastore.filterNotNull().first().edit { preferences ->
            val currentModel = getGestureEduModel(gestureType, preferences)
            val updatedModel = transform(currentModel)
            preferences[getSignalCountKey(gestureType)] = updatedModel.signalCount
            preferences[getEducationShownCountKey(gestureType)] = updatedModel.educationShownCount
            setInstant(
                preferences,
                updatedModel.lastShortcutTriggeredTime,
                getLastShortcutTriggeredTimeKey(gestureType)
            )
            setInstant(
                preferences,
                updatedModel.usageSessionStartTime,
                getUsageSessionStartTimeKey(gestureType)
            )
            setInstant(
                preferences,
                updatedModel.lastEducationTime,
                getLastEducationTimeKey(gestureType)
            )
        }
    }

    override fun readEduDeviceConnectionTime(): Flow<EduDeviceConnectionTime> =
        prefData.map { preferences -> getEduDeviceConnectionTime(preferences) }

    override suspend fun updateEduDeviceConnectionTime(
        transform: (EduDeviceConnectionTime) -> EduDeviceConnectionTime
    ) {
        datastore.filterNotNull().first().edit { preferences ->
            val currentModel = getEduDeviceConnectionTime(preferences)
            val updatedModel = transform(currentModel)
            setInstant(
                preferences,
                updatedModel.keyboardFirstConnectionTime,
                getKeyboardFirstConnectionTimeKey()
            )
            setInstant(
                preferences,
                updatedModel.touchpadFirstConnectionTime,
                getTouchpadFirstConnectionTimeKey()
            )
        }
    }

    private fun getEduDeviceConnectionTime(preferences: Preferences): EduDeviceConnectionTime {
        return EduDeviceConnectionTime(
            keyboardFirstConnectionTime =
                preferences[getKeyboardFirstConnectionTimeKey()]?.let { Instant.ofEpochSecond(it) },
            touchpadFirstConnectionTime =
                preferences[getTouchpadFirstConnectionTimeKey()]?.let { Instant.ofEpochSecond(it) }
        )
    }

    private fun getSignalCountKey(gestureType: GestureType): Preferences.Key<Int> =
        intPreferencesKey(gestureType.name + SIGNAL_COUNT_SUFFIX)

    private fun getEducationShownCountKey(gestureType: GestureType): Preferences.Key<Int> =
        intPreferencesKey(gestureType.name + NUMBER_OF_EDU_SHOWN_SUFFIX)

    private fun getLastShortcutTriggeredTimeKey(gestureType: GestureType): Preferences.Key<Long> =
        longPreferencesKey(gestureType.name + LAST_SHORTCUT_TRIGGERED_TIME_SUFFIX)

    private fun getUsageSessionStartTimeKey(gestureType: GestureType): Preferences.Key<Long> =
        longPreferencesKey(gestureType.name + USAGE_SESSION_START_TIME_SUFFIX)

    private fun getLastEducationTimeKey(gestureType: GestureType): Preferences.Key<Long> =
        longPreferencesKey(gestureType.name + LAST_EDUCATION_TIME_SUFFIX)

    private fun getKeyboardFirstConnectionTimeKey(): Preferences.Key<Long> =
        longPreferencesKey(KEYBOARD_FIRST_CONNECTION_TIME)

    private fun getTouchpadFirstConnectionTimeKey(): Preferences.Key<Long> =
        longPreferencesKey(TOUCHPAD_FIRST_CONNECTION_TIME)

    private fun setInstant(
        preferences: MutablePreferences,
        instant: Instant?,
        key: Preferences.Key<Long>
    ) {
        if (instant != null) {
            // Use epochSecond because an instant is defined as a signed long (64bit number) of
            // seconds. Using toEpochMilli() on Instant.MIN or Instant.MAX will throw exception
            // when converting to a long. So we use second instead of milliseconds for storage.
            preferences[key] = instant.epochSecond
        } else {
            preferences.remove(key)
        }
    }
}
