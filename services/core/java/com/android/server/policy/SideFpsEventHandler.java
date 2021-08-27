/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.policy;

import static android.hardware.fingerprint.FingerprintStateListener.STATE_ENROLLING;
import static android.hardware.fingerprint.FingerprintStateListener.STATE_IDLE;

import android.annotation.NonNull;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.FingerprintManager;
import android.hardware.fingerprint.FingerprintSensorPropertiesInternal;
import android.hardware.fingerprint.FingerprintStateListener;
import android.hardware.fingerprint.IFingerprintAuthenticatorsRegisteredCallback;
import android.os.Handler;
import android.os.PowerManager;
import android.view.WindowManager;

import com.android.internal.R;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Defines behavior for handling interactions between power button events and
 * fingerprint-related operations, for devices where the fingerprint sensor (side fps)
 * lives on the power button.
 */
public class SideFpsEventHandler {
    @NonNull private final Context mContext;
    @NonNull private final Handler mHandler;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final AtomicBoolean mIsSideFps;
    @NonNull private final AtomicBoolean mSideFpsEventHandlerReady;

    private @FingerprintStateListener.State int mFingerprintState;

    SideFpsEventHandler(Context context, Handler handler, PowerManager powerManager) {
        mContext = context;
        mHandler = handler;
        mPowerManager = powerManager;
        mFingerprintState = STATE_IDLE;
        mIsSideFps = new AtomicBoolean(false);
        mSideFpsEventHandlerReady = new AtomicBoolean(false);
    }

    /**
     * Called from {@link PhoneWindowManager} after power button is pressed. Checks fingerprint
     * sensor state and if mFingerprintState = STATE_ENROLLING, displays a dialog confirming intent
     * to turn screen off. If confirmed, the device goes to sleep, and if canceled, the dialog is
     * dismissed.
     * @param eventTime powerPress event time
     * @return true if powerPress was consumed, false otherwise
     */
    public boolean onSinglePressDetected(long eventTime) {
        if (!mSideFpsEventHandlerReady.get() || !mIsSideFps.get()
                || mFingerprintState != STATE_ENROLLING) {
            return false;
        }
        mHandler.post(() -> {
            Dialog confirmScreenOffDialog = new AlertDialog.Builder(mContext)
                    .setTitle(R.string.fp_enrollment_powerbutton_intent_title)
                    .setMessage(R.string.fp_enrollment_powerbutton_intent_message)
                    .setPositiveButton(
                            R.string.fp_enrollment_powerbutton_intent_positive_button,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                    mPowerManager.goToSleep(
                                            eventTime,
                                            PowerManager.GO_TO_SLEEP_REASON_POWER_BUTTON,
                                            0 /* flags */
                                    );
                                }
                            })
                    .setNegativeButton(
                            R.string.fp_enrollment_powerbutton_intent_negative_button,
                            new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(DialogInterface dialog, int which) {
                                    dialog.dismiss();
                                }
                            })
                    .setCancelable(false)
                    .create();
            confirmScreenOffDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_STATUS_BAR_SUB_PANEL);
            confirmScreenOffDialog.show();
        });
        return true;
    }

    /**
     * Awaits notification from PhoneWindowManager that fingerprint service is ready
     * to send updates about power button fps sensor state. Then configures a
     * FingerprintStateListener to receive and record updates to fps state, and
     * registers the FingerprintStateListener in FingerprintManager.
     */
    public void onFingerprintSensorReady() {
        final PackageManager pm = mContext.getPackageManager();
        if (!pm.hasSystemFeature(PackageManager.FEATURE_FINGERPRINT)) return;
        FingerprintManager fingerprintManager =
                mContext.getSystemService(FingerprintManager.class);
        fingerprintManager.addAuthenticatorsRegisteredCallback(
                new IFingerprintAuthenticatorsRegisteredCallback.Stub() {
                    @Override
                    public void onAllAuthenticatorsRegistered(
                            List<FingerprintSensorPropertiesInternal> sensors) {
                        mIsSideFps.set(fingerprintManager.isPowerbuttonFps());
                        FingerprintStateListener fingerprintStateListener =
                                new FingerprintStateListener() {
                            @Override
                            public void onStateChanged(
                                    @FingerprintStateListener.State int newState) {
                                mFingerprintState = newState;
                            }
                        };
                        fingerprintManager.registerFingerprintStateListener(
                                fingerprintStateListener);
                        mSideFpsEventHandlerReady.set(true);
                    }
                });
    }
}
