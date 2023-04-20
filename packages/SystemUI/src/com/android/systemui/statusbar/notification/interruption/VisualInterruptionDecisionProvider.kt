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

import com.android.systemui.statusbar.notification.collection.NotificationEntry

/**
 * Decides whether a notification should visually interrupt the user in various ways.
 *
 * These include displaying the notification as heads-up (peeking while the device is awake or
 * pulsing while the device is dozing), displaying the notification as a bubble, and launching a
 * full-screen intent for the notification.
 */
interface VisualInterruptionDecisionProvider {
    /**
     * Represents the decision to visually interrupt or not.
     *
     * Used for heads-up and bubble decisions; subclassed by [FullScreenIntentDecision] for
     * full-screen intent decisions.
     *
     * @property[shouldInterrupt] whether a visual interruption should be triggered
     * @property[logReason] a log-friendly string explaining the reason for the decision; should be
     *   used *only* for logging, not decision-making
     */
    interface Decision {
        val shouldInterrupt: Boolean
        val logReason: String
    }

    /**
     * Represents the decision to launch a full-screen intent for a notification or not.
     *
     * @property[wouldInterruptWithoutDnd] whether a full-screen intent should not be launched only
     *   because Do Not Disturb has suppressed it
     */
    interface FullScreenIntentDecision : Decision {
        val wouldInterruptWithoutDnd: Boolean
    }

    /**
     * Adds a [component][suppressor] that can suppress visual interruptions.
     *
     * This class may call suppressors in any order.
     *
     * @param[suppressor] the suppressor to add
     */
    fun addSuppressor(suppressor: NotificationInterruptSuppressor)

    /**
     * Decides whether a [notification][entry] should display as heads-up or not, but does not log
     * that decision.
     *
     * @param[entry] the notification that this decision is about
     * @return the decision to display that notification as heads-up or not
     */
    fun makeUnloggedHeadsUpDecision(entry: NotificationEntry): Decision

    /**
     * Decides whether a [notification][entry] should display as heads-up or not, and logs that
     * decision.
     *
     * If the device is awake, the decision will consider whether the notification should "peek"
     * (slide in from the top of the screen over the current activity).
     *
     * If the device is dozing, the decision will consider whether the notification should "pulse"
     * (wake the screen up and display the ambient view of the notification).
     *
     * @see[makeUnloggedHeadsUpDecision]
     *
     * @param[entry] the notification that this decision is about
     * @return the decision to display that notification as heads-up or not
     */
    fun makeAndLogHeadsUpDecision(entry: NotificationEntry): Decision

    /**
     * Decides whether a [notification][entry] should launch a full-screen intent or not, but does
     * not log that decision.
     *
     * The returned decision can be logged by passing it to [logFullScreenIntentDecision].
     *
     * @see[makeAndLogHeadsUpDecision]
     *
     * @param[entry] the notification that this decision is about
     * @return the decision to launch a full-screen intent for that notification or not
     */
    fun makeUnloggedFullScreenIntentDecision(entry: NotificationEntry): FullScreenIntentDecision

    /**
     * Logs a previous [decision] to launch a full-screen intent or not.
     *
     * @param[decision] the decision to log
     */
    fun logFullScreenIntentDecision(decision: FullScreenIntentDecision)

    /**
     * Decides whether a [notification][entry] should display as a bubble or not.
     *
     * @param[entry] the notification that this decision is about
     * @return the decision to display that notification as a bubble or not
     */
    fun makeAndLogBubbleDecision(entry: NotificationEntry): Decision
}
