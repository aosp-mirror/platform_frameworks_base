/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.systemui.statusbar.policy;

import android.content.Context;
import android.hardware.SensorPrivacyManager;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Controls sensor privacy state and notification.
 */
@Singleton
public class SensorPrivacyControllerImpl implements SensorPrivacyController,
        SensorPrivacyManager.OnSensorPrivacyChangedListener {
    private SensorPrivacyManager mSensorPrivacyManager;
    private final List<OnSensorPrivacyChangedListener> mListeners;
    private Object mLock = new Object();
    private boolean mSensorPrivacyEnabled;

    /**
     * Public constructor.
     */
    @Inject
    public SensorPrivacyControllerImpl(Context context) {
        mSensorPrivacyManager = (SensorPrivacyManager) context.getSystemService(
                Context.SENSOR_PRIVACY_SERVICE);
        mSensorPrivacyEnabled = mSensorPrivacyManager.isSensorPrivacyEnabled();
        mSensorPrivacyManager.addSensorPrivacyListener(this);
        mListeners = new ArrayList<>(1);
    }

    /**
     * Returns whether sensor privacy is enabled.
     */
    public boolean isSensorPrivacyEnabled() {
        synchronized (mLock) {
            return mSensorPrivacyEnabled;
        }
    }

    /**
     * Adds the provided listener for callbacks when sensor privacy state changes.
     */
    public void addCallback(OnSensorPrivacyChangedListener listener) {
        synchronized (mLock) {
            mListeners.add(listener);
            notifyListenerLocked(listener);
        }
    }

    /**
     * Removes the provided listener from callbacks when sensor privacy state changes.
     */
    public void removeCallback(OnSensorPrivacyChangedListener listener) {
        synchronized (mLock) {
            mListeners.remove(listener);
        }
    }

    /**
     * Callback invoked by the SensorPrivacyService when sensor privacy state changes.
     */
    public void onSensorPrivacyChanged(boolean enabled) {
        synchronized (mLock) {
            mSensorPrivacyEnabled = enabled;
            for (OnSensorPrivacyChangedListener listener : mListeners) {
                notifyListenerLocked(listener);
            }
        }
    }

    private void notifyListenerLocked(OnSensorPrivacyChangedListener listener) {
        listener.onSensorPrivacyChanged(mSensorPrivacyEnabled);
    }
}
