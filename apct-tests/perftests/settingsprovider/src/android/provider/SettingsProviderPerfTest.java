/*
 * Copyright (C) 2022 The Android Open Source Project
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


package android.provider;

import android.content.ContentResolver;
import android.content.Context;
import android.perftests.utils.BenchmarkState;
import android.perftests.utils.PerfStatusReporter;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public final class SettingsProviderPerfTest {
    private static final String NAMESPACE = "testing";
    private static final String SETTING_NAME1 = "test:setting1";
    private static final String SETTING_NAME2 = "test-setting2";
    private static final String UNSET_SETTING = "test_unset_setting";

    private final ContentResolver mContentResolver;

    @Rule
    public final PerfStatusReporter mPerfStatusReporter = new PerfStatusReporter();

    public SettingsProviderPerfTest() {
        final Context context = InstrumentationRegistry.getInstrumentation().getContext();
        mContentResolver = context.getContentResolver();
    }

    @Before
    public void setUp() {
        Settings.Secure.putString(mContentResolver, SETTING_NAME1, "1");
        Settings.Config.putString(NAMESPACE, SETTING_NAME1, "1", true);
        Settings.Config.putString(NAMESPACE, SETTING_NAME2, "2", true);
    }

    @After
    public void destroy() {
        Settings.Secure.putString(mContentResolver, SETTING_NAME1, "null");
        Settings.Config.deleteString(NAMESPACE, SETTING_NAME1);
        Settings.Config.deleteString(NAMESPACE, SETTING_NAME2);
    }

    @Test
    public void testSettingsValueConsecutiveRead() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 0;
        while (state.keepRunning()) {
            state.pauseTiming();
            // Writing to setting2 should not invalidate setting1's cache
            Settings.Secure.putString(mContentResolver, SETTING_NAME2, Integer.toString(i));
            i++;
            state.resumeTiming();
            Settings.Secure.getString(mContentResolver, SETTING_NAME1);
        }
    }

    @Test
    public void testSettingsValueConsecutiveReadAfterWrite() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 0;
        while (state.keepRunning()) {
            state.pauseTiming();
            // Triggering the invalidation of setting1's cache
            Settings.Secure.putString(mContentResolver, SETTING_NAME1, Integer.toString(i));
            i++;
            state.resumeTiming();
            Settings.Secure.getString(mContentResolver, SETTING_NAME1);
        }
    }

    @Test
    public void testSettingsValueConsecutiveReadUnset() {
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Settings.Secure.getString(mContentResolver, UNSET_SETTING);
        }
    }

    @Test
    public void testSettingsNamespaceConsecutiveRead() {
        final List<String> names = new ArrayList<>();
        names.add(SETTING_NAME1);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        while (state.keepRunning()) {
            Settings.Config.getStrings(mContentResolver, NAMESPACE, names);
        }
    }

    @Test
    public void testSettingsNamespaceConsecutiveReadAfterWrite() {
        final List<String> names = new ArrayList<>();
        names.add(SETTING_NAME1);
        final BenchmarkState state = mPerfStatusReporter.getBenchmarkState();
        int i = 0;
        while (state.keepRunning()) {
            state.pauseTiming();
            // Triggering the invalidation of the list's cache
            Settings.Config.putString(NAMESPACE, SETTING_NAME2, Integer.toString(i), true);
            i++;
            state.resumeTiming();
            Settings.Config.getStrings(mContentResolver, NAMESPACE, names);
        }
    }
}
