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

import com.android.keyguard.KeyguardUpdateMonitor;
import com.android.systemui.doze.dagger.DozeScope;
import com.android.systemui.user.domain.interactor.SelectedUserInteractor;

import javax.inject.Inject;

/**
 * Controls removing Keyguard authorization when the phone goes to sleep.
 */
@DozeScope
public class DozeAuthRemover implements DozeMachine.Part {

    private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
    private final SelectedUserInteractor mSelectedUserInteractor;

    @Inject
    public DozeAuthRemover(KeyguardUpdateMonitor keyguardUpdateMonitor,
            SelectedUserInteractor selectedUserInteractor) {
        mKeyguardUpdateMonitor = keyguardUpdateMonitor;
        mSelectedUserInteractor = selectedUserInteractor;
    }

    @Override
    public void transitionTo(DozeMachine.State oldState, DozeMachine.State newState) {
        if (newState == DozeMachine.State.DOZE || newState == DozeMachine.State.DOZE_AOD) {
            int currentUser = mSelectedUserInteractor.getSelectedUserId();
            if (mKeyguardUpdateMonitor.getUserUnlockedWithBiometric(currentUser)) {
                mKeyguardUpdateMonitor.clearFingerprintRecognized();
            }
        }
    }
}
