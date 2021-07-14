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

import android.app.AlarmManager;
import android.os.Handler;

import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.util.AlarmTimeout;

import javax.inject.Inject;

/**
 * Moves the doze machine from the pausing to the paused state after a timeout.
 */
@DozeScope
public class DozePauser implements DozeMachine.Part {
    public static final String TAG = DozePauser.class.getSimpleName();
    private final AlarmTimeout mPauseTimeout;
    private DozeMachine mMachine;
    private final AlwaysOnDisplayPolicy mPolicy;

    @Inject
    public DozePauser(@Main Handler handler, AlarmManager alarmManager,
            AlwaysOnDisplayPolicy policy) {
        mPauseTimeout = new AlarmTimeout(alarmManager, this::onTimeout, TAG, handler);
        mPolicy = policy;
    }

    @Override
    public void setDozeMachine(DozeMachine dozeMachine) {
        mMachine = dozeMachine;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        switch (newState) {
            case DOZE_AOD_PAUSING:
                mPauseTimeout.schedule(mPolicy.proxScreenOffDelayMs,
                        AlarmTimeout.MODE_IGNORE_IF_SCHEDULED);
                break;
            default:
                mPauseTimeout.cancel();
                break;
        }
    }

    private void onTimeout() {
        mMachine.requestState(DozeMachine.State.DOZE_AOD_PAUSED);
    }
}
