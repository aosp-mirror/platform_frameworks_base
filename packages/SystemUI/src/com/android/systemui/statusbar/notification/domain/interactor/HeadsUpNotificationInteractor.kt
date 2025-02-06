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

package com.android.systemui.statusbar.notification.domain.interactor

import com.android.systemui.deviceentry.domain.interactor.DeviceEntryFaceAuthInteractor
import com.android.systemui.keyguard.domain.interactor.KeyguardTransitionInteractor
import com.android.systemui.keyguard.shared.model.KeyguardState
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.shade.domain.interactor.ShadeInteractor
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRepository
import com.android.systemui.statusbar.notification.data.repository.HeadsUpRowRepository
import com.android.systemui.statusbar.notification.domain.model.TopPinnedState
import com.android.systemui.statusbar.notification.headsup.PinnedStatus
import com.android.systemui.statusbar.notification.shared.HeadsUpRowKey
import javax.inject.Inject
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
    faceAuthInteractor: DeviceEntryFaceAuthInteractor,
    keyguardTransitionInteractor: KeyguardTransitionInteractor,
    notificationsKeyguardInteractor: NotificationsKeyguardInteractor,
    shadeInteractor: ShadeInteractor,
) {

    /** The top-ranked heads up row, regardless of pinned state */
    val topHeadsUpRow: Flow<HeadsUpRowKey?> = headsUpRepository.topHeadsUpRow

    /** The top-ranked heads up row, if that row is pinned */
    val topHeadsUpRowIfPinned: Flow<HeadsUpRowKey?> =
        headsUpRepository.topHeadsUpRow
            .flatMapLatest { repository ->
                repository?.pinnedStatus?.map { pinnedStatus ->
                    repository.takeIf { pinnedStatus.isPinned }
                } ?: flowOf(null)
            }
            .distinctUntilChanged()

    private val activeHeadsUpRows: Flow<Set<Pair<HeadsUpRowKey, Boolean>>> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(emptySet())
        } else {
            headsUpRepository.activeHeadsUpRows.flatMapLatest { repositories ->
                if (repositories.isNotEmpty()) {
                    val toCombine: List<Flow<Pair<HeadsUpRowRepository, Boolean>>> =
                        repositories.map { repo ->
                            repo.pinnedStatus.map { pinnedStatus -> repo to pinnedStatus.isPinned }
                        }
                    combine(toCombine) { pairs -> pairs.toSet() }
                } else {
                    // if the set is empty, there are no flows to combine
                    flowOf(emptySet())
                }
            }
        }
    }

    /** Set of currently active top-level heads up rows to be displayed. */
    val activeHeadsUpRowKeys: Flow<Set<HeadsUpRowKey>> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(emptySet())
        } else {
            activeHeadsUpRows.map { it.map { (repo, _) -> repo }.toSet() }
        }
    }

    /** Set of currently pinned top-level heads up rows to be displayed. */
    val pinnedHeadsUpRowKeys: Flow<Set<HeadsUpRowKey>> by lazy {
        if (SceneContainerFlag.isUnexpectedlyInLegacyMode()) {
            flowOf(emptySet())
        } else {
            activeHeadsUpRows.map {
                it.filter { (_, isPinned) -> isPinned }.map { (repo, _) -> repo }.toSet()
            }
        }
    }

    /** What [PinnedStatus] and key does the top row have? */
    private val topPinnedState: Flow<TopPinnedState> =
        headsUpRepository.activeHeadsUpRows.flatMapLatest { rows ->
            if (rows.isNotEmpty()) {
                // For each row, emits a (key, pinnedStatus) pair each time any row's
                // `pinnedStatus` changes
                val toCombine: List<Flow<Pair<String, PinnedStatus>>> =
                    rows.map { row -> row.pinnedStatus.map { status -> row.key to status } }
                combine(toCombine) { pairs ->
                    val topPinnedRow: Pair<String, PinnedStatus>? =
                        pairs.firstOrNull { it.second.isPinned }
                    if (topPinnedRow != null) {
                        TopPinnedState.Pinned(
                            key = topPinnedRow.first,
                            status = topPinnedRow.second,
                        )
                    } else {
                        TopPinnedState.NothingPinned
                    }
                }
            } else {
                flowOf(TopPinnedState.NothingPinned)
            }
        }

    /** Are there any pinned heads up rows to display? */
    val hasPinnedRows: Flow<Boolean> =
        topPinnedState.map {
            when (it) {
                is TopPinnedState.Pinned -> it.status.isPinned
                is TopPinnedState.NothingPinned -> false
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

    /**
     * Emits the pinned notification state as it relates to the status bar. Includes both the pinned
     * status and key of the notification that's pinned (if there is a pinned notification).
     */
    val statusBarHeadsUpState: Flow<TopPinnedState> =
        combine(topPinnedState, canShowHeadsUp) { topPinnedState, canShowHeadsUp ->
            if (canShowHeadsUp) {
                topPinnedState
            } else {
                TopPinnedState.NothingPinned
            }
        }

    /** Emits the pinned notification status as it relates to the status bar. */
    val statusBarHeadsUpStatus: Flow<PinnedStatus> =
        statusBarHeadsUpState.map {
            when (it) {
                is TopPinnedState.Pinned -> it.status
                is TopPinnedState.NothingPinned -> PinnedStatus.NotPinned
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
