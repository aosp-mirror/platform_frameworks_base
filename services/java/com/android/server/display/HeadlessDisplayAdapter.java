/*
 * Copyright (C) 2012 The Android Open Source Project
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

package com.android.server.display;

/**
 * Provides a fake default display for headless systems.
 */
public final class HeadlessDisplayAdapter extends DisplayAdapter {
    private final DisplayDevice mDefaultDisplay = new DisplayDevice() {
        @Override
        public void getInfo(DisplayDeviceInfo outInfo) {
            outInfo.width = 640;
            outInfo.height = 480;
            outInfo.refreshRate = 60;
            outInfo.density = 1.0f;
            outInfo.xDpi = 160;
            outInfo.yDpi = 160;
        }
    };

    @Override
    public String getName() {
        return "HeadlessDisplayAdapter";
    }

    @Override
    public DisplayDevice[] getDisplayDevices() {
        return new DisplayDevice[] { mDefaultDisplay };
    }
}
