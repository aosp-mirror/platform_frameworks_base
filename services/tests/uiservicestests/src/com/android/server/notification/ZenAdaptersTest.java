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

import static com.android.server.notification.ZenAdapters.notificationPolicyToZenPolicy;

import static com.google.common.truth.Truth.assertThat;

import android.app.Flags;
import android.app.NotificationManager.Policy;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.ZenPolicy;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ZenAdaptersTest extends UiServiceTestCase {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    public void notificationPolicyToZenPolicy_allCallers() {
        Policy policy = new Policy(Policy.PRIORITY_CATEGORY_CALLS, Policy.PRIORITY_SENDERS_ANY, 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getPriorityCategoryCalls()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(zenPolicy.getPriorityCallSenders()).isEqualTo(ZenPolicy.PEOPLE_TYPE_ANYONE);
    }

    @Test
    public void notificationPolicyToZenPolicy_starredCallers() {
        Policy policy = new Policy(Policy.PRIORITY_CATEGORY_CALLS, Policy.PRIORITY_SENDERS_STARRED,
                0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getPriorityCategoryCalls()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(zenPolicy.getPriorityCallSenders()).isEqualTo(ZenPolicy.PEOPLE_TYPE_STARRED);
    }

    @Test
    public void notificationPolicyToZenPolicy_repeatCallers() {
        Policy policy = new Policy(Policy.PRIORITY_CATEGORY_REPEAT_CALLERS, 0, 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getPriorityCategoryCalls()).isEqualTo(ZenPolicy.STATE_DISALLOW);
        assertThat(zenPolicy.getPriorityCategoryRepeatCallers()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(zenPolicy.getPriorityCallSenders()).isEqualTo(ZenPolicy.PEOPLE_TYPE_NONE);
    }

    @Test
    public void notificationPolicyToZenPolicy_noCallers() {
        Policy policy = new Policy(0, 0, 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getPriorityCategoryCalls()).isEqualTo(ZenPolicy.STATE_DISALLOW);
        assertThat(zenPolicy.getPriorityCallSenders()).isEqualTo(ZenPolicy.PEOPLE_TYPE_NONE);
    }

    @Test
    public void notificationPolicyToZenPolicy_conversationsAllowedSendersUnset() {
        Policy policy = new Policy(Policy.PRIORITY_CATEGORY_CONVERSATIONS, 0, 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getPriorityCategoryConversations()).isEqualTo(ZenPolicy.STATE_UNSET);
    }

    @Test
    public void notificationPolicyToZenPolicy_conversationsNotAllowedSendersUnset() {
        Policy policy = new Policy(0, 0, 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getPriorityCategoryConversations()).isEqualTo(
                ZenPolicy.STATE_DISALLOW);
    }

    @Test
    public void notificationPolicyToZenPolicy_setEffects() {
        Policy policy = new Policy(0, 0, 0,
                Policy.SUPPRESSED_EFFECT_BADGE | Policy.SUPPRESSED_EFFECT_LIGHTS);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getVisualEffectBadge()).isEqualTo(ZenPolicy.STATE_DISALLOW);
        assertThat(zenPolicy.getVisualEffectLights()).isEqualTo(ZenPolicy.STATE_DISALLOW);

        assertThat(zenPolicy.getVisualEffectAmbient()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(zenPolicy.getVisualEffectFullScreenIntent()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(zenPolicy.getVisualEffectNotificationList()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(zenPolicy.getVisualEffectPeek()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(zenPolicy.getVisualEffectStatusBar()).isEqualTo(ZenPolicy.STATE_ALLOW);
    }

    @Test
    public void notificationPolicyToZenPolicy_unsetEffects() {
        Policy policy = new Policy(0, 0, 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);

        assertThat(zenPolicy.getVisualEffectAmbient()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(zenPolicy.getVisualEffectBadge()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(zenPolicy.getVisualEffectFullScreenIntent()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(zenPolicy.getVisualEffectLights()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(zenPolicy.getVisualEffectNotificationList()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(zenPolicy.getVisualEffectPeek()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(zenPolicy.getVisualEffectStatusBar()).isEqualTo(ZenPolicy.STATE_UNSET);
    }

    @Test
    @EnableFlags(Flags.FLAG_MODES_API)
    public void notificationPolicyToZenPolicy_modesApi_priorityChannels() {
        Policy policy = new Policy(0, 0, 0, 0,
                Policy.policyState(false, true), 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);
        assertThat(zenPolicy.getAllowedChannels()).isEqualTo(ZenPolicy.CHANNEL_TYPE_PRIORITY);

        Policy notAllowed = new Policy(0, 0, 0, 0,
                Policy.policyState(false, false), 0);
        ZenPolicy zenPolicyNotAllowed = notificationPolicyToZenPolicy(notAllowed);
        assertThat(zenPolicyNotAllowed.getAllowedChannels()).isEqualTo(ZenPolicy.CHANNEL_TYPE_NONE);
    }

    @Test
    @DisableFlags(Flags.FLAG_MODES_API)
    public void notificationPolicyToZenPolicy_noModesApi_priorityChannelsUnset() {
        Policy policy = new Policy(0, 0, 0, 0,
                Policy.policyState(false, true), 0);

        ZenPolicy zenPolicy = notificationPolicyToZenPolicy(policy);
        assertThat(zenPolicy.getAllowedChannels()).isEqualTo(ZenPolicy.CHANNEL_TYPE_UNSET);

        Policy notAllowed = new Policy(0, 0, 0, 0,
                Policy.policyState(false, false), 0);
        ZenPolicy zenPolicyNotAllowed = notificationPolicyToZenPolicy(notAllowed);
        assertThat(zenPolicyNotAllowed.getAllowedChannels())
                .isEqualTo(ZenPolicy.CHANNEL_TYPE_UNSET);
    }
}
