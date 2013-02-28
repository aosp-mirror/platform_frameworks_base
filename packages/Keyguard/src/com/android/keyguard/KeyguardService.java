/*
 * Copyright (C) 2010 The Android Open Source Project
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

import java.io.FileDescriptor;
import java.io.PrintWriter;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.util.Log;
import android.view.accessibility.AccessibilityManager;

import com.android.internal.policy.IKeyguardService;
import com.android.internal.policy.IKeyguardResult;
import com.android.internal.widget.LockPatternUtils;

public class KeyguardService extends Service {
    static final String TAG = "KeyguardService";
    private KeyguardViewMediator mKeyguardViewMediator;

    @Override
    public void onCreate() {
        if (mKeyguardViewMediator == null) {
            mKeyguardViewMediator = new KeyguardViewMediator(
                    KeyguardService.this, new LockPatternUtils(KeyguardService.this));
        }
        Log.v(TAG, "onCreate()");
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        // TODO
    }

    private final IKeyguardService.Stub mBinder = new IKeyguardService.Stub() {
        public boolean isShowing() {
            return mKeyguardViewMediator.isShowing();
        }
        public boolean isSecure() {
            return mKeyguardViewMediator.isSecure();
        }
        public boolean isShowingAndNotHidden() {
            return mKeyguardViewMediator.isShowingAndNotHidden();
        }
        public boolean isInputRestricted() {
            return mKeyguardViewMediator.isInputRestricted();
        }
        public void userActivity() {
            mKeyguardViewMediator.userActivity();
        }
        public void verifyUnlock(IKeyguardResult result) {
            mKeyguardViewMediator.verifyUnlock(result);
        }
        public void keyguardDone(boolean authenticated, boolean wakeup) {
            mKeyguardViewMediator.keyguardDone(authenticated, wakeup);
        }
        public void setHidden(boolean isHidden) {
            mKeyguardViewMediator.setHidden(isHidden);
        }
        public void dismiss() {
            mKeyguardViewMediator.dismiss();
        }
        public void onWakeKeyWhenKeyguardShowingTq(int keyCode) {
            mKeyguardViewMediator.onWakeKeyWhenKeyguardShowingTq(keyCode);
        }
        public void onWakeMotionWhenKeyguardShowingTq() {
            mKeyguardViewMediator.onWakeMotionWhenKeyguardShowingTq();
        }
        public void onDreamingStarted() {
            mKeyguardViewMediator.onDreamingStarted();
        }
        public void onDreamingStopped() {
            mKeyguardViewMediator.onDreamingStopped();
        }
        public void onScreenTurnedOff(int reason) {
            mKeyguardViewMediator.onScreenTurnedOff(reason);
        }
        public void onScreenTurnedOn(IKeyguardResult result) {
            mKeyguardViewMediator.onScreenTurnedOn(result);
        }
        public void setKeyguardEnabled(boolean enabled) {
            mKeyguardViewMediator.setKeyguardEnabled(enabled);
        }
        public boolean isDismissable() {
            return mKeyguardViewMediator.isDismissable();
        }
        public void onSystemReady() {
            mKeyguardViewMediator.onSystemReady();
        }
        public void doKeyguardTimeout(Bundle options) {
            mKeyguardViewMediator.doKeyguardTimeout(options);
        }
        public void setCurrentUser(int userId) {
            mKeyguardViewMediator.setCurrentUser(userId);
        }
        public void showAssistant() {
            mKeyguardViewMediator.showAssistant();
        }
    };

}

