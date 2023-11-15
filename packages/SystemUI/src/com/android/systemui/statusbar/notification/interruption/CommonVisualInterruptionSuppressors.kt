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

package com.android.systemui.statusbar.notification.interruption

import android.app.Notification.BubbleMetadata
import android.app.Notification.VISIBILITY_PRIVATE
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.database.ContentObserver
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.PowerManager
import android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED
import android.provider.Settings.Global.HEADS_UP_OFF
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.MAX_HUN_WHEN_AGE_MS
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.time.SystemClock

class PeekDisabledSuppressor(
    private val globalSettings: GlobalSettings,
    private val headsUpManager: HeadsUpManager,
    private val logger: VisualInterruptionDecisionLogger,
    @Main private val mainHandler: Handler,
) : VisualInterruptionCondition(types = setOf(PEEK), reason = "peek disabled by global setting") {
    private var isEnabled = false

    override fun shouldSuppress(): Boolean = !isEnabled

    override fun start() {
        val observer =
            object : ContentObserver(mainHandler) {
                override fun onChange(selfChange: Boolean) {
                    val wasEnabled = isEnabled

                    isEnabled =
                        globalSettings.getInt(HEADS_UP_NOTIFICATIONS_ENABLED, HEADS_UP_OFF) !=
                            HEADS_UP_OFF

                    // QQQ: Do we want to log this even if it hasn't changed?
                    logger.logHeadsUpFeatureChanged(isEnabled)

                    // QQQ: Is there a better place for this side effect? What if HeadsUpManager
                    // registered for it directly?
                    if (wasEnabled && !isEnabled) {
                        logger.logWillDismissAll()
                        headsUpManager.releaseAllImmediately()
                    }
                }
            }

        globalSettings.registerContentObserver(
            globalSettings.getUriFor(HEADS_UP_NOTIFICATIONS_ENABLED),
            /* notifyForDescendants = */ true,
            observer
        )

        // QQQ: Do we need to register for SETTING_HEADS_UP_TICKER? It seems unused.

        observer.onChange(/* selfChange = */ true)
    }
}

class PulseDisabledSuppressor(
    private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
    private val userTracker: UserTracker,
) : VisualInterruptionCondition(types = setOf(PULSE), reason = "pulse disabled by user setting") {
    override fun shouldSuppress(): Boolean =
        !ambientDisplayConfiguration.pulseOnNotificationEnabled(userTracker.userId)
}

class PulseBatterySaverSuppressor(private val batteryController: BatteryController) :
    VisualInterruptionCondition(types = setOf(PULSE), reason = "pulse disabled by battery saver") {
    override fun shouldSuppress() = batteryController.isAodPowerSave()
}

class PeekPackageSnoozedSuppressor(private val headsUpManager: HeadsUpManager) :
    VisualInterruptionFilter(types = setOf(PEEK), reason = "package snoozed") {
    override fun shouldSuppress(entry: NotificationEntry) =
        when {
            // Assume any notification with an FSI is time-sensitive (like an alarm or incoming
            // call) and ignore whether HUNs have been snoozed for the package.
            entry.sbn.notification.fullScreenIntent != null -> false

            // Otherwise, check if the package is snoozed.
            else -> headsUpManager.isSnoozed(entry.sbn.packageName)
        }
}

class PeekAlreadyBubbledSuppressor(private val statusBarStateController: StatusBarStateController) :
    VisualInterruptionFilter(types = setOf(PEEK), reason = "already bubbled") {
    override fun shouldSuppress(entry: NotificationEntry) =
        when {
            statusBarStateController.state != SHADE -> false
            else -> entry.isBubble
        }
}

class PeekDndSuppressor() :
    VisualInterruptionFilter(types = setOf(PEEK), reason = "suppressed by DND") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.shouldSuppressPeek()
}

class PeekNotImportantSuppressor() :
    VisualInterruptionFilter(types = setOf(PEEK), reason = "importance < HIGH") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.importance < IMPORTANCE_HIGH
}

class PeekDeviceNotInUseSuppressor(
    private val powerManager: PowerManager,
    private val statusBarStateController: StatusBarStateController
) : VisualInterruptionCondition(types = setOf(PEEK), reason = "device not in use") {
    override fun shouldSuppress() =
        when {
            !powerManager.isScreenOn || statusBarStateController.isDreaming -> true
            else -> false
        }
}

class PeekOldWhenSuppressor(private val systemClock: SystemClock) :
    VisualInterruptionFilter(types = setOf(PEEK), reason = "has old `when`") {
    private fun whenAge(entry: NotificationEntry) =
        systemClock.currentTimeMillis() - entry.sbn.notification.`when`

    override fun shouldSuppress(entry: NotificationEntry): Boolean =
        when {
            // Ignore a "when" of 0, as it is unlikely to be a meaningful timestamp.
            entry.sbn.notification.`when` <= 0L -> false

            // Assume all HUNs with FSIs, foreground services, or user-initiated jobs are
            // time-sensitive, regardless of their "when".
            entry.sbn.notification.fullScreenIntent != null ||
                entry.sbn.notification.isForegroundService ||
                entry.sbn.notification.isUserInitiatedJob -> false

            // Otherwise, check if the HUN's "when" is too old.
            else -> whenAge(entry) >= MAX_HUN_WHEN_AGE_MS
        }
}

class PulseEffectSuppressor() :
    VisualInterruptionFilter(types = setOf(PULSE), reason = "suppressed by DND") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.shouldSuppressAmbient()
}

class PulseLockscreenVisibilityPrivateSuppressor() :
    VisualInterruptionFilter(
        types = setOf(PULSE),
        reason = "hidden by lockscreen visibility override"
    ) {
    override fun shouldSuppress(entry: NotificationEntry) =
        entry.ranking.lockscreenVisibilityOverride == VISIBILITY_PRIVATE
}

class PulseLowImportanceSuppressor() :
    VisualInterruptionFilter(types = setOf(PULSE), reason = "importance < DEFAULT") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.importance < IMPORTANCE_DEFAULT
}

class HunGroupAlertBehaviorSuppressor() :
    VisualInterruptionFilter(
        types = setOf(PEEK, PULSE),
        reason = "suppressive group alert behavior"
    ) {
    override fun shouldSuppress(entry: NotificationEntry) =
        entry.sbn.let { it.isGroup && it.notification.suppressAlertingDueToGrouping() }
}

class HunJustLaunchedFsiSuppressor() :
    VisualInterruptionFilter(types = setOf(PEEK, PULSE), reason = "just launched FSI") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.hasJustLaunchedFullScreenIntent()
}

class BubbleNotAllowedSuppressor() :
    VisualInterruptionFilter(types = setOf(BUBBLE), reason = "cannot bubble") {
    override fun shouldSuppress(entry: NotificationEntry) = !entry.canBubble()
}

class BubbleNoMetadataSuppressor() :
    VisualInterruptionFilter(types = setOf(BUBBLE), reason = "has no or invalid bubble metadata") {

    private fun isValidMetadata(metadata: BubbleMetadata?) =
        metadata != null && (metadata.intent != null || metadata.shortcutId != null)

    override fun shouldSuppress(entry: NotificationEntry) = !isValidMetadata(entry.bubbleMetadata)
}

class AlertKeyguardVisibilitySuppressor(
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider
) : VisualInterruptionFilter(types = setOf(PEEK, PULSE, BUBBLE), reason = "hidden on keyguard") {
    override fun shouldSuppress(entry: NotificationEntry) =
        keyguardNotificationVisibilityProvider.shouldHideNotification(entry)
}
