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

package com.android.location.provider;

import android.hardware.location.IFusedLocationHardware;
import android.hardware.location.IFusedLocationHardwareSink;

import android.location.Location;

import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;

/**
 * Class that exposes IFusedLocationHardware functionality to unbundled services.
 */
public final class FusedLocationHardware {
    private final String TAG = "FusedLocationHardware";

    private IFusedLocationHardware mLocationHardware;

    // the list uses a copy-on-write pattern to update its contents
    HashMap<FusedLocationHardwareSink, DispatcherHandler> mSinkList =
            new HashMap<FusedLocationHardwareSink, DispatcherHandler>();

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

    /**
     * @hide
     */
    public FusedLocationHardware(IFusedLocationHardware locationHardware) {
        mLocationHardware = locationHardware;
    }

    /*
     * Methods to provide a Facade for IFusedLocationHardware
     */
    public void registerSink(FusedLocationHardwareSink sink, Looper looper) {
        if(sink == null || looper == null) {
            throw new IllegalArgumentException("Parameter sink and looper cannot be null.");
        }

        boolean registerSink;
        synchronized (mSinkList) {
            // register only on first insertion
            registerSink = mSinkList.size() == 0;
            // guarantee uniqueness
            if(mSinkList.containsKey(sink)) {
                return;
            }

            HashMap<FusedLocationHardwareSink, DispatcherHandler> newSinkList =
                    new HashMap<FusedLocationHardwareSink, DispatcherHandler>(mSinkList);
            newSinkList.put(sink, new DispatcherHandler(looper));
            mSinkList = newSinkList;
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
            throw new IllegalArgumentException("Parameter sink cannot be null.");
        }

        boolean unregisterSink;
        synchronized(mSinkList) {
            if(!mSinkList.containsKey(sink)) {
                //done
                return;
            }

            HashMap<FusedLocationHardwareSink, DispatcherHandler> newSinkList =
                    new HashMap<FusedLocationHardwareSink, DispatcherHandler>(mSinkList);
            newSinkList.remove(sink);
            //unregister after the last sink
            unregisterSink = newSinkList.size() == 0;

            mSinkList = newSinkList;
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
     * Helper methods and classes
     */
    private class DispatcherHandler extends Handler {
        public static final int DISPATCH_LOCATION = 1;
        public static final int DISPATCH_DIAGNOSTIC_DATA = 2;

        public DispatcherHandler(Looper looper) {
            super(looper, null /*callback*/ , true /*async*/);
        }

        @Override
        public void handleMessage(Message message) {
            MessageCommand command = (MessageCommand) message.obj;
            switch(message.what) {
                case DISPATCH_LOCATION:
                    command.dispatchLocation();
                    break;
                case DISPATCH_DIAGNOSTIC_DATA:
                    command.dispatchDiagnosticData();
                default:
                    Log.e(TAG, "Invalid dispatch message");
                    break;
            }
        }
    }

    private class MessageCommand {
        private final FusedLocationHardwareSink mSink;
        private final Location[] mLocations;
        private final String mData;

        public MessageCommand(
                FusedLocationHardwareSink sink,
                Location[] locations,
                String data) {
            mSink = sink;
            mLocations = locations;
            mData = data;
        }

        public void dispatchLocation() {
            mSink.onLocationAvailable(mLocations);
        }

        public void dispatchDiagnosticData() {
            mSink.onDiagnosticDataAvailable(mData);
        }
    }

    private void dispatchLocations(Location[] locations) {
        HashMap<FusedLocationHardwareSink, DispatcherHandler> sinks;
        synchronized (mSinkList) {
            sinks = mSinkList;
        }

        for(Map.Entry<FusedLocationHardwareSink, DispatcherHandler> entry : sinks.entrySet()) {
            Message message = Message.obtain(
                    entry.getValue(),
                    DispatcherHandler.DISPATCH_LOCATION,
                    new MessageCommand(entry.getKey(), locations, null /*data*/));
            message.sendToTarget();
        }
    }

    private void dispatchDiagnosticData(String data) {
        HashMap<FusedLocationHardwareSink, DispatcherHandler> sinks;
        synchronized(mSinkList) {
            sinks = mSinkList;
        }

        for(Map.Entry<FusedLocationHardwareSink, DispatcherHandler> entry : sinks.entrySet()) {
            Message message = Message.obtain(
                    entry.getValue(),
                    DispatcherHandler.DISPATCH_DIAGNOSTIC_DATA,
                    new MessageCommand(entry.getKey(), null /*locations*/, data));
            message.sendToTarget();
        }
    }
}
