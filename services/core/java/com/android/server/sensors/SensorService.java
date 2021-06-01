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

package com.android.server.sensors;

import static com.android.server.sensors.SensorManagerInternal.ProximityActiveListener;

import android.annotation.NonNull;
import android.content.Context;
import android.util.ArrayMap;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.util.ConcurrentUtils;
import com.android.server.LocalServices;
import com.android.server.SystemServerInitThreadPool;
import com.android.server.SystemService;
import com.android.server.utils.TimingsTraceAndSlog;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.Future;

public class SensorService extends SystemService {
    private static final String START_NATIVE_SENSOR_SERVICE = "StartNativeSensorService";
    private final Object mLock = new Object();
    @GuardedBy("mLock")
    private final ArrayMap<ProximityActiveListener, ProximityListenerProxy> mProximityListeners =
            new ArrayMap<>();
    @GuardedBy("mLock")
    private Future<?> mSensorServiceStart;
    @GuardedBy("mLock")
    private long mPtr;


    /** Start the sensor service. This is a blocking call and can take time. */
    private static native long startSensorServiceNative(ProximityActiveListener listener);

    private static native void registerProximityActiveListenerNative(long ptr);
    private static native void unregisterProximityActiveListenerNative(long ptr);


    public SensorService(Context ctx) {
        super(ctx);
        synchronized (mLock) {
            mSensorServiceStart = SystemServerInitThreadPool.submit(() -> {
                TimingsTraceAndSlog traceLog = TimingsTraceAndSlog.newAsyncLog();
                traceLog.traceBegin(START_NATIVE_SENSOR_SERVICE);
                long ptr = startSensorServiceNative(new ProximityListenerDelegate());
                synchronized (mLock) {
                    mPtr = ptr;
                }
                traceLog.traceEnd();
            }, START_NATIVE_SENSOR_SERVICE);
        }
    }

    @Override
    public void onStart() {
        LocalServices.addService(SensorManagerInternal.class, new LocalService());
    }

    @Override
    public void onBootPhase(int phase) {
        if (phase == SystemService.PHASE_WAIT_FOR_SENSOR_SERVICE) {
            ConcurrentUtils.waitForFutureNoInterrupt(mSensorServiceStart,
                    START_NATIVE_SENSOR_SERVICE);
            synchronized (mLock) {
                mSensorServiceStart = null;
            }
        }
    }

    class LocalService extends SensorManagerInternal {
        @Override
        public void addProximityActiveListener(@NonNull Executor executor,
                @NonNull ProximityActiveListener listener) {
            Objects.requireNonNull(executor, "executor must not be null");
            Objects.requireNonNull(listener, "listener must not be null");
            ProximityListenerProxy proxy = new ProximityListenerProxy(executor, listener);
            synchronized (mLock) {
                if (mProximityListeners.containsKey(listener)) {
                    throw new IllegalArgumentException("listener already registered");
                }
                mProximityListeners.put(listener, proxy);
                if (mProximityListeners.size() == 1) {
                    registerProximityActiveListenerNative(mPtr);
                }
            }
        }

        @Override
        public void removeProximityActiveListener(@NonNull ProximityActiveListener listener) {
            Objects.requireNonNull(listener, "listener must not be null");
            synchronized (mLock) {
                ProximityListenerProxy proxy = mProximityListeners.remove(listener);
                if (proxy == null) {
                    throw new IllegalArgumentException(
                            "listener was not registered with sensor service");
                }
                if (mProximityListeners.isEmpty()) {
                    unregisterProximityActiveListenerNative(mPtr);
                }
            }
        }
    }

    private static class ProximityListenerProxy implements ProximityActiveListener {
        private final Executor mExecutor;
        private final ProximityActiveListener mListener;

        ProximityListenerProxy(Executor executor, ProximityActiveListener listener) {
            mExecutor = executor;
            mListener = listener;
        }

        @Override
        public void onProximityActive(boolean isActive) {
            mExecutor.execute(() -> mListener.onProximityActive(isActive));
        }
    }

    private class ProximityListenerDelegate implements ProximityActiveListener {
        @Override
        public void onProximityActive(boolean isActive) {
            final ProximityListenerProxy[] listeners;
            // We can't call out while holding the lock because clients might be calling into us
            // while holding their own  locks (e.g. when registering / unregistering their
            // listeners).This would break lock ordering and create deadlocks. Instead, we need to
            // copy the listeners out and then only invoke them once we've dropped the lock.
            synchronized (mLock) {
                listeners = mProximityListeners.values().toArray(new ProximityListenerProxy[0]);
            }
            for (ProximityListenerProxy listener : listeners) {
                listener.onProximityActive(isActive);
            }
        }
    }
}
