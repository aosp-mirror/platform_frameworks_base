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

import static junit.framework.TestCase.assertEquals;
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNull;
import static junit.framework.TestCase.assertTrue;

import android.app.NotificationManager.Policy;
import android.content.ComponentName;
import android.net.Uri;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.EventInfo;
import android.service.notification.ZenPolicy;
import android.test.suitebuilder.annotation.SmallTest;
import android.util.TypedXmlPullParser;
import android.util.TypedXmlSerializer;
import android.util.Xml;

import androidx.test.runner.AndroidJUnit4;

import com.android.internal.util.FastXmlSerializer;
import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlSerializer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ZenModeConfigTest extends UiServiceTestCase {

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
    public void testZenPolicyToNotificationPolicy() {
        ZenModeConfig config = getMutedAllConfig();
        config.suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_BADGE;

        ZenPolicy zenPolicy = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowReminders(true)
                .allowEvents(true)
                .showLights(false)
                .showInAmbientDisplay(false)
                .build();

        Policy originalPolicy = config.toNotificationPolicy();
        int priorityCategories = originalPolicy.priorityCategories;
        int priorityCallSenders = originalPolicy.priorityCallSenders;
        int priorityMessageSenders = originalPolicy.priorityMessageSenders;
        int priorityConversationsSenders = originalPolicy.priorityConversationSenders;
        int suppressedVisualEffects = originalPolicy.suppressedVisualEffects;
        priorityCategories |= Policy.PRIORITY_CATEGORY_ALARMS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_REMINDERS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_EVENTS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_LIGHTS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_AMBIENT;

        Policy expectedPolicy = new Policy(priorityCategories, priorityCallSenders,
                priorityMessageSenders, suppressedVisualEffects, 0, priorityConversationsSenders);
        assertEquals(expectedPolicy, config.toNotificationPolicy(zenPolicy));
    }

    @Test
    public void testZenConfigToZenPolicy() {
        ZenPolicy expected = new ZenPolicy.Builder()
                .allowAlarms(true)
                .allowReminders(true)
                .allowEvents(true)
                .showLights(false)
                .showBadges(false)
                .showInAmbientDisplay(false)
                .build();

        ZenModeConfig config = getMutedAllConfig();
        config.allowAlarms = true;
        config.allowReminders = true;
        config.allowEvents = true;
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
    public void testRuleXml() throws Exception {
        String tag = "tag";

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

        TypedXmlSerializer out = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.setOutput(new BufferedOutputStream(baos), "utf-8");
        out.startDocument(null, true);
        out.startTag(null, tag);
        ZenModeConfig.writeRuleXml(rule, out);
        out.endTag(null, tag);
        out.endDocument();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        ZenModeConfig.ZenRule fromXml = ZenModeConfig.readRuleXml(parser);
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
    public void testRuleXml_pkg_component() throws Exception {
        String tag = "tag";

        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = new ComponentName("a", "a");
        rule.component = new ComponentName("b", "b");

        TypedXmlSerializer out = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.setOutput(new BufferedOutputStream(baos), "utf-8");
        out.startDocument(null, true);
        out.startTag(null, tag);
        ZenModeConfig.writeRuleXml(rule, out);
        out.endTag(null, tag);
        out.endDocument();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        ZenModeConfig.ZenRule fromXml = ZenModeConfig.readRuleXml(parser);
        assertEquals("b", fromXml.pkg);
    }

    @Test
    public void testRuleXml_pkg_configActivity() throws Exception {
        String tag = "tag";

        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.configurationActivity = new ComponentName("a", "a");

        TypedXmlSerializer out = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.setOutput(new BufferedOutputStream(baos), "utf-8");
        out.startDocument(null, true);
        out.startTag(null, tag);
        ZenModeConfig.writeRuleXml(rule, out);
        out.endTag(null, tag);
        out.endDocument();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        ZenModeConfig.ZenRule fromXml = ZenModeConfig.readRuleXml(parser);
        assertNull(fromXml.pkg);
    }

    @Test
    public void testRuleXml_getPkg_nullPkg() throws Exception {
        String tag = "tag";

        ZenModeConfig.ZenRule rule = new ZenModeConfig.ZenRule();
        rule.enabled = true;
        rule.configurationActivity = new ComponentName("a", "a");

        TypedXmlSerializer out = Xml.newFastSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.setOutput(new BufferedOutputStream(baos), "utf-8");
        out.startDocument(null, true);
        out.startTag(null, tag);
        ZenModeConfig.writeRuleXml(rule, out);
        out.endTag(null, tag);
        out.endDocument();

        TypedXmlPullParser parser = Xml.newFastPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        ZenModeConfig.ZenRule fromXml = ZenModeConfig.readRuleXml(parser);
        assertEquals("a", fromXml.getPkg());

        fromXml.condition = new Condition(Uri.EMPTY, "", Condition.STATE_TRUE);
        assertTrue(fromXml.isAutomaticActive());
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
        config.allowConversationsFrom = ZenPolicy.CONVERSATION_SENDERS_IMPORTANT;

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
}
