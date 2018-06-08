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

package com.android.systemui.doze;

import android.annotation.NonNull;
import android.app.PendingIntent;

/**
 * Interface the doze service uses to communicate with the rest of system UI.
 */
public interface DozeHost {
    void addCallback(@NonNull Callback callback);
    void removeCallback(@NonNull Callback callback);
    void startDozing();
    void pulseWhileDozing(@NonNull PulseCallback callback, int reason);
    void stopDozing();
    void dozeTimeTick();
    boolean isPowerSaveActive();
    boolean isPulsingBlocked();
    boolean isProvisioned();
    boolean isBlockingDoze();

    void startPendingIntentDismissingKeyguard(PendingIntent intent);
    void extendPulse();

    void setAnimateWakeup(boolean animateWakeup);
    void setAnimateScreenOff(boolean animateScreenOff);

    void onDoubleTap(float x, float y);

    default void setAodDimmingScrim(float scrimOpacity) {}
    void setDozeScreenBrightness(int value);

    void onIgnoreTouchWhilePulsing(boolean ignore);

    interface Callback {
        default void onNotificationHeadsUp() {}
        default void onPowerSaveChanged(boolean active) {}
    }

    interface PulseCallback {
        void onPulseStarted();
        void onPulseFinished();
    }
}
