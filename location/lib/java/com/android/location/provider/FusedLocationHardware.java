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

package com.android.location.provider;

import android.annotation.SystemApi;
import android.os.Looper;

/**
 * Class that exposes IFusedLocationHardware functionality to unbundled services.
 *
 * @deprecated This class may no longer be used from Android P and onwards.
 * @hide
 */
@Deprecated
@SystemApi
public final class FusedLocationHardware {

    private FusedLocationHardware() {}

    /*
     * Methods to provide a Facade for IFusedLocationHardware
     */
    public void registerSink(FusedLocationHardwareSink sink, Looper looper) {}

    public void unregisterSink(FusedLocationHardwareSink sink) {}

    public int getSupportedBatchSize() {
        return 0;
    }

    public void startBatching(int id, GmsFusedBatchOptions batchOptions) {}

    public void stopBatching(int id) {}

    public void updateBatchingOptions(int id, GmsFusedBatchOptions batchOptions) {}

    public void requestBatchOfLocations(int batchSizeRequest) {}

    public void flushBatchedLocations() {}

    public boolean supportsDiagnosticDataInjection() {
        return false;
    }

    public void injectDiagnosticData(String data) {}

    public boolean supportsDeviceContextInjection() {
        return false;
    }

    public void injectDeviceContext(int deviceEnabledContext) {}

    /**
     * Returns the version of the FLP HAL.
     *
     * <p>Version 1 is the initial release.
     * <p>Version 2 adds the ability to use {@link #flushBatchedLocations},
     * {@link FusedLocationHardwareSink#onCapabilities}, and
     * {@link FusedLocationHardwareSink#onStatusChanged}.
     *
     * <p>This method is only available on API 23 or later.  Older APIs have version 1.
     */
    public int getVersion() {
        return 1;
    }
}
