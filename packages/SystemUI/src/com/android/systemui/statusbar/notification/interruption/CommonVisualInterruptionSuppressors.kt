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

import android.Manifest.permission.RECEIVE_EMERGENCY_BROADCAST
import android.app.Notification
import android.app.Notification.BubbleMetadata
import android.app.Notification.CATEGORY_ALARM
import android.app.Notification.CATEGORY_CAR_EMERGENCY
import android.app.Notification.CATEGORY_CAR_WARNING
import android.app.Notification.CATEGORY_EVENT
import android.app.Notification.CATEGORY_REMINDER
import android.app.Notification.VISIBILITY_PRIVATE
import android.app.NotificationManager
import android.app.NotificationManager.IMPORTANCE_DEFAULT
import android.app.NotificationManager.IMPORTANCE_HIGH
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.PERMISSION_GRANTED
import android.database.ContentObserver
import android.hardware.display.AmbientDisplayConfiguration
import android.os.Bundle
import android.os.Handler
import android.os.PowerManager
import android.os.SystemProperties
import android.provider.Settings
import android.provider.Settings.Global.HEADS_UP_NOTIFICATIONS_ENABLED
import android.provider.Settings.Global.HEADS_UP_OFF
import android.service.notification.Flags
import com.android.internal.logging.UiEvent
import com.android.internal.logging.UiEventLogger
import com.android.internal.messages.nano.SystemMessageProto.SystemMessage
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.shared.notifications.domain.interactor.NotificationSettingsInteractor
import com.android.systemui.statusbar.StatusBarState.SHADE
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.MAX_HUN_WHEN_AGE_MS
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.HUN_SUPPRESSED_OLD_WHEN
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.NotificationChannels
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.settings.SystemSettings
import com.android.systemui.util.time.SystemClock
import com.android.wm.shell.bubbles.Bubbles
import java.util.Optional
import kotlin.jvm.optionals.getOrElse

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

        globalSettings.registerContentObserverSync(
            globalSettings.getUriFor(HEADS_UP_NOTIFICATIONS_ENABLED),
            /* notifyForDescendants = */ true,
            observer
        )

        // QQQ: Do we need to register for SETTING_HEADS_UP_TICKER? It seems unused.

        observer.onChange(/* selfChange= */ true)
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

class PeekAlreadyBubbledSuppressor(
    private val statusBarStateController: StatusBarStateController,
    private val bubbles: Optional<Bubbles>
) : VisualInterruptionFilter(types = setOf(PEEK), reason = "already bubbled") {
    override fun shouldSuppress(entry: NotificationEntry) =
        when {
            statusBarStateController.state != SHADE -> false
            else -> {
                val bubblesCanShowNotification =
                    bubbles.map { it.canShowBubbleNotification() }.getOrElse { false }
                entry.isBubble && bubblesCanShowNotification
            }
        }
}

class PeekDndSuppressor :
    VisualInterruptionFilter(types = setOf(PEEK), reason = "suppressed by DND") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.shouldSuppressPeek()
}

class PeekNotImportantSuppressor :
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
    VisualInterruptionFilter(
        types = setOf(PEEK),
        reason = "has old `when`",
        uiEventId = HUN_SUPPRESSED_OLD_WHEN
    ) {
    private fun whenAge(entry: NotificationEntry) =
        systemClock.currentTimeMillis() - entry.sbn.notification.getWhen()

    override fun shouldSuppress(entry: NotificationEntry): Boolean =
        when {
            // Ignore a "when" of 0, as it is unlikely to be a meaningful timestamp.
            entry.sbn.notification.getWhen() <= 0L -> false

            // Assume all HUNs with FSIs, foreground services, or user-initiated jobs are
            // time-sensitive, regardless of their "when".
            entry.sbn.notification.fullScreenIntent != null ||
                entry.sbn.notification.isForegroundService ||
                entry.sbn.notification.isUserInitiatedJob -> false

            // Otherwise, check if the HUN's "when" is too old.
            else -> whenAge(entry) >= MAX_HUN_WHEN_AGE_MS
        }
}

class PulseEffectSuppressor :
    VisualInterruptionFilter(types = setOf(PULSE), reason = "suppressed by DND") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.shouldSuppressAmbient()
}

class PulseLockscreenVisibilityPrivateSuppressor :
    VisualInterruptionFilter(
        types = setOf(PULSE),
        reason = "hidden by lockscreen visibility override"
    ) {
    override fun shouldSuppress(entry: NotificationEntry) =
        entry.ranking.lockscreenVisibilityOverride == VISIBILITY_PRIVATE
}

class PulseLowImportanceSuppressor :
    VisualInterruptionFilter(types = setOf(PULSE), reason = "importance < DEFAULT") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.importance < IMPORTANCE_DEFAULT
}

class HunGroupAlertBehaviorSuppressor :
    VisualInterruptionFilter(
        types = setOf(PEEK, PULSE),
        reason = "suppressive group alert behavior"
    ) {
    override fun shouldSuppress(entry: NotificationEntry) =
        entry.sbn.let { it.isGroup && it.notification.suppressAlertingDueToGrouping() }
}

class HunSilentNotificationSuppressor :
    VisualInterruptionFilter(types = setOf(PEEK, PULSE), reason = "notification isSilent") {
    override fun shouldSuppress(entry: NotificationEntry) =
        entry.sbn.let { Flags.notificationSilentFlag() && it.notification.isSilent }
}

class HunJustLaunchedFsiSuppressor :
    VisualInterruptionFilter(types = setOf(PEEK, PULSE), reason = "just launched FSI") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.hasJustLaunchedFullScreenIntent()
}

class BubbleNotAllowedSuppressor :
    VisualInterruptionFilter(types = setOf(BUBBLE), reason = "cannot bubble", isSpammy = true) {
    override fun shouldSuppress(entry: NotificationEntry) = !entry.canBubble()
}

class BubbleNoMetadataSuppressor :
    VisualInterruptionFilter(types = setOf(BUBBLE), reason = "has no or invalid bubble metadata") {

    private fun isValidMetadata(metadata: BubbleMetadata?) =
        metadata != null && (metadata.intent != null || metadata.shortcutId != null)

    override fun shouldSuppress(entry: NotificationEntry) = !isValidMetadata(entry.bubbleMetadata)
}

class AlertAppSuspendedSuppressor :
    VisualInterruptionFilter(types = setOf(PEEK, PULSE, BUBBLE), reason = "app is suspended") {
    override fun shouldSuppress(entry: NotificationEntry) = entry.ranking.isSuspended
}

class AlertKeyguardVisibilitySuppressor(
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider
) : VisualInterruptionFilter(types = setOf(PEEK, PULSE, BUBBLE), reason = "hidden on keyguard") {
    override fun shouldSuppress(entry: NotificationEntry) =
        keyguardNotificationVisibilityProvider.shouldHideNotification(entry)
}

/**
 * Set with:
 *
 * adb shell setprop persist.force_show_avalanche_edu_once 1 && adb shell stop; adb shell start
 */
private const val FORCE_SHOW_AVALANCHE_EDU_ONCE = "persist.force_show_avalanche_edu_once"

private const val PREF_HAS_SEEN_AVALANCHE_EDU = "has_seen_avalanche_edu"

class AvalancheSuppressor(
    private val avalancheProvider: AvalancheProvider,
    private val systemClock: SystemClock,
    private val settingsInteractor: NotificationSettingsInteractor,
    private val packageManager: PackageManager,
    private val uiEventLogger: UiEventLogger,
    private val context: Context,
    private val notificationManager: NotificationManager,
    private val logger: VisualInterruptionDecisionLogger,
    private val systemSettings: SystemSettings,
) :
    VisualInterruptionFilter(
        types = setOf(PEEK, PULSE),
        reason = "avalanche",
    ) {
    val TAG = "AvalancheSuppressor"

    private val prefs = context.getSharedPreferences(context.packageName, Context.MODE_PRIVATE)

    // SharedPreferences are persisted across reboots
    var hasSeenEdu: Boolean
        get() = prefs.getBoolean(PREF_HAS_SEEN_AVALANCHE_EDU, false)
        set(value) = prefs.edit().putBoolean(PREF_HAS_SEEN_AVALANCHE_EDU, value).apply()

    // Reset on reboot.
    // The pipeline runs these suppressors many times very fast, so we must use a separate bool
    // to force show for debug so that phone does not get stuck sending out infinite number of
    // education HUNs.
    private var hasShownOnceForDebug = false

    // Sometimes the kotlin flow value is false even when the cooldown setting is true (b/356768397)
    // so let's directly check settings until we confirm that the flow is initialized and in sync
    // with the real settings value.
    private var isCooldownFlowInSync = false

    private fun shouldShowEdu(): Boolean {
        val forceShowOnce = SystemProperties.get(FORCE_SHOW_AVALANCHE_EDU_ONCE, "").equals("1")
        return !hasSeenEdu || (forceShowOnce && !hasShownOnceForDebug)
    }

    enum class State {
        ALLOW_CONVERSATION_AFTER_AVALANCHE,
        ALLOW_HIGH_PRIORITY_CONVERSATION_ANY_TIME,
        ALLOW_CALLSTYLE,
        ALLOW_CATEGORY_REMINDER,
        ALLOW_CATEGORY_EVENT,
        ALLOW_CATEGORY_ALARM,
        ALLOW_CATEGORY_CAR_EMERGENCY,
        ALLOW_CATEGORY_CAR_WARNING,
        ALLOW_FSI_WITH_PERMISSION_ON,
        ALLOW_COLORIZED,
        ALLOW_EMERGENCY,
        SUPPRESS
    }

    enum class AvalancheEvent(private val id: Int) : UiEventLogger.UiEventEnum {
        @UiEvent(doc = "An avalanche event occurred, and a suppression period will start now.")
        AVALANCHE_SUPPRESSOR_RECEIVED_TRIGGERING_EVENT(1824),
        @UiEvent(doc = "HUN was suppressed in avalanche.")
        AVALANCHE_SUPPRESSOR_HUN_SUPPRESSED(1825),
        @UiEvent(doc = "HUN allowed during avalanche because conversation newer than the trigger.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_NEW_CONVERSATION(1826),
        @UiEvent(doc = "HUN allowed during avalanche because it is a priority conversation.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_PRIORITY_CONVERSATION(1827),
        @UiEvent(doc = "HUN allowed during avalanche because it is a CallStyle notification.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CALL_STYLE(1828),
        @UiEvent(doc = "HUN allowed during avalanche because it is a reminder notification.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_REMINDER(1829),
        @UiEvent(doc = "HUN allowed during avalanche because it is a calendar notification.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_EVENT(1830),
        @UiEvent(doc = "HUN allowed during avalanche because it has FSI.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_FSI_WITH_PERMISSION(1831),
        @UiEvent(doc = "HUN allowed during avalanche because it is colorized.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_COLORIZED(1832),
        @UiEvent(doc = "HUN allowed during avalanche because it is an emergency notification.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_EMERGENCY(1833),
        @UiEvent(doc = "HUN allowed during avalanche because it is an alarm.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_ALARM(1867),
        @UiEvent(doc = "HUN allowed during avalanche because it is a car emergency.")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_CAR_EMERGENCY(1868),
        @UiEvent(doc = "HUN allowed during avalanche because it is a car warning")
        AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_CAR_WARNING(1869);
        override fun getId(): Int {
            return id
        }
    }

    override fun shouldSuppress(entry: NotificationEntry): Boolean {
        if (!isCooldownEnabled()) {
            logger.logAvalancheAllow("cooldown OFF")
            return false
        }
        val timeSinceAvalancheMs = systemClock.currentTimeMillis() - avalancheProvider.startTime
        val timedOut = timeSinceAvalancheMs >= avalancheProvider.timeoutMs
        if (timedOut) {
            logger.logAvalancheAllow("timedOut! timeSinceAvalancheMs=$timeSinceAvalancheMs")
            return false
        }
        val state = calculateState(entry)
        if (state != State.SUPPRESS) {
            logger.logAvalancheAllow("state=$state")
            return false
        }
        if (shouldShowEdu()) {
            showEdu()
        }
        return true
    }

    /** Show avalanche education HUN from SystemUI. */
    private fun showEdu() {
        val res = context.resources
        val titleStr =
            res.getString(com.android.systemui.res.R.string.adaptive_notification_edu_hun_title)
        val textStr =
            res.getString(com.android.systemui.res.R.string.adaptive_notification_edu_hun_text)
        val actionStr =
            res.getString(com.android.systemui.res.R.string.go_to_adaptive_notification_settings)

        val intent = Intent(Settings.ACTION_MANAGE_ADAPTIVE_NOTIFICATIONS)
        val pendingIntent =
            PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE)

        // Replace "System UI" app name with "Android System"
        val bundle = Bundle()
        bundle.putString(
            Notification.EXTRA_SUBSTITUTE_APP_NAME,
            context.getString(com.android.internal.R.string.android_system_label)
        )

        val builder =
            Notification.Builder(context, NotificationChannels.ALERTS)
                .setTicker(titleStr)
                .setContentTitle(titleStr)
                .setContentText(textStr)
                .setStyle(Notification.BigTextStyle().bigText(textStr))
                .setSmallIcon(com.android.systemui.res.R.drawable.ic_settings)
                .setCategory(Notification.CATEGORY_SYSTEM)
                .setTimeoutAfter(/* one day in ms */ 24 * 60 * 60 * 1000L)
                .setAutoCancel(true)
                .addAction(android.R.drawable.button_onoff_indicator_off, actionStr, pendingIntent)
                .setContentIntent(pendingIntent)
                .addExtras(bundle)

        notificationManager.notify(SystemMessage.NOTE_ADAPTIVE_NOTIFICATIONS, builder.build())
        hasSeenEdu = true
        hasShownOnceForDebug = true
    }

    private fun calculateState(entry: NotificationEntry): State {
        if (
            entry.ranking.isConversation &&
                entry.sbn.notification.getWhen() > avalancheProvider.startTime
        ) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_NEW_CONVERSATION)
            return State.ALLOW_CONVERSATION_AFTER_AVALANCHE
        }

        if (entry.channel?.isImportantConversation == true) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_PRIORITY_CONVERSATION)
            return State.ALLOW_HIGH_PRIORITY_CONVERSATION_ANY_TIME
        }

        if (entry.sbn.notification.isStyle(Notification.CallStyle::class.java)) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CALL_STYLE)
            return State.ALLOW_CALLSTYLE
        }

        if (entry.sbn.notification.category == CATEGORY_REMINDER) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_REMINDER)
            return State.ALLOW_CATEGORY_REMINDER
        }

        if (entry.sbn.notification.category == CATEGORY_ALARM) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_ALARM)
            return State.ALLOW_CATEGORY_ALARM
        }

        if (entry.sbn.notification.category == CATEGORY_CAR_EMERGENCY) {
            uiEventLogger.log(
                    AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_CAR_EMERGENCY)
            return State.ALLOW_CATEGORY_CAR_EMERGENCY
        }

        if (entry.sbn.notification.category == CATEGORY_CAR_WARNING) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_CAR_WARNING)
            return State.ALLOW_CATEGORY_CAR_WARNING
        }

        if (entry.sbn.notification.category == CATEGORY_EVENT) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_CATEGORY_EVENT)
            return State.ALLOW_CATEGORY_EVENT
        }

        if (entry.sbn.notification.fullScreenIntent != null) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_FSI_WITH_PERMISSION)
            return State.ALLOW_FSI_WITH_PERMISSION_ON
        }
        if (entry.sbn.notification.isColorized) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_COLORIZED)
            return State.ALLOW_COLORIZED
        }
        if (
            packageManager.checkPermission(RECEIVE_EMERGENCY_BROADCAST, entry.sbn.packageName) ==
                PERMISSION_GRANTED
        ) {
            uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_ALLOWED_EMERGENCY)
            return State.ALLOW_EMERGENCY
        }
        uiEventLogger.log(AvalancheEvent.AVALANCHE_SUPPRESSOR_HUN_SUPPRESSED)
        return State.SUPPRESS
    }

    private fun isCooldownEnabled(): Boolean {
        val isEnabledFromFlow = settingsInteractor.isCooldownEnabled.value
        if (isCooldownFlowInSync) {
            return isEnabledFromFlow
        }
        val isEnabled =
            systemSettings.getInt(Settings.System.NOTIFICATION_COOLDOWN_ENABLED, /* def */ 1) == 1
        if (isEnabled == isEnabledFromFlow) {
            isCooldownFlowInSync = true
        }
        return isEnabled
    }
}
