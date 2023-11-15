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

import android.hardware.display.AmbientDisplayConfiguration
import android.os.Handler
import android.os.PowerManager
import android.util.Log
import com.android.internal.annotations.VisibleForTesting
import com.android.internal.logging.UiEventLogger
import com.android.internal.logging.UiEventLogger.UiEventEnum
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider.Decision
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider.FullScreenIntentDecision
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionSuppressor.EventLogData
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.statusbar.policy.KeyguardStateController
import com.android.systemui.util.EventLog
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

class VisualInterruptionDecisionProviderImpl
@Inject
constructor(
    private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
    private val batteryController: BatteryController,
    deviceProvisionedController: DeviceProvisionedController,
    private val eventLog: EventLog,
    private val globalSettings: GlobalSettings,
    private val headsUpManager: HeadsUpManager,
    private val keyguardNotificationVisibilityProvider: KeyguardNotificationVisibilityProvider,
    keyguardStateController: KeyguardStateController,
    private val logger: VisualInterruptionDecisionLogger,
    @Main private val mainHandler: Handler,
    private val powerManager: PowerManager,
    private val statusBarStateController: StatusBarStateController,
    private val systemClock: SystemClock,
    private val uiEventLogger: UiEventLogger,
    private val userTracker: UserTracker,
) : VisualInterruptionDecisionProvider {
    interface Loggable {
        val uiEventId: UiEventEnum?
        val eventLogData: EventLogData?
    }

    private class DecisionImpl(
        override val shouldInterrupt: Boolean,
        override val logReason: String
    ) : Decision

    private data class LoggableDecision
    private constructor(
        val decision: DecisionImpl,
        override val uiEventId: UiEventEnum? = null,
        override val eventLogData: EventLogData? = null
    ) : Loggable {
        companion object {
            val unsuppressed =
                LoggableDecision(DecisionImpl(shouldInterrupt = true, logReason = "not suppressed"))

            fun suppressed(legacySuppressor: NotificationInterruptSuppressor, methodName: String) =
                LoggableDecision(
                    DecisionImpl(
                        shouldInterrupt = false,
                        logReason = "${legacySuppressor.name}.$methodName"
                    )
                )

            fun suppressed(suppressor: VisualInterruptionSuppressor) =
                LoggableDecision(
                    DecisionImpl(shouldInterrupt = false, logReason = suppressor.reason),
                    uiEventId = suppressor.uiEventId,
                    eventLogData = suppressor.eventLogData
                )
        }
    }

    private class FullScreenIntentDecisionImpl(
        val entry: NotificationEntry,
        private val fsiDecision: FullScreenIntentDecisionProvider.Decision
    ) : FullScreenIntentDecision, Loggable {
        var hasBeenLogged = false

        override val shouldInterrupt
            get() = fsiDecision.shouldFsi

        override val wouldInterruptWithoutDnd
            get() = fsiDecision.wouldFsiWithoutDnd

        override val logReason
            get() = fsiDecision.logReason

        val shouldLog
            get() = fsiDecision.shouldLog

        val isWarning
            get() = fsiDecision.isWarning

        override val uiEventId
            get() = fsiDecision.uiEventId

        override val eventLogData
            get() = fsiDecision.eventLogData
    }

    private val fullScreenIntentDecisionProvider =
        FullScreenIntentDecisionProvider(
            deviceProvisionedController,
            keyguardStateController,
            powerManager,
            statusBarStateController
        )

    private val legacySuppressors = mutableSetOf<NotificationInterruptSuppressor>()
    private val conditions = mutableListOf<VisualInterruptionCondition>()
    private val filters = mutableListOf<VisualInterruptionFilter>()

    private var started = false

    override fun start() {
        check(!started)

        addCondition(PeekDisabledSuppressor(globalSettings, headsUpManager, logger, mainHandler))
        addCondition(PulseDisabledSuppressor(ambientDisplayConfiguration, userTracker))
        addCondition(PulseBatterySaverSuppressor(batteryController))
        addFilter(PeekPackageSnoozedSuppressor(headsUpManager))
        addFilter(PeekAlreadyBubbledSuppressor(statusBarStateController))
        addFilter(PeekDndSuppressor())
        addFilter(PeekNotImportantSuppressor())
        addCondition(PeekDeviceNotInUseSuppressor(powerManager, statusBarStateController))
        addFilter(PeekOldWhenSuppressor(systemClock))
        addFilter(PulseEffectSuppressor())
        addFilter(PulseLockscreenVisibilityPrivateSuppressor())
        addFilter(PulseLowImportanceSuppressor())
        addFilter(BubbleNotAllowedSuppressor())
        addFilter(BubbleNoMetadataSuppressor())
        addFilter(HunGroupAlertBehaviorSuppressor())
        addFilter(HunJustLaunchedFsiSuppressor())
        addFilter(AlertKeyguardVisibilitySuppressor(keyguardNotificationVisibilityProvider))

        started = true
    }

    override fun addLegacySuppressor(suppressor: NotificationInterruptSuppressor) {
        legacySuppressors.add(suppressor)
    }

    override fun removeLegacySuppressor(suppressor: NotificationInterruptSuppressor) {
        legacySuppressors.remove(suppressor)
    }

    fun addCondition(condition: VisualInterruptionCondition) {
        conditions.add(condition)
        condition.start()
    }

    @VisibleForTesting
    fun removeCondition(condition: VisualInterruptionCondition) {
        conditions.remove(condition)
    }

    fun addFilter(filter: VisualInterruptionFilter) {
        filters.add(filter)
        filter.start()
    }

    @VisibleForTesting
    fun removeFilter(filter: VisualInterruptionFilter) {
        filters.remove(filter)
    }

    override fun makeUnloggedHeadsUpDecision(entry: NotificationEntry): Decision {
        check(started)

        return if (statusBarStateController.isDozing) {
                makeLoggablePulseDecision(entry)
            } else {
                makeLoggablePeekDecision(entry)
            }
            .decision
    }

    override fun makeAndLogHeadsUpDecision(entry: NotificationEntry): Decision {
        check(started)

        return if (statusBarStateController.isDozing) {
                makeLoggablePulseDecision(entry).also { logDecision(PULSE, entry, it) }
            } else {
                makeLoggablePeekDecision(entry).also { logDecision(PEEK, entry, it) }
            }
            .decision
    }

    private fun makeLoggablePeekDecision(entry: NotificationEntry): LoggableDecision =
        checkConditions(PEEK)
            ?: checkFilters(PEEK, entry) ?: checkSuppressInterruptions(entry)
                ?: checkSuppressAwakeInterruptions(entry) ?: checkSuppressAwakeHeadsUp(entry)
                ?: LoggableDecision.unsuppressed

    private fun makeLoggablePulseDecision(entry: NotificationEntry): LoggableDecision =
        checkConditions(PULSE)
            ?: checkFilters(PULSE, entry) ?: checkSuppressInterruptions(entry)
                ?: LoggableDecision.unsuppressed

    override fun makeAndLogBubbleDecision(entry: NotificationEntry): Decision {
        check(started)

        return makeLoggableBubbleDecision(entry).also { logDecision(BUBBLE, entry, it) }.decision
    }

    private fun makeLoggableBubbleDecision(entry: NotificationEntry): LoggableDecision =
        checkConditions(BUBBLE)
            ?: checkFilters(BUBBLE, entry) ?: checkSuppressInterruptions(entry)
                ?: checkSuppressAwakeInterruptions(entry) ?: LoggableDecision.unsuppressed

    private fun logDecision(
        type: VisualInterruptionType,
        entry: NotificationEntry,
        loggableDecision: LoggableDecision
    ) {
        logger.logDecision(type.name, entry, loggableDecision.decision)
        logEvents(entry, loggableDecision)
    }

    override fun makeUnloggedFullScreenIntentDecision(
        entry: NotificationEntry
    ): FullScreenIntentDecision {
        check(started)

        val couldHeadsUp = makeUnloggedHeadsUpDecision(entry).shouldInterrupt
        val fsiDecision =
            fullScreenIntentDecisionProvider.makeFullScreenIntentDecision(entry, couldHeadsUp)
        return FullScreenIntentDecisionImpl(entry, fsiDecision)
    }

    override fun logFullScreenIntentDecision(decision: FullScreenIntentDecision) {
        check(started)

        if (decision !is FullScreenIntentDecisionImpl) {
            Log.wtf(TAG, "FSI decision $decision was not created by this class")
            return
        }

        if (decision.hasBeenLogged) {
            Log.wtf(TAG, "FSI decision $decision has already been logged")
            return
        }

        decision.hasBeenLogged = true

        if (!decision.shouldLog) {
            return
        }

        logger.logFullScreenIntentDecision(decision.entry, decision, decision.isWarning)
        logEvents(decision.entry, decision)
    }

    private fun logEvents(entry: NotificationEntry, loggable: Loggable) {
        loggable.uiEventId?.let { uiEventLogger.log(it, entry.sbn.uid, entry.sbn.packageName) }
        loggable.eventLogData?.let {
            eventLog.writeEvent(0x534e4554, it.number, entry.sbn.uid, it.description)
        }
    }

    private fun checkSuppressInterruptions(entry: NotificationEntry) =
        legacySuppressors
            .firstOrNull { it.suppressInterruptions(entry) }
            ?.let { LoggableDecision.suppressed(it, "suppressInterruptions") }

    private fun checkSuppressAwakeInterruptions(entry: NotificationEntry) =
        legacySuppressors
            .firstOrNull { it.suppressAwakeInterruptions(entry) }
            ?.let { LoggableDecision.suppressed(it, "suppressAwakeInterruptions") }

    private fun checkSuppressAwakeHeadsUp(entry: NotificationEntry) =
        legacySuppressors
            .firstOrNull { it.suppressAwakeHeadsUp(entry) }
            ?.let { LoggableDecision.suppressed(it, "suppressAwakeHeadsUp") }

    private fun checkConditions(type: VisualInterruptionType) =
        conditions
            .firstOrNull { it.types.contains(type) && it.shouldSuppress() }
            ?.let { LoggableDecision.suppressed(it) }

    private fun checkFilters(type: VisualInterruptionType, entry: NotificationEntry) =
        filters
            .firstOrNull { it.types.contains(type) && it.shouldSuppress(entry) }
            ?.let { LoggableDecision.suppressed(it) }
}

private const val TAG = "VisualInterruptionDecisionProviderImpl"
