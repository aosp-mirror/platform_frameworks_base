/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.display.brightness.clamper;

import android.annotation.NonNull;
import android.os.PowerManager;

import java.io.PrintWriter;

/**
 * Provides max allowed brightness
 */
abstract class BrightnessClamper<T> {

    protected float mBrightnessCap = PowerManager.BRIGHTNESS_MAX;
    protected boolean mIsActive = false;

    float getBrightnessCap() {
        return mBrightnessCap;
    }

    boolean isActive() {
        return mIsActive;
    }

    void dump(PrintWriter writer) {
        writer.println("BrightnessClamper:" + getType());
        writer.println(" mBrightnessCap: " + mBrightnessCap);
        writer.println(" mIsActive: " + mIsActive);
    }

    @NonNull
    abstract Type getType();

    abstract void onDeviceConfigChanged();

    abstract void onDisplayChanged(T displayData);

    abstract void stop();

    enum Type {
        THERMAL
    }
}
