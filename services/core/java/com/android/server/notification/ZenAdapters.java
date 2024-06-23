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

package com.android.server.notification;

import android.app.Flags;
import android.app.NotificationManager.Policy;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;

/**
 * Converters between different Zen representations.
 */
class ZenAdapters {

    static ZenPolicy notificationPolicyToZenPolicy(Policy policy) {
        ZenPolicy.Builder zenPolicyBuilder = new ZenPolicy.Builder()
                .allowAlarms(policy.allowAlarms())
                .allowCalls(
                        policy.allowCalls()
                                ? ZenModeConfig.getZenPolicySenders(policy.allowCallsFrom())
                                : ZenPolicy.PEOPLE_TYPE_NONE)
                .allowConversations(
                        policy.allowConversations()
                                ? notificationPolicyConversationSendersToZenPolicy(
                                        policy.allowConversationsFrom())
                                : ZenPolicy.CONVERSATION_SENDERS_NONE)
                .allowEvents(policy.allowEvents())
                .allowMedia(policy.allowMedia())
                .allowMessages(
                        policy.allowMessages()
                                ? ZenModeConfig.getZenPolicySenders(policy.allowMessagesFrom())
                                : ZenPolicy.PEOPLE_TYPE_NONE)
                .allowReminders(policy.allowReminders())
                .allowRepeatCallers(policy.allowRepeatCallers())
                .allowSystem(policy.allowSystem());

        if (policy.suppressedVisualEffects != Policy.SUPPRESSED_EFFECTS_UNSET) {
            zenPolicyBuilder.showBadges(policy.showBadges())
                    .showFullScreenIntent(policy.showFullScreenIntents())
                    .showInAmbientDisplay(policy.showAmbient())
                    .showInNotificationList(policy.showInNotificationList())
                    .showLights(policy.showLights())
                    .showPeeking(policy.showPeeking())
                    .showStatusBarIcons(policy.showStatusBarIcons());
        }

        if (Flags.modesApi()) {
            zenPolicyBuilder.allowPriorityChannels(policy.allowPriorityChannels());
        }

        return zenPolicyBuilder.build();
    }

    @ZenPolicy.ConversationSenders
    private static int notificationPolicyConversationSendersToZenPolicy(
            int npPriorityConversationSenders) {
        switch (npPriorityConversationSenders) {
            case Policy.CONVERSATION_SENDERS_ANYONE:
                return ZenPolicy.CONVERSATION_SENDERS_ANYONE;
            case Policy.CONVERSATION_SENDERS_IMPORTANT:
                return ZenPolicy.CONVERSATION_SENDERS_IMPORTANT;
            case Policy.CONVERSATION_SENDERS_NONE:
                return ZenPolicy.CONVERSATION_SENDERS_NONE;
            case Policy.CONVERSATION_SENDERS_UNSET:
            default:
                return ZenPolicy.CONVERSATION_SENDERS_UNSET;
        }
    }
}
