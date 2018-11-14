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
 * limitations under the License
 */

package com.android.server.backup;

import static com.android.server.backup.testing.BackupManagerServiceTestUtils.startSilentBackupThread;
import static com.android.server.backup.testing.TransportData.backupTransport;
import static com.android.server.backup.testing.TransportData.d2dTransport;
import static com.android.server.backup.testing.TransportData.localTransport;
import static com.android.server.backup.testing.TransportTestUtils.setUpCurrentTransport;
import static com.android.server.backup.testing.TransportTestUtils.setUpTransports;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.testng.Assert.expectThrows;

import android.app.backup.BackupManager;
import android.app.backup.IBackupObserver;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import com.android.server.backup.testing.BackupManagerServiceTestUtils;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.testing.shadows.ShadowAppBackupUtils;
import com.android.server.testing.shadows.ShadowBinder;
import com.android.server.testing.shadows.ShadowKeyValueBackupJob;
import com.android.server.testing.shadows.ShadowKeyValueBackupTask;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSettings;

import java.io.File;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(shadows = {ShadowAppBackupUtils.class})
@Presubmit
public class BackupManagerServiceTest {
    private static final String TAG = "BMSTest";
    private static final String PACKAGE_1 = "some.package.1";
    private static final String PACKAGE_2 = "some.package.2";

    @Mock private TransportManager mTransportManager;
    private HandlerThread mBackupThread;
    private ShadowLooper mShadowBackupLooper;
    private File mBaseStateDir;
    private File mDataDir;
    private ShadowContextWrapper mShadowContext;
    private Context mContext;
    private TransportData mTransport;
    private String mTransportName;
    private ShadowPackageManager mShadowPackageManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();
        mTransportName = mTransport.transportName;

        // Unrelated exceptions are thrown in the backup thread. Until we mock everything properly
        // we should not fail tests because of this. This is not flakiness, the exceptions thrown
        // don't interfere with the tests.
        mBackupThread = startSilentBackupThread(TAG);
        mShadowBackupLooper = shadowOf(mBackupThread.getLooper());

        ContextWrapper context = RuntimeEnvironment.application;
        mShadowPackageManager = shadowOf(context.getPackageManager());
        mContext = context;
        mShadowContext = shadowOf(context);

        File cacheDir = mContext.getCacheDir();
        // Corresponds to /data/backup
        mBaseStateDir = new File(cacheDir, "base_state");
        // Corresponds to /cache/backup_stage
        mDataDir = new File(cacheDir, "data");
    }

    @After
    public void tearDown() throws Exception {
        mBackupThread.quit();
        ShadowAppBackupUtils.reset();
    }

    @Test
    public void testMoreDebug_isFalse() throws Exception {
        boolean moreDebug = BackupManagerService.MORE_DEBUG;

        assertThat(moreDebug).isFalse();
    }

    /* Tests for destination string */

    @Test
    public void testDestinationString() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenReturn("destinationString");
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String destination = backupManagerService.getDestinationString(mTransportName);

        assertThat(destination).isEqualTo("destinationString");
    }

    @Test
    public void testDestinationString_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenThrow(TransportNotRegisteredException.class);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String destination = backupManagerService.getDestinationString(mTransportName);

        assertThat(destination).isNull();
    }

    @Test
    public void testDestinationString_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenThrow(TransportNotRegisteredException.class);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.getDestinationString(mTransportName));
    }

    /* Tests for app eligibility */

    @Test
    public void testIsAppEligibleForBackup_whenAppNotEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        boolean result = backupManagerService.isAppEligibleForBackup(PACKAGE_1);

        assertThat(result).isFalse();
    }

    @Test
    public void testIsAppEligibleForBackup_whenAppEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpCurrentTransport(mTransportManager, backupTransport());
        ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(PACKAGE_1);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        boolean result = backupManagerService.isAppEligibleForBackup(PACKAGE_1);

        assertThat(result).isTrue();
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
    }

    @Test
    public void testIsAppEligibleForBackup_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(PACKAGE_1);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.isAppEligibleForBackup(PACKAGE_1));
    }

    @Test
    public void testFilterAppsEligibleForBackup() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpCurrentTransport(mTransportManager, mTransport);
        ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(PACKAGE_1);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String[] filtered =
                backupManagerService.filterAppsEligibleForBackup(
                        new String[] {PACKAGE_1, PACKAGE_2});

        assertThat(filtered).asList().containsExactly(PACKAGE_1);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
    }

    @Test
    public void testFilterAppsEligibleForBackup_whenNoneIsEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String[] filtered =
                backupManagerService.filterAppsEligibleForBackup(
                        new String[] {PACKAGE_1, PACKAGE_2});

        assertThat(filtered).isEmpty();
    }

    @Test
    public void testFilterAppsEligibleForBackup_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.filterAppsEligibleForBackup(
                                new String[] {PACKAGE_1, PACKAGE_2}));
    }

    /* Tests for select transport */

    private ComponentName mNewTransportComponent;
    private TransportData mNewTransport;
    private TransportMock mNewTransportMock;
    private TransportData mOldTransport;
    private TransportMock mOldTransportMock;

    private void setUpForSelectTransport() throws Exception {
        mNewTransport = backupTransport();
        mNewTransportComponent = mNewTransport.getTransportComponent();
        mOldTransport = d2dTransport();
        List<TransportMock> transportMocks =
                setUpTransports(mTransportManager, mNewTransport, mOldTransport, localTransport());
        mNewTransportMock = transportMocks.get(0);
        mOldTransportMock = transportMocks.get(1);
        when(mTransportManager.selectTransport(eq(mNewTransport.transportName)))
                .thenReturn(mOldTransport.transportName);
    }

    @Test
    public void testSelectBackupTransport() throws Exception {
        setUpForSelectTransport();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String oldTransport =
                backupManagerService.selectBackupTransport(mNewTransport.transportName);

        assertThat(getSettingsTransport()).isEqualTo(mNewTransport.transportName);
        assertThat(oldTransport).isEqualTo(mOldTransport.transportName);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(mNewTransportMock.transportClient), any());
    }

    @Test
    public void testSelectBackupTransport_withoutPermission() throws Exception {
        setUpForSelectTransport();
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.selectBackupTransport(mNewTransport.transportName));
    }

    @Test
    public void testSelectBackupTransportAsync() throws Exception {
        setUpForSelectTransport();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.registerAndSelectTransport(eq(mNewTransportComponent)))
                .thenReturn(BackupManager.SUCCESS);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(mNewTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isEqualTo(mNewTransport.transportName);
        verify(callback).onSuccess(eq(mNewTransport.transportName));
        verify(mTransportManager)
                .disposeOfTransportClient(eq(mNewTransportMock.transportClient), any());
    }

    @Test
    public void testSelectBackupTransportAsync_whenRegistrationFails() throws Exception {
        setUpForSelectTransport();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.registerAndSelectTransport(eq(mNewTransportComponent)))
                .thenReturn(BackupManager.ERROR_TRANSPORT_UNAVAILABLE);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(mNewTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isNotEqualTo(mNewTransport.transportName);
        verify(callback).onFailure(anyInt());
    }

    @Test
    public void testSelectBackupTransportAsync_whenTransportGetsUnregistered() throws Exception {
        setUpTransports(mTransportManager, mTransport.unregistered());
        ComponentName newTransportComponent = mTransport.getTransportComponent();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.registerAndSelectTransport(eq(newTransportComponent)))
                .thenReturn(BackupManager.SUCCESS);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(newTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isNotEqualTo(mTransportName);
        verify(callback).onFailure(anyInt());
    }

    @Test
    public void testSelectBackupTransportAsync_withoutPermission() throws Exception {
        setUpForSelectTransport();
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        ComponentName newTransportComponent = mNewTransport.getTransportComponent();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.selectBackupTransportAsync(
                                newTransportComponent, mock(ISelectBackupTransportCallback.class)));
    }

    private String getSettingsTransport() {
        return ShadowSettings.ShadowSecure.getString(
                mContext.getContentResolver(), Settings.Secure.BACKUP_TRANSPORT);
    }

    /* Tests for transport attributes */

    @Test
    public void testGetCurrentTransportComponent() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent())
                .thenReturn(mTransport.getTransportComponent());
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isEqualTo(mTransport.getTransportComponent());
    }

    @Test
    public void testGetCurrentTransportComponent_whenNoTransportSelected() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent()).thenReturn(null);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isNull();
    }

    @Test
    public void testGetCurrentTransportComponent_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent())
                .thenThrow(TransportNotRegisteredException.class);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isNull();
    }

    @Test
    public void testGetCurrentTransportComponent_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(SecurityException.class, backupManagerService::getCurrentTransportComponent);
    }

    /* Tests for updating transport attributes */

    private static final int PACKAGE_UID = 10;
    private ComponentName mTransportComponent;
    private int mTransportUid;

    private void setUpForUpdateTransportAttributes() throws Exception {
        mTransportComponent = mTransport.getTransportComponent();
        String transportPackage = mTransportComponent.getPackageName();

        ShadowPackageManager shadowPackageManager = shadowOf(mContext.getPackageManager());
        shadowPackageManager.addPackage(transportPackage);
        shadowPackageManager.setPackagesForUid(PACKAGE_UID, transportPackage);

        mTransportUid = mContext.getPackageManager().getPackageUid(transportPackage, 0);
    }

    @Test
    public void
            testUpdateTransportAttributes_whenTransportUidEqualsToCallingUid_callsThroughToTransportManager()
                    throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        backupManagerService.updateTransportAttributes(
                mTransportUid,
                mTransportComponent,
                mTransportName,
                configurationIntent,
                "currentDestinationString",
                dataManagementIntent,
                "dataManagementLabel");

        verify(mTransportManager)
                .updateTransportAttributes(
                        eq(mTransportComponent),
                        eq(mTransportName),
                        eq(configurationIntent),
                        eq("currentDestinationString"),
                        eq(dataManagementIntent),
                        eq("dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenTransportUidNotEqualToCallingUid_throwsException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mTransportUid + 1,
                                mTransportComponent,
                                mTransportName,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenTransportComponentNull_throwsException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mTransportUid,
                                null,
                                mTransportName,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenNameNull_throwsException() throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mTransportUid,
                                mTransportComponent,
                                null,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenCurrentDestinationStringNull_throwsException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mTransportUid,
                                mTransportComponent,
                                mTransportName,
                                new Intent(),
                                null,
                                new Intent(),
                                "dataManagementLabel"));
    }

    @Test
    public void
            testUpdateTransportAttributes_whenDataManagementArgumentsNullityDontMatch_throwsException()
                    throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mTransportUid,
                                mTransportComponent,
                                mTransportName,
                                new Intent(),
                                "currentDestinationString",
                                null,
                                "dataManagementLabel"));

        expectThrows(
                RuntimeException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mTransportUid,
                                mTransportComponent,
                                mTransportName,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                null));
    }

    @Test
    public void testUpdateTransportAttributes_whenPermissionGranted_callsThroughToTransportManager()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        backupManagerService.updateTransportAttributes(
                mTransportUid,
                mTransportComponent,
                mTransportName,
                configurationIntent,
                "currentDestinationString",
                dataManagementIntent,
                "dataManagementLabel");

        verify(mTransportManager)
                .updateTransportAttributes(
                        eq(mTransportComponent),
                        eq(mTransportName),
                        eq(configurationIntent),
                        eq("currentDestinationString"),
                        eq(dataManagementIntent),
                        eq("dataManagementLabel"));
    }

    @Test
    public void testUpdateTransportAttributes_whenPermissionDenied_throwsSecurityException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.updateTransportAttributes(
                                mTransportUid,
                                mTransportComponent,
                                mTransportName,
                                new Intent(),
                                "currentDestinationString",
                                new Intent(),
                                "dataManagementLabel"));
    }

    /* Tests for request backup */

    @Mock private IBackupObserver mObserver;

    private void setUpForRequestBackup(String... packages) throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        for (String packageName : packages) {
            mShadowPackageManager.addPackage(packageName);
            ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(packageName);
        }
        setUpCurrentTransport(mTransportManager, mTransport);
    }

    private void tearDownForRequestBackup() {
        ShadowKeyValueBackupTask.reset();
    }

    @Test
    public void testRequestBackup_whenPermissionDenied() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0));
    }

    @Test
    public void testRequestBackup_whenPackagesNull() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                IllegalArgumentException.class,
                () -> backupManagerService.requestBackup(null, mObserver, 0));
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRequestBackup_whenPackagesEmpty() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                IllegalArgumentException.class,
                () -> backupManagerService.requestBackup(new String[0], mObserver, 0));
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRequestBackup_whenBackupDisabled() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        backupManagerService.setEnabled(false);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    @Test
    public void testRequestBackup_whenNotProvisioned() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        backupManagerService.setProvisioned(false);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    @Test
    public void testRequestBackup_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport.unregistered());
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        backupManagerService.setEnabled(true);
        backupManagerService.setProvisioned(true);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_TRANSPORT_ABORTED);
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    @Test
    public void testRequestBackup_whenAppNotEligibleForBackup() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        mShadowPackageManager.addPackage(PACKAGE_1);
        setUpCurrentTransport(mTransportManager, mTransport);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        backupManagerService.setEnabled(true);
        backupManagerService.setProvisioned(true);
        // Haven't set PACKAGE_1 as eligible

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.SUCCESS);
        verify(mObserver).onResult(PACKAGE_1, BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        // TODO: We probably don't need to kick-off KeyValueBackupTask when list is empty
        tearDownForRequestBackup();
    }

    @Test
    @Config(shadows = ShadowKeyValueBackupTask.class)
    public void testRequestBackup_whenPackageIsKeyValue() throws Exception {
        setUpForRequestBackup(PACKAGE_1);
        BackupManagerService backupManagerService = createBackupManagerServiceForRequestBackup();

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(BackupManager.SUCCESS);
        ShadowKeyValueBackupTask shadowTask = ShadowKeyValueBackupTask.getLastCreated();
        assertThat(shadowTask.getQueue()).containsExactly(PACKAGE_1);
        assertThat(shadowTask.getPendingFullBackups()).isEmpty();
        // TODO: Assert more about KeyValueBackupTask
        tearDownForRequestBackup();
    }

    @Test
    @Config(shadows = ShadowKeyValueBackupTask.class)
    public void testRequestBackup_whenPackageIsFullBackup() throws Exception {
        setUpForRequestBackup(PACKAGE_1);
        ShadowAppBackupUtils.setAppGetsFullBackup(PACKAGE_1);
        BackupManagerService backupManagerService = createBackupManagerServiceForRequestBackup();

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(result).isEqualTo(BackupManager.SUCCESS);
        ShadowKeyValueBackupTask shadowTask = ShadowKeyValueBackupTask.getLastCreated();
        assertThat(shadowTask.getQueue()).isEmpty();
        assertThat(shadowTask.getPendingFullBackups()).containsExactly(PACKAGE_1);
        // TODO: Assert more about KeyValueBackupTask
        tearDownForRequestBackup();
    }

    @Test
    @Config(shadows = {ShadowBinder.class, ShadowKeyValueBackupJob.class})
    public void testBackupNow_clearsCallingIdentityForJobScheduler() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        setUpPowerManager(backupManagerService);
        ShadowBinder.setCallingUid(1);

        backupManagerService.backupNow();

        assertThat(ShadowKeyValueBackupJob.getCallingUid()).isEqualTo(ShadowBinder.LOCAL_UID);
        assertThat(ShadowBinder.getCallingUid()).isEqualTo(1);
    }

    @Test
    @Config(shadows = {ShadowBinder.class, ShadowKeyValueBackupJobException.class})
    public void testBackupNow_whenExceptionThrown_restoresCallingIdentity() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        setUpPowerManager(backupManagerService);
        ShadowBinder.setCallingUid(1);

        expectThrows(IllegalArgumentException.class, backupManagerService::backupNow);
        assertThat(ShadowKeyValueBackupJobException.getCallingUid())
                .isEqualTo(ShadowBinder.LOCAL_UID);
        assertThat(ShadowBinder.getCallingUid()).isEqualTo(1);
    }

    private BackupManagerService createBackupManagerServiceForRequestBackup() {
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        backupManagerService.setEnabled(true);
        backupManagerService.setProvisioned(true);
        return backupManagerService;
    }

    /* Miscellaneous tests */

    @Test
    public void testConstructor_postRegisterTransports() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);

        createBackupManagerService();

        mShadowBackupLooper.runToEndOfTasks();
        verify(mTransportManager).registerTransports();
    }

    @Test
    public void testConstructor_doesNotRegisterTransportsSynchronously() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);

        createBackupManagerService();

        // Operations posted to mBackupThread only run with mShadowBackupLooper.runToEndOfTasks()
        verify(mTransportManager, never()).registerTransports();
    }

    private BackupManagerService createBackupManagerService() {
        return new BackupManagerService(
                mContext,
                new Trampoline(mContext),
                mBackupThread,
                mBaseStateDir,
                mDataDir,
                mTransportManager);
    }

    private BackupManagerService createInitializedBackupManagerService() {
        return BackupManagerServiceTestUtils.createInitializedBackupManagerService(
                mContext, mBackupThread, mBaseStateDir, mDataDir, mTransportManager);
    }

    private void setUpPowerManager(BackupManagerService backupManagerService) {
        PowerManager powerManagerMock = mock(PowerManager.class);
        when(powerManagerMock.getPowerSaveState(anyInt()))
                .thenReturn(new PowerSaveState.Builder().setBatterySaverEnabled(true).build());
        backupManagerService.setPowerManager(powerManagerMock);
    }

    /**
     * We can't mock the void method {@link #schedule(Context, long, BackupManagerConstants)} so we
     * extend {@link ShadowKeyValueBackupJob} and throw an exception at the end of the method.
     */
    @Implements(KeyValueBackupJob.class)
    public static class ShadowKeyValueBackupJobException extends ShadowKeyValueBackupJob {
        public static void schedule(Context ctx, long delay, BackupManagerConstants constants) {
            ShadowKeyValueBackupJob.schedule(ctx, delay, constants);
            throw new IllegalArgumentException();
        }
    }
}
