/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.keyguard;

import android.content.Context;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.MotionEvent;

public class KeyguardSimpleHostView extends KeyguardViewBase {

    public KeyguardSimpleHostView(Context context, AttributeSet attrs) {
        super(context, attrs);
        KeyguardUpdateMonitor.getInstance(context).registerCallback(mUpdateCallback);
    }

    @Override
    protected void showBouncer(boolean show) {
        super.showBouncer(show);
        if (show) {
            getSecurityContainer().showBouncer(250);
        } else {
            getSecurityContainer().hideBouncer(250);
        }
    }

    @Override
    public void cleanUp() {
        getSecurityContainer().onPause();
    }

    @Override
    public long getUserActivityTimeout() {
        return -1; // not used
    }

    @Override
    protected void onUserSwitching(boolean switching) {
        // TODO Auto-generated method stub
    }

    @Override
    protected void onCreateOptions(Bundle options) {
        // TODO Auto-generated method stub
    }

    @Override
    protected void onExternalMotionEvent(MotionEvent event) {
        // TODO Auto-generated method stub
    }

    private KeyguardUpdateMonitorCallback mUpdateCallback = new KeyguardUpdateMonitorCallback() {
        @Override
        public void onUserSwitchComplete(int userId) {
            getSecurityContainer().showPrimarySecurityScreen(false /* turning off */);
        }

        @Override
        public void onTrustInitiatedByUser(int userId) {
            if (userId != mLockPatternUtils.getCurrentUser()) return;
            if (!isAttachedToWindow()) return;

            if (isVisibleToUser()) {
                dismiss(false /* authenticated */);
            } else {
                mViewMediatorCallback.playTrustedSound();
            }
        }
    };
}
