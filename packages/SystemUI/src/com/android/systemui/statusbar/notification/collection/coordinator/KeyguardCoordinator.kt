/*
 * Copyright (C) 2022 The Android Open Source Project
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

import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.provider.SectionHeaderVisibilityProvider
import com.android.systemui.statusbar.notification.interruption.KeyguardNotificationVisibilityProvider
import javax.inject.Inject

/**
 * Filters low priority and privacy-sensitive notifications from the lockscreen, and hides section
 * headers on the lockscreen. If enabled, it will also track and hide seen notifications on the
 * lockscreen.
 */
@CoordinatorScope
class KeyguardCoordinator
@Inject
constructor(
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
    private val sectionHeaderVisibilityProvider: SectionHeaderVisibilityProvider,
    private val statusBarStateController: StatusBarStateController,
) : Coordinator {

    override fun attach(pipeline: NotifPipeline) {
        setupInvalidateNotifListCallbacks()
        // Filter at the "finalize" stage so that views remain bound by PreparationCoordinator
        pipeline.addFinalizeFilter(notifFilter)
        keyguardNotificationVisibilityProvider.addOnStateChangedListener(::invalidateListFromFilter)
        updateSectionHeadersVisibility()
    }

    private val notifFilter: NotifFilter =
        object : NotifFilter(TAG) {
            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean =
                keyguardNotificationVisibilityProvider.shouldHideNotification(entry)
        }

    // TODO(b/206118999): merge this class with SensitiveContentCoordinator which also depends on
    //  these same updates
    private fun setupInvalidateNotifListCallbacks() {}

    private fun invalidateListFromFilter(reason: String) {
        updateSectionHeadersVisibility()
        notifFilter.invalidateList(reason)
    }

    private fun updateSectionHeadersVisibility() {
        val onKeyguard = statusBarStateController.state == StatusBarState.KEYGUARD
        val neverShowSections = sectionHeaderVisibilityProvider.neverShowSectionHeaders
        val showSections = !onKeyguard && !neverShowSections
        sectionHeaderVisibilityProvider.sectionHeadersVisible = showSections
    }

    companion object {
        private const val TAG = "KeyguardCoordinator"
    }
}
