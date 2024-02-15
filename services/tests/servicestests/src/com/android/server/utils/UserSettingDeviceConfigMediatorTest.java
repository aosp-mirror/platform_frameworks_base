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

package com.android.server.utils;

import static org.junit.Assert.assertEquals;

import android.provider.DeviceConfig;

import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link UserSettingDeviceConfigMediator}
 */
@RunWith(AndroidJUnit4.class)
public class UserSettingDeviceConfigMediatorTest {
    @Test
    public void testDeviceConfigOnly() {
        UserSettingDeviceConfigMediator mediator =
                new UserSettingDeviceConfigMediator.SettingsOverridesIndividualMediator(',');

        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder("test")
                .setInt("int", 1)
                .setFloat("float", .5f)
                .setBoolean("boolean", true)
                .setLong("long", 123456789)
                .setString("string", "abc123")
                .build();

        mediator.setDeviceConfigProperties(properties);

        assertEquals(1, mediator.getInt("int", 123));
        assertEquals(123, mediator.getInt("invalidKey", 123));
        assertEquals(.5f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.8f, mediator.getFloat("invalidKey", .8f), 0.001);
        assertEquals(true, mediator.getBoolean("boolean", false));
        assertEquals(true, mediator.getBoolean("invalidKey", true));
        assertEquals(123456789, mediator.getLong("long", 987654321));
        assertEquals(987654321, mediator.getInt("invalidKey", 987654321));
        assertEquals("abc123", mediator.getString("string", "xyz987"));
        assertEquals("xyz987", mediator.getString("invalidKey", "xyz987"));

        // Clear the properties
        mediator.setDeviceConfigProperties(null);

        assertEquals(123, mediator.getInt("int", 123));
        assertEquals(123, mediator.getInt("invalidKey", 123));
        assertEquals(.8f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.8f, mediator.getFloat("invalidKey", .8f), 0.001);
        assertEquals(false, mediator.getBoolean("boolean", false));
        assertEquals(true, mediator.getBoolean("invalidKey", true));
        assertEquals(987654321, mediator.getLong("long", 987654321));
        assertEquals(987654321, mediator.getInt("invalidKey", 987654321));
        assertEquals("xyz987", mediator.getString("string", "xyz987"));
        assertEquals("xyz987", mediator.getString("invalidKey", "xyz987"));
    }

    @Test
    public void testSettingsOnly() {
        UserSettingDeviceConfigMediator mediator =
                new UserSettingDeviceConfigMediator.SettingsOverridesIndividualMediator(',');

        String settings = "int=1,float=.5f,boolean=true,long=123456789,string=abc123";

        mediator.setSettingsString(settings);

        assertEquals(1, mediator.getInt("int", 123));
        assertEquals(123, mediator.getInt("invalidKey", 123));
        assertEquals(.5f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.8f, mediator.getFloat("invalidKey", .8f), 0.001);
        assertEquals(true, mediator.getBoolean("boolean", false));
        assertEquals(true, mediator.getBoolean("invalidKey", true));
        assertEquals(123456789, mediator.getLong("long", 987654321));
        assertEquals(987654321, mediator.getInt("invalidKey", 987654321));
        assertEquals("abc123", mediator.getString("string", "xyz987"));
        assertEquals("xyz987", mediator.getString("invalidKey", "xyz987"));

        // Clear the settings
        mediator.setSettingsString(null);

        assertEquals(123, mediator.getInt("int", 123));
        assertEquals(123, mediator.getInt("invalidKey", 123));
        assertEquals(.8f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.8f, mediator.getFloat("invalidKey", .8f), 0.001);
        assertEquals(false, mediator.getBoolean("boolean", false));
        assertEquals(true, mediator.getBoolean("invalidKey", true));
        assertEquals(987654321, mediator.getLong("long", 987654321));
        assertEquals(987654321, mediator.getInt("invalidKey", 987654321));
        assertEquals("xyz987", mediator.getString("string", "xyz987"));
        assertEquals("xyz987", mediator.getString("invalidKey", "xyz987"));
    }

    @Test
    public void testSettingsOverridesAll() {
        UserSettingDeviceConfigMediator mediator =
                new UserSettingDeviceConfigMediator.SettingsOverridesAllMediator(',');

        String settings = "int=1,float=.5f,boolean=true,long=123456789,string=abc123,"
                + "intOnlyInSettings=9,floatOnlyInSettings=.25f,booleanOnlyInSettings=true,"
                + "longOnlyInSettings=53771465,stringOnlyInSettings=settingsString";
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder("test")
                .setInt("int", 10)
                .setInt("intOnlyInDeviceConfig", 9001)
                .setFloat("float", .7f)
                .setFloat("floatOnlyInDeviceConfig", .9f)
                .setBoolean("boolean", false)
                .setBoolean("booleanOnlyInDeviceConfig", true)
                .setLong("long", 60000001)
                .setLong("longOnlyInDeviceConfig", 7357)
                .setString("string", "xyz987")
                .setString("stringOnlyInDeviceConfig", "deviceConfigString")
                .build();

        mediator.setSettingsString(settings);
        mediator.setDeviceConfigProperties(properties);

        // Since settings overrides all, anything in DeviceConfig should be ignored,
        // even if settings doesn't have a value for it.
        assertEquals(1, mediator.getInt("int", 123));
        assertEquals(9, mediator.getInt("intOnlyInSettings", 123));
        assertEquals(123, mediator.getInt("intOnlyInDeviceConfig", 123));
        assertEquals(.5f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.25f, mediator.getFloat("floatOnlyInSettings", .8f), 0.001);
        assertEquals(.8f, mediator.getFloat("floatOnlyInDeviceConfig", .8f), 0.001);
        assertEquals(true, mediator.getBoolean("boolean", false));
        assertEquals(true, mediator.getBoolean("booleanOnlyInSettings", false));
        assertEquals(false, mediator.getBoolean("booleanOnlyInDeviceConfig", false));
        assertEquals(123456789, mediator.getLong("long", 987654321));
        assertEquals(53771465, mediator.getLong("longOnlyInSettings", 987654321));
        assertEquals(987654321, mediator.getLong("longOnlyInDeviceConfig", 987654321));
        assertEquals("abc123", mediator.getString("string", "default"));
        assertEquals("settingsString", mediator.getString("stringOnlyInSettings", "default"));
        assertEquals("default", mediator.getString("stringOnlyInDeviceConfig", "default"));

        // Nothing in settings, do DeviceConfig can be used.
        mediator.setSettingsString("");

        assertEquals(10, mediator.getInt("int", 123));
        assertEquals(123, mediator.getInt("intOnlyInSettings", 123));
        assertEquals(9001, mediator.getInt("intOnlyInDeviceConfig", 123));
        assertEquals(.7f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.8f, mediator.getFloat("floatOnlyInSettings", .8f), 0.001);
        assertEquals(.9f, mediator.getFloat("floatOnlyInDeviceConfig", .8f), 0.001);
        assertEquals(false, mediator.getBoolean("boolean", false));
        assertEquals(false, mediator.getBoolean("booleanOnlyInSettings", false));
        assertEquals(true, mediator.getBoolean("booleanOnlyInDeviceConfig", false));
        assertEquals(60000001, mediator.getLong("long", 987654321));
        assertEquals(987654321, mediator.getLong("longOnlyInSettings", 987654321));
        assertEquals(7357, mediator.getLong("longOnlyInDeviceConfig", 987654321));
        assertEquals("xyz987", mediator.getString("string", "default"));
        assertEquals("default", mediator.getString("stringOnlyInSettings", "default"));
        assertEquals("deviceConfigString",
                mediator.getString("stringOnlyInDeviceConfig", "default"));

        // Nothing in settings, do DeviceConfig can be used.
        mediator.setSettingsString(null);

        assertEquals(10, mediator.getInt("int", 123));
        assertEquals(123, mediator.getInt("intOnlyInSettings", 123));
        assertEquals(9001, mediator.getInt("intOnlyInDeviceConfig", 123));
        assertEquals(.7f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.8f, mediator.getFloat("floatOnlyInSettings", .8f), 0.001);
        assertEquals(.9f, mediator.getFloat("floatOnlyInDeviceConfig", .8f), 0.001);
        assertEquals(false, mediator.getBoolean("boolean", false));
        assertEquals(false, mediator.getBoolean("booleanOnlyInSettings", false));
        assertEquals(true, mediator.getBoolean("booleanOnlyInDeviceConfig", false));
        assertEquals(60000001, mediator.getLong("long", 987654321));
        assertEquals(987654321, mediator.getLong("longOnlyInSettings", 987654321));
        assertEquals(7357, mediator.getLong("longOnlyInDeviceConfig", 987654321));
        assertEquals("xyz987", mediator.getString("string", "default"));
        assertEquals("default", mediator.getString("stringOnlyInSettings", "default"));
        assertEquals("deviceConfigString",
                mediator.getString("stringOnlyInDeviceConfig", "default"));
    }

    @Test
    public void testSettingsOverridesIndividual() {
        UserSettingDeviceConfigMediator mediator =
                new UserSettingDeviceConfigMediator.SettingsOverridesIndividualMediator(',');

        String settings = "int=1,float=.5f,boolean=true,long=123456789,string=abc123,"
                + "intOnlyInSettings=9,floatOnlyInSettings=.25f,booleanOnlyInSettings=true,"
                + "longOnlyInSettings=53771465,stringOnlyInSettings=settingsString";
        DeviceConfig.Properties properties = new DeviceConfig.Properties.Builder("test")
                .setInt("int", 10)
                .setInt("intOnlyInDeviceConfig", 9001)
                .setFloat("float", .7f)
                .setFloat("floatOnlyInDeviceConfig", .9f)
                .setBoolean("boolean", false)
                .setBoolean("booleanOnlyInDeviceConfig", true)
                .setLong("long", 60000001)
                .setLong("longOnlyInDeviceConfig", 7357)
                .setString("string", "xyz987")
                .setString("stringOnlyInDeviceConfig", "deviceConfigString")
                .build();

        mediator.setSettingsString(settings);
        mediator.setDeviceConfigProperties(properties);

        // Since settings overrides individual, anything in DeviceConfig that doesn't exist in
        // settings should be used.
        assertEquals(1, mediator.getInt("int", 123));
        assertEquals(9, mediator.getInt("intOnlyInSettings", 123));
        assertEquals(9001, mediator.getInt("intOnlyInDeviceConfig", 123));
        assertEquals(.5f, mediator.getFloat("float", .8f), 0.001);
        assertEquals(.25f, mediator.getFloat("floatOnlyInSettings", .8f), 0.001);
        assertEquals(.9f, mediator.getFloat("floatOnlyInDeviceConfig", .8f), 0.001);
        assertEquals(true, mediator.getBoolean("boolean", false));
        assertEquals(true, mediator.getBoolean("booleanOnlyInSettings", false));
        assertEquals(true, mediator.getBoolean("booleanOnlyInDeviceConfig", false));
        assertEquals(123456789, mediator.getLong("long", 987654321));
        assertEquals(53771465, mediator.getLong("longOnlyInSettings", 987654321));
        assertEquals(7357, mediator.getLong("longOnlyInDeviceConfig", 987654321));
        assertEquals("abc123", mediator.getString("string", "default"));
        assertEquals("settingsString", mediator.getString("stringOnlyInSettings", "default"));
        assertEquals("deviceConfigString",
                mediator.getString("stringOnlyInDeviceConfig", "default"));
    }
}
