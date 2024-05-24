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

package com.android.systemui.utils.leaks;

import android.testing.LeakCheck;

import androidx.annotation.VisibleForTesting;

import com.android.systemui.statusbar.policy.FlashlightController;
import com.android.systemui.statusbar.policy.FlashlightController.FlashlightListener;

import java.util.ArrayList;
import java.util.List;

public class FakeFlashlightController extends BaseLeakChecker<FlashlightListener>
        implements FlashlightController {

    private final List<FlashlightListener> callbacks = new ArrayList<>();

    @VisibleForTesting
    public boolean isAvailable = true;
    @VisibleForTesting
    public boolean isEnabled = false;
    @VisibleForTesting
    public boolean hasFlashlight = true;

    public FakeFlashlightController(LeakCheck test) {
        super(test, "flashlight");
    }

    @VisibleForTesting
    public void onFlashlightAvailabilityChanged(boolean newValue) {
        callbacks.forEach(
                flashlightListener -> flashlightListener.onFlashlightAvailabilityChanged(newValue)
        );
    }

    @VisibleForTesting
    public void onFlashlightError() {
        callbacks.forEach(FlashlightListener::onFlashlightError);
    }

    /**
     * Used to decide if tile should be shown or gone
     * @return available/unavailable
     */
    @Override
    public boolean hasFlashlight() {
        return hasFlashlight;
    }

    /**
     * @param newState active/inactive
     */
    @Override
    public void setFlashlight(boolean newState) {
        callbacks.forEach(flashlightListener -> flashlightListener.onFlashlightChanged(newState));
    }

    /**
     * @return temporary availability
     */
    @Override
    public boolean isAvailable() {
        return isAvailable;
    }

    @Override
    public boolean isEnabled() {
        return isEnabled;
    }

    @Override
    public void addCallback(FlashlightListener listener) {
        super.addCallback(listener);
        callbacks.add(listener);

        listener.onFlashlightAvailabilityChanged(isAvailable());
        listener.onFlashlightChanged(isEnabled());
    }

    @Override
    public void removeCallback(FlashlightListener listener) {
        super.removeCallback(listener);
        callbacks.remove(listener);
    }
}
