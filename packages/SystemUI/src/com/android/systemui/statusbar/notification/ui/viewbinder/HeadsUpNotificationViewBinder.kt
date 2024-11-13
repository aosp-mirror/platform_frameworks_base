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

package com.android.systemui.statusbar.notification.ui.viewbinder

import com.android.systemui.statusbar.notification.row.ExpandableNotificationRow
import com.android.systemui.statusbar.notification.shared.HeadsUpRowKey
import com.android.systemui.statusbar.notification.stack.NotificationStackScrollLayout
import com.android.systemui.statusbar.notification.stack.ui.viewmodel.NotificationListViewModel
import com.android.systemui.util.kotlin.sample
import com.android.systemui.utils.coroutines.flow.conflatedCallbackFlow
import javax.inject.Inject
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

class HeadsUpNotificationViewBinder
@Inject
constructor(private val viewModel: NotificationListViewModel) {
    suspend fun bindHeadsUpNotifications(parentView: NotificationStackScrollLayout): Unit =
        coroutineScope {
            launch {
                var previousKeys = emptySet<HeadsUpRowKey>()
                viewModel.pinnedHeadsUpRows
                    .sample(viewModel.headsUpAnimationsEnabled, ::Pair)
                    .collect { (newKeys, animationsEnabled) ->
                        val added = newKeys - previousKeys
                        val removed = previousKeys - newKeys
                        previousKeys = newKeys

                        if (animationsEnabled) {
                            added.forEach { key ->
                                parentView.generateHeadsUpAnimation(
                                    obtainView(key),
                                    /* isHeadsUp = */ true
                                )
                            }
                            removed.forEach { key ->
                                val row = obtainView(key)
                                if (!parentView.isBeingDragged()) {
                                    parentView.generateHeadsUpAnimation(row, /* isHeadsUp= */ false)
                                }
                                row.markHeadsUpSeen()
                            }
                        }
                    }
            }
            launch {
                viewModel.topHeadsUpRow.collect { key ->
                    parentView.setTopHeadsUpRow(key?.let(::obtainView))
                }
            }
            launch {
                viewModel.hasPinnedHeadsUpRow.collect { parentView.setInHeadsUpPinnedMode(it) }
            }
            launch {
                parentView.isHeadsUpAnimatingAway.collect { viewModel.setHeadsUpAnimatingAway(it) }
            }
        }

    private fun obtainView(key: HeadsUpRowKey): ExpandableNotificationRow {
        return viewModel.elementKeyFor(key) as ExpandableNotificationRow
    }
}

private val NotificationStackScrollLayout.isHeadsUpAnimatingAway: Flow<Boolean>
    get() = conflatedCallbackFlow {
        setHeadsUpAnimatingAwayListener { animatingAway -> trySend(animatingAway) }
        awaitClose { setHeadsUpAnimatingAwayListener(null) }
    }
