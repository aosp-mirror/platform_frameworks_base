/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.content.Context;
import android.os.Handler;
import android.os.UserHandle;
import android.util.Log;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.DozeMachine.State;

import java.io.PrintWriter;

/**
 * Handles dock events for ambient state changes.
 */
public class DozeDockHandler implements DozeMachine.Part {

    private static final String TAG = "DozeDockHandler";
    private static final boolean DEBUG = DozeService.DEBUG;

    private final DozeMachine mMachine;
    private final DozeHost mDozeHost;
    private final AmbientDisplayConfiguration mConfig;
    private final Handler mHandler;
    private final DockEventListener mDockEventListener = new DockEventListener();
    private final DockManager mDockManager;

    private int mDockState = DockManager.STATE_NONE;

    public DozeDockHandler(Context context, DozeMachine machine, DozeHost dozeHost,
            AmbientDisplayConfiguration config, Handler handler, DockManager dockManager) {
        mMachine = machine;
        mDozeHost = dozeHost;
        mConfig = config;
        mHandler = handler;
        mDockManager = dockManager;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                mDockEventListener.register();
                break;
            case DOZE_AOD:
                if (mDockState == DockManager.STATE_DOCKED_HIDE) {
                    mMachine.requestState(State.DOZE);
                    break;
                }
                // continue below
            case DOZE:
                if (mDockState == DockManager.STATE_DOCKED) {
                    mHandler.post(() -> requestPulse(newState));
                }
                break;
            case FINISH:
                mDockEventListener.unregister();
                break;
            default:
                // no-op
        }
    }

    private void requestPulse(State dozeState) {
        if (mDozeHost.isPulsingBlocked() || !dozeState.canPulse()) {
            return;
        }

        mMachine.requestPulse(DozeLog.PULSE_REASON_DOCKING);
    }

    private void requestPulseOutNow(State dozeState) {
        if (dozeState == State.DOZE_REQUEST_PULSE || dozeState == State.DOZE_PULSING) {
            final int pulseReason = mMachine.getPulseReason();
            if (pulseReason == DozeLog.PULSE_REASON_DOCKING) {
                mDozeHost.stopPulsing();
            }
        }
    }

    private boolean isDocked() {
        return mDockState == DockManager.STATE_DOCKED
                || mDockState == DockManager.STATE_DOCKED_HIDE;
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print(" DozeDockTriggers docking="); pw.println(isDocked());
    }

    private class DockEventListener implements DockManager.DockEventListener {
        private boolean mRegistered;

        @Override
        public void onEvent(int event) {
            if (DEBUG) Log.d(TAG, "dock event = " + event);
            final DozeMachine.State dozeState = mMachine.getState();
            mDockState = event;
            switch (mDockState) {
                case DockManager.STATE_DOCKED:
                    requestPulse(dozeState);
                    break;
                case DockManager.STATE_NONE:
                    if (dozeState == State.DOZE
                            && mConfig.alwaysOnEnabled(UserHandle.USER_CURRENT)) {
                        mMachine.requestState(State.DOZE_AOD);
                        break;
                    }
                    // continue below
                case DockManager.STATE_DOCKED_HIDE:
                    requestPulseOutNow(dozeState);
                    break;
                default:
                    // no-op
            }
        }

        void register() {
            if (mRegistered) {
                return;
            }
            if (mDockManager != null) {
                mDockManager.addListener(this);
            }
            mRegistered = true;
        }

        void unregister() {
            if (!mRegistered) {
                return;
            }
            if (mDockManager != null) {
                mDockManager.removeListener(this);
            }
            mRegistered = false;
        }
    }
}
