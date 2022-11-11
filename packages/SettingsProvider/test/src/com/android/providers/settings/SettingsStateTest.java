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

import android.os.Looper;
import android.test.AndroidTestCase;
import android.util.Xml;

import org.xmlpull.v1.XmlSerializer;

import com.google.common.base.Strings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;

public class SettingsStateTest extends AndroidTestCase {
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

    private final Object mLock = new Object();

    private File mSettingsFile;

    @Override
    protected void setUp() {
        mSettingsFile = new File(getContext().getCacheDir(), "setting.xml");
        mSettingsFile.delete();
    }

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
    public void testWriteReadNoCrash() throws Exception {
        ByteArrayOutputStream os = new ByteArrayOutputStream();

        XmlSerializer serializer = Xml.newSerializer();
        serializer.setOutput(os, StandardCharsets.UTF_8.name());
        serializer.setFeature("http://xmlpull.org/v1/doc/features.html#indent-output", true);
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

    private void checkWriteSingleSetting(XmlSerializer serializer, String key, String value)
            throws Exception {
        checkWriteSingleSetting(key + "/" + value, serializer, key, value);
    }

    private void checkWriteSingleSetting(String msg, XmlSerializer serializer,
            String key, String value) throws Exception {
        // Make sure the XML serializer won't crash.
        SettingsState.writeSingleSetting(
                SettingsState.SETTINGS_VERSION_NEW_ENCODING,
                serializer, "1", key, value, null, "package", null, false, false);
    }

    /**
     * Make sure settings can be written to a file and also can be read.
     */
    public void testReadWrite() {
        final File file = new File(getContext().getCacheDir(), "setting.xml");
        file.delete();
        final Object lock = new Object();

        final SettingsState ssWriter = new SettingsState(getContext(), lock, file, 1,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        ssWriter.setVersionLocked(SettingsState.SETTINGS_VERSION_NEW_ENCODING);

        ssWriter.insertSettingLocked("k1", "\u0000", null, false, "package");
        ssWriter.insertSettingLocked("k2", "abc", null, false, "p2");
        ssWriter.insertSettingLocked("k3", null, null, false, "p2");
        ssWriter.insertSettingLocked("k4", CRAZY_STRING, null, false, "p3");
        synchronized (lock) {
            ssWriter.persistSyncLocked();
        }

        final SettingsState ssReader = new SettingsState(getContext(), lock, file, 1,
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
    public void testUpgrade() throws Exception {
        final File file = new File(getContext().getCacheDir(), "setting.xml");
        file.delete();
        final Object lock = new Object();
        final PrintStream os = new PrintStream(new FileOutputStream(file));
        os.print(
                "<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>" +
                "<settings version=\"120\">" +
                "  <setting id=\"0\" name=\"k0\" value=\"null\" package=\"null\" />" +
                "  <setting id=\"1\" name=\"k1\" value=\"\" package=\"\" />" +
                "  <setting id=\"2\" name=\"k2\" value=\"v2\" package=\"p2\" />" +
                "</settings>");
        os.close();

        final SettingsState ss = new SettingsState(getContext(), lock, file, 1,
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

    public void testInitializeSetting_preserveFlagNotSet() {
        SettingsState settingsWriter = getSettingStateObject();
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        settingsWriter.persistSyncLocked();

        SettingsState settingsReader = getSettingStateObject();
        assertFalse(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    public void testModifySetting_preserveFlagSet() {
        SettingsState settingsWriter = getSettingStateObject();
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        settingsWriter.insertSettingLocked(SETTING_NAME, "2", null, false, TEST_PACKAGE);
        settingsWriter.persistSyncLocked();

        SettingsState settingsReader = getSettingStateObject();
        assertTrue(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    public void testModifySettingOverrideableByRestore_preserveFlagNotSet() {
        SettingsState settingsWriter = getSettingStateObject();
        settingsWriter.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        settingsWriter.insertSettingLocked(SETTING_NAME, "2", null, false, false, TEST_PACKAGE,
                /* overrideableByRestore */ true);
        settingsWriter.persistSyncLocked();

        SettingsState settingsReader = getSettingStateObject();
        assertFalse(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

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
        settingsWriter.persistSyncLocked();

        SettingsState settingsReader = getSettingStateObject();
        assertTrue(settingsReader.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    public void testResetSetting_preservedFlagIsReset() {
        SettingsState settingsState = getSettingStateObject();
        // Initialize the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, TEST_PACKAGE);
        // Update the setting so that preserved flag is set.
        settingsState.insertSettingLocked(SETTING_NAME, "2", null, false, TEST_PACKAGE);

        settingsState.resetSettingLocked(SETTING_NAME);
        assertFalse(settingsState.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());

    }

    public void testModifySettingBySystemPackage_sameValue_preserveFlagNotSet() {
        SettingsState settingsState = getSettingStateObject();
        // Initialize the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, SYSTEM_PACKAGE);
        // Update the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, SYSTEM_PACKAGE);

        assertFalse(settingsState.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    public void testModifySettingBySystemPackage_newValue_preserveFlagSet() {
        SettingsState settingsState = getSettingStateObject();
        // Initialize the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "1", null, false, SYSTEM_PACKAGE);
        // Update the setting.
        settingsState.insertSettingLocked(SETTING_NAME, "2", null, false, SYSTEM_PACKAGE);

        assertTrue(settingsState.getSettingLocked(SETTING_NAME).isValuePreservedInRestore());
    }

    private SettingsState getSettingStateObject() {
        SettingsState settingsState = new SettingsState(getContext(), mLock, mSettingsFile, 1,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        settingsState.setVersionLocked(SettingsState.SETTINGS_VERSION_NEW_ENCODING);
        return settingsState;
    }

    public void testInsertSetting_memoryUsage() {
        SettingsState settingsState = new SettingsState(getContext(), mLock, mSettingsFile, 1,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        // No exception should be thrown when there is no cap
        settingsState.insertSettingLocked(SETTING_NAME, Strings.repeat("A", 20001),
                null, false, "p1");
        settingsState.deleteSettingLocked(SETTING_NAME);

        settingsState = new SettingsState(getContext(), mLock, mSettingsFile, 1,
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

    public void testMemoryUsagePerPackage() {
        SettingsState settingsState = new SettingsState(getContext(), mLock, mSettingsFile, 1,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_LIMITED, Looper.getMainLooper());

        // Test inserting one key with default
        final String testKey1 = SETTING_NAME;
        final String testValue1 = Strings.repeat("A", 100);
        settingsState.insertSettingLocked(testKey1, testValue1, null, true, TEST_PACKAGE);
        int expectedMemUsage = testKey1.length() + testValue1.length()
                + testValue1.length() /* size for default */;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test inserting another key
        final String testKey2 = SETTING_NAME + "2";
        settingsState.insertSettingLocked(testKey2, testValue1, null, false, TEST_PACKAGE);
        expectedMemUsage += testKey2.length() + testValue1.length();
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test updating first key with new default
        final String testValue2 = Strings.repeat("A", 300);
        settingsState.insertSettingLocked(testKey1, testValue2, null, true, TEST_PACKAGE);
        expectedMemUsage += (testValue2.length() - testValue1.length()) * 2;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test updating first key without new default
        final String testValue3 = Strings.repeat("A", 50);
        settingsState.insertSettingLocked(testKey1, testValue3, null, false, TEST_PACKAGE);
        expectedMemUsage -= testValue2.length() - testValue3.length();
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test updating second key
        settingsState.insertSettingLocked(testKey2, testValue2, null, false, TEST_PACKAGE);
        expectedMemUsage -= testValue1.length() - testValue2.length();
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test resetting key
        settingsState.resetSettingLocked(testKey1);
        expectedMemUsage += testValue2.length() - testValue3.length();
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test resetting default value
        settingsState.resetSettingDefaultValueLocked(testKey1);
        expectedMemUsage -= testValue2.length();
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test deletion
        settingsState.deleteSettingLocked(testKey2);
        expectedMemUsage -= testValue2.length() + testKey2.length() /* key is deleted too */;
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test another package with a different key
        final String testPackage2 = TEST_PACKAGE + "2";
        final String testKey3 = SETTING_NAME + "3";
        settingsState.insertSettingLocked(testKey3, testValue1, null, true, testPackage2);
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));
        final int expectedMemUsage2 = testKey3.length() + testValue1.length() * 2;
        assertEquals(expectedMemUsage2, settingsState.getMemoryUsage(testPackage2));

        // Test system package
        settingsState.insertSettingLocked(testKey1, testValue1, null, true, SYSTEM_PACKAGE);
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));
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
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));

        // Test invalid key
        try {
            settingsState.insertSettingLocked(Strings.repeat("A", 20001), "", null, false,
                    TEST_PACKAGE);
            fail("Should throw because it exceeded per package memory usage");
        } catch (IllegalStateException ex) {
            assertTrue(ex.getMessage().contains("You are adding too many system settings"));
        }
        assertEquals(expectedMemUsage, settingsState.getMemoryUsage(TEST_PACKAGE));
    }
}
