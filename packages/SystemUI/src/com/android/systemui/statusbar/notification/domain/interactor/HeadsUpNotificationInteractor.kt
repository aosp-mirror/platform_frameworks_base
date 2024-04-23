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

@file:OptIn(ExperimentalCoroutinesApi::class)

package com.android.systemui.statusbar.notification.domain.interactor

import com.android.systemui.statusbar.notification.data.repository.HeadsUpRepository
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRowRepository
import com.android.systemui.statusbar.notification.shared.HeadsUpRowKey
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class HeadsUpNotificationInteractor @Inject constructor(private val repository: HeadsUpRepository) {

    val topHeadsUpRow: Flow<HeadsUpRowKey?> = repository.topHeadsUpRow

    /** Set of currently pinned top-level heads up rows to be displayed. */
    val pinnedHeadsUpRows: Flow<Set<HeadsUpRowKey>> =
        repository.activeHeadsUpRows.flatMapLatest { repositories ->
            if (repositories.isNotEmpty()) {
                val toCombine: List<Flow<Pair<HeadsUpRowRepository, Boolean>>> =
                    repositories.map { repo -> repo.isPinned.map { isPinned -> repo to isPinned } }
                combine(toCombine) { pairs ->
                    pairs.filter { (_, isPinned) -> isPinned }.map { (repo, _) -> repo }.toSet()
                }
            } else {
                // if the set is empty, there are no flows to combine
                flowOf(emptySet())
            }
        }

    /** Are there any pinned heads up rows to display? */
    val hasPinnedRows: Flow<Boolean> =
        repository.activeHeadsUpRows.flatMapLatest { rows ->
            if (rows.isNotEmpty()) {
                combine(rows.map { it.isPinned }) { pins -> pins.any { it } }
            } else {
                // if the set is empty, there are no flows to combine
                flowOf(false)
            }
        }

    val isHeadsUpOrAnimatingAway: Flow<Boolean> =
        combine(hasPinnedRows, repository.isHeadsUpAnimatingAway) { hasPinnedRows, animatingAway ->
            hasPinnedRows || animatingAway
        }

    fun headsUpRow(key: HeadsUpRowKey): HeadsUpRowInteractor =
        HeadsUpRowInteractor(key as HeadsUpRowRepository)
    fun elementKeyFor(key: HeadsUpRowKey) = (key as HeadsUpRowRepository).elementKey
    fun setHeadsUpAnimatingAway(animatingAway: Boolean) {
        repository.setHeadsUpAnimatingAway(animatingAway)
    }
}

class HeadsUpRowInteractor(repository: HeadsUpRowRepository)
