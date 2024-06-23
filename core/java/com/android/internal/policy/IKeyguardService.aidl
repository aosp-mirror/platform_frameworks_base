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

import android.content.Intent;
import com.android.internal.policy.IKeyguardDrawnCallback;
import com.android.internal.policy.IKeyguardDismissCallback;
import com.android.internal.policy.IKeyguardStateCallback;
import com.android.internal.policy.IKeyguardExitCallback;

import android.os.Bundle;

oneway interface IKeyguardService {

    /**
     * Sets the Keyguard as occluded when a window dismisses the Keyguard with flag
     * FLAG_SHOW_ON_LOCK_SCREEN.
     *
     * @param isOccluded Whether the Keyguard is occluded by another window.
     * @param animate Whether to play an animation for the state change.
     */
    void setOccluded(boolean isOccluded, boolean animate);

    void addStateMonitorCallback(IKeyguardStateCallback callback);
    void verifyUnlock(IKeyguardExitCallback callback);
    void dismiss(IKeyguardDismissCallback callback, CharSequence message);
    void onDreamingStarted();
    void onDreamingStopped();

    /**
     * Called when the device has started going to sleep.
     *
     * @param pmSleepReason One of PowerManager.GO_TO_SLEEP_REASON_*, detailing the specific reason
     * we're going to sleep, such as GO_TO_SLEEP_REASON_POWER_BUTTON or GO_TO_SLEEP_REASON_TIMEOUT.
     */
    void onStartedGoingToSleep(int pmSleepReason);

    /**
     * Called when the device has finished going to sleep.
     *
     * @param pmSleepReason One of PowerManager.GO_TO_SLEEP_REASON_*, detailing the specific reason
     * we're going to sleep, such as GO_TO_SLEEP_REASON_POWER_BUTTON or GO_TO_SLEEP_REASON_TIMEOUT.
     * @param cameraGestureTriggered whether the camera gesture was triggered between
     *                               {@link #onStartedGoingToSleep} and this method; if it's been
     *                               triggered, we shouldn't lock the device.
     */
    void onFinishedGoingToSleep(int pmSleepReason, boolean cameraGestureTriggered);

    /**
     * Called when the device has started waking up.

     * @param pmWakeReason One of PowerManager.WAKE_REASON_*, detailing the reason we're waking up,
     * such as WAKE_REASON_POWER_BUTTON or WAKE_REASON_GESTURE.
     * @param cameraGestureTriggered Whether we're waking up due to a power button double tap
     * gesture.
     */
    void onStartedWakingUp(int pmWakeReason,  boolean cameraGestureTriggered);

    /**
     * Called when the device has finished waking up.
     */
    void onFinishedWakingUp();

    /**
     * Called when the device screen is turning on.
     */
    void onScreenTurningOn(IKeyguardDrawnCallback callback);

    /**
     * Called when the screen has actually turned on.
     */
    void onScreenTurnedOn();

    /**
     * Called when the screen starts turning off.
     */
    void onScreenTurningOff();

    /**
     * Called when the screen has turned off.
     */
    void onScreenTurnedOff();

    @UnsupportedAppUsage
    void setKeyguardEnabled(boolean enabled);
    void onSystemReady();
    @UnsupportedAppUsage
    void doKeyguardTimeout(in Bundle options);
    void setSwitchingUser(boolean switching);
    void setCurrentUser(int userId);
    void onBootCompleted();

    /**
     * Notifies that the activity behind has now been drawn and it's safe to remove the wallpaper
     * and keyguard flag.
     *
     * @param startTime the start time of the animation in uptime milliseconds
     * @param fadeoutDuration the duration of the exit animation, in milliseconds
     */
    void startKeyguardExitAnimation(long startTime, long fadeoutDuration);

    /**
     * Notifies the Keyguard that the power key was pressed while locked and launched Home rather
     * than putting the device to sleep or waking up. Note that it's called only if the device is
     * interactive.
     */
    void onShortPowerPressedGoHome();

    /**
     * Notifies the Keyguard that it needs to bring up a bouncer and then launch the intent as soon
     * as user unlocks the watch.
     */
    void dismissKeyguardToLaunch(in Intent intentToLaunch);

    /**
     * Notifies the Keyguard that a key was pressed while locked so the Keyguard can handle it.
     * Note that it's called only if the device is interactive.
     */
    void onSystemKeyPressed(int keycode);

    /**
     * Requests to show the keyguard immediately without locking the device. Keyguard will show
     * whether a screen lock was configured or not (including if screen lock is SWIPE or NONE).
     */
    void showDismissibleKeyguard();
}
