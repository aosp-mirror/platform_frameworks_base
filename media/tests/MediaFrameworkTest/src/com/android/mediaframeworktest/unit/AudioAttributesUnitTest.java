/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.mediaframeworktest.unit;

import static org.junit.Assert.assertEquals;

import android.media.AudioAttributes;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AudioAttributesUnitTest {

    @Test
    public void testUsageToString_returnCorrectStrings() {
        assertEquals("USAGE_UNKNOWN", AudioAttributes.usageToString(AudioAttributes.USAGE_UNKNOWN));
        assertEquals("USAGE_MEDIA", AudioAttributes.usageToString(AudioAttributes.USAGE_MEDIA));
        assertEquals("USAGE_VOICE_COMMUNICATION",
                AudioAttributes.usageToString(AudioAttributes.USAGE_VOICE_COMMUNICATION));
        assertEquals("USAGE_VOICE_COMMUNICATION_SIGNALLING",
                AudioAttributes.usageToString(
                        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING));
        assertEquals("USAGE_ALARM", AudioAttributes.usageToString(AudioAttributes.USAGE_ALARM));
        assertEquals("USAGE_NOTIFICATION",
                AudioAttributes.usageToString(AudioAttributes.USAGE_NOTIFICATION));
        assertEquals("USAGE_NOTIFICATION_RINGTONE",
                AudioAttributes.usageToString(AudioAttributes.USAGE_NOTIFICATION_RINGTONE));
        assertEquals("USAGE_NOTIFICATION_COMMUNICATION_REQUEST",
                AudioAttributes.usageToString(
                        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_REQUEST));
        assertEquals("USAGE_NOTIFICATION_COMMUNICATION_INSTANT",
                AudioAttributes.usageToString(
                        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_INSTANT));
        assertEquals("USAGE_NOTIFICATION_COMMUNICATION_DELAYED",
                AudioAttributes.usageToString(
                        AudioAttributes.USAGE_NOTIFICATION_COMMUNICATION_DELAYED));
        assertEquals("USAGE_NOTIFICATION_EVENT",
                AudioAttributes.usageToString(AudioAttributes.USAGE_NOTIFICATION_EVENT));
        assertEquals("USAGE_ASSISTANCE_ACCESSIBILITY",
                AudioAttributes.usageToString(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY));
        assertEquals("USAGE_ASSISTANCE_NAVIGATION_GUIDANCE",
                AudioAttributes.usageToString(
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE));
        assertEquals("USAGE_ASSISTANCE_SONIFICATION",
                AudioAttributes.usageToString(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION));
        assertEquals("USAGE_GAME", AudioAttributes.usageToString(AudioAttributes.USAGE_GAME));
        assertEquals("USAGE_ASSISTANT",
                AudioAttributes.usageToString(AudioAttributes.USAGE_ASSISTANT));
        assertEquals("USAGE_CALL_ASSISTANT",
                AudioAttributes.usageToString(AudioAttributes.USAGE_CALL_ASSISTANT));
        assertEquals("USAGE_EMERGENCY",
                AudioAttributes.usageToString(AudioAttributes.USAGE_EMERGENCY));
        assertEquals("USAGE_SAFETY", AudioAttributes.usageToString(AudioAttributes.USAGE_SAFETY));
        assertEquals("USAGE_VEHICLE_STATUS",
                AudioAttributes.usageToString(AudioAttributes.USAGE_VEHICLE_STATUS));
        assertEquals("USAGE_ANNOUNCEMENT",
                AudioAttributes.usageToString(AudioAttributes.USAGE_ANNOUNCEMENT));
    }

    @Test
    public void testUsageToString_unknownUsage() {
        assertEquals("unknown usage -1", AudioAttributes.usageToString(-1));
    }
}
