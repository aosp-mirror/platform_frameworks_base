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

package com.android.systemui.statusbar.notification.stack.ui.view

import android.service.notification.NotificationListenerService
import androidx.annotation.VisibleForTesting
import com.android.internal.statusbar.IStatusBarService
import com.android.internal.statusbar.NotificationVisibility
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.dagger.qualifiers.Background
import com.android.systemui.statusbar.notification.logging.NotificationPanelLogger
import com.android.systemui.statusbar.notification.logging.nano.Notifications
import com.android.systemui.statusbar.notification.shared.ActiveNotificationModel
import com.android.systemui.statusbar.notification.stack.ExpandableViewState
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@VisibleForTesting const val UNKNOWN_RANK = -1

@SysUISingleton
class NotificationStatsLoggerImpl
@Inject
constructor(
    @Application private val applicationScope: CoroutineScope,
    @Background private val bgDispatcher: CoroutineDispatcher,
    private val notificationListenerService: NotificationListenerService,
    private val notificationPanelLogger: NotificationPanelLogger,
    private val statusBarService: IStatusBarService,
) : NotificationStatsLogger {
    private val lastLoggedVisibilities = mutableMapOf<String, VisibilityState>()
    private var logVisibilitiesJob: Job? = null

    private val expansionStates: MutableMap<String, ExpansionState> =
        ConcurrentHashMap<String, ExpansionState>()
    private val lastReportedExpansionValues: MutableMap<String, Boolean> =
        ConcurrentHashMap<String, Boolean>()

    override fun onNotificationListUpdated(
        locationsProvider: Callable<Map<String, Int>>,
        notificationRanks: Map<String, Int>,
    ) {
        if (logVisibilitiesJob?.isActive == true) {
            return
        }

        logVisibilitiesJob =
            startLogVisibilitiesJob(
                newVisibilities =
                    combine(
                        visibilities = locationsProvider.call(),
                        rankingsMap = notificationRanks
                    ),
                activeNotifCount = notificationRanks.size,
            )
    }

    override fun onNotificationExpansionChanged(
        key: String,
        isExpanded: Boolean,
        location: Int,
        isUserAction: Boolean,
    ) {
        val expansionState =
            ExpansionState(
                key = key,
                isExpanded = isExpanded,
                isUserAction = isUserAction,
                location = location,
            )
        expansionStates[key] = expansionState
        maybeLogNotificationExpansionChange(expansionState)
    }

    private fun maybeLogNotificationExpansionChange(expansionState: ExpansionState) {
        if (expansionState.visible.not()) {
            // Only log visible expansion changes
            return
        }

        val loggedExpansionValue: Boolean? = lastReportedExpansionValues[expansionState.key]
        if (loggedExpansionValue == null && !expansionState.isExpanded) {
            // Consider the Notification initially collapsed, so only expanded is logged in the
            // first time.
            return
        }

        if (loggedExpansionValue != null && loggedExpansionValue == expansionState.isExpanded) {
            // We have already logged this state, don't log it again
            return
        }

        logNotificationExpansionChange(expansionState)
        lastReportedExpansionValues[expansionState.key] = expansionState.isExpanded
    }

    private fun logNotificationExpansionChange(expansionState: ExpansionState) =
        applicationScope.launch {
            withContext(bgDispatcher) {
                statusBarService.onNotificationExpansionChanged(
                    /* key = */ expansionState.key,
                    /* userAction = */ expansionState.isUserAction,
                    /* expanded = */ expansionState.isExpanded,
                    /* notificationLocation = */ expansionState.location
                        .toNotificationLocation()
                        .ordinal
                )
            }
        }

    override fun onLockscreenOrShadeInteractive(
        isOnLockScreen: Boolean,
        activeNotifications: List<ActiveNotificationModel>,
    ) {
        applicationScope.launch {
            withContext(bgDispatcher) {
                notificationPanelLogger.logPanelShown(
                    isOnLockScreen,
                    activeNotifications.toNotificationProto()
                )
            }
        }
    }

    override fun onLockscreenOrShadeNotInteractive(
        activeNotifications: List<ActiveNotificationModel>
    ) {
        logVisibilitiesJob =
            startLogVisibilitiesJob(
                newVisibilities = emptyMap(),
                activeNotifCount = activeNotifications.size
            )
    }

    // TODO(b/308623704) wire this in with NotifPipeline updates
    override fun onNotificationRemoved(key: String) {
        // No need to track expansion states for Notifications that are removed.
        expansionStates.remove(key)
        lastReportedExpansionValues.remove(key)
    }

    // TODO(b/308623704) wire this in with NotifPipeline updates
    override fun onNotificationUpdated(key: String) {
        // When the Notification is updated, we should consider it as not yet logged.
        lastReportedExpansionValues.remove(key)
    }

    private fun combine(
        visibilities: Map<String, Int>,
        rankingsMap: Map<String, Int>
    ): Map<String, VisibilityState> =
        visibilities.mapValues { entry ->
            VisibilityState(entry.key, entry.value, rankingsMap[entry.key] ?: UNKNOWN_RANK)
        }

    private fun startLogVisibilitiesJob(
        newVisibilities: Map<String, VisibilityState>,
        activeNotifCount: Int,
    ) =
        applicationScope.launch {
            val newlyVisible = newVisibilities - lastLoggedVisibilities.keys
            val noLongerVisible = lastLoggedVisibilities - newVisibilities.keys

            maybeLogVisibilityChanges(newlyVisible, noLongerVisible, activeNotifCount)
            updateExpansionStates(newlyVisible, noLongerVisible)

            lastLoggedVisibilities.clear()
            lastLoggedVisibilities.putAll(newVisibilities)
        }

    private suspend fun maybeLogVisibilityChanges(
        newlyVisible: Map<String, VisibilityState>,
        noLongerVisible: Map<String, VisibilityState>,
        activeNotifCount: Int,
    ) {
        if (newlyVisible.isEmpty() && noLongerVisible.isEmpty()) {
            return
        }

        val newlyVisibleAr =
            newlyVisible.mapToNotificationVisibilitiesAr(visible = true, count = activeNotifCount)

        val noLongerVisibleAr =
            noLongerVisible.mapToNotificationVisibilitiesAr(
                visible = false,
                count = activeNotifCount
            )

        withContext(bgDispatcher) {
            statusBarService.onNotificationVisibilityChanged(newlyVisibleAr, noLongerVisibleAr)
            if (newlyVisible.isNotEmpty()) {
                notificationListenerService.setNotificationsShown(newlyVisible.keys.toTypedArray())
            }
        }
    }

    private fun updateExpansionStates(
        newlyVisible: Map<String, VisibilityState>,
        noLongerVisible: Map<String, VisibilityState>
    ) {
        expansionStates.forEach { (key, expansionState) ->
            if (newlyVisible.contains(key)) {
                val newState =
                    expansionState.copy(
                        visible = true,
                        location = newlyVisible.getValue(key).location,
                    )
                expansionStates[key] = newState
                maybeLogNotificationExpansionChange(newState)
            }

            if (noLongerVisible.contains(key)) {
                expansionStates[key] =
                    expansionState.copy(
                        visible = false,
                        location = noLongerVisible.getValue(key).location,
                    )
            }
        }
    }

    private data class VisibilityState(
        val key: String,
        val location: Int,
        val rank: Int,
    )

    private data class ExpansionState(
        val key: String,
        val isUserAction: Boolean,
        val isExpanded: Boolean,
        val visible: Boolean,
        val location: Int,
    ) {
        constructor(
            key: String,
            isExpanded: Boolean,
            location: Int,
            isUserAction: Boolean,
        ) : this(
            key = key,
            isExpanded = isExpanded,
            isUserAction = isUserAction,
            visible = isVisibleLocation(location),
            location = location,
        )
    }

    private fun Map<String, VisibilityState>.mapToNotificationVisibilitiesAr(
        visible: Boolean,
        count: Int,
    ): Array<NotificationVisibility> =
        this.map { (key, state) ->
                NotificationVisibility.obtain(
                    /* key = */ key,
                    /* rank = */ state.rank,
                    /* count = */ count,
                    /* visible = */ visible,
                    /* location = */ state.location.toNotificationLocation()
                )
            }
            .toTypedArray()
}

private fun Int.toNotificationLocation(): NotificationVisibility.NotificationLocation {
    return when (this) {
        ExpandableViewState.LOCATION_FIRST_HUN ->
            NotificationVisibility.NotificationLocation.LOCATION_FIRST_HEADS_UP
        ExpandableViewState.LOCATION_HIDDEN_TOP ->
            NotificationVisibility.NotificationLocation.LOCATION_HIDDEN_TOP
        ExpandableViewState.LOCATION_MAIN_AREA ->
            NotificationVisibility.NotificationLocation.LOCATION_MAIN_AREA
        ExpandableViewState.LOCATION_BOTTOM_STACK_PEEKING ->
            NotificationVisibility.NotificationLocation.LOCATION_BOTTOM_STACK_PEEKING
        ExpandableViewState.LOCATION_BOTTOM_STACK_HIDDEN ->
            NotificationVisibility.NotificationLocation.LOCATION_BOTTOM_STACK_HIDDEN
        ExpandableViewState.LOCATION_GONE ->
            NotificationVisibility.NotificationLocation.LOCATION_GONE
        else -> NotificationVisibility.NotificationLocation.LOCATION_UNKNOWN
    }
}

private fun List<ActiveNotificationModel>.toNotificationProto(): Notifications.NotificationList {
    val notificationList = Notifications.NotificationList()
    val protoArray: Array<Notifications.Notification> =
        map { notification ->
                Notifications.Notification().apply {
                    uid = notification.uid
                    packageName = notification.packageName
                    notification.instanceId?.let { instanceId = it }
                    // TODO(b/308623704) check if we can set groupInstanceId as well
                    isGroupSummary = notification.isGroupSummary
                    section = NotificationPanelLogger.toNotificationSection(notification.bucket)
                }
            }
            .toTypedArray()

    if (protoArray.isNotEmpty()) {
        notificationList.notifications = protoArray
    }

    return notificationList
}

private fun isVisibleLocation(location: Int): Boolean =
    location and ExpandableViewState.VISIBLE_LOCATIONS != 0
