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

package com.android.server.location.injector;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.SystemClock;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;

import com.android.server.FgThread;

import java.util.Objects;

/**
 * Provides helpers for emergency sessions.
 */
public class SystemEmergencyHelper extends EmergencyHelper {

    private final Context mContext;
    private final EmergencyCallTelephonyCallback mEmergencyCallTelephonyCallback =
            new EmergencyCallTelephonyCallback();

    TelephonyManager mTelephonyManager;

    boolean mIsInEmergencyCall;
    long mEmergencyCallEndRealtimeMs = Long.MIN_VALUE;

    public SystemEmergencyHelper(Context context) {
        mContext = context;
    }

    /** Called when system is ready. */
    public synchronized void onSystemReady() {
        if (mTelephonyManager != null) {
            return;
        }

        mTelephonyManager = Objects.requireNonNull(
                mContext.getSystemService(TelephonyManager.class));

        // TODO: this doesn't account for multisim phones

        mTelephonyManager.registerTelephonyCallback(FgThread.getExecutor(),
                mEmergencyCallTelephonyCallback);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (!Intent.ACTION_NEW_OUTGOING_CALL.equals(intent.getAction())) {
                    return;
                }

                synchronized (SystemEmergencyHelper.this) {
                    mIsInEmergencyCall = mTelephonyManager.isEmergencyNumber(
                            intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER));
                }
            }
        }, new IntentFilter(Intent.ACTION_NEW_OUTGOING_CALL));
    }

    @Override
    public synchronized boolean isInEmergency(long extensionTimeMs) {
        if (mTelephonyManager == null) {
            return false;
        }

        boolean isInExtensionTime = mEmergencyCallEndRealtimeMs != Long.MIN_VALUE
                && (SystemClock.elapsedRealtime() - mEmergencyCallEndRealtimeMs) < extensionTimeMs;

        return mIsInEmergencyCall
                || isInExtensionTime
                || mTelephonyManager.getEmergencyCallbackMode()
                || mTelephonyManager.isInEmergencySmsMode();
    }

    private class EmergencyCallTelephonyCallback extends TelephonyCallback implements
            TelephonyCallback.CallStateListener{

        EmergencyCallTelephonyCallback() {}

        @Override
        public void onCallStateChanged(int state) {
            if (state == TelephonyManager.CALL_STATE_IDLE) {
                synchronized (SystemEmergencyHelper.this) {
                    if (mIsInEmergencyCall) {
                        mEmergencyCallEndRealtimeMs = SystemClock.elapsedRealtime();
                        mIsInEmergencyCall = false;
                    }
                }
            }
        }
    }
}
