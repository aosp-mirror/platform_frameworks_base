/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.server.devicepolicy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link PasswordBlacklist}.
 *
 * bit FrameworksServicesTests:com.android.server.devicepolicy.PasswordBlacklistTest
 * runtest -x frameworks/base/services/tests/servicestests/src/com/android/server/devicepolicy/PasswordBlacklistTest.java
 */
@RunWith(AndroidJUnit4.class)
public final class PasswordBlacklistTest {
    private File mBlacklistFile;
    private PasswordBlacklist mBlacklist;

    @Before
    public void setUp() throws IOException {
        mBlacklistFile = File.createTempFile("pwdbl", null);
        mBlacklist = new PasswordBlacklist(mBlacklistFile);
    }

    @After
    public void tearDown() {
        mBlacklist.delete();
    }

    @Test
    public void matchIsExact() {
        // Note: Case sensitivity is handled by the user of PasswordBlacklist by normalizing the
        // values stored in and tested against it.
        mBlacklist.savePasswordBlacklist("matchIsExact", Arrays.asList("password", "qWERty"));
        assertTrue(mBlacklist.isPasswordBlacklisted("password"));
        assertTrue(mBlacklist.isPasswordBlacklisted("qWERty"));
        assertFalse(mBlacklist.isPasswordBlacklisted("Password"));
        assertFalse(mBlacklist.isPasswordBlacklisted("qwert"));
        assertFalse(mBlacklist.isPasswordBlacklisted("letmein"));
    }

    @Test
    public void matchIsNotRegex() {
        mBlacklist.savePasswordBlacklist("matchIsNotRegex", Arrays.asList("a+b*"));
        assertTrue(mBlacklist.isPasswordBlacklisted("a+b*"));
        assertFalse(mBlacklist.isPasswordBlacklisted("aaaa"));
        assertFalse(mBlacklist.isPasswordBlacklisted("abbbb"));
        assertFalse(mBlacklist.isPasswordBlacklisted("aaaa"));
    }

    @Test
    public void matchFailsSafe() throws IOException {
        try (FileOutputStream fos = new FileOutputStream(mBlacklistFile)) {
            // Write a malformed blacklist file
            fos.write(17);
        }
        assertTrue(mBlacklist.isPasswordBlacklisted("anything"));
        assertTrue(mBlacklist.isPasswordBlacklisted("at"));
        assertTrue(mBlacklist.isPasswordBlacklisted("ALL"));
    }

    @Test
    public void blacklistCanBeNamed() {
        final String name = "identifier";
        mBlacklist.savePasswordBlacklist(name, Arrays.asList("one", "two", "three"));
        assertEquals(mBlacklist.getName(), name);
    }

    @Test
    public void reportsTheCorrectNumberOfEntries() {
        mBlacklist.savePasswordBlacklist("Count Entries", Arrays.asList("1", "2", "3", "4"));
        assertEquals(mBlacklist.getSize(), 4);
    }

    @Test
    public void reportsBlacklistFile() {
        assertEquals(mBlacklistFile, mBlacklist.getFile());
    }
}
