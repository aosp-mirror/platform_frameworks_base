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

import static android.app.AppOpsManager.MODE_ALLOWED;
import static android.app.AppOpsManager.MODE_ERRORED;
import static android.app.AppOpsManager.OP_COARSE_LOCATION;
import static android.app.AppOpsManager.OP_FLAGS_ALL;
import static android.app.AppOpsManager.OP_FLAG_SELF;
import static android.app.AppOpsManager.OP_READ_DEVICE_IDENTIFIERS;
import static android.app.AppOpsManager.OP_READ_SMS;
import static android.app.AppOpsManager.OP_TAKE_AUDIO_FOCUS;
import static android.app.AppOpsManager.OP_WIFI_SCAN;
import static android.app.AppOpsManager.OP_WRITE_SMS;
import static android.os.UserHandle.getAppId;
import static android.os.UserHandle.getUserId;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;

import android.app.AppOpsManager;
import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.PackageOps;
import android.app.SyncNotedAppOp;
import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageManagerInternal;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.permission.PermissionManager;
import android.provider.Settings;
import android.util.ArrayMap;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;
import com.android.server.LocalManagerRegistry;
import com.android.server.LocalServices;
import com.android.server.pm.PackageManagerLocal;
import com.android.server.pm.UserManagerInternal;
import com.android.server.pm.pkg.AndroidPackage;
import com.android.server.pm.pkg.PackageState;
import com.android.server.pm.pkg.PackageStateInternal;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.quality.Strictness;

import java.io.File;
import java.util.List;
import java.util.Map;

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
    private static final String APP_OPS_FILENAME = "appops.test.xml";
    private static final String APP_OPS_ACCESSES_FILENAME = "appops_accesses.test.xml";

    private static final Context sContext = InstrumentationRegistry.getTargetContext();
    private static final String sMyPackageName = sContext.getOpPackageName();
    private static final String sSdkSandboxPackageName = sContext.getPackageManager()
            .getSdkSandboxPackageName();

    private File mStorageFile;
    private File mRecentAccessesFile;
    private Handler mHandler;
    private AppOpsService mAppOpsService;
    private int mMyUid;
    private int mSdkSandboxPackageUid;
    private long mTestStartMillis;
    private StaticMockitoSession mMockingSession;

    private void setupAppOpsService() {
        mAppOpsService = new AppOpsService(mRecentAccessesFile, mStorageFile, mHandler,
                spy(sContext));
        mAppOpsService.mHistoricalRegistry.systemReady(sContext.getContentResolver());
        mAppOpsService.prepareInternalCallbacks();

        // Always approve all permission checks
        doNothing().when(mAppOpsService.mContext).enforcePermission(anyString(), anyInt(),
                anyInt(), nullable(String.class));
    }

    @Before
    public void setUp() {
        assumeFalse(PermissionManager.USE_ACCESS_CHECKING_SERVICE);

        mStorageFile = new File(sContext.getFilesDir(), APP_OPS_FILENAME);
        mRecentAccessesFile = new File(sContext.getFilesDir(), APP_OPS_ACCESSES_FILENAME);
        mStorageFile.delete();
        mRecentAccessesFile.delete();

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mMyUid = Process.myUid();
        mSdkSandboxPackageUid = resolveSdkSandboxPackageUid();

        initializeStaticMocks();

        setupAppOpsService();

        mTestStartMillis = System.currentTimeMillis();
    }

    @After
    public void tearDown() {
        // @After methods are still executed even if there's assumption failure in @Before.
        if (PermissionManager.USE_ACCESS_CHECKING_SERVICE) {
            return;
        }

        mAppOpsService.shutdown();

        mMockingSession.finishMocking();
    }

    private static int resolveSdkSandboxPackageUid() {
        try {
            return sContext.getPackageManager().getPackageUid(
                    sSdkSandboxPackageName,
                    PackageManager.PackageInfoFlags.of(0)
            );
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Can't resolve sandbox package uid", e);
            return Process.INVALID_UID;
        }
    }

    private static void mockGetPackage(
            PackageManagerInternal managerMock,
            String packageName
    ) {
        AndroidPackage packageMock = mock(AndroidPackage.class);
        when(managerMock.getPackage(packageName)).thenReturn(packageMock);
    }

    private static void mockGetPackageStateInternal(
            PackageManagerInternal managerMock,
            String packageName,
            int uid
    ) {
        PackageStateInternal packageStateInternalMock = mock(PackageStateInternal.class);
        when(packageStateInternalMock.isPrivileged()).thenReturn(false);
        when(packageStateInternalMock.getAppId()).thenReturn(uid);
        when(packageStateInternalMock.getAndroidPackage()).thenReturn(mock(AndroidPackage.class));
        when(managerMock.getPackageStateInternal(packageName))
                .thenReturn(packageStateInternalMock);
    }

    private void initializeStaticMocks() {
        mMockingSession = mockitoSession()
                .strictness(Strictness.LENIENT)
                .spyStatic(LocalServices.class)
                .spyStatic(LocalManagerRegistry.class)
                .spyStatic(Settings.Global.class)
                .startMocking();

        // Mock LocalServices.getService(PackageManagerInternal.class).getPackageStateInternal
        // and getPackage dependency needed by AppOpsService
        PackageManagerInternal mockPackageManagerInternal = mock(PackageManagerInternal.class);
        mockGetPackage(mockPackageManagerInternal, sMyPackageName);
        mockGetPackageStateInternal(mockPackageManagerInternal, sMyPackageName, mMyUid);
        mockGetPackage(mockPackageManagerInternal, sSdkSandboxPackageName);
        mockGetPackageStateInternal(mockPackageManagerInternal, sSdkSandboxPackageName,
                mSdkSandboxPackageUid);
        when(mockPackageManagerInternal.getPackageUid(eq(sMyPackageName), anyLong(),
                eq(getUserId(mMyUid)))).thenReturn(mMyUid);
        doReturn(mockPackageManagerInternal).when(
                () -> LocalServices.getService(PackageManagerInternal.class));

        PackageManagerLocal mockPackageManagerLocal = mock(PackageManagerLocal.class);
        PackageManagerLocal.UnfilteredSnapshot mockUnfilteredSnapshot =
                mock(PackageManagerLocal.UnfilteredSnapshot.class);
        PackageState mockMyPS = mock(PackageState.class);
        ArrayMap<String, PackageState> packageStates = new ArrayMap<>();
        packageStates.put(sMyPackageName, mockMyPS);
        when(mockMyPS.getAppId()).thenReturn(mMyUid);
        when(mockUnfilteredSnapshot.getPackageStates()).thenReturn(packageStates);
        when(mockPackageManagerLocal.withUnfilteredSnapshot()).thenReturn(mockUnfilteredSnapshot);
        doReturn(mockPackageManagerLocal).when(
                () -> LocalManagerRegistry.getManager(PackageManagerLocal.class));

        UserManagerInternal mockUserManagerInternal = mock(UserManagerInternal.class);
        when(mockUserManagerInternal.getUserIds()).thenReturn(new int[] {getUserId(mMyUid)});
        doReturn(mockUserManagerInternal).when(
                () -> LocalServices.getService(UserManagerInternal.class));

        // Mock behavior to use specific Settings.Global.APPOP_HISTORY_PARAMETERS
        doReturn(null).when(() -> Settings.Global.getString(any(ContentResolver.class),
                eq(Settings.Global.APPOP_HISTORY_PARAMETERS)));

        prepareInstallInvocation(mockPackageManagerInternal);
    }

    private void prepareInstallInvocation(PackageManagerInternal mockPackageManagerInternal) {
        when(mockPackageManagerInternal.getPackageList(any())).thenAnswer(invocation -> {
            PackageManagerInternal.PackageListObserver observer = invocation.getArgument(0);
            observer.onPackageAdded(sMyPackageName, getAppId(mMyUid));
            return null;
        });
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
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        // Note another op that's not allowed.
        mAppOpsService.noteOperation(OP_WRITE_SMS, mMyUid, sMyPackageName, null, false, null,
                false);
        loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);
        assertContainsOp(loggedOps, OP_WRITE_SMS, -1, mTestStartMillis, MODE_ERRORED);
    }

    @Test
    public void testNoteOperationFromSdkSandbox() {
        int sandboxUid = Process.toSdkSandboxUid(mMyUid);

        // Note an op that's allowed.
        SyncNotedAppOp allowedResult = mAppOpsService.noteOperation(OP_TAKE_AUDIO_FOCUS, sandboxUid,
                sSdkSandboxPackageName, null, false, null, false);
        assertThat(allowedResult.getOpMode()).isEqualTo(MODE_ALLOWED);

        // Note another op that's not allowed.
        SyncNotedAppOp erroredResult = mAppOpsService.noteOperation(OP_READ_DEVICE_IDENTIFIERS,
                sandboxUid, sSdkSandboxPackageName, null, false, null, false);
        assertThat(erroredResult.getOpMode()).isEqualTo(MODE_ERRORED);
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

        assertThat(mAppOpsService.noteOperation(OP_WIFI_SCAN, mMyUid, sMyPackageName, null, false,
                null, false).getOpMode()).isEqualTo(MODE_ALLOWED);

        assertContainsOp(getLoggedOps(), OP_WIFI_SCAN, mTestStartMillis, -1,
                MODE_ALLOWED /* default for WIFI_SCAN; this is not changed or used in this test */);

        // Now set COARSE_LOCATION to ERRORED -> this will make WIFI_SCAN disabled as well.
        mAppOpsService.setMode(OP_COARSE_LOCATION, mMyUid, sMyPackageName, MODE_ERRORED);
        assertThat(mAppOpsService.noteOperation(OP_WIFI_SCAN, mMyUid, sMyPackageName, null, false,
                null, false).getOpMode()).isEqualTo(MODE_ERRORED);

        assertContainsOp(getLoggedOps(), OP_WIFI_SCAN, mTestStartMillis, mTestStartMillis,
                MODE_ALLOWED /* default for WIFI_SCAN; this is not changed or used in this test */);
    }

    // Tests the dumping and restoring of the in-memory state to/from XML.
    @Test
    public void testStatePersistence() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.setMode(OP_WRITE_SMS, mMyUid, sMyPackageName, MODE_ERRORED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);
        mAppOpsService.noteOperation(OP_WRITE_SMS, mMyUid, sMyPackageName, null, false, null,
                false);

        mAppOpsService.shutdown();

        // Create a new app ops service which will initialize its state from XML.
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
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);
        mAppOpsService.shutdown();

        // Create a new app ops service which will initialize its state from XML.
        setupAppOpsService();

        // Query the state of the 2nd service.
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);
    }

    @Test
    public void testGetOpsForPackage() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);

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
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);

        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        mAppOpsService.packageRemoved(mMyUid, sMyPackageName);
        assertThat(getLoggedOps()).isNull();
    }


    /*
    TODO ntmyren: re enable when we have time to rewrite test.
    @Test
    public void testPackageRemovedHistoricalOps() throws InterruptedException {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);

        AppOpsManager.HistoricalOps historicalOps = new AppOpsManager.HistoricalOps(0, 15000);
        historicalOps.increaseAccessCount(OP_READ_SMS, mMyUid, sMyPackageName, null,
                AppOpsManager.UID_STATE_PERSISTENT, 0, 1);

        mAppOpsService.addHistoricalOps(historicalOps);

        AtomicReference<AppOpsManager.HistoricalOps> resultOpsRef = new AtomicReference<>();
        AtomicReference<CountDownLatch> latchRef = new AtomicReference<>(new CountDownLatch(1));
        RemoteCallback callback = new RemoteCallback(result -> {
            resultOpsRef.set(result.getParcelable(AppOpsManager.KEY_HISTORICAL_OPS));
            latchRef.get().countDown();
        });

        // First, do a fetch to ensure it's written
        mAppOpsService.getHistoricalOps(mMyUid, sMyPackageName, null, null,
                FILTER_BY_UID | FILTER_BY_PACKAGE_NAME, 0, Long.MAX_VALUE, 0, callback);

        latchRef.get().await(5, TimeUnit.SECONDS);
        assertThat(latchRef.get().getCount()).isEqualTo(0);
        assertThat(resultOpsRef.get().isEmpty()).isFalse();

        // Then, check it's deleted on removal
        mAppOpsService.packageRemoved(mMyUid, sMyPackageName);

        latchRef.set(new CountDownLatch(1));

        mAppOpsService.getHistoricalOps(mMyUid, sMyPackageName, null, null,
                FILTER_BY_UID | FILTER_BY_PACKAGE_NAME, 0, Long.MAX_VALUE, 0, callback);

        latchRef.get().await(5, TimeUnit.SECONDS);
        assertThat(latchRef.get().getCount()).isEqualTo(0);
        assertThat(resultOpsRef.get().isEmpty()).isTrue();
    }
     */

    @Test
    public void testUidRemoved() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);

        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        mAppOpsService.uidRemoved(mMyUid);
        assertThat(getLoggedOps()).isNull();
    }

    @Test
    public void testUidStateInitializationDoesntClearState() throws InterruptedException {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, sMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, sMyPackageName, null, false, null, false);
        mAppOpsService.initializeUidStates();
        List<PackageOps> ops = mAppOpsService.getOpsForPackage(mMyUid, sMyPackageName,
                new int[]{OP_READ_SMS});
        assertNotNull(ops);
        for (int i = 0; i < ops.size(); i++) {
            List<OpEntry> opEntries = ops.get(i).getOps();
            for (int j = 0; j < opEntries.size(); j++) {
                Map<String, AppOpsManager.AttributedOpEntry> attributedOpEntries = opEntries.get(
                        j).getAttributedOpEntries();
                assertNotEquals(-1, attributedOpEntries.get(null)
                        .getLastAccessTime(OP_FLAG_SELF));
            }
        }
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
                            .that(opEntry.getLastAccessTime(OP_FLAGS_ALL)).isAtLeast(minMillis);
                }
                if (minRejectMillis > 0) {
                    assertWithMessage("Unexpected rejection timestamp").that(
                            opEntry.getLastRejectTime(OP_FLAGS_ALL)).isAtLeast(minRejectMillis);
                }
            }
        }
        assertWithMessage("Op was not logged").that(opLogged).isTrue();
    }
}
