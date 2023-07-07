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

import android.hardware.display.AmbientDisplayConfiguration;
import android.util.Log;

import com.android.systemui.dock.DockManager;
import com.android.systemui.doze.DozeMachine.State;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.settings.UserTracker;

import java.io.PrintWriter;

import javax.inject.Inject;

/**
 * Handles dock events for ambient state changes.
 */
@DozeScope
public class DozeDockHandler implements DozeMachine.Part {

    private static final String TAG = "DozeDockHandler";
    private static final boolean DEBUG = DozeService.DEBUG;

    private final AmbientDisplayConfiguration mConfig;
    private DozeMachine mMachine;
    private final DockManager mDockManager;
    private final UserTracker mUserTracker;
    private final DockEventListener mDockEventListener;

    private int mDockState = DockManager.STATE_NONE;

    @Inject
    DozeDockHandler(AmbientDisplayConfiguration config, DockManager dockManager,
            UserTracker userTracker) {
        mConfig = config;
        mDockManager = dockManager;
        mUserTracker = userTracker;
        mDockEventListener = new DockEventListener();
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                mDockEventListener.register();
                break;
            case FINISH:
                mDockEventListener.unregister();
                break;
            default:
                // no-op
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.println("DozeDockHandler:");
        pw.println(" dockState=" + mDockState);
    }

    private class DockEventListener implements DockManager.DockEventListener {
        private boolean mRegistered;

        @Override
        public void onEvent(int dockState) {
            if (DEBUG) Log.d(TAG, "dock event = " + dockState);

            // Only act upon state changes, otherwise we might overwrite other transitions,
            // like proximity sensor initialization.
            if (mDockState == dockState) {
                return;
            }

            mDockState = dockState;
            if (isPulsing()) {
                return;
            }

            DozeMachine.State nextState;
            switch (mDockState) {
                case DockManager.STATE_DOCKED:
                    nextState = State.DOZE_AOD_DOCKED;
                    break;
                case DockManager.STATE_NONE:
                    nextState = mConfig.alwaysOnEnabled(mUserTracker.getUserId()) ? State.DOZE_AOD
                            : State.DOZE;
                    break;
                case DockManager.STATE_DOCKED_HIDE:
                    nextState = State.DOZE;
                    break;
                default:
                    return;
            }
            mMachine.requestState(nextState);
        }

        private boolean isPulsing() {
            DozeMachine.State state = mMachine.getState();
            return state == State.DOZE_REQUEST_PULSE || state == State.DOZE_PULSING
                    || state == State.DOZE_PULSING_BRIGHT;
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
