/*
 * Copyright (C) 2008 The Android Open Source Project
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

package com.android.internal.location;

import android.Manifest;
import android.annotation.RequiresPermission;
import android.content.Context;
import android.content.pm.PackageManager;
import android.location.LocationManager;
import android.os.SystemClock;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.telephony.emergency.EmergencyNumber;
import android.util.Log;

import com.android.internal.annotations.KeepForWeakReference;
import com.android.internal.telephony.flags.Flags;

import java.util.concurrent.TimeUnit;

/**
 * A GPS Network-initiated Handler class used by LocationManager.
 *
 * {@hide}
 */
public class GpsNetInitiatedHandler {

    private static final String TAG = "GpsNetInitiatedHandler";

    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private final Context mContext;
    private final TelephonyManager mTelephonyManager;

    // parent gps location provider
    private final LocationManager mLocationManager;

    // Set to true if the phone is having emergency call.
    private volatile boolean mIsInEmergencyCall;


    // End time of emergency call, and extension, if set
    private volatile long mCallEndElapsedRealtimeMillis = 0;
    private volatile long mEmergencyExtensionMillis = 0;

    /** Callbacks for Emergency call events. */
    public interface EmergencyCallCallback {
        /** Callback invoked when an emergency call starts */
        void onEmergencyCallStart(int subId);
        /** Callback invoked when an emergency call ends */
        void onEmergencyCallEnd();
    }

    private class EmergencyCallListener extends TelephonyCallback implements
            TelephonyCallback.OutgoingEmergencyCallListener,
            TelephonyCallback.CallStateListener {

        @Override
        @RequiresPermission(Manifest.permission.READ_ACTIVE_EMERGENCY_SESSION)
        public void onOutgoingEmergencyCall(EmergencyNumber placedEmergencyNumber,
                int subscriptionId) {
            mIsInEmergencyCall = true;
            if (DEBUG) Log.d(TAG, "onOutgoingEmergencyCall(): inEmergency = " + getInEmergency());
            mEmergencyCallCallback.onEmergencyCallStart(subscriptionId);
        }

        @Override
        @RequiresPermission(Manifest.permission.READ_PHONE_STATE)
        public void onCallStateChanged(int state) {
            if (DEBUG) Log.d(TAG, "onCallStateChanged(): state is " + state);
            // listening for emergency call ends
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                if (mIsInEmergencyCall) {
                    mCallEndElapsedRealtimeMillis = SystemClock.elapsedRealtime();
                    mIsInEmergencyCall = false;
                    mEmergencyCallCallback.onEmergencyCallEnd();
                }
            }
        }
    }

    // The internal implementation of TelephonyManager uses WeakReference so we have to keep a
    // reference here.
    @KeepForWeakReference
    private final EmergencyCallListener mEmergencyCallListener = new EmergencyCallListener();

    private final EmergencyCallCallback mEmergencyCallCallback;

    public GpsNetInitiatedHandler(Context context,
                                  EmergencyCallCallback emergencyCallCallback,
                                  boolean isSuplEsEnabled) {
        mContext = context;
        mEmergencyCallCallback = emergencyCallCallback;

        mLocationManager = (LocationManager)context.getSystemService(Context.LOCATION_SERVICE);
        mTelephonyManager =
            (TelephonyManager)context.getSystemService(Context.TELEPHONY_SERVICE);
        mTelephonyManager.registerTelephonyCallback(mContext.getMainExecutor(),
                mEmergencyCallListener);

    }

    /**
     * Determines whether device is in user-initiated emergency session based on the following
     * 1. If the user is making an emergency call, this is provided by actively
     *    monitoring the outgoing phone number;
     * 2. If the user has recently ended an emergency call, and the device is in a configured time
     *    window after the end of that call.
     * 3. If the device is in a emergency callback state, this is provided by querying
     *    TelephonyManager.
     * 4. If the user has recently sent an Emergency SMS and telephony reports that it is in
     *    emergency SMS mode, this is provided by querying TelephonyManager.
     * @return true if is considered in user initiated emergency mode for NI purposes
     */
    public boolean getInEmergency() {
        return getInEmergency(mEmergencyExtensionMillis);
    }

    /**
     * Determines whether device is in user-initiated emergency session with the given extension
     * time.
     *
     * @return true if is considered in user initiated emergency mode for NI purposes within the
     * given extension time.
     *
     * @see {@link #getInEmergency()}
     */
    public boolean getInEmergency(long emergencyExtensionMillis) {
        boolean isInEmergencyExtension =
                (mCallEndElapsedRealtimeMillis > 0)
                        && ((SystemClock.elapsedRealtime() - mCallEndElapsedRealtimeMillis)
                        < emergencyExtensionMillis);
        boolean isInEmergencyCallback = false;
        boolean isInEmergencySmsMode = false;
        if (!Flags.enforceTelephonyFeatureMappingForPublicApis()) {
            isInEmergencyCallback = mTelephonyManager.getEmergencyCallbackMode();
            isInEmergencySmsMode = mTelephonyManager.isInEmergencySmsMode();
        } else {
            PackageManager pm = mContext.getPackageManager();
            if (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_CALLING)) {
                isInEmergencyCallback = mTelephonyManager.getEmergencyCallbackMode();
            }
            if (pm != null && pm.hasSystemFeature(PackageManager.FEATURE_TELEPHONY_MESSAGING)) {
                isInEmergencySmsMode = mTelephonyManager.isInEmergencySmsMode();
            }
        }
        return mIsInEmergencyCall || isInEmergencyCallback || isInEmergencyExtension
                || isInEmergencySmsMode;
    }

    public void setEmergencyExtensionSeconds(int emergencyExtensionSeconds) {
        mEmergencyExtensionMillis = TimeUnit.SECONDS.toMillis(emergencyExtensionSeconds);
    }
}
