/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.doze;

import static android.app.UiModeManager.ACTION_ENTER_CAR_MODE;
import static android.app.UiModeManager.ACTION_EXIT_CAR_MODE;

import android.app.UiModeManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.statusbar.phone.BiometricUnlockController;

import java.io.PrintWriter;

import javax.inject.Inject;

import dagger.Lazy;

/**
 * Handles suppressing doze on:
 * 1. INITIALIZED, don't allow dozing at all when:
 *      - in CAR_MODE
 *      - device is NOT provisioned
 *      - there's a pending authentication
 * 2. PowerSaveMode active
 *      - no always-on-display (DOZE_AOD)
 *      - continues to allow doze triggers (DOZE, DOZE_REQUEST_PULSE)
 * 3. Suppression changes from the PowerManager API. See {@link PowerManager#suppressAmbientDisplay}
 *      and {@link DozeHost#isAlwaysOnSuppressed()}.
 *      - no always-on-display (DOZE_AOD)
 *      - allow doze triggers (DOZE), but disallow notifications (handled by {@link DozeTriggers})
 *      - See extra check in {@link DozeMachine} to guarantee device never enters always-on states
 */
@DozeScope
public class DozeSuppressor implements DozeMachine.Part {
    private static final String TAG = "DozeSuppressor";

    private DozeMachine mMachine;
    private final DozeHost mDozeHost;
    private final AmbientDisplayConfiguration mConfig;
    private final DozeLog mDozeLog;
    private final BroadcastDispatcher mBroadcastDispatcher;
    private final UiModeManager mUiModeManager;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;

    private boolean mBroadcastReceiverRegistered;

    @Inject
    public DozeSuppressor(
            DozeHost dozeHost,
            AmbientDisplayConfiguration config,
            DozeLog dozeLog,
            BroadcastDispatcher broadcastDispatcher,
            UiModeManager uiModeManager,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy) {
        mDozeHost = dozeHost;
        mConfig = config;
        mDozeLog = dozeLog;
        mBroadcastDispatcher = broadcastDispatcher;
        mUiModeManager = uiModeManager;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                registerBroadcastReceiver();
                mDozeHost.addCallback(mHostCallback);
                checkShouldImmediatelyEndDoze();
                checkShouldImmediatelySuspendDoze();
                break;
            case FINISH:
                destroy();
                break;
            default:
        }
    }

    @Override
    public void destroy() {
        unregisterBroadcastReceiver();
        mDozeHost.removeCallback(mHostCallback);
    }

    private void checkShouldImmediatelySuspendDoze() {
        if (mUiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_CAR) {
            mDozeLog.traceCarModeStarted();
            mMachine.requestState(DozeMachine.State.DOZE_SUSPEND_TRIGGERS);
        }
    }

    private void checkShouldImmediatelyEndDoze() {
        String reason = null;
        if (!mDozeHost.isProvisioned()) {
            reason = "device_unprovisioned";
        } else if (mBiometricUnlockControllerLazy.get().hasPendingAuthentication()) {
            reason = "has_pending_auth";
        }

        if (!TextUtils.isEmpty(reason)) {
            mDozeLog.traceImmediatelyEndDoze(reason);
            mMachine.requestState(DozeMachine.State.FINISH);
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println(" uiMode=" + mUiModeManager.getCurrentModeType());
        pw.println(" hasPendingAuth="
                + mBiometricUnlockControllerLazy.get().hasPendingAuthentication());
        pw.println(" isProvisioned=" + mDozeHost.isProvisioned());
        pw.println(" isAlwaysOnSuppressed=" + mDozeHost.isAlwaysOnSuppressed());
        pw.println(" aodPowerSaveActive=" + mDozeHost.isPowerSaveActive());
    }

    private void registerBroadcastReceiver() {
        if (mBroadcastReceiverRegistered) {
            return;
        }
        IntentFilter filter = new IntentFilter(ACTION_ENTER_CAR_MODE);
        filter.addAction(ACTION_EXIT_CAR_MODE);
        mBroadcastDispatcher.registerReceiver(mBroadcastReceiver, filter);
        mBroadcastReceiverRegistered = true;
    }

    private void unregisterBroadcastReceiver() {
        if (!mBroadcastReceiverRegistered) {
            return;
        }
        mBroadcastDispatcher.unregisterReceiver(mBroadcastReceiver);
        mBroadcastReceiverRegistered = false;
    }

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (ACTION_ENTER_CAR_MODE.equals(action)) {
                mDozeLog.traceCarModeStarted();
                mMachine.requestState(DozeMachine.State.DOZE_SUSPEND_TRIGGERS);
            } else if (ACTION_EXIT_CAR_MODE.equals(action)) {
                mDozeLog.traceCarModeEnded();
                mMachine.requestState(mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT)
                        ? DozeMachine.State.DOZE_AOD : DozeMachine.State.DOZE);
            }
        }
    };

    private DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override
        public void onPowerSaveChanged(boolean active) {
            // handles suppression changes, while DozeMachine#transitionPolicy handles gating
            // transitions to DOZE_AOD
            DozeMachine.State nextState = null;
            if (mDozeHost.isPowerSaveActive()) {
                nextState = DozeMachine.State.DOZE;
            } else if (mMachine.getState() == DozeMachine.State.DOZE
                    && mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT)) {
                nextState = DozeMachine.State.DOZE_AOD;
            }

            if (nextState != null) {
                mDozeLog.tracePowerSaveChanged(mDozeHost.isPowerSaveActive(), nextState);
                mMachine.requestState(nextState);
            }
        }

        @Override
        public void onAlwaysOnSuppressedChanged(boolean suppressed) {
            // handles suppression changes, while DozeMachine#transitionPolicy handles gating
            // transitions to DOZE_AOD
            final DozeMachine.State nextState;
            if (mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT) && !suppressed) {
                nextState = DozeMachine.State.DOZE_AOD;
            } else {
                nextState = DozeMachine.State.DOZE;
            }
            mDozeLog.traceAlwaysOnSuppressedChange(suppressed, nextState);
            mMachine.requestState(nextState);
        }
    };
}
