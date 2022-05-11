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

package com.android.server.pm;

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_IGNORED;
import static android.app.AppOpsManager.OP_CAMERA;
import static android.app.AppOpsManager.OP_PLAY_AUDIO;
import static android.app.AppOpsManager.OP_RECORD_AUDIO;
import static android.app.AppOpsManager.opToName;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.AppGlobals;
import android.content.Context;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.pm.SuspendDialogInfo;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.FlakyTest;
import androidx.test.filters.LargeTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.servicestests.apps.suspendtestapp.SuspendTestReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@LargeTest
@FlakyTest
public class SuspendPackagesTest {
    private static final String TEST_APP_PACKAGE_NAME = SuspendTestReceiver.PACKAGE_NAME;
    private static final String[] PACKAGES_TO_SUSPEND = new String[]{TEST_APP_PACKAGE_NAME};

    public static final String INSTRUMENTATION_PACKAGE = "com.android.frameworks.servicestests";
    public static final String ACTION_REPORT_MY_PACKAGE_SUSPENDED =
            INSTRUMENTATION_PACKAGE + ".action.REPORT_MY_PACKAGE_SUSPENDED";
    public static final String ACTION_REPORT_MY_PACKAGE_UNSUSPENDED =
            INSTRUMENTATION_PACKAGE + ".action.REPORT_MY_PACKAGE_UNSUSPENDED";
    public static final String ACTION_REPORT_TEST_ACTIVITY_STARTED =
            INSTRUMENTATION_PACKAGE + ".action.REPORT_TEST_ACTIVITY_STARTED";
    public static final String ACTION_REPORT_TEST_ACTIVITY_STOPPED =
            INSTRUMENTATION_PACKAGE + ".action.REPORT_TEST_ACTIVITY_STOPPED";
    public static final String ACTION_REPORT_MORE_DETAILS_ACTIVITY_STARTED =
            INSTRUMENTATION_PACKAGE + ".action.REPORT_MORE_DETAILS_ACTIVITY_STARTED";
    public static final String ACTION_FINISH_TEST_ACTIVITY =
            INSTRUMENTATION_PACKAGE + ".action.FINISH_TEST_ACTIVITY";
    public static final String EXTRA_RECEIVED_PACKAGE_NAME =
            SuspendPackagesTest.INSTRUMENTATION_PACKAGE + ".extra.RECEIVED_PACKAGE_NAME";

    private Context mContext;
    private PackageManager mPackageManager;
    private LauncherApps mLauncherApps;
    private Handler mReceiverHandler;
    private StubbedCallback mTestCallback;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mPackageManager = mContext.getPackageManager();
        mLauncherApps = (LauncherApps) mContext.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        mReceiverHandler = new Handler(Looper.getMainLooper());
        IPackageManager ipm = AppGlobals.getPackageManager();
        try {
            // Otherwise implicit broadcasts will not be delivered.
            ipm.setPackageStoppedState(TEST_APP_PACKAGE_NAME, false, mContext.getUserId());
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        unsuspendTestPackage();
    }

    private PersistableBundle getExtras(String keyPrefix, long lval, String sval, double dval) {
        final PersistableBundle extras = new PersistableBundle(3);
        extras.putLong(keyPrefix + ".LONG_VALUE", lval);
        extras.putDouble(keyPrefix + ".DOUBLE_VALUE", dval);
        extras.putString(keyPrefix + ".STRING_VALUE", sval);
        return extras;
    }

    private void suspendTestPackage(PersistableBundle appExtras, PersistableBundle launcherExtras,
            SuspendDialogInfo dialogInfo) {
        final String[] unchangedPackages = mPackageManager.setPackagesSuspended(
                PACKAGES_TO_SUSPEND, true, appExtras, launcherExtras, dialogInfo);
        assertTrue("setPackagesSuspended returned non-empty list", unchangedPackages.length == 0);
    }

    private void unsuspendTestPackage() {
        final String[] unchangedPackages = mPackageManager.setPackagesSuspended(
                PACKAGES_TO_SUSPEND, false, null, null, (SuspendDialogInfo) null);
        assertTrue("setPackagesSuspended returned non-empty list", unchangedPackages.length == 0);
    }

    private static boolean areSameExtras(BaseBundle expected, BaseBundle received) {
        if (expected != null) {
            expected.get(""); // hack to unparcel the bundles.
        }
        if (received != null) {
            received.get("");
        }
        return BaseBundle.kindofEquals(expected, received);
    }

    private static void assertSameExtras(String message, BaseBundle expected, BaseBundle received) {
        if (!areSameExtras(expected, received)) {
            fail(message + ": [expected: " + expected + "; received: " + received + "]");
        }
    }

    @Test
    public void testGetLauncherExtrasNonNull() {
        final Bundle extrasWhenUnsuspended = mLauncherApps.getSuspendedPackageLauncherExtras(
                TEST_APP_PACKAGE_NAME, mContext.getUser());
        assertNull("Non null extras when package unsuspended:", extrasWhenUnsuspended);
        final PersistableBundle launcherExtras = getExtras("testGetLauncherExtras", 1, "1", 0.1);
        suspendTestPackage(null, launcherExtras, null);
        final Bundle receivedExtras = mLauncherApps.getSuspendedPackageLauncherExtras(
                TEST_APP_PACKAGE_NAME, mContext.getUser());
        assertSameExtras("Received launcher extras different to the ones supplied", launcherExtras,
                receivedExtras);
    }

    @Test
    public void testGetLauncherExtrasNull() {
        suspendTestPackage(null, null, null);
        final Bundle extrasWhenNoneGiven = mLauncherApps.getSuspendedPackageLauncherExtras(
                TEST_APP_PACKAGE_NAME, mContext.getUser());
        assertNull("Non null extras when null extras provided:", extrasWhenNoneGiven);
    }

    @Test
    public void testGetLauncherExtrasInvalidPackage() {
        final Bundle extrasForInvalidPackage = mLauncherApps.getSuspendedPackageLauncherExtras(
                "test.nonexistent.packagename", mContext.getUser());
        assertNull("Non null extras for an invalid package:", extrasForInvalidPackage);
    }

    @Test
    public void testOnPackagesSuspendedNewAndOld() throws InterruptedException {
        final PersistableBundle suppliedExtras = getExtras(
                "testOnPackagesSuspendedNewAndOld", 2, "2", 0.2);
        final AtomicReference<String> error = new AtomicReference<>("");
        final CountDownLatch rightCallbackLatch = new CountDownLatch(1);
        final CountDownLatch wrongCallbackLatch = new CountDownLatch(1);
        mTestCallback = new StubbedCallback() {
            @Override
            public void onPackagesSuspended(String[] packageNames, UserHandle user) {
                error.set(error.get()
                        + "Old callback called even when the new one is overriden. ");
                wrongCallbackLatch.countDown();
            }

            @Override
            public void onPackagesSuspended(String[] packageNames, UserHandle user,
                    Bundle launcherExtras) {
                final StringBuilder errorString = new StringBuilder();
                if (!Arrays.equals(packageNames, PACKAGES_TO_SUSPEND)) {
                    errorString.append("Received unexpected packageNames in onPackagesSuspended:");
                    for (String packageName : packageNames) {
                        errorString.append(" " + packageName);
                    }
                    errorString.append(". ");
                }
                if (user.getIdentifier() != mContext.getUserId()) {
                    errorString.append("Received wrong user " + user.getIdentifier() + ". ");
                }
                if (!areSameExtras(launcherExtras, suppliedExtras)) {
                    errorString.append("Unexpected launcherExtras, supplied: " + suppliedExtras
                            + ", received: " + launcherExtras + ". ");
                }
                error.set(error.get()
                        + errorString.toString());
                rightCallbackLatch.countDown();
            }
        };
        mLauncherApps.registerCallback(mTestCallback, mReceiverHandler);
        suspendTestPackage(null, suppliedExtras, null);
        assertFalse("Wrong callback was invoked", wrongCallbackLatch.await(5, TimeUnit.SECONDS));
        assertTrue("Right callback wasn't invoked", rightCallbackLatch.await(2, TimeUnit.SECONDS));
        final String result = error.get();
        assertTrue("Callbacks did not complete as expected: " + result, result.isEmpty());
    }

    @Test
    public void testOnPackagesSuspendedOld() throws InterruptedException {
        final PersistableBundle suppliedExtras = getExtras(
                "testOnPackagesSuspendedOld", 2, "2", 0.2);
        final AtomicReference<String> overridingOneCallbackResult = new AtomicReference<>("");
        final CountDownLatch oneCallbackLatch = new CountDownLatch(1);
        mTestCallback = new StubbedCallback() {
            @Override
            public void onPackagesSuspended(String[] packageNames, UserHandle user) {
                final StringBuilder errorString = new StringBuilder();
                if (!Arrays.equals(packageNames, PACKAGES_TO_SUSPEND)) {
                    errorString.append("Received unexpected packageNames in onPackagesSuspended:");
                    for (String packageName : packageNames) {
                        errorString.append(" " + packageName);
                    }
                    errorString.append(". ");
                }
                if (user.getIdentifier() != mContext.getUserId()) {
                    errorString.append("Received wrong user " + user.getIdentifier() + ". ");
                }
                overridingOneCallbackResult.set(overridingOneCallbackResult.get()
                        + errorString.toString());
                oneCallbackLatch.countDown();
            }
        };
        mLauncherApps.registerCallback(mTestCallback, mReceiverHandler);
        suspendTestPackage(null, suppliedExtras, null);
        assertTrue("Callback not invoked", oneCallbackLatch.await(5, TimeUnit.SECONDS));
        final String result = overridingOneCallbackResult.get();
        assertTrue("Callback did not complete as expected: " + result, result.isEmpty());
    }

    @Test
    public void testCameraBlockedOnSuspend() throws Exception {
        assertOpBlockedOnSuspend(OP_CAMERA);
    }

    @Test
    public void testPlayAudioBlockedOnSuspend() throws Exception {
        assertOpBlockedOnSuspend(OP_PLAY_AUDIO);
    }

    @Test
    public void testRecordAudioBlockedOnSuspend() throws Exception {
        assertOpBlockedOnSuspend(OP_RECORD_AUDIO);
    }

    private void assertOpBlockedOnSuspend(int code) throws Exception {
        final IAppOpsService iAppOps = IAppOpsService.Stub.asInterface(
                ServiceManager.getService(Context.APP_OPS_SERVICE));
        final CountDownLatch latch = new CountDownLatch(1);
        final IAppOpsCallback watcher = new IAppOpsCallback.Stub() {
            @Override
            public void opChanged(int op, int uid, String packageName) {
                if (op == code && packageName.equals(TEST_APP_PACKAGE_NAME)) {
                    latch.countDown();
                }
            }
        };
        iAppOps.startWatchingMode(code, TEST_APP_PACKAGE_NAME, watcher);
        final int testPackageUid = mPackageManager.getPackageUid(TEST_APP_PACKAGE_NAME, 0);
        int opMode = iAppOps.checkOperation(code, testPackageUid, TEST_APP_PACKAGE_NAME);
        assertEquals("Op " + opToName(code) + " disallowed for unsuspended package", MODE_ALLOWED,
                opMode);
        suspendTestPackage(null, null, null);
        assertTrue("AppOpsWatcher did not callback", latch.await(5, TimeUnit.SECONDS));
        opMode = iAppOps.checkOperation(code, testPackageUid, TEST_APP_PACKAGE_NAME);
        assertEquals("Op " + opToName(code) + " allowed for suspended package", MODE_IGNORED,
                opMode);
        iAppOps.stopWatchingMode(watcher);
    }

    @After
    public void tearDown() throws IOException {
        if (mTestCallback != null) {
            mLauncherApps.unregisterCallback(mTestCallback);
        }
    }

    private static abstract class StubbedCallback extends LauncherApps.Callback {

        @Override
        public void onPackageRemoved(String packageName, UserHandle user) {
        }

        @Override
        public void onPackageAdded(String packageName, UserHandle user) {
        }

        @Override
        public void onPackageChanged(String packageName, UserHandle user) {
        }

        @Override
        public void onPackagesAvailable(String[] packageNames, UserHandle user, boolean replacing) {

        }

        @Override
        public void onPackagesUnavailable(String[] packageNames, UserHandle user,
                boolean replacing) {
        }
    }
}
