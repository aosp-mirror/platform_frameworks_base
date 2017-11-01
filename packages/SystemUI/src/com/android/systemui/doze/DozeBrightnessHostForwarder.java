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

/**
 * Forwards the currently used brightness to {@link DozeHost}.
 */
public class DozeBrightnessHostForwarder extends DozeMachine.Service.Delegate {

    private final DozeHost mHost;

    public DozeBrightnessHostForwarder(DozeMachine.Service wrappedService, DozeHost host) {
        super(wrappedService);
        mHost = host;
    }

    @Override
    public void setDozeScreenBrightness(int brightness) {
        super.setDozeScreenBrightness(brightness);
        mHost.setDozeScreenBrightness(brightness);
    }
}
