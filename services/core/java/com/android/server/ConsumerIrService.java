/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.hardware.input.InputManager;
import android.hardware.IConsumerIrService;
import android.os.Handler;
import android.os.PowerManager;
import android.os.Process;
import android.os.RemoteException;
import android.os.IBinder;
import android.os.Binder;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.WorkSource;
import android.provider.Settings;
import android.provider.Settings.SettingNotFoundException;
import android.util.Slog;
import android.view.InputDevice;

import java.lang.RuntimeException;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.ListIterator;

public class ConsumerIrService extends IConsumerIrService.Stub {
    private static final String TAG = "ConsumerIrService";

    private static final int MAX_XMIT_TIME = 2000000; /* in microseconds */

    private static native int halOpen();
    private static native int halTransmit(int halObject, int carrierFrequency, int[] pattern);
    private static native int[] halGetCarrierFrequencies(int halObject);

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final int mHal;
    private final Object mHalLock = new Object();

    ConsumerIrService(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);

        mHal = halOpen();
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)) {
            if (mHal == 0) {
                throw new RuntimeException("FEATURE_CONSUMER_IR present, but no IR HAL loaded!");
            }
        } else if (mHal != 0) {
            throw new RuntimeException("IR HAL present, but FEATURE_CONSUMER_IR is not set!");
        }
    }

    @Override
    public boolean hasIrEmitter() {
        return mHal != 0;
    }

    private void throwIfNoIrEmitter() {
        if (mHal == 0) {
            throw new UnsupportedOperationException("IR emitter not available");
        }
    }


    @Override
    public void transmit(String packageName, int carrierFrequency, int[] pattern) {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.TRANSMIT_IR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires TRANSMIT_IR permission");
        }

        long totalXmitTime = 0;

        for (int slice : pattern) {
            if (slice <= 0) {
                throw new IllegalArgumentException("Non-positive IR slice");
            }
            totalXmitTime += slice;
        }

        if (totalXmitTime > MAX_XMIT_TIME ) {
            throw new IllegalArgumentException("IR pattern too long");
        }

        throwIfNoIrEmitter();

        // Right now there is no mechanism to ensure fair queing of IR requests
        synchronized (mHalLock) {
            int err = halTransmit(mHal, carrierFrequency, pattern);

            if (err < 0) {
                Slog.e(TAG, "Error transmitting: " + err);
            }
        }
    }

    @Override
    public int[] getCarrierFrequencies() {
        if (mContext.checkCallingOrSelfPermission(android.Manifest.permission.TRANSMIT_IR)
                != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("Requires TRANSMIT_IR permission");
        }

        throwIfNoIrEmitter();

        synchronized(mHalLock) {
            return halGetCarrierFrequencies(mHal);
        }
    }
}
