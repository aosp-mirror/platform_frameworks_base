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

import static com.android.server.backup.testing.BackupManagerServiceTestUtils.startBackupThread;
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
import android.app.backup.ISelectBackupTransportCallback;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.os.HandlerThread;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import com.android.server.testing.shadows.ShadowAppBackupUtils;
import com.android.server.testing.shadows.ShadowBackupPolicyEnforcer;
import com.android.server.backup.testing.TransportData;
import com.android.server.backup.testing.TransportTestUtils.TransportMock;
import com.android.server.backup.transport.TransportNotRegisteredException;
import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderPackages;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowContextWrapper;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(
    manifest = Config.NONE,
    sdk = 26,
    shadows = {ShadowAppBackupUtils.class, ShadowBackupPolicyEnforcer.class}
)
@SystemLoaderPackages({"com.android.server.backup"})
@Presubmit
public class BackupManagerServiceTest {
    private static final String TAG = "BMSTest";

    @Mock private TransportManager mTransportManager;
    private HandlerThread mBackupThread;
    private ShadowLooper mShadowBackupLooper;
    private File mBaseStateDir;
    private File mDataDir;
    private ShadowContextWrapper mShadowContext;
    private Context mContext;
    private TransportData mTransport;
    private String mTransportName;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        mTransport = backupTransport();
        mTransportName = mTransport.transportName;

        mBackupThread = startBackupThread(this::uncaughtException);
        mShadowBackupLooper = shadowOf(mBackupThread.getLooper());

        ContextWrapper context = RuntimeEnvironment.application;
        mContext = context;
        mShadowContext = shadowOf(context);

        File cacheDir = mContext.getCacheDir();
        mBaseStateDir = new File(cacheDir, "base_state_dir");
        mDataDir = new File(cacheDir, "data_dir");

        ShadowBackupPolicyEnforcer.setMandatoryBackupTransport(null);
    }

    @After
    public void tearDown() throws Exception {
        mBackupThread.quit();
        ShadowAppBackupUtils.reset();
        ShadowBackupPolicyEnforcer.setMandatoryBackupTransport(null);
    }

    private void uncaughtException(Thread thread, Throwable e) {
        // Unrelated exceptions are thrown in the backup thread. Until we mock everything properly
        // we should not fail tests because of this. This is not flakiness, the exceptions thrown
        // don't interfere with the tests.
        ShadowLog.e(TAG, "Uncaught exception in test thread " + thread.getName(), e);
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
    public void testIsAppEligibleForBackup_whenAppEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpCurrentTransport(mTransportManager, backupTransport());
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = p -> true;
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        boolean result = backupManagerService.isAppEligibleForBackup("app.package");

        assertThat(result).isTrue();

        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
    }

    @Test
    public void testIsAppEligibleForBackup_whenAppNotEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = p -> false;
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        boolean result = backupManagerService.isAppEligibleForBackup("app.package");

        assertThat(result).isFalse();
    }

    @Test
    public void testIsAppEligibleForBackup_withoutPermission() throws Exception {
        mShadowContext.denyPermissions(android.Manifest.permission.BACKUP);
        setUpCurrentTransport(mTransportManager, mTransport);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        expectThrows(
                SecurityException.class,
                () -> backupManagerService.isAppEligibleForBackup("app.package"));
    }

    @Test
    public void testFilterAppsEligibleForBackup() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        TransportMock transportMock = setUpCurrentTransport(mTransportManager, mTransport);
        Map<String, Boolean> packagesMap = new HashMap<>();
        packagesMap.put("package.a", true);
        packagesMap.put("package.b", false);
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = packagesMap::get;
        BackupManagerService backupManagerService = createInitializedBackupManagerService();
        String[] packages = packagesMap.keySet().toArray(new String[packagesMap.size()]);

        String[] filtered = backupManagerService.filterAppsEligibleForBackup(packages);

        assertThat(filtered).asList().containsExactly("package.a");
        verify(mTransportManager)
                .disposeOfTransportClient(eq(transportMock.transportClient), any());
    }

    @Test
    public void testFilterAppsEligibleForBackup_whenNoneIsEligible() throws Exception {
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        ShadowAppBackupUtils.sAppIsRunningAndEligibleForBackupWithTransport = p -> false;
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        String[] filtered =
                backupManagerService.filterAppsEligibleForBackup(
                        new String[] {"package.a", "package.b"});

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
                                new String[] {"package.a", "package.b"}));
    }

    /* Tests for select transport */

    private ComponentName mNewTransportComponent;
    private TransportData mNewTransport;
    private TransportMock mNewTransportMock;
    private ComponentName mOldTransportComponent;
    private TransportData mOldTransport;
    private TransportMock mOldTransportMock;

    private void setUpForSelectTransport() throws Exception {
        mNewTransport = backupTransport();
        mNewTransportComponent = mNewTransport.getTransportComponent();
        mOldTransport = d2dTransport();
        mOldTransportComponent = mOldTransport.getTransportComponent();
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
    public void testSelectBackupTransportAsync_whenMandatoryTransport() throws Exception {
        setUpForSelectTransport();
        ShadowBackupPolicyEnforcer.setMandatoryBackupTransport(mNewTransportComponent);
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.registerAndSelectTransport(eq(mNewTransportComponent)))
                .thenReturn(BackupManager.SUCCESS);
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        backupManagerService.selectBackupTransportAsync(mNewTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isEqualTo(mNewTransport.transportName);
        verify(callback).onSuccess(eq(mNewTransport.transportName));
        verify(mTransportManager)
                .disposeOfTransportClient(eq(mNewTransportMock.transportClient), any());
    }

    @Test
    public void testSelectBackupTransportAsync_whenOtherThanMandatoryTransport() throws Exception {
        setUpForSelectTransport();
        ShadowBackupPolicyEnforcer.setMandatoryBackupTransport(mOldTransportComponent);
        mShadowContext.grantPermissions(android.Manifest.permission.BACKUP);
        when(mTransportManager.registerAndSelectTransport(eq(mNewTransportComponent)))
                .thenReturn(BackupManager.SUCCESS);
        ISelectBackupTransportCallback callback = mock(ISelectBackupTransportCallback.class);
        BackupManagerService backupManagerService = createInitializedBackupManagerService();

        backupManagerService.selectBackupTransportAsync(mNewTransportComponent, callback);

        mShadowBackupLooper.runToEndOfTasks();
        assertThat(getSettingsTransport()).isNotEqualTo(mNewTransport.transportName);
        verify(callback).onFailure(eq(BackupManager.ERROR_BACKUP_NOT_ALLOWED));
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
        BackupManagerService backupManagerService =
                new BackupManagerService(
                        mContext,
                        new Trampoline(mContext),
                        mBackupThread,
                        mBaseStateDir,
                        mDataDir,
                        mTransportManager);
        mShadowBackupLooper.runToEndOfTasks();
        // Handler instances have their own clock, so advancing looper (with runToEndOfTasks())
        // above does NOT advance the handlers' clock, hence whenever a handler post messages with
        // specific time to the looper the time of those messages will be before the looper's time.
        // To fix this we advance SystemClock as well since that is from where the handlers read
        // time.
        ShadowSystemClock.setCurrentTimeMillis(mShadowBackupLooper.getScheduler().getCurrentTime());
        return backupManagerService;
    }
}
