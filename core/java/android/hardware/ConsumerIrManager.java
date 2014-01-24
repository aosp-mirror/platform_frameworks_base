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

package android.hardware;

import android.content.Context;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.util.Log;

/**
 * Class that operates consumer infrared on the device.
 *
 * <p>
 * To obtain an instance of the system infrared transmitter, call
 * {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with
 * {@link android.content.Context#CONSUMER_IR_SERVICE} as the argument.
 * </p>
 */
public final class ConsumerIrManager {
    private static final String TAG = "ConsumerIr";

    private final String mPackageName;
    private final IConsumerIrService mService;

    /**
     * @hide to prevent subclassing from outside of the framework
     */
    public ConsumerIrManager(Context context) {
        mPackageName = context.getPackageName();
        mService = IConsumerIrService.Stub.asInterface(
                ServiceManager.getService(Context.CONSUMER_IR_SERVICE));
    }

    /**
     * Check whether the device has an infrared emitter.
     *
     * @return true if the device has an infrared emitter, else false.
     */
    public boolean hasIrEmitter() {
        if (mService == null) {
            Log.w(TAG, "no consumer ir service.");
            return false;
        }

        try {
            return mService.hasIrEmitter();
        } catch (RemoteException e) {
        }
        return false;
    }

    /**
     * Transmit an infrared pattern
     * <p>
     * This method is synchronous; when it returns the pattern has
     * been transmitted. Only patterns shorter than 2 seconds will
     * be transmitted.
     * </p>
     *
     * @param carrierFrequency The IR carrier frequency in Hertz.
     * @param pattern The alternating on/off pattern in microseconds to transmit.
     */
    public void transmit(int carrierFrequency, int[] pattern) {
        if (mService == null) {
            Log.w(TAG, "failed to transmit; no consumer ir service.");
            return;
        }

        try {
            mService.transmit(mPackageName, carrierFrequency, pattern);
        } catch (RemoteException e) {
            Log.w(TAG, "failed to transmit.", e);
        }
    }

    /**
     * Represents a range of carrier frequencies (inclusive) on which the
     * infrared transmitter can transmit
     */
    public final class CarrierFrequencyRange {
        private final int mMinFrequency;
        private final int mMaxFrequency;

        /**
         * Create a segment of a carrier frequency range.
         *
         * @param min The minimum transmittable frequency in this range segment.
         * @param max The maximum transmittable frequency in this range segment.
         */
        public CarrierFrequencyRange(int min, int max) {
            mMinFrequency = min;
            mMaxFrequency = max;
        }

        /**
         * Get the minimum (inclusive) frequency in this range segment.
         */
        public int getMinFrequency() {
            return mMinFrequency;
        }

        /**
         * Get the maximum (inclusive) frequency in this range segment.
         */
        public int getMaxFrequency() {
            return mMaxFrequency;
        }
    };

    /**
     * Query the infrared transmitter's supported carrier frequencies
     *
     * @return an array of
     * {@link android.hardware.ConsumerIrManager.CarrierFrequencyRange}
     * objects representing the ranges that the transmitter can support, or
     * null if there was an error communicating with the Consumer IR Service.
     */
    public CarrierFrequencyRange[] getCarrierFrequencies() {
        if (mService == null) {
            Log.w(TAG, "no consumer ir service.");
            return null;
        }

        try {
            int[] freqs = mService.getCarrierFrequencies();
            if (freqs.length % 2 != 0) {
                Log.w(TAG, "consumer ir service returned an uneven number of frequencies.");
                return null;
            }
            CarrierFrequencyRange[] range = new CarrierFrequencyRange[freqs.length / 2];

            for (int i = 0; i < freqs.length; i += 2) {
                range[i / 2] = new CarrierFrequencyRange(freqs[i], freqs[i+1]);
            }
            return range;
        } catch (RemoteException e) {
        }
        return null;
    }

}
