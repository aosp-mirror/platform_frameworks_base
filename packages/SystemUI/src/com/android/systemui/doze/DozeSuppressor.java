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

import static android.content.res.Configuration.UI_MODE_TYPE_CAR;

import android.hardware.display.AmbientDisplayConfiguration;
import android.os.PowerManager;
import android.text.TextUtils;

import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.statusbar.phone.BiometricUnlockController;

import dagger.Lazy;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Handles suppressing doze on:
 * 1. INITIALIZED, don't allow dozing at all when:
 *      - in CAR_MODE, in this scenario the device is asleep and won't listen for any triggers
 *      to wake up. In this state, no UI shows. Unlike other conditions, this suppression is only
 *      temporary and stops when the device exits CAR_MODE
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

    private DozeMachine mMachine;
    private final DozeHost mDozeHost;
    private final AmbientDisplayConfiguration mConfig;
    private final DozeLog mDozeLog;
    private final Lazy<BiometricUnlockController> mBiometricUnlockControllerLazy;
    private final UserTracker mUserTracker;

    private boolean mIsCarModeEnabled = false;

    @Inject
    public DozeSuppressor(
            DozeHost dozeHost,
            AmbientDisplayConfiguration config,
            DozeLog dozeLog,
            Lazy<BiometricUnlockController> biometricUnlockControllerLazy,
            UserTracker userTracker) {
        mDozeHost = dozeHost;
        mConfig = config;
        mDozeLog = dozeLog;
        mBiometricUnlockControllerLazy = biometricUnlockControllerLazy;
        mUserTracker = userTracker;
    }

    @Override
    public void onUiModeTypeChanged(int newUiModeType) {
        boolean isCarModeEnabled = newUiModeType == UI_MODE_TYPE_CAR;
        if (mIsCarModeEnabled == isCarModeEnabled) {
            return;
        }
        mIsCarModeEnabled = isCarModeEnabled;
        // Do not handle the event if doze machine is not initialized yet.
        // It will be handled upon initialization.
        if (mMachine.isUninitializedOrFinished()) {
            return;
        }
        if (mIsCarModeEnabled) {
            handleCarModeStarted();
        } else {
            handleCarModeExited();
        }
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
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
        mDozeHost.removeCallback(mHostCallback);
    }

    private void checkShouldImmediatelySuspendDoze() {
        if (mIsCarModeEnabled) {
            handleCarModeStarted();
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
        pw.println(" isCarModeEnabled=" + mIsCarModeEnabled);
        pw.println(" hasPendingAuth="
                + mBiometricUnlockControllerLazy.get().hasPendingAuthentication());
        pw.println(" isProvisioned=" + mDozeHost.isProvisioned());
        pw.println(" isAlwaysOnSuppressed=" + mDozeHost.isAlwaysOnSuppressed());
        pw.println(" aodPowerSaveActive=" + mDozeHost.isPowerSaveActive());
    }

    private void handleCarModeExited() {
        mDozeLog.traceCarModeEnded();
        mMachine.requestState(mConfig.alwaysOnEnabled(mUserTracker.getUserId())
                ? DozeMachine.State.DOZE_AOD : DozeMachine.State.DOZE);
    }

    private void handleCarModeStarted() {
        mDozeLog.traceCarModeStarted();
        mMachine.requestState(DozeMachine.State.DOZE_SUSPEND_TRIGGERS);
    }

    private final DozeHost.Callback mHostCallback = new DozeHost.Callback() {
        @Override
        public void onPowerSaveChanged(boolean active) {
            // handles suppression changes, while DozeMachine#transitionPolicy handles gating
            // transitions to DOZE_AOD
            DozeMachine.State nextState = null;
            if (mDozeHost.isPowerSaveActive()) {
                nextState = DozeMachine.State.DOZE;
            } else if (mMachine.getState() == DozeMachine.State.DOZE
                    && mConfig.alwaysOnEnabled(mUserTracker.getUserId())) {
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
            if (mConfig.alwaysOnEnabled(mUserTracker.getUserId()) && !suppressed) {
                nextState = DozeMachine.State.DOZE_AOD;
            } else {
                nextState = DozeMachine.State.DOZE;
            }
            mDozeLog.traceAlwaysOnSuppressedChange(suppressed, nextState);
            mMachine.requestState(nextState);
        }
    };
}
