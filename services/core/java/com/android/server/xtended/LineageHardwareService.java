/*
 * Copyright (C) 2015-2016 The CyanogenMod Project
 *               2017-2019 The LineageOS Project
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
package com.android.server.xtended;

import android.content.Context;
import android.content.Intent;
import android.os.IBinder;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Range;

import com.android.server.LocalServices;
import com.android.server.SystemService;

import com.android.internal.xtended.hardware.ILineageHardwareService;
import com.android.internal.xtended.hardware.LineageHardwareManager;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.android.internal.xtended.app.LineageContextConstants;


/** @hide */
public class LineageHardwareService extends SystemService {

    private static final boolean DEBUG = true;
    private static final String TAG = LineageHardwareService.class.getSimpleName();

    private final Context mContext;
    private final LineageHardwareInterface mLineageHwImpl;

    private interface LineageHardwareInterface {
        public int getSupportedFeatures();
        public boolean get(int feature);
        public boolean set(int feature, boolean enable);
    }

    private class LegacyLineageHardware implements LineageHardwareInterface {

        private int mSupportedFeatures = 0;

        public LegacyLineageHardware() {
        }

        public int getSupportedFeatures() {
            return mSupportedFeatures;
        }

        public boolean get(int feature) {
            switch(feature) {
                default:
                    Log.e(TAG, "feature " + feature + " is not a boolean feature");
                    return false;
            }
        }

        public boolean set(int feature, boolean enable) {
            switch(feature) {
                default:
                    Log.e(TAG, "feature " + feature + " is not a boolean feature");
                    return false;
            }
        }
    }

    private LineageHardwareInterface getImpl(Context context) {
        return new LegacyLineageHardware();
    }

    public LineageHardwareService(Context context) {
        super(context);
        mContext = context;
        mLineageHwImpl = getImpl(context);
        publishBinderService(LineageContextConstants.LINEAGE_HARDWARE_SERVICE, mService);
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == PHASE_BOOT_COMPLETED) {
            Intent intent = new Intent("lineageos.intent.action.INITIALIZE_LINEAGE_HARDWARE");
            intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
            mContext.sendBroadcastAsUser(intent, UserHandle.ALL,
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS");
        }
    }

    @Override
    public void onStart() {
    }

    private final IBinder mService = new ILineageHardwareService.Stub() {

        private boolean isSupported(int feature) {
            return (getSupportedFeatures() & feature) == feature;
        }

        @Override
        public int getSupportedFeatures() {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            return mLineageHwImpl.getSupportedFeatures();
        }

        @Override
        public boolean get(int feature) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(feature)) {
                Log.e(TAG, "feature " + feature + " is not supported");
                return false;
            }
            return mLineageHwImpl.get(feature);
        }

        @Override
        public boolean set(int feature, boolean enable) {
            mContext.enforceCallingOrSelfPermission(
                    "lineageos.permission.HARDWARE_ABSTRACTION_ACCESS", null);
            if (!isSupported(feature)) {
                Log.e(TAG, "feature " + feature + " is not supported");
                return false;
            }
            return mLineageHwImpl.set(feature, enable);
        }
    };
}
