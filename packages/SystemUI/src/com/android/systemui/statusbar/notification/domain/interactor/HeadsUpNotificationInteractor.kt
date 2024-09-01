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

import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRepository
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRowRepository
import com.android.systemui.statusbar.notification.shared.HeadsUpRowKey
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

class HeadsUpNotificationInteractor
@Inject
constructor(
    private val headsUpRepository: HeadsUpRepository,
    private val faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    private val keyguardTransitionInteractor: KeyguardTransitionInteractor,
    private val notificationsKeyguardInteractor: NotificationsKeyguardInteractor,
    private val shadeInteractor: ShadeInteractor,
) {

    /** The top-ranked heads up row, regardless of pinned state */
    val topHeadsUpRow: Flow<HeadsUpRowKey?> = headsUpRepository.topHeadsUpRow

    /** The top-ranked heads up row, if that row is pinned */
    val topHeadsUpRowIfPinned: Flow<HeadsUpRowKey?> =
        headsUpRepository.topHeadsUpRow
            .flatMapLatest { repository ->
                repository?.isPinned?.map { pinned -> repository.takeIf { pinned } } ?: flowOf(null)
            }
            .distinctUntilChanged()

    /** Set of currently pinned top-level heads up rows to be displayed. */
    val pinnedHeadsUpRows: Flow<Set<HeadsUpRowKey>> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(emptySet())
        } else {
            headsUpRepository.activeHeadsUpRows.flatMapLatest { repositories ->
                if (repositories.isNotEmpty()) {
                    val toCombine: List<Flow<Pair<HeadsUpRowRepository, Boolean>>> =
                        repositories.map { repo ->
                            repo.isPinned.map { isPinned -> repo to isPinned }
                        }
                    combine(toCombine) { pairs ->
                        pairs.filter { (_, isPinned) -> isPinned }.map { (repo, _) -> repo }.toSet()
                    }
                } else {
                    // if the set is empty, there are no flows to combine
                    flowOf(emptySet())
                }
            }
        }
    }

    /** Are there any pinned heads up rows to display? */
    val hasPinnedRows: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            headsUpRepository.activeHeadsUpRows.flatMapLatest { rows ->
                if (rows.isNotEmpty()) {
                    combine(rows.map { it.isPinned }) { pins -> pins.any { it } }
                } else {
                    // if the set is empty, there are no flows to combine
                    flowOf(false)
                }
            }
        }
    }

    val isHeadsUpOrAnimatingAway: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            combine(hasPinnedRows, headsUpRepository.isHeadsUpAnimatingAway) {
                hasPinnedRows,
                animatingAway ->
                hasPinnedRows || animatingAway
            }
        }
    }

    private val canShowHeadsUp: Flow<Boolean> =
        combine(
            faceAuthInteractor.isBypassEnabled,
            shadeInteractor.isShadeFullyCollapsed,
            keyguardTransitionInteractor.currentKeyguardState,
            notificationsKeyguardInteractor.areNotificationsFullyHidden,
        ) { isBypassEnabled, isShadeCollapsed, keyguardState, areNotificationsHidden ->
            val isOnLockScreen = keyguardState == KeyguardState.LOCKSCREEN
            when {
                areNotificationsHidden -> false // don't show when notification are hidden
                !isShadeCollapsed -> false // don't show when the shade is expanded
                isOnLockScreen -> isBypassEnabled // on the lock screen only show for bypass
                else -> true // show otherwise
            }
        }

    val showHeadsUpStatusBar: Flow<Boolean> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(false)
        } else {
            combine(hasPinnedRows, canShowHeadsUp) { hasPinnedRows, canShowHeadsUp ->
                hasPinnedRows && canShowHeadsUp
            }
        }
    }

    fun headsUpRow(key: HeadsUpRowKey): HeadsUpRowInteractor =
        HeadsUpRowInteractor(key as HeadsUpRowRepository)

    fun elementKeyFor(key: HeadsUpRowKey) = (key as HeadsUpRowRepository).elementKey

    /** Returns the Notification Key (the standard string) of this row. */
    fun notificationKey(key: HeadsUpRowKey): String = (key as HeadsUpRowRepository).key

    fun setHeadsUpAnimatingAway(animatingAway: Boolean) {
        headsUpRepository.setHeadsUpAnimatingAway(animatingAway)
    }

    /** Snooze the currently pinned HUN. */
    fun snooze() {
        headsUpRepository.snooze()
    }

    /** Unpin all currently pinned HUNs. */
    fun unpinAll(userUnPinned: Boolean) {
        headsUpRepository.unpinAll(userUnPinned)
    }

    /** Notifies that the current scene transition is idle. */
    fun onTransitionIdle() {
        headsUpRepository.releaseAfterExpansion()
    }
}

class HeadsUpRowInteractor(repository: HeadsUpRowRepository)
