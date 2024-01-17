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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import android.app.Flags;
import android.os.Parcel;
import android.platform.test.flag.junit.SetFlagsRule;
import android.service.notification.ZenPolicy;
import android.service.notification.nano.DNDPolicyProto;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import com.google.protobuf.nano.InvalidProtocolBufferNanoException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ZenPolicyTest extends UiServiceTestCase {
    private static final String CLASS = "android.service.notification.ZenPolicy";

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Before
    public final void setUp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
    }

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
    public void testZenPolicyApplyChannels_applyUnset() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        ZenPolicy unset = builder.build();

        // priority channels allowed
        builder.allowPriorityChannels(true);
        ZenPolicy channelsPriority = builder.build();

        // unset applied, channels setting keeps its state
        channelsPriority.apply(unset);
        assertThat(channelsPriority.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_ALLOW);
    }

    @Test
    public void testZenPolicyApplyChannels_applyStricter() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        builder.allowPriorityChannels(false);
        ZenPolicy none = builder.build();

        builder.allowPriorityChannels(true);
        ZenPolicy priority = builder.build();

        // priority channels (less strict state) cannot override a setting that sets it to none
        none.apply(priority);
        assertThat(none.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_DISALLOW);
    }

    @Test
    public void testZenPolicyApplyChannels_applyLooser() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        builder.allowPriorityChannels(false);
        ZenPolicy none = builder.build();

        builder.allowPriorityChannels(true);
        ZenPolicy priority = builder.build();

        // applying a policy with channelType=none overrides priority setting
        priority.apply(none);
        assertThat(priority.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_DISALLOW);
    }

    @Test
    public void testZenPolicyApplyChannels_applySet() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        ZenPolicy unset = builder.build();

        builder.allowPriorityChannels(true);
        ZenPolicy priority = builder.build();

        // applying a policy with a set channel type actually goes through
        unset.apply(priority);
        assertThat(unset.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_ALLOW);
    }

    @Test
    public void testZenPolicyMessagesInvalid() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowMessages(20); // invalid #, won't change policy
        ZenPolicy policy = builder.build();
        assertEquals(ZenPolicy.STATE_UNSET, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_UNSET, policy.getPriorityMessageSenders());
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testZenPolicyCallsInvalid() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_ANYONE);
        builder.allowCalls(20); // invalid #, won't change policy
        ZenPolicy policy = builder.build();
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_ANYONE, policy.getPriorityCallSenders());
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testEmptyZenPolicy() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, -1);
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testEmptyZenPolicy_emptyChannels() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        ZenPolicy policy = builder.build();
        assertThat(policy.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_UNSET);
    }

    @Test
    public void testAllowReminders() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowReminders(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REMINDERS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryReminders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowReminders(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REMINDERS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryReminders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testAllowEvents() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowEvents(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_EVENTS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryEvents());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowEvents(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_EVENTS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryEvents());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
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
        assertProtoMatches(policy, policy.toProto());

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_CONTACTS);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MESSAGES);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_CONTACTS, policy.getPriorityMessageSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_STARRED);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MESSAGES);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_STARRED, policy.getPriorityMessageSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_NONE);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MESSAGES);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryMessages());
        assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, policy.getPriorityMessageSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowMessages(ZenPolicy.PEOPLE_TYPE_UNSET);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, -1);
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
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
        assertProtoMatches(policy, policy.toProto());

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_CALLS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_CONTACTS, policy.getPriorityCallSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_STARRED);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_CALLS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_STARRED, policy.getPriorityCallSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_NONE);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_CALLS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryCalls());
        assertEquals(ZenPolicy.PEOPLE_TYPE_NONE, policy.getPriorityCallSenders());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowCalls(ZenPolicy.PEOPLE_TYPE_UNSET);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, -1);
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testAllowRepeatCallers() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowRepeatCallers(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REPEAT_CALLERS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryRepeatCallers());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowRepeatCallers(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_REPEAT_CALLERS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryRepeatCallers());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testAllowAlarms() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowAlarms(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_ALARMS);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryAlarms());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowAlarms(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_ALARMS);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryAlarms());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testAllowMedia() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowMedia(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MEDIA);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategoryMedia());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowMedia(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_MEDIA);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategoryMedia());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testAllowSystem() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.allowSystem(true);
        ZenPolicy policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_SYSTEM);
        assertEquals(ZenPolicy.STATE_ALLOW, policy.getPriorityCategorySystem());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());

        builder.allowSystem(false);
        policy = builder.build();
        assertAllPriorityCategoriesUnsetExcept(policy, ZenPolicy.PRIORITY_CATEGORY_SYSTEM);
        assertEquals(ZenPolicy.STATE_DISALLOW, policy.getPriorityCategorySystem());
        assertAllVisualEffectsUnsetExcept(policy, -1);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void tesShowFullScreenIntent() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.showFullScreenIntent(true);
        ZenPolicy policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_FULL_SCREEN_INTENT);
        assertProtoMatches(policy, policy.toProto());

        builder.showFullScreenIntent(false);
        policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_FULL_SCREEN_INTENT);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void tesShowLights() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.showLights(true);
        ZenPolicy policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_LIGHTS);
        assertProtoMatches(policy, policy.toProto());

        builder.showLights(false);
        policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_LIGHTS);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void tesShowPeeking() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.showPeeking(true);
        ZenPolicy policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_PEEK);
        assertProtoMatches(policy, policy.toProto());

        builder.showPeeking(false);
        policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_PEEK);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void tesShowStatusBarIcons() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.showStatusBarIcons(true);
        ZenPolicy policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_STATUS_BAR);
        assertProtoMatches(policy, policy.toProto());

        builder.showStatusBarIcons(false);
        policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_STATUS_BAR);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void tesShowBadges() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.showBadges(true);
        ZenPolicy policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_BADGE);
        assertProtoMatches(policy, policy.toProto());

        builder.showBadges(false);
        policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_BADGE);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void tesShowInAmbientDisplay() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.showInAmbientDisplay(true);
        ZenPolicy policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_AMBIENT);
        assertProtoMatches(policy, policy.toProto());

        builder.showInAmbientDisplay(false);
        policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_AMBIENT);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void tesShowInNotificationList() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();

        builder.showInNotificationList(true);
        ZenPolicy policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST);
        assertProtoMatches(policy, policy.toProto());

        builder.showInNotificationList(false);
        policy = builder.build();
        assertAllVisualEffectsUnsetExcept(policy, ZenPolicy.VISUAL_EFFECT_NOTIFICATION_LIST);
        assertProtoMatches(policy, policy.toProto());
    }

    @Test
    public void testAllowChannels_noFlag() {
        mSetFlagsRule.disableFlags(Flags.FLAG_MODES_API);

        // allowChannels should be unset, not be modifiable, and not show up in any output
        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        builder.allowPriorityChannels(true);
        ZenPolicy policy = builder.build();

        assertThat(policy.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(policy.toString().contains("allowChannels")).isFalse();
    }

    @Test
    public void testAllowChannels() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        // allow priority channels
        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        builder.allowPriorityChannels(true);
        ZenPolicy policy = builder.build();
        assertThat(policy.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_ALLOW);

        // disallow priority channels
        builder.allowPriorityChannels(false);
        policy = builder.build();
        assertThat(policy.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_DISALLOW);
    }

    @Test
    public void testFromParcel() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        builder.setUserModifiedFields(10);

        ZenPolicy policy = builder.build();
        assertThat(policy.getUserModifiedFields()).isEqualTo(10);

        Parcel parcel = Parcel.obtain();
        policy.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ZenPolicy fromParcel = ZenPolicy.CREATOR.createFromParcel(parcel);
        assertThat(fromParcel.getUserModifiedFields()).isEqualTo(10);
    }

    @Test
    public void testPolicy_userModifiedFields() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        builder.setUserModifiedFields(10);
        assertThat(builder.build().getUserModifiedFields()).isEqualTo(10);

        builder.setUserModifiedFields(0);
        assertThat(builder.build().getUserModifiedFields()).isEqualTo(0);
    }

    @Test
    public void testPolicyBuilder_constructFromPolicy() {
        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        ZenPolicy policy = builder.allowRepeatCallers(true).allowAlarms(false)
                .showLights(true).showBadges(false)
                .allowPriorityChannels(true)
                .setUserModifiedFields(20).build();

        ZenPolicy newPolicy = new ZenPolicy.Builder(policy).build();

        assertThat(newPolicy.getPriorityCategoryAlarms()).isEqualTo(ZenPolicy.STATE_DISALLOW);
        assertThat(newPolicy.getPriorityCategoryCalls()).isEqualTo(ZenPolicy.STATE_UNSET);
        assertThat(newPolicy.getPriorityCategoryRepeatCallers()).isEqualTo(ZenPolicy.STATE_ALLOW);

        assertThat(newPolicy.getVisualEffectLights()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(newPolicy.getVisualEffectBadge()).isEqualTo(ZenPolicy.STATE_DISALLOW);
        assertThat(newPolicy.getVisualEffectPeek()).isEqualTo(ZenPolicy.STATE_UNSET);

        assertThat(newPolicy.getPriorityChannels()).isEqualTo(ZenPolicy.STATE_ALLOW);
        assertThat(newPolicy.getUserModifiedFields()).isEqualTo(20);
    }

    @Test
    public void testTooLongLists_fromParcel() {
        ArrayList<Integer> longList = new ArrayList<Integer>(50);
        for (int i = 0; i < 50; i++) {
            longList.add(ZenPolicy.STATE_UNSET);
        }

        ZenPolicy.Builder builder = new ZenPolicy.Builder();
        ZenPolicy policy = builder.build();

        try {
            Field priorityCategories = Class.forName(CLASS).getDeclaredField(
                    "mPriorityCategories");
            priorityCategories.setAccessible(true);
            priorityCategories.set(policy, longList);

            Field visualEffects = Class.forName(CLASS).getDeclaredField("mVisualEffects");
            visualEffects.setAccessible(true);
            visualEffects.set(policy, longList);
        } catch (NoSuchFieldException e) {
            fail(e.toString());
        } catch (ClassNotFoundException e) {
            fail(e.toString());
        } catch (IllegalAccessException e) {
            fail(e.toString());
        }

        Parcel parcel = Parcel.obtain();
        policy.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        ZenPolicy fromParcel = ZenPolicy.CREATOR.createFromParcel(parcel);

        // Confirm that all the fields are accessible and UNSET
        assertAllPriorityCategoriesUnsetExcept(fromParcel, -1);
        assertAllVisualEffectsUnsetExcept(fromParcel, -1);

        // Because we don't access the lists directly, we also need to use reflection to make sure
        // the lists are the right length.
        try {
            Field priorityCategories = Class.forName(CLASS).getDeclaredField(
                    "mPriorityCategories");
            priorityCategories.setAccessible(true);
            ArrayList<Integer> pcList = (ArrayList<Integer>) priorityCategories.get(fromParcel);
            assertEquals(ZenPolicy.NUM_PRIORITY_CATEGORIES, pcList.size());


            Field visualEffects = Class.forName(CLASS).getDeclaredField("mVisualEffects");
            visualEffects.setAccessible(true);
            ArrayList<Integer> veList = (ArrayList<Integer>) visualEffects.get(fromParcel);
            assertEquals(ZenPolicy.NUM_VISUAL_EFFECTS, veList.size());
        } catch (NoSuchFieldException e) {
            fail(e.toString());
        } catch (ClassNotFoundException e) {
            fail(e.toString());
        } catch (IllegalAccessException e) {
            fail(e.toString());
        }
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

    private void assertProtoMatches(ZenPolicy policy, byte[] bytes) {
        try {
            DNDPolicyProto proto = DNDPolicyProto.parseFrom(bytes);

            assertEquals(policy.getPriorityCategoryCalls(), proto.calls);
            assertEquals(policy.getPriorityCategoryRepeatCallers(), proto.repeatCallers);
            assertEquals(policy.getPriorityCategoryMessages(), proto.messages);
            assertEquals(policy.getPriorityCategoryConversations(), proto.conversations);
            assertEquals(policy.getPriorityCategoryReminders(), proto.reminders);
            assertEquals(policy.getPriorityCategoryEvents(), proto.events);
            assertEquals(policy.getPriorityCategoryAlarms(), proto.alarms);
            assertEquals(policy.getPriorityCategoryMedia(), proto.media);
            assertEquals(policy.getPriorityCategorySystem(), proto.system);

            assertEquals(policy.getVisualEffectFullScreenIntent(), proto.fullscreen);
            assertEquals(policy.getVisualEffectLights(), proto.lights);
            assertEquals(policy.getVisualEffectPeek(), proto.peek);
            assertEquals(policy.getVisualEffectStatusBar(), proto.statusBar);
            assertEquals(policy.getVisualEffectBadge(), proto.badge);
            assertEquals(policy.getVisualEffectAmbient(), proto.ambient);
            assertEquals(policy.getVisualEffectNotificationList(), proto.notificationList);

            assertEquals(policy.getPriorityCallSenders(), proto.allowCallsFrom);
            assertEquals(policy.getPriorityMessageSenders(), proto.allowMessagesFrom);
            assertEquals(policy.getPriorityConversationSenders(), proto.allowConversationsFrom);
        } catch (InvalidProtocolBufferNanoException e) {
            fail("could not parse proto bytes");
        }

    }
}
