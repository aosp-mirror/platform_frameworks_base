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

import static android.app.AutomaticZenRule.TYPE_BEDTIME;
import static android.service.notification.ZenPolicy.CONVERSATION_SENDERS_IMPORTANT;

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNotNull;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import android.app.AutomaticZenRule;
import android.app.Flags;
import android.app.NotificationManager.Policy;
import android.content.ComponentName;
import android.net.Uri;
import android.os.Parcel;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenDeviceEffects;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.EventInfo;
import android.service.notification.ZenPolicy;
import android.util.Xml;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;
import com.android.server.UiServiceTestCase;

import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ZenModeConfigTest extends UiServiceTestCase {

    private final String NAME = "name";
    private final ComponentName OWNER = new ComponentName("pkg", "cls");
    private final ComponentName CONFIG_ACTIVITY = new ComponentName("pkg", "act");
    private final ZenPolicy POLICY = new ZenPolicy.Builder().allowAlarms(true).build();
    private final Uri CONDITION_ID = new Uri.Builder().scheme("scheme")
            .authority("authority")
            .appendPath("path")
            .appendPath("test")
            .build();

    private final Condition CONDITION = new Condition(CONDITION_ID, "", Condition.STATE_TRUE);
    private final String TRIGGER_DESC = "Every Night, 10pm to 6am";
    private final int TYPE = TYPE_BEDTIME;
    private final boolean ALLOW_MANUAL = true;
    private final String ICON_RES_NAME = "icon_res";
    private final int INTERRUPTION_FILTER = Settings.Global.ZEN_MODE_ALARMS;
    private final boolean ENABLED = true;
    private final int CREATION_TIME = 123;

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule(
            SetFlagsRule.DefaultInitValueType.DEVICE_DEFAULT);

    @Before
    public final void setUp() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
    }

    @Test
    public void testPriorityOnlyMutingAllNotifications() {
        ZenModeConfig config = getMutedRingerConfig();
        assertTrue(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));

        config.allowReminders = true;
        assertFalse(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
        config.allowReminders = false;

        config.areChannelsBypassingDnd = true;
        assertFalse(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
        config.areChannelsBypassingDnd = false;

        assertTrue(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
    }

    @Test
    public void testZenPolicyNothingSetToNotificationPolicy() {
        ZenModeConfig config = getCustomConfig();
        ZenPolicy zenPolicy = new ZenPolicy.Builder().build();
        assertEquals(config.toNotificationPolicy(), config.toNotificationPolicy(zenPolicy));
    }

    @Test
    public void testZenPolicyToNotificationPolicy_classic() {
        ZenModeConfig config = getMutedAllConfig();
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_BADGE;

        // Explicitly allow conversations from priority senders to make sure that goes through
        ZenPolicy zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowReminders(true)
                .allowEvents(true)
                .allowConversations(CONVERSATION_SENDERS_IMPORTANT)
                .showLights(false)
                .showInAmbientDisplay(false)
                .build();

        Policy originalPolicy = config.toNotificationPolicy();
        int priorityCategories = originalPolicy.priorityCategories;
        int priorityCallSenders = originalPolicy.priorityCallSenders;
        int priorityMessageSenders = originalPolicy.priorityMessageSenders;
        int priorityConversationsSenders = CONVERSATION_SENDERS_IMPORTANT;
        int suppressedVisualEffects = originalPolicy.suppressedVisualEffects;
        priorityCategories |= Policy.PRIORITY_CATEGORY_ALARMS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_REMINDERS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_EVENTS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_CONVERSATIONS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_LIGHTS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_AMBIENT;

        Policy expectedPolicy = new Policy(priorityCategories, priorityCallSenders,
                priorityMessageSenders, suppressedVisualEffects, 0, priorityConversationsSenders);
        assertEquals(expectedPolicy, config.toNotificationPolicy(zenPolicy));
    }

    @Test
    public void testZenPolicyToNotificationPolicy() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenModeConfig config = getMutedAllConfig();
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_BADGE;

        // Explicitly allow conversations from priority senders to make sure that goes through
        // Explicitly disallow channels to make sure that goes through, too
        ZenPolicy zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowReminders(true)
                .allowEvents(true)
                .allowConversations(CONVERSATION_SENDERS_IMPORTANT)
                .showLights(false)
                .showInAmbientDisplay(false)
                .allowPriorityChannels(false)
                .build();

        Policy originalPolicy = config.toNotificationPolicy();
        int priorityCategories = originalPolicy.priorityCategories;
        int priorityCallSenders = originalPolicy.priorityCallSenders;
        int priorityMessageSenders = originalPolicy.priorityMessageSenders;
        int priorityConversationsSenders = CONVERSATION_SENDERS_IMPORTANT;
        int suppressedVisualEffects = originalPolicy.suppressedVisualEffects;
        priorityCategories |= Policy.PRIORITY_CATEGORY_ALARMS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_REMINDERS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_EVENTS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_CONVERSATIONS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_LIGHTS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_AMBIENT;

        Policy expectedPolicy = new Policy(priorityCategories, priorityCallSenders,
                priorityMessageSenders, suppressedVisualEffects,
                Policy.STATE_PRIORITY_CHANNELS_BLOCKED, priorityConversationsSenders);
        assertEquals(expectedPolicy, config.toNotificationPolicy(zenPolicy));

        // make sure allowChannels=false has gotten through correctly (also covered above)
        assertFalse(expectedPolicy.allowPriorityChannels());
    }

    @Test
    public void testZenPolicyToNotificationPolicy_unsetChannelsTakesDefault() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenModeConfig config = new ZenModeConfig();
        ZenPolicy zenPolicy = new ZenPolicy.Builder().build();

        // When allowChannels is not set to anything in the ZenPolicy builder, make sure it takes
        // the default value from the zen mode config.
        Policy policy = config.toNotificationPolicy(zenPolicy);
        assertEquals(config.allowPriorityChannels, policy.allowPriorityChannels());
    }

    @Test
    public void testZenConfigToZenPolicy_classic() {
        ZenPolicy expected = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowReminders(true)
                .allowEvents(true)
                .showLights(false)
                .showBadges(false)
                .showInAmbientDisplay(false)
                .allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_STARRED)
                .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
                .build();

        ZenModeConfig config = getMutedAllConfig();
        config.allowAlarms = true;
        config.allowReminders = true;
        config.allowEvents = true;
        config.allowCalls = true;
        config.allowCallsFrom = Policy.PRIORITY_SENDERS_CONTACTS;
        config.allowMessages = true;
        config.allowMessagesFrom = Policy.PRIORITY_SENDERS_STARRED;
        config.allowConversations = false;
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_BADGE;
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_LIGHTS;
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_AMBIENT;
        ZenPolicy actual = config.toZenPolicy();

        assertEquals(expected.getVisualEffectBadge(), actual.getVisualEffectBadge());
        assertEquals(expected.getPriorityCategoryAlarms(), actual.getPriorityCategoryAlarms());
        assertEquals(expected.getPriorityCategoryReminders(),
                actual.getPriorityCategoryReminders());
        assertEquals(expected.getPriorityCategoryEvents(), actual.getPriorityCategoryEvents());
        assertEquals(expected.getVisualEffectLights(), actual.getVisualEffectLights());
        assertEquals(expected.getVisualEffectAmbient(), actual.getVisualEffectAmbient());
        assertEquals(expected.getPriorityConversationSenders(),
                actual.getPriorityConversationSenders());
        assertEquals(expected.getPriorityCallSenders(), actual.getPriorityCallSenders());
        assertEquals(expected.getPriorityMessageSenders(), actual.getPriorityMessageSenders());
    }

    @Test
    public void testZenConfigToZenPolicy() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);
        ZenPolicy expected = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowReminders(true)
                .allowEvents(true)
                .showLights(false)
                .showBadges(false)
                .showInAmbientDisplay(false)
                .allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_STARRED)
                .allowConversations(ZenPolicy.CONVERSATION_SENDERS_NONE)
                .allowPriorityChannels(false)
                .build();

        ZenModeConfig config = getMutedAllConfig();
        config.allowAlarms = true;
        config.allowReminders = true;
        config.allowEvents = true;
        config.allowCalls = true;
        config.allowCallsFrom = Policy.PRIORITY_SENDERS_CONTACTS;
        config.allowMessages = true;
        config.allowMessagesFrom = Policy.PRIORITY_SENDERS_STARRED;
        config.allowConversations = false;
        config.allowPriorityChannels = false;
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_BADGE;
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_LIGHTS;
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_AMBIENT;
        ZenPolicy actual = config.toZenPolicy();

        assertEquals(expected.getVisualEffectBadge(), actual.getVisualEffectBadge());
        assertEquals(expected.getPriorityCategoryAlarms(), actual.getPriorityCategoryAlarms());
        assertEquals(expected.getPriorityCategoryReminders(),
                actual.getPriorityCategoryReminders());
        assertEquals(expected.getPriorityCategoryEvents(), actual.getPriorityCategoryEvents());
        assertEquals(expected.getVisualEffectLights(), actual.getVisualEffectLights());
        assertEquals(expected.getVisualEffectAmbient(), actual.getVisualEffectAmbient());
        assertEquals(expected.getPriorityConversationSenders(),
                actual.getPriorityConversationSenders());
        assertEquals(expected.getPriorityCallSenders(), actual.getPriorityCallSenders());
        assertEquals(expected.getPriorityMessageSenders(), actual.getPriorityMessageSenders());
        assertEquals(expected.getPriorityChannelsAllowed(), actual.getPriorityChannelsAllowed());
    }

    @Test
    public void testPriorityOnlyMutingAll() {
        ZenModeConfig config = getMutedAllConfig();
        assertTrue(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
        assertTrue(ZenModeConfig.areAllZenBehaviorSoundsMuted(config));

        config.allowReminders = true;
        assertFalse(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
        assertFalse(ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
        config.allowReminders = false;

        config.areChannelsBypassingDnd = true;
        assertFalse(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
        assertFalse(ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
        config.areChannelsBypassingDnd = false;

        config.allowAlarms = true;
        assertTrue(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
        assertFalse(ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
        config.allowAlarms = false;

        assertTrue(ZenModeConfig.areAllPriorityOnlyRingerSoundsMuted(config));
        assertTrue(ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
    }

    @Test
    public void testParseOldEvent() {
        EventInfo oldEvent = new EventInfo();
        oldEvent.userId = 1;
        oldEvent.calName = "calName";
        oldEvent.calendarId = null; // old events will have null ids

        Uri conditionId = ZenModeConfig.toEventConditionId(oldEvent);
        EventInfo eventParsed = ZenModeConfig.tryParseEventConditionId(conditionId);
        assertEquals(oldEvent, eventParsed);
    }

    @Test
    public void testParseNewEvent() {
        EventInfo event = new EventInfo();
        event.userId = 1;
        event.calName = "calName";
        event.calendarId = 12345L;

        Uri conditionId = ZenModeConfig.toEventConditionId(event);
        EventInfo eventParsed = ZenModeConfig.tryParseEventConditionId(conditionId);
        assertEquals(event, eventParsed);
    }

    @Test
    public void testCanBeUpdatedByApp_nullPolicyAndDeviceEffects() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.zenPolicy = null;
        rule.zenDeviceEffects = null;
        assertThat(rule.canBeUpdatedByApp()).isTrue();

        rule.userModifiedFields = 1;

        assertThat(rule.canBeUpdatedByApp()).isFalse();
    }

    @Test
    public void testCanBeUpdatedByApp_policyModified() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.zenPolicy = new ZenPolicy();
        assertThat(rule.canBeUpdatedByApp()).isTrue();

        rule.zenPolicyUserModifiedFields = 1;

        assertThat(rule.canBeUpdatedByApp()).isFalse();
    }

    @Test
    public void testCanBeUpdatedByApp_deviceEffectsModified() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.zenDeviceEffects = new ZenDeviceEffects.Builder().build();
        assertThat(rule.canBeUpdatedByApp()).isTrue();

        rule.zenDeviceEffectsUserModifiedFields = 1;

        assertThat(rule.canBeUpdatedByApp()).isFalse();
    }

    @Test
    public void testWriteToParcel() {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = CONFIG_ACTIVITY;
        rule.component = OWNER;
        rule.conditionId = CONDITION_ID;
        rule.condition = CONDITION;
        rule.enabled = ENABLED;
        rule.creationTime = 123;
        rule.id = "id";
        rule.zenMode = INTERRUPTION_FILTER;
        rule.modified = true;
        rule.name = NAME;
        rule.snoozing = true;
        rule.pkg = OWNER.getPackageName();
        rule.zenPolicy = POLICY;

        rule.allowManualInvocation = ALLOW_MANUAL;
        rule.type = TYPE;
        rule.userModifiedFields = 16;
        rule.zenPolicyUserModifiedFields = 5;
        rule.zenDeviceEffectsUserModifiedFields = 2;
        rule.iconResName = ICON_RES_NAME;
        rule.triggerDescription = TRIGGER_DESC;
        rule.deletionInstant = Instant.ofEpochMilli(1701790147000L);

        Parcel parcel = Parcel.obtain();
        rule.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        ZenModeConfig.ZenRule parceled = new ZenModeConfig.ZenRule(parcel);

        assertEquals(rule.pkg, parceled.pkg);
        assertEquals(rule.snoozing, parceled.snoozing);
        assertEquals(rule.enabler, parceled.enabler);
        assertEquals(rule.component, parceled.component);
        assertEquals(rule.configurationActivity, parceled.configurationActivity);
        assertEquals(rule.condition, parceled.condition);
        assertEquals(rule.enabled, parceled.enabled);
        assertEquals(rule.creationTime, parceled.creationTime);
        assertEquals(rule.modified, parceled.modified);
        assertEquals(rule.conditionId, parceled.conditionId);
        assertEquals(rule.name, parceled.name);
        assertEquals(rule.zenMode, parceled.zenMode);

        assertEquals(rule.allowManualInvocation, parceled.allowManualInvocation);
        assertEquals(rule.iconResName, parceled.iconResName);
        assertEquals(rule.type, parceled.type);
        assertEquals(rule.userModifiedFields, parceled.userModifiedFields);
        assertEquals(rule.zenPolicyUserModifiedFields, parceled.zenPolicyUserModifiedFields);
        assertEquals(rule.zenDeviceEffectsUserModifiedFields,
                parceled.zenDeviceEffectsUserModifiedFields);
        assertEquals(rule.triggerDescription, parceled.triggerDescription);
        assertEquals(rule.zenPolicy, parceled.zenPolicy);
        assertEquals(rule.deletionInstant, parceled.deletionInstant);

        assertEquals(rule, parceled);
        assertEquals(rule.hashCode(), parceled.hashCode());
    }

    @Test
    public void testRuleXml_classic() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = new ComponentName("a", "a");
        rule.component = new ComponentName("b", "b");
        rule.conditionId = new Uri.Builder().scheme("hello").build();
        rule.condition = new Condition(rule.conditionId, "", Condition.STATE_TRUE);
        rule.enabled = true;
        rule.creationTime = 123;
        rule.id = "id";
        rule.zenMode = Settings.Global.ZEN_MODE_ALARMS;
        rule.modified = true;
        rule.name = "name";
        rule.snoozing = true;
        rule.pkg = "b";

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertEquals("b", fromXml.pkg);
        // always resets on reboot
        assertFalse(fromXml.snoozing);
        //should all match original
        assertEquals(rule.component, fromXml.component);
        assertEquals(rule.configurationActivity, fromXml.configurationActivity);
        assertNull(fromXml.enabler);
        assertEquals(rule.condition, fromXml.condition);
        assertEquals(rule.enabled, fromXml.enabled);
        assertEquals(rule.creationTime, fromXml.creationTime);
        assertEquals(rule.modified, fromXml.modified);
        assertEquals(rule.conditionId, fromXml.conditionId);
        assertEquals(rule.name, fromXml.name);
        assertEquals(rule.zenMode, fromXml.zenMode);
    }

    @Test
    public void testRuleXml() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = CONFIG_ACTIVITY;
        rule.component = OWNER;
        rule.conditionId = CONDITION_ID;
        rule.condition = CONDITION;
        rule.enabled = ENABLED;
        rule.id = "id";
        rule.zenMode = INTERRUPTION_FILTER;
        rule.modified = true;
        rule.name = NAME;
        rule.snoozing = true;
        rule.pkg = OWNER.getPackageName();
        rule.zenPolicy = POLICY;
        rule.zenDeviceEffects = new ZenDeviceEffects.Builder()
                .setShouldDisplayGrayscale(false)
                .setShouldSuppressAmbientDisplay(true)
                .setShouldDimWallpaper(false)
                .setShouldUseNightMode(true)
                .setShouldDisableAutoBrightness(false)
                .setShouldDisableTapToWake(true)
                .setShouldDisableTiltToWake(false)
                .setShouldDisableTouch(true)
                .setShouldMinimizeRadioUsage(false)
                .setShouldMaximizeDoze(true)
                .setExtraEffects(ImmutableSet.of("one", "two"))
                .build();
        rule.creationTime = CREATION_TIME;

        rule.allowManualInvocation = ALLOW_MANUAL;
        rule.type = TYPE;
        rule.userModifiedFields = 4;
        rule.zenPolicyUserModifiedFields = 5;
        rule.zenDeviceEffectsUserModifiedFields = 2;
        rule.iconResName = ICON_RES_NAME;
        rule.triggerDescription = TRIGGER_DESC;
        rule.deletionInstant = Instant.ofEpochMilli(1701790147000L);

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertEquals(rule.pkg, fromXml.pkg);
        // always resets on reboot
        assertFalse(fromXml.snoozing);
        //should all match original
        assertEquals(rule.component, fromXml.component);
        assertEquals(rule.configurationActivity, fromXml.configurationActivity);
        assertNull(fromXml.enabler);
        assertEquals(rule.condition, fromXml.condition);
        assertEquals(rule.enabled, fromXml.enabled);
        assertEquals(rule.creationTime, fromXml.creationTime);
        assertEquals(rule.modified, fromXml.modified);
        assertEquals(rule.conditionId, fromXml.conditionId);
        assertEquals(rule.name, fromXml.name);
        assertEquals(rule.zenMode, fromXml.zenMode);
        assertEquals(rule.creationTime, fromXml.creationTime);
        assertEquals(rule.zenPolicy, fromXml.zenPolicy);
        assertEquals(rule.zenDeviceEffects, fromXml.zenDeviceEffects);

        assertEquals(rule.allowManualInvocation, fromXml.allowManualInvocation);
        assertEquals(rule.type, fromXml.type);
        assertEquals(rule.userModifiedFields, fromXml.userModifiedFields);
        assertEquals(rule.zenPolicyUserModifiedFields, fromXml.zenPolicyUserModifiedFields);
        assertEquals(rule.zenDeviceEffectsUserModifiedFields,
                fromXml.zenDeviceEffectsUserModifiedFields);
        assertEquals(rule.triggerDescription, fromXml.triggerDescription);
        assertEquals(rule.iconResName, fromXml.iconResName);
        assertEquals(rule.deletionInstant, fromXml.deletionInstant);
    }

    @Test
    public void testRuleXml_weirdEffects() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.zenDeviceEffects = new ZenDeviceEffects.Builder()
                .setShouldMaximizeDoze(true)
                .addExtraEffect("one,stillOne,,andStillOne,,,andYetStill")
                .addExtraEffect(",two,stillTwo,")
                .addExtraEffect("three\\andThree")
                .addExtraEffect("four\\,andFour")
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertThat(fromXml.zenDeviceEffects.getExtraEffects()).isNotNull();
        assertThat(fromXml.zenDeviceEffects.getExtraEffects())
                .containsExactly("one,stillOne,,andStillOne,,,andYetStill", ",two,stillTwo,",
                        "three\\andThree", "four\\,andFour");
    }

    @Test
    public void testRuleXml_pkg_component() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = new ComponentName("a", "a");
        rule.component = new ComponentName("b", "b");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertEquals("b", fromXml.pkg);
    }

    @Test
    public void testRuleXml_pkg_configActivity() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = new ComponentName("a", "a");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertNull(fromXml.pkg);
    }

    @Test
    public void testRuleXml_getPkg_nullPkg() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.enabled = true;
        rule.configurationActivity = new ComponentName("a", "a");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);
        assertEquals("a", fromXml.getPkg());

        fromXml.condition = new Condition(Uri.EMPTY, "", Condition.STATE_TRUE);
        assertTrue(fromXml.isAutomaticActive());
    }

    @Test
    public void testRuleXml_emptyConditionId() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.conditionId = Uri.EMPTY;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertEquals(rule.condition, fromXml.condition);
    }

    @Test
    public void testRuleXml_customInterruptionFilter() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.zenMode = Settings.Global.ZEN_MODE_ALARMS;
        rule.conditionId = Uri.parse("condition://android/blah");
        assertThat(Condition.isValidId(rule.conditionId, ZenModeConfig.SYSTEM_AUTHORITY)).isTrue();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertEquals(rule.zenMode, fromXml.zenMode);
    }

    @Test
    public void testZenPolicyXml_allUnset() throws Exception {
        ZenPolicy policy = new ZenPolicy.Builder().build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writePolicyXml(policy, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenPolicy fromXml = readPolicyXml(bais);

        // nothing was set, so we should have nothing from the parser
        assertNull(fromXml);
    }

    @Test
    public void testRuleXml_userModifiedField() throws Exception {
        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.userModifiedFields |= AutomaticZenRule.FIELD_NAME;
        assertThat(rule.userModifiedFields).isEqualTo(1);
        assertThat(rule.canBeUpdatedByApp()).isFalse();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writeRuleXml(rule, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenModeConfig.ZenRule fromXml = readRuleXml(bais);

        assertThat(fromXml.userModifiedFields).isEqualTo(rule.userModifiedFields);
        assertThat(fromXml.canBeUpdatedByApp()).isFalse();
    }

    @Test
    public void testZenPolicyXml_classic() throws Exception {
        ZenPolicy policy = new ZenPolicy.Builder()
                .allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
                .allowConversations(ZenPolicy.CONVERSATION_SENDERS_IMPORTANT)
                .allowRepeatCallers(true)
                .allowAlarms(true)
                .allowMedia(false)
                .allowSystem(true)
                .allowReminders(false)
                .allowEvents(true)
                .hideAllVisualEffects()
                .showVisualEffect(ZenPolicy.VISUAL_EFFECT_AMBIENT, true)
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writePolicyXml(policy, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenPolicy fromXml = readPolicyXml(bais);

        assertNotNull(fromXml);
        assertEquals(policy.getPriorityCategoryCalls(), fromXml.getPriorityCategoryCalls());
        assertEquals(policy.getPriorityCallSenders(), fromXml.getPriorityCallSenders());
        assertEquals(policy.getPriorityCategoryMessages(), fromXml.getPriorityCategoryMessages());
        assertEquals(policy.getPriorityMessageSenders(), fromXml.getPriorityMessageSenders());
        assertEquals(policy.getPriorityCategoryConversations(),
                fromXml.getPriorityCategoryConversations());
        assertEquals(policy.getPriorityConversationSenders(),
                fromXml.getPriorityConversationSenders());
        assertEquals(policy.getPriorityCategoryRepeatCallers(),
                fromXml.getPriorityCategoryRepeatCallers());
        assertEquals(policy.getPriorityCategoryAlarms(), fromXml.getPriorityCategoryAlarms());
        assertEquals(policy.getPriorityCategoryMedia(), fromXml.getPriorityCategoryMedia());
        assertEquals(policy.getPriorityCategorySystem(), fromXml.getPriorityCategorySystem());
        assertEquals(policy.getPriorityCategoryReminders(), fromXml.getPriorityCategoryReminders());
        assertEquals(policy.getPriorityCategoryEvents(), fromXml.getPriorityCategoryEvents());

        assertEquals(policy.getVisualEffectFullScreenIntent(),
                fromXml.getVisualEffectFullScreenIntent());
        assertEquals(policy.getVisualEffectLights(), fromXml.getVisualEffectLights());
        assertEquals(policy.getVisualEffectPeek(), fromXml.getVisualEffectPeek());
        assertEquals(policy.getVisualEffectStatusBar(), fromXml.getVisualEffectStatusBar());
        assertEquals(policy.getVisualEffectBadge(), fromXml.getVisualEffectBadge());
        assertEquals(policy.getVisualEffectAmbient(), fromXml.getVisualEffectAmbient());
        assertEquals(policy.getVisualEffectNotificationList(),
                fromXml.getVisualEffectNotificationList());
    }

    @Test
    public void testZenPolicyXml() throws Exception {
        mSetFlagsRule.enableFlags(Flags.FLAG_MODES_API);

        ZenPolicy policy = new ZenPolicy.Builder()
                .allowCalls(ZenPolicy.PEOPLE_TYPE_CONTACTS)
                .allowMessages(ZenPolicy.PEOPLE_TYPE_NONE)
                .allowConversations(ZenPolicy.CONVERSATION_SENDERS_IMPORTANT)
                .allowRepeatCallers(true)
                .allowAlarms(true)
                .allowMedia(false)
                .allowSystem(true)
                .allowReminders(false)
                .allowEvents(true)
                .allowPriorityChannels(false)
                .hideAllVisualEffects()
                .showVisualEffect(ZenPolicy.VISUAL_EFFECT_AMBIENT, true)
                .build();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        writePolicyXml(policy, baos);
        ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
        ZenPolicy fromXml = readPolicyXml(bais);

        assertNotNull(fromXml);
        assertEquals(policy.getPriorityCategoryCalls(), fromXml.getPriorityCategoryCalls());
        assertEquals(policy.getPriorityCallSenders(), fromXml.getPriorityCallSenders());
        assertEquals(policy.getPriorityCategoryMessages(), fromXml.getPriorityCategoryMessages());
        assertEquals(policy.getPriorityMessageSenders(), fromXml.getPriorityMessageSenders());
        assertEquals(policy.getPriorityCategoryConversations(),
                fromXml.getPriorityCategoryConversations());
        assertEquals(policy.getPriorityConversationSenders(),
                fromXml.getPriorityConversationSenders());
        assertEquals(policy.getPriorityCategoryRepeatCallers(),
                fromXml.getPriorityCategoryRepeatCallers());
        assertEquals(policy.getPriorityCategoryAlarms(), fromXml.getPriorityCategoryAlarms());
        assertEquals(policy.getPriorityCategoryMedia(), fromXml.getPriorityCategoryMedia());
        assertEquals(policy.getPriorityCategorySystem(), fromXml.getPriorityCategorySystem());
        assertEquals(policy.getPriorityCategoryReminders(), fromXml.getPriorityCategoryReminders());
        assertEquals(policy.getPriorityCategoryEvents(), fromXml.getPriorityCategoryEvents());
        assertEquals(policy.getPriorityChannelsAllowed(), fromXml.getPriorityChannelsAllowed());

        assertEquals(policy.getVisualEffectFullScreenIntent(),
                fromXml.getVisualEffectFullScreenIntent());
        assertEquals(policy.getVisualEffectLights(), fromXml.getVisualEffectLights());
        assertEquals(policy.getVisualEffectPeek(), fromXml.getVisualEffectPeek());
        assertEquals(policy.getVisualEffectStatusBar(), fromXml.getVisualEffectStatusBar());
        assertEquals(policy.getVisualEffectBadge(), fromXml.getVisualEffectBadge());
        assertEquals(policy.getVisualEffectAmbient(), fromXml.getVisualEffectAmbient());
        assertEquals(policy.getVisualEffectNotificationList(),
                fromXml.getVisualEffectNotificationList());
    }

    private ZenModeConfig getMutedRingerConfig() {
        ZenModeConfig config = new ZenModeConfig();
        // Allow alarms, media
        config.allowAlarms = true;
        config.allowMedia = true;

        // All sounds that respect the ringer are not allowed
        config.allowSystem = false;
        config.allowCalls = false;
        config.allowRepeatCallers = false;
        config.allowMessages = false;
        config.allowReminders = false;
        config.allowEvents = false;
        config.areChannelsBypassingDnd = false;

        config.suppressedVisualEffects = 0;

        return config;
    }

    private ZenModeConfig getCustomConfig() {
        ZenModeConfig config = new ZenModeConfig();
        // Some sounds allowed
        config.allowAlarms = true;
        config.allowMedia = false;
        config.allowSystem = false;
        config.allowCalls = true;
        config.allowRepeatCallers = true;
        config.allowMessages = false;
        config.allowReminders = false;
        config.allowEvents = false;
        config.areChannelsBypassingDnd = false;
        config.allowCallsFrom = ZenModeConfig.SOURCE_ANYONE;
        config.allowMessagesFrom = ZenModeConfig.SOURCE_ANYONE;
        config.allowConversations = true;
        config.allowConversationsFrom = CONVERSATION_SENDERS_IMPORTANT;

        config.suppressedVisualEffects = 0;
        return config;
    }

    private ZenModeConfig getMutedAllConfig() {
        ZenModeConfig config = new ZenModeConfig();
        // No sounds allowed
        config.allowAlarms = false;
        config.allowMedia = false;
        config.allowSystem = false;
        config.allowCalls = false;
        config.allowRepeatCallers = false;
        config.allowMessages = false;
        config.allowReminders = false;
        config.allowEvents = false;
        config.areChannelsBypassingDnd = false;
        config.allowConversations = false;
        config.allowConversationsFrom = ZenPolicy.CONVERSATION_SENDERS_NONE;

        config.suppressedVisualEffects = 0;
        return config;
    }

    private void writeRuleXml(ZenModeConfig.ZenRule rule, ByteArrayOutputStream os)
            throws IOException {
        String tag = "tag";

        TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(new BufferedOutputStream(os), "utf-8");
        out.startDocument(null, true);
        out.startTag(null, tag);
        ZenModeConfig.writeRuleXml(rule, out);
        out.endTag(null, tag);
        out.endDocument();
    }

    private ZenModeConfig.ZenRule readRuleXml(ByteArrayInputStream is)
            throws XmlPullParserException, IOException {
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(is), null);
        parser.nextTag();
        return ZenModeConfig.readRuleXml(parser);
    }

    private void writePolicyXml(ZenPolicy policy, ByteArrayOutputStream os) throws IOException {
        String tag = "tag";

        TypedXmlSerializer out = Xml.newFastSerializer();
        out.setOutput(new BufferedOutputStream(os), "utf-8");
        out.startDocument(null, true);
        out.startTag(null, tag);
        ZenModeConfig.writeZenPolicyXml(policy, out);
        out.endTag(null, tag);
        out.endDocument();
    }

    private ZenPolicy readPolicyXml(ByteArrayInputStream is)
            throws XmlPullParserException, IOException {
        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(is), null);
        parser.nextTag();
        return ZenModeConfig.readZenPolicyXml(parser);
    }
}
