/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.keyguard.KeyguardUpdateMonitor;

/**
 * Controls removing Keyguard authorization when the phone goes to sleep.
 */
public class DozeAuthRemover implements DozeMachine.Part {

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;

    public DozeAuthRemover(Context context) {
        mKeyguardUpdateMonitor = KeyguardUpdateMonitor.getInstance(context);
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        if (newState == DozeMachine.State.DOZE || newState == DozeMachine.State.DOZE_AOD) {
            int currentUser = KeyguardUpdateMonitor.getCurrentUser();
            if (mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(currentUser)) {
                mKeyguardUpdateMonitor.clearBiometricRecognized();
            }
        }
    }
}
