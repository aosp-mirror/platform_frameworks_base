/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.Settings;
import android.provider.Settings.Global;
import android.provider.Settings.Secure;
import android.support.test.InstrumentationRegistry;
import android.support.test.runner.AndroidJUnit4;
import android.testing.TestableSettings.AcquireTimeoutException;
import android.testing.TestableSettings.SettingOverrider;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class TestableSettingsTest {

    public static final String NONEXISTENT_SETTING = "nonexistent_setting";
    private static final String TAG = "TestableSettingsTest";
    private SettingOverrider mOverrider;
    private ContentResolver mContentResolver;
    @Rule
    public final TestableContext mContext =
            new TestableContext(InstrumentationRegistry.getContext());

    @Before
    public void setup() throws AcquireTimeoutException {
        mOverrider = mContext.getSettingsProvider().acquireOverridesBuilder()
                .addSetting("secure", NONEXISTENT_SETTING)
                .addSetting("global", NONEXISTENT_SETTING, "initial value")
                .addSetting("global", Global.DEVICE_PROVISIONED)
                .build();
        mContentResolver = mContext.getContentResolver();
    }

    @Test
    public void testInitialValueSecure() {
        String value = Secure.getString(mContentResolver, NONEXISTENT_SETTING);
        assertNull(value);
    }

    @Test
    public void testInitialValueGlobal() {
        String value = Global.getString(mContentResolver, NONEXISTENT_SETTING);
        assertEquals("initial value", value);
    }

    @Test
    public void testSeparateTables() {
        Secure.putString(mContentResolver, NONEXISTENT_SETTING, "something");
        Global.putString(mContentResolver, NONEXISTENT_SETTING, "else");
        assertEquals("something", Secure.getString(mContentResolver, NONEXISTENT_SETTING));
        assertEquals("something", mOverrider.get("secure", NONEXISTENT_SETTING));
        assertEquals("else", Global.getString(mContentResolver, NONEXISTENT_SETTING));
        assertEquals("else", mOverrider.get("global", NONEXISTENT_SETTING));
    }

    @Test
    public void testPassThrough() {
        // Grab the value of a setting that is not overridden.
        assertTrue(Secure.getInt(mContentResolver, Secure.USER_SETUP_COMPLETE, 0) != 0);
    }

    @Test
    public void testOverrideExisting() {
        // Grab the value of a setting that is overridden and will be different than the actual
        // value.
        assertNull(Global.getString(mContentResolver, Global.DEVICE_PROVISIONED));
    }

    @Test
    public void testRelease() {
        // Verify different value.
        assertNull(Global.getString(mContentResolver, Global.DEVICE_PROVISIONED));
        mOverrider.release();
        mOverrider = null;
        // Verify actual value after release.
        assertEquals("1", Global.getString(mContentResolver, Global.DEVICE_PROVISIONED));
    }

    @Test
    public void testAutoRelease() throws Exception {
        mOverrider.release();
        mOverrider = null;
        mContext.getSettingsProvider().acquireOverridesBuilder()
                .addSetting("global", Global.DEVICE_PROVISIONED)
                .build();
    }

    @Test
    public void testContention() throws AcquireTimeoutException, InterruptedException {
        SettingOverrider[] overriders = new SettingOverrider[2];
        Object lock = new Object();
        String secure = "secure";
        String key = "something shared";
        String[] result = new String[1];
        overriders[0] = mContext.getSettingsProvider().acquireOverridesBuilder()
                .addSetting(secure, key, "Some craziness")
                .build();
        synchronized (lock) {
            HandlerThread t = runOnHandler(() -> {
                try {
                    // Grab the lock that will be used for the settings ownership to ensure
                    // we have some contention going on.
                    synchronized (mContext.getSettingsProvider().getLock()) {
                        synchronized (lock) {
                            // Let the other thread know to release the settings, but it won't
                            // be able to until this thread waits in the build() method.
                            lock.notify();
                        }
                        overriders[1] = mContext.getSettingsProvider()
                                .acquireOverridesBuilder()
                                .addSetting(secure, key, "default value")
                                .build();
                        // Ensure that the default is the one we set, and not left over from
                        // the other setting override.
                        result[0] = Settings.Secure.getString(mContentResolver, key);
                        synchronized (lock) {
                            // Let the main thread know we are done.
                            lock.notify();
                        }
                    }
                } catch (AcquireTimeoutException e) {
                    Log.e(TAG, "Couldn't acquire setting", e);
                }
            });
            // Wait for the thread to hold the acquire lock, then release the settings.
            lock.wait();
            overriders[0].release();
            // Wait for the thread to be done getting the value.
            lock.wait();
            // Quit and cleanup.
            t.quitSafely();
            assertNotNull(overriders[1]);
            overriders[1].release();
        }
        // Verify the value was the expected one from the thread's SettingOverride.
        assertEquals("default value", result[0]);
    }

    private HandlerThread runOnHandler(Runnable r) {
        HandlerThread t = new HandlerThread("Test Thread");
        t.start();
        new Handler(t.getLooper()).post(r);
        return t;
    }
}
