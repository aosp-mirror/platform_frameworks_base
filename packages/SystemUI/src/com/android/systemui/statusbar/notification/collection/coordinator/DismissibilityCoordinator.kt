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

import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.provider.NotificationDismissibilityProviderImpl
import com.android.systemui.statusbar.policy.KeyguardStateController
import javax.inject.Inject

/** Decides if a Notification can be dismissed by the user. */
@CoordinatorScope
class DismissibilityCoordinator
@Inject
constructor(
    private val keyguardStateController: KeyguardStateController,
    private val provider: NotificationDismissibilityProviderImpl
) : Coordinator {

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addOnBeforeRenderListListener(::onBeforeRenderListListener)
    }

    private fun onBeforeRenderListListener(entries: List<ListEntry>) {
        val isLocked = !keyguardStateController.isUnlocked
        val nonDismissableEntryKeys = mutableSetOf<String>()
        markNonDismissibleEntries(nonDismissableEntryKeys, entries, isLocked)
        provider.update(nonDismissableEntryKeys)
    }

    /**
     * Visits every entry and its children to mark the dismissible entries.
     *
     * @param markedKeys set to store the marked entry keys
     * @param entries to visit
     * @param isLocked the locked state of the device
     * @return true if any of the entries were marked as non-dismissible.
     */
    private fun markNonDismissibleEntries(
        markedKeys: MutableSet<String>,
        entries: List<ListEntry>,
        isLocked: Boolean
    ): Boolean {
        var anyNonDismissableEntries = false

        for (entry in entries) {
            entry.representativeEntry?.let { notifEntry ->
                // mark the entry if it is non-dismissible
                if (!notifEntry.isDismissableForState(isLocked)) {
                    markedKeys.add(notifEntry.key)
                    anyNonDismissableEntries = true
                }
            }

            if (entry is GroupEntry) {
                if (markNonDismissibleEntries(markedKeys, entry.children, isLocked)) {
                    // if any child is non-dismissible, mark the parent as well
                    entry.representativeEntry?.let { markedKeys.add(it.key) }
                    anyNonDismissableEntries = true
                }
            }
        }

        return anyNonDismissableEntries
    }
}
