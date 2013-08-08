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
 * WITHOUT WARRANTIES OR CONDITIOS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.location.provider;

import android.hardware.location.IFusedLocationHardware;
import android.hardware.location.IFusedLocationHardwareSink;

import android.location.Location;

import android.os.RemoteException;
import android.util.Log;

import java.util.ArrayList;

/**
 * Class that exposes IFusedLocationHardware functionality to unbundled services.
 * Namely this is used by GmsCore Fused Location Provider.
 */
public final class FusedLocationHardware {
    private final String TAG = "FusedLocationHardware";

    private IFusedLocationHardware mLocationHardware;
    ArrayList<FusedLocationHardwareSink> mSinkList = new ArrayList<FusedLocationHardwareSink>();

    private IFusedLocationHardwareSink mInternalSink = new IFusedLocationHardwareSink.Stub() {
        @Override
        public void onLocationAvailable(Location[] locations) {
            dispatchLocations(locations);
        }

        @Override
        public void onDiagnosticDataAvailable(String data) {
            dispatchDiagnosticData(data);
        }
    };

    public FusedLocationHardware(IFusedLocationHardware locationHardware) {
        mLocationHardware = locationHardware;
    }

    /*
     * Methods to provide a Facade for IFusedLocationHardware
     */
    public void registerSink(FusedLocationHardwareSink sink) {
        if(sink == null) {
            return;
        }

        boolean registerSink = false;
        synchronized (mSinkList) {
            // register only on first insertion
            registerSink = mSinkList.size() == 0;
            // guarantee uniqueness
            if(!mSinkList.contains(sink)) {
                mSinkList.add(sink);
            }
        }

        if(registerSink) {
            try {
                mLocationHardware.registerSink(mInternalSink);
            } catch(RemoteException e) {
                Log.e(TAG, "RemoteException at registerSink");
            }
        }
    }

    public void unregisterSink(FusedLocationHardwareSink sink) {
        if(sink == null) {
            return;
        }

        boolean unregisterSink = false;
        synchronized(mSinkList) {
            mSinkList.remove(sink);
            // unregister after the last sink
            unregisterSink = mSinkList.size() == 0;
        }

        if(unregisterSink) {
            try {
                mLocationHardware.unregisterSink(mInternalSink);
            } catch(RemoteException e) {
                Log.e(TAG, "RemoteException at unregisterSink");
            }
        }
    }

    public int getSupportedBatchSize() {
        try {
            return mLocationHardware.getSupportedBatchSize();
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at getSupportedBatchSize");
            return 0;
        }
    }

    public void startBatching(int id, GmsFusedBatchOptions batchOptions) {
        try {
            mLocationHardware.startBatching(id, batchOptions.getParcelableOptions());
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at startBatching");
        }
    }

    public void stopBatching(int id) {
        try {
            mLocationHardware.stopBatching(id);
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at stopBatching");
        }
    }

    public void updateBatchingOptions(int id, GmsFusedBatchOptions batchOptions) {
        try {
            mLocationHardware.updateBatchingOptions(id, batchOptions.getParcelableOptions());
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at updateBatchingOptions");
        }
    }

    public void requestBatchOfLocations(int batchSizeRequest) {
        try {
            mLocationHardware.requestBatchOfLocations(batchSizeRequest);
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at requestBatchOfLocations");
        }
    }

    public boolean supportsDiagnosticDataInjection() {
        try {
            return mLocationHardware.supportsDiagnosticDataInjection();
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at supportsDiagnisticDataInjection");
            return false;
        }
    }

    public void injectDiagnosticData(String data) {
        try {
            mLocationHardware.injectDiagnosticData(data);
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at injectDiagnosticData");
        }
    }

    public boolean supportsDeviceContextInjection() {
        try {
            return mLocationHardware.supportsDeviceContextInjection();
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at supportsDeviceContextInjection");
            return false;
        }
    }

    public void injectDeviceContext(int deviceEnabledContext) {
        try {
            mLocationHardware.injectDeviceContext(deviceEnabledContext);
        } catch(RemoteException e) {
            Log.e(TAG, "RemoteException at injectDeviceContext");
        }
    }

    /*
     * Helper methods
     */
    private void dispatchLocations(Location[] locations) {
        ArrayList<FusedLocationHardwareSink> sinks = null;
        synchronized (mSinkList) {
            sinks = new ArrayList<FusedLocationHardwareSink>(mSinkList);
        }

        for(FusedLocationHardwareSink sink : sinks) {
            sink.onLocationAvailable(locations);
        }
    }

    private void dispatchDiagnosticData(String data) {
        ArrayList<FusedLocationHardwareSink> sinks = null;
        synchronized(mSinkList) {
            sinks = new ArrayList<FusedLocationHardwareSink>(mSinkList);
        }

        for(FusedLocationHardwareSink sink : sinks) {
            sink.onDiagnosticDataAvailable(data);
        }
    }
}
