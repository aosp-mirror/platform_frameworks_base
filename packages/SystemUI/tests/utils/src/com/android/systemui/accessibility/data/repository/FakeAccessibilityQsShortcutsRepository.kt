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

package com.android.systemui.accessibility.data.repository

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class FakeAccessibilityQsShortcutsRepository : AccessibilityQsShortcutsRepository {

    private val targetsPerUser = mutableMapOf<Int, MutableSharedFlow<Set<String>>>()

    override fun a11yQsShortcutTargets(userId: Int): SharedFlow<Set<String>> {
        return getFlow(userId).asSharedFlow()
    }

    /**
     * Set the a11y qs shortcut targets. In real world, the A11y QS Shortcut targets are set by the
     * Settings app not in SysUi
     */
    suspend fun setA11yQsShortcutTargets(userId: Int, targets: Set<String>) {
        getFlow(userId).emit(targets)
    }

    private fun getFlow(userId: Int): MutableSharedFlow<Set<String>> =
        targetsPerUser.getOrPut(userId) { MutableSharedFlow(replay = 1) }
}
