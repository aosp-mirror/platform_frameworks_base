/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.internal.policy;

import android.view.MotionEvent;

import com.android.internal.policy.IKeyguardShowCallback;
import com.android.internal.policy.IKeyguardExitCallback;

import android.os.Bundle;

interface IKeyguardService {
    boolean isShowing();
    boolean isSecure();
    boolean isShowingAndNotHidden();
    boolean isInputRestricted();
    boolean isDismissable();
    oneway void verifyUnlock(IKeyguardExitCallback callback);
    oneway void keyguardDone(boolean authenticated, boolean wakeup);
    oneway void setHidden(boolean isHidden);
    oneway void dismiss();
    oneway void onDreamingStarted();
    oneway void onDreamingStopped();
    oneway void onScreenTurnedOff(int reason);
    oneway void onScreenTurnedOn(IKeyguardShowCallback callback);
    oneway void setKeyguardEnabled(boolean enabled);
    oneway void onSystemReady();
    oneway void doKeyguardTimeout(in Bundle options);
    oneway void setCurrentUser(int userId);
    oneway void showAssistant();
    oneway void dispatch(in MotionEvent event);
    oneway void launchCamera();
    oneway void onBootCompleted();
}
