/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.annotation.NonNull;

/**
 * A rudimentary fake for DozeHost.
 */
class DozeHostFake implements DozeHost {
    Callback callback;
    boolean pulseExtended;
    boolean animateWakeup;
    boolean animateScreenOff;
    boolean dozing;
    float doubleTapX;
    float doubleTapY;
    float aodDimmingScrimOpacity;

    @Override
    public void addCallback(@NonNull Callback callback) {
        this.callback = callback;
    }

    @Override
    public void removeCallback(@NonNull Callback callback) {
        this.callback = null;
    }

    @Override
    public void startDozing() {
        dozing = true;
    }

    @Override
    public void pulseWhileDozing(@NonNull PulseCallback callback, int reason) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void stopDozing() {
        dozing = false;
    }

    @Override
    public void dozeTimeTick() {
        // Nothing to do in here. Real host would just update the UI.
    }

    @Override
    public boolean isPowerSaveActive() {
        return false;
    }

    @Override
    public boolean isPulsingBlocked() {
        return false;
    }

    @Override
    public boolean isProvisioned() {
        return false;
    }

    @Override
    public boolean isBlockingDoze() {
        return false;
    }

    @Override
    public void onIgnoreTouchWhilePulsing(boolean ignore) {
    }

    @Override
    public void extendPulse() {
        pulseExtended = true;
    }

    @Override
    public void stopPulsing() {}

    @Override
    public void setAnimateWakeup(boolean animateWakeup) {
        this.animateWakeup = animateWakeup;
    }

    @Override
    public void setAnimateScreenOff(boolean animateScreenOff) {
        this.animateScreenOff = animateScreenOff;
    }

    @Override
    public void onSlpiTap(float x, float y) {
        doubleTapX = y;
        doubleTapY = y;
    }

    @Override
    public void setDozeScreenBrightness(int value) {
    }

    @Override
    public void setAodDimmingScrim(float scrimOpacity) {
        aodDimmingScrimOpacity = scrimOpacity;
    }
}
