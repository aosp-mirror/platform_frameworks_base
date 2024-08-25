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

package com.android.systemui.statusbar.notification.stack.data.repository

import com.android.systemui.kosmos.Kosmos
import com.android.systemui.kosmos.Kosmos.Fixture
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRepository
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRowRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

val Kosmos.headsUpNotificationRepository by Fixture { FakeHeadsUpNotificationRepository() }

class FakeHeadsUpNotificationRepository : HeadsUpRepository {
    override val isHeadsUpAnimatingAway: MutableStateFlow<Boolean> = MutableStateFlow(false)

    val orderedHeadsUpRows = MutableStateFlow(emptyList<HeadsUpRowRepository>())
    override val topHeadsUpRow: Flow<HeadsUpRowRepository?> =
        orderedHeadsUpRows.map { it.firstOrNull() }.distinctUntilChanged()
    override val activeHeadsUpRows: Flow<Set<HeadsUpRowRepository>> =
        orderedHeadsUpRows.map { it.toSet() }.distinctUntilChanged()

    override fun setHeadsUpAnimatingAway(animatingAway: Boolean) {
        isHeadsUpAnimatingAway.value = animatingAway
    }

    override fun snooze() {
        // do nothing
    }

    override fun unpinAll(userUnPinned: Boolean) {
        // do nothing
    }

    override fun releaseAfterExpansion() {
        // do nothing
    }

    fun setNotifications(notifications: List<HeadsUpRowRepository>) {
        this.orderedHeadsUpRows.value = notifications.toList()
    }

    fun setNotifications(vararg notifications: HeadsUpRowRepository) {
        this.orderedHeadsUpRows.value = notifications.toList()
    }
}
