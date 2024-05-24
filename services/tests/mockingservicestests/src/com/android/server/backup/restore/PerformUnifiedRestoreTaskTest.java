/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup.restore;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupTransport;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Message;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.modules.utils.testing.TestableDeviceConfig;
import com.android.server.backup.Flags;
import com.android.server.backup.UserBackupManagerService;
import com.android.server.backup.internal.BackupHandler;
import com.android.server.backup.transport.BackupTransportClient;
import com.android.server.backup.transport.TransportConnection;
import com.android.server.backup.transport.TransportNotAvailableException;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class PerformUnifiedRestoreTaskTest {
    private static final String PACKAGE_NAME = "package";
    private static final String INCLUDED_KEY = "included_key";
    private static final String EXCLUDED_KEY_1 = "excluded_key_1";
    private static final String EXCLUDED_KEY_2 = "excluded_key_2";
    private static final String SYSTEM_PACKAGE_NAME = "android";
    private static final String NON_SYSTEM_PACKAGE_NAME = "package";

    private static final String V_TO_U_ALLOWLIST = "pkg1";
    private static final String V_TO_U_DENYLIST = "pkg2";

    @Mock
    private BackupDataInput mBackupDataInput;
    @Mock
    private BackupDataOutput mBackupDataOutput;
    @Mock
    private UserBackupManagerService mBackupManagerService;
    @Mock
    private TransportConnection mTransportConnection;

    private Set<String> mExcludedkeys = new HashSet<>();
    private Map<String, String> mBackupData = new HashMap<>();
    // Mock BackupDataInput reads backup data from here.
    private Queue<String> mBackupDataSource;
    // Mock BackupDataOutput will write backup data here.
    private Set<String> mBackupDataDump;
    private PerformUnifiedRestoreTask mRestoreTask;

    @Rule
    public TestableDeviceConfig.TestableDeviceConfigRule mDeviceConfigRule =
            new TestableDeviceConfig.TestableDeviceConfigRule();

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();


    private Context mContext;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        populateTestData();

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mBackupDataSource = new ArrayDeque<>(mBackupData.keySet());
        when(mBackupDataInput.readNextHeader())
                .then((Answer<Boolean>) invocation -> !mBackupDataSource.isEmpty());
        when(mBackupDataInput.getKey())
                .then((Answer<String>) invocation -> mBackupDataSource.poll());
        when(mBackupDataInput.getDataSize()).thenReturn(0);

        mBackupDataDump = new HashSet<>();
        ArgumentCaptor<String> keyCaptor = ArgumentCaptor.forClass(String.class);
        when(mBackupDataOutput.writeEntityHeader(keyCaptor.capture(), anyInt()))
                .then(
                        (Answer<Void>)
                                invocation -> {
                                    mBackupDataDump.add(keyCaptor.getValue());
                                    return null;
                                });

        mRestoreTask = new PerformUnifiedRestoreTask(mBackupManagerService, mTransportConnection,
                V_TO_U_ALLOWLIST, V_TO_U_DENYLIST);
    }

    private void populateTestData() {
        mBackupData = new HashMap<>();
        mBackupData.put(INCLUDED_KEY, "1");
        mBackupData.put(EXCLUDED_KEY_1, "2");
        mBackupData.put(EXCLUDED_KEY_2, "3");

        mExcludedkeys = new HashSet<>();
        mExcludedkeys.add(EXCLUDED_KEY_1);
        mExcludedkeys.add(EXCLUDED_KEY_2);
    }

    @Test
    public void testFilterExcludedKeys() throws Exception {
        when(mBackupManagerService.getExcludedRestoreKeys(eq(PACKAGE_NAME)))
                .thenReturn(mExcludedkeys);

        mRestoreTask.filterExcludedKeys(PACKAGE_NAME, mBackupDataInput, mBackupDataOutput);

        // Verify only the correct were written into BackupDataOutput object.
        Set<String> allowedBackupKeys = new HashSet<>(mBackupData.keySet());
        allowedBackupKeys.removeAll(mExcludedkeys);
        assertEquals(allowedBackupKeys, mBackupDataDump);
    }

    @Test
    public void testGetExcludedKeysForPackage_alwaysReturnsLatestKeys() {
        Set<String> firstExcludedKeys = new HashSet<>(Collections.singletonList(EXCLUDED_KEY_1));
        when(mBackupManagerService.getExcludedRestoreKeys(eq(PACKAGE_NAME)))
                .thenReturn(firstExcludedKeys);
        assertEquals(firstExcludedKeys, mRestoreTask.getExcludedKeysForPackage(PACKAGE_NAME));

        Set<String> secondExcludedKeys =
                new HashSet<>(Arrays.asList(EXCLUDED_KEY_1, EXCLUDED_KEY_2));
        when(mBackupManagerService.getExcludedRestoreKeys(eq(PACKAGE_NAME)))
                .thenReturn(secondExcludedKeys);
        assertEquals(secondExcludedKeys, mRestoreTask.getExcludedKeysForPackage(PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_stageForNonSystemPackageWithKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(eq(NON_SYSTEM_PACKAGE_NAME)))
                .thenReturn(mExcludedkeys);

        assertTrue(mRestoreTask.shouldStageBackupData(NON_SYSTEM_PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_stageForNonSystemPackageWithNoKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(any()))
                .thenReturn(Collections.emptySet());

        assertTrue(mRestoreTask.shouldStageBackupData(NON_SYSTEM_PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_doNotStageForSystemPackageWithNoKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(any()))
                .thenReturn(Collections.emptySet());

        assertFalse(mRestoreTask.shouldStageBackupData(SYSTEM_PACKAGE_NAME));
    }

    @Test
    public void testStageBackupData_stageForSystemPackageWithKeysToExclude() {
        when(mBackupManagerService.getExcludedRestoreKeys(eq(SYSTEM_PACKAGE_NAME)))
                .thenReturn(mExcludedkeys);

        assertTrue(mRestoreTask.shouldStageBackupData(SYSTEM_PACKAGE_NAME));
    }

    @Test
    public void testFailedKeyValueRestore_continueAfterFeatureEnabled_nextStateIsRunningQueue()
            throws TransportNotAvailableException, RemoteException {
        DeviceConfig.setProperty(
                "backup_and_restore",
                "unified_restore_continue_after_transport_failure_in_kv_restore",
                "true",
                false);

        setupForRestoreKeyValueState(BackupTransport.TRANSPORT_ERROR);

        mRestoreTask.setCurrentUnifiedRestoreStateForTesting(UnifiedRestoreState.RESTORE_KEYVALUE);
        mRestoreTask.setStateDirForTesting(mContext.getCacheDir());

        PackageInfo testPackageInfo = new PackageInfo();
        testPackageInfo.packageName = "test.package.name";
        mRestoreTask.initiateOneRestoreForTesting(testPackageInfo, 0L);
        assertTrue(
                mRestoreTask.getCurrentUnifiedRestoreStateForTesting()
                        == UnifiedRestoreState.RUNNING_QUEUE);
    }

    @Test
    public void testFailedKeyValueRestore_continueAfterFeatureDisabled_nextStateIsFinal()
            throws RemoteException, TransportNotAvailableException {
        DeviceConfig.setProperty(
                "backup_and_restore",
                "unified_restore_continue_after_transport_failure_in_kv_restore",
                "false",
                false);

        setupForRestoreKeyValueState(BackupTransport.TRANSPORT_ERROR);

        mRestoreTask.setCurrentUnifiedRestoreStateForTesting(UnifiedRestoreState.RESTORE_KEYVALUE);
        mRestoreTask.setStateDirForTesting(mContext.getCacheDir());

        PackageInfo testPackageInfo = new PackageInfo();
        testPackageInfo.packageName = "test.package.name";
        mRestoreTask.initiateOneRestoreForTesting(testPackageInfo, 0L);
        assertTrue(
                mRestoreTask.getCurrentUnifiedRestoreStateForTesting()
                        == UnifiedRestoreState.FINAL);
    }

    @Test
    public void testCreateVToUList_listSettingIsNull_returnEmptyList() {
        List<String> expectedEmptyList = new ArrayList<>();

        List<String> list = mRestoreTask.createVToUList(null);

        assertEquals(list, expectedEmptyList);
    }

    @Test
    public void testCreateVToUList_listIsNotNull_returnCorrectList() {
        List<String> expectedList = Arrays.asList("a", "b", "c");
        String listString = "a,b,c";

        List<String> list = mRestoreTask.createVToUList(listString);

        assertEquals(list, expectedList);
    }

    @Test
    public void testIsVToUDowngrade_vToUFlagIsOffAndTargetIsUSourceIsV_returnFalse() {
        mSetFlagsRule.disableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        boolean isVToUDowngrade = mRestoreTask.isVToUDowngrade(
                Build.VERSION_CODES.VANILLA_ICE_CREAM, Build.VERSION_CODES.UPSIDE_DOWN_CAKE);

        assertFalse(isVToUDowngrade);
    }

    @Test
    public void testIsVToUDowngrade_vToUFlagIsOnAndTargetIsUSourceIsV_returnTrue() {
        mSetFlagsRule.enableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        boolean isVToUDowngrade = mRestoreTask.isVToUDowngrade(
                Build.VERSION_CODES.VANILLA_ICE_CREAM, Build.VERSION_CODES.UPSIDE_DOWN_CAKE);

        assertTrue(isVToUDowngrade);
    }

    @Test
    public void testIsVToUDowngrade_vToUFlagIsOnAndSourceIsNotV_returnFalse() {
        mSetFlagsRule.enableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        boolean isVToUDowngrade = mRestoreTask.isVToUDowngrade(Build.VERSION_CODES.UPSIDE_DOWN_CAKE,
                Build.VERSION_CODES.UPSIDE_DOWN_CAKE);

        assertFalse(isVToUDowngrade);
    }

    @Test
    public void testIsVToUDowngrade_vToUFlagIsOnAndTargetIsNotU_returnFalse() {
        mSetFlagsRule.enableFlags(
                Flags.FLAG_ENABLE_V_TO_U_RESTORE_FOR_SYSTEM_COMPONENTS_IN_ALLOWLIST);

        boolean isVToUDowngrade = mRestoreTask.isVToUDowngrade(
                Build.VERSION_CODES.VANILLA_ICE_CREAM, Build.VERSION_CODES.VANILLA_ICE_CREAM);

        assertFalse(isVToUDowngrade);
    }


    @Test
    public void testIsEligibleForVToUDowngrade_pkgIsNotOnAllowlist_returnFalse() {
        PackageInfo testPackageInfo = new PackageInfo();
        testPackageInfo.packageName = "pkg";
        testPackageInfo.applicationInfo = new ApplicationInfo();
        // restoreAnyVersion flag is off
        testPackageInfo.applicationInfo.flags = 0;

        boolean eligibilityCriteria = mRestoreTask.isPackageEligibleForVToURestore(testPackageInfo);

        assertFalse(eligibilityCriteria);
    }

    @Test
    public void testIsEligibleForVToUDowngrade_pkgIsOnAllowlist_returnTrue() {
        PackageInfo testPackageInfo = new PackageInfo();
        testPackageInfo.packageName = "pkg1";
        testPackageInfo.applicationInfo = new ApplicationInfo();
        // restoreAnyVersion flag is off
        testPackageInfo.applicationInfo.flags = 0;

        boolean eligibilityCriteria = mRestoreTask.isPackageEligibleForVToURestore(testPackageInfo);

        assertTrue(eligibilityCriteria);
    }

    @Test
    public void testIsEligibleForVToUDowngrade_pkgIsNotOnDenyList_returnTrue() {
        PackageInfo testPackageInfo = new PackageInfo();
        testPackageInfo.packageName = "pkg";
        testPackageInfo.applicationInfo = new ApplicationInfo();
        // restoreAnyVersion flag is on
        testPackageInfo.applicationInfo.flags = ApplicationInfo.FLAG_RESTORE_ANY_VERSION;

        boolean eligibilityCriteria = mRestoreTask.isPackageEligibleForVToURestore(testPackageInfo);

        assertTrue(eligibilityCriteria);
    }

    @Test
    public void testIsEligibleForVToUDowngrade_pkgIsOnDenyList_returnFalse() {
        PackageInfo testPackageInfo = new PackageInfo();
        testPackageInfo.packageName = "pkg2";
        testPackageInfo.applicationInfo = new ApplicationInfo();
        // restoreAnyVersion flag is on
        testPackageInfo.applicationInfo.flags = ApplicationInfo.FLAG_RESTORE_ANY_VERSION;

        boolean eligibilityCriteria = mRestoreTask.isPackageEligibleForVToURestore(testPackageInfo);

        assertFalse(eligibilityCriteria);
    }

    private void setupForRestoreKeyValueState(int transportStatus)
            throws RemoteException, TransportNotAvailableException {
        // Mock BackupHandler to do nothing when executeNextState() is called
        BackupHandler backupHandler = Mockito.mock(BackupHandler.class);
        when(backupHandler.obtainMessage(anyInt(), any())).thenReturn(new Message());
        when(backupHandler.sendMessage(any())).thenReturn(true);

        // Return cache directory for any bookkeeping or maintaining persistent state.
        when(mBackupManagerService.getDataDir()).thenReturn(mContext.getCacheDir());
        when(mBackupManagerService.getBackupHandler()).thenReturn(backupHandler);

        BackupTransportClient transport = Mockito.mock(BackupTransportClient.class);
        when(transport.getRestoreData(any())).thenReturn(transportStatus);
        when(mTransportConnection.connectOrThrow(any())).thenReturn(transport);
    }
}
