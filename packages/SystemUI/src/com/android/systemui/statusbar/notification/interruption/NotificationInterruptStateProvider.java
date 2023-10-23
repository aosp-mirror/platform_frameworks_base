/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.statusbar.notification.interruption;

import androidx.annotation.NonNull;

import com.android.systemui.statusbar.notification.collection.NotificationEntry;

/**
 * Provides bubble-up and heads-up state for notification entries.
 *
 * When a notification is heads-up when dozing, this is also called "pulsing."
 */
public interface NotificationInterruptStateProvider {
    /**
     * Enum representing a decision of whether to show a full screen intent. While many of the
     * relevant properties could overlap, the decision represents the deciding factor for whether
     * the full screen intent should or shouldn't launch.
     */
    enum FullScreenIntentDecision {
        /**
         * Full screen intents are disabled.
         */
        NO_FSI_SHOW_STICKY_HUN(false),
        /**
         * No full screen intent included, so there is nothing to show.
         */
        NO_FULL_SCREEN_INTENT(false),
        /**
         * Suppressed by DND settings.
         */
        NO_FSI_SUPPRESSED_BY_DND(false),
        /**
         * Full screen intent was suppressed *only* by DND, and if not for DND would have shown. We
         * track this separately in order to allow the intent to be shown if the DND decision
         * changes.
         */
        NO_FSI_SUPPRESSED_ONLY_BY_DND(false),
        /**
         * Notification importance not high enough to show FSI.
         */
        NO_FSI_NOT_IMPORTANT_ENOUGH(false),
        /**
         * Notification should not FSI due to having suppressive GroupAlertBehavior. This blocks a
         * potentially malicious use of flags that previously allowed apps to escalate a HUN to an
         * FSI even while the device was unlocked.
         */
        NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR(false),
        /**
         * Notification should not FSI due to having suppressive BubbleMetadata. This blocks a
         * potentially malicious use of flags that previously allowed apps to escalate a HUN to an
         * FSI even while the device was unlocked.
         */
        NO_FSI_SUPPRESSIVE_BUBBLE_METADATA(false),
        /**
         * Device screen is off, so the FSI should launch.
         */
        FSI_DEVICE_NOT_INTERACTIVE(true),
        /**
         * Device is currently dreaming, so FSI should launch.
         */
        FSI_DEVICE_IS_DREAMING(true),
        /**
         * Keyguard is showing, so FSI should launch.
         */
        FSI_KEYGUARD_SHOWING(true),
        /**
         * The notification is expected to show heads-up, so FSI is not needed.
         */
        NO_FSI_EXPECTED_TO_HUN(false),
        /**
         * The notification is not expected to HUN while the keyguard is occluded, so show FSI.
         */
        FSI_KEYGUARD_OCCLUDED(true),
        /**
         * The notification is not expected to HUN when the keyguard is showing but not occluded,
         * which likely means that the shade is showing over the lockscreen; show FSI in this case.
         */
        FSI_LOCKED_SHADE(true),
        /**
         * FSI requires keyguard to be showing, but there is no keyguard. This is a (potentially
         * malicious) warning state where we suppress the FSI because the device is in use knowing
         * that the HUN will probably not display.
         */
        NO_FSI_NO_HUN_OR_KEYGUARD(false),
        /**
         * The notification is coming from a suspended packages, so FSI is suppressed.
         */
        NO_FSI_SUSPENDED(false),
        /**
         * The device is not provisioned, launch FSI.
         */
        FSI_NOT_PROVISIONED(true);

        public final boolean shouldLaunch;

        FullScreenIntentDecision(boolean shouldLaunch) {
            this.shouldLaunch = shouldLaunch;
        }
    }

    /**
     * If the device is awake (not dozing):
     *  Whether the notification should peek in from the top and alert the user.
     *
     * If the device is dozing:
     *  Whether the notification should show the ambient view of the notification ("pulse").
     *
     * @param entry the entry to check
     * @return true if the entry should heads up, false otherwise
     */
    boolean shouldHeadsUp(NotificationEntry entry);

    /**
     * Returns the value of whether this entry should peek (from shouldHeadsUp(entry)), but only
     * optionally logs the status.
     *
     * This method should be used in cases where the caller needs to check whether a notification
     * qualifies for a heads up, but is not necessarily guaranteed to make the heads-up happen.
     *
     * @param entry the entry to check
     * @param log whether or not to log the results of this check
     * @return true if the entry should heads up, false otherwise
     */
    boolean checkHeadsUp(NotificationEntry entry, boolean log);

    /**
     * Whether the notification should appear as a bubble with a fly-out on top of the screen.
     *
     * @param entry the entry to check
     * @return true if the entry should bubble up, false otherwise
     */
    boolean shouldBubbleUp(NotificationEntry entry);

    /**
     * Whether to launch the entry's full screen intent when the entry is added.
     *
     * @param entry the entry that was added
     * @return {@code true} if we should launch the full screen intent
     */
    boolean shouldLaunchFullScreenIntentWhenAdded(NotificationEntry entry);

    /**
     * Whether an entry's full screen intent would be launched.
     *
     * This method differs from shouldLaunchFullScreenIntentWhenAdded by returning more information
     * on the decision, and only optionally logging the outcome. It should be used in cases where
     * the caller needs to know what the decision would be, but may not actually launch the full
     * screen intent.
     *
     * @param entry the entry to evaluate
     * @return FullScreenIntentDecision representing the decision for whether to show the intent
     */
    @NonNull
    FullScreenIntentDecision getFullScreenIntentDecision(@NonNull NotificationEntry entry);

    /**
     * Write the full screen launch decision for the given entry to logs.
     *
     * @param entry the NotificationEntry for which the decision applies
     * @param decision reason for launch or no-launch of FSI for entry
     */
    void logFullScreenIntentDecision(NotificationEntry entry, FullScreenIntentDecision decision);

    /**
     * Add a component that can suppress visual interruptions.
     */
    void addSuppressor(NotificationInterruptSuppressor suppressor);

    /**
     * Remove a component that can suppress visual interruptions.
     */
    void removeSuppressor(NotificationInterruptSuppressor suppressor);
}
