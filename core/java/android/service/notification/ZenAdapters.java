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

package android.service.notification;

import android.annotation.NonNull;
import android.app.Flags;
import android.app.NotificationManager.Policy;

/**
 * Converters between different Zen representations.
 * @hide
 */
public class ZenAdapters {

    /** Maps {@link Policy} to {@link ZenPolicy}. */
    @NonNull
    public static ZenPolicy notificationPolicyToZenPolicy(@NonNull Policy policy) {
        ZenPolicy.Builder zenPolicyBuilder = new ZenPolicy.Builder()
                .allowAlarms(policy.allowAlarms())
                .allowCalls(
                        policy.allowCalls()
                                ? prioritySendersToPeopleType(
                                        policy.allowCallsFrom())
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
                                ? prioritySendersToPeopleType(
                                        policy.allowMessagesFrom())
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

    /** Maps {@link ZenPolicy.PeopleType} enum to {@link Policy.PrioritySenders}. */
    @Policy.PrioritySenders
    public static int peopleTypeToPrioritySenders(
            @ZenPolicy.PeopleType int zpPeopleType, @Policy.PrioritySenders int defaultResult) {
        switch (zpPeopleType) {
            case ZenPolicy.PEOPLE_TYPE_ANYONE:
                return Policy.PRIORITY_SENDERS_ANY;
            case ZenPolicy.PEOPLE_TYPE_CONTACTS:
                return Policy.PRIORITY_SENDERS_CONTACTS;
            case ZenPolicy.PEOPLE_TYPE_STARRED:
                return Policy.PRIORITY_SENDERS_STARRED;
            default:
                return defaultResult;
        }
    }

    /** Maps {@link Policy.PrioritySenders} enum to {@link ZenPolicy.PeopleType}. */
    @ZenPolicy.PeopleType
    public static int prioritySendersToPeopleType(
            @Policy.PrioritySenders int npPrioritySenders) {
        switch (npPrioritySenders) {
            case Policy.PRIORITY_SENDERS_ANY:
                return ZenPolicy.PEOPLE_TYPE_ANYONE;
            case Policy.PRIORITY_SENDERS_CONTACTS:
                return ZenPolicy.PEOPLE_TYPE_CONTACTS;
            case Policy.PRIORITY_SENDERS_STARRED:
            default:
                return ZenPolicy.PEOPLE_TYPE_STARRED;
        }
    }

    /** Maps {@link ZenPolicy.ConversationSenders} enum to {@link Policy.ConversationSenders}. */
    @Policy.ConversationSenders
    public static int zenPolicyConversationSendersToNotificationPolicy(
            @ZenPolicy.ConversationSenders int zpConversationSenders,
            @Policy.ConversationSenders int defaultResult) {
        switch (zpConversationSenders) {
            case ZenPolicy.CONVERSATION_SENDERS_ANYONE:
                return Policy.CONVERSATION_SENDERS_ANYONE;
            case ZenPolicy.CONVERSATION_SENDERS_IMPORTANT:
                return Policy.CONVERSATION_SENDERS_IMPORTANT;
            case ZenPolicy.CONVERSATION_SENDERS_NONE:
                return Policy.CONVERSATION_SENDERS_NONE;
            default:
                return defaultResult;
        }
    }

    /** Maps {@link Policy.ConversationSenders} enum to {@link ZenPolicy.ConversationSenders}. */
    @ZenPolicy.ConversationSenders
    private static int notificationPolicyConversationSendersToZenPolicy(
            @Policy.ConversationSenders int npPriorityConversationSenders) {
        switch (npPriorityConversationSenders) {
            case Policy.CONVERSATION_SENDERS_ANYONE:
                return ZenPolicy.CONVERSATION_SENDERS_ANYONE;
            case Policy.CONVERSATION_SENDERS_IMPORTANT:
                return ZenPolicy.CONVERSATION_SENDERS_IMPORTANT;
            case Policy.CONVERSATION_SENDERS_NONE:
                return ZenPolicy.CONVERSATION_SENDERS_NONE;
            default: // including Policy.CONVERSATION_SENDERS_UNSET
                return ZenPolicy.CONVERSATION_SENDERS_UNSET;
        }
    }
}
