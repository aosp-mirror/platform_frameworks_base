/*
 * Copyright (C) 2022 The Android Open Source Project
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

import android.platform.test.annotations.Presubmit;
import android.provider.DeviceConfig;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.modules.utils.testing.TestableDeviceConfig;

import static com.google.common.truth.Truth.assertThat;


import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@Presubmit
@RunWith(AndroidJUnit4.class)
public class BackupAndRestoreFeatureFlagsTest {
    @Rule
    public TestableDeviceConfig.TestableDeviceConfigRule
            mDeviceConfigRule = new TestableDeviceConfig.TestableDeviceConfigRule();

    @Test
    public void getBackupTransportFutureTimeoutMillis_notSet_returnsDefault() {
        assertThat(
                BackupAndRestoreFeatureFlags.getBackupTransportFutureTimeoutMillis()).isEqualTo(
                600000);
    }

    @Test
    public void getBackupTransportFutureTimeoutMillis_set_returnsSetValue() {
        DeviceConfig.setProperty(/*namespace=*/ "backup_and_restore",
                /*name=*/ "backup_transport_future_timeout_millis",
                /*value=*/ "1234", /*makeDefault=*/ false);

        assertThat(
                BackupAndRestoreFeatureFlags.getBackupTransportFutureTimeoutMillis()).isEqualTo(
                1234);
    }

    @Test
    public void getBackupTransportCallbackTimeoutMillis_notSet_returnsDefault() {
        assertThat(
                BackupAndRestoreFeatureFlags.getBackupTransportCallbackTimeoutMillis()).isEqualTo(
                300000);
    }

    @Test
    public void getBackupTransportCallbackTimeoutMillis_set_returnsSetValue() {
        DeviceConfig.setProperty(/*namespace=*/ "backup_and_restore",
                /*name=*/ "backup_transport_callback_timeout_millis",
                /*value=*/ "5678", /*makeDefault=*/ false);

        assertThat(
                BackupAndRestoreFeatureFlags.getBackupTransportCallbackTimeoutMillis()).isEqualTo(
                5678);
    }

    @Test
    public void getFullBackupWriteToTransportBufferSizeBytes_notSet_returnsDefault() {
        assertThat(BackupAndRestoreFeatureFlags.getFullBackupWriteToTransportBufferSizeBytes())
                .isEqualTo(8 * 1024);
    }

    @Test
    public void getFullBackupWriteToTransportBufferSizeBytes_set_returnsSetValue() {
        DeviceConfig.setProperty(/*namespace=*/ "backup_and_restore",
                /*name=*/ "full_backup_write_to_transport_buffer_size_bytes",
                /*value=*/ "5678", /*makeDefault=*/ false);

        assertThat(BackupAndRestoreFeatureFlags.getFullBackupWriteToTransportBufferSizeBytes())
                .isEqualTo(5678);
    }

    @Test
    public void getFullBackupUtilsRouteBufferSizeBytes_notSet_returnsDefault() {
        assertThat(BackupAndRestoreFeatureFlags.getFullBackupUtilsRouteBufferSizeBytes())
                .isEqualTo(32 * 1024);
    }

    @Test
    public void getFullBackupUtilsRouteBufferSizeBytes_set_returnsSetValue() {
        DeviceConfig.setProperty(/*namespace=*/ "backup_and_restore",
                /*name=*/ "full_backup_utils_route_buffer_size_bytes",
                /*value=*/ "5678", /*makeDefault=*/ false);

        assertThat(BackupAndRestoreFeatureFlags.getFullBackupUtilsRouteBufferSizeBytes())
                .isEqualTo(5678);
    }

    @Test
    public void getUnifiedRestoreContinueAfterTransportFailureInKvRestore_notSet_returnsDefault() {
        assertThat(
                BackupAndRestoreFeatureFlags
                        .getUnifiedRestoreContinueAfterTransportFailureInKvRestore())
                .isEqualTo(true);
    }

    @Test
    public void getUnifiedRestoreContinueAfterTransportFailureInKvRestore_set_returnsSetValue() {
        DeviceConfig.setProperty(/*namespace=*/ "backup_and_restore",
                /*name=*/ "unified_restore_continue_after_transport_failure_in_kv_restore",
                /*value=*/ "false", /*makeDefault=*/ false);

        assertThat(
                BackupAndRestoreFeatureFlags
                        .getUnifiedRestoreContinueAfterTransportFailureInKvRestore())
                .isEqualTo(false);
    }
}
