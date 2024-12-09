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

import android.app.Notification
import android.os.UserHandle
import com.android.keyguard.KeyguardUpdateMonitor
import com.android.server.notification.Flags.screenshareNotificationHiding
import com.android.systemui.dagger.qualifiers.Application
import com.android.systemui.deviceentry.domain.interactor.DeviceEntryInteractor
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.scene.domain.interactor.SceneInteractor
import com.android.systemui.scene.shared.flag.SceneContainerFlag
import com.android.systemui.scene.shared.model.Scenes
import com.android.systemui.statusbar.NotificationLockscreenUserManager
import com.android.systemui.statusbar.StatusBarState
import com.android.systemui.statusbar.notification.DynamicPrivacyController
import com.android.systemui.statusbar.notification.collection.GroupEntry
import com.android.systemui.statusbar.notification.collection.ListEntry
import com.android.systemui.statusbar.notification.collection.NotifPipeline
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.collection.coordinator.dagger.CoordinatorScope
import com.android.systemui.statusbar.notification.collection.listbuilder.OnBeforeRenderListListener
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.Invalidator
import com.android.systemui.statusbar.notification.collection.listbuilder.pluggable.NotifFilter
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.statusbar.policy.SensitiveNotificationProtectionController
import com.android.systemui.user.domain.interactor.SelectedUserInteractor
import dagger.Binds
import dagger.Module
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.mapNotNull
import com.android.app.tracing.coroutines.launchTraced as launch

@Module(includes = [PrivateSensitiveContentCoordinatorModule::class])
interface SensitiveContentCoordinatorModule

@Module
interface PrivateSensitiveContentCoordinatorModule {
    @Binds fun bindCoordinator(impl: SensitiveContentCoordinatorImpl): SensitiveContentCoordinator
}

/** Coordinates re-inflation and post-processing of sensitive notification content. */
interface SensitiveContentCoordinator : Coordinator

@CoordinatorScope
class SensitiveContentCoordinatorImpl
@Inject
constructor(
    private val dynamicPrivacyController: DynamicPrivacyController,
    private val lockscreenUserManager: NotificationLockscreenUserManager,
    private val keyguardUpdateMonitor: KeyguardUpdateMonitor,
    private val statusBarStateController: StatusBarStateController,
    private val keyguardStateController: KeyguardStateController,
    private val selectedUserInteractor: SelectedUserInteractor,
    private val sensitiveNotificationProtectionController:
        SensitiveNotificationProtectionController,
    private val deviceEntryInteractor: DeviceEntryInteractor,
    private val sceneInteractor: SceneInteractor,
    @Application private val scope: CoroutineScope,
) :
    Invalidator("SensitiveContentInvalidator"),
    SensitiveContentCoordinator,
    DynamicPrivacyController.Listener,
    OnBeforeRenderListListener {
    private var inTransitionFromLockedToGone = false

    private val onSensitiveStateChanged = Runnable() { invalidateList("onSensitiveStateChanged") }

    private val screenshareSecretFilter =
        object : NotifFilter("ScreenshareSecretFilter") {
            val NotificationEntry.isSecret
                get() =
                    channel?.lockscreenVisibility == Notification.VISIBILITY_SECRET ||
                        sbn.notification?.visibility == Notification.VISIBILITY_SECRET

            override fun shouldFilterOut(entry: NotificationEntry, now: Long): Boolean {
                return screenshareNotificationHiding() &&
                    sensitiveNotificationProtectionController.isSensitiveStateActive &&
                    entry.isSecret
            }
        }

    override fun attach(pipeline: NotifPipeline) {
        dynamicPrivacyController.addListener(this)
        if (screenshareNotificationHiding()) {
            sensitiveNotificationProtectionController.registerSensitiveStateListener(
                onSensitiveStateChanged
            )
        }
        pipeline.addOnBeforeRenderListListener(this)
        pipeline.addPreRenderInvalidator(this)
        if (screenshareNotificationHiding()) {
            pipeline.addFinalizeFilter(screenshareSecretFilter)
        }

        if (SceneContainerFlag.isEnabled) {
            scope.launch {
                sceneInteractor.transitionState
                    .mapNotNull {
                        val transitioningToGone = it.isTransitioning(to = Scenes.Gone)
                        val deviceEntered = deviceEntryInteractor.isDeviceEntered.value
                        when {
                            transitioningToGone && !deviceEntered -> true
                            !transitioningToGone -> false
                            else -> null
                        }
                    }
                    .distinctUntilChanged()
                    .collect {
                        inTransitionFromLockedToGone = it
                        invalidateList("inTransitionFromLockedToGoneChanged")
                    }
            }
        }
    }

    override fun onDynamicPrivacyChanged(): Unit = invalidateList("onDynamicPrivacyChanged")

    private val isKeyguardGoingAway: Boolean
        get() {
            if (SceneContainerFlag.isEnabled) {
                return inTransitionFromLockedToGone
            } else {
                return keyguardStateController.isKeyguardGoingAway
            }
        }

    override fun onBeforeRenderList(entries: List<ListEntry>) {
        if (
            isKeyguardGoingAway ||
                statusBarStateController.state == StatusBarState.KEYGUARD &&
                    keyguardUpdateMonitor.getUserUnlockedWithBiometricAndIsBypassing(
                        selectedUserInteractor.getSelectedUserId()
                    )
        ) {
            // don't update yet if:
            // - the keyguard is currently going away
            // - LS is about to be dismissed by a biometric that bypasses LS (avoid notif flash)

            // TODO(b/206118999): merge this class with KeyguardCoordinator which ensures the
            // dependent state changes invalidate the pipeline
            return
        }

        val isSensitiveContentProtectionActive =
            screenshareNotificationHiding() &&
                sensitiveNotificationProtectionController.isSensitiveStateActive
        val currentUserId = lockscreenUserManager.currentUserId
        val devicePublic = lockscreenUserManager.isLockscreenPublicMode(currentUserId)
        val deviceSensitive =
            (devicePublic &&
                !lockscreenUserManager.userAllowsPrivateNotificationsInPublic(currentUserId)) ||
                isSensitiveContentProtectionActive
        val dynamicallyUnlocked = dynamicPrivacyController.isDynamicallyUnlocked
        for (entry in extractAllRepresentativeEntries(entries).filter { it.rowExists() }) {
            val notifUserId = entry.sbn.user.identifier
            val userLockscreen =
                devicePublic || lockscreenUserManager.isLockscreenPublicMode(notifUserId)
            val userPublic =
                when {
                    // if we're not on the lockscreen, we're definitely private
                    !userLockscreen -> false
                    // we are on the lockscreen, so unless we're dynamically unlocked, we're
                    // definitely public
                    !dynamicallyUnlocked -> true
                    // we're dynamically unlocked, but check if the notification needs
                    // a separate challenge if it's from a work profile
                    else ->
                        when (notifUserId) {
                            currentUserId -> false
                            UserHandle.USER_ALL -> false
                            else -> lockscreenUserManager.needsSeparateWorkChallenge(notifUserId)
                        }
                }

            val shouldProtectNotification =
                screenshareNotificationHiding() &&
                    sensitiveNotificationProtectionController.shouldProtectNotification(entry)

            val needsRedaction = lockscreenUserManager.needsRedaction(entry)
            val isSensitive = userPublic && needsRedaction
            entry.setSensitive(isSensitive || shouldProtectNotification, deviceSensitive)
            if (screenshareNotificationHiding()) {
                entry.row?.setPublicExpanderVisible(!shouldProtectNotification)
            }
        }
    }
}

private fun extractAllRepresentativeEntries(entries: List<ListEntry>): Sequence<NotificationEntry> =
    entries.asSequence().flatMap(::extractAllRepresentativeEntries)

private fun extractAllRepresentativeEntries(listEntry: ListEntry): Sequence<NotificationEntry> =
    sequence {
        listEntry.representativeEntry?.let { yield(it) }
        if (listEntry is GroupEntry) {
            yieldAll(extractAllRepresentativeEntries(listEntry.children))
        }
    }
