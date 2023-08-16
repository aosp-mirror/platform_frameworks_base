/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.internal.content;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.IRemoteCallback;
import android.os.Looper;
import android.os.UserHandle;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A test to verify PackageMonitor implementation respects the app visibility.
 */
@RunWith(AndroidJUnit4.class)
public class PackageMonitorVisibilityTest {
    private static final String TEST_DATA_PATH = "/data/local/tmp/contenttests/";
    private static final String TEAT_APK_PATH =
            TEST_DATA_PATH + "TestVisibilityApp.apk";
    private static final String TEAT_APK_PACKAGE_NAME = "com.example.android.testvisibilityapp";
    private static final int WAIT_CALLBACK_CALLED_IN_SECONDS = 1;

    @Test
    public void testPackageMonitorCallbackMultipleRegisterThrowsException() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getContext();
        final IRemoteCallback callback = new IRemoteCallback.Stub() {
            @Override
            public void sendResult(android.os.Bundle bundle) {
                // do-nothing
            }
        };
        try {
            context.getPackageManager().registerPackageMonitorCallback(callback, 0);
            assertThrows(IllegalStateException.class,
                    () -> context.getPackageManager().registerPackageMonitorCallback(callback, 0));
        } finally {
            context.getPackageManager().unregisterPackageMonitorCallback(callback);
        }
    }

    @Test
    public void testPackageMonitorPackageVisible() throws Exception {
        TestVisibilityPackageMonitor testPackageMonitor = new TestVisibilityPackageMonitor();

        try {
            Context context = InstrumentationRegistry.getInstrumentation().getContext();
            testPackageMonitor.register(context, UserHandle.ALL,
                    new Handler(Looper.getMainLooper()));

            installTestPackage(true /* forceQueryable */);
            boolean result = testPackageMonitor.mCallbackCountDownLatch.await(
                    WAIT_CALLBACK_CALLED_IN_SECONDS, TimeUnit.SECONDS);

            int expectedUid = context.getPackageManager().getPackageUid(TEAT_APK_PACKAGE_NAME,
                    PackageManager.PackageInfoFlags.of(0));
            assertThat(result).isTrue();
            assertThat(testPackageMonitor.mAddedPackageName).isEqualTo(TEAT_APK_PACKAGE_NAME);
            assertThat(testPackageMonitor.mAddedPackageUid).isEqualTo(expectedUid);
        } finally {
            testPackageMonitor.unregister();
            uninstallTestPackage();
        }
    }

    @Test
    public void testPackageMonitorPackageNotVisible() throws Exception {
        TestVisibilityPackageMonitor testPackageMonitor = new TestVisibilityPackageMonitor();

        try {
            Context context = InstrumentationRegistry.getInstrumentation().getContext();
            testPackageMonitor.register(context, UserHandle.ALL,
                    new Handler(Looper.getMainLooper()));

            installTestPackage(false /* forceQueryable */);
            boolean result = testPackageMonitor.mCallbackCountDownLatch.await(
                    WAIT_CALLBACK_CALLED_IN_SECONDS, TimeUnit.SECONDS);

            assertThat(result).isFalse();
        } finally {
            testPackageMonitor.unregister();
            uninstallTestPackage();
        }
    }

    private static void installTestPackage(boolean forceQueryable) {
        final StringBuilder cmd = new StringBuilder("pm install ");
        if (forceQueryable) {
            cmd.append("--force-queryable ");
        }
        cmd.append(TEAT_APK_PATH);
        final String result = runShellCommand(cmd.toString());
        assertThat(result.trim()).contains("Success");
    }

    private static void uninstallTestPackage() {
        runShellCommand("pm uninstall " + TEAT_APK_PACKAGE_NAME);
    }

    private static class TestVisibilityPackageMonitor extends PackageMonitor {
        String mAddedPackageName;
        int mAddedPackageUid;
        CountDownLatch mCallbackCountDownLatch = new CountDownLatch(1);

        @Override
        public void onPackageAdded(String packageName, int uid) {
            if (!TEAT_APK_PACKAGE_NAME.equals(packageName)) {
                return;
            }
            mAddedPackageName = packageName;
            mAddedPackageUid = uid;
            mCallbackCountDownLatch.countDown();
        }
    }
}
