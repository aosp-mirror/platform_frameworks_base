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
package com.android.server.appop;

import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE;
import static android.app.ActivityManager.PROCESS_STATE_FOREGROUND_SERVICE_LOCATION;
import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.MODE_FOREGROUND;
import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_READ_SMS;
import static android.app.AppOpsManager.OP_WIFI_SCAN;
import static android.app.AppOpsManager.OP_WRITE_SMS;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;

import android.app.ActivityManager;
import android.app.AppOpsManager;
import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.PackageOps;
import android.content.Context;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.RemoteCallback;
import android.provider.Settings;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.LocalServices;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unit tests for AppOpsService. Covers functionality that is difficult to test using CTS tests
 * or for which we can write more detailed unit tests than CTS tests (because the internal APIs are
 * more finegrained data than the public ones).
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class AppOpsServiceTest {

    private static final String TAG = AppOpsServiceTest.class.getSimpleName();
    // State will be persisted into this XML file.
    private static final String APP_OPS_FILENAME = "appops-service-test.xml";

    private static final Context sContext = InstrumentationRegistry.getTargetContext();
    private static final String sMyPackageName = sContext.getOpPackageName();

    private File mAppOpsFile;
    private Handler mHandler;
    private AppOpsService mAppOpsService;
    private int mMyUid;
    private long mTestStartMillis;
    private StaticMockitoSession mMockingSession;

    @Before
    public void mockPackageManagerInternalGetApplicationInfo() {
        mMockingSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(LocalServices.class)
                .startMocking();

        // Mock LocalServices.getService(PackageManagerInternal.class).getApplicationInfo dependency
        // needed by AppOpsService
        PackageManagerInternal mockPackageManagerInternal = mock(PackageManagerInternal.class);
        when(mockPackageManagerInternal.getApplicationInfo(eq(sMyPackageName), anyInt(), anyInt(),
                anyInt())).thenReturn(sContext.getApplicationInfo());
        doReturn(mockPackageManagerInternal).when(
                () -> LocalServices.getService(PackageManagerInternal.class));
    }

    private void setupAppOpsService() {
        mAppOpsService = new AppOpsService(mAppOpsFile, mHandler);
        mAppOpsService.mContext = spy(sContext);

        // Always approve all permission checks
        doNothing().when(mAppOpsService.mContext).enforcePermission(anyString(), anyInt(),
                anyInt(), nullable(String.class));
    }

    private static String sDefaultAppopHistoryParameters;

    @Before
    public void setUp() {
        mAppOpsFile = new File(sContext.getFilesDir(), APP_OPS_FILENAME);
        if (mAppOpsFile.exists()) {
            // Start with a clean state (persisted into XML).
            mAppOpsFile.delete();
        }

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mMyUid = Process.myUid();

        setupAppOpsService();
        mAppOpsService.mHistoricalRegistry.systemReady(sContext.getContentResolver());
        mTestStartMillis = System.currentTimeMillis();
    }

    @BeforeClass
    public static void configureDesiredAppopHistoryParameters() {
        final Context context = InstrumentationRegistry.getTargetContext();
        sDefaultAppopHistoryParameters = Settings.Global.getString(context.getContentResolver(),
                Settings.Global.APPOP_HISTORY_PARAMETERS);
        Settings.Global.putString(InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.APPOP_HISTORY_PARAMETERS, null);
    }

    @AfterClass
    public static void restoreDefaultAppopHistoryParameters() {
        Settings.Global.putString(InstrumentationRegistry.getTargetContext().getContentResolver(),
                Settings.Global.APPOP_HISTORY_PARAMETERS,
                sDefaultAppopHistoryParameters);
    }

    @After
    public void resetStaticMocks() {
        mMockingSession.finishMocking();
    }

    @Test
    public void testGetOpsForPackage_noOpsLogged() {
        assertThat(getLoggedOps()).isNull();
    }

    @Test
    public void testNoteOperationAndGetOpsForPackage() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.setMode(OP_WRITE_SMS, mMyUid, sMyPackageName, MODE_ERRORED);

        // Note an op that's allowed.
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName);
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        // Note another op that's not allowed.
        mAppOpsService.noteOperation(OP_WRITE_SMS, mMyUid, sMyPackageName);
        loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);
        assertContainsOp(loggedOps, OP_WRITE_SMS, -1, mTestStartMillis, MODE_ERRORED);
    }

    /**
     * Tests the scenario where an operation's permission is controlled by another operation.
     * For example the results of a WIFI_SCAN can be used to infer the location of a user, so the
     * ACCESS_COARSE_LOCATION op is used to check whether WIFI_SCAN is allowed.
     */
    @Test
    public void testNoteOperationAndGetOpsForPackage_controlledByDifferentOp() {
        // This op controls WIFI_SCAN
        mAppOpsService.setMode(OP_COARSE_LOCATION, mMyUid, sMyPackageName, MODE_ALLOWED);

        assertThat(mAppOpsService.noteOperation(OP_WIFI_SCAN, mMyUid, sMyPackageName))
                .isEqualTo(MODE_ALLOWED);

        assertContainsOp(getLoggedOps(), OP_WIFI_SCAN, mTestStartMillis, -1,
                MODE_ALLOWED /* default for WIFI_SCAN; this is not changed or used in this test */);

        // Now set COARSE_LOCATION to ERRORED -> this will make WIFI_SCAN disabled as well.
        mAppOpsService.setMode(OP_COARSE_LOCATION, mMyUid, sMyPackageName, MODE_ERRORED);
        assertThat(mAppOpsService.noteOperation(OP_WIFI_SCAN, mMyUid, sMyPackageName))
                .isEqualTo(MODE_ERRORED);

        assertContainsOp(getLoggedOps(), OP_WIFI_SCAN, mTestStartMillis, mTestStartMillis,
                MODE_ALLOWED /* default for WIFI_SCAN; this is not changed or used in this test */);
    }

    // Tests the dumping and restoring of the in-memory state to/from XML.
    @Test
    public void testStatePersistence() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.setMode(OP_WRITE_SMS, mMyUid, sMyPackageName, MODE_ERRORED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName);
        mAppOpsService.noteOperation(OP_WRITE_SMS, mMyUid, sMyPackageName);
        mAppOpsService.writeState();

        // Create a new app ops service, and initialize its state from XML.
        setupAppOpsService();
        mAppOpsService.readState();

        // Query the state of the 2nd service.
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);
        assertContainsOp(loggedOps, OP_WRITE_SMS, -1, mTestStartMillis, MODE_ERRORED);
    }

    // Tests that ops are persisted during shutdown.
    @Test
    public void testShutdown() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName);
        mAppOpsService.shutdown();

        // Create a new app ops service, and initialize its state from XML.
        setupAppOpsService();
        mAppOpsService.readState();

        // Query the state of the 2nd service.
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);
    }

    @Test
    public void testGetOpsForPackage() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName);

        // Query all ops
        List<PackageOps> loggedOps = mAppOpsService.getOpsForPackage(
                mMyUid, sMyPackageName, null /* all ops */);
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        // Query specific ops
        loggedOps = mAppOpsService.getOpsForPackage(
                mMyUid, sMyPackageName, new int[]{OP_READ_SMS, OP_WRITE_SMS});
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        // Query unknown UID
        loggedOps = mAppOpsService.getOpsForPackage(mMyUid + 1, sMyPackageName, null /* all ops */);
        assertThat(loggedOps).isNull();

        // Query unknown package name
        loggedOps = mAppOpsService.getOpsForPackage(mMyUid, "fake.package", null /* all ops */);
        assertThat(loggedOps).isNull();

        // Query op code that's not been logged
        loggedOps = mAppOpsService.getOpsForPackage(mMyUid, sMyPackageName,
                new int[]{OP_WRITE_SMS});
        assertThat(loggedOps).isNull();
    }

    @Test
    public void testPackageRemoved() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName);

        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        mAppOpsService.packageRemoved(mMyUid, sMyPackageName);
        assertThat(getLoggedOps()).isNull();
    }

    @Ignore("Historical appops are disabled in Android Q")
    @Test
    public void testPackageRemovedHistoricalOps() throws InterruptedException {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName);

        AppOpsManager.HistoricalOps historicalOps = new AppOpsManager.HistoricalOps(0, 15000);
        historicalOps.increaseAccessCount(OP_READ_SMS, mMyUid, sMyPackageName,
                AppOpsManager.UID_STATE_PERSISTENT, 0, 1);

        mAppOpsService.addHistoricalOps(historicalOps);

        AtomicReference<AppOpsManager.HistoricalOps> resultOpsRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(1));
        RemoteCallback callback = new RemoteCallback(result -> {
            resultOpsRef.set(result.getParcelable(AppOpsManager.KEY_HISTORICAL_OPS));
            latchRef.get().countDown();
        });

        // First, do a fetch to ensure it's written
        mAppOpsService.getHistoricalOps(mMyUid, sMyPackageName, null, 0, Long.MAX_VALUE, 0,
                callback);

        latchRef.get().await(5, TimeUnit.SECONDS);
        assertThat(latchRef.get().getCount()).isEqualTo(0);
        assertThat(resultOpsRef.get().isEmpty()).isFalse();

        // Then, check it's deleted on removal
        mAppOpsService.packageRemoved(mMyUid, sMyPackageName);

        latchRef.set(new CountDownLatch(1));

        mAppOpsService.getHistoricalOps(mMyUid, sMyPackageName, null, 0, Long.MAX_VALUE, 0,
                callback);

        latchRef.get().await(5, TimeUnit.SECONDS);
        assertThat(latchRef.get().getCount()).isEqualTo(0);
        assertThat(resultOpsRef.get().isEmpty()).isTrue();
    }

    @Test
    public void testUidRemoved() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName);

        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        mAppOpsService.uidRemoved(mMyUid);
        assertThat(getLoggedOps()).isNull();
    }

    private void setupProcStateTests() {
        // For the location proc state tests
        mAppOpsService.setMode(OP_COARSE_LOCATION, mMyUid, sMyPackageName, MODE_FOREGROUND);
        mAppOpsService.mConstants.FG_SERVICE_STATE_SETTLE_TIME = 0;
        mAppOpsService.mConstants.TOP_STATE_SETTLE_TIME = 0;
        mAppOpsService.mConstants.BG_STATE_SETTLE_TIME = 0;
    }

    @Test
    public void testUidProcStateChange_cachedToTopToCached() throws Exception {
        setupProcStateTests();

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_TOP);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        // Second time to make sure that settle time is overcome
        Thread.sleep(50);
        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);
    }

    @Test
    public void testUidProcStateChange_cachedToFgs() throws Exception {
        setupProcStateTests();

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, PROCESS_STATE_FOREGROUND_SERVICE);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);
    }

    @Test
    public void testUidProcStateChange_cachedToFgsLocation() throws Exception {
        setupProcStateTests();

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid,
                PROCESS_STATE_FOREGROUND_SERVICE_LOCATION);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isEqualTo(MODE_ALLOWED);
    }

    @Test
    public void testUidProcStateChange_topToFgs() throws Exception {
        setupProcStateTests();

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_TOP);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, PROCESS_STATE_FOREGROUND_SERVICE);
        // Second time to make sure that settle time is overcome
        Thread.sleep(50);
        mAppOpsService.updateUidProcState(mMyUid, PROCESS_STATE_FOREGROUND_SERVICE);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);
    }

    @Test
    public void testUidProcStateChange_topToFgsLocationToFgs() throws Exception {
        setupProcStateTests();

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_CACHED_EMPTY);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, ActivityManager.PROCESS_STATE_TOP);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, PROCESS_STATE_FOREGROUND_SERVICE_LOCATION);
        // Second time to make sure that settle time is overcome
        Thread.sleep(50);
        mAppOpsService.updateUidProcState(mMyUid, PROCESS_STATE_FOREGROUND_SERVICE_LOCATION);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isEqualTo(MODE_ALLOWED);

        mAppOpsService.updateUidProcState(mMyUid, PROCESS_STATE_FOREGROUND_SERVICE);
        // Second time to make sure that settle time is overcome
        Thread.sleep(50);
        mAppOpsService.updateUidProcState(mMyUid, PROCESS_STATE_FOREGROUND_SERVICE);
        assertThat(mAppOpsService.noteOperation(OP_COARSE_LOCATION, mMyUid, sMyPackageName))
                .isNotEqualTo(MODE_ALLOWED);
    }

    private List<PackageOps> getLoggedOps() {
        return mAppOpsService.getOpsForPackage(mMyUid, sMyPackageName, null /* all ops */);
    }

    private void assertContainsOp(List<PackageOps> loggedOps, int opCode, long minMillis,
            long minRejectMillis, int mode) {

        boolean opLogged = false;
        for (PackageOps pkgOps : loggedOps) {
            assertWithMessage("Unexpected UID").that(mMyUid).isEqualTo(pkgOps.getUid());
            assertWithMessage("Unexpected package name").that(sMyPackageName).isEqualTo(
                    pkgOps.getPackageName());

            for (OpEntry opEntry : pkgOps.getOps()) {
                if (opCode != opEntry.getOp()) {
                    continue;
                }
                opLogged = true;

                assertWithMessage("Unexpected mode").that(mode).isEqualTo(opEntry.getMode());
                if (minMillis > 0) {
                    assertWithMessage("Unexpected timestamp")
                            .that(opEntry.getTime()).isAtLeast(minMillis);
                }
                if (minRejectMillis > 0) {
                    assertWithMessage("Unexpected rejection timestamp")
                            .that(opEntry.getRejectTime()).isAtLeast(minRejectMillis);
                }
            }
        }
        assertWithMessage("Op was not logged").that(opLogged).isTrue();
    }
}
