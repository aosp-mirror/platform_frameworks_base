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
 * limitations under the License
 */

package com.android.systemui.keyguard;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Bundle;
import android.os.Debug;
import android.os.IBinder;
import android.util.Log;
import android.view.MotionEvent;

import com.android.internal.policy.IKeyguardExitCallback;
import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardServiceConstants;
import com.android.internal.policy.IKeyguardShowCallback;
import com.android.systemui.SystemUIApplication;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

public class KeyguardService extends Service {
    static final String TAG = "KeyguardService";
    static final String PERMISSION = android.Manifest.permission.CONTROL_KEYGUARD;

    private KeyguardViewMediator mKeyguardViewMediator;

    @Override
    public void onCreate() {
        ((SystemUIApplication) getApplication()).startServicesIfNeeded();
        mKeyguardViewMediator =
                ((SystemUIApplication) getApplication()).getComponent(KeyguardViewMediator.class);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    void checkPermission() {
        if (getBaseContext().checkCallingOrSelfPermission(PERMISSION) != PERMISSION_GRANTED) {
            Log.w(TAG, "Caller needs permission '" + PERMISSION + "' to call " + Debug.getCaller());
            throw new SecurityException("Access denied to process: " + Binder.getCallingPid()
                    + ", must have permission " + PERMISSION);
        }
    }

    private final IKeyguardService.Stub mBinder = new IKeyguardService.Stub() {

        private boolean mIsOccluded;

        @Override
        public boolean isShowing() {
            return mKeyguardViewMediator.isShowing();
        }

        @Override
        public boolean isSecure() {
            return mKeyguardViewMediator.isSecure();
        }

        @Override
        public boolean isShowingAndNotOccluded() {
            return mKeyguardViewMediator.isShowingAndNotOccluded();
        }

        @Override
        public boolean isInputRestricted() {
            return mKeyguardViewMediator.isInputRestricted();
        }

        @Override
        public void verifyUnlock(IKeyguardExitCallback callback) {
            checkPermission();
            mKeyguardViewMediator.verifyUnlock(callback);
        }

        @Override
        public void keyguardDone(boolean authenticated, boolean wakeup) {
            checkPermission();
            mKeyguardViewMediator.keyguardDone(authenticated, wakeup);
        }

        @Override
        public int setOccluded(boolean isOccluded) {
            checkPermission();
            synchronized (this) {
                int result;
                if (isOccluded && mKeyguardViewMediator.isShowing()
                        && !mIsOccluded) {
                    result = IKeyguardServiceConstants
                            .KEYGUARD_SERVICE_SET_OCCLUDED_RESULT_UNSET_FLAGS;
                } else if (!isOccluded && mKeyguardViewMediator.isShowing()
                        && mIsOccluded) {
                    result = IKeyguardServiceConstants
                            .KEYGUARD_SERVICE_SET_OCCLUDED_RESULT_SET_FLAGS;
                } else {
                    result = IKeyguardServiceConstants.KEYGUARD_SERVICE_SET_OCCLUDED_RESULT_NONE;
                }
                if (mIsOccluded != isOccluded) {
                    mKeyguardViewMediator.setOccluded(isOccluded);

                    // Cache the value so we always have a fresh view in whether Keyguard is occluded.
                    // If we would just call mKeyguardViewMediator.isOccluded(), this might be stale
                    // because that value gets updated in another thread.
                    mIsOccluded = isOccluded;
                }
                return result;
            }
        }

        @Override
        public void dismiss() {
            checkPermission();
            mKeyguardViewMediator.dismiss();
        }

        @Override
        public void onDreamingStarted() {
            checkPermission();
            mKeyguardViewMediator.onDreamingStarted();
        }

        @Override
        public void onDreamingStopped() {
            checkPermission();
            mKeyguardViewMediator.onDreamingStopped();
        }

        @Override
        public void onScreenTurnedOff(int reason) {
            checkPermission();
            mKeyguardViewMediator.onScreenTurnedOff(reason);
        }

        @Override
        public void onScreenTurnedOn(IKeyguardShowCallback callback) {
            checkPermission();
            mKeyguardViewMediator.onScreenTurnedOn(callback);
        }

        @Override
        public void setKeyguardEnabled(boolean enabled) {
            checkPermission();
            mKeyguardViewMediator.setKeyguardEnabled(enabled);
        }

        @Override
        public boolean isDismissable() {
            return mKeyguardViewMediator.isDismissable();
        }

        @Override
        public void onSystemReady() {
            checkPermission();
            mKeyguardViewMediator.onSystemReady();
        }

        @Override
        public void doKeyguardTimeout(Bundle options) {
            checkPermission();
            mKeyguardViewMediator.doKeyguardTimeout(options);
        }

        @Override
        public void setCurrentUser(int userId) {
            checkPermission();
            mKeyguardViewMediator.setCurrentUser(userId);
        }

        @Override
        public void showAssistant() {
            checkPermission();
        }

        @Override
        public void dispatch(MotionEvent event) {
            checkPermission();
        }

        @Override
        public void launchCamera() {
            checkPermission();
        }

        @Override
        public void onBootCompleted() {
            checkPermission();
            mKeyguardViewMediator.onBootCompleted();
        }

        @Override
        public void startKeyguardExitAnimation(long startTime, long fadeoutDuration) {
            checkPermission();
            mKeyguardViewMediator.startKeyguardExitAnimation(startTime, fadeoutDuration);
        }

        @Override
        public void onActivityDrawn() {
            checkPermission();
            mKeyguardViewMediator.onActivityDrawn();
        }
    };
}

