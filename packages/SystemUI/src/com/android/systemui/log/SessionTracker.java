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

package com.android.systemui.log;

import static android.app.StatusBarManager.ALL_SESSIONS;
import static android.app.StatusBarManager.SESSION_BIOMETRIC_PROMPT;
import static android.app.StatusBarManager.SESSION_KEYGUARD;

import android.annotation.Nullable;
import android.os.RemoteException;
import android.util.Log;

import androidx.annotation.NonNull;

import com.android.internal.logging.InstanceId;
import com.android.internal.logging.InstanceIdSequence;
import com.android.internal.statusbar.IStatusBarService;
import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.keyguard.KeyguardUpdateMonitorCallback;
import com.android.systemui.CoreStartable;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.dagger.SysUISingleton;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import java.io.PrintWriter;
import java.util.HashMap;
import java.util.Map;

import javax.inject.Inject;

/**
 * Track Session InstanceIds to be used for metrics logging to correlate logs in the same
 * session. Can be used across processes via StatusBarManagerService#registerSessionListener
 */
@SysUISingleton
public class SessionTracker implements CoreStartable {
    private static final String TAG = "SessionTracker";

    // To enable logs: `adb shell setprop log.tag.SessionTracker DEBUG` & restart sysui
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    // At most 20 bits: ~1m possibilities, ~0.5% probability of collision in 100 values
    private final InstanceIdSequence mInstanceIdGenerator = new InstanceIdSequence(1 << 20);

    private final IStatusBarService mStatusBarManagerService;
    private final AuthController mAuthController;
    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final KeyguardStateController mKeyguardStateController;
    private final Map<Integer, InstanceId> mSessionToInstanceId = new HashMap<>();

    private boolean mKeyguardSessionStarted;

    @Inject
    public SessionTracker(
            IStatusBarService statusBarService,
            AuthController authController,
            KeyguardUpdateMonitor keyguardUpdateMonitor,
            KeyguardStateController keyguardStateController
    ) {
        mStatusBarManagerService = statusBarService;
        mAuthController = authController;
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mKeyguardStateController = keyguardStateController;
    }

    @Override
    public void start() {
        mAuthController.addCallback(mAuthControllerCallback);
        mKeyguardUpdateMonitor.registerCallback(mKeyguardUpdateMonitorCallback);
        mKeyguardStateController.addCallback(mKeyguardStateCallback);

        if (mKeyguardStateController.isShowing()) {
            mKeyguardSessionStarted = true;
            startSession(SESSION_KEYGUARD);
        }
    }

    /**
     * Get the session ID associated with the passed session type.
     */
    public @Nullable InstanceId getSessionId(int type) {
        return mSessionToInstanceId.getOrDefault(type, null);
    }

    private void startSession(int type) {
        if (mSessionToInstanceId.getOrDefault(type, null) != null) {
            Log.e(TAG, "session [" + getString(type) + "] was already started");
            return;
        }

        final InstanceId instanceId = mInstanceIdGenerator.newInstanceId();
        mSessionToInstanceId.put(type, instanceId);
        try {
            if (DEBUG) {
                Log.d(TAG, "Session start for [" + getString(type) + "] id=" + instanceId);
            }
            mStatusBarManagerService.onSessionStarted(type, instanceId);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send onSessionStarted for session="
                    + "[" + getString(type) + "]", e);
        }
    }

    private void endSession(int type) {
        if (mSessionToInstanceId.getOrDefault(type, null) == null) {
            Log.e(TAG, "session [" + getString(type) + "] was not started");
            return;
        }

        final InstanceId instanceId = mSessionToInstanceId.get(type);
        mSessionToInstanceId.put(type, null);
        try {
            if (DEBUG) {
                Log.d(TAG, "Session end for [" + getString(type) + "] id=" + instanceId);
            }
            mStatusBarManagerService.onSessionEnded(type, instanceId);
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send onSessionEnded for session="
                    + "[" + getString(type) + "]", e);
        }
    }

    public KeyguardUpdateMonitorCallback mKeyguardUpdateMonitorCallback =
            new KeyguardUpdateMonitorCallback() {
        @Override
        public void onStartedGoingToSleep(int why) {
            if (mKeyguardSessionStarted) {
                endSession(SESSION_KEYGUARD);
            }

            // Start a new session whenever the device goes to sleep
            mKeyguardSessionStarted = true;
            startSession(SESSION_KEYGUARD);
        }
    };


    public KeyguardStateController.Callback mKeyguardStateCallback =
            new KeyguardStateController.Callback() {
        public void onKeyguardShowingChanged() {
            boolean wasSessionStarted = mKeyguardSessionStarted;
            boolean keyguardShowing = mKeyguardStateController.isShowing();
            if (keyguardShowing && !wasSessionStarted) {
                // the keyguard can start showing without the device going to sleep (ie: lockdown
                // from the power button), so we start a new keyguard session when the keyguard is
                // newly shown in addition to when the device starts going to sleep
                mKeyguardSessionStarted = true;
                startSession(SESSION_KEYGUARD);
            } else if (!keyguardShowing && wasSessionStarted) {
                mKeyguardSessionStarted = false;
                endSession(SESSION_KEYGUARD);
            }
        }
    };

    public AuthController.Callback mAuthControllerCallback = new AuthController.Callback() {
        @Override
        public void onBiometricPromptShown() {
            startSession(SESSION_BIOMETRIC_PROMPT);
        }

        @Override
        public void onBiometricPromptDismissed() {
            endSession(SESSION_BIOMETRIC_PROMPT);
        }
    };

    @Override
    public void dump(@NonNull PrintWriter pw, @NonNull String[] args) {
        for (int session : ALL_SESSIONS) {
            pw.println("  " + getString(session)
                    + " instanceId=" + mSessionToInstanceId.get(session));
        }
    }

    /**
     * @return the string representation of a SINGLE SessionFlag. Combined SessionFlags will be
     * considered unknown.
     */
    public static String getString(int sessionType) {
        if (sessionType == SESSION_KEYGUARD) {
            return "KEYGUARD";
        } else if (sessionType == SESSION_BIOMETRIC_PROMPT) {
            return "BIOMETRIC_PROMPT";
        }

        return "unknownType=" + sessionType;
    }
}
