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

package com.android.systemui.util.sensors;

import java.util.ArrayList;
import java.util.List;

public class FakeThresholdSensor implements ThresholdSensor {
    private boolean mIsLoaded;
    private boolean mPaused;
    private List<Listener> mListeners = new ArrayList<>();

    public FakeThresholdSensor() {
    }

    public void setTag(String tag) {
    }

    @Override
    public void setDelay(int delay) {
    }

    @Override
    public boolean isLoaded() {
        return mIsLoaded;
    }

    @Override
    public void pause() {
        mPaused = true;
    }

    @Override
    public void resume() {
        mPaused = false;
    }

    @Override
    public void register(ThresholdSensor.Listener listener) {
        mListeners.add(listener);
    }

    @Override
    public void unregister(ThresholdSensor.Listener listener) {
        mListeners.remove(listener);
    }

    public void setLoaded(boolean loaded) {
        mIsLoaded = loaded;
    }

    void triggerEvent(boolean below, long timestampNs) {
        if (!mPaused) {
            for (Listener listener : mListeners) {
                listener.onThresholdCrossed(new ThresholdSensorEvent(below, timestampNs));
            }
        }
    }

    boolean isPaused() {
        return mPaused;
    }

    int getNumListeners() {
        return mListeners.size();
    }
}
