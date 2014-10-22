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
    boolean isShowingAndNotOccluded();
    boolean isInputRestricted();
    boolean isDismissable();
    oneway void verifyUnlock(IKeyguardExitCallback callback);
    oneway void keyguardDone(boolean authenticated, boolean wakeup);

    /**
     * Sets the Keyguard as occluded when a window dismisses the Keyguard with flag
     * FLAG_SHOW_ON_LOCK_SCREEN.
     *
     * @param isOccluded Whether the Keyguard is occluded by another window.
     * @return See IKeyguardServiceConstants.KEYGUARD_SERVICE_SET_OCCLUDED_*. This is needed because
     *         PhoneWindowManager needs to set these flags immediately and can't wait for the
     *         Keyguard thread to pick it up. In the hidden case, PhoneWindowManager is solely
     *         responsible to make sure that the flags are unset.
     */
    int setOccluded(boolean isOccluded);

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

    /**
     * Notifies that the activity behind has now been drawn and it's safe to remove the wallpaper
     * and keyguard flag.
     *
     * @param startTime the start time of the animation in uptime milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    oneway void startKeyguardExitAnimation(long startTime, long fadeoutDuration);

    /**
     * Notifies the Keyguard that the activity that was starting has now been drawn and it's safe
     * to start the keyguard dismiss sequence.
     */
    oneway void onActivityDrawn();
}
