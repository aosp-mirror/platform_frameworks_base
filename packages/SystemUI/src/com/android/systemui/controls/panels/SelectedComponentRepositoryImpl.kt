/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.controls.panels

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import android.os.UserHandle
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.settings.UserFileManager
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.policy.DeviceControlsControllerImpl
import com.android.systemui.util.kotlin.SharedPreferencesExt.observe
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@SysUISingleton
class SelectedComponentRepositoryImpl
@Inject
constructor(
    private val userFileManager: UserFileManager,
    private val userTracker: UserTracker,
    @Background private val bgDispatcher: CoroutineDispatcher
) : SelectedComponentRepository {

    private companion object {
        const val PREF_COMPONENT = "controls_component"
        const val PREF_STRUCTURE_OR_APP_NAME = "controls_structure"
        const val PREF_IS_PANEL = "controls_is_panel"
        const val SHOULD_ADD_DEFAULT_PANEL = "should_add_default_panel"
    }

    private fun getSharedPreferencesForUser(userId: Int): SharedPreferences {
        return userFileManager.getSharedPreferences(
            fileName = DeviceControlsControllerImpl.PREFS_CONTROLS_FILE,
            mode = Context.MODE_PRIVATE,
            userId = userId
        )
    }

    override fun selectedComponentFlow(
        userHandle: UserHandle
    ): Flow<SelectedComponentRepository.SelectedComponent?> {
        val prefs = getSharedPreferencesForUser(userHandle.identifier)
        return prefs
            .observe(PREF_COMPONENT)
            .onStart { emit(Unit) }
            .map { getSelectedComponent(userHandle) }
            .flowOn(bgDispatcher)
    }

    override fun getSelectedComponent(
        userHandle: UserHandle
    ): SelectedComponentRepository.SelectedComponent? {
        val userId =
            if (userHandle == UserHandle.CURRENT) userTracker.userId else userHandle.identifier
        with(getSharedPreferencesForUser(userId)) {
            val componentString = getString(PREF_COMPONENT, null) ?: return null
            return SelectedComponentRepository.SelectedComponent(
                name = getString(PREF_STRUCTURE_OR_APP_NAME, "")!!,
                componentName = ComponentName.unflattenFromString(componentString),
                isPanel = getBoolean(PREF_IS_PANEL, false)
            )
        }
    }

    override fun setSelectedComponent(
        selectedComponent: SelectedComponentRepository.SelectedComponent
    ) {
        getSharedPreferencesForUser(userTracker.userId)
            .edit()
            .putString(PREF_COMPONENT, selectedComponent.componentName?.flattenToString())
            .putString(PREF_STRUCTURE_OR_APP_NAME, selectedComponent.name)
            .putBoolean(PREF_IS_PANEL, selectedComponent.isPanel)
            .apply()
    }

    override fun removeSelectedComponent() {
        getSharedPreferencesForUser(userTracker.userId)
            .edit()
            .remove(PREF_COMPONENT)
            .remove(PREF_STRUCTURE_OR_APP_NAME)
            .remove(PREF_IS_PANEL)
            .apply()
    }

    override fun shouldAddDefaultComponent(): Boolean =
        getSharedPreferencesForUser(userTracker.userId).getBoolean(SHOULD_ADD_DEFAULT_PANEL, true)

    override fun setShouldAddDefaultComponent(shouldAdd: Boolean) {
        getSharedPreferencesForUser(userTracker.userId)
            .edit()
            .putBoolean(SHOULD_ADD_DEFAULT_PANEL, shouldAdd)
            .apply()
    }
}
