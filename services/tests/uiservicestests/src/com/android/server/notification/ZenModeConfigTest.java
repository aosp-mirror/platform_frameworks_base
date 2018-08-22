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

import android.app.NotificationManager.Policy;
import android.service.notification.ZenModeConfig;
import android.service.notification.ZenPolicy;
import android.support.test.runner.AndroidJUnit4;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.UiServiceTestCase;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

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
        ZenModeConfig config = getMutedAllConfig();
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
