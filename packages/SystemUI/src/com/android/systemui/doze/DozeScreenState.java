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

import com.android.systemui.statusbar.phone.DozeParameters;
import com.android.systemui.util.wakelock.SettableWakeLock;
import com.android.systemui.util.wakelock.WakeLock;

/**
 * Controls the screen when dozing.
 */
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

    private final DozeMachine.Service mDozeService;
    private final Handler mHandler;
    private final Runnable mApplyPendingScreenState = this::applyPendingScreenState;
    private final DozeParameters mParameters;
    private final DozeHost mDozeHost;

    private int mPendingScreenState = Display.STATE_UNKNOWN;
    private SettableWakeLock mWakeLock;

    public DozeScreenState(DozeMachine.Service service, Handler handler, DozeHost host,
            DozeParameters parameters, WakeLock wakeLock) {
        mDozeService = service;
        mHandler = handler;
        mParameters = parameters;
        mDozeHost = host;
        mWakeLock = new SettableWakeLock(wakeLock, TAG);
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
            boolean shouldDelayTransition = newState == DOZE_AOD
                    && mParameters.shouldControlScreenOff() && !turningOn;

            if (shouldDelayTransition) {
                mWakeLock.setAcquired(true);
            }

            if (!messagePending) {
                if (DEBUG) {
                    Log.d(TAG, "Display state changed to " + screenState + " delayed by "
                            + (shouldDelayTransition ? ENTER_DOZE_DELAY : 1));
                }

                if (shouldDelayTransition) {
                    mHandler.postDelayed(mApplyPendingScreenState, ENTER_DOZE_DELAY);
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
        applyScreenState(mPendingScreenState);
        mPendingScreenState = Display.STATE_UNKNOWN;
    }

    private void applyScreenState(int screenState) {
        if (screenState != Display.STATE_UNKNOWN) {
            if (DEBUG) Log.d(TAG, "setDozeScreenState(" + screenState + ")");
            mDozeService.setDozeScreenState(screenState);
            mPendingScreenState = Display.STATE_UNKNOWN;
            mWakeLock.setAcquired(false);
        }
    }
}
