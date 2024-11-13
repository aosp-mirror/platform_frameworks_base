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

import android.app.NotificationManager.IMPORTANCE_HIGH
import android.os.PowerManager
import com.android.internal.logging.UiEventLogger.UiEventEnum
import com.android.systemui.plugins.statusbar.StatusBarStateController
import com.android.systemui.statusbar.StatusBarState.KEYGUARD
import com.android.systemui.statusbar.notification.collection.NotificationEntry
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.FSI_DEVICE_DREAMING
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.FSI_DEVICE_NOT_INTERACTIVE
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.FSI_DEVICE_NOT_PROVISIONED
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.FSI_KEYGUARD_OCCLUDED
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.FSI_KEYGUARD_SHOWING
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.FSI_LOCKED_SHADE
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.FSI_USER_SETUP_INCOMPLETE
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_EXPECTED_TO_HUN
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_NOT_IMPORTANT_ENOUGH
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_NO_FULL_SCREEN_INTENT
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_NO_HUN_OR_KEYGUARD
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_PACKAGE_SUSPENDED
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_SHOW_STICKY_HUN
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_SUPPRESSED_BY_DND
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_SUPPRESSED_ONLY_BY_DND
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_SUPPRESSIVE_BUBBLE_METADATA
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR
import com.android.systemui.statusbar.notification.interruption.FullScreenIntentDecisionProvider.DecisionImpl.NO_FSI_SUPPRESSIVE_SILENT_NOTIFICATION
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA
import com.android.systemui.statusbar.notification.interruption.NotificationInterruptStateProviderImpl.NotificationInterruptEvent.FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR
import com.android.systemui.statusbar.notification.interruption.VisualInterruptionSuppressor.EventLogData
import com.android.systemui.statusbar.policy.DeviceProvisionedController
import com.android.systemui.statusbar.policy.KeyguardStateController

class FullScreenIntentDecisionProvider(
    private val deviceProvisionedController: DeviceProvisionedController,
    private val keyguardStateController: KeyguardStateController,
    private val powerManager: PowerManager,
    private val statusBarStateController: StatusBarStateController
) {
    interface Decision {
        val shouldFsi: Boolean
        val wouldFsiWithoutDnd: Boolean
        val logReason: String
        val shouldLog: Boolean
        val isWarning: Boolean
        val uiEventId: UiEventEnum?
        val eventLogData: EventLogData?
    }

    private enum class DecisionImpl(
        override val shouldFsi: Boolean,
        override val logReason: String,
        override val wouldFsiWithoutDnd: Boolean = shouldFsi,
        val supersedesDnd: Boolean = false,
        override val shouldLog: Boolean = true,
        override val isWarning: Boolean = false,
        override val uiEventId: UiEventEnum? = null,
        override val eventLogData: EventLogData? = null
    ) : Decision {
        NO_FSI_NO_FULL_SCREEN_INTENT(
            false,
            "no full-screen intent",
            supersedesDnd = true,
            shouldLog = false
        ),
        NO_FSI_SHOW_STICKY_HUN(false, "full-screen intents are disabled", supersedesDnd = true),
        NO_FSI_NOT_IMPORTANT_ENOUGH(false, "not important enough"),
        NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR(
            false,
            "suppressive group alert behavior",
            isWarning = true,
            uiEventId = FSI_SUPPRESSED_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR,
            eventLogData = EventLogData("231322873", "groupAlertBehavior")
        ),
        NO_FSI_SUPPRESSIVE_BUBBLE_METADATA(
            false,
            "suppressive bubble metadata",
            isWarning = true,
            uiEventId = FSI_SUPPRESSED_SUPPRESSIVE_BUBBLE_METADATA,
            eventLogData = EventLogData("274759612", "bubbleMetadata")
        ),
        NO_FSI_SUPPRESSIVE_SILENT_NOTIFICATION(false, "suppressive setSilent notification"),
        NO_FSI_PACKAGE_SUSPENDED(false, "package suspended"),
        FSI_DEVICE_NOT_INTERACTIVE(true, "device is not interactive"),
        FSI_DEVICE_DREAMING(true, "device is dreaming"),
        FSI_KEYGUARD_SHOWING(true, "keyguard is showing"),
        NO_FSI_EXPECTED_TO_HUN(false, "expected to heads-up instead"),
        FSI_KEYGUARD_OCCLUDED(true, "keyguard is occluded"),
        FSI_LOCKED_SHADE(true, "locked shade"),
        FSI_DEVICE_NOT_PROVISIONED(true, "device not provisioned"),
        FSI_USER_SETUP_INCOMPLETE(true, "user setup incomplete"),
        NO_FSI_NO_HUN_OR_KEYGUARD(
            false,
            "no HUN or keyguard",
            isWarning = true,
            uiEventId = FSI_SUPPRESSED_NO_HUN_OR_KEYGUARD,
            eventLogData = EventLogData("231322873", "no hun or keyguard")
        ),
        NO_FSI_SUPPRESSED_BY_DND(false, "suppressed by DND", wouldFsiWithoutDnd = false),
        NO_FSI_SUPPRESSED_ONLY_BY_DND(false, "suppressed only by DND", wouldFsiWithoutDnd = true)
    }

    fun makeFullScreenIntentDecision(entry: NotificationEntry, couldHeadsUp: Boolean): Decision {
        val reasonWithoutDnd = makeDecisionWithoutDnd(entry, couldHeadsUp)

        val suppressedWithoutDnd = !reasonWithoutDnd.shouldFsi
        val suppressedByDnd = entry.shouldSuppressFullScreenIntent()

        val reasonWithDnd =
            when {
                reasonWithoutDnd.supersedesDnd -> reasonWithoutDnd
                suppressedByDnd && !suppressedWithoutDnd -> NO_FSI_SUPPRESSED_ONLY_BY_DND
                suppressedByDnd -> NO_FSI_SUPPRESSED_BY_DND
                else -> reasonWithoutDnd
            }

        return reasonWithDnd
    }

    private fun makeDecisionWithoutDnd(
        entry: NotificationEntry,
        couldHeadsUp: Boolean
    ): DecisionImpl {
        val sbn = entry.sbn
        val notification = sbn.notification!!

        if (notification.fullScreenIntent == null) {
            return if (entry.isStickyAndNotDemoted) {
                NO_FSI_SHOW_STICKY_HUN
            } else {
                NO_FSI_NO_FULL_SCREEN_INTENT
            }
        }

        if (entry.importance < IMPORTANCE_HIGH) {
            return NO_FSI_NOT_IMPORTANT_ENOUGH
        }

        if (sbn.isGroup && notification.suppressAlertingDueToGrouping()) {
            return NO_FSI_SUPPRESSIVE_GROUP_ALERT_BEHAVIOR
        }

        if (android.service.notification.Flags.notificationSilentFlag()) {
            if (sbn.notification.isSilent) {
                return NO_FSI_SUPPRESSIVE_SILENT_NOTIFICATION
            }
        }

        val bubbleMetadata = notification.bubbleMetadata
        if (bubbleMetadata != null && bubbleMetadata.isNotificationSuppressed) {
            return NO_FSI_SUPPRESSIVE_BUBBLE_METADATA
        }

        if (entry.ranking.isSuspended) {
            return NO_FSI_PACKAGE_SUSPENDED
        }

        if (!powerManager.isInteractive) {
            return FSI_DEVICE_NOT_INTERACTIVE
        }

        if (statusBarStateController.isDreaming) {
            return FSI_DEVICE_DREAMING
        }

        if (statusBarStateController.state == KEYGUARD) {
            return FSI_KEYGUARD_SHOWING
        }

        if (couldHeadsUp) {
            return NO_FSI_EXPECTED_TO_HUN
        }

        if (keyguardStateController.isShowing) {
            return if (keyguardStateController.isOccluded) {
                FSI_KEYGUARD_OCCLUDED
            } else {
                FSI_LOCKED_SHADE
            }
        }

        if (!deviceProvisionedController.isDeviceProvisioned) {
            return FSI_DEVICE_NOT_PROVISIONED
        }

        if (!deviceProvisionedController.isCurrentUserSetup) {
            return FSI_USER_SETUP_INCOMPLETE
        }

        return NO_FSI_NO_HUN_OR_KEYGUARD
    }
}
