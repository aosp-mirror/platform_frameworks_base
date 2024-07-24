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

package android.os;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.platform.test.annotations.IgnoreUnderRavenwood;
import android.platform.test.ravenwood.RavenwoodRule;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.android.frameworks.coretests.aidl.IBpcCallbackObserver;
import com.android.frameworks.coretests.aidl.IBpcTestAppCmdService;
import com.android.frameworks.coretests.aidl.IBpcTestServiceCmdService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Tests for verifying the Binder Proxy Counting and Limiting.
 *
 * To manually build and install relevant test apps
 *
 * Build:
 * mmma frameworks/base/core/tests/coretests/BinderProxyCountingTestApp
 * mmma frameworks/base/core/tests/coretests/BinderProxyCountingTestService
 * Install:
 * adb install -r \
 * ${ANDROID_PRODUCT_OUT}/data/app/BinderProxyCountingTestApp/BinderProxyCountingTestApp.apk
 * adb install -r \
 * ${ANDROID_PRODUCT_OUT}/data/app/BinderProxyCountingTestService/BinderProxyCountingTestService.apk
 *
 * To run the tests, use
 *
 * Build: m FrameworksCoreTests
 * Install: adb install -r \
 * ${ANDROID_PRODUCT_OUT}/data/app/FrameworksCoreTests/FrameworksCoreTests.apk
 * Run: adb shell am instrument -e class android.os.BinderProxyCountingTest -w \
 * com.android.frameworks.coretests/androidx.test.runner.AndroidJUnitRunner
 *
 * or
 *
 * bit FrameworksCoreTests:android.os.BinderProxyCountingTest
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
@IgnoreUnderRavenwood(blockedBy = ActivityManager.class)
public class BinderProxyCountingTest {
    private static final String TAG = BinderProxyCountingTest.class.getSimpleName();

    private static final String TEST_APP_PKG =
            "com.android.frameworks.coretests.binderproxycountingtestapp";
    private static final String TEST_APP_CMD_SERVICE = TEST_APP_PKG + ".BpcTestAppCmdService";
    private static final String TEST_SERVICE_PKG =
            "com.android.frameworks.coretests.binderproxycountingtestservice";
    private static final String TEST_SERVICE_CMD_SERVICE =
            TEST_SERVICE_PKG + ".BpcTestServiceCmdService";

    private static final int BIND_SERVICE_TIMEOUT_SEC = 5;
    private static final int TOO_MANY_BINDERS_TIMEOUT_SEC = 2;
    private static final int TOO_MANY_BINDERS_WITH_KILL_TIMEOUT_SEC = 30;

    // Keep in sync with BINDER_PROXY_HIGH_WATERMARK in ActivityManagerService.java
    private static final int BINDER_PROXY_LIMIT = 6000;

    private static Context sContext;
    private static UiDevice sUiDevice;

    private static ServiceConnection sTestAppConnection;
    private static ServiceConnection sTestServiceConnection;
    private static IBpcTestAppCmdService sBpcTestAppCmdService;
    private static IBpcTestServiceCmdService sBpcTestServiceCmdService;
    private static final Intent sTestAppIntent = new Intent()
            .setComponent(new ComponentName(TEST_APP_PKG, TEST_APP_CMD_SERVICE));
    private static final Intent sTestServiceIntent = new Intent()
            .setComponent(new ComponentName(TEST_SERVICE_PKG, TEST_SERVICE_CMD_SERVICE));
    private static final Consumer<IBinder> sTestAppConsumer = (service) -> {
        sBpcTestAppCmdService = IBpcTestAppCmdService.Stub.asInterface(service);
    };
    private static final Consumer<IBinder> sTestServiceConsumer = (service) -> {
        sBpcTestServiceCmdService = IBpcTestServiceCmdService.Stub.asInterface(service);
    };
    private static int sTestPkgUid;

    @Rule
    public final RavenwoodRule mRavenwood = new RavenwoodRule();

    /**
     * Setup any common data for the upcoming tests.
     */
    @Before
    public void setUp() throws Exception {
        sContext = InstrumentationRegistry.getContext();
        sTestPkgUid = sContext.getPackageManager().getPackageUid(TEST_APP_PKG, 0);
        ((ActivityManager) sContext.getSystemService(Context.ACTIVITY_SERVICE)).killUid(sTestPkgUid,
                "Wiping Test Package");

        sUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    private ServiceConnection bindService(final Consumer<IBinder> consumer, Intent intent)
            throws Exception {
        final CountDownLatch bindLatch = new CountDownLatch(1);
        ServiceConnection connection = new ServiceConnection() {
            @Override
            public void onServiceConnected(ComponentName name, IBinder service) {
                Log.i(TAG, "Service connected");
                consumer.accept(service);
                bindLatch.countDown();
            }

            @Override
            public void onServiceDisconnected(ComponentName name) {
                Log.i(TAG, "Service disconnected");
            }
        };
        sContext.bindService(intent, connection,
                Context.BIND_AUTO_CREATE
                        | Context.BIND_ALLOW_OOM_MANAGEMENT
                        | Context.BIND_NOT_FOREGROUND);
        if (!bindLatch.await(BIND_SERVICE_TIMEOUT_SEC, TimeUnit.SECONDS)) {
            fail("Timed out waiting for the service to bind in " + sTestPkgUid);
        }
        return connection;
    }


    private void unbindService(ServiceConnection service) {
        if (service != null) {
            sContext.unbindService(service);
        }
    }

    private void bindTestAppToTestService() throws Exception {
        if (sBpcTestAppCmdService != null) {
            String errorMessage = sBpcTestAppCmdService.bindToTestService();
            if (errorMessage != null) {
                fail(errorMessage);
            }
        }
    }

    private void unbindTestAppFromTestService() throws Exception {
        if (sBpcTestAppCmdService != null) {
            sBpcTestAppCmdService.unbindFromTestService();
        }
    }

    private CountDownLatch[] createBinderLimitLatch() throws RemoteException {
        final CountDownLatch[] latches = new CountDownLatch[] {
            new CountDownLatch(1), new CountDownLatch(1)
        };
        sBpcTestServiceCmdService.setBinderProxyCountCallback(
                new IBpcCallbackObserver.Stub() {
                    @Override
                    public void onLimitReached(int uid) {
                        if (uid == sTestPkgUid) {
                            latches[0].countDown();
                        }
                    }
                    @Override
                    public void onWarningThresholdReached(int uid) {
                        if (uid == sTestPkgUid) {
                            latches[1].countDown();
                        }
                    }
                });
        return latches;
    }

    /**
     * Get the Binder Proxy count held by SYSTEM for a given uid
     */
    private int getSystemBinderCount(int uid) throws Exception {
        return Integer.parseInt(sUiDevice.executeShellCommand(
                "dumpsys activity binder-proxies " + uid).trim());
    }

    @Test
    public void testBinderProxyCount() throws Exception {
        // Arbitrary list of Binder create and release
        // Should cumulatively equal 0 and must never add up past the binder limit at any point
        int[] testValues = {223, -103, -13, 25, 90, -222};
        try {
            sTestAppConnection = bindService(sTestAppConsumer, sTestAppIntent);
            // Get the baseline of binders naturally held by the test Package
            int expectedBinderCount = getSystemBinderCount(sTestPkgUid);

            for (int testValue : testValues) {
                if (testValue > 0) {
                    sBpcTestAppCmdService.createSystemBinders(testValue);
                } else {
                    sBpcTestAppCmdService.releaseSystemBinders(-testValue);
                }
                expectedBinderCount += testValue;
                int currentBinderCount = getSystemBinderCount(sTestPkgUid);
                assertEquals("Current Binder Count (" + currentBinderCount
                        + ") does not equal expected Binder Count (" + expectedBinderCount
                        + ")", expectedBinderCount, currentBinderCount);
            }
        } finally {
            unbindService(sTestAppConnection);
        }
    }

    @Test
    public void testBinderProxyLimitBoundary() throws Exception {
        final int binderProxyLimit = 2000;
        final int binderProxyWarning = 1900;
        final int rearmThreshold = 1800;
        try {
            sTestAppConnection = bindService(sTestAppConsumer, sTestAppIntent);
            sTestServiceConnection = bindService(sTestServiceConsumer, sTestServiceIntent);
            bindTestAppToTestService();
            sBpcTestServiceCmdService.enableBinderProxyLimit(true);

            sBpcTestServiceCmdService.forceGc();
            // Get the baseline of binders naturally held by the test Package
            int baseBinderCount = sBpcTestServiceCmdService.getBinderProxyCount(sTestPkgUid);

            final CountDownLatch[] binderLatches = createBinderLimitLatch();
            sBpcTestServiceCmdService.setBinderProxyWatermarks(binderProxyLimit, rearmThreshold,
                    binderProxyWarning);

            // Create Binder Proxies up to the warning;
            sBpcTestAppCmdService.createTestBinders(binderProxyWarning - baseBinderCount);
            if (binderLatches[1].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Received BinderProxyLimitCallback for uid " + sTestPkgUid
                        + " when proxy warning should not have been triggered");
            }

            // Create one more Binder to trigger the warning
            sBpcTestAppCmdService.createTestBinders(1);
            if (!binderLatches[1].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for uid " + sTestPkgUid + " to trigger the warning");
            }

            // Create Binder Proxies up to the limit
            sBpcTestAppCmdService.createTestBinders(binderProxyLimit - binderProxyWarning - 1);
            if (binderLatches[0].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Received BinderProxyLimitCallback for uid " + sTestPkgUid
                        + " when proxy limit should not have been reached");
            }

            // Create one more Binder to cross the limit
            sBpcTestAppCmdService.createTestBinders(1);
            if (!binderLatches[0].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for uid " + sTestPkgUid + " to hit limit");
            }

            sBpcTestAppCmdService.releaseAllBinders();
        } finally {
            unbindTestAppFromTestService();
            unbindService(sTestAppConnection);
            unbindService(sTestServiceConnection);
        }
    }

    @Test
    public void testSetBinderProxyLimit() throws Exception {
        int[] testLimits = {1000, 222, 800};
        try {
            sTestAppConnection = bindService(sTestAppConsumer, sTestAppIntent);
            sTestServiceConnection = bindService(sTestServiceConsumer, sTestServiceIntent);
            bindTestAppToTestService();
            sBpcTestServiceCmdService.enableBinderProxyLimit(true);

            sBpcTestServiceCmdService.forceGc();
            int baseBinderCount = sBpcTestServiceCmdService.getBinderProxyCount(sTestPkgUid);
            for (int testLimit : testLimits) {
                final CountDownLatch[] binderLatches = createBinderLimitLatch();
                // Change the BinderProxyLimit
                sBpcTestServiceCmdService.setBinderProxyWatermarks(testLimit, baseBinderCount + 10,
                        testLimit - 10);

                // Trigger the new Binder Proxy warning
                sBpcTestAppCmdService.createTestBinders(testLimit - 9 - baseBinderCount);
                if (!binderLatches[1].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    fail("Timed out waiting for uid " + sTestPkgUid + " to trigger the warning");
                }

                // Exceed the new Binder Proxy Limit
                sBpcTestAppCmdService.createTestBinders(10);
                if (!binderLatches[0].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                    fail("Timed out waiting for uid " + sTestPkgUid + " to hit limit");
                }

                sBpcTestAppCmdService.releaseTestBinders(testLimit + 1);
                sBpcTestServiceCmdService.forceGc();
            }
        } finally {
            unbindTestAppFromTestService();
            unbindService(sTestAppConnection);
            unbindService(sTestServiceConnection);
        }
    }

    @Test
    public void testRearmCallbackThreshold() throws Exception {
        final int binderProxyLimit = 2000;
        final int exceedBinderProxyLimit = binderProxyLimit + 10;
        final int binderProxyWarning = 1900;
        final int rearmThreshold = 1800;
        try {
            sTestAppConnection = bindService(sTestAppConsumer, sTestAppIntent);
            sTestServiceConnection = bindService(sTestServiceConsumer, sTestServiceIntent);
            bindTestAppToTestService();
            sBpcTestServiceCmdService.enableBinderProxyLimit(true);

            sBpcTestServiceCmdService.forceGc();
            int baseBinderCount = sBpcTestServiceCmdService.getBinderProxyCount(sTestPkgUid);
            final CountDownLatch[] firstBinderLatches = createBinderLimitLatch();
            sBpcTestServiceCmdService.setBinderProxyWatermarks(binderProxyLimit, rearmThreshold,
                    binderProxyWarning);
            // Trigger the Binder Proxy Waring
            sBpcTestAppCmdService.createTestBinders(binderProxyWarning - baseBinderCount + 1);
            if (!firstBinderLatches[1].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for uid " + sTestPkgUid + " to trigger warning");
            }

            // Exceed the Binder Proxy Limit
            sBpcTestAppCmdService.createTestBinders(exceedBinderProxyLimit - binderProxyWarning);
            if (!firstBinderLatches[0].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for uid " + sTestPkgUid + " to hit limit");
            }

            sBpcTestServiceCmdService.forceGc();
            int currentBinderCount = sBpcTestServiceCmdService.getBinderProxyCount(sTestPkgUid);
            // Drop to the threshold, this should not rearm the callback
            sBpcTestAppCmdService.releaseTestBinders(currentBinderCount - rearmThreshold);

            sBpcTestServiceCmdService.forceGc();
            currentBinderCount = sBpcTestServiceCmdService.getBinderProxyCount(sTestPkgUid);

            final CountDownLatch[] secondBinderLatches = createBinderLimitLatch();

            // Exceed the Binder Proxy warning which should not cause a callback since there has
            // been no rearm
            sBpcTestAppCmdService.createTestBinders(binderProxyWarning - currentBinderCount + 1);
            if (secondBinderLatches[1].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Received BinderProxyLimitCallback for uid " + sTestPkgUid
                        + " when the callback has not been rearmed yet");
            }

            // Exceed the Binder Proxy limit which should not cause a callback since there has
            // been no rearm
            sBpcTestAppCmdService.createTestBinders(exceedBinderProxyLimit - binderProxyWarning);
            if (secondBinderLatches[0].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Received BinderProxyLimitCallback for uid " + sTestPkgUid
                        + " when the callback has not been rearmed yet");
            }

            sBpcTestServiceCmdService.forceGc();
            currentBinderCount = sBpcTestServiceCmdService.getBinderProxyCount(sTestPkgUid);
            // Drop below the rearmThreshold to rearm the BinderProxyLimitCallback
            sBpcTestAppCmdService.releaseTestBinders(currentBinderCount - rearmThreshold + 1);

            sBpcTestServiceCmdService.forceGc();
            currentBinderCount = sBpcTestServiceCmdService.getBinderProxyCount(sTestPkgUid);
            // Trigger the Binder Proxy Waring
            sBpcTestAppCmdService.createTestBinders(binderProxyWarning - currentBinderCount + 1);
            if (!secondBinderLatches[1].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for uid " + sTestPkgUid + " to trigger warning");
            }

            // Exceed the Binder Proxy limit for the last time
            sBpcTestAppCmdService.createTestBinders(exceedBinderProxyLimit - currentBinderCount);

            if (!secondBinderLatches[0].await(TOO_MANY_BINDERS_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                fail("Timed out waiting for uid " + sTestPkgUid + " to hit limit");
            }
            sBpcTestAppCmdService.releaseTestBinders(currentBinderCount);
        } finally {
            unbindTestAppFromTestService();
            unbindService(sTestAppConnection);
            unbindService(sTestServiceConnection);
        }
    }

    @Test
    public void testKillBadBehavingApp() throws Exception {
        final CountDownLatch binderDeathLatch = new CountDownLatch(1);
        final int exceedBinderProxyLimit = BINDER_PROXY_LIMIT + 1;

        try {
            sTestAppConnection = bindService(sTestAppConsumer, sTestAppIntent);
            sBpcTestAppCmdService.asBinder().linkToDeath(new IBinder.DeathRecipient() {
                @Override
                public void binderDied() {
                    Log.v(TAG, "BpcTestAppCmdService died!");
                    binderDeathLatch.countDown();
                }
            }, 0);
            try {
                // Exceed the Binder Proxy Limit emulating a bad behaving app
                sBpcTestAppCmdService.createSystemBinders(exceedBinderProxyLimit);
            } catch (DeadObjectException doe) {
                // We are expecting the service to get killed mid call, so a DeadObjectException
                // is not unexpected
            }

            if (!binderDeathLatch.await(TOO_MANY_BINDERS_WITH_KILL_TIMEOUT_SEC, TimeUnit.SECONDS)) {
                sBpcTestAppCmdService.releaseSystemBinders(exceedBinderProxyLimit);
                fail("Timed out waiting for uid " + sTestPkgUid + " to die.");
            }

        } finally {
            unbindService(sTestAppConnection);
        }
    }
}
