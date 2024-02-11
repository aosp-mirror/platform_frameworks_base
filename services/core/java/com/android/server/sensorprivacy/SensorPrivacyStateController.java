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

import android.annotation.FlaggedApi;
import android.os.Handler;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.camera.flags.Flags;
import com.android.internal.util.dump.DualDumpOutputStream;
import com.android.internal.util.function.pooled.PooledLambda;

abstract class SensorPrivacyStateController {

    private static SensorPrivacyStateController sInstance;

    AllSensorStateController mAllSensorStateController = AllSensorStateController.getInstance();

    private final Object mLock = new Object();

    static SensorPrivacyStateController getInstance() {
        if (sInstance == null) {
            sInstance = SensorPrivacyStateControllerImpl.getInstance();
        }

        return sInstance;
    }

    SensorState getState(int toggleType, int userId, int sensor) {
        synchronized (mLock) {
            return getStateLocked(toggleType, userId, sensor);
        }
    }

    void setState(int toggleType, int userId, int sensor, boolean enabled, Handler callbackHandler,
            SetStateResultCallback callback) {
        synchronized (mLock) {
            setStateLocked(toggleType, userId, sensor, enabled, callbackHandler, callback);
        }
    }

    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    void setState(int toggleType, int userId, int sensor, int state, Handler callbackHandler,
            SetStateResultCallback callback) {
        synchronized (mLock) {
            setStateLocked(toggleType, userId, sensor, state, callbackHandler, callback);
        }
    }

    void setSensorPrivacyListener(Handler handler,
            SensorPrivacyListener listener) {
        synchronized (mLock) {
            setSensorPrivacyListenerLocked(handler, listener);
        }
    }

    // Following calls are for the developer settings sensor mute feature
    boolean getAllSensorState() {
        synchronized (mLock) {
            return mAllSensorStateController.getAllSensorStateLocked();
        }
    }

    void setAllSensorState(boolean enable) {
        synchronized (mLock) {
            mAllSensorStateController.setAllSensorStateLocked(enable);
        }
    }

    void setAllSensorPrivacyListener(Handler handler, AllSensorPrivacyListener listener) {
        synchronized (mLock) {
            mAllSensorStateController.setAllSensorPrivacyListenerLocked(handler, listener);
        }
    }

    void persistAll() {
        synchronized (mLock) {
            mAllSensorStateController.schedulePersistLocked();
            schedulePersistLocked();
        }
    }

    void forEachState(SensorPrivacyStateConsumer consumer) {
        synchronized (mLock) {
            forEachStateLocked(consumer);
        }
    }

    void dump(DualDumpOutputStream dumpStream) {
        synchronized (mLock) {
            mAllSensorStateController.dumpLocked(dumpStream);
            dumpLocked(dumpStream);
        }
        dumpStream.flush();
    }

    public void atomic(Runnable r) {
        synchronized (mLock) {
            r.run();
        }
    }

    interface SensorPrivacyListener {
        void onSensorPrivacyChanged(int toggleType, int userId, int sensor, SensorState state);
    }

    interface SensorPrivacyStateConsumer {
        void accept(int toggleType, int userId, int sensor, SensorState state);
    }

    interface SetStateResultCallback {
        void callback(boolean changed);
    }

    interface AllSensorPrivacyListener {
        void onAllSensorPrivacyChanged(boolean enabled);
    }

    @GuardedBy("mLock")
    abstract SensorState getStateLocked(int toggleType, int userId, int sensor);

    @GuardedBy("mLock")
    abstract void setStateLocked(int toggleType, int userId, int sensor, boolean enabled,
            Handler callbackHandler, SetStateResultCallback callback);

    @GuardedBy("mLock")
    @FlaggedApi(Flags.FLAG_CAMERA_PRIVACY_ALLOWLIST)
    abstract void setStateLocked(int toggleType, int userId, int sensor, int state,
            Handler callbackHandler, SetStateResultCallback callback);

    @GuardedBy("mLock")
    abstract void setSensorPrivacyListenerLocked(Handler handler,
            SensorPrivacyListener listener);

    @GuardedBy("mLock")
    abstract void schedulePersistLocked();

    @GuardedBy("mLock")
    abstract void forEachStateLocked(SensorPrivacyStateConsumer consumer);

    @GuardedBy("mLock")
    abstract void dumpLocked(DualDumpOutputStream dumpStream);

    static void sendSetStateCallback(Handler callbackHandler,
            SetStateResultCallback callback, boolean success) {
        callbackHandler.sendMessage(PooledLambda.obtainMessage(SetStateResultCallback::callback,
                callback, success));
    }

    /**
     * Used for unit testing
     */
    void resetForTesting() {
        mAllSensorStateController.resetForTesting();
        resetForTestingImpl();
        sInstance = null;
    }
    abstract void resetForTestingImpl();
}
