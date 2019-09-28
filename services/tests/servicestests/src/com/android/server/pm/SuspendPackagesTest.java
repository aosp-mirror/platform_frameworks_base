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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.app.AppGlobals;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.IPackageManager;
import android.content.pm.LauncherApps;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.os.BaseBundle;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.LargeTest;
import android.support.test.runner.AndroidJUnit4;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.Log;
import android.view.IWindowManager;
import android.view.WindowManagerGlobal;

import com.android.internal.app.IAppOpsCallback;
import com.android.internal.app.IAppOpsService;
import com.android.servicestests.apps.suspendtestapp.SuspendTestActivity;
import com.android.servicestests.apps.suspendtestapp.SuspendTestReceiver;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(AndroidJUnit4.class)
@LargeTest
public class SuspendPackagesTest {
    private static final String TAG = SuspendPackagesTest.class.getSimpleName();
    private static final String TEST_APP_LABEL = "Suspend Test App";
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
    private AppCommunicationReceiver mAppCommsReceiver;
    private StubbedCallback mTestCallback;
    private UiDevice mUiDevice;
    private ComponentName mDeviceAdminComponent;
    private boolean mPoSet;
    private boolean mDoSet;

    private static final class AppCommunicationReceiver extends BroadcastReceiver {
        private Context context;
        private boolean registered;
        private SynchronousQueue<Intent> intentQueue = new SynchronousQueue<>();

        AppCommunicationReceiver(Context context) {
            this.context = context;
        }

        void register(Handler handler, String... actions) {
            registered = true;
            final IntentFilter intentFilter = new IntentFilter();
            for (String action : actions) {
                intentFilter.addAction(action);
            }
            context.registerReceiver(this, intentFilter, null, handler);
        }

        void unregister() {
            if (registered) {
                context.unregisterReceiver(this);
            }
        }

        @Override
        public void onReceive(Context context, Intent intent) {
            Log.d(TAG, "AppCommunicationReceiver#onReceive: " + intent.getAction());
            try {
                intentQueue.offer(intent, 5, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                throw new RuntimeException("Receiver thread interrupted", ie);
            }
        }

        Intent pollForIntent(long secondsToWait) {
            if (!registered) {
                throw new IllegalStateException("Receiver not registered");
            }
            final Intent intent;
            try {
                intent = intentQueue.poll(secondsToWait, TimeUnit.SECONDS);
            } catch (InterruptedException ie) {
                throw new RuntimeException("Interrupted while waiting for app broadcast", ie);
            }
            return intent;
        }

        void drainPendingBroadcasts() {
            while (pollForIntent(5) != null);
        }

        Intent receiveIntentFromApp() {
            final Intent intentReceived = pollForIntent(5);
            assertNotNull("No intent received from app within 5 seconds", intentReceived);
            return intentReceived;
        }
    }

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mPackageManager = mContext.getPackageManager();
        mLauncherApps = (LauncherApps) mContext.getSystemService(Context.LAUNCHER_APPS_SERVICE);
        mReceiverHandler = new Handler(Looper.getMainLooper());
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDeviceAdminComponent = new ComponentName(mContext,
                "com.android.server.devicepolicy.DummyDeviceAdmins$Admin1");
        IPackageManager ipm = AppGlobals.getPackageManager();
        try {
            // Otherwise implicit broadcasts will not be delivered.
            ipm.setPackageStoppedState(TEST_APP_PACKAGE_NAME, false, mContext.getUserId());
        } catch (RemoteException e) {
            e.rethrowAsRuntimeException();
        }
        unsuspendTestPackage();
        mAppCommsReceiver = new AppCommunicationReceiver(mContext);
    }

    /**
     * Care should be taken when used with {@link #mAppCommsReceiver} in the same test as both use
     * the same handler.
     */
    private Bundle requestAppAction(String action) throws InterruptedException {
        final AtomicReference<Bundle> result = new AtomicReference<>();
        final CountDownLatch receiverLatch = new CountDownLatch(1);
        final ComponentName testReceiverComponent = new ComponentName(TEST_APP_PACKAGE_NAME,
                SuspendTestReceiver.class.getCanonicalName());
        final Intent broadcastIntent = new Intent(action)
                .setComponent(testReceiverComponent)
                .setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        mContext.sendOrderedBroadcast(broadcastIntent, null, new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                result.set(getResultExtras(true));
                receiverLatch.countDown();
            }
        }, mReceiverHandler, 0, null, null);

        assertTrue("Test receiver timed out ", receiverLatch.await(5, TimeUnit.SECONDS));
        return result.get();
    }

    private PersistableBundle getExtras(String keyPrefix, long lval, String sval, double dval) {
        final PersistableBundle extras = new PersistableBundle(3);
        extras.putLong(keyPrefix + ".LONG_VALUE", lval);
        extras.putDouble(keyPrefix + ".DOUBLE_VALUE", dval);
        extras.putString(keyPrefix + ".STRING_VALUE", sval);
        return extras;
    }

    private void suspendTestPackage(PersistableBundle appExtras, PersistableBundle launcherExtras,
            String dialogMessage) {
        final String[] unchangedPackages = mPackageManager.setPackagesSuspended(
                PACKAGES_TO_SUSPEND, true, appExtras, launcherExtras, dialogMessage);
        assertTrue("setPackagesSuspended returned non-empty list", unchangedPackages.length == 0);
    }

    private void unsuspendTestPackage() {
        final String[] unchangedPackages = mPackageManager.setPackagesSuspended(
                PACKAGES_TO_SUSPEND, false, null, null, null);
        assertTrue("setPackagesSuspended returned non-empty list", unchangedPackages.length == 0);
    }

    private void startTestAppActivity() {
        final Intent testActivity = new Intent()
                .setComponent(new ComponentName(TEST_APP_PACKAGE_NAME,
                        SuspendTestActivity.class.getCanonicalName()))
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        mContext.startActivity(testActivity);
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
    public void testIsPackageSuspended() throws Exception {
        suspendTestPackage(null, null, null);
        assertTrue("isPackageSuspended is false",
                mPackageManager.isPackageSuspended(TEST_APP_PACKAGE_NAME));
    }

    @Test
    public void testSuspendedStateFromApp() throws Exception {
        Bundle resultFromApp = requestAppAction(SuspendTestReceiver.ACTION_GET_SUSPENDED_STATE);
        assertFalse(resultFromApp.getBoolean(SuspendTestReceiver.EXTRA_SUSPENDED, true));
        assertNull(resultFromApp.getBundle(SuspendTestReceiver.EXTRA_SUSPENDED_APP_EXTRAS));

        final PersistableBundle appExtras = getExtras("testSuspendedStateFromApp", 20, "20", 0.2);
        suspendTestPackage(appExtras, null, null);

        resultFromApp = requestAppAction(SuspendTestReceiver.ACTION_GET_SUSPENDED_STATE);
        assertTrue("resultFromApp:suspended is false",
                resultFromApp.getBoolean(SuspendTestReceiver.EXTRA_SUSPENDED));
        final Bundle receivedAppExtras =
                resultFromApp.getBundle(SuspendTestReceiver.EXTRA_SUSPENDED_APP_EXTRAS);
        assertSameExtras("Received app extras different to the ones supplied",
                appExtras, receivedAppExtras);
    }

    @Test
    public void testMyPackageSuspendedUnsuspended() {
        mAppCommsReceiver.register(mReceiverHandler, ACTION_REPORT_MY_PACKAGE_SUSPENDED,
                ACTION_REPORT_MY_PACKAGE_UNSUSPENDED);
        mAppCommsReceiver.drainPendingBroadcasts();
        final PersistableBundle appExtras = getExtras("testMyPackageSuspendBroadcasts", 1, "1", .1);
        suspendTestPackage(appExtras, null, null);
        Intent intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals("MY_PACKAGE_SUSPENDED delivery not reported",
                ACTION_REPORT_MY_PACKAGE_SUSPENDED, intentFromApp.getAction());
        assertSameExtras("Received app extras different to the ones supplied", appExtras,
                intentFromApp.getBundleExtra(SuspendTestReceiver.EXTRA_SUSPENDED_APP_EXTRAS));
        unsuspendTestPackage();
        intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals("MY_PACKAGE_UNSUSPENDED delivery not reported",
                ACTION_REPORT_MY_PACKAGE_UNSUSPENDED, intentFromApp.getAction());
    }

    @Test
    public void testUpdatingAppExtras() {
        mAppCommsReceiver.register(mReceiverHandler, ACTION_REPORT_MY_PACKAGE_SUSPENDED);
        final PersistableBundle extras1 = getExtras("testMyPackageSuspendedOnChangingExtras", 1,
                "1", 0.1);
        suspendTestPackage(extras1, null, null);
        Intent intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals("MY_PACKAGE_SUSPENDED delivery not reported",
                ACTION_REPORT_MY_PACKAGE_SUSPENDED, intentFromApp.getAction());
        assertSameExtras("Received app extras different to the ones supplied", extras1,
                intentFromApp.getBundleExtra(SuspendTestReceiver.EXTRA_SUSPENDED_APP_EXTRAS));
        final PersistableBundle extras2 = getExtras("testMyPackageSuspendedOnChangingExtras", 2,
                "2", 0.2);
        suspendTestPackage(extras2, null, null);
        intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals("MY_PACKAGE_SUSPENDED delivery not reported",
                ACTION_REPORT_MY_PACKAGE_SUSPENDED, intentFromApp.getAction());
        assertSameExtras("Received app extras different to the updated extras", extras2,
                intentFromApp.getBundleExtra(SuspendTestReceiver.EXTRA_SUSPENDED_APP_EXTRAS));
    }

    @Test
    public void testCannotSuspendSelf() {
        final String[] unchangedPkgs = mPackageManager.setPackagesSuspended(
                new String[]{mContext.getOpPackageName()}, true, null, null, null);
        assertTrue(unchangedPkgs.length == 1);
        assertEquals(mContext.getOpPackageName(), unchangedPkgs[0]);
    }

    @Test
    public void testActivityStoppedOnSuspend() {
        mAppCommsReceiver.register(mReceiverHandler, ACTION_REPORT_TEST_ACTIVITY_STARTED,
                ACTION_REPORT_TEST_ACTIVITY_STOPPED);
        startTestAppActivity();
        Intent intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals("Test activity start not reported",
                ACTION_REPORT_TEST_ACTIVITY_STARTED, intentFromApp.getAction());
        suspendTestPackage(null, null, null);
        intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals("Test activity stop not reported on suspending the test app",
                ACTION_REPORT_TEST_ACTIVITY_STOPPED, intentFromApp.getAction());
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
        final AtomicReference<String> overridingBothCallbackResult = new AtomicReference<>("");
        final CountDownLatch twoCallbackLatch = new CountDownLatch(2);
        mTestCallback = new StubbedCallback() {
            @Override
            public void onPackagesSuspended(String[] packageNames, UserHandle user) {
                overridingBothCallbackResult.set(overridingBothCallbackResult.get()
                        + "Old callback called even when the new one is overriden. ");
                twoCallbackLatch.countDown();
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
                overridingBothCallbackResult.set(overridingBothCallbackResult.get()
                        + errorString.toString());
                twoCallbackLatch.countDown();
            }
        };
        mLauncherApps.registerCallback(mTestCallback, mReceiverHandler);
        suspendTestPackage(null, suppliedExtras, null);
        assertFalse("Both callbacks were invoked", twoCallbackLatch.await(5, TimeUnit.SECONDS));
        twoCallbackLatch.countDown();
        assertTrue("No callback was invoked", twoCallbackLatch.await(2, TimeUnit.SECONDS));
        final String result = overridingBothCallbackResult.get();
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

    private void turnScreenOn() throws Exception {
        if (!mUiDevice.isScreenOn()) {
            mUiDevice.wakeUp();
        }
        final IWindowManager wm = WindowManagerGlobal.getWindowManagerService();
        wm.dismissKeyguard(null, null);
    }

    @Test
    public void testInterceptorActivity() throws Exception {
        turnScreenOn();
        mAppCommsReceiver.register(mReceiverHandler, ACTION_REPORT_MORE_DETAILS_ACTIVITY_STARTED,
                ACTION_REPORT_TEST_ACTIVITY_STARTED);
        final String testMessage = "This is a test message to report suspension of %1$s";
        suspendTestPackage(null, null, testMessage);
        startTestAppActivity();
        assertNull("No broadcast was expected from app", mAppCommsReceiver.pollForIntent(2));
        assertNotNull("Given dialog message not shown", mUiDevice.wait(
                Until.findObject(By.text(String.format(testMessage, TEST_APP_LABEL))), 5000));
        final String buttonText = mContext.getResources().getString(Resources.getSystem()
                .getIdentifier("app_suspended_more_details", "string", "android"));
        final UiObject2 moreDetailsButton = mUiDevice.findObject(
                By.clickable(true).text(buttonText));
        assertNotNull(buttonText + " button not shown", moreDetailsButton);
        moreDetailsButton.click();
        final Intent intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals(buttonText + " activity start not reported",
                ACTION_REPORT_MORE_DETAILS_ACTIVITY_STARTED, intentFromApp.getAction());
        final String receivedPackageName = intentFromApp.getStringExtra(
                EXTRA_RECEIVED_PACKAGE_NAME);
        assertEquals("Wrong package name received by " + buttonText + " activity",
                TEST_APP_PACKAGE_NAME, receivedPackageName);
    }

    private boolean setProfileOwner() throws IOException {
        final String result = mUiDevice.executeShellCommand("dpm set-profile-owner --user cur "
                + mDeviceAdminComponent.flattenToString());
        return mPoSet = result.trim().startsWith("Success");
    }

    private boolean setDeviceOwner() throws IOException {
        final String result = mUiDevice.executeShellCommand("dpm set-device-owner --user cur "
                + mDeviceAdminComponent.flattenToString());
        return mDoSet = result.trim().startsWith("Success");
    }

    private void removeProfileOrDeviceOwner() throws IOException {
        if (mPoSet || mDoSet) {
            mUiDevice.executeShellCommand("dpm remove-active-admin --user cur "
                    + mDeviceAdminComponent.flattenToString());
            mPoSet = mDoSet = false;
        }
    }

    @Test
    public void testCannotSuspendWhenProfileOwner() throws IOException {
        assumeTrue(mPackageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN));
        assertTrue("Profile-owner could not be set", setProfileOwner());
        try {
            suspendTestPackage(null, null, null);
            fail("Suspend succeeded. Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException uex) {
        }
    }

    @Test
    public void testCannotSuspendWhenDeviceOwner() throws IOException {
        assumeTrue(mPackageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN));
        assertTrue("Device-owner could not be set", setDeviceOwner());
        try {
            suspendTestPackage(null, null, null);
            fail("Suspend succeeded. Expected UnsupportedOperationException");
        } catch (UnsupportedOperationException uex) {
        }
    }

    @Test
    public void testPackageUnsuspendedOnAddingDeviceOwner() throws IOException {
        assumeTrue(mPackageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN));
        mAppCommsReceiver.register(mReceiverHandler, ACTION_REPORT_MY_PACKAGE_UNSUSPENDED,
                ACTION_REPORT_MY_PACKAGE_SUSPENDED);
        mAppCommsReceiver.drainPendingBroadcasts();
        suspendTestPackage(null, null, null);
        Intent intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals(ACTION_REPORT_MY_PACKAGE_SUSPENDED, intentFromApp.getAction());
        assertTrue("Device-owner could not be set", setDeviceOwner());
        intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals(ACTION_REPORT_MY_PACKAGE_UNSUSPENDED, intentFromApp.getAction());
    }

    @Test
    public void testPackageUnsuspendedOnAddingProfileOwner() throws IOException {
        assumeTrue(mPackageManager.hasSystemFeature(PackageManager.FEATURE_DEVICE_ADMIN));
        mAppCommsReceiver.register(mReceiverHandler, ACTION_REPORT_MY_PACKAGE_UNSUSPENDED,
                ACTION_REPORT_MY_PACKAGE_SUSPENDED);
        mAppCommsReceiver.drainPendingBroadcasts();
        suspendTestPackage(null, null, null);
        Intent intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals(ACTION_REPORT_MY_PACKAGE_SUSPENDED, intentFromApp.getAction());
        assertTrue("Profile-owner could not be set", setProfileOwner());
        intentFromApp = mAppCommsReceiver.receiveIntentFromApp();
        assertEquals(ACTION_REPORT_MY_PACKAGE_UNSUSPENDED, intentFromApp.getAction());
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
        mAppCommsReceiver.unregister();
        if (mTestCallback != null) {
            mLauncherApps.unregisterCallback(mTestCallback);
        }
        removeProfileOrDeviceOwner();
        mContext.sendBroadcast(new Intent(ACTION_FINISH_TEST_ACTIVITY)
                .setPackage(TEST_APP_PACKAGE_NAME));
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
