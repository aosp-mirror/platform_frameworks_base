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

import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.IConsumerIrService;
import android.os.PowerManager;
import android.util.Slog;

import java.lang.RuntimeException;

public class ConsumerIrService extends IConsumerIrService.Stub {
    private static final String TAG = "ConsumerIrService";

    private static final int MAX_XMIT_TIME = 2000000; /* in microseconds */

    private static native boolean halOpen();
    private static native int halTransmit(int carrierFrequency, int[] pattern);
    private static native int[] halGetCarrierFrequencies();

    private final Context mContext;
    private final PowerManager.WakeLock mWakeLock;
    private final boolean mHasNativeHal;
    private final Object mHalLock = new Object();

    ConsumerIrService(Context context) {
        mContext = context;
        PowerManager pm = (PowerManager)context.getSystemService(
                Context.POWER_SERVICE);
        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG);
        mWakeLock.setReferenceCounted(true);

        mHasNativeHal = halOpen();
        if (mContext.getPackageManager().hasSystemFeature(PackageManager.FEATURE_CONSUMER_IR)) {
            if (!mHasNativeHal) {
                throw new RuntimeException("FEATURE_CONSUMER_IR present, but no IR HAL loaded!");
            }
        }
    }

    @Override
    public boolean hasIrEmitter() {
        return mHasNativeHal;
    }

    private void throwIfNoIrEmitter() {
        if (!mHasNativeHal) {
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
            int err = halTransmit(carrierFrequency, pattern);

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
            return halGetCarrierFrequencies();
        }
    }
}
