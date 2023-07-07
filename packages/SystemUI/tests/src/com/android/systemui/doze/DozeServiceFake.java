/*
 * Copyright (C) 2016 The Android Open Source Project
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

import android.os.PowerManager;
import android.view.Display;

/**
 * Fake implementation of {@link DozeMachine.Service} for tests.
 *
 * Useful instead of mocking because it allows verifying state instead of interactions.
 */
public class DozeServiceFake implements DozeMachine.Service {

    public boolean finished;
    public int screenState;
    public boolean screenStateSet;
    public boolean requestedWakeup;
    public int screenBrightness;

    public DozeServiceFake() {
        reset();
    }

    @Override
    public void finish() {
        finished = true;
    }

    @Override
    public void setDozeScreenState(int state) {
        screenState = state;
        screenStateSet = true;
    }

    @Override
    public void requestWakeUp(@DozeLog.Reason int reason) {
        requestedWakeup = true;
    }

    @Override
    public void setDozeScreenBrightness(int brightness) {
        screenBrightness = brightness;
    }

    public void reset() {
        finished = false;
        screenState = Display.STATE_UNKNOWN;
        screenStateSet = false;
        requestedWakeup = false;
        screenBrightness = PowerManager.BRIGHTNESS_DEFAULT;
    }
}
