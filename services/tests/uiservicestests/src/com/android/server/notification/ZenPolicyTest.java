/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distriZenbuted on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.notification;

import static junit.framework.Assert.assertEquals;

import android.service.notification.ZenPolicy;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ZenPolicyTest extends UiServiceTestCase {

    @Test
    public void testZenPolicyApplyAllowedToDisallowed() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // reminders are disallowed
        builder.allowReminders(false);
        ZenPolicy remindersDisallowed = builder.build();
        assertEquals(ZenPolicy.STATE_DISALLOW,
                remindersDisallowed.getPriorityCategoryReminders());

        // reminders are allowed
        builder.allowReminders(true);
        ZenPolicy remindersAllowed = builder.build();
        assertEquals(ZenPolicy.STATE_ALLOW,
                remindersAllowed.getPriorityCategoryReminders());
        assertEquals(ZenPolicy.STATE_DISALLOW,
                remindersDisallowed.getPriorityCategoryReminders());

        // we apply reminders allowed to reminders disallowed
        // -> reminders should remain disallowed
        remindersDisallowed.apply(remindersAllowed);
        assertEquals(ZenPolicy.STATE_DISALLOW,
                remindersDisallowed.getPriorityCategoryReminders());
    }

    @Test
    public void testZenPolicyApplyAllowedToUnset() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // reminders are unset
        ZenPolicy remindersUnset = builder.build();

        // reminders are allowed
        builder.allowReminders(true);
        ZenPolicy remindersAllowed = builder.build();

        // we apply reminders allowed to reminders unset
        // -> reminders should be allowed
        remindersUnset.apply(remindersAllowed);
        assertEquals(ZenPolicy.STATE_ALLOW, remindersUnset.getPriorityCategoryReminders());
    }

    @Test
    public void testZenPolicyApplyDisallowedToUnset() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // reminders are unset
        ZenPolicy remindersUnset = builder.build();

        // reminders are disallowed
        builder.allowReminders(false);
        ZenPolicy remindersDisallowed = builder.build();

        // we apply reminders disallowed to reminders unset
        // -> reminders should remain disallowed
        remindersUnset.apply(remindersDisallowed);
        assertEquals(ZenPolicy.STATE_DISALLOW,
                remindersUnset.getPriorityCategoryReminders());
    }

    @Test
    public void testZenPolicyApplyDisallowedToAllowed() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // reminders are allowed
        builder.allowReminders(true);
        ZenPolicy remindersAllowed = builder.build();

        // reminders are disallowed
        builder.allowReminders(false);
        ZenPolicy remindersDisallowed = builder.build();

        // we apply reminders allowed to reminders disallowed
        // -> reminders should change to disallowed
        remindersAllowed.apply(remindersDisallowed);
        assertEquals(ZenPolicy.STATE_DISALLOW, remindersAllowed.getPriorityCategoryReminders());
    }

    @Test
    public void testZenPolicyApplyUnsetToAllowed() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // reminders are allowed
        builder.allowReminders(true);
        ZenPolicy remindersAllowed = builder.build();

        // reminders are unset
        ZenPolicy.Builder builder2 = new ZenPolicy.Builder();
        ZenPolicy remindersUnset = builder2.build();

        // we apply reminders allowed to reminders unset
        // -> reminders should remain allowed
        remindersAllowed.apply(remindersUnset);
        assertEquals(ZenPolicy.STATE_ALLOW, remindersAllowed.getPriorityCategoryReminders());
    }

    @Test
    public void testZenPolicyApplyMoreSevereCallSenders() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // calls from contacts allowed
        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS);
        ZenPolicy contactsAllowed = builder.build();

        // calls from starred contacts allowed
        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_STARRED);
        ZenPolicy starredAllowed = builder.build();

        // we apply starredAllowed to contactsAllowed -> starred contacts allowed (more restrictive)
        contactsAllowed.apply(starredAllowed);
        assertEquals(ZenPolicy.STATE_ALLOW, contactsAllowed.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_STARRED, contactsAllowed.getPriorityCallSenders());
    }

    @Test
    public void testZenPolicyApplyLessSevereCallSenders() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // calls from contacts allowed
        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS);
        ZenPolicy contactsAllowed = builder.build();

        // calls from anyone allowed
        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE);
        ZenPolicy anyoneAllowed = builder.build();

        // we apply anyoneAllowed to contactsAllowed -> contactsAllowed (more restrictive)
        contactsAllowed.apply(anyoneAllowed);
        assertEquals(ZenPolicy.STATE_ALLOW, contactsAllowed.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_CONTACTS, contactsAllowed.getPriorityCallSenders());
    }

    @Test
    public void testZenPolicyApplyMoreSevereMessageSenders() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        // messsages from contacts allowed
        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_CONTACTS);
        ZenPolicy contactsAllowed = builder.build();

        // messsages from no one allowed
        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_NONE);
        ZenPolicy noneAllowed = builder.build();

        // noneAllowed to contactsAllowed -> no messages allowed (more restrictive)
        contactsAllowed.apply(noneAllowed);
        assertEquals(ZenPolicy.STATE_DISALLOW, contactsAllowed.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, contactsAllowed.getPriorityMessageSenders());
    }

    @Test
    public void testZenPolicyMessagesInvalid() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowMessages(20); // invalid #, won't change policy
        ZenPolicy policy = builder.build();
        assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_UNSET, policy.getPriorityMessageSenders());
    }

    @Test
    public void testZenPolicyCallsInvalid() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE);
        builder.allowCalls(20); // invalid #, won't change policy
        ZenPolicy policy = builder.build();
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_ANYONE, policy.getPriorityCallSenders());
    }

    @Test
    public void testEmptyZenPolicy() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, -1);
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowReminders() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowReminders(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REMINDERS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryReminders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowReminders(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REMINDERS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryReminders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowEvents() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowEvents(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_EVENTS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryEvents());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowEvents(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_EVENTS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryEvents());
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowMessages() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_ANYONE);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MESSAGES);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_ANYONE, policy.getPriorityMessageSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_CONTACTS);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MESSAGES);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_CONTACTS, policy.getPriorityMessageSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_STARRED);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MESSAGES);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_STARRED, policy.getPriorityMessageSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_NONE);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MESSAGES);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, policy.getPriorityMessageSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_UNSET);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, -1);
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowCalls() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_CALLS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_ANYONE, policy.getPriorityCallSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_CALLS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_CONTACTS, policy.getPriorityCallSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_STARRED);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_CALLS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_STARRED, policy.getPriorityCallSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_NONE);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_CALLS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, policy.getPriorityCallSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_UNSET);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, -1);
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowRepeatCallers() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowRepeatCallers(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REPEAT_CALLERS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryRepeatCallers());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowRepeatCallers(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REPEAT_CALLERS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryRepeatCallers());
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowAlarms() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowAlarms(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_ALARMS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryAlarms());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowAlarms(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_ALARMS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryAlarms());
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowMedia() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowMedia(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MEDIA);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryMedia());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowMedia(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MEDIA);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryMedia());
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    @Test
    public void testAllowSystem() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowSystem(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_SYSTEM);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategorySystem());
        assertAllVisualEffectsUnsetExcept(policy, -1);

        builder.allowSystem(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_SYSTEM);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategorySystem());
        assertAllVisualEffectsUnsetExcept(policy, -1);
    }

    private void assertAllPriorityCategoriesUnsetExcept(ZenPolicy policy, int except) {
        if (except != ZenPolicy.PRIORITY_CATEGORY_REMINDERS) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryReminders());
        }

        if (except != ZenPolicy.PRIORITY_CATEGORY_EVENTS) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryEvents());
        }

        if (except != ZenPolicy.PRIORITY_CATEGORY_MESSAGES) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryMessages());
        }

        if (except != ZenPolicy.PRIORITY_CATEGORY_CALLS) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryCalls());
        }

        if (except != ZenPolicy.PRIORITY_CATEGORY_REPEAT_CALLERS) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryRepeatCallers());
        }

        if (except != ZenPolicy.PRIORITY_CATEGORY_ALARMS) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryAlarms());
        }

        if (except != ZenPolicy.PRIORITY_CATEGORY_MEDIA) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryMedia());
        }

        if (except != ZenPolicy.PRIORITY_CATEGORY_SYSTEM) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategorySystem());
        }
    }

    private void assertAllVisualEffectsUnsetExcept(ZenPolicy policy, int except) {
        if (except != ZenPolicy.VISUAL_EFFECT_FULL_SCREEN_INTENT) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getVisualEffectFullScreenIntent());
        }

        if (except != ZenPolicy.VISUAL_EFFECT_LIGHTS) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getVisualEffectLights());
        }

        if (except != ZenPolicy.VISUAL_EFFECT_PEEK) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getVisualEffectPeek());
        }

        if (except != ZenPolicy.VISUAL_EFFECT_STATUS_BAR) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getVisualEffectStatusBar());
        }

        if (except != ZenPolicy.VISUAL_EFFECT_BADGE) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getVisualEffectBadge());
        }

        if (except != ZenPolicy.VISUAL_EFFECT_AMBIENT) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getVisualEffectAmbient());
        }

        if (except != ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST) {
            assertEquals(ZenPolicy.STATE_UNSET, policy.getVisualEffectNotificationList());
        }
    }
}
