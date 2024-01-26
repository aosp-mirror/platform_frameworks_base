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

import android.util.SparseArray
import androidx.annotation.GuardedBy
import com.android.systemui.dagger.SysUISingleton
import javax.inject.Inject
import kotlinx.coroutines.flow.SharedFlow

/** Provides data related to accessibility quick setting shortcut option. */
interface AccessibilityQsShortcutsRepository {
    /**
     * Observable for the a11y features the user chooses in the Settings app to use the quick
     * setting option.
     */
    fun a11yQsShortcutTargets(userId: Int): SharedFlow<Set<String>>
}

@SysUISingleton
class AccessibilityQsShortcutsRepositoryImpl
@Inject
constructor(
    private val userA11yQsShortcutsRepositoryFactory: UserA11yQsShortcutsRepository.Factory,
) : AccessibilityQsShortcutsRepository {

    @GuardedBy("userA11yQsShortcutsRepositories")
    private val userA11yQsShortcutsRepositories = SparseArray<UserA11yQsShortcutsRepository>()

    override fun a11yQsShortcutTargets(userId: Int): SharedFlow<Set<String>> {
        return synchronized(userA11yQsShortcutsRepositories) {
            if (userId !in userA11yQsShortcutsRepositories) {
                val userA11yQsShortcutsRepository =
                    userA11yQsShortcutsRepositoryFactory.create(userId)
                userA11yQsShortcutsRepositories.put(userId, userA11yQsShortcutsRepository)
            }
            userA11yQsShortcutsRepositories.get(userId).targets
        }
    }
}
