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

/** Tests for the system service {@link BackupManagerService} that performs backup/restore. */
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

    /**
     * Initialize state that {@link BackupManagerService} operations interact with. This includes
     * setting up the transport, starting the backup thread, and creating backup data directories.
     */
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

    /**
     * Clean up and reset state that was created for testing {@link BackupManagerService}
     * operations.
     */
    @After
    public void tearDown() throws Exception {
        mBackupThread.quit();
        ShadowAppBackupUtils.reset();
    }

    /**
     * Test verifying that {@link BackupManagerService#MORE_DEBUG} is set to {@code false}. This is
     * specifically to prevent overloading the logs in production.
     */
    @Test
    public void testMoreDebug_isFalse() throws Exception {
        boolean moreDebug = BackupManagerService.MORE_DEBUG;

        assertThat(moreDebug).isFalse();
    }

    /**
     * Test verifying that {@link BackupManagerService#getDestinationString(String)} returns the
     * current destination string of inputted transport if the transport is registered.
     */
    @Test
    public void testDestinationString() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenReturn("destinationString");
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String destination = backupManagerService.getDestinationString(mTransportName);

        assertThat(destination).isEqualTo("destinationString");
    }

    /**
     * Test verifying that {@link BackupManagerService#getDestinationString(String)} returns {@code
     * null} if the inputted transport is not registered.
     */
    @Test
    public void testDestinationString_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenThrow(TransportNotRegisteredException.class);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String destination = backupManagerService.getDestinationString(mTransportName);

        assertThat(destination).isNull();
    }

    /**
     * Test verifying that {@link BackupManagerService#getDestinationString(String)} throws a {@link
     * SecurityException} if the caller does not have backup permission.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#isAppEligibleForBackup(String)} returns
     * {@code false} when the given app is not eligible for backup.
     */
    @Test
    public void testIsAppEligibleForBackup_whenAppNotEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        boolean result = backupManagerService.isAppEligibleForBackup(PACKAGE_1);

        assertThat(result).isFalse();
    }

    /**
     * Test verifying that {@link BackupManagerService#isAppEligibleForBackup(String)} returns
     * {@code true} when the given app is eligible for backup.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#isAppEligibleForBackup(String)} throws a
     * {@link SecurityException} if the caller does not have backup permission.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#filterAppsEligibleForBackup(String[])}
     * returns an {@code array} of only apps that are eligible for backup from an {@array} of
     * inputted apps.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#filterAppsEligibleForBackup(String[])}
     * returns an empty {@code array} if no inputted apps are eligible for backup.
     */
    @Test
    public void testFilterAppsEligibleForBackup_whenNoneIsEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String[] filtered =
                backupManagerService.filterAppsEligibleForBackup(
                        new String[] {PACKAGE_1, PACKAGE_2});

        assertThat(filtered).isEmpty();
    }

    /**
     * Test verifying that {@link BackupManagerService#filterAppsEligibleForBackup(String[])} throws
     * a {@link SecurityException} if the caller does not have backup permission.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#selectBackupTransport(String)} successfully
     * switches the current transport to the inputted transport, returns the name of the old
     * transport, and disposes of the transport client after the operation.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#selectBackupTransport(String)} throws a
     * {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testSelectBackupTransport_withoutPermission() throws Exception {
        setUpForSelectTransport();
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.selectBackupTransport(mNewTransport.transportName));
    }

    /**
     * Test verifying that {@link BackupManagerService#selectBackupTransportAsync(ComponentName,
     * ISelectBackupTransportCallback)} successfully switches the current transport to the inputted
     * transport and disposes of the transport client after the operation.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#selectBackupTransportAsync(ComponentName,
     * ISelectBackupTransportCallback)} does not switch the current transport to the inputted
     * transport and notifies the inputted callback of failure when it fails to register the
     * transport.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#selectBackupTransportAsync(ComponentName,
     * ISelectBackupTransportCallback)} does not switch the current transport to the inputted
     * transport and notifies the inputted callback of failure when the transport gets unregistered.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#selectBackupTransportAsync(ComponentName,
     * ISelectBackupTransportCallback)} throws a {@link SecurityException} if the caller does not
     * have backup permission.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#getCurrentTransportComponent()} returns the
     * {@link ComponentName} of the currently selected transport.
     */
    @Test
    public void testGetCurrentTransportComponent() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent())
                .thenReturn(mTransport.getTransportComponent());
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isEqualTo(mTransport.getTransportComponent());
    }

    /**
     * Test verifying that {@link BackupManagerService#getCurrentTransportComponent()} returns
     * {@code null} if there is no currently selected transport.
     */
    @Test
    public void testGetCurrentTransportComponent_whenNoTransportSelected() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent()).thenReturn(null);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isNull();
    }

    /**
     * Test verifying that {@link BackupManagerService#getCurrentTransportComponent()} returns
     * {@code null} if the currently selected transport is not registered.
     */
    @Test
    public void testGetCurrentTransportComponent_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent())
                .thenThrow(TransportNotRegisteredException.class);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isNull();
    }

    /**
     * Test verifying that {@link BackupManagerService#getCurrentTransportComponent()} throws a
     * {@link SecurityException} if the caller does not have backup permission.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} succeeds if the uid of the transport is same as the
     * uid of the caller.
     */
    @Test
    public void
            testUpdateTransportAttributes_whenTransportUidEqualsCallingUid_callsTransportManager()
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} throws a {@link SecurityException} if the uid of the
     * transport is not equal to the uid of the caller.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} throws a {@link RuntimeException} if given a {@code
     * null} transport component.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} throws a {@link RuntimeException} if given a {@code
     * null} transport name.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} throws a {@link RuntimeException} if given a {@code
     * null} destination string.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} throws a {@link RuntimeException} if given either a
     * {@code null} data management label or {@code null} data management intent, but not both.
     */
    @Test
    public void
            testUpdateTransportAttributes_whenDataManagementArgsNullityDontMatch_throwsException()
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} succeeds if the caller has backup permission.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#updateTransportAttributes(int, ComponentName,
     * String, Intent, String, Intent, String)} throws a {@link SecurityException} if the caller
     * does not have backup permission.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} throws a {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testRequestBackup_whenPermissionDenied() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0));
    }

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} throws an {@link IllegalArgumentException} if passed {@null} for packages.
     */
    @Test
    public void testRequestBackup_whenPackagesNull() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                IllegalArgumentException.class,
                () -> backupManagerService.requestBackup(null, mObserver, 0));
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} throws an {@link IllegalArgumentException} if passed an empty {@code array} for
     * packages.
     */
    @Test
    public void testRequestBackup_whenPackagesEmpty() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                IllegalArgumentException.class,
                () -> backupManagerService.requestBackup(new String[0], mObserver, 0));
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#ERROR_BACKUP_NOT_ALLOWED} if backup is disabled.
     */
    @Test
    public void testRequestBackup_whenBackupDisabled() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        backupManagerService.setEnabled(false);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#ERROR_BACKUP_NOT_ALLOWED} if the system user hasn't gone
     * through SUW.
     */
    @Test
    public void testRequestBackup_whenNotProvisioned() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        backupManagerService.setProvisioned(false);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#ERROR_TRANSPORT_ABORTED} if the current transport is not
     * registered.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#SUCCESS} and notifies the observer of {@link
     * BackupManager#ERROR_BACKUP_NOT_ALLOWED} if the specified app is not eligible for backup.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#SUCCESS} and updates bookkeeping if backup for a key value
     * package succeeds.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#SUCCESS} and updates bookkeeping if backup for a full
     * backup package succeeds.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#backupNow()} clears the calling identity
     * for scheduling a job and then restores the original calling identity after the operation.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#backupNow()} restores the original calling
     * identity if an exception is thrown during execution.
     */
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

    /**
     * Test verifying that {@link BackupManagerService#BackupManagerService(Context, Trampoline,
     * HandlerThread, File, File, TransportManager)} posts a transport registration task to the
     * backup handler thread.
     */
    @Test
    public void testConstructor_postRegisterTransports() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);

        createBackupManagerService();

        mShadowBackupLooper.runToEndOfTasks();
        verify(mTransportManager).registerTransports();
    }

    /**
     * Test verifying that the {@link BackupManagerService#BackupManagerService(Context, Trampoline,
     * HandlerThread, File, File, TransportManager)} does not directly register transports in its
     * own thread.
     */
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
        /**
         * Implementation of {@link ShadowKeyValueBackupJob#schedule(Context, long,
         * BackupManagerConstants)} that throws an {@link IllegalArgumentException}.
         */
        public static void schedule(Context ctx, long delay, BackupManagerConstants constants) {
            ShadowKeyValueBackupJob.schedule(ctx, delay, constants);
            throw new IllegalArgumentException();
        }
    }
}
