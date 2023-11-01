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
import com.android.systemui.dagger.qualifiers.Main
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.settings.UserTracker
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider.Decision
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider.FullScreenIntentDecision
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.BUBBLE
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PEEK
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionType.PULSE
import com.android.systemui.statusbar.policy.BatteryController
import com.android.systemui.statusbar.policy.HeadsUpManager
import com.android.systemui.util.settings.GlobalSettings
import com.android.systemui.util.time.SystemClock
import javax.inject.Inject

class VisualInterruptionDecisionProviderImpl
@Inject
constructor(
    private val ambientDisplayConfiguration: AmbientDisplayConfiguration,
    private val batteryController: BatteryController,
    private val globalSettings: GlobalSettings,
    private val headsUpManager: HeadsUpManager,
    private val logger: NotificationInterruptLogger,
    @Main private val mainHandler: Handler,
    private val powerManager: PowerManager,
    private val statusBarStateController: StatusBarStateController,
    private val systemClock: SystemClock,
    private val userTracker: UserTracker,
) : VisualInterruptionDecisionProvider {
    private class DecisionImpl(
        override val shouldInterrupt: Boolean,
        override val logReason: String
    ) : Decision

    private class FullScreenIntentDecisionImpl(
        override val shouldInterrupt: Boolean,
        override val wouldInterruptWithoutDnd: Boolean,
        override val logReason: String,
        val originalEntry: NotificationEntry,
    ) : FullScreenIntentDecision {
        var hasBeenLogged = false
    }

    private val legacySuppressors = mutableSetOf<NotificationInterruptSuppressor>()
    private val conditions = mutableListOf<VisualInterruptionCondition>()
    private val filters = mutableListOf<VisualInterruptionFilter>()

    override fun addLegacySuppressor(suppressor: NotificationInterruptSuppressor) {
        legacySuppressors.add(suppressor)
    }

    override fun removeLegacySuppressor(suppressor: NotificationInterruptSuppressor) {
        legacySuppressors.remove(suppressor)
    }

    fun addCondition(condition: VisualInterruptionCondition) {
        conditions.add(condition)
    }

    fun addFilter(filter: VisualInterruptionFilter) {
        filters.add(filter)
    }

    override fun makeUnloggedHeadsUpDecision(entry: NotificationEntry): Decision {
        return makeHeadsUpDecision(entry)
    }

    override fun makeAndLogHeadsUpDecision(entry: NotificationEntry): Decision {
        return makeHeadsUpDecision(entry).also { logHeadsUpDecision(entry, it) }
    }

    override fun makeUnloggedFullScreenIntentDecision(
        entry: NotificationEntry
    ): FullScreenIntentDecision {
        return makeFullScreenDecision(entry)
    }

    override fun logFullScreenIntentDecision(decision: FullScreenIntentDecision) {
        val decisionImpl =
            decision as? FullScreenIntentDecisionImpl
                ?: run {
                    Log.wtf(TAG, "Wrong subclass of FullScreenIntentDecision: $decision")
                    return
                }
        if (decision.hasBeenLogged) {
            Log.wtf(TAG, "Already logged decision: $decision")
            return
        }
        logFullScreenIntentDecision(decisionImpl)
        decision.hasBeenLogged = true
    }

    override fun makeAndLogBubbleDecision(entry: NotificationEntry): Decision {
        return makeBubbleDecision(entry).also { logBubbleDecision(entry, it) }
    }

    private fun makeHeadsUpDecision(entry: NotificationEntry): DecisionImpl {
        if (statusBarStateController.isDozing) {
            return makePulseDecision(entry)
        } else {
            return makePeekDecision(entry)
        }
    }

    private fun makePeekDecision(entry: NotificationEntry): DecisionImpl {
        checkConditions(PEEK)?.let {
            return DecisionImpl(shouldInterrupt = false, logReason = it.reason)
        }
        checkFilters(PEEK, entry)?.let {
            return DecisionImpl(shouldInterrupt = false, logReason = it.reason)
        }
        checkSuppressors(entry)?.let {
            return DecisionImpl(
                shouldInterrupt = false,
                logReason = "${it.name}.suppressInterruptions"
            )
        }
        checkAwakeSuppressors(entry)?.let {
            return DecisionImpl(
                shouldInterrupt = false,
                logReason = "${it.name}.suppressAwakeInterruptions"
            )
        }
        checkAwakeHeadsUpSuppressors(entry)?.let {
            return DecisionImpl(
                shouldInterrupt = false,
                logReason = "${it.name}.suppressAwakeHeadsUpInterruptions"
            )
        }
        return DecisionImpl(shouldInterrupt = true, logReason = "not suppressed")
    }

    private fun makePulseDecision(entry: NotificationEntry): DecisionImpl {
        checkConditions(PULSE)?.let {
            return DecisionImpl(shouldInterrupt = false, logReason = it.reason)
        }
        checkFilters(PULSE, entry)?.let {
            return DecisionImpl(shouldInterrupt = false, logReason = it.reason)
        }
        checkSuppressors(entry)?.let {
            return DecisionImpl(
                shouldInterrupt = false,
                logReason = "${it.name}.suppressInterruptions"
            )
        }
        return DecisionImpl(shouldInterrupt = true, logReason = "not suppressed")
    }

    private fun makeBubbleDecision(entry: NotificationEntry): DecisionImpl {
        checkConditions(BUBBLE)?.let {
            return DecisionImpl(shouldInterrupt = false, logReason = it.reason)
        }
        checkFilters(BUBBLE, entry)?.let {
            return DecisionImpl(shouldInterrupt = false, logReason = it.reason)
        }
        checkSuppressors(entry)?.let {
            return DecisionImpl(
                shouldInterrupt = false,
                logReason = "${it.name}.suppressInterruptions"
            )
        }
        checkAwakeSuppressors(entry)?.let {
            return DecisionImpl(
                shouldInterrupt = false,
                logReason = "${it.name}.suppressAwakeInterruptions"
            )
        }
        return DecisionImpl(shouldInterrupt = true, logReason = "not suppressed")
    }

    private fun makeFullScreenDecision(entry: NotificationEntry): FullScreenIntentDecisionImpl {
        // Not yet implemented.
        return FullScreenIntentDecisionImpl(
            shouldInterrupt = true,
            wouldInterruptWithoutDnd = true,
            logReason = "FSI logic not yet implemented in VisualInterruptionDecisionProviderImpl",
            originalEntry = entry
        )
    }

    private fun logHeadsUpDecision(entry: NotificationEntry, decision: DecisionImpl) {
        // Not yet implemented.
    }

    private fun logBubbleDecision(entry: NotificationEntry, decision: DecisionImpl) {
        // Not yet implemented.
    }

    private fun logFullScreenIntentDecision(decision: FullScreenIntentDecisionImpl) {
        // Not yet implemented.
    }

    private fun checkSuppressors(entry: NotificationEntry) =
        legacySuppressors.firstOrNull { it.suppressInterruptions(entry) }

    private fun checkAwakeSuppressors(entry: NotificationEntry) =
        legacySuppressors.firstOrNull { it.suppressAwakeInterruptions(entry) }

    private fun checkAwakeHeadsUpSuppressors(entry: NotificationEntry) =
        legacySuppressors.firstOrNull { it.suppressAwakeHeadsUp(entry) }

    private fun checkConditions(type: VisualInterruptionType): VisualInterruptionCondition? =
        conditions.firstOrNull { it.types.contains(type) && it.shouldSuppress() }

    private fun checkFilters(
        type: VisualInterruptionType,
        entry: NotificationEntry
    ): VisualInterruptionFilter? =
        filters.firstOrNull { it.types.contains(type) && it.shouldSuppress(entry) }
}

private const val TAG = "VisualInterruptionDecisionProviderImpl"
