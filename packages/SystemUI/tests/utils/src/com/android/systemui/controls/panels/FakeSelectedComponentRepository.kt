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

import android.os.UserHandle
import com.android.systemui.kosmos.Kosmos
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow

class FakeSelectedComponentRepository : SelectedComponentRepository {
    private var shouldAddDefaultPanel: Boolean = true
    private val _selectedComponentFlows =
        mutableMapOf<UserHandle, MutableStateFlow<SelectedComponentRepository.SelectedComponent?>>()
    private var currentUserHandle: UserHandle = UserHandle.of(0)

    override fun selectedComponentFlow(
        userHandle: UserHandle
    ): Flow<SelectedComponentRepository.SelectedComponent?> {
        // Return an existing flow for the user or create a new one
        return _selectedComponentFlows.getOrPut(getUserHandle(userHandle)) {
            MutableStateFlow(null)
        }
    }

    override fun getSelectedComponent(
        userHandle: UserHandle
    ): SelectedComponentRepository.SelectedComponent? {
        return _selectedComponentFlows[getUserHandle(userHandle)]?.value
    }

    override fun setSelectedComponent(
        selectedComponent: SelectedComponentRepository.SelectedComponent
    ) {
        val flow = _selectedComponentFlows.getOrPut(currentUserHandle) { MutableStateFlow(null) }
        flow.value = selectedComponent
    }

    override fun removeSelectedComponent() {
        _selectedComponentFlows[currentUserHandle]?.value = null
    }
    override fun shouldAddDefaultComponent(): Boolean = shouldAddDefaultPanel

    override fun setShouldAddDefaultComponent(shouldAdd: Boolean) {
        shouldAddDefaultPanel = shouldAdd
    }

    fun setCurrentUserHandle(userHandle: UserHandle) {
        currentUserHandle = userHandle
    }
    private fun getUserHandle(userHandle: UserHandle): UserHandle {
        return if (userHandle == UserHandle.CURRENT) {
            currentUserHandle
        } else {
            userHandle
        }
    }
}

val Kosmos.selectedComponentRepository by
    Kosmos.Fixture<FakeSelectedComponentRepository> { FakeSelectedComponentRepository() }
