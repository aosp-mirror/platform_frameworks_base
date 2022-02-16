/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.sensorprivacy;

import android.os.Handler;

import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;

import java.util.Objects;

class SensorPrivacyStateControllerImpl extends SensorPrivacyStateController {

    private static final String SENSOR_PRIVACY_XML_FILE = "sensor_privacy_impl.xml";

    private static SensorPrivacyStateControllerImpl sInstance;

    private PersistedState mPersistedState;

    private SensorPrivacyListener mListener;
    private Handler mListenerHandler;

    static SensorPrivacyStateController getInstance() {
        if (sInstance == null) {
            sInstance = new SensorPrivacyStateControllerImpl();
        }
        return sInstance;
    }

    private SensorPrivacyStateControllerImpl() {
        mPersistedState = PersistedState.fromFile(SENSOR_PRIVACY_XML_FILE);
        persistAll();
    }

    @Override
    SensorState getStateLocked(int toggleType, int userId, int sensor) {
        SensorState sensorState = mPersistedState.getState(toggleType, userId, sensor);
        if (sensorState != null) {
            return new SensorState(sensorState);
        }
        return getDefaultSensorState();
    }

    private static SensorState getDefaultSensorState() {
        return new SensorState(false);
    }

    @Override
    void setStateLocked(int toggleType, int userId, int sensor, boolean enabled,
            Handler callbackHandler, SetStateResultCallback callback) {
        // Changing the SensorState's mEnabled updates the timestamp of its last change.
        // A nonexistent state -> unmuted should not set the timestamp.
        SensorState lastState = mPersistedState.getState(toggleType, userId, sensor);
        if (lastState == null) {
            if (!enabled) {
                sendSetStateCallback(callbackHandler, callback, false);
                return;
            } else if (enabled) {
                SensorState sensorState = new SensorState(true);
                mPersistedState.setState(toggleType, userId, sensor, sensorState);
                notifyStateChangeLocked(toggleType, userId, sensor, sensorState);
                sendSetStateCallback(callbackHandler, callback, true);
                return;
            }
        }
        if (lastState.setEnabled(enabled)) {
            notifyStateChangeLocked(toggleType, userId, sensor, lastState);
            sendSetStateCallback(callbackHandler, callback, true);
            return;
        }
        sendSetStateCallback(callbackHandler, callback, false);
    }

    private void notifyStateChangeLocked(int toggleType, int userId, int sensor,
            SensorState sensorState) {
        if (mListenerHandler != null && mListener != null) {
            mListenerHandler.sendMessage(PooledLambda.obtainMessage(
                    SensorPrivacyListener::onSensorPrivacyChanged, mListener,
                    toggleType, userId, sensor, new SensorState(sensorState)));
        }
        schedulePersistLocked();
    }

    @Override
    void setSensorPrivacyListenerLocked(Handler handler, SensorPrivacyListener listener) {
        Objects.requireNonNull(handler);
        Objects.requireNonNull(listener);
        if (mListener != null) {
            throw new IllegalStateException("Listener is already set");
        }
        mListener = listener;
        mListenerHandler = handler;
    }

    @Override
    void schedulePersistLocked() {
        mPersistedState.schedulePersist();
    }

    @Override
    void forEachStateLocked(SensorPrivacyStateConsumer consumer) {
        mPersistedState.forEachKnownState(consumer::accept);
    }

    @Override
    void resetForTestingImpl() {
        mPersistedState.resetForTesting();
        mListener = null;
        mListenerHandler = null;
        sInstance = null;
    }

    @Override
    void dumpLocked(DualDumpOutputStream dumpStream) {
        mPersistedState.dump(dumpStream);
    }
}
