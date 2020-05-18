/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.car.statusbar;

import com.android.systemui.doze.DozeHost;

import javax.inject.Inject;
import javax.inject.Singleton;

/** No-op implementation of {@link DozeHost} for use by car sysui, which does not support dozing. */
@Singleton
public class DozeServiceHost implements DozeHost {

    @Inject
    public DozeServiceHost() {}

    @Override
    public void addCallback(Callback callback) {
        // No op.
    }

    @Override
    public void removeCallback(Callback callback) {
        // No op.
    }

    @Override
    public void startDozing() {
        // No op.
    }

    @Override
    public void pulseWhileDozing(PulseCallback callback, int reason) {
        // No op.
    }

    @Override
    public void stopDozing() {
        // No op.
    }

    @Override
    public void dozeTimeTick() {
        // No op.
    }

    @Override
    public boolean isPowerSaveActive() {
        return false;
    }

    @Override
    public boolean isPulsingBlocked() {
        return true;
    }

    @Override
    public boolean isProvisioned() {
        return false;
    }

    @Override
    public boolean isBlockingDoze() {
        return true;
    }

    @Override
    public void extendPulse(int reason) {
        // No op.
    }

    @Override
    public void setAnimateWakeup(boolean animateWakeup) {
        // No op.
    }

    @Override
    public void setAnimateScreenOff(boolean animateScreenOff) {
        // No op.
    }

    @Override
    public void onSlpiTap(float x, float y) {
        // No op.
    }

    @Override
    public void setDozeScreenBrightness(int value) {
        // No op.
    }

    @Override
    public void prepareForGentleSleep(Runnable onDisplayOffCallback) {
        // No op.
    }

    @Override
    public void cancelGentleSleep() {
        // No op.
    }

    @Override
    public void onIgnoreTouchWhilePulsing(boolean ignore) {
        // No op.
    }

    @Override
    public void stopPulsing() {
        // No op.
    }

    @Override
    public boolean isDozeSuppressed() {
        return true;
    }
}
