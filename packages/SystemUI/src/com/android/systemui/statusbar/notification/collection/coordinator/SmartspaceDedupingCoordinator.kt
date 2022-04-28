/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.app.smartspace.SmartspaceTarget
import android.os.Parcelable
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.SysuiStatusBarStateController
import com.android.systemui.statusbar.lockscreen.LockscreenSmartspaceController
import com.android.systemui.statusbar.notification.NotificationEntryManager
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.notification.collection.notifcollection.NotifCollectionListener
import com.android.systemui.util.concurrency.DelayableExecutor
import com.android.systemui.util.time.SystemClock
import java.util.concurrent.TimeUnit.SECONDS
import javax.inject.Inject

/**
 * Hides notifications on the lockscreen if the content of those notifications is also visible
 * in smartspace. This ONLY hides the notifications on the lockscreen: if the user pulls the shade
 * down or unlocks the device, then the notifications are unhidden.
 *
 * In addition, notifications that have recently alerted aren't filtered. Tracking this in a way
 * that involves the fewest pipeline invalidations requires some unfortunately complex logic.
 */
// This class is a singleton so that the same instance can be accessed by both the old and new
// pipelines
@CoordinatorScope
class SmartspaceDedupingCoordinator @Inject constructor(
    private val statusBarStateController: SysuiStatusBarStateController,
    private val smartspaceController: LockscreenSmartspaceController,
    private val notificationEntryManager: NotificationEntryManager,
    private val notificationLockscreenUserManager: NotificationLockscreenUserManager,
    private val notifPipeline: NotifPipeline,
    @Main private val executor: DelayableExecutor,
    private val clock: SystemClock
) : Coordinator {
    private var isOnLockscreen = false

    private var trackedSmartspaceTargets = mutableMapOf<String, TrackedSmartspaceTarget>()

    override fun attach(pipeline: NotifPipeline) {
        pipeline.addPreGroupFilter(filter)
        pipeline.addCollectionListener(collectionListener)
        statusBarStateController.addCallback(statusBarStateListener)
        smartspaceController.addListener(this::onNewSmartspaceTargets)

        if (!pipeline.isNewPipelineEnabled) {
            // TODO (b/173126564): Remove this once the old pipeline is no longer necessary
            notificationLockscreenUserManager.addKeyguardNotificationSuppressor { entry ->
                isDupedWithSmartspaceContent(entry)
            }
        }

        recordStatusBarState(statusBarStateController.state)
    }

    private fun isDupedWithSmartspaceContent(entry: NotificationEntry): Boolean {
        return trackedSmartspaceTargets[entry.key]?.shouldFilter ?: false
    }

    private val filter = object : NotifFilter("SmartspaceDedupingFilter") {
        override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
            return isOnLockscreen && isDupedWithSmartspaceContent(entry)
        }
    }

    private val collectionListener = object : NotifCollectionListener {
        override fun onEntryAdded(entry: NotificationEntry) {
            trackedSmartspaceTargets[entry.key]?.let {
                updateFilterStatus(it)
            }
        }

        override fun onEntryUpdated(entry: NotificationEntry) {
            trackedSmartspaceTargets[entry.key]?.let {
                updateFilterStatus(it)
            }
        }

        override fun onEntryRemoved(entry: NotificationEntry, reason: Int) {
            trackedSmartspaceTargets[entry.key]?.let { trackedTarget ->
                cancelExceptionTimeout(trackedTarget)
            }
        }
    }

    private val statusBarStateListener = object : StatusBarStateController.StateListener {
        override fun onStateChanged(newState: Int) {
            recordStatusBarState(newState)
        }
    }

    private fun onNewSmartspaceTargets(targets: List<Parcelable>) {
        var changed = false
        val newMap = mutableMapOf<String, TrackedSmartspaceTarget>()
        val oldMap = trackedSmartspaceTargets

        for (target in targets) {
            // For all targets that are SmartspaceTargets and have non-null sourceNotificationKeys
            (target as? SmartspaceTarget)?.sourceNotificationKey?.let { key ->
                val trackedTarget = oldMap.getOrElse(key) {
                    TrackedSmartspaceTarget(key)
                }
                newMap[key] = trackedTarget
                changed = changed || updateFilterStatus(trackedTarget)
            }
            // Currently, only filter out the first target
            break
        }

        for (prevKey in oldMap.keys) {
            if (!newMap.containsKey(prevKey)) {
                oldMap[prevKey]?.cancelTimeoutRunnable?.run()
                changed = true
            }
        }

        if (changed) {
            filter.invalidateList()
            notificationEntryManager.updateNotifications("Smartspace targets changed")
        }

        trackedSmartspaceTargets = newMap
    }

    /**
     * Returns true if the target's alert exception status has changed
     */
    private fun updateFilterStatus(target: TrackedSmartspaceTarget): Boolean {
        val prevShouldFilter = target.shouldFilter

        val entry = notifPipeline.getEntry(target.key)
        if (entry != null) {
            updateAlertException(target, entry)

            target.shouldFilter = !hasRecentlyAlerted(entry)
        }

        return target.shouldFilter != prevShouldFilter && isOnLockscreen
    }

    private fun updateAlertException(target: TrackedSmartspaceTarget, entry: NotificationEntry) {
        val now = clock.currentTimeMillis()
        val alertExceptionExpires = entry.ranking.lastAudiblyAlertedMillis + ALERT_WINDOW

        if (alertExceptionExpires != target.alertExceptionExpires &&
                alertExceptionExpires > now) {
            // If we got here, the target is subject to a new alert exception window, so we
            // should update our timeout to fire at the end of the new window

            target.cancelTimeoutRunnable?.run()
            target.alertExceptionExpires = alertExceptionExpires
            target.cancelTimeoutRunnable = executor.executeDelayed({
                target.cancelTimeoutRunnable = null
                target.shouldFilter = true
                filter.invalidateList()
                notificationEntryManager.updateNotifications("deduping timeout expired")
            }, alertExceptionExpires - now)
        }
    }

    private fun cancelExceptionTimeout(target: TrackedSmartspaceTarget) {
        target.cancelTimeoutRunnable?.run()
        target.cancelTimeoutRunnable = null
        target.alertExceptionExpires = 0
    }

    private fun recordStatusBarState(newState: Int) {
        val wasOnLockscreen = isOnLockscreen
        isOnLockscreen = newState == StatusBarState.KEYGUARD

        if (isOnLockscreen != wasOnLockscreen) {
            filter.invalidateList()
            // No need to call notificationEntryManager.updateNotifications; something else already
            // does it for us when the keyguard state changes
        }
    }

    private fun hasRecentlyAlerted(entry: NotificationEntry): Boolean {
        return clock.currentTimeMillis() - entry.ranking.lastAudiblyAlertedMillis <= ALERT_WINDOW
    }
}

private class TrackedSmartspaceTarget(
    val key: String
) {
    var cancelTimeoutRunnable: Runnable? = null
    var alertExceptionExpires: Long = 0
    var shouldFilter = false
}

private val ALERT_WINDOW = SECONDS.toMillis(30)
