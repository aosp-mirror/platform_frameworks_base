/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm;

import static android.os.Build.VERSION_CODES;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.Looper;
import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.filters.MediumTest;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Test for the splash screen exception list
 * atest WmTests:SplashScreenExceptionListTest
 */
@MediumTest
@Presubmit
public class SplashScreenExceptionListTest {

    // Constant copied on purpose so it's not refactored by accident.
    // If the key needs to be modified, the server side key also needs to be changed.
    private static final String KEY_SPLASH_SCREEN_EXCEPTION_LIST = "splash_screen_exception_list";

    private DeviceConfig.Properties mInitialWindowManagerProperties;
    private final HandlerExecutor mExecutor = new HandlerExecutor(
            new Handler(Looper.getMainLooper()));
    private final SplashScreenExceptionList mList = new SplashScreenExceptionList(mExecutor) {
        @Override
        void updateDeviceConfig(String rawList) {
            super.updateDeviceConfig(rawList);
            if (mOnUpdateDeviceConfig != null) {
                mOnUpdateDeviceConfig.accept(rawList);
            }
        }
    };
    private Consumer<String> mOnUpdateDeviceConfig;

    @Before
    public void setUp() throws Exception {
        mInitialWindowManagerProperties = DeviceConfig.getProperties(
                DeviceConfig.NAMESPACE_WINDOW_MANAGER);
        clearConstrainDisplayApisFlags();
    }

    private void clearConstrainDisplayApisFlags() {
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                KEY_SPLASH_SCREEN_EXCEPTION_LIST,
                null, /* makeDefault= */ false);
    }

    @After
    public void tearDown() throws Exception {
        DeviceConfig.setProperties(mInitialWindowManagerProperties);
        DeviceConfig.removeOnPropertiesChangedListener(mList.mOnPropertiesChangedListener);
    }

    @Test
    public void packageFromDeviceConfigIgnored() {
        setExceptionListAndWaitForCallback("com.test.nosplashscreen1,com.test.nosplashscreen2");

        // In list, up to T included
        assertIsException("com.test.nosplashscreen1", VERSION_CODES.R);
        assertIsException("com.test.nosplashscreen1", VERSION_CODES.S);
        assertIsException("com.test.nosplashscreen1", VERSION_CODES.TIRAMISU);

        // In list, after T
        assertIsNotException("com.test.nosplashscreen2", VERSION_CODES.TIRAMISU + 1);
        assertIsNotException("com.test.nosplashscreen2", VERSION_CODES.CUR_DEVELOPMENT);

        // Not in list, up to T included
        assertIsNotException("com.test.splashscreen", VERSION_CODES.S);
        assertIsNotException("com.test.splashscreen", VERSION_CODES.R);
        assertIsNotException("com.test.splashscreen", VERSION_CODES.TIRAMISU);
    }

    @Test
    public void metaDataOptOut() {
        String packageName = "com.test.nosplashscreen_opt_out";
        setExceptionListAndWaitForCallback(packageName);

        Bundle metaData = new Bundle();
        ApplicationInfo activityInfo = new ApplicationInfo();
        activityInfo.metaData = metaData;

        // No Exceptions
        metaData.putBoolean("android.splashscreen.exception_opt_out", true);
        assertIsNotException(packageName, VERSION_CODES.R, activityInfo);
        assertIsNotException(packageName, VERSION_CODES.S, activityInfo);
        assertIsNotException(packageName, VERSION_CODES.TIRAMISU, activityInfo);

        // Exception up to T
        metaData.putBoolean("android.splashscreen.exception_opt_out", false);
        assertIsException(packageName, VERSION_CODES.R, activityInfo);
        assertIsException(packageName, VERSION_CODES.S, activityInfo);
        assertIsException(packageName, VERSION_CODES.TIRAMISU, activityInfo);

        // No Exception after T
        assertIsNotException(packageName, VERSION_CODES.TIRAMISU + 1, activityInfo);
        assertIsNotException(packageName, VERSION_CODES.CUR_DEVELOPMENT, activityInfo);

        // Edge Cases
        activityInfo.metaData = null;
        assertIsException(packageName, VERSION_CODES.R, activityInfo);
        assertIsException(packageName, VERSION_CODES.R);
    }

    private void setExceptionListAndWaitForCallback(String commaSeparatedList) {
        CountDownLatch latch = new CountDownLatch(1);
        mOnUpdateDeviceConfig = rawList -> {
            if (commaSeparatedList.equals(rawList)) {
                latch.countDown();
            }
        };
        DeviceConfig.setProperty(DeviceConfig.NAMESPACE_WINDOW_MANAGER,
                KEY_SPLASH_SCREEN_EXCEPTION_LIST, commaSeparatedList, false);
        try {
            assertTrue("Timed out waiting for DeviceConfig to be updated.",
                    latch.await(1, TimeUnit.SECONDS));
        } catch (InterruptedException e) {
            Assert.fail(e.getMessage());
        }
    }

    private void assertIsNotException(String packageName, int targetSdk) {
        assertIsNotException(packageName, targetSdk, null);
    }

    private void assertIsNotException(String packageName, int targetSdk,
            ApplicationInfo activityInfo) {
        assertFalse(String.format("%s (sdk=%d) should have not been considered as an exception",
                        packageName, targetSdk),
                mList.isException(packageName, targetSdk, () -> activityInfo));
    }

    private void assertIsException(String packageName, int targetSdk) {
        assertIsException(packageName, targetSdk, null);
    }

    private void assertIsException(String packageName,
            int targetSdk, ApplicationInfo activityInfo) {
        assertTrue(String.format("%s (sdk=%d) should have been considered as an exception",
                        packageName, targetSdk),
                mList.isException(packageName, targetSdk, () -> activityInfo));
    }
}
