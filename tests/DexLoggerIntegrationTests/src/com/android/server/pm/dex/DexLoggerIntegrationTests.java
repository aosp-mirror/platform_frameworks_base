/*
 * Copyright 2017 The Android Open Source Project
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

package com.android.server.pm.dex;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.util.EventLog;
import dalvik.system.DexClassLoader;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Formatter;
import java.util.List;

/**
 * Integration tests for {@link com.android.server.pm.dex.DexLogger}.
 *
 * The setup for the test dynamically loads code in a jar extracted
 * from our assets (a secondary dex file).
 *
 * We then use adb to trigger secondary dex file reconcilation (and
 * wait for it to complete). As a side-effect of this DexLogger should
 * be notified of the file and should log the hash of the file's name
 * and content.  We verify that this message appears in the event log.
 *
 * Run with "atest DexLoggerIntegrationTests".
 */
@LargeTest
@RunWith(JUnit4.class)
public final class DexLoggerIntegrationTests {

    private static final String PACKAGE_NAME = "com.android.frameworks.dexloggertest";

    // Event log tag used for SNET related events
    private static final int SNET_TAG = 0x534e4554;
    // Subtag used to distinguish dynamic code loading events
    private static final String DCL_SUBTAG = "dcl";

    // Obtained via "echo -n copied.jar | sha256sum"
    private static final String EXPECTED_NAME_HASH =
            "1B6C71DB26F36582867432CCA12FB6A517470C9F9AABE9198DD4C5C030D6DC0C";

    private static String expectedContentHash;

    @BeforeClass
    public static void setUpAll() throws Exception {
        Context context = InstrumentationRegistry.getTargetContext();
        MessageDigest hasher = MessageDigest.getInstance("SHA-256");

        // Copy the jar from our Java resources to a private data directory
        File privateCopy = new File(context.getDir("jars", Context.MODE_PRIVATE), "copied.jar");
        Class<?> thisClass = DexLoggerIntegrationTests.class;
        try (InputStream input = thisClass.getResourceAsStream("/javalib.jar");
                OutputStream output = new FileOutputStream(privateCopy)) {
            byte[] buffer = new byte[1024];
            while (true) {
                int numRead = input.read(buffer);
                if (numRead < 0) {
                    break;
                }
                output.write(buffer, 0, numRead);
                hasher.update(buffer, 0, numRead);
            }
        }

        // Remember the SHA-256 of the file content to check that it is the same as
        // the value we see logged.
        Formatter formatter = new Formatter();
        for (byte b : hasher.digest()) {
            formatter.format("%02X", b);
        }
        expectedContentHash = formatter.toString();

        // Feed the jar to a class loader and make sure it contains what we expect.
        ClassLoader loader =
                new DexClassLoader(
                    privateCopy.toString(), null, null, context.getClass().getClassLoader());
        loader.loadClass("com.android.dcl.Simple");
    }

    @Test
    public void testDexLoggerReconcileGeneratesEvents() throws Exception {
        int[] tagList = new int[] { SNET_TAG };
        List<EventLog.Event> events = new ArrayList<>();

        // There may already be events in the event log - figure out the most recent one
        EventLog.readEvents(tagList, events);
        long previousEventNanos =
                events.isEmpty() ? 0 : events.get(events.size() - 1).getTimeNanos();
        events.clear();

        Process process = Runtime.getRuntime().exec(
            "cmd package reconcile-secondary-dex-files " + PACKAGE_NAME);
        int exitCode = process.waitFor();
        assertThat(exitCode).isEqualTo(0);

        int myUid = android.os.Process.myUid();
        String expectedMessage = EXPECTED_NAME_HASH + " " + expectedContentHash;

        EventLog.readEvents(tagList, events);
        boolean found = false;
        for (EventLog.Event event : events) {
            if (event.getTimeNanos() <= previousEventNanos) {
                continue;
            }
            Object[] data = (Object[]) event.getData();

            // We only care about DCL events that we generated.
            String subTag = (String) data[0];
            if (!DCL_SUBTAG.equals(subTag)) {
                continue;
            }
            int uid = (int) data[1];
            if (uid != myUid) {
                continue;
            }

            String message = (String) data[2];
            assertThat(message).isEqualTo(expectedMessage);
            found = true;
        }

        assertThat(found).isTrue();
    }
}
