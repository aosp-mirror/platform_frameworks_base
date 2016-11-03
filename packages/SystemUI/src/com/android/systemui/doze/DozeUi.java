/*
 * Copyright (C) 2016 The Android Open Source Project
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

/**
 * The policy controlling doze.
 */
public class DozeUi implements DozeMachine.Part {

    private final Context mContext;
    private final DozeHost mHost;
    private DozeFactory.WakeLock mWakeLock;
    private DozeMachine mMachine;

    public DozeUi(Context context, DozeMachine machine, DozeFactory.WakeLock wakeLock,
            DozeHost host) {
        mContext = context;
        mMachine = machine;
        mWakeLock = wakeLock;
        mHost = host;
    }

    private void pulseWhileDozing(int reason) {
        mHost.pulseWhileDozing(
                new DozeHost.PulseCallback() {
                    @Override
                    public void onPulseStarted() {
                        mMachine.requestState(DozeMachine.State.DOZE_PULSING);
                    }

                    @Override
                    public void onPulseFinished() {
                        mMachine.requestState(DozeMachine.State.DOZE_PULSE_DONE);
                    }
                }, reason);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case DOZE_REQUEST_PULSE:
                pulseWhileDozing(DozeLog.PULSE_REASON_NOTIFICATION /* TODO */);
                break;
            case INITIALIZED:
                mHost.startDozing();
                break;
            case FINISH:
                mHost.stopDozing();
                break;
        }
    }
}
