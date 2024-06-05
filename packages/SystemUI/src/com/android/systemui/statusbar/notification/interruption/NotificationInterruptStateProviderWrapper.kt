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

import com.android.app.tracing.traceSection
import com.android.internal.annotations.VisibleForTesting
import com.android.systemui.dagger.SysUISingleton
import com.android.systemui.flags.RefactorFlagUtils
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProvider.FullScreenIntentDecision.NO_FSI_SUPPRESSED_ONLY_BY_DND
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider.Decision
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionDecisionProvider.FullScreenIntentDecision

/**
 * Wraps a [NotificationInterruptStateProvider] to convert it to the new
 * [VisualInterruptionDecisionProvider] interface.
 */
@SysUISingleton
class NotificationInterruptStateProviderWrapper(
    private val wrapped: NotificationInterruptStateProvider
) : VisualInterruptionDecisionProvider {
    init {
        VisualInterruptionRefactor.assertInLegacyMode()
    }

    @VisibleForTesting
    enum class DecisionImpl(override val shouldInterrupt: Boolean) : Decision {
        SHOULD_INTERRUPT(shouldInterrupt = true),
        SHOULD_NOT_INTERRUPT(shouldInterrupt = false);

        override val logReason = "unknown"

        companion object {
            fun of(booleanDecision: Boolean) =
                if (booleanDecision) SHOULD_INTERRUPT else SHOULD_NOT_INTERRUPT
        }
    }

    @VisibleForTesting
    class FullScreenIntentDecisionImpl(
        val originalEntry: NotificationEntry,
        val originalDecision: NotificationInterruptStateProvider.FullScreenIntentDecision
    ) : FullScreenIntentDecision {
        override val shouldInterrupt = originalDecision.shouldLaunch
        override val wouldInterruptWithoutDnd = originalDecision == NO_FSI_SUPPRESSED_ONLY_BY_DND
        override val logReason = originalDecision.name
    }

    override fun addLegacySuppressor(suppressor: NotificationInterruptSuppressor) {
        wrapped.addSuppressor(suppressor)
    }

    override fun removeLegacySuppressor(suppressor: NotificationInterruptSuppressor) {
        wrapped.removeSuppressor(suppressor)
    }

    override fun addCondition(condition: VisualInterruptionCondition) = notValidInLegacyMode()

    override fun removeCondition(condition: VisualInterruptionCondition) = notValidInLegacyMode()

    override fun addFilter(filter: VisualInterruptionFilter) = notValidInLegacyMode()

    override fun removeFilter(filter: VisualInterruptionFilter) = notValidInLegacyMode()

    private fun notValidInLegacyMode() {
        RefactorFlagUtils.assertOnEngBuild(
            "This method is only implemented in VisualInterruptionDecisionProviderImpl, " +
                "and so should only be called when FLAG_VISUAL_INTERRUPTIONS_REFACTOR is enabled."
        )
    }

    override fun makeUnloggedHeadsUpDecision(entry: NotificationEntry): Decision =
        traceSection("NotificationInterruptStateProviderWrapper#makeUnloggedHeadsUpDecision") {
            wrapped.checkHeadsUp(entry, /* log= */ false).let { DecisionImpl.of(it) }
        }

    override fun makeAndLogHeadsUpDecision(entry: NotificationEntry): Decision =
        traceSection("NotificationInterruptStateProviderWrapper#makeAndLogHeadsUpDecision") {
            wrapped.checkHeadsUp(entry, /* log= */ true).let { DecisionImpl.of(it) }
        }

    override fun makeUnloggedFullScreenIntentDecision(entry: NotificationEntry) =
        traceSection(
            "NotificationInterruptStateProviderWrapper#makeUnloggedFullScreenIntentDecision"
        ) {
            wrapped.getFullScreenIntentDecision(entry).let {
                FullScreenIntentDecisionImpl(entry, it)
            }
        }

    override fun logFullScreenIntentDecision(decision: FullScreenIntentDecision) =
        traceSection("NotificationInterruptStateProviderWrapper#logFullScreenIntentDecision") {
            (decision as FullScreenIntentDecisionImpl).let {
                wrapped.logFullScreenIntentDecision(it.originalEntry, it.originalDecision)
            }
        }

    override fun makeAndLogBubbleDecision(entry: NotificationEntry): Decision =
        traceSection("NotificationInterruptStateProviderWrapper#makeAndLogBubbleDecision") {
            wrapped.shouldBubbleUp(entry).let { DecisionImpl.of(it) }
        }
}
