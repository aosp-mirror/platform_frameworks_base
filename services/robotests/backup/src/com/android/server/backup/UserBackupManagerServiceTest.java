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
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.Shadows.shadowOf;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.expectThrows;

import android.app.backup.BackupManager;
import android.app.backup.IBackupObserver;
import android.app.backup.ISelectBackupTransportCallback;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Binder;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.PowerSaveState;
import android.os.UserHandle;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import com.android.server.backup.testing.BackupManagerServiceTestUtils;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.testing.shadows.ShadowAppBackupUtils;
import com.android.server.testing.shadows.ShadowApplicationPackageManager;
import com.android.server.testing.shadows.ShadowBinder;
import com.android.server.testing.shadows.ShadowKeyValueBackupJob;
import com.android.server.testing.shadows.ShadowKeyValueBackupTask;
import com.android.server.testing.shadows.ShadowSystemServiceRegistry;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;

import java.io.File;
import java.io.FileDescriptor;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Tests for the per-user instance of the backup/restore system service {@link
 * UserBackupManagerService} that performs operations for its target user.
 */
@RunWith(RobolectricTestRunner.class)
@Config(
        shadows = {
            ShadowAppBackupUtils.class,
            ShadowApplicationPackageManager.class,
            ShadowSystemServiceRegistry.class
        })
@Presubmit
public class UserBackupManagerServiceTest {
    private static final String TAG = "BMSTest";
    private static final String PACKAGE_1 = "some.package.1";
    private static final String PACKAGE_2 = "some.package.2";
    private static final String USER_FACING_PACKAGE = "user.facing.package";
    private static final int USER_ID = 10;

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
     * Initialize state that {@link UserBackupManagerService} operations interact with. This
     * includes setting up the transport, starting the backup thread, and creating backup data
     * directories.
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
     * Clean up and reset state that was created for testing {@link UserBackupManagerService}
     * operations.
     */
    @After
    public void tearDown() throws Exception {
        mBackupThread.quit();
        ShadowAppBackupUtils.reset();
        ShadowApplicationPackageManager.reset();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#getDestinationString(String)} returns the
     * current destination string of inputted transport if the transport is registered.
     */
    @Test
    public void testDestinationString() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenReturn("destinationString");
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        String destination = backupManagerService.getDestinationString(mTransportName);

        assertThat(destination).isEqualTo("destinationString");
    }

    /**
     * Test verifying that {@link UserBackupManagerService#getDestinationString(String)} returns
     * {@code null} if the inputted transport is not registered.
     */
    @Test
    public void testDestinationString_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenThrow(TransportNotRegisteredException.class);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        String destination = backupManagerService.getDestinationString(mTransportName);

        assertThat(destination).isNull();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#getDestinationString(String)} throws a
     * {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testDestinationString_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getTransportCurrentDestinationString(eq(mTransportName)))
                .thenThrow(TransportNotRegisteredException.class);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.getDestinationString(mTransportName));
    }

    /**
     * Test verifying that {@link UserBackupManagerService#isAppEligibleForBackup(String)} returns
     * {@code false} when the given app is not eligible for backup.
     */
    @Test
    public void testIsAppEligibleForBackup_whenAppNotEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        registerPackages(PACKAGE_1);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        boolean result = backupManagerService.isAppEligibleForBackup(PACKAGE_1);

        assertThat(result).isFalse();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#isAppEligibleForBackup(String)} returns
     * {@code true} when the given app is eligible for backup.
     */
    @Test
    public void testIsAppEligibleForBackup_whenAppEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpCurrentTransport(mTransportManager, backupTransport());
        registerPackages(PACKAGE_1);
        ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(PACKAGE_1);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        boolean result = backupManagerService.isAppEligibleForBackup(PACKAGE_1);

        assertThat(result).isTrue();
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
    }

    /**
     * Test verifying that {@link UserBackupManagerService#isAppEligibleForBackup(String)} throws a
     * {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testIsAppEligibleForBackup_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        registerPackages(PACKAGE_1);
        ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(PACKAGE_1);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.isAppEligibleForBackup(PACKAGE_1));
    }

    /**
     * Test verifying that {@link UserBackupManagerService#filterAppsEligibleForBackup(String[])}
     * returns an {@code array} of only apps that are eligible for backup from an {@array} of
     * inputted apps.
     */
    @Test
    public void testFilterAppsEligibleForBackup() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpCurrentTransport(mTransportManager, mTransport);
        registerPackages(PACKAGE_1, PACKAGE_2);
        ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(PACKAGE_1);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        String[] filtered =
                backupManagerService.filterAppsEligibleForBackup(
                        new String[] {PACKAGE_1, PACKAGE_2});

        assertThat(filtered).asList().containsExactly(PACKAGE_1);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
    }

    /**
     * Test verifying that {@link UserBackupManagerService#filterAppsEligibleForBackup(String[])}
     * returns an empty {@code array} if no inputted apps are eligible for backup.
     */
    @Test
    public void testFilterAppsEligibleForBackup_whenNoneIsEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        registerPackages(PACKAGE_1, PACKAGE_2);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        String[] filtered =
                backupManagerService.filterAppsEligibleForBackup(
                        new String[] {PACKAGE_1, PACKAGE_2});

        assertThat(filtered).isEmpty();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#filterAppsEligibleForBackup(String[])}
     * throws a {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testFilterAppsEligibleForBackup_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        registerPackages(PACKAGE_1, PACKAGE_2);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#selectBackupTransport(String)}
     * successfully switches the current transport to the inputted transport, returns the name of
     * the old transport, and disposes of the transport client after the operation.
     */
    @Test
    public void testSelectBackupTransport() throws Exception {
        setUpForSelectTransport();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        String oldTransport =
                backupManagerService.selectBackupTransport(mNewTransport.transportName);

        assertThat(getSettingsTransport()).isEqualTo(mNewTransport.transportName);
        assertThat(oldTransport).isEqualTo(mOldTransport.transportName);
        verify(mTransportManager)
                .disposeOfTransportClient(eq(mNewTransportMock.transportClient), any());
    }

    /**
     * Test verifying that {@link UserBackupManagerService#selectBackupTransport(String)} throws a
     * {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testSelectBackupTransport_withoutPermission() throws Exception {
        setUpForSelectTransport();
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.selectBackupTransport(mNewTransport.transportName));
    }

    /**
     * Test verifying that {@link UserBackupManagerService#selectBackupTransportAsync(ComponentName,
     * ISelectBackupTransportCallback)} successfully switches the current transport to the inputted
     * transport and disposes of the transport client after the operation.
     */
    @Test
    public void testSelectBackupTransportAsync() throws Exception {
        setUpForSelectTransport();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.registerAndSelectTransport(eq(mNewTransportComponent)))
                .thenReturn(BackupManager.SUCCESS);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(mNewTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isEqualTo(mNewTransport.transportName);
        verify(callback).onSuccess(eq(mNewTransport.transportName));
        verify(mTransportManager)
                .disposeOfTransportClient(eq(mNewTransportMock.transportClient), any());
    }

    /**
     * Test verifying that {@link UserBackupManagerService#selectBackupTransportAsync(ComponentName,
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
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(mNewTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isNotEqualTo(mNewTransport.transportName);
        verify(callback).onFailure(anyInt());
    }

    /**
     * Test verifying that {@link UserBackupManagerService#selectBackupTransportAsync(ComponentName,
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
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);

        backupManagerService.selectBackupTransportAsync(newTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isNotEqualTo(mTransportName);
        verify(callback).onFailure(anyInt());
    }

    /**
     * Test verifying that {@link UserBackupManagerService#selectBackupTransportAsync(ComponentName,
     * ISelectBackupTransportCallback)} throws a {@link SecurityException} if the caller does not
     * have backup permission.
     */
    @Test
    public void testSelectBackupTransportAsync_withoutPermission() throws Exception {
        setUpForSelectTransport();
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        ComponentName newTransportComponent = mNewTransport.getTransportComponent();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.selectBackupTransportAsync(
                                newTransportComponent, mock(ISelectBackupTransportCallback.class)));
    }

    private String getSettingsTransport() {
        return Settings.Secure.getString(
                mContext.getContentResolver(), Settings.Secure.BACKUP_TRANSPORT);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#getCurrentTransportComponent()} returns
     * the {@link ComponentName} of the currently selected transport.
     */
    @Test
    public void testGetCurrentTransportComponent() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent())
                .thenReturn(mTransport.getTransportComponent());
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isEqualTo(mTransport.getTransportComponent());
    }

    /**
     * Test verifying that {@link UserBackupManagerService#getCurrentTransportComponent()} returns
     * {@code null} if there is no currently selected transport.
     */
    @Test
    public void testGetCurrentTransportComponent_whenNoTransportSelected() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent()).thenReturn(null);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isNull();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#getCurrentTransportComponent()} returns
     * {@code null} if the currently selected transport is not registered.
     */
    @Test
    public void testGetCurrentTransportComponent_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.getCurrentTransportComponent())
                .thenThrow(TransportNotRegisteredException.class);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        ComponentName transportComponent = backupManagerService.getCurrentTransportComponent();

        assertThat(transportComponent).isNull();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#getCurrentTransportComponent()} throws a
     * {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testGetCurrentTransportComponent_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(SecurityException.class, backupManagerService::getCurrentTransportComponent);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#excludeKeysFromRestore(String, List)}
     * throws a {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testExcludeKeysFromRestore_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(
                SecurityException.class,
                () ->
                        backupManagerService.excludeKeysFromRestore(
                                PACKAGE_1,
                                new ArrayList<String>(){}));
    }

    /* Tests for updating transport attributes */

    private static final int PACKAGE_UID = 10;
    private ComponentName mTransportComponent;
    private int mTransportUid;

    private void setUpForUpdateTransportAttributes() throws Exception {
        mTransportComponent = mTransport.getTransportComponent();
        String transportPackage = mTransportComponent.getPackageName();
        PackageInfo packageInfo = getPackageInfo(transportPackage);

        ShadowPackageManager shadowPackageManager = shadowOf(mContext.getPackageManager());
        shadowPackageManager.installPackage(packageInfo);
        shadowPackageManager.setPackagesForUid(PACKAGE_UID, transportPackage);
        // Set up for user invocations on ApplicationPackageManager.
        ShadowApplicationPackageManager.addInstalledPackage(transportPackage, packageInfo);
        ShadowApplicationPackageManager.setPackageUid(transportPackage, PACKAGE_UID);

        mTransportUid = mContext.getPackageManager().getPackageUid(transportPackage, 0);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} succeeds if the uid of the transport
     * is same as the uid of the caller.
     */
    @Test
    public void
            testUpdateTransportAttributes_whenTransportUidEqualsCallingUid_callsTransportManager()
                    throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} throws a {@link SecurityException} if
     * the uid of the transport is not equal to the uid of the caller.
     */
    @Test
    public void testUpdateTransportAttributes_whenTransportUidNotEqualToCallingUid_throwsException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} throws a {@link RuntimeException} if
     * given a {@code null} transport component.
     */
    @Test
    public void testUpdateTransportAttributes_whenTransportComponentNull_throwsException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} throws a {@link RuntimeException} if
     * given a {@code null} transport name.
     */
    @Test
    public void testUpdateTransportAttributes_whenNameNull_throwsException() throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} throws a {@link RuntimeException} if
     * given a {@code null} destination string.
     */
    @Test
    public void testUpdateTransportAttributes_whenCurrentDestinationStringNull_throwsException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} throws a {@link RuntimeException} if
     * given either a {@code null} data management label or {@code null} data management intent, but
     * not both.
     */
    @Test
    public void
            testUpdateTransportAttributes_whenDataManagementArgsNullityDontMatch_throwsException()
                    throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} succeeds if the caller has backup
     * permission.
     */
    @Test
    public void testUpdateTransportAttributes_whenPermissionGranted_callsThroughToTransportManager()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        Intent configurationIntent = new Intent();
        Intent dataManagementIntent = new Intent();
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
     * Test verifying that {@link UserBackupManagerService#updateTransportAttributes(int,
     * ComponentName, String, Intent, String, Intent, String)} throws a {@link SecurityException} if
     * the caller does not have backup permission.
     */
    @Test
    public void testUpdateTransportAttributes_whenPermissionDenied_throwsSecurityException()
            throws Exception {
        setUpForUpdateTransportAttributes();
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

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
            registerPackages(packageName);
            ShadowAppBackupUtils.setAppRunningAndEligibleForBackupWithTransport(packageName);
        }
        setUpCurrentTransport(mTransportManager, mTransport);
    }

    private void tearDownForRequestBackup() {
        ShadowKeyValueBackupTask.reset();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} throws a {@link SecurityException} if the caller does not have backup permission.
     */
    @Test
    public void testRequestBackup_whenPermissionDenied() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0));
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} throws an {@link IllegalArgumentException} if passed {@null} for packages.
     */
    @Test
    public void testRequestBackup_whenPackagesNull() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(
                IllegalArgumentException.class,
                () -> backupManagerService.requestBackup(null, mObserver, 0));
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} throws an {@link IllegalArgumentException} if passed an empty {@code array} for
     * packages.
     */
    @Test
    public void testRequestBackup_whenPackagesEmpty() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();

        expectThrows(
                IllegalArgumentException.class,
                () -> backupManagerService.requestBackup(new String[0], mObserver, 0));
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#ERROR_BACKUP_NOT_ALLOWED} if backup is disabled.
     */
    @Test
    public void testRequestBackup_whenBackupDisabled() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        backupManagerService.setEnabled(false);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#ERROR_BACKUP_NOT_ALLOWED} if the system user hasn't gone
     * through SUW.
     */
    @Test
    public void testRequestBackup_whenNotProvisioned() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        backupManagerService.setSetupComplete(false);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        verify(mObserver).backupFinished(BackupManager.ERROR_BACKUP_NOT_ALLOWED);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#ERROR_TRANSPORT_ABORTED} if the current transport is not
     * registered.
     */
    @Test
    public void testRequestBackup_whenTransportNotRegistered() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport.unregistered());
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        backupManagerService.setEnabled(true);
        backupManagerService.setSetupComplete(true);

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.ERROR_TRANSPORT_ABORTED);
        verify(mObserver).backupFinished(BackupManager.ERROR_TRANSPORT_ABORTED);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#SUCCESS} and notifies the observer of {@link
     * BackupManager#ERROR_BACKUP_NOT_ALLOWED} if the specified app is not eligible for backup.
     */
    @Test
    public void testRequestBackup_whenAppNotEligibleForBackup() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        registerPackages(PACKAGE_1);
        setUpCurrentTransport(mTransportManager, mTransport);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        backupManagerService.setEnabled(true);
        backupManagerService.setSetupComplete(true);
        // Haven't set PACKAGE_1 as eligible

        int result = backupManagerService.requestBackup(new String[] {PACKAGE_1}, mObserver, 0);

        assertThat(result).isEqualTo(BackupManager.SUCCESS);
        verify(mObserver).onResult(PACKAGE_1, BackupManager.ERROR_BACKUP_NOT_ALLOWED);
        // TODO: We probably don't need to kick-off KeyValueBackupTask when list is empty
        tearDownForRequestBackup();
    }

    /**
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#SUCCESS} and updates bookkeeping if backup for a key value
     * package succeeds.
     */
    @Test
    @Config(shadows = ShadowKeyValueBackupTask.class)
    public void testRequestBackup_whenPackageIsKeyValue() throws Exception {
        setUpForRequestBackup(PACKAGE_1);
        UserBackupManagerService backupManagerService =
                createBackupManagerServiceForRequestBackup();

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
     * Test verifying that {@link UserBackupManagerService#requestBackup(String[], IBackupObserver,
     * int)} returns {@link BackupManager#SUCCESS} and updates bookkeeping if backup for a full
     * backup package succeeds.
     */
    @Test
    @Config(shadows = ShadowKeyValueBackupTask.class)
    public void testRequestBackup_whenPackageIsFullBackup() throws Exception {
        setUpForRequestBackup(PACKAGE_1);
        ShadowAppBackupUtils.setAppGetsFullBackup(PACKAGE_1);
        UserBackupManagerService backupManagerService =
                createBackupManagerServiceForRequestBackup();

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
     * Test verifying that {@link UserBackupManagerService#backupNow()} clears the calling identity
     * for scheduling a job and then restores the original calling identity after the operation.
     */
    @Test
    @Config(shadows = {ShadowBinder.class, ShadowKeyValueBackupJob.class})
    public void testBackupNow_clearsCallingIdentityForJobScheduler() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        setUpPowerManager(backupManagerService);
        ShadowBinder.setCallingUid(1);

        backupManagerService.backupNow();

        assertThat(ShadowKeyValueBackupJob.getCallingUid()).isEqualTo(ShadowBinder.LOCAL_UID);
        assertThat(Binder.getCallingUid()).isEqualTo(1);
    }

    /**
     * Test verifying that {@link UserBackupManagerService#backupNow()} restores the original
     * calling identity if an exception is thrown during execution.
     */
    @Test
    @Config(shadows = {ShadowBinder.class, ShadowKeyValueBackupJobException.class})
    public void testBackupNow_whenExceptionThrown_restoresCallingIdentity() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        setUpPowerManager(backupManagerService);
        ShadowBinder.setCallingUid(1);

        expectThrows(IllegalArgumentException.class, backupManagerService::backupNow);
        assertThat(ShadowKeyValueBackupJobException.getCallingUid())
                .isEqualTo(ShadowBinder.LOCAL_UID);
        assertThat(Binder.getCallingUid()).isEqualTo(1);
    }

    private UserBackupManagerService createBackupManagerServiceForRequestBackup() {
        UserBackupManagerService backupManagerService = createUserBackupManagerServiceAndRunTasks();
        backupManagerService.setEnabled(true);
        backupManagerService.setSetupComplete(true);
        return backupManagerService;
    }

    /**
     * Test verifying that creating a new instance posts a transport registration task to the backup
     * thread.
     */
    @Test
    public void testCreateAndInitializeService_postRegisterTransports() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);

        UserBackupManagerService.createAndInitializeService(
                USER_ID,
                mContext,
                new BackupManagerService(mContext),
                mBackupThread,
                mBaseStateDir,
                mDataDir,
                mTransportManager);

        mShadowBackupLooper.runToEndOfTasks();
        verify(mTransportManager).registerTransports();
    }

    /**
     * Test verifying that creating a new instance does not directly register transports on the main
     * thread.
     */
    @Test
    public void testCreateAndInitializeService_doesNotRegisterTransportsSynchronously() {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);

        UserBackupManagerService.createAndInitializeService(
                USER_ID,
                mContext,
                new BackupManagerService(mContext),
                mBackupThread,
                mBaseStateDir,
                mDataDir,
                mTransportManager);

        // Operations posted to mBackupThread only run with mShadowBackupLooper.runToEndOfTasks()
        verify(mTransportManager, never()).registerTransports();
    }

    /** Test checking non-null argument on instance creation. */
    @Test
    public void testCreateAndInitializeService_withNullContext_throws() {
        expectThrows(
                NullPointerException.class,
                () ->
                        UserBackupManagerService.createAndInitializeService(
                                USER_ID,
                                /* context */ null,
                                new BackupManagerService(mContext),
                                mBackupThread,
                                mBaseStateDir,
                                mDataDir,
                                mTransportManager));
    }

    /** Test checking non-null argument on instance creation. */
    @Test
    public void testCreateAndInitializeService_withNullTrampoline_throws() {
        expectThrows(
                NullPointerException.class,
                () ->
                        UserBackupManagerService.createAndInitializeService(
                                USER_ID,
                                mContext,
                                /* trampoline */ null,
                                mBackupThread,
                                mBaseStateDir,
                                mDataDir,
                                mTransportManager));
    }

    /** Test checking non-null argument on instance creation. */
    @Test
    public void testCreateAndInitializeService_withNullBackupThread_throws() {
        expectThrows(
                NullPointerException.class,
                () ->
                        UserBackupManagerService.createAndInitializeService(
                                USER_ID,
                                mContext,
                                new BackupManagerService(mContext),
                                /* backupThread */ null,
                                mBaseStateDir,
                                mDataDir,
                                mTransportManager));
    }

    /** Test checking non-null argument on instance creation. */
    @Test
    public void testCreateAndInitializeService_withNullStateDir_throws() {
        expectThrows(
                NullPointerException.class,
                () ->
                        UserBackupManagerService.createAndInitializeService(
                                USER_ID,
                                mContext,
                                new BackupManagerService(mContext),
                                mBackupThread,
                                /* baseStateDir */ null,
                                mDataDir,
                                mTransportManager));
    }

    /**
     * Test checking non-null argument on {@link
     * UserBackupManagerService#createAndInitializeService(int, Context, BackupManagerService,
     * HandlerThread, File, File, TransportManager)}.
     */
    @Test
    public void testCreateAndInitializeService_withNullDataDir_throws() {
        expectThrows(
                NullPointerException.class,
                () ->
                        UserBackupManagerService.createAndInitializeService(
                                USER_ID,
                                mContext,
                                new BackupManagerService(mContext),
                                mBackupThread,
                                mBaseStateDir,
                                /* dataDir */ null,
                                mTransportManager));
    }

    /**
     * Test checking non-null argument on {@link
     * UserBackupManagerService#createAndInitializeService(int, Context, BackupManagerService,
     * HandlerThread, File, File, TransportManager)}.
     */
    @Test
    public void testCreateAndInitializeService_withNullTransportManager_throws() {
        expectThrows(
                NullPointerException.class,
                () ->
                        UserBackupManagerService.createAndInitializeService(
                                USER_ID,
                                mContext,
                                new BackupManagerService(mContext),
                                mBackupThread,
                                mBaseStateDir,
                                mDataDir,
                                /* transportManager */ null));
    }

    /**
     * Test verifying that creating a new instance registers the broadcast receiver for package
     * tracking
     */
    @Test
    public void testCreateAndInitializeService_registersPackageTrackingReceiver() throws Exception {
        Context contextSpy = Mockito.spy(mContext);

        UserBackupManagerService service = UserBackupManagerService.createAndInitializeService(
                USER_ID,
                contextSpy,
                new BackupManagerService(mContext),
                mBackupThread,
                mBaseStateDir,
                mDataDir,
                mTransportManager);

        BroadcastReceiver packageTrackingReceiver = service.getPackageTrackingReceiver();
        assertThat(packageTrackingReceiver).isNotNull();

        // One call for package changes and one call for sd card events.
        verify(contextSpy, times(2)).registerReceiverAsUser(
                eq(packageTrackingReceiver), eq(UserHandle.of(USER_ID)), any(), any(), any());
    }

    @Test
    public void testFilterUserFacingPackages_shouldSkipUserFacing_filtersUserFacing() {
        List<PackageInfo> packages = Arrays.asList(getPackageInfo(USER_FACING_PACKAGE),
                getPackageInfo(PACKAGE_1));
        UserBackupManagerService backupManagerService = spy(
                createUserBackupManagerServiceAndRunTasks());
        when(backupManagerService.shouldSkipUserFacingData()).thenReturn(true);
        when(backupManagerService.shouldSkipPackage(eq(USER_FACING_PACKAGE))).thenReturn(true);

        List<PackageInfo> filteredPackages = backupManagerService.filterUserFacingPackages(
                packages);

        assertFalse(containsPackage(filteredPackages, USER_FACING_PACKAGE));
        assertTrue(containsPackage(filteredPackages, PACKAGE_1));
    }

    @Test
    public void testFilterUserFacingPackages_shouldNotSkipUserFacing_doesNotFilterUserFacing() {
        List<PackageInfo> packages = Arrays.asList(getPackageInfo(USER_FACING_PACKAGE),
                getPackageInfo(PACKAGE_1));
        UserBackupManagerService backupManagerService = spy(
                createUserBackupManagerServiceAndRunTasks());
        when(backupManagerService.shouldSkipUserFacingData()).thenReturn(false);
        when(backupManagerService.shouldSkipPackage(eq(USER_FACING_PACKAGE))).thenReturn(true);

        List<PackageInfo> filteredPackages = backupManagerService.filterUserFacingPackages(
                packages);

        assertTrue(containsPackage(filteredPackages, USER_FACING_PACKAGE));
        assertTrue(containsPackage(filteredPackages, PACKAGE_1));
    }

    private static boolean containsPackage(List<PackageInfo> packages, String targetPackage) {
        for (PackageInfo packageInfo : packages) {
            if (targetPackage.equals(packageInfo.packageName)) {
                return true;
            }
        }
        return false;
    }

    private UserBackupManagerService createUserBackupManagerServiceAndRunTasks() {
        return BackupManagerServiceTestUtils.createUserBackupManagerServiceAndRunTasks(
                USER_ID, mContext, mBackupThread, mBaseStateDir, mDataDir, mTransportManager);
    }

    private void setUpPowerManager(UserBackupManagerService backupManagerService) {
        PowerManager powerManagerMock = mock(PowerManager.class);
        when(powerManagerMock.getPowerSaveState(anyInt()))
                .thenReturn(new PowerSaveState.Builder().setBatterySaverEnabled(true).build());
        backupManagerService.setPowerManager(powerManagerMock);
    }

    private PackageInfo getPackageInfo(String packageName) {
        PackageInfo packageInfo = new PackageInfo();
        packageInfo.packageName = packageName;
        packageInfo.applicationInfo = new ApplicationInfo();
        packageInfo.applicationInfo.packageName = packageName;
        return packageInfo;
    }

    private void registerPackages(String... packages) {
        for (String packageName : packages) {
            PackageInfo packageInfo = getPackageInfo(packageName);
            mShadowPackageManager.installPackage(packageInfo);
            ShadowApplicationPackageManager.addInstalledPackage(packageName, packageInfo);
        }
    }

    /**
     * Test that {@link UserBackupManagerService#getAncestralSerialNumber()} returns {@code -1}
     * when value not set.
     */
    @Test
    public void testGetAncestralSerialNumber_notSet_returnsMinusOne() {
        UserBackupManagerService service = createUserBackupManagerServiceAndRunTasks();

        assertThat(service.getAncestralSerialNumber()).isEqualTo(-1L);
    }

    /**
     * Test that {@link UserBackupManagerService#getAncestralSerialNumber()} returns correct value
     * when value set.
     */
    @Test
    public void testGetAncestralSerialNumber_set_returnsCorrectValue() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService service = createUserBackupManagerServiceAndRunTasks();
        service.setAncestralSerialNumberFile(createTestFile());

        long testSerialNumber = 20L;
        service.setAncestralSerialNumber(testSerialNumber);

        assertThat(service.getAncestralSerialNumber()).isEqualTo(testSerialNumber);
    }

    /**
     * Test that {@link UserBackupManagerService#getAncestralSerialNumber()} returns correct value
     * when value set.
     */
    @Test
    public void testGetAncestralSerialNumber_setTwice_returnsCorrectValue() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService service = createUserBackupManagerServiceAndRunTasks();
        service.setAncestralSerialNumberFile(createTestFile());

        long testSerialNumber = 20L;
        long testSerialNumber2 = 21L;
        service.setAncestralSerialNumber(testSerialNumber);
        service.setAncestralSerialNumber(testSerialNumber2);

        assertThat(service.getAncestralSerialNumber()).isEqualTo(testSerialNumber2);
    }

    /**
     * Test that {@link UserBackupManagerService#dump()} for system user does not prefix dump with
     * "User 0:".
     */
    @Test
    public void testDump_forSystemUser_DoesNotHaveUserPrefix() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService service =
                BackupManagerServiceTestUtils.createUserBackupManagerServiceAndRunTasks(
                        UserHandle.USER_SYSTEM,
                        mContext,
                        mBackupThread,
                        mBaseStateDir,
                        mDataDir,
                        mTransportManager);

        StringWriter dump = new StringWriter();
        service.dump(new FileDescriptor(), new PrintWriter(dump), new String[0]);

        assertThat(dump.toString()).startsWith("Backup Manager is ");
    }

    /**
     * Test that {@link UserBackupManagerService#dump()} for non-system user prefixes dump with
     * "User <userid>:".
     */
    @Test
    public void testDump_forNonSystemUser_HasUserPrefix() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        UserBackupManagerService service = createUserBackupManagerServiceAndRunTasks();

        StringWriter dump = new StringWriter();
        service.dump(new FileDescriptor(), new PrintWriter(dump), new String[0]);

        assertThat(dump.toString()).startsWith("User " + USER_ID + ":" + "Backup Manager is ");
    }

    private File createTestFile() throws IOException {
        File testFile = new File(mContext.getFilesDir(), "test");
        testFile.createNewFile();
        return testFile;
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
        public static void schedule(int userId, Context ctx, long delay,
                BackupManagerConstants constants) {
            ShadowKeyValueBackupJob.schedule(userId, ctx, delay, constants);
            throw new IllegalArgumentException();
        }
    }
}
