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

import android.Manifest;
import android.annotation.RequiresPermission;
import android.provider.DeviceConfig;

/**
 * Retrieves values of feature flags.
 *
 * <p>These flags are intended to be configured server-side and their values should be set in {@link
 * DeviceConfig} by a service that periodically syncs with the server.
 *
 * <p>This class must ensure that the namespace, flag name, and default value passed into {@link
 * DeviceConfig} matches what's declared on the server. The namespace is shared for all backup and
 * restore flags.
 */
public class BackupAndRestoreFeatureFlags {
    private static final String NAMESPACE = "backup_and_restore";

    private BackupAndRestoreFeatureFlags() {}

    /** Retrieves the value of the flag "backup_transport_future_timeout_millis". */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public static long getBackupTransportFutureTimeoutMillis() {
        return DeviceConfig.getLong(
                NAMESPACE,
                /* name= */ "backup_transport_future_timeout_millis",
                /* defaultValue= */ 600000); // 10 minutes
    }

    /** Retrieves the value of the flag "backup_transport_callback_timeout_millis". */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public static long getBackupTransportCallbackTimeoutMillis() {
        return DeviceConfig.getLong(
                NAMESPACE,
                /* name= */ "backup_transport_callback_timeout_millis",
                /* defaultValue= */ 300000); // 5 minutes
    }

    /**
     * Retrieves the value of the flag "full_backup_write_to_transport_buffer_size_bytes".
     * The returned value is max size of a chunk of backup data that is sent to the transport.
     */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public static int getFullBackupWriteToTransportBufferSizeBytes() {
        return DeviceConfig.getInt(
                NAMESPACE,
                /* name= */ "full_backup_write_to_transport_buffer_size_bytes",
                /* defaultValue= */ 8 * 1024); // 8 KB
    }

    /**
     * Retrieves the value of the flag "full_backup_utils_route_buffer_size_bytes".
     * The returned value is max size of a chunk of backup data that routed from write end of
     * pipe from BackupAgent, to read end of pipe to Full Backup Task (PFTBT).
     */
    @RequiresPermission(Manifest.permission.READ_DEVICE_CONFIG)
    public static int getFullBackupUtilsRouteBufferSizeBytes() {
        return DeviceConfig.getInt(
                NAMESPACE,
                /* name= */ "full_backup_utils_route_buffer_size_bytes",
                /* defaultValue= */ 32 * 1024); // 32 KB
    }
}
