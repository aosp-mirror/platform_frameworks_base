/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;
import static junit.framework.Assert.fail;

import android.aconfig.Aconfig;
import android.aconfig.Aconfig.parsed_flag;
import android.aconfig.Aconfig.parsed_flags;
import android.os.Looper;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.Xml;
import android.util.proto.ProtoOutputStream;
import com.android.providers.settings.SettingsState.FlagOverrideToSync;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.TypedXmlSerializer;

import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import com.google.common.base.Strings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class SettingsStateTest {
    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    public static final String CRAZY_STRING =
            "\u0000\u0001\u0002\u0003\u0004\u0005\u0006\u0007\u0008\u0009\n\u000b\u000c\r" +
                    "\u000e\u000f\u0010\u0011\u0012\u0013\u0014\u0015\u0016\u0017\u0018\u0019\u001a" +
                    "\u001b\u001c\u001d\u001e\u001f\u0020" +
                    "fake_setting_value_1" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx" +
                    "\u1000 \u2000 \u5000 \u8000 \uc000 \ue000" +
                    "\ud800\udc00\udbff\udfff" + // surrogate pairs
                    "\uD800ab\uDC00 " + // broken surrogate pairs
                    "日本語";

    private static final String TEST_PACKAGE = "package";
    private static final String SYSTEM_PACKAGE = "android";
    private static final String SETTING_NAME = "test_setting";

    private static final String FLAG_NAME_1 = "namespace123/flag456";
    private static final String FLAG_NAME_1_STAGED = "staged/namespace123*flag456";
    private static final String FLAG_NAME_2 = "not_staged/flag101";

    private static final String INVALID_STAGED_FLAG_1 = "stagednamespace*flagName";
    private static final String INVALID_STAGED_FLAG_2 = "staged/";
    private static final String INVALID_STAGED_FLAG_3 = "staged/namespace*";
    private static final String INVALID_STAGED_FLAG_4 = "staged/*flagName";

    private static final String VALID_STAGED_FLAG_1 = "staged/namespace*flagName";
    private static final String VALID_STAGED_FLAG_1_TRANSFORMED = "namespace/flagName";

    private static final String VALUE1 = "5";
    private static final String VALUE2 = "6";

    private final Object mLock = new Object();

    private File mSettingsFile;

    @Before
    public void setUp() {
        mSettingsFile = new File(InstrumentationRegistry.getContext().getCacheDir(), "setting.xml");
        mSettingsFile.delete();
    }

    @After
    public void tearDown() throws Exception {
        if (mSettingsFile != null) {
            mSettingsFile.delete();
        }
    }

    @Test
    public void testLoadValidAconfigProto() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        parsed_flags flags = parsed_flags
                .newBuilder()
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setPackage("com.android.flags")
                        .setName("flag1")
                        .setNamespace("test_namespace")
                        .setDescription("test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.DISABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setPackage("com.android.flags")
                        .setName("flag2")
                        .setNamespace("test_namespace")
                        .setDescription("another test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.ENABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .build();

        synchronized (lock) {
            Map<String, Map<String, String>> defaults = new HashMap<>();
            settingsState.loadAconfigDefaultValues(flags.toByteArray(), defaults);
            Map<String, String> namespaceDefaults = defaults.get("test_namespace");
            assertEquals(2, namespaceDefaults.keySet().size());

            assertEquals("false", namespaceDefaults.get("test_namespace/com.android.flags.flag1"));
            assertEquals("true", namespaceDefaults.get("test_namespace/com.android.flags.flag2"));
        }
    }

    @Test
    public void testSkipLoadingAconfigFlagWithMissingFields() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        parsed_flags flags = parsed_flags
                .newBuilder()
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setDescription("test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.DISABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .build();

        synchronized (lock) {
            Map<String, Map<String, String>> defaults = new HashMap<>();
            settingsState.loadAconfigDefaultValues(flags.toByteArray(), defaults);

            Map<String, String> namespaceDefaults = defaults.get("test_namespace");
            assertEquals(null, namespaceDefaults);
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_STAGE_ALL_ACONFIG_FLAGS)
    public void testWritingAconfigFlagStages() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        parsed_flags flags = parsed_flags
                .newBuilder()
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setPackage("com.android.flags")
                        .setName("flag5")
                        .setNamespace("test_namespace")
                        .setDescription("test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.DISABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .build();

        synchronized (lock) {
            Map<String, Map<String, String>> defaults = new HashMap<>();
            settingsState.loadAconfigDefaultValues(flags.toByteArray(), defaults);
            settingsState.addAconfigDefaultValuesFromMap(defaults);

            settingsState.insertSettingLocked("test_namespace/com.android.flags.flag5",
                    "true", null, false, "com.android.flags");
            settingsState.insertSettingLocked("test_namespace/com.android.flags.flag6",
                    "true", null, false, "com.android.flags");

            assertEquals("true",
                    settingsState
                        .getSettingLocked("staged/test_namespace*com.android.flags.flag5")
                        .getValue());
            assertEquals(null,
                    settingsState
                        .getSettingLocked("test_namespace/com.android.flags.flag5")
                        .getValue());

            assertEquals(null,
                    settingsState
                        .getSettingLocked("staged/test_namespace*com.android.flags.flag6")
                        .getValue());
            assertEquals("true",
                    settingsState
                        .getSettingLocked("test_namespace/com.android.flags.flag6")
                        .getValue());
        }
    }

    @Test
    public void testInvalidAconfigProtoDoesNotCrash() {
        Map<String, Map<String, String>> defaults = new HashMap<>();
        SettingsState settingsState = getSettingStateObject();
        settingsState.loadAconfigDefaultValues("invalid protobuf".getBytes(), defaults);
    }

    @Test
    public void testIsBinary() {
        assertFalse(SettingsState.isBinary(" abc 日本語"));

        for (char ch = 0x20; ch < 0xd800; ch++) {
            assertFalse("ch=" + Integer.toString(ch, 16),
                    SettingsState.isBinary(String.valueOf(ch)));
        }
        for (char ch = 0xe000; ch < 0xfffe; ch++) {
            assertFalse("ch=" + Integer.toString(ch, 16),
                    SettingsState.isBinary(String.valueOf(ch)));
        }

        for (char ch = 0x0000; ch < 0x20; ch++) {
            assertTrue("ch=" + Integer.toString(ch, 16),
                    SettingsState.isBinary(String.valueOf(ch)));
        }
        for (char ch = 0xd800; ch < 0xe000; ch++) {
            assertTrue("ch=" + Integer.toString(ch, 16),
                    SettingsState.isBinary(String.valueOf(ch)));
        }
        assertTrue(SettingsState.isBinary("\ufffe"));
        assertTrue(SettingsState.isBinary("\uffff"));
        try {
            assertFalse(SettingsState.isBinary(null));
            fail("NullPointerException expected");
        } catch (NullPointerException expected) {
        }
    }

    /** Make sure we won't pass invalid characters to XML serializer. */
    @Test
    public void testWriteReadNoCrash() throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        TypedXmlSerializer serializer = Xml.resolveSerializer(os);
        serializer.startDocument(null, true);

        for (int ch = 0; ch < 0x10000; ch++) {
            checkWriteSingleSetting("char=0x" + Integer.toString(ch, 16), serializer,
                    "key", String.valueOf((char) ch));
        }
        checkWriteSingleSetting(serializer, "k", "");
        checkWriteSingleSetting(serializer, "x", "abc");
        checkWriteSingleSetting(serializer, "abc", CRAZY_STRING);
        checkWriteSingleSetting(serializer, "def", null);

        // Invlid input, but shouoldn't crash.
        checkWriteSingleSetting(serializer, null, null);
        checkWriteSingleSetting(serializer, CRAZY_STRING, null);
        SettingsState.writeSingleSetting(
                SettingsState.SETTINGS_VERSION_NEW_ENCODING,
                serializer, null, "k", "v", null, "package", null, false, false);
        SettingsState.writeSingleSetting(
                SettingsState.SETTINGS_VERSION_NEW_ENCODING,
                serializer, "1", "k", "v", null, null, null, false, false);
    }

    private void checkWriteSingleSetting(TypedXmlSerializer serializer, String key, String value)
            throws Exception {
        checkWriteSingleSetting(key + "/" + value, serializer, key, value);
    }

    private void checkWriteSingleSetting(String msg, TypedXmlSerializer serializer,
            String key, String value) throws Exception {
        // Make sure the XML serializer won't crash.
        SettingsState.writeSingleSetting(
                SettingsState.SETTINGS_VERSION_NEW_ENCODING,
                serializer, "1", key, value, null, "package", null, false, false);
    }

    /**
     * Make sure settings can be written to a file and also can be read.
     */
    @Test
    public void testReadWrite() {
        final Object lock = new Object();

        assertFalse(mSettingsFile.exists());
        final SettingsState ssWriter =
                new SettingsState(
                        InstrumentationRegistry.getContext(), lock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        ssWriter.setVersionLocked(SettingsState.SETTINGS_VERSION_NEW_ENCODING);

        ssWriter.insertSettingLocked("k1", "\u0000", null, false, "package");
        ssWriter.insertSettingLocked("k2", "abc", null, false, "p2");
        ssWriter.insertSettingLocked("k3", null, null, false, "p2");
        ssWriter.insertSettingLocked("k4", CRAZY_STRING, null, false, "p3");
        synchronized (lock) {
            ssWriter.persistSettingsLocked();
        }
        ssWriter.waitForHandler();
        assertTrue(mSettingsFile.exists());
        final SettingsState ssReader =
                new SettingsState(
                        InstrumentationRegistry.getContext(), lock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        synchronized (lock) {
            assertEquals("\u0000", ssReader.getSettingLocked("k1").getValue());
            assertEquals("abc", ssReader.getSettingLocked("k2").getValue());
            assertEquals(null, ssReader.getSettingLocked("k3").getValue());
            assertEquals(CRAZY_STRING, ssReader.getSettingLocked("k4").getValue());
        }
    }

    /**
     * In version 120, value "null" meant {code NULL}.
     */
    @Test
    public void testUpgrade() throws Exception {
        final Object lock = new Object();
        final PrintStream os = new PrintStream(new FileOutputStream(mSettingsFile));
        os.print(
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
                        "<settings version=\"120\">" +
                        "  <setting id=\"0\" name=\"k0\" value=\"null\" package=\"null\" />" +
                        "  <setting id=\"1\" name=\"k1\" value=\"\" package=\"\" />" +
                        "  <setting id=\"2\" name=\"k2\" value=\"v2\" package=\"p2\" />" +
                        "</settings>");
        os.close();

        final SettingsState ss =
                new SettingsState(
                        InstrumentationRegistry.getContext(), lock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        synchronized (lock) {
            SettingsState.Setting s;
            s = ss.getSettingLocked("k0");
            assertEquals(null, s.getValue());
            assertEquals("null", s.getPackageName());

            s = ss.getSettingLocked("k1");
            assertEquals("", s.getValue());
            assertEquals("", s.getPackageName());

            s = ss.getSettingLocked("k2");
            assertEquals("v2", s.getValue());
            assertEquals("p2", s.getPackageName());
        }
    }

    @Test
    public void testInitializeSetting_preserveFlagNotSet() {
        SettingsState settingsWriter = getSettingStateObject();
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        settingsWriter.persistSettingsLocked();
        settingsWriter.waitForHandler();

        SettingsState settingsReader = getSettingStateObject();
        assertFalse(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    @Test
    public void testModifySetting_preserveFlagSet() {
        SettingsState settingsWriter = getSettingStateObject();
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        settingsWriter.insertSettingLocked(SETTING_NAME, "2", null, false, TEST_PACKAGE);
        settingsWriter.persistSettingsLocked();
        settingsWriter.waitForHandler();

        SettingsState settingsReader = getSettingStateObject();
        assertTrue(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    @Test
    public void testModifySettingOverrideableByRestore_preserveFlagNotSet() {
        SettingsState settingsWriter = getSettingStateObject();
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        settingsWriter.insertSettingLocked(SETTING_NAME, "2", null, false, false, TEST_PACKAGE,
                /* overrideableByRestore */ true);
        settingsWriter.persistSettingsLocked();
        settingsWriter.waitForHandler();

        SettingsState settingsReader = getSettingStateObject();
        assertFalse(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    @Test
    public void testModifySettingOverrideableByRestore_preserveFlagAlreadySet_flagValueUnchanged() {
        SettingsState settingsWriter = getSettingStateObject();
        // Init the setting.
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        // This modification will set isValuePreservedInRestore = true.
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        // This modification shouldn't change the value of isValuePreservedInRestore since it's
        // already been set to true.
        settingsWriter.insertSettingLocked(SETTING_NAME, "2", null, false, false, TEST_PACKAGE,
                /* overrideableByRestore */ true);
        settingsWriter.persistSettingsLocked();
        settingsWriter.waitForHandler();

        SettingsState settingsReader = getSettingStateObject();
        assertTrue(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    @Test
    public void testResetSetting_preservedFlagIsReset() {
        SettingsState settingsState = getSettingStateObject();
        // Initialize the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        // Update the setting so that preserved flag is set.
        settingsState.insertSettingLocked(SETTING_NAME, "2", null, false, TEST_PACKAGE);

        settingsState.resetSettingLocked(SETTING_NAME);
        assertFalse(settingsState.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());

    }

    @Test
    public void testModifySettingBySystemPackage_sameValue_preserveFlagNotSet() {
        SettingsState settingsState = getSettingStateObject();
        // Initialize the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, SYSTEM_PACKAGE);
        // Update the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, SYSTEM_PACKAGE);

        assertFalse(settingsState.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    @Test
    public void testModifySettingBySystemPackage_newValue_preserveFlagSet() {
        SettingsState settingsState = getSettingStateObject();
        // Initialize the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, SYSTEM_PACKAGE);
        // Update the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "2", null, false, SYSTEM_PACKAGE);

        assertTrue(settingsState.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    private SettingsState getSettingStateObject() {
        SettingsState settingsState =
                new SettingsState(
                        InstrumentationRegistry.getContext(), mLock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        settingsState.setVersionLocked(SettingsState.SETTINGS_VERSION_NEW_ENCODING);
        return settingsState;
    }

    @Test
    public void testInsertSetting_memoryUsage() {
        SettingsState settingsState = getSettingStateObject();
        // No exception should be thrown when there is no cap
        settingsState.insertSettingLocked(SETTING_NAME, Strings.repeat("A", 20001),
                null, false, "p1");
        settingsState.deleteSettingLocked(SETTING_NAME);

        settingsState =
                new SettingsState(
                        InstrumentationRegistry.getContext(), mLock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_LIMITED, Looper.getMainLooper());
        // System package doesn't have memory usage limit
        settingsState.insertSettingLocked(SETTING_NAME, Strings.repeat("A", 20001),
                null, false, SYSTEM_PACKAGE);
        settingsState.deleteSettingLocked(SETTING_NAME);

        // Should not throw if usage is under the cap
        settingsState.insertSettingLocked(SETTING_NAME, Strings.repeat("A", 19975),
                null, false, "p1");
        settingsState.deleteSettingLocked(SETTING_NAME);
        try {
            settingsState.insertSettingLocked(SETTING_NAME, Strings.repeat("A", 20001),
                    null, false, "p1");
            fail("Should throw because it exceeded per package memory usage");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("p1"));
        }
        try {
            settingsState.insertSettingLocked(SETTING_NAME, Strings.repeat("A", 20001),
                    null, false, "p1");
            fail("Should throw because it exceeded per package memory usage");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("p1"));
        }
        assertTrue(settingsState.getSettingLocked(SETTING_NAME).isNull());
        try {
            settingsState.insertSettingLocked(Strings.repeat("A", 20001), "",
                    null, false, "p1");
            fail("Should throw because it exceeded per package memory usage");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("You are adding too many system settings"));
        }
    }

    @Test
    public void testMemoryUsagePerPackage() {
        SettingsState settingsState =
                new SettingsState(
                        InstrumentationRegistry.getContext(), mLock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_LIMITED, Looper.getMainLooper());

        // Test inserting one key with default
        final String testKey1 = SETTING_NAME;
        final String testValue1 = Strings.repeat("A", 100);
        settingsState.insertSettingLocked(testKey1, testValue1, null, true, TEST_PACKAGE);
        int expectedMemUsage = (testKey1.length() + testValue1.length()
                + testValue1.length() /* size for default */) * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test inserting another key
        final String testKey2 = SETTING_NAME + "2";
        settingsState.insertSettingLocked(testKey2, testValue1, null, false, TEST_PACKAGE);
        expectedMemUsage += (testKey2.length() + testValue1.length()) * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test updating first key with new default
        final String testValue2 = Strings.repeat("A", 300);
        settingsState.insertSettingLocked(testKey1, testValue2, null, true, TEST_PACKAGE);
        expectedMemUsage += (testValue2.length() - testValue1.length()) * 2 * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test updating first key without new default
        final String testValue3 = Strings.repeat("A", 50);
        settingsState.insertSettingLocked(testKey1, testValue3, null, false, TEST_PACKAGE);
        expectedMemUsage -= (testValue2.length() - testValue3.length()) * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test updating second key
        settingsState.insertSettingLocked(testKey2, testValue2, null, false, TEST_PACKAGE);
        expectedMemUsage -= (testValue1.length() - testValue2.length()) * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test resetting key
        settingsState.resetSettingLocked(testKey1);
        expectedMemUsage += (testValue2.length() - testValue3.length()) * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test resetting default value
        settingsState.resetSettingDefaultValueLocked(testKey1);
        expectedMemUsage -= testValue2.length() * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test deletion
        settingsState.deleteSettingLocked(testKey2);
        expectedMemUsage -= (testValue2.length() + testKey2.length() /* key is deleted too */)
                * Character.BYTES;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test another package with a different key
        final String testPackage2 = TEST_PACKAGE + "2";
        final String testKey3 = SETTING_NAME + "3";
        settingsState.insertSettingLocked(testKey3, testValue1, null, true, testPackage2);
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));
        final int expectedMemUsage2 = (testKey3.length() + testValue1.length() * 2)
                * Character.BYTES;
        assertEquals(expectedMemUsage2, settingsState.getMemoryUsage(testPackage2));

        // Let system package take over testKey1 which is no longer subject to memory usage counting
        settingsState.insertSettingLocked(testKey1, testValue1, null, true, SYSTEM_PACKAGE);
        assertEquals(0, settingsState.getMemoryUsage(TEST_PACKAGE));
        assertEquals(expectedMemUsage2, settingsState.getMemoryUsage(testPackage2));
        assertEquals(0, settingsState.getMemoryUsage(SYSTEM_PACKAGE));

        // Test invalid value
        try {
            settingsState.insertSettingLocked(testKey1, Strings.repeat("A", 20001), null, false,
                    TEST_PACKAGE);
            fail("Should throw because it exceeded per package memory usage");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("You are adding too many system settings"));
        }
        assertEquals(0, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test invalid key
        try {
            settingsState.insertSettingLocked(Strings.repeat("A", 20001), "", null, false,
                    TEST_PACKAGE);
            fail("Should throw because it exceeded per package memory usage");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("You are adding too many system settings"));
        }
        assertEquals(0, settingsState.getMemoryUsage(TEST_PACKAGE));
    }

    @Test
    public void testLargeSettingKey() {
        SettingsState settingsState =
                new SettingsState(
                        InstrumentationRegistry.getContext(), mLock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_LIMITED, Looper.getMainLooper());
        final String largeKey = Strings.repeat("A", SettingsState.MAX_LENGTH_PER_STRING + 1);
        final String testValue = "testValue";
        synchronized (mLock) {
            // Test system package
            try {
                settingsState.insertSettingLocked(largeKey, testValue, null, true, SYSTEM_PACKAGE);
                fail("Should throw because it exceeded max string length");
            } catch (IllegalArgumentException ex) {
                assertTrue(ex.getMessage().contains("The max length allowed for the string is "));
            }
            // Test non system package
            try {
                settingsState.insertSettingLocked(largeKey, testValue, null, true, TEST_PACKAGE);
                fail("Should throw because it exceeded max string length");
            } catch (IllegalArgumentException ex) {
                assertTrue(ex.getMessage().contains("The max length allowed for the string is "));
            }
        }
    }

    @Test
    public void testLargeSettingValue() {
        SettingsState settingsState =
                new SettingsState(
                        InstrumentationRegistry.getContext(), mLock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        final String testKey = "testKey";
        final String largeValue = Strings.repeat("A", SettingsState.MAX_LENGTH_PER_STRING + 1);
        synchronized (mLock) {
            // Test system package
            try {
                settingsState.insertSettingLocked(testKey, largeValue, null, true, SYSTEM_PACKAGE);
                fail("Should throw because it exceeded max string length");
            } catch (IllegalArgumentException ex) {
                assertTrue(ex.getMessage().contains("The max length allowed for the string is "));
            }
            // Test non system package
            try {
                settingsState.insertSettingLocked(testKey, largeValue, null, true, TEST_PACKAGE);
                fail("Should throw because it exceeded max string length");
            } catch (IllegalArgumentException ex) {
                assertTrue(ex.getMessage().contains("The max length allowed for the string is "));
            }
        }
    }

    @Test
    public void testApplyStagedConfigValues() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        synchronized (lock) {
            settingsState.insertSettingLocked(
                    FLAG_NAME_1_STAGED, VALUE1, null, false, TEST_PACKAGE);
            settingsState.insertSettingLocked(FLAG_NAME_2, VALUE2, null, false, TEST_PACKAGE);
            settingsState.persistSettingsLocked();
        }
        settingsState.waitForHandler();

        synchronized (lock) {
            assertEquals(VALUE1, settingsState.getSettingLocked(FLAG_NAME_1_STAGED).getValue());
            assertEquals(VALUE2, settingsState.getSettingLocked(FLAG_NAME_2).getValue());
        }

        settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        synchronized (lock) {
            assertEquals(VALUE1, settingsState.getSettingLocked(FLAG_NAME_1).getValue());
            assertEquals(VALUE2, settingsState.getSettingLocked(FLAG_NAME_2).getValue());

            assertEquals(null, settingsState.getSettingLocked(FLAG_NAME_1_STAGED).getValue());
        }
    }

    @Test
    public void testStagingTransformation() {
        assertEquals(INVALID_STAGED_FLAG_1,
                SettingsState.createRealFlagName(INVALID_STAGED_FLAG_1));
        assertEquals(INVALID_STAGED_FLAG_2,
                SettingsState.createRealFlagName(INVALID_STAGED_FLAG_2));
        assertEquals(INVALID_STAGED_FLAG_3,
                SettingsState.createRealFlagName(INVALID_STAGED_FLAG_3));
        assertEquals(INVALID_STAGED_FLAG_4,
                SettingsState.createRealFlagName(INVALID_STAGED_FLAG_4));

        assertEquals(VALID_STAGED_FLAG_1_TRANSFORMED,
                SettingsState.createRealFlagName(VALID_STAGED_FLAG_1));
    }

    @Test
    public void testInvalidStagedFlagsUnaffectedByReboot() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        synchronized (lock) {
            settingsState.insertSettingLocked(INVALID_STAGED_FLAG_1,
                    VALUE2, null, false, TEST_PACKAGE);
            settingsState.persistSettingsLocked();
        }
        settingsState.waitForHandler();
        synchronized (lock) {
            assertEquals(VALUE2, settingsState.getSettingLocked(INVALID_STAGED_FLAG_1).getValue());
        }

        settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        synchronized (lock) {
            assertEquals(VALUE2, settingsState.getSettingLocked(INVALID_STAGED_FLAG_1).getValue());
        }
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_STAGE_ALL_ACONFIG_FLAGS)
    public void testSetSettingsLockedStagesAconfigFlags() throws Exception {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);

        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), mLock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        String prefix = "test_namespace";
        String packageName = "com.android.flags";
        Map<String, String> keyValues =
                Map.of("test_namespace/com.android.flags.flag3", "true");

        parsed_flags flags = parsed_flags
                .newBuilder()
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setPackage(packageName)
                        .setName("flag3")
                        .setNamespace(prefix)
                        .setDescription("test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.DISABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .build();

        synchronized (mLock) {
            settingsState.loadAconfigDefaultValues(
                    flags.toByteArray(), settingsState.getAconfigDefaultValues());
            List<String> updates =
                    settingsState.setSettingsLocked("test_namespace/", keyValues, packageName);
            assertEquals(1, updates.size());
            assertEquals(updates.get(0), "staged/test_namespace*com.android.flags.flag3");

            SettingsState.Setting s;

            s = settingsState.getSettingLocked("test_namespace/com.android.flags.flag3");
            assertNull(s.getValue());

            s = settingsState.getSettingLocked("staged/test_namespace*com.android.flags.flag3");
            assertEquals("true", s.getValue());
        }
    }

    @Test
    public void testsetSettingsLockedKeepTrunkDefault() throws Exception {
        final PrintStream os = new PrintStream(new FileOutputStream(mSettingsFile));
        os.print(
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
                        + "<settings version=\"120\">"
                        + "  <setting id=\"0\" name=\"test_namespace/flag0\" "
                            + "value=\"false\" package=\"com.android.flags\" />"
                        + "  <setting id=\"1\" name=\"test_namespace/flag1\" "
                            + "value=\"false\" package=\"com.android.flags\" />"
                        + "  <setting id=\"2\" name=\"test_namespace/com.android.flags.flag3\" "
                            + "value=\"false\" package=\"com.android.flags\" />"
                        + "  <setting id=\"3\" "
                        + "name=\"test_another_namespace/com.android.another.flags.flag0\" "
                            + "value=\"false\" package=\"com.android.another.flags\" />"
                        + "</settings>");
        os.close();

        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);

        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), mLock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        String prefix = "test_namespace";
        Map<String, String> keyValues =
                Map.of("test_namespace/flag0", "true", "test_namespace/flag2", "false");
        String packageName = "com.android.flags";

        parsed_flags flags = parsed_flags
                .newBuilder()
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setPackage(packageName)
                        .setName("flag3")
                        .setNamespace(prefix)
                        .setDescription("test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.DISABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setPackage("com.android.another.flags")
                        .setName("flag0")
                        .setNamespace("test_another_namespace")
                        .setDescription("test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.DISABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .build();

        synchronized (mLock) {
            settingsState.loadAconfigDefaultValues(
                    flags.toByteArray(), settingsState.getAconfigDefaultValues());
            List<String> updates =
                    settingsState.setSettingsLocked("test_namespace/", keyValues, packageName);
            assertEquals(3, updates.size());

            SettingsState.Setting s;

            s = settingsState.getSettingLocked("test_namespace/flag0");
            assertEquals("true", s.getValue());

            s = settingsState.getSettingLocked("test_namespace/flag1");
            assertNull(s.getValue());

            s = settingsState.getSettingLocked("test_namespace/flag2");
            assertEquals("false", s.getValue());

            s = settingsState.getSettingLocked("test_namespace/com.android.flags.flag3");
            assertEquals("false", s.getValue());

            s = settingsState.getSettingLocked(
                    "test_another_namespace/com.android.another.flags.flag0");
            assertEquals("false", s.getValue());
        }
    }

    @Test
    public void testsetSettingsLockedNoTrunkDefault() throws Exception {
        final PrintStream os = new PrintStream(new FileOutputStream(mSettingsFile));
        os.print(
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>"
                        + "<settings version=\"120\">"
                        + "  <setting id=\"0\" name=\"test_namespace/flag0\" "
                            + "value=\"false\" package=\"com.android.flags\" />"
                        + "  <setting id=\"1\" name=\"test_namespace/flag1\" "
                            + "value=\"false\" package=\"com.android.flags\" />"
                        + "</settings>");
        os.close();

        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);

        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), mLock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        Map<String, String> keyValues =
                Map.of("test_namespace/flag0", "true", "test_namespace/flag2", "false");
        String packageName = "com.android.flags";

        synchronized (mLock) {
            List<String> updates =
                    settingsState.setSettingsLocked("test_namespace/", keyValues, packageName);
            assertEquals(3, updates.size());

            SettingsState.Setting s;

            s = settingsState.getSettingLocked("test_namespace/flag0");
            assertEquals("true", s.getValue());

            s = settingsState.getSettingLocked("test_namespace/flag1");
            assertNull(s.getValue());

            s = settingsState.getSettingLocked("test_namespace/flag2");
            assertEquals("false", s.getValue());
        }
    }

    @Test
    public void testMemoryUsagePerPackage_SameSettingUsedByDifferentPackages() {
        SettingsState settingsState =
                new SettingsState(
                        InstrumentationRegistry.getContext(), mLock, mSettingsFile, 1,
                        SettingsState.MAX_BYTES_PER_APP_PACKAGE_LIMITED, Looper.getMainLooper());
        final String testKey1 = SETTING_NAME;
        final String testKey2 = SETTING_NAME + "_2";
        final String testValue1 = Strings.repeat("A", 100);
        final String testValue2 = Strings.repeat("A", 50);
        final String package1 = "p1";
        final String package2 = "p2";

        settingsState.insertSettingLocked(testKey1, testValue1, null, false, package1);
        settingsState.insertSettingLocked(testKey2, testValue1, null, true, package2);
        // Package1's usage should be remain the same Package2 owns a different setting
        int expectedMemUsageForPackage1 = (testKey1.length() + testValue1.length())
                * Character.BYTES;
        int expectedMemUsageForPackage2 = (testKey2.length() + testValue1.length()
                + testValue1.length() /* size for default */) * Character.BYTES;
        assertEquals(expectedMemUsageForPackage1, settingsState.getMemoryUsage(package1));
        assertEquals(expectedMemUsageForPackage2, settingsState.getMemoryUsage(package2));

        settingsState.insertSettingLocked(testKey1, testValue2, null, false, package2);
        // Package1's usage should be cleared because the setting is taken over by another package
        expectedMemUsageForPackage1 = 0;
        assertEquals(expectedMemUsageForPackage1, settingsState.getMemoryUsage(package1));
        // Package2 now owns two settings
        expectedMemUsageForPackage2 = (testKey1.length() + testValue2.length()
                + testKey2.length() + testValue1.length()
                + testValue1.length() /* size for default */)
                * Character.BYTES;
        assertEquals(expectedMemUsageForPackage2, settingsState.getMemoryUsage(package2));

        settingsState.insertSettingLocked(testKey1, testValue1, null, true, package1);
        // Package1 now owns setting1
        expectedMemUsageForPackage1 = (testKey1.length() + testValue1.length()
                + testValue1.length() /* size for default */) * Character.BYTES;
        assertEquals(expectedMemUsageForPackage1, settingsState.getMemoryUsage(package1));
        // Package2 now only own setting2
        expectedMemUsageForPackage2 = (testKey2.length() + testValue1.length()
                + testValue1.length() /* size for default */) * Character.BYTES;
        assertEquals(expectedMemUsageForPackage2, settingsState.getMemoryUsage(package2));
    }

    @Test
    public void testGetFlagOverrideToSync() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        parsed_flags flags = parsed_flags
                .newBuilder()
                .addParsedFlag(parsed_flag
                    .newBuilder()
                        .setPackage("com.android.flags")
                        .setName("flag1")
                        .setNamespace("test_namespace")
                        .setDescription("test flag")
                        .addBug("12345678")
                        .setState(Aconfig.flag_state.DISABLED)
                        .setPermission(Aconfig.flag_permission.READ_WRITE))
                .build();

        synchronized (lock) {
            Map<String, Map<String, String>> defaults = new HashMap<>();
            settingsState.loadAconfigDefaultValues(flags.toByteArray(), defaults);
            Map<String, String> namespaceDefaults = defaults.get("test_namespace");
            assertEquals(1, namespaceDefaults.keySet().size());
            settingsState.addAconfigDefaultValuesFromMap(defaults);
        }

        // invalid flag name
        assertTrue(settingsState.getFlagOverrideToSync(
            "invalid_flag", "false") == null);

        // non aconfig flag
        assertTrue(settingsState.getFlagOverrideToSync(
            "some_namespace/some_flag", "false") == null);

        // server override
        FlagOverrideToSync flag = settingsState.getFlagOverrideToSync(
            "test_namespace/com.android.flags.flag1", "false");
        assertTrue(flag != null);
        assertEquals(flag.packageName, "com.android.flags");
        assertEquals(flag.flagName, "flag1");
        assertEquals(flag.flagValue, "false");
        assertEquals(flag.isLocal, false);

        // local override
        flag = settingsState.getFlagOverrideToSync(
            "device_config_overrides/test_namespace:com.android.flags.flag1", "false");
        assertTrue(flag != null);
        assertEquals(flag.packageName, "com.android.flags");
        assertEquals(flag.flagName, "flag1");
        assertEquals(flag.flagValue, "false");
        assertEquals(flag.isLocal, true);
    }

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    @Test
    @EnableFlags(com.android.aconfig_new_storage.Flags.FLAG_ENABLE_ACONFIG_STORAGE_DAEMON)
    public void testHandleBulkSyncWithAconfigdEnabled() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        synchronized (lock) {
            settingsState.insertSettingLocked("aconfigd_marker/bulk_synced",
                    "false", null, false, "aconfig");

            // first bulk sync
            ProtoOutputStream requests = settingsState.handleBulkSyncToNewStorage();
            assertTrue(requests != null);
            String value = settingsState.getSettingLocked("aconfigd_marker/bulk_synced").getValue();
            assertEquals("true", value);

            // send time should no longer bulk sync
            requests = settingsState.handleBulkSyncToNewStorage();
            assertTrue(requests == null);
            value = settingsState.getSettingLocked("aconfigd_marker/bulk_synced").getValue();
            assertEquals("true", value);
        }
    }

    @Test
    @DisableFlags(com.android.aconfig_new_storage.Flags.FLAG_ENABLE_ACONFIG_STORAGE_DAEMON)
    public void testHandleBulkSyncWithAconfigdDisabled() {
        int configKey = SettingsState.makeKey(SettingsState.SETTINGS_TYPE_CONFIG, 0);
        Object lock = new Object();
        SettingsState settingsState = new SettingsState(
                InstrumentationRegistry.getContext(), lock, mSettingsFile, configKey,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());

        synchronized (lock) {
            settingsState.insertSettingLocked("aconfigd_marker/bulk_synced",
                    "true", null, false, "aconfig");

            // when aconfigd is off, should change the marker to false
            ProtoOutputStream requests = settingsState.handleBulkSyncToNewStorage();
            assertTrue(requests == null);
            String value = settingsState.getSettingLocked("aconfigd_marker/bulk_synced").getValue();
            assertEquals("false", value);

            // marker started with false value, after call, it should remain false
            requests = settingsState.handleBulkSyncToNewStorage();
            assertTrue(requests == null);
            value = settingsState.getSettingLocked("aconfigd_marker/bulk_synced").getValue();
            assertEquals("false", value);
        }
    }
}
