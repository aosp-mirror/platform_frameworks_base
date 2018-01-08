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

import android.app.AlarmManager;
import android.content.Context;
import android.os.Handler;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;

import com.android.server.testing.FrameworkRobolectricTestRunner;
import com.android.server.testing.SystemLoaderClasses;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(FrameworkRobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 26)
@SystemLoaderClasses({BackupManagerConstants.class})
@Presubmit
public class BackupManagerConstantsTest {
    private static final String PACKAGE_NAME = "some.package.name";
    private static final String ANOTHER_PACKAGE_NAME = "another.package.name";

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testDefaultValues() throws Exception {
        final Context context = RuntimeEnvironment.application.getApplicationContext();
        final Handler handler = new Handler();

        Settings.Secure.putString(
                context.getContentResolver(), Settings.Secure.BACKUP_MANAGER_CONSTANTS, null);

        final BackupManagerConstants constants =
                new BackupManagerConstants(handler, context.getContentResolver());

        assertThat(constants.getKeyValueBackupIntervalMilliseconds())
                .isEqualTo(4 * AlarmManager.INTERVAL_HOUR);
        assertThat(constants.getKeyValueBackupFuzzMilliseconds()).isEqualTo(10 * 60 * 1000);
        assertThat(constants.getKeyValueBackupRequireCharging()).isEqualTo(true);
        assertThat(constants.getKeyValueBackupRequiredNetworkType()).isEqualTo(1);

        assertThat(constants.getFullBackupIntervalMilliseconds())
                .isEqualTo(24 * AlarmManager.INTERVAL_HOUR);
        assertThat(constants.getFullBackupRequireCharging()).isEqualTo(true);
        assertThat(constants.getFullBackupRequiredNetworkType()).isEqualTo(2);
        assertThat(constants.getBackupFinishedNotificationReceivers()).isEqualTo(new String[0]);
    }

    @Test
    public void testParseNotificationReceivers() throws Exception {
        final Context context = RuntimeEnvironment.application.getApplicationContext();
        final Handler handler = new Handler();

        final String recieversSetting =
                "backup_finished_notification_receivers="
                        + PACKAGE_NAME
                        + ':'
                        + ANOTHER_PACKAGE_NAME;
        Settings.Secure.putString(
                context.getContentResolver(),
                Settings.Secure.BACKUP_MANAGER_CONSTANTS,
                recieversSetting);

        final BackupManagerConstants constants =
                new BackupManagerConstants(handler, context.getContentResolver());

        assertThat(constants.getBackupFinishedNotificationReceivers())
                .isEqualTo(new String[] {PACKAGE_NAME, ANOTHER_PACKAGE_NAME});
    }
}
