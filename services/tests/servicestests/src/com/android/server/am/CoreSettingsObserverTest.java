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

package com.android.server.am;

import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;

import static com.android.server.am.ActivityManagerService.Injector;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.test.mock.MockContentResolver;

import androidx.test.filters.SmallTest;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.server.appop.AppOpsService;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;

/**
 * Test class for {@link CoreSettingsObserver}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:CoreSettingsObserverTest
 */
@SmallTest
public class CoreSettingsObserverTest {
    private static final String TEST_SETTING_SECURE_INT = "secureInt";
    private static final String TEST_SETTING_GLOBAL_FLOAT = "globalFloat";
    private static final String TEST_SETTING_SYSTEM_STRING = "systemString";

    private static final int TEST_INT = 111;
    private static final float TEST_FLOAT = 3.14f;
    private static final String TEST_STRING = "testString";

    private ActivityManagerService mAms;
    @Mock private Context mContext;

    private MockContentResolver mContentResolver;
    private CoreSettingsObserver mCoreSettingsObserver;

    @BeforeClass
    public static void setupOnce() {
        FakeSettingsProvider.clearSettingsProvider();
        CoreSettingsObserver.sSecureSettingToTypeMap.put(TEST_SETTING_SECURE_INT, int.class);
        CoreSettingsObserver.sGlobalSettingToTypeMap.put(TEST_SETTING_GLOBAL_FLOAT, float.class);
        CoreSettingsObserver.sSystemSettingToTypeMap.put(TEST_SETTING_SYSTEM_STRING, String.class);
    }

    @AfterClass
    public static void tearDownOnce() {
        FakeSettingsProvider.clearSettingsProvider();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        final Context originalContext = getInstrumentation().getTargetContext();
        when(mContext.getApplicationInfo()).thenReturn(originalContext.getApplicationInfo());
        mContentResolver = new MockContentResolver(mContext);
        mContentResolver.addProvider(Settings.AUTHORITY, new FakeSettingsProvider());
        when(mContext.getContentResolver()).thenReturn(mContentResolver);

        mAms = new ActivityManagerService(new TestInjector());
        mCoreSettingsObserver = new CoreSettingsObserver(mAms);
    }

    @Test
    public void testPopulateSettings() {
        Settings.Secure.putInt(mContentResolver, TEST_SETTING_SECURE_INT, TEST_INT);
        Settings.Global.putFloat(mContentResolver, TEST_SETTING_GLOBAL_FLOAT, TEST_FLOAT);
        Settings.System.putString(mContentResolver, TEST_SETTING_SYSTEM_STRING, TEST_STRING);

        final Bundle settingsBundle = getPopulatedBundle();

        assertEquals("Unexpected value of " + TEST_SETTING_SECURE_INT,
                TEST_INT, settingsBundle.getInt(TEST_SETTING_SECURE_INT));
        assertEquals("Unexpected value of " + TEST_SETTING_GLOBAL_FLOAT,
                TEST_FLOAT, settingsBundle.getFloat(TEST_SETTING_GLOBAL_FLOAT), 0);
        assertEquals("Unexpected value of " + TEST_SETTING_SYSTEM_STRING,
                TEST_STRING, settingsBundle.getString(TEST_SETTING_SYSTEM_STRING));
    }

    @Test
    public void testPopulateSettings_settingNotSet() {
        final Bundle settingsBundle = getPopulatedBundle();

        assertWithMessage("Bundle should not contain " + TEST_SETTING_SECURE_INT)
                .that(settingsBundle.keySet()).doesNotContain(TEST_SETTING_SECURE_INT);
        assertWithMessage("Bundle should not contain " + TEST_SETTING_GLOBAL_FLOAT)
                .that(settingsBundle.keySet()).doesNotContain(TEST_SETTING_GLOBAL_FLOAT);
        assertWithMessage("Bundle should not contain " + TEST_SETTING_SYSTEM_STRING)
                .that(settingsBundle.keySet()).doesNotContain(TEST_SETTING_SYSTEM_STRING);
    }

    @Test
    public void testPopulateSettings_settingDeleted() {
        Settings.Secure.putInt(mContentResolver, TEST_SETTING_SECURE_INT, TEST_INT);
        Settings.Global.putFloat(mContentResolver, TEST_SETTING_GLOBAL_FLOAT, TEST_FLOAT);
        Settings.System.putString(mContentResolver, TEST_SETTING_SYSTEM_STRING, TEST_STRING);

        Bundle settingsBundle = getPopulatedBundle();

        assertEquals("Unexpected value of " + TEST_SETTING_SECURE_INT,
                TEST_INT, settingsBundle.getInt(TEST_SETTING_SECURE_INT));
        assertEquals("Unexpected value of " + TEST_SETTING_GLOBAL_FLOAT,
                TEST_FLOAT, settingsBundle.getFloat(TEST_SETTING_GLOBAL_FLOAT), 0);
        assertEquals("Unexpected value of " + TEST_SETTING_SYSTEM_STRING,
                TEST_STRING, settingsBundle.getString(TEST_SETTING_SYSTEM_STRING));

        Settings.Global.putString(mContentResolver, TEST_SETTING_GLOBAL_FLOAT, null);
        settingsBundle = getPopulatedBundle();

        assertWithMessage("Bundle should not contain " + TEST_SETTING_GLOBAL_FLOAT)
                .that(settingsBundle.keySet()).doesNotContain(TEST_SETTING_GLOBAL_FLOAT);
        assertEquals("Unexpected value of " + TEST_SETTING_SECURE_INT,
                TEST_INT, settingsBundle.getInt(TEST_SETTING_SECURE_INT));
        assertEquals("Unexpected value of " + TEST_SETTING_SYSTEM_STRING,
                TEST_STRING, settingsBundle.getString(TEST_SETTING_SYSTEM_STRING));

    }

    private Bundle getPopulatedBundle() {
        mCoreSettingsObserver.onChange(false);
        return mCoreSettingsObserver.getCoreSettingsLocked();
    }

    private class TestInjector extends Injector {
        @Override
        public Context getContext() {
            return mContext;
        }

        @Override
        public AppOpsService getAppOpsService(File file, Handler handler) {
            return null;
        }

        @Override
        public Handler getUiHandler(ActivityManagerService service) {
            return null;
        }
    }
}
