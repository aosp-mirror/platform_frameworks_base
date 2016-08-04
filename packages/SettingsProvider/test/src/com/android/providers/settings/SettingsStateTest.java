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
                serializer, null, "k", "v", "package");
        SettingsState.writeSingleSetting(
                SettingsState.SETTINGS_VERSION_NEW_ENCODING,
                serializer, "1", "k", "v", null);
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
                serializer, "1", key, value, "package");
    }

    /**
     * Make sure settings can be written to a file and also can be read.
     */
    public void testReadWrite() {
        final File file = new File(getContext().getCacheDir(), "setting.xml");
        file.delete();
        final Object lock = new Object();

        final SettingsState ssWriter = new SettingsState(lock, file, 1,
                SettingsState.MAX_BYTES_PER_APP_PACKAGE_UNLIMITED, Looper.getMainLooper());
        ssWriter.setVersionLocked(SettingsState.SETTINGS_VERSION_NEW_ENCODING);

        ssWriter.insertSettingLocked("k1", "\u0000", "package");
        ssWriter.insertSettingLocked("k2", "abc", "p2");
        ssWriter.insertSettingLocked("k3", null, "p2");
        ssWriter.insertSettingLocked("k4", CRAZY_STRING, "p3");
        synchronized (lock) {
            ssWriter.persistSyncLocked();
        }

        final SettingsState ssReader = new SettingsState(lock, file, 1,
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

        final SettingsState ss = new SettingsState(lock, file, 1,
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
}
