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

package com.android.providers.settings;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import static org.junit.Assert.assertArrayEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import android.app.backup.BackupAnnotations.BackupDestination;
import android.app.backup.BackupAnnotations.OperationType;
import android.app.backup.BackupDataInput;
import android.app.backup.BackupDataOutput;
import android.app.backup.BackupRestoreEventLogger;
import android.app.backup.BackupRestoreEventLogger.DataTypeResult;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.UserHandle;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.provider.Settings;
import android.provider.settings.validators.SettingsValidators;
import android.provider.settings.validators.Validator;
import android.test.mock.MockContentProvider;
import android.test.mock.MockContentResolver;

import androidx.test.runner.AndroidJUnit4;

import com.android.window.flags.Flags;

import java.util.List;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;


/**
 * Tests for the SettingsHelperTest
 * Usage: atest SettingsProviderTest:SettingsBackupAgentTest
 */
@RunWith(AndroidJUnit4.class)
public class SettingsBackupAgentTest extends BaseSettingsProviderTest {
    private static final Uri TEST_URI = Uri.EMPTY;
    private static final String TEST_DISPLAY_DENSITY_FORCED = "123";
    private static final String OVERRIDDEN_TEST_SETTING = "overridden_setting";
    private static final String PRESERVED_TEST_SETTING = "preserved_setting";
    private static final Map<String, String> DEVICE_SPECIFIC_TEST_VALUES = new HashMap<>();
    private static final Map<String, String> TEST_VALUES = new HashMap<>();
    private static final Map<String, Validator> TEST_VALUES_VALIDATORS = new HashMap<>();
    private static final String TEST_KEY = "test_key";
    private static final String TEST_VALUE = "test_value";
    private static final String ERROR_COULD_NOT_READ_ENTITY = "could_not_read_entity";
    private static final String ERROR_SKIPPED_BY_SYSTEM = "skipped_by_system";
    private static final String ERROR_SKIPPED_BY_BLOCKLIST =
        "skipped_by_dynamic_blocklist";
    private static final String ERROR_SKIPPED_PRESERVED = "skipped_preserved";
    private static final String ERROR_DID_NOT_PASS_VALIDATION = "did_not_pass_validation";
    private static final String KEY_SYSTEM = "system";
    private static final String KEY_SECURE = "secure";
    private static final String KEY_GLOBAL = "global";

    static {
        DEVICE_SPECIFIC_TEST_VALUES.put(Settings.Secure.DISPLAY_DENSITY_FORCED,
                TEST_DISPLAY_DENSITY_FORCED);

        TEST_VALUES.put(OVERRIDDEN_TEST_SETTING, "123");
        TEST_VALUES.put(PRESERVED_TEST_SETTING, "124");

        TEST_VALUES_VALIDATORS.put(OVERRIDDEN_TEST_SETTING,
                SettingsValidators.ANY_STRING_VALIDATOR);
        TEST_VALUES_VALIDATORS.put(PRESERVED_TEST_SETTING, SettingsValidators.ANY_STRING_VALIDATOR);
    }

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    @Mock private BackupDataInput mBackupDataInput;
    @Mock private BackupDataOutput mBackupDataOutput;

    private TestFriendlySettingsBackupAgent mAgentUnderTest;
    private Context mContext;

    @Override
    @Before
    public void setUp() {
        super.setUp();
        mContext = new ContextWithMockContentResolver(getContext());

        mAgentUnderTest = new TestFriendlySettingsBackupAgent();
        mAgentUnderTest.attach(mContext);
    }

    @Test
    public void testRoundTripDeviceSpecificSettings() throws IOException {
        TestSettingsHelper helper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = helper;

        byte[] settingsBackup = mAgentUnderTest.getDeviceSpecificConfiguration();

        assertEquals("Not all values backed up.", DEVICE_SPECIFIC_TEST_VALUES.keySet(), helper.mReadEntries);

        mAgentUnderTest.restoreDeviceSpecificConfig(
                settingsBackup,
                R.array.restore_blocked_device_specific_settings,
                Collections.emptySet(),
                Collections.emptySet());

        assertEquals("Not all values were restored.", DEVICE_SPECIFIC_TEST_VALUES, helper.mWrittenValues);
    }

    @Test
    public void testRoundTripDeviceSpecificSettingsWithBlock() throws IOException {
        TestSettingsHelper helper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = helper;

        byte[] settingsBackup = mAgentUnderTest.getDeviceSpecificConfiguration();

        assertEquals("Not all values backed up.", DEVICE_SPECIFIC_TEST_VALUES.keySet(), helper.mReadEntries);
        mAgentUnderTest.setBlockedSettings(DEVICE_SPECIFIC_TEST_VALUES.keySet().toArray(new String[0]));

        mAgentUnderTest.restoreDeviceSpecificConfig(
                settingsBackup,
                R.array.restore_blocked_device_specific_settings,
                Collections.emptySet(),
                Collections.emptySet());

        assertTrue("Not all values were blocked.", helper.mWrittenValues.isEmpty());
    }

    @Test
    public void testGeneratedHeaderMatchesCurrentDevice() throws IOException {
        mAgentUnderTest.mSettingsHelper = new TestSettingsHelper(mContext);

        byte[] header = generateUncorruptedHeader();

        AtomicInteger pos = new AtomicInteger(0);
        assertTrue(
                "Generated header is not correct for device.",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void testTestHeaderGeneratorIsAccurate() throws IOException {
        byte[] classGeneratedHeader = generateUncorruptedHeader();
        byte[] testGeneratedHeader = generateCorruptedHeader(false, false, false);

        assertArrayEquals(
                "Difference in header generation", classGeneratedHeader, testGeneratedHeader);
    }

    @Test
    public void testNewerHeaderVersionFailsMatch() throws IOException {
        byte[] header = generateCorruptedHeader(true, false, false);

        AtomicInteger pos = new AtomicInteger(0);
        assertFalse(
                "Newer header does not fail match",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void testWrongManufacturerFailsMatch() throws IOException {
        byte[] header = generateCorruptedHeader(false, true, false);

        AtomicInteger pos = new AtomicInteger(0);
        assertFalse(
                "Wrong manufacturer does not fail match",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void testWrongProductFailsMatch() throws IOException {
        byte[] header = generateCorruptedHeader(false, false, true);

        AtomicInteger pos = new AtomicInteger(0);
        assertFalse(
                "Wrong product does not fail match",
                mAgentUnderTest.isSourceAcceptable(header, pos));
    }

    @Test
    public void checkAcceptTestFailingBlockRestore() {
        mAgentUnderTest.setForcedDeviceInfoRestoreAcceptability(false);
        byte[] data = new byte[0];

        assertFalse(
                "Blocking isSourceAcceptable did not stop restore",
                mAgentUnderTest.restoreDeviceSpecificConfig(
                        data,
                        R.array.restore_blocked_device_specific_settings,
                        Collections.emptySet(),
                        Collections.emptySet()));
    }

    @Test
    public void testOnRestore_preservedSettingsAreNotRestored() {
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] { OVERRIDDEN_TEST_SETTING, PRESERVED_TEST_SETTING },
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest.restoreSettings(
            backupData,
            /* pos */ 0,
            backupData.length,
            TEST_URI,
            null,
            null,
            null,
            /* blockedSettingsArrayId */ 0,
            Collections.emptySet(),
            new HashSet<>(Collections
                              .singletonList(
                                  SettingsBackupAgent
                                      .getQualifiedKeyForSetting(
                                          PRESERVED_TEST_SETTING, TEST_URI))),
            TEST_KEY);

        assertTrue(settingsHelper.mWrittenValues.containsKey(OVERRIDDEN_TEST_SETTING));
        assertFalse(settingsHelper.mWrittenValues.containsKey(PRESERVED_TEST_SETTING));
    }

    @Test
    public void testOnRestore_bluetoothOnRestoredOnNonWearablesOnly() {
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        restoreGlobalSettings(generateBackupData(Map.of(Settings.Global.BLUETOOTH_ON, "0")));

        var isWatch = mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_WATCH);
        if (isWatch) {
            assertFalse(settingsHelper.mWrittenValues.containsKey(Settings.Global.BLUETOOTH_ON));
        } else {
            assertEquals("0", settingsHelper.mWrittenValues.get(Settings.Global.BLUETOOTH_ON));
        }
    }

    @Test
    @EnableFlags(Flags.FLAG_CONFIGURABLE_FONT_SCALE_DEFAULT)
    public void testFindClosestAllowedFontScale() {
        final String[] availableFontScales = new String[]{"0.5", "0.9", "1.0", "1.1", "1.5"};
        final Function<String, String> testedMethod =
                (value) -> SettingsBackupAgent.findClosestAllowedFontScale(value,
                        availableFontScales);

        // Any allowed value needs to be preserved.
        assertEquals("0.5", testedMethod.apply("0.5"));
        assertEquals("0.9", testedMethod.apply("0.9"));
        assertEquals("1.0", testedMethod.apply("1.0"));
        assertEquals("1.1", testedMethod.apply("1.1"));
        assertEquals("1.5", testedMethod.apply("1.5"));

        // When the current value is not one of the available, the first larger is returned
        assertEquals("0.5", testedMethod.apply("0.3"));
        assertEquals("0.9", testedMethod.apply("0.8"));
        assertEquals("1.1", testedMethod.apply("1.05"));
        assertEquals("1.5", testedMethod.apply("1.2"));

        // When the current value is larger than the only one available, the largest allowed
        // is returned.
        assertEquals("1.5", testedMethod.apply("1.8"));
    }

    @Test
    @DisableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void onCreate_metricsFlagIsDisabled_areAgentMetricsEnabledIsFalse() {
        mAgentUnderTest.onCreate();

        assertFalse(mAgentUnderTest.areAgentMetricsEnabled);
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void onCreate_flagIsEnabled_areAgentMetricsEnabledIsTrue() {
        mAgentUnderTest.onCreate();

        assertTrue(mAgentUnderTest.areAgentMetricsEnabled);
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void writeDataForKey_metricsFlagIsEnabled_numberOfSettingsPerKeyContainsKey_dataWriteSucceeds_logsSuccessMetrics()
        throws IOException {
        when(mBackupDataOutput.writeEntityHeader(anyString(), anyInt())).thenReturn(0);
        when(mBackupDataOutput.writeEntityData(any(byte[].class), anyInt())).thenReturn(0);
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.BACKUP);
        mAgentUnderTest.setNumberOfSettingsPerKey(TEST_KEY, 1);

        mAgentUnderTest.writeDataForKey(
            TEST_KEY, TEST_VALUE.getBytes(), mBackupDataOutput);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getSuccessCount(), 1);
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void writeDataForKey_metricsFlagIsEnabled_numberOfSettingsPerKeyContainsKey_writeEntityHeaderFails_logsFailureMetrics()
        throws IOException {
        when(mBackupDataOutput.writeEntityHeader(anyString(), anyInt())).thenThrow(new IOException());
        when(mBackupDataOutput.writeEntityData(any(byte[].class), anyInt())).thenReturn(0);
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.BACKUP);
        mAgentUnderTest.setNumberOfSettingsPerKey(TEST_KEY, 1);

        mAgentUnderTest.writeDataForKey(
            TEST_KEY, TEST_VALUE.getBytes(), mBackupDataOutput);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getFailCount(), 1);
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void writeDataForKey_metricsFlagIsEnabled_numberOfSettingsPerKeyContainsKey_writeEntityDataFails_logsFailureMetrics()
        throws IOException {
        when(mBackupDataOutput.writeEntityHeader(anyString(), anyInt())).thenReturn(0);
        when(mBackupDataOutput.writeEntityData(any(byte[].class), anyInt())).thenThrow(new IOException());
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.BACKUP);
        mAgentUnderTest.setNumberOfSettingsPerKey(TEST_KEY, 1);

        mAgentUnderTest.writeDataForKey(
            TEST_KEY, TEST_VALUE.getBytes(), mBackupDataOutput);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getFailCount(), 1);
    }

    @Test
    @DisableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void writeDataForKey_metricsFlagIsDisabled_doesNotLogMetrics()
        throws IOException {
        when(mBackupDataOutput.writeEntityHeader(anyString(), anyInt())).thenReturn(0);
        when(mBackupDataOutput.writeEntityData(any(byte[].class), anyInt())).thenReturn(0);
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.BACKUP);
        mAgentUnderTest.setNumberOfSettingsPerKey(TEST_KEY, 1);

        mAgentUnderTest.writeDataForKey(
            TEST_KEY, TEST_VALUE.getBytes(), mBackupDataOutput);

        assertNull(getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void writeDataForKey_metricsFlagIsEnabled_numberOfSettingsPerKeyDoesNotContainKey_doesNotLogMetrics()
        throws IOException {
        when(mBackupDataOutput.writeEntityHeader(anyString(), anyInt())).thenReturn(0);
        when(mBackupDataOutput.writeEntityData(any(byte[].class), anyInt())).thenReturn(0);
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.BACKUP);

        mAgentUnderTest.writeDataForKey(
            TEST_KEY, TEST_VALUE.getBytes(), mBackupDataOutput);

        assertNull(getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_agentMetricsAreLogged() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ null,
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList= */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getSuccessCount(), 1);
    }

    @Test
    @DisableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreDisabled_agentMetricsAreNotLogged() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ null,
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList= */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNull(loggingResult);
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_readEntityDataFails_failureIsLogged()
        throws IOException {
        when(mBackupDataInput.readEntityData(any(byte[].class), anyInt(), anyInt()))
            .thenThrow(new IOException());
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);

        mAgentUnderTest.restoreSettings(
            mBackupDataInput,
            TEST_URI,
            /* movedToGlobal= */ null,
            /* movedToSecure= */ null,
            /* movedToSystem= */ null,
            /* blockedSettingsArrayId= */ 0,
            /* dynamicBlockList= */ Collections.emptySet(),
            /* settingsToPreserve= */ Collections.emptySet(),
            TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getFailCount(), 1);
        assertTrue(loggingResult.getErrors().containsKey(ERROR_COULD_NOT_READ_ENTITY));
    }

    @Test
    @DisableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreDisabled_readEntityDataFails_failureIsNotLogged()
        throws IOException {
        when(mBackupDataInput.readEntityData(any(byte[].class), anyInt(), anyInt()))
            .thenThrow(new IOException());
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);

        mAgentUnderTest.restoreSettings(
            mBackupDataInput,
            TEST_URI,
            /* movedToGlobal= */ null,
            /* movedToSecure= */ null,
            /* movedToSystem= */ null,
            /* blockedSettingsArrayId= */ 0,
            /* dynamicBlockList= */ Collections.emptySet(),
            /* settingsToPreserve= */ Collections.emptySet(),
            TEST_KEY);

        assertNull(getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_settingIsSkippedBySystem_failureIsLogged() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        String[] settingBlockedBySystem = new String[] {OVERRIDDEN_TEST_SETTING};
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        settingBlockedBySystem,
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings(settingBlockedBySystem);
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ null,
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList= */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getFailCount(), 1);
        assertTrue(loggingResult.getErrors().containsKey(ERROR_SKIPPED_BY_SYSTEM));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_settingIsSkippedByBlockList_failureIsLogged() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;
        Set<String> dynamicBlockList =
            Set.of(Uri.withAppendedPath(TEST_URI, OVERRIDDEN_TEST_SETTING).toString());

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ null,
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                dynamicBlockList,
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getFailCount(), 1);
        assertTrue(loggingResult.getErrors().containsKey(ERROR_SKIPPED_BY_BLOCKLIST));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_settingIsPreserved_failureIsLogged() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;
        Set<String> preservedSettings =
            Set.of(Uri.withAppendedPath(TEST_URI, OVERRIDDEN_TEST_SETTING).toString());

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ null,
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList = */ Collections.emptySet(),
                preservedSettings,
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getFailCount(), 1);
        assertTrue(loggingResult.getErrors().containsKey(ERROR_SKIPPED_PRESERVED));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_settingIsNotValid_failureIsLogged() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        /* settingsValidators= */ null);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ null,
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList = */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getFailCount(), 1);
        assertTrue(loggingResult.getErrors().containsKey(ERROR_DID_NOT_PASS_VALIDATION));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_settingIsMarkedAsMovedToGlobal_agentMetricsAreLoggedWithGlobalKey() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ Set.of(OVERRIDDEN_TEST_SETTING),
                /* movedToSecure= */ null,
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList= */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(KEY_GLOBAL, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getSuccessCount(), 1);
        assertNull(getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_settingIsMarkedAsMovedToSecure_agentMetricsAreLoggedWithSecureKey() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ Set.of(OVERRIDDEN_TEST_SETTING),
                /* movedToSystem= */ null,
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList= */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(KEY_SECURE, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getSuccessCount(), 1);
        assertNull(getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest));
    }

    @Test
    @EnableFlags(com.android.server.backup.Flags.FLAG_ENABLE_METRICS_SETTINGS_BACKUP_AGENTS)
    public void restoreSettings_agentMetricsAreEnabled_settingIsMarkedAsMovedToSystem_agentMetricsAreLoggedWithSystemKey() {
        mAgentUnderTest.onCreate(
            UserHandle.SYSTEM, BackupDestination.CLOUD, OperationType.RESTORE);
        SettingsBackupAgent.SettingsBackupAllowlist allowlist =
                new SettingsBackupAgent.SettingsBackupAllowlist(
                        new String[] {OVERRIDDEN_TEST_SETTING},
                        TEST_VALUES_VALIDATORS);
        mAgentUnderTest.setSettingsAllowlist(allowlist);
        mAgentUnderTest.setBlockedSettings();
        TestSettingsHelper settingsHelper = new TestSettingsHelper(mContext);
        mAgentUnderTest.mSettingsHelper = settingsHelper;

        byte[] backupData = generateBackupData(TEST_VALUES);
        mAgentUnderTest
            .restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                TEST_URI,
                /* movedToGlobal= */ null,
                /* movedToSecure= */ null,
                /* movedToSystem= */ Set.of(OVERRIDDEN_TEST_SETTING),
                /* blockedSettingsArrayId= */ 0,
                /* dynamicBlockList= */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);

        DataTypeResult loggingResult =
            getLoggingResultForDatatype(KEY_SYSTEM, mAgentUnderTest);
        assertNotNull(loggingResult);
        assertEquals(loggingResult.getSuccessCount(), 1);
        assertNull(getLoggingResultForDatatype(TEST_KEY, mAgentUnderTest));
    }

    private byte[] generateBackupData(Map<String, String> keyValueData) {
        int totalBytes = 0;
        for (String key : keyValueData.keySet()) {
            totalBytes += 2 * Integer.BYTES + key.getBytes().length
                    + keyValueData.get(key).getBytes().length;
        }

        ByteBuffer buffer = ByteBuffer.allocate(totalBytes);
        for (String key : keyValueData.keySet()) {
            byte[] keyBytes = key.getBytes();
            byte[] valueBytes = keyValueData.get(key).getBytes();
            buffer.putInt(keyBytes.length);
            buffer.put(keyBytes);
            buffer.putInt(valueBytes.length);
            buffer.put(valueBytes);
        }

        return buffer.array();
    }

    private void restoreGlobalSettings(byte[] backupData) {
        mAgentUnderTest.restoreSettings(
                backupData,
                /* pos= */ 0,
                backupData.length,
                Settings.Global.CONTENT_URI,
                null,
                null,
                null,
                R.array.restore_blocked_global_settings,
                /* dynamicBlockList= */ Collections.emptySet(),
                /* settingsToPreserve= */ Collections.emptySet(),
                TEST_KEY);
    }

    private byte[] generateUncorruptedHeader() throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            mAgentUnderTest.writeHeader(os);
            return os.toByteArray();
        }
    }

    private byte[] generateCorruptedHeader(
            boolean corruptVersion, boolean corruptManufacturer, boolean corruptProduct)
            throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            int version = SettingsBackupAgent.DEVICE_SPECIFIC_VERSION;
            if (corruptVersion) {
                version++;
            }
            os.write(SettingsBackupAgent.toByteArray(version));

            String manufacturer = Build.MANUFACTURER;
            if (corruptManufacturer) {
                manufacturer = manufacturer == null ? "X" : manufacturer + "X";
            }
            os.write(SettingsBackupAgent.toByteArray(manufacturer));

            String product = Build.PRODUCT;
            if (corruptProduct) {
                product = product == null ? "X" : product + "X";
            }
            os.write(SettingsBackupAgent.toByteArray(product));

            return os.toByteArray();
        }
    }

    private DataTypeResult getLoggingResultForDatatype(
        String dataType, SettingsBackupAgent agent) {
        if (agent.getBackupRestoreEventLogger() == null) {
            return null;
        }
        List<DataTypeResult> loggingResults =
            agent.getBackupRestoreEventLogger().getLoggingResults();
        for (DataTypeResult result : loggingResults) {
            if (result.getDataType().equals(dataType)) {
                return result;
            }
        }
        return null;
    }

    private byte[] generateSingleKeyTestBackupData(String key, String value) throws IOException {
        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            os.write(SettingsBackupAgent.toByteArray(key));
            os.write(SettingsBackupAgent.toByteArray(value));
            return os.toByteArray();
        }
    }

    private static class TestFriendlySettingsBackupAgent extends SettingsBackupAgent {
        private Boolean mForcedDeviceInfoRestoreAcceptability = null;
        private String[] mBlockedSettings = null;
        private SettingsBackupAllowlist mSettingsAllowlist = null;

        void setForcedDeviceInfoRestoreAcceptability(boolean value) {
            mForcedDeviceInfoRestoreAcceptability = value;
        }

        void setBlockedSettings(String... blockedSettings) {
            mBlockedSettings = blockedSettings;
        }

        void setSettingsAllowlist(SettingsBackupAllowlist settingsAllowlist) {
            mSettingsAllowlist = settingsAllowlist;
        }

        @Override
        protected Set<String> getBlockedSettings(int blockedSettingsArrayId) {
            return mBlockedSettings == null
                    ? super.getBlockedSettings(blockedSettingsArrayId)
                    : new HashSet<>(Arrays.asList(mBlockedSettings));
        }

        @Override
        boolean isSourceAcceptable(byte[] data, AtomicInteger pos) {
            return mForcedDeviceInfoRestoreAcceptability == null
                    ? super.isSourceAcceptable(data, pos)
                    : mForcedDeviceInfoRestoreAcceptability;
        }

        @Override
        SettingsBackupAllowlist getBackupAllowlist(Uri contentUri) {
            if (mSettingsAllowlist == null) {
                return super.getBackupAllowlist(contentUri);
            }

            return mSettingsAllowlist;
        }

        void setNumberOfSettingsPerKey(String key, int numberOfSettings) {
            if (numberOfSettingsPerKey != null) {
                this.numberOfSettingsPerKey.put(key, numberOfSettings);
            }
        }
    }

    /** The TestSettingsHelper tracks which values have been backed up and/or restored. */
    private static class TestSettingsHelper extends SettingsHelper {
        private Set<String> mReadEntries;
        private Map<String, String> mWrittenValues;

        TestSettingsHelper(Context context) {
            super(context);
            mReadEntries = new HashSet<>();
            mWrittenValues = new HashMap<>();
        }

        @Override
        public String onBackupValue(String key, String value) {
            mReadEntries.add(key);
            String readValue = DEVICE_SPECIFIC_TEST_VALUES.get(key);
            assert readValue != null;
            return readValue;
        }

        @Override
        public void restoreValue(
                Context context,
                ContentResolver cr,
                ContentValues contentValues,
                Uri destination,
                String name,
                String value,
                int restoredFromSdkInt) {
            mWrittenValues.put(name, value);
        }
    }

    /**
     * ContextWrapper which allows us to return a MockContentResolver to code which uses it to
     * access settings. This allows us to override the ContentProvider for the Settings URIs to
     * return known values.
     */
    private static class ContextWithMockContentResolver extends ContextWrapper {
        private MockContentResolver mContentResolver;

        ContextWithMockContentResolver(Context targetContext) {
            super(targetContext);

            mContentResolver = new MockContentResolver();
            mContentResolver.addProvider(
                    Settings.AUTHORITY, new DeviceSpecificInfoMockContentProvider());
        }

        @Override
        public ContentResolver getContentResolver() {
            return mContentResolver;
        }
    }

    /** ContentProvider which returns a set of known test values. */
    private static class DeviceSpecificInfoMockContentProvider extends MockContentProvider {
        private static final Object[][] RESULT_ROWS = {
            {Settings.Secure.DISPLAY_DENSITY_FORCED, TEST_DISPLAY_DENSITY_FORCED},
        };

        @Override
        public Cursor query(
                Uri uri,
                String[] projection,
                String selection,
                String[] selectionArgs,
                String sortOrder) {
            MatrixCursor result = new MatrixCursor(SettingsBackupAgent.PROJECTION);
            for (Object[] resultRow : RESULT_ROWS) {
                result.addRow(resultRow);
            }
            return result;
        }

        @Override
        public Bundle call(String method, String request, Bundle args) {
            for (Object[] resultRow : RESULT_ROWS) {
                if (Objects.equals(request, resultRow[0])) {
                    final Bundle res = new Bundle();
                    res.putString("value", String.valueOf(resultRow[1]));
                    return res;
                }
            }
            return Bundle.EMPTY;
        }
    }
}
