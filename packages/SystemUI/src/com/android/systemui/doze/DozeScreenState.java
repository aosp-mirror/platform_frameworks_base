/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.systemui.doze;

import static com.android.systemui.doze.DozeMachine.State.DOZE;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSED;
import static com.android.systemui.doze.DozeMachine.State.DOZE_AOD_PAUSING;
import static com.android.systemui.doze.DozeMachine.State.DOZE_PULSE_DONE;

import android.os.Handler;
import android.util.Log;
import android.view.Display;

import androidx.annotation.Nullable;

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.biometrics.AuthController;
import com.android.systemui.biometrics.UdfpsController;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.doze.dagger.WrappedService;
import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

import javax.inject.Inject;
import javax.inject.Provider;

/**
 * Controls the screen when dozing.
 */
@DozeScope
public class DozeScreenState implements DozeMachine.Part {

    private static final boolean DEBUG = DozeService.DEBUG;
    private static final String TAG = "DozeScreenState";

    /**
     * Delay entering low power mode when animating to make sure that we'll have
     * time to move all elements into their final positions while still at 60 fps.
     */
    private static final int ENTER_DOZE_DELAY = 4000;
    /**
     * Hide wallpaper earlier when entering low power mode. The gap between
     * hiding the wallpaper and changing the display mode is necessary to hide
     * the black frame that's inherent to hardware specs.
     */
    public static final int ENTER_DOZE_HIDE_WALLPAPER_DELAY = 2500;

    /**
     * Add an extra delay to the transition to DOZE when udfps is current activated before
     * the display state transitions from ON => DOZE.
     */
    public static final int UDFPS_DISPLAY_STATE_DELAY = 1200;

    private final DozeMachine.Service mDozeService;
    private final Handler mHandler;
    private final Runnable mApplyPendingScreenState = this::applyPendingScreenState;
    private final DozeParameters mParameters;
    private final DozeHost mDozeHost;
    private final AuthController mAuthController;
    private final Provider<UdfpsController> mUdfpsControllerProvider;
    @Nullable private UdfpsController mUdfpsController;
    private final DozeLog mDozeLog;
    private final DozeScreenBrightness mDozeScreenBrightness;

    private int mPendingScreenState = Display.STATE_UNKNOWN;
    private SettableWakeLock mWakeLock;

    @Inject
    public DozeScreenState(
            @WrappedService DozeMachine.Service service,
            @Main Handler handler,
            DozeHost host,
            DozeParameters parameters,
            WakeLock wakeLock,
            AuthController authController,
            Provider<UdfpsController> udfpsControllerProvider,
            DozeLog dozeLog,
            DozeScreenBrightness dozeScreenBrightness) {
        mDozeService = service;
        mHandler = handler;
        mParameters = parameters;
        mDozeHost = host;
        mWakeLock = new SettableWakeLock(wakeLock, TAG);
        mAuthController = authController;
        mUdfpsControllerProvider = udfpsControllerProvider;
        mDozeLog = dozeLog;
        mDozeScreenBrightness = dozeScreenBrightness;

        updateUdfpsController();
        if (mUdfpsController == null) {
            mAuthController.addCallback(mAuthControllerCallback);
        }
    }

    private void updateUdfpsController() {
        if (mAuthController.isUdfpsEnrolled(KeyguardUpdateMonitor.getCurrentUser())) {
            mUdfpsController = mUdfpsControllerProvider.get();
        } else {
            mUdfpsController = null;
        }
    }

    @Override
    public void destroy() {
        mAuthController.removeCallback(mAuthControllerCallback);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        int screenState = newState.screenState(mParameters);
        mDozeHost.cancelGentleSleep();

        if (newState == DozeMachine.State.FINISH) {
            // Make sure not to apply the screen state after DozeService was destroyed.
            mPendingScreenState = Display.STATE_UNKNOWN;
            mHandler.removeCallbacks(mApplyPendingScreenState);

            applyScreenState(screenState);
            mWakeLock.setAcquired(false);
            return;
        }

        if (screenState == Display.STATE_UNKNOWN) {
            // We'll keep it in the existing state
            return;
        }

        final boolean messagePending = mHandler.hasCallbacks(mApplyPendingScreenState);
        final boolean pulseEnding = oldState == DOZE_PULSE_DONE && newState.isAlwaysOn();
        final boolean turningOn = (oldState == DOZE_AOD_PAUSED || oldState == DOZE)
                && newState.isAlwaysOn();
        final boolean turningOff = (oldState.isAlwaysOn() && newState == DOZE)
                || (oldState == DOZE_AOD_PAUSING && newState == DOZE_AOD_PAUSED);
        final boolean justInitialized = oldState == DozeMachine.State.INITIALIZED;
        if (messagePending || justInitialized || pulseEnding || turningOn) {
            // During initialization, we hide the navigation bar. That is however only applied after
            // a traversal; setting the screen state here is immediate however, so it can happen
            // that the screen turns on again before the navigation bar is hidden. To work around
            // that, wait for a traversal to happen before applying the initial screen state.
            mPendingScreenState = screenState;

            // Delay screen state transitions even longer while animations are running.
            boolean shouldDelayTransitionEnteringDoze = newState == DOZE_AOD
                    && mParameters.shouldControlScreenOff() && !turningOn;

            // Delay screen state transition longer if UDFPS is actively authenticating a fp
            boolean shouldDelayTransitionForUDFPS = newState == DOZE_AOD
                    && mUdfpsController != null && mUdfpsController.isFingerDown();

            if (shouldDelayTransitionEnteringDoze || shouldDelayTransitionForUDFPS) {
                mWakeLock.setAcquired(true);
            }

            if (!messagePending) {
                if (DEBUG) {
                    Log.d(TAG, "Display state changed to " + screenState + " delayed by "
                            + (shouldDelayTransitionEnteringDoze ? ENTER_DOZE_DELAY : 1));
                }

                if (shouldDelayTransitionEnteringDoze) {
                    mHandler.postDelayed(mApplyPendingScreenState, ENTER_DOZE_DELAY);
                } else if (shouldDelayTransitionForUDFPS) {
                    mDozeLog.traceDisplayStateDelayedByUdfps(mPendingScreenState);
                    mHandler.postDelayed(mApplyPendingScreenState, UDFPS_DISPLAY_STATE_DELAY);
                } else {
                    mHandler.post(mApplyPendingScreenState);
                }
            } else if (DEBUG) {
                Log.d(TAG, "Pending display state change to " + screenState);
            }
        } else if (turningOff) {
            mDozeHost.prepareForGentleSleep(() -> applyScreenState(screenState));
        } else {
            applyScreenState(screenState);
        }
    }

    private void applyPendingScreenState() {
        if (mUdfpsController != null && mUdfpsController.isFingerDown()) {
            mDozeLog.traceDisplayStateDelayedByUdfps(mPendingScreenState);
            mHandler.postDelayed(mApplyPendingScreenState, UDFPS_DISPLAY_STATE_DELAY);
            return;
        }

        applyScreenState(mPendingScreenState);
        mPendingScreenState = Display.STATE_UNKNOWN;
    }

    private void applyScreenState(int screenState) {
        if (screenState != Display.STATE_UNKNOWN) {
            if (DEBUG) Log.d(TAG, "setDozeScreenState(" + screenState + ")");
            mDozeService.setDozeScreenState(screenState);
            if (screenState == Display.STATE_DOZE) {
                // If we're entering doze, update the doze screen brightness. We might have been
                // clamping it to the dim brightness during the screen off animation, and we should
                // now change it to the brightness we actually want according to the sensor.
                mDozeScreenBrightness.updateBrightnessAndReady(false /* force */);
            }
            mPendingScreenState = Display.STATE_UNKNOWN;
            mWakeLock.setAcquired(false);
        }
    }

    private final AuthController.Callback mAuthControllerCallback = new AuthController.Callback() {
        @Override
        public void onAllAuthenticatorsRegistered() {
            updateUdfpsController();
        }

        @Override
        public void onEnrollmentsChanged() {
            updateUdfpsController();
        }
    };
}
