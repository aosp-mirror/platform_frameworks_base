/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.bluetooth.le;

import java.util.List;

/**
 * Callback of Bluetooth LE scans. The results of the scans will be delivered through the callbacks.
 */
public abstract class ScanCallback {

    /**
     * Fails to start scan as BLE scan with the same settings is already started by the app.
     */
    public static final int SCAN_FAILED_ALREADY_STARTED = 1;
    /**
     * Fails to start scan as app cannot be registered.
     */
    public static final int SCAN_FAILED_APPLICATION_REGISTRATION_FAILED = 2;
    /**
     * Fails to start scan due to gatt service failure.
     */
    public static final int SCAN_FAILED_GATT_SERVICE_FAILURE = 3;
    /**
     * Fails to start scan due to controller failure.
     */
    public static final int SCAN_FAILED_CONTROLLER_FAILURE = 4;

    /**
     * Callback when a BLE advertisement is found.
     *
     * @param result A Bluetooth LE scan result.
     */
    public abstract void onAdvertisementUpdate(ScanResult result);

    /**
     * Callback when the BLE advertisement is found for the first time.
     *
     * @param result The Bluetooth LE scan result when the onFound event is triggered.
     * @hide
     */
    public abstract void onAdvertisementFound(ScanResult result);

    /**
     * Callback when the BLE advertisement was lost. Note a device has to be "found" before it's
     * lost.
     *
     * @param result The Bluetooth scan result that was last found.
     * @hide
     */
    public abstract void onAdvertisementLost(ScanResult result);

    /**
     * Callback when batch results are delivered.
     *
     * @param results List of scan results that are previously scanned.
     * @hide
     */
    public abstract void onBatchScanResults(List<ScanResult> results);

    /**
     * Callback when scan failed.
     */
    public abstract void onScanFailed(int errorCode);
}
