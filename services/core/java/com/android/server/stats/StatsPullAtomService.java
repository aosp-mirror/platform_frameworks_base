/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.server.stats;

import android.app.StatsManager;
import android.content.Context;
import android.util.Slog;

import com.android.internal.os.BackgroundThread;
import com.android.server.SystemService;

/**
 * SystemService containing PullAtomCallbacks that are registered with statsd.
 *
 * @hide
 */
public class StatsPullAtomService extends SystemService {
    private static final String TAG = "StatsPullAtomService";
    private static final boolean DEBUG = true;

    private final StatsManager mStatsManager;

    public StatsPullAtomService(Context context) {
        super(context);
        mStatsManager = (StatsManager) context.getSystemService(Context.STATS_MANAGER);
    }

    @Override
    public void onStart() {
        // No op.
    }

    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        if (phase == PHASE_SYSTEM_SERVICES_READY) {
            BackgroundThread.getHandler().post(() -> {
                registerAllPullers();
            });
        }
    }

    void registerAllPullers() {
        if (DEBUG) {
            Slog.d(TAG, "Registering all pullers with statsd");
        }
        registerWifiBytesTransfer();
        registerWifiBytesTransferBackground();
        registerMobileBytesTransfer();
        registerMobileBytesTransferBackground();
        registerBluetoothBytesTransfer();
        registerKernelWakelock();
        registerCpuTimePerFreq();
        registerCpuTimePerUid();
        registerCpuTimePerUidFreq();
        registerCpuActiveTime();
        registerCpuClusterTime();
        registerWifiActivityInfo();
        registerModemActivityInfo();
        registerBluetoothActivityInfo();
        registerSystemElapsedRealtime();
        registerSystemUptime();
        registerRemainingBatteryCapacity();
        registerFullBatteryCapacity();
        registerBatteryVoltage();
        registerBatteryLevel();
        registerBatteryCycleCount();
        registerProcessMemoryState();
        registerProcessMemoryHighWaterMark();
        registerProcessMemorySnapshot();
        registerSystemIonHeapSize();
        registerProcessSystemIonHeapSize();
        registerTemperature();
        registerCoolingDevice();
        registerBinderCalls();
        registerBinderCallsExceptions();
        registerLooperStats();
        registerDiskStats();
        registerDirectoryUsage();
        registerAppSize();
        registerCategorySize();
        registerNumFingerprintsEnrolled();
        registerNumFacesEnrolled();
        registerProcStats();
        registerProcStatsPkgProc();
        registerDiskIO();
        registerPowerProfile();
        registerProcessCpuTime();
        registerCpuTimePerThreadFreq();
        registerDeviceCalculatedPowerUse();
        registerDeviceCalculatedPowerBlameUid();
        registerDeviceCalculatedPowerBlameOther();
        registerDebugElapsedClock();
        registerDebugFailingElapsedClock();
        registerBuildInformation();
        registerRoleHolder();
        registerDangerousPermissionState();
        registerTimeZoneDataInfo();
        registerExternalStorageInfo();
        registerAppsOnExternalStorageInfo();
        registerFaceSettings();
        registerAppOps();
        registerNotificationRemoteViews();
        registerDangerousPermissionState();
        registerDangerousPermissionStateSampled();
    }

    private void registerWifiBytesTransfer() {
        // No op.
    }

    private void pullWifiBytesTransfer() {
        // No op.
    }

    private void registerWifiBytesTransferBackground() {
        // No op.
    }

    private void pullWifiBytesTransferBackground() {
        // No op.
    }

    private void registerMobileBytesTransfer() {
        // No op.
    }

    private void pullMobileBytesTransfer() {
        // No op.
    }

    private void registerMobileBytesTransferBackground() {
        // No op.
    }

    private void pullMobileBytesTransferBackground() {
        // No op.
    }

    private void registerBluetoothBytesTransfer() {
        // No op.
    }

    private void pullBluetoothBytesTransfer() {
        // No op.
    }

    private void registerKernelWakelock() {
        // No op.
    }

    private void pullKernelWakelock() {
        // No op.
    }

    private void registerCpuTimePerFreq() {
        // No op.
    }

    private void pullCpuTimePerFreq() {
        // No op.
    }

    private void registerCpuTimePerUid() {
        // No op.
    }

    private void pullCpuTimePerUid() {
        // No op.
    }

    private void registerCpuTimePerUidFreq() {
        // No op.
    }

    private void pullCpuTimeperUidFreq() {
        // No op.
    }

    private void registerCpuActiveTime() {
        // No op.
    }

    private void pullCpuActiveTime() {
        // No op.
    }

    private void registerCpuClusterTime() {
        // No op.
    }

    private int pullCpuClusterTime() {
        return 0;
    }

    private void registerWifiActivityInfo() {
        // No op.
    }

    private void pullWifiActivityInfo() {
        // No op.
    }

    private void registerModemActivityInfo() {
        // No op.
    }

    private void pullModemActivityInfo() {
        // No op.
    }

    private void registerBluetoothActivityInfo() {
        // No op.
    }

    private void pullBluetoothActivityInfo() {
        // No op.
    }

    private void registerSystemElapsedRealtime() {
        // No op.
    }

    private void pullSystemElapsedRealtime() {
        // No op.
    }

    private void registerSystemUptime() {
        // No op.
    }

    private void pullSystemUptime() {
        // No op.
    }

    private void registerRemainingBatteryCapacity() {
        // No op.
    }

    private void pullRemainingBatteryCapacity() {
        // No op.
    }

    private void registerFullBatteryCapacity() {
        // No op.
    }

    private void pullFullBatteryCapacity() {
        // No op.
    }

    private void registerBatteryVoltage() {
        // No op.
    }

    private void pullBatteryVoltage() {
        // No op.
    }

    private void registerBatteryLevel() {
        // No op.
    }

    private void pullBatteryLevel() {
        // No op.
    }

    private void registerBatteryCycleCount() {
        // No op.
    }

    private void pullBatteryCycleCount() {
        // No op.
    }

    private void registerProcessMemoryState() {
        // No op.
    }

    private void pullProcessMemoryState() {
        // No op.
    }

    private void registerProcessMemoryHighWaterMark() {
        // No op.
    }

    private void pullProcessMemoryHighWaterMark() {
        // No op.
    }

    private void registerProcessMemorySnapshot() {
        // No op.
    }

    private void pullProcessMemorySnapshot() {
        // No op.
    }

    private void registerSystemIonHeapSize() {
        // No op.
    }

    private void pullSystemIonHeapSize() {
        // No op.
    }

    private void registerProcessSystemIonHeapSize() {
        // No op.
    }

    private void pullProcessSystemIonHeapSize() {
        // No op.
    }

    private void registerTemperature() {
        // No op.
    }

    private void pullTemperature() {
        // No op.
    }

    private void registerCoolingDevice() {
        // No op.
    }

    private void pullCooldownDevice() {
        // No op.
    }

    private void registerBinderCalls() {
        // No op.
    }

    private void pullBinderCalls() {
        // No op.
    }

    private void registerBinderCallsExceptions() {
        // No op.
    }

    private void pullBinderCallsExceptions() {
        // No op.
    }

    private void registerLooperStats() {
        // No op.
    }

    private void pullLooperStats() {
        // No op.
    }

    private void registerDiskStats() {
        // No op.
    }

    private void pullDiskStats() {
        // No op.
    }

    private void registerDirectoryUsage() {
        // No op.
    }

    private void pullDirectoryUsage() {
        // No op.
    }

    private void registerAppSize() {
        // No op.
    }

    private void pullAppSize() {
        // No op.
    }

    private void registerCategorySize() {
        // No op.
    }

    private void pullCategorySize() {
        // No op.
    }

    private void registerNumFingerprintsEnrolled() {
        // No op.
    }

    private void pullNumFingerprintsEnrolled() {
        // No op.
    }

    private void registerNumFacesEnrolled() {
        // No op.
    }

    private void pullNumFacesEnrolled() {
        // No op.
    }

    private void registerProcStats() {
        // No op.
    }

    private void pullProcStats() {
        // No op.
    }

    private void registerProcStatsPkgProc() {
        // No op.
    }

    private void pullProcStatsPkgProc() {
        // No op.
    }

    private void registerDiskIO() {
        // No op.
    }

    private void pullDiskIO() {
        // No op.
    }

    private void registerPowerProfile() {
        // No op.
    }

    private void pullPowerProfile() {
        // No op.
    }

    private void registerProcessCpuTime() {
        // No op.
    }

    private void pullProcessCpuTime() {
        // No op.
    }

    private void registerCpuTimePerThreadFreq() {
        // No op.
    }

    private void pullCpuTimePerThreadFreq() {
        // No op.
    }

    private void registerDeviceCalculatedPowerUse() {
        // No op.
    }

    private void pullDeviceCalculatedPowerUse() {
        // No op.
    }

    private void registerDeviceCalculatedPowerBlameUid() {
        // No op.
    }

    private void pullDeviceCalculatedPowerBlameUid() {
        // No op.
    }

    private void registerDeviceCalculatedPowerBlameOther() {
        // No op.
    }

    private void pullDeviceCalculatedPowerBlameOther() {
        // No op.
    }

    private void registerDebugElapsedClock() {
        // No op.
    }

    private void pullDebugElapsedClock() {
        // No op.
    }

    private void registerDebugFailingElapsedClock() {
        // No op.
    }

    private void pullDebugFailingElapsedClock() {
        // No op.
    }

    private void registerBuildInformation() {
        // No op.
    }

    private void pullBuildInformation() {
        // No op.
    }

    private void registerRoleHolder() {
        // No op.
    }

    private void pullRoleHolder() {
        // No op.
    }

    private void registerDangerousPermissionState() {
        // No op.
    }

    private void pullDangerousPermissionState() {
        // No op.
    }

    private void registerTimeZoneDataInfo() {
        // No op.
    }

    private void pullTimeZoneDataInfo() {
        // No op.
    }

    private void registerExternalStorageInfo() {
        // No op.
    }

    private void pullExternalStorageInfo() {
        // No op.
    }

    private void registerAppsOnExternalStorageInfo() {
        // No op.
    }

    private void pullAppsOnExternalStorageInfo() {
        // No op.
    }

    private void registerFaceSettings() {
        // No op.
    }

    private void pullRegisterFaceSettings() {
        // No op.
    }

    private void registerAppOps() {
        // No op.
    }

    private void pullAppOps() {
        // No op.
    }

    private void registerNotificationRemoteViews() {
        // No op.
    }

    private void pullNotificationRemoteViews() {
        // No op.
    }

    private void registerDangerousPermissionStateSampled() {
        // No op.
    }

    private void pullDangerousPermissionStateSampled() {
        // No op.
    }
}
