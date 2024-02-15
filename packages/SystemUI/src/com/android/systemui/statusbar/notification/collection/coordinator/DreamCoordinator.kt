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

package com.android.systemui.statusbar.notification.collection.coordinator

import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.keyguard.data.repository.KeyguardRepository
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Filter out notifications on the lockscreen if the lockscreen hosted dream is active. If the user
 * stops dreaming, pulls the shade down or unlocks the device, then the notifications are unhidden.
 */
@CoordinatorScope
class DreamCoordinator
@Inject
constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    @Application private val scope: CoroutineScope,
    private val keyguardRepository: KeyguardRepository,
) : Coordinator {
    private var isOnKeyguard = false
    private var isLockscreenHostedDream = false

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addPreGroupFilter(filter)
        statusBarStateController.addCallback(statusBarStateListener)
        scope.launch { attachFilterOnDreamingStateChange() }
        recordStatusBarState(statusBarStateController.state)
    }

    private val filter =
        object : NotifFilter("LockscreenHostedDreamFilter") {
            var isFiltering = false
            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
                return isFiltering
            }
            inline fun update(msg: () -> String) {
                val wasFiltering = isFiltering
                isFiltering = isLockscreenHostedDream && isOnKeyguard
                if (wasFiltering != isFiltering) {
                    invalidateList(msg())
                }
            }
        }

    private val statusBarStateListener =
        object : StatusBarStateController.StateListener {
            override fun onStateChanged(newState: Int) {
                recordStatusBarState(newState)
            }
        }

    private suspend fun attachFilterOnDreamingStateChange() {
        keyguardRepository.isActiveDreamLockscreenHosted.collect { isDreaming ->
            recordDreamingState(isDreaming)
        }
    }

    private fun recordStatusBarState(newState: Int) {
        isOnKeyguard = newState == StatusBarState.KEYGUARD
        filter.update { "recordStatusBarState: " + StatusBarState.toString(newState) }
    }

    private fun recordDreamingState(isDreaming: Boolean) {
        isLockscreenHostedDream = isDreaming
        filter.update { "recordLockscreenHostedDreamState: $isDreaming" }
    }
}
