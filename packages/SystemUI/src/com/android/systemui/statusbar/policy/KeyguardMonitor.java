/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar.policy;

import com.android.systemui.statusbar.policy.KeyguardMonitor.Callback;

public interface KeyguardMonitor extends CallbackController<Callback> {

    boolean isSecure();
    boolean isShowing();
    boolean isOccluded();
    boolean isKeyguardFadingAway();
    boolean isKeyguardGoingAway();
    boolean isLaunchTransitionFadingAway();
    long getKeyguardFadingAwayDuration();
    long getKeyguardFadingAwayDelay();
    long calculateGoingToFullShadeDelay();

    default boolean isDeviceInteractive() {
        return false;
    }

    default void setLaunchTransitionFadingAway(boolean b) {
    }

    default void notifyKeyguardGoingAway(boolean b) {
    }

    default void notifyKeyguardFadingAway(long delay, long fadeoutDuration) {
    }

    default void notifyKeyguardDoneFading() {
    }

    default void notifyKeyguardState(boolean showing, boolean methodSecure, boolean occluded) {
    }

    interface Callback {
        void onKeyguardShowingChanged();
    }
}
