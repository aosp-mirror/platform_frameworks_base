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
import static android.app.AppOpsManager.OP_READ_SMS;
import static android.app.AppOpsManager.OP_WIFI_SCAN;
import static android.app.AppOpsManager.OP_WRITE_SMS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.app.AppOpsManager.OpEntry;
import android.app.AppOpsManager.PackageOps;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.appop.AppOpsService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.util.List;

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

    private File mAppOpsFile;
    private Context mContext;
    private Handler mHandler;
    private AppOpsService mAppOpsService;
    private String mMyPackageName;
    private int mMyUid;
    private long mTestStartMillis;

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getTargetContext();
        mAppOpsFile = new File(mContext.getFilesDir(), APP_OPS_FILENAME);
        if (mAppOpsFile.exists()) {
            // Start with a clean state (persisted into XML).
            mAppOpsFile.delete();
        }

        HandlerThread handlerThread = new HandlerThread(TAG);
        handlerThread.start();
        mHandler = new Handler(handlerThread.getLooper());
        mMyPackageName = mContext.getOpPackageName();
        mMyUid = Process.myUid();

        mAppOpsService = new AppOpsService(mAppOpsFile, mHandler);
        mAppOpsService.mContext = mContext;
        mTestStartMillis = System.currentTimeMillis();
    }

    @Test
    public void testGetOpsForPackage_noOpsLogged() {
        assertThat(getLoggedOps()).isNull();
    }

    @Test
    public void testNoteOperationAndGetOpsForPackage() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, mMyPackageName, MODE_ALLOWED);
        mAppOpsService.setMode(OP_WRITE_SMS, mMyUid, mMyPackageName, MODE_ERRORED);

        // Note an op that's allowed.
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, mMyPackageName);
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        // Note another op that's not allowed.
        mAppOpsService.noteOperation(OP_WRITE_SMS, mMyUid, mMyPackageName);
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
        mAppOpsService.setMode(OP_COARSE_LOCATION, mMyUid, mMyPackageName, MODE_ALLOWED);

        assertThat(mAppOpsService.noteOperation(OP_WIFI_SCAN, mMyUid, mMyPackageName))
                .isEqualTo(MODE_ALLOWED);

        assertContainsOp(getLoggedOps(), OP_WIFI_SCAN, mTestStartMillis, -1,
                MODE_ALLOWED /* default for WIFI_SCAN; this is not changed or used in this test */);

        // Now set COARSE_LOCATION to ERRORED -> this will make WIFI_SCAN disabled as well.
        mAppOpsService.setMode(OP_COARSE_LOCATION, mMyUid, mMyPackageName, MODE_ERRORED);
        assertThat(mAppOpsService.noteOperation(OP_WIFI_SCAN, mMyUid, mMyPackageName))
                .isEqualTo(MODE_ERRORED);

        assertContainsOp(getLoggedOps(), OP_WIFI_SCAN, mTestStartMillis, mTestStartMillis,
                MODE_ALLOWED /* default for WIFI_SCAN; this is not changed or used in this test */);
    }

    // Tests the dumping and restoring of the in-memory state to/from XML.
    @Test
    public void testStatePersistence() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, mMyPackageName, MODE_ALLOWED);
        mAppOpsService.setMode(OP_WRITE_SMS, mMyUid, mMyPackageName, MODE_ERRORED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, mMyPackageName);
        mAppOpsService.noteOperation(OP_WRITE_SMS, mMyUid, mMyPackageName);
        mAppOpsService.writeState();

        // Create a new app ops service, and initialize its state from XML.
        mAppOpsService = new AppOpsService(mAppOpsFile, mHandler);
        mAppOpsService.mContext = mContext;
        mAppOpsService.readState();

        // Query the state of the 2nd service.
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);
        assertContainsOp(loggedOps, OP_WRITE_SMS, -1, mTestStartMillis, MODE_ERRORED);
    }

    // Tests that ops are persisted during shutdown.
    @Test
    public void testShutdown() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, mMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, mMyPackageName);
        mAppOpsService.shutdown();

        // Create a new app ops service, and initialize its state from XML.
        mAppOpsService = new AppOpsService(mAppOpsFile, mHandler);
        mAppOpsService.mContext = mContext;
        mAppOpsService.readState();

        // Query the state of the 2nd service.
        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);
    }

    @Test
    public void testGetOpsForPackage() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, mMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, mMyPackageName);

        // Query all ops
        List<PackageOps> loggedOps = mAppOpsService.getOpsForPackage(
                mMyUid, mMyPackageName, null /* all ops */);
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        // Query specific ops
        loggedOps = mAppOpsService.getOpsForPackage(
                mMyUid, mMyPackageName, new int[]{OP_READ_SMS, OP_WRITE_SMS});
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        // Query unknown UID
        loggedOps = mAppOpsService.getOpsForPackage(mMyUid + 1, mMyPackageName, null /* all ops */);
        assertThat(loggedOps).isNull();

        // Query unknown package name
        loggedOps = mAppOpsService.getOpsForPackage(mMyUid, "fake.package", null /* all ops */);
        assertThat(loggedOps).isNull();

        // Query op code that's not been logged
        loggedOps = mAppOpsService.getOpsForPackage(mMyUid, mMyPackageName,
                new int[]{OP_WRITE_SMS});
        assertThat(loggedOps).isNull();
    }

    @Test
    public void testPackageRemoved() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, mMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, mMyPackageName);

        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        mAppOpsService.packageRemoved(mMyUid, mMyPackageName);
        assertThat(getLoggedOps()).isNull();
    }

    @Test
    public void testUidRemoved() {
        mAppOpsService.setMode(OP_READ_SMS, mMyUid, mMyPackageName, MODE_ALLOWED);
        mAppOpsService.noteOperation(OP_READ_SMS, mMyUid, mMyPackageName);

        List<PackageOps> loggedOps = getLoggedOps();
        assertContainsOp(loggedOps, OP_READ_SMS, mTestStartMillis, -1, MODE_ALLOWED);

        mAppOpsService.uidRemoved(mMyUid);
        assertThat(getLoggedOps()).isNull();
    }

    private List<PackageOps> getLoggedOps() {
        return mAppOpsService.getOpsForPackage(mMyUid, mMyPackageName, null /* all ops */);
    }

    private void assertContainsOp(List<PackageOps> loggedOps, int opCode, long minMillis,
            long minRejectMillis, int mode) {

        boolean opLogged = false;
        for (PackageOps pkgOps : loggedOps) {
            assertWithMessage("Unexpected UID").that(mMyUid).isEqualTo(pkgOps.getUid());
            assertWithMessage("Unexpected package name").that(mMyPackageName).isEqualTo(
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
