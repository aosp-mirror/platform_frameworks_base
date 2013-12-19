/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.location;

import android.content.Context;
import android.hardware.location.IFusedLocationHardware;
import android.hardware.location.IFusedLocationHardwareSink;
import android.location.FusedBatchOptions;
import android.os.RemoteException;

/**
 * FusedLocationHardware decorator that adds permission checking.
 * @hide
 */
public class FusedLocationHardwareSecure extends IFusedLocationHardware.Stub {
    private final IFusedLocationHardware mLocationHardware;
    private final Context mContext;
    private final String mPermissionId;

    public FusedLocationHardwareSecure(
            IFusedLocationHardware locationHardware,
            Context context,
            String permissionId) {
        mLocationHardware = locationHardware;
        mContext = context;
        mPermissionId = permissionId;
    }

    private void checkPermissions() {
        mContext.enforceCallingPermission(
                mPermissionId,
                String.format(
                        "Permission '%s' not granted to access FusedLocationHardware",
                        mPermissionId));
    }

    @Override
    public void registerSink(IFusedLocationHardwareSink eventSink) throws RemoteException {
        checkPermissions();
        mLocationHardware.registerSink(eventSink);
    }

    @Override
    public void unregisterSink(IFusedLocationHardwareSink eventSink) throws RemoteException {
        checkPermissions();
        mLocationHardware.unregisterSink(eventSink);
    }

    @Override
    public int getSupportedBatchSize() throws RemoteException {
        checkPermissions();
        return mLocationHardware.getSupportedBatchSize();
    }

    @Override
    public void startBatching(int id, FusedBatchOptions batchOptions) throws RemoteException {
        checkPermissions();
        mLocationHardware.startBatching(id, batchOptions);
    }

    @Override
    public void stopBatching(int id) throws RemoteException {
        checkPermissions();
        mLocationHardware.stopBatching(id);
    }

    @Override
    public void updateBatchingOptions(
            int id,
            FusedBatchOptions batchoOptions
            ) throws RemoteException {
        checkPermissions();
        mLocationHardware.updateBatchingOptions(id, batchoOptions);
    }

    @Override
    public void requestBatchOfLocations(int batchSizeRequested) throws RemoteException {
        checkPermissions();
        mLocationHardware.requestBatchOfLocations(batchSizeRequested);
    }

    @Override
    public boolean supportsDiagnosticDataInjection() throws RemoteException {
        checkPermissions();
        return mLocationHardware.supportsDiagnosticDataInjection();
    }

    @Override
    public void injectDiagnosticData(String data) throws RemoteException {
        checkPermissions();
        mLocationHardware.injectDiagnosticData(data);
    }

    @Override
    public boolean supportsDeviceContextInjection() throws RemoteException {
        checkPermissions();
        return mLocationHardware.supportsDeviceContextInjection();
    }

    @Override
    public void injectDeviceContext(int deviceEnabledContext) throws RemoteException {
        checkPermissions();
        mLocationHardware.injectDeviceContext(deviceEnabledContext);
    }
}
