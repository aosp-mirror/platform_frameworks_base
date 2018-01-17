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
 * limitations under the License
 */

package com.android.server.backup;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.testng.Assert.expectThrows;

import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.HandlerThread;
import android.platform.test.annotations.Presubmit;
import android.support.test.InstrumentationRegistry;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;
import android.util.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;

@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
// TODO: Migrate this to Robolectric and merge with BackupManagerServiceRoboTest (and remove 'Robo')
public class BackupManagerServiceTest {
    private static final String TAG = "BMSTest";
    private static final ComponentName TRANSPORT_COMPONENT =
            new ComponentName(
                    "com.google.android.gms",
                    "com.google.android.gms.backup.BackupTransportService");
    private static final String TRANSPORT_NAME = TRANSPORT_COMPONENT.flattenToShortString();

    @Mock private TransportManager mTransportManager;
    private Context mContext;
    private HandlerThread mBackupThread;
    private int mPackageUid;
    private File mBaseStateDir;
    private File mDataDir;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Context baseContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mContext = spy(new ContextWrapper(baseContext));

        mBackupThread = new HandlerThread("backup-test");
        mBackupThread.setUncaughtExceptionHandler(
                (t, e) -> Log.e(TAG, "Uncaught exception in test thread " + t.getName(), e));
        mBackupThread.start();

        File cacheDir = mContext.getCacheDir();
        mBaseStateDir = new File(cacheDir, "base_state_dir");
        mDataDir = new File(cacheDir, "data_dir");

        mPackageUid =
                mContext.getPackageManager().getPackageUid(TRANSPORT_COMPONENT.getPackageName(), 0);
    }

    @After
    public void tearDown() throws Exception {
        mBackupThread.quit();
        mBaseStateDir.delete();
        mDataDir.delete();
    }

    @Test
    public void testConstructor_callsTransportManagerSetTransportBoundListener() throws Exception {
        createBackupManagerService();

        verify(mTransportManager)
                .setOnTransportRegisteredListener(any());
    }

    @Test
    public void
            testUpdateTransportAttributes_whenTransportUidEqualsToCallingUid_callsThroughToTransportManager()
                    throws Exception {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();

        backupManagerService.updateTransportAttributes(
                mPackageUid,
                TRANSPORT_COMPONENT,
                TRANSPORT_NAME,
                configurationIntent,
                "currentDestinationString",
                dataManagementIntent,
                "dataManagementLabel");

        verify(mTransportManager)
                .updateTransportAttributes(
                        eq(TRANSPORT_COMPONENT),
                        eq(TRANSPORT_NAME),
                        eq(configurationIntent),
                        eq("currentDestinationString"),
                        eq(dataManagementIntent),
                        eq("dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenTransportUidNotEqualToCallingUid_throwsException()
            throws Exception {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mPackageUid + 1,
                                TRANSPORT_COMPONENT,
                                TRANSPORT_NAME,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenTransportComponentNull_throwsException() {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mPackageUid,
                                null,
                                TRANSPORT_NAME,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenNameNull_throwsException() {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mPackageUid,
                                TRANSPORT_COMPONENT,
                                null,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenCurrentDestinationStringNull_throwsException() {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mPackageUid,
                                TRANSPORT_COMPONENT,
                                TRANSPORT_NAME,
                                new Intent(),
                                null,
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void
            testUpdateTransportAttributes_whenDataManagementArgumentsNullityDontMatch_throwsException() {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mPackageUid,
                                TRANSPORT_COMPONENT,
                                TRANSPORT_NAME,
                                new Intent(),
                                "currentDestinationString",
                                null,
                                "dataManagementLabel"));

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mPackageUid,
                                TRANSPORT_COMPONENT,
                                TRANSPORT_NAME,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                null));
    }

    @Test
    public void
            testUpdateTransportAttributes_whenDataManagementArgumentsNull_callsThroughToTransportManager() {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();
        Intent configurationIntent = new Intent();

        backupManagerService.updateTransportAttributes(
                mPackageUid,
                TRANSPORT_COMPONENT,
                TRANSPORT_NAME,
                configurationIntent,
                "currentDestinationString",
                null,
                null);

        verify(mTransportManager)
                .updateTransportAttributes(
                        eq(TRANSPORT_COMPONENT),
                        eq(TRANSPORT_NAME),
                        eq(configurationIntent),
                        eq("currentDestinationString"),
                        eq(null),
                        eq(null));
    }

    @Test
    public void
            testUpdateTransportAttributes_whenPermissionGranted_callsThroughToTransportManager() {
        grantBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();

        backupManagerService.updateTransportAttributes(
                mPackageUid,
                TRANSPORT_COMPONENT,
                TRANSPORT_NAME,
                configurationIntent,
                "currentDestinationString",
                dataManagementIntent,
                "dataManagementLabel");

        verify(mTransportManager)
                .updateTransportAttributes(
                        eq(TRANSPORT_COMPONENT),
                        eq(TRANSPORT_NAME),
                        eq(configurationIntent),
                        eq("currentDestinationString"),
                        eq(dataManagementIntent),
                        eq("dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenPermissionDenied_throwsSecurityException() {
        denyBackupPermission();
        RefactoredBackupManagerService backupManagerService = createBackupManagerService();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mPackageUid,
                                TRANSPORT_COMPONENT,
                                TRANSPORT_NAME,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    private RefactoredBackupManagerService createBackupManagerService() {
        return new RefactoredBackupManagerService(
                mContext,
                new Trampoline(mContext),
                mBackupThread,
                mBaseStateDir,
                mDataDir,
                mTransportManager);
    }

    private void grantBackupPermission() {
        doNothing().when(mContext).enforceCallingOrSelfPermission(any(), anyString());
    }

    private void denyBackupPermission() {
        doThrow(new SecurityException())
                .when(mContext)
                .enforceCallingOrSelfPermission(
                        eq(android.Manifest.permission.BACKUP), anyString());
    }
}
