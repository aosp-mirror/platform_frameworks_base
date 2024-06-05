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
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;

import android.content.ContentResolver;
import android.os.Bundle;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.google.common.io.CharStreams;

import libcore.io.Streams;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Tests for {@link DeviceConfigService}.
 */
@RunWith(AndroidJUnit4.class)
public class DeviceConfigServiceTest {
    private static final String sNamespace = "namespace1";
    private static final String sKey = "key1";
    private static final String sValue = "value1";

    private ContentResolver mContentResolver;

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    @Before
    public void setUp() {
        mContentResolver = InstrumentationRegistry.getContext().getContentResolver();
    }

    @After
    public void cleanUp() {
        deleteFromContentProvider(mContentResolver, sNamespace, sKey);
    }

    /**
     * Test that setting overrides are properly disabled when the flag is off.
     */
    @Test
    @RequiresFlagsDisabled("com.android.providers.settings.support_overrides")
    public void testOverrideDisabled() throws IOException {
        final String newValue = "value2";

        executeShellCommand("device_config put " + sNamespace + " " + sKey + " " + sValue);
        executeShellCommand("device_config override " + sNamespace + " " + sKey + " " + newValue);
        String result = readShellCommandOutput("device_config get " + sNamespace + " " + sKey);
        assertEquals(sValue + "\n", result);
    }

    /**
     * Test that overrides are readable and can be cleared.
     */
    @Test
    @RequiresFlagsEnabled("com.android.providers.settings.support_overrides")
    public void testOverride() throws IOException {
        final String newValue = "value2";

        executeShellCommand("device_config put " + sNamespace + " " + sKey + " " + sValue);
        executeShellCommand("device_config override " + sNamespace + " " + sKey + " " + newValue);

        String result = readShellCommandOutput("device_config get " + sNamespace + " " + sKey);
        assertEquals(newValue + "\n", result);

        executeShellCommand("device_config clear_override " + sNamespace + " " + sKey);
        result = readShellCommandOutput("device_config get " + sNamespace + " " + sKey);
        assertEquals(sValue + "\n", result);
    }

    @Test
    public void testPut() throws Exception {
        final String newNamespace = "namespace2";
        final String newValue = "value2";

        String result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        assertNull(result);

        try {
            executeShellCommand("device_config put " + sNamespace + " " + sKey + " " + sValue);
            executeShellCommand("device_config put " + newNamespace + " " + sKey + " " + newValue);

            result = getFromContentProvider(mContentResolver, sNamespace, sKey);
            assertEquals(sValue, result);
            result = getFromContentProvider(mContentResolver, newNamespace, sKey);
            assertEquals(newValue, result);
        } finally {
            deleteFromContentProvider(mContentResolver, newNamespace, sKey);
        }
    }

    @Test
    public void testPut_invalidArgs() throws Exception {
        // missing sNamespace
        executeShellCommand("device_config put " + sKey + " " + sValue);
        String result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        // still null
        assertNull(result);

        // too many arguments
        executeShellCommand(
                "device_config put " + sNamespace + " " + sKey + " " + sValue + " extra_arg");
        result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        // still null
        assertNull(result);
    }

    @Test
    public void testDelete() throws Exception {
        final String newNamespace = "namespace2";

        putWithContentProvider(mContentResolver, sNamespace, sKey, sValue);
        putWithContentProvider(mContentResolver, newNamespace, sKey, sValue);
        String result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        assertEquals(sValue, result);
        result = getFromContentProvider(mContentResolver, newNamespace, sKey);
        assertEquals(sValue, result);

        try {
            executeShellCommand("device_config delete " + sNamespace + " " + sKey);
            // sKey is deleted from sNamespace
            result = getFromContentProvider(mContentResolver, sNamespace, sKey);
            assertNull(result);
            // sKey is not deleted from newNamespace
            result = getFromContentProvider(mContentResolver, newNamespace, sKey);
            assertEquals(sValue, result);
        } finally {
            deleteFromContentProvider(mContentResolver, newNamespace, sKey);
        }
    }

    @Test
    public void testDelete_invalidArgs() throws Exception {
        putWithContentProvider(mContentResolver, sNamespace, sKey, sValue);
        String result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        assertEquals(sValue, result);

        // missing sNamespace
        executeShellCommand("device_config delete " + sKey);
        result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        // sValue was not deleted
        assertEquals(sValue, result);

        // too many arguments
        executeShellCommand("device_config delete " + sNamespace + " " + sKey + " extra_arg");
        result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        // sValue was not deleted
        assertEquals(sValue, result);
    }

    @Test
    public void testReset() throws Exception {
        String newValue = "value2";

        // make sValue the default value
        executeShellCommand(
                "device_config put " + sNamespace + " " + sKey + " " + sValue + " default");
        // make newValue the current value (as set by a untrusted package)
        executeShellCommand(
                "device_config put " + sNamespace + " " + sKey + " " + newValue);
        String result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        assertEquals(newValue, result);

        // reset values that were set by untrusted packages
        executeShellCommand("device_config reset untrusted_defaults " + sNamespace);
        result = getFromContentProvider(mContentResolver, sNamespace, sKey);
        // the current value was set by a untrusted package, so it's reset
        assertEquals(sValue, result);
    }

    private static void executeShellCommand(String command) throws IOException {
        InputStream is = new FileInputStream(InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command).getFileDescriptor());
        Streams.readFully(is);
    }

    private static String readShellCommandOutput(String command) throws IOException {
        InputStream is = new FileInputStream(InstrumentationRegistry.getInstrumentation()
                .getUiAutomation().executeShellCommand(command).getFileDescriptor());
        return CharStreams.toString(new InputStreamReader(is, StandardCharsets.UTF_8));
    }

    private static void putWithContentProvider(ContentResolver resolver, String namespace,
            String key, String value) {
        putWithContentProvider(resolver, namespace, key, value, false);
    }

    private static void putWithContentProvider(ContentResolver resolver, String namespace,
            String key, String value, boolean makeDefault) {
        String compositeName = namespace + "/" + key;
        Bundle args = new Bundle();
        args.putString(Settings.NameValueTable.VALUE, value);
        if (makeDefault) {
            args.putBoolean(Settings.CALL_METHOD_MAKE_DEFAULT_KEY, true);
        }
        resolver.call(
                Settings.Config.CONTENT_URI, Settings.CALL_METHOD_PUT_CONFIG, compositeName, args);
    }

    private static String getFromContentProvider(ContentResolver resolver, String namespace,
            String key) {
        String compositeName = namespace + "/" + key;
        Bundle result = resolver.call(
                Settings.Config.CONTENT_URI, Settings.CALL_METHOD_GET_CONFIG, compositeName, null);
        assertNotNull(result);
        return result.getString(Settings.NameValueTable.VALUE);
    }

    private static boolean deleteFromContentProvider(ContentResolver resolver, String namespace,
            String key) {
        String compositeName = namespace + "/" + key;
        Bundle result = resolver.call(
                Settings.Config.CONTENT_URI,
                Settings.CALL_METHOD_DELETE_CONFIG, compositeName, null);
        assertNotNull(result);
        return compositeName.equals(result.getString(Settings.NameValueTable.VALUE));
    }
}
