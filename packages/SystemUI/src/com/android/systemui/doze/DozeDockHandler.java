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
import android.util.Log;

import com.android.internal.hardware.AmbientDisplayConfiguration;
import com.android.systemui.SysUiServiceProvider;
import com.android.systemui.dock.DockManager;

import java.io.PrintWriter;

/**
 * Handles dock events for ambient state changes.
 */
public class DozeDockHandler implements DozeMachine.Part {

    private static final String TAG = "DozeDockHandler";
    private static final boolean DEBUG = DozeService.DEBUG;

    private final Context mContext;
    private final DozeMachine mMachine;
    private final DozeHost mDozeHost;
    private final AmbientDisplayConfiguration mConfig;
    private final Handler mHandler;
    private final DockEventListener mDockEventListener = new DockEventListener();
    private final DockManager mDockManager;

    private boolean mDocking;

    public DozeDockHandler(Context context, DozeMachine machine, DozeHost dozeHost,
            AmbientDisplayConfiguration config, Handler handler) {
        mContext = context;
        mMachine = machine;
        mDozeHost = dozeHost;
        mConfig = config;
        mHandler = handler;
        mDockManager = SysUiServiceProvider.getComponent(context, DockManager.class);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case INITIALIZED:
                mDockEventListener.register();
                break;
            case DOZE:
            case DOZE_AOD:
                mHandler.post(() -> requestPulse());
                break;
            case FINISH:
                mDockEventListener.unregister();
                break;
            default:
        }
    }

    private void requestPulse() {
        if (!mDocking || mDozeHost.isPulsingBlocked() || !canPulse()) {
            return;
        }

        mMachine.requestPulse(DozeLog.PULSE_REASON_DOCKING);
    }

    private boolean canPulse() {
        return mMachine.getState() == DozeMachine.State.DOZE
                || mMachine.getState() == DozeMachine.State.DOZE_AOD;
    }

    private void requestPulseOutNow() {
        final DozeMachine.State state = mMachine.getState();
        if (state == DozeMachine.State.DOZE_PULSING
                || state == DozeMachine.State.DOZE_REQUEST_PULSE) {
            final int pulseReason = mMachine.getPulseReason();
            if (pulseReason == DozeLog.PULSE_REASON_DOCKING) {
                mDozeHost.stopPulsing();
            }
        }
    }

    @Override
    public void dump(PrintWriter pw) {
        pw.print(" DozeDockTriggers docking="); pw.println(mDocking);
    }

    private class DockEventListener implements DockManager.DockEventListener {
        private boolean mRegistered;

        @Override
        public void onEvent(int event) {
            if (DEBUG) Log.d(TAG, "dock event = " + event);
            switch (event) {
                case DockManager.STATE_DOCKING:
                    mDocking = true;
                    requestPulse();
                    break;
                case DockManager.STATE_UNDOCKING:
                    mDocking = false;
                    requestPulseOutNow();
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
