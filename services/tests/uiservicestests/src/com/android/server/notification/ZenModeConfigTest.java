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
import static junit.framework.TestCase.assertFalse;
import static junit.framework.TestCase.assertNull;

import android.app.NotificationManager.Policy;
import android.content.ComponentName;
import android.net.Uri;
import android.provider.Settings;
import android.service.notification.Condition;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenModeConfig.EventInfo;
import android.service.notification.ZenPolicy;
import android.test.suitebuilder.annotation.SmallTest;
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
        ZenModeConfig config = getMutedNotificationsConfig();
        assertEquals(true, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));

        config.allowReminders = true;
        assertEquals(false, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
        config.allowReminders = false;

        config.areChannelsBypassingDnd = true;
        assertEquals(false, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
        config.areChannelsBypassingDnd = false;

        assertEquals(true, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
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
        int suppressedVisualEffects = originalPolicy.suppressedVisualEffects;
        priorityCategories |= Policy.PRIORITY_CATEGORY_ALARMS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_REMINDERS;
        priorityCategories |= Policy.PRIORITY_CATEGORY_EVENTS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_LIGHTS;
        suppressedVisualEffects |= Policy.SUPPRESSED_EFFECT_AMBIENT;

        Policy expectedPolicy = new Policy(priorityCategories, priorityCallSenders,
                priorityMessageSenders, suppressedVisualEffects);

        assertEquals(expectedPolicy, config.toNotificationPolicy(zenPolicy));
    }

    @Test
    public void testPriorityOnlyMutingAll() {
        ZenModeConfig config = getMutedAllConfig();
        assertEquals(true, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
        assertEquals(true, ZenModeConfig.areAllZenBehaviorSoundsMuted(config));

        config.allowReminders = true;
        assertEquals(false, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
        assertEquals(false, ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
        config.allowReminders = false;

        config.areChannelsBypassingDnd = true;
        assertEquals(false, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
        assertEquals(false, ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
        config.areChannelsBypassingDnd = false;

        config.allowAlarms = true;
        assertEquals(true, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
        assertEquals(false, ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
        config.allowAlarms = false;

        assertEquals(true, ZenModeConfig.areAllPriorityOnlyNotificationZenSoundsMuted(config));
        assertEquals(true, ZenModeConfig.areAllZenBehaviorSoundsMuted(config));
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
        rule.component = new ComponentName("a", "b");
        rule.conditionId = new Uri.Builder().scheme("hello").build();
        rule.condition = new Condition(rule.conditionId, "", Condition.STATE_TRUE);
        rule.enabled = true;
        rule.creationTime = 123;
        rule.id = "id";
        rule.zenMode = Settings.Global.ZEN_MODE_ALARMS;
        rule.modified = true;
        rule.name = "name";
        rule.snoozing = true;

        XmlSerializer out = new FastXmlSerializer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        out.setOutput(new BufferedOutputStream(baos), "utf-8");
        out.startDocument(null, true);
        out.startTag(null, tag);
        ZenModeConfig.writeRuleXml(rule, out);
        out.endTag(null, tag);
        out.endDocument();

        XmlPullParser parser = Xml.newPullParser();
        parser.setInput(new BufferedInputStream(
                new ByteArrayInputStream(baos.toByteArray())), null);
        parser.nextTag();
        ZenModeConfig.ZenRule fromXml = ZenModeConfig.readRuleXml(parser);
        // read from backing component
        assertEquals("a", fromXml.pkg);
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

    private ZenModeConfig getMutedNotificationsConfig() {
        ZenModeConfig config = new ZenModeConfig();
        // Allow alarms, media, and system
        config.allowAlarms = true;
        config.allowMedia = true;
        config.allowSystem = true;

        // All notification sounds are not allowed
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

        config.suppressedVisualEffects = 0;
        return config;
    }
}
