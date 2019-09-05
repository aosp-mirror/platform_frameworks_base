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

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.content.Context;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

@RunWith(RobolectricTestRunner.class)
@Presubmit
public class BackupManagerConstantsTest {
    private static final String PACKAGE_NAME = "some.package.name";
    private static final String ANOTHER_PACKAGE_NAME = "another.package.name";

    private ContentResolver mContentResolver;
    private BackupManagerConstants mConstants;

    @Before
    public void setUp() {
        final Context context = RuntimeEnvironment.application.getApplicationContext();

        mContentResolver = context.getContentResolver();
        mConstants = new BackupManagerConstants(new Handler(), mContentResolver);
        mConstants.start();
    }

    @After
    public void tearDown() {
        mConstants.stop();
    }

    @Test
    public void testGetConstants_afterConstructorWithStart_returnsDefaultValues() {
        long keyValueBackupIntervalMilliseconds =
                mConstants.getKeyValueBackupIntervalMilliseconds();
        long keyValueBackupFuzzMilliseconds = mConstants.getKeyValueBackupFuzzMilliseconds();
        boolean keyValueBackupRequireCharging = mConstants.getKeyValueBackupRequireCharging();
        int keyValueBackupRequiredNetworkType = mConstants.getKeyValueBackupRequiredNetworkType();
        long fullBackupIntervalMilliseconds = mConstants.getFullBackupIntervalMilliseconds();
        boolean fullBackupRequireCharging = mConstants.getFullBackupRequireCharging();
        int fullBackupRequiredNetworkType = mConstants.getFullBackupRequiredNetworkType();
        String[] backupFinishedNotificationReceivers =
                mConstants.getBackupFinishedNotificationReceivers();

        assertThat(keyValueBackupIntervalMilliseconds)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS);
        assertThat(keyValueBackupFuzzMilliseconds)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_FUZZ_MILLISECONDS);
        assertThat(keyValueBackupRequireCharging)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_REQUIRE_CHARGING);
        assertThat(keyValueBackupRequiredNetworkType)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE);
        assertThat(fullBackupIntervalMilliseconds)
                .isEqualTo(BackupManagerConstants.DEFAULT_FULL_BACKUP_INTERVAL_MILLISECONDS);
        assertThat(fullBackupRequireCharging)
                .isEqualTo(BackupManagerConstants.DEFAULT_FULL_BACKUP_REQUIRE_CHARGING);
        assertThat(fullBackupRequiredNetworkType)
                .isEqualTo(BackupManagerConstants.DEFAULT_FULL_BACKUP_REQUIRED_NETWORK_TYPE);
        assertThat(backupFinishedNotificationReceivers).isEqualTo(new String[0]);
    }

    /**
     * Tests that if there is a setting change when we are not currently observing the setting, that
     * once we start observing again, we receive the most up-to-date value.
     */
    @Test
    public void testGetConstant_withSettingChangeBeforeStart_updatesValues() {
        mConstants.stop();
        long testInterval =
                BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS * 2;
        final String setting =
                BackupManagerConstants.KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS + "=" + testInterval;
        putStringAndNotify(setting);

        mConstants.start();

        long keyValueBackupIntervalMilliseconds =
                mConstants.getKeyValueBackupIntervalMilliseconds();
        assertThat(keyValueBackupIntervalMilliseconds).isEqualTo(testInterval);
    }

    @Test
    public void testGetConstants_whenSettingIsNull_returnsDefaultValues() {
        putStringAndNotify(null);

        long keyValueBackupIntervalMilliseconds =
                mConstants.getKeyValueBackupIntervalMilliseconds();
        long keyValueBackupFuzzMilliseconds = mConstants.getKeyValueBackupFuzzMilliseconds();
        boolean keyValueBackupRequireCharging = mConstants.getKeyValueBackupRequireCharging();
        int keyValueBackupRequiredNetworkType = mConstants.getKeyValueBackupRequiredNetworkType();
        long fullBackupIntervalMilliseconds = mConstants.getFullBackupIntervalMilliseconds();
        boolean fullBackupRequireCharging = mConstants.getFullBackupRequireCharging();
        int fullBackupRequiredNetworkType = mConstants.getFullBackupRequiredNetworkType();
        String[] backupFinishedNotificationReceivers =
                mConstants.getBackupFinishedNotificationReceivers();

        assertThat(keyValueBackupIntervalMilliseconds)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS);
        assertThat(keyValueBackupFuzzMilliseconds)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_FUZZ_MILLISECONDS);
        assertThat(keyValueBackupRequireCharging)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_REQUIRE_CHARGING);
        assertThat(keyValueBackupRequiredNetworkType)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE);
        assertThat(fullBackupIntervalMilliseconds)
                .isEqualTo(BackupManagerConstants.DEFAULT_FULL_BACKUP_INTERVAL_MILLISECONDS);
        assertThat(fullBackupRequireCharging)
                .isEqualTo(BackupManagerConstants.DEFAULT_FULL_BACKUP_REQUIRE_CHARGING);
        assertThat(fullBackupRequiredNetworkType)
                .isEqualTo(BackupManagerConstants.DEFAULT_FULL_BACKUP_REQUIRED_NETWORK_TYPE);
        assertThat(backupFinishedNotificationReceivers).isEqualTo(new String[0]);
    }

    /**
     * Test passing in a malformed setting string. The setting expects
     * "key1=value,key2=value,key3=value..." but we pass in "key1=,value"
     */
    @Test
    public void testGetConstant_whenSettingIsMalformed_doesNotUpdateParamsOrThrow() {
        long testFuzz = BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_FUZZ_MILLISECONDS * 2;
        final String invalidSettingFormat =
                BackupManagerConstants.KEY_VALUE_BACKUP_FUZZ_MILLISECONDS + "=," + testFuzz;
        putStringAndNotify(invalidSettingFormat);

        long keyValueBackupFuzzMilliseconds = mConstants.getKeyValueBackupFuzzMilliseconds();

        assertThat(keyValueBackupFuzzMilliseconds)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_FUZZ_MILLISECONDS);
    }

    /**
     * Test passing in an invalid value type. {@link
     * BackupManagerConstants#KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE} expects an integer, but we
     * pass in a boolean.
     */
    @Test
    public void testGetConstant_whenSettingHasInvalidType_doesNotUpdateParamsOrThrow() {
        boolean testValue = true;
        final String invalidSettingType =
                BackupManagerConstants.KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE + "=" + testValue;
        putStringAndNotify(invalidSettingType);

        int keyValueBackupRequiredNetworkType = mConstants.getKeyValueBackupRequiredNetworkType();

        assertThat(keyValueBackupRequiredNetworkType)
                .isEqualTo(BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_REQUIRED_NETWORK_TYPE);
    }

    @Test
    public void testGetConstants_afterSettingChange_updatesValues() {
        long testKVInterval =
                BackupManagerConstants.DEFAULT_KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS * 2;
        long testFullInterval =
                BackupManagerConstants.DEFAULT_FULL_BACKUP_INTERVAL_MILLISECONDS * 2;
        final String intervalSetting =
                BackupManagerConstants.KEY_VALUE_BACKUP_INTERVAL_MILLISECONDS
                        + "="
                        + testKVInterval
                        + ","
                        + BackupManagerConstants.FULL_BACKUP_INTERVAL_MILLISECONDS
                        + "="
                        + testFullInterval;
        putStringAndNotify(intervalSetting);

        long keyValueBackupIntervalMilliseconds =
                mConstants.getKeyValueBackupIntervalMilliseconds();
        long fullBackupIntervalMilliseconds = mConstants.getFullBackupIntervalMilliseconds();

        assertThat(keyValueBackupIntervalMilliseconds).isEqualTo(testKVInterval);
        assertThat(fullBackupIntervalMilliseconds).isEqualTo(testFullInterval);
    }

    @Test
    public void testBackupNotificationReceivers_afterSetting_updatesAndParsesCorrectly() {
        final String receiversSetting =
                BackupManagerConstants.BACKUP_FINISHED_NOTIFICATION_RECEIVERS
                        + "="
                        + PACKAGE_NAME
                        + ':'
                        + ANOTHER_PACKAGE_NAME;
        putStringAndNotify(receiversSetting);

        String[] backupFinishedNotificationReceivers =
                mConstants.getBackupFinishedNotificationReceivers();
        assertThat(backupFinishedNotificationReceivers)
                .isEqualTo(new String[] {PACKAGE_NAME, ANOTHER_PACKAGE_NAME});
    }

    /**
     * Robolectric does not notify observers of changes to settings so we have to trigger it here.
     * Currently, the mock of {@link Settings.Secure#putString(ContentResolver, String, String)}
     * only stores the value. TODO: Implement properly in ShadowSettings.
     */
    private void putStringAndNotify(String value) {
        Settings.Secure.putString(
                mContentResolver, Settings.Secure.BACKUP_MANAGER_CONSTANTS, value);

        // We pass null as the observer since notifyChange iterates over all available observers and
        // we don't have access to the local observer.
        mContentResolver.notifyChange(
                Settings.Secure.getUriFor(Settings.Secure.BACKUP_MANAGER_CONSTANTS),
                /*observer*/ null);
    }
}
