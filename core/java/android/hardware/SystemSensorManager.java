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

package android.hardware;

import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;
import android.util.Pools;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;
import dalvik.system.CloseGuard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Sensor manager implementation that communicates with the built-in
 * system sensors.
 *
 * @hide
 */
public class SystemSensorManager extends SensorManager {
    private static native void nativeClassInit();
    private static native int nativeGetNextSensor(Sensor sensor, int next);

    private static boolean sSensorModuleInitialized = false;
    private static final Object sSensorModuleLock = new Object();
    private static final ArrayList<Sensor> sFullSensorsList = new ArrayList<Sensor>();
    private static final SparseArray<Sensor> sHandleToSensor = new SparseArray<Sensor>();

    // Listener list
    private final HashMap<SensorEventListener, SensorEventQueue> mSensorListeners =
            new HashMap<SensorEventListener, SensorEventQueue>();
    private final HashMap<TriggerEventListener, TriggerEventQueue> mTriggerListeners =
            new HashMap<TriggerEventListener, TriggerEventQueue>();

    private static final int MAX_EVENTS = 16;
    private static Pools.SynchronizedPool<SensorEvent> sSensorEventPool;
    private static Pools.SynchronizedPool<TriggerEvent> sTriggerEventPool;

    // Looper associated with the context in which this instance was created.
    private final Looper mMainLooper;

    /** {@hide} */
    public SystemSensorManager(Looper mainLooper) {
        mMainLooper = mainLooper;
        synchronized(sSensorModuleLock) {
            if (!sSensorModuleInitialized) {
                sSensorModuleInitialized = true;

                nativeClassInit();

                // initialize the sensor list
                final ArrayList<Sensor> fullList = sFullSensorsList;
                int i = 0;
                do {
                    Sensor sensor = new Sensor();
                    i = nativeGetNextSensor(sensor, i);
                    if (i>=0) {
                        //Log.d(TAG, "found sensor: " + sensor.getName() +
                        //        ", handle=" + sensor.getHandle());
                        fullList.add(sensor);
                        sHandleToSensor.append(sensor.getHandle(), sensor);
                    }
                } while (i>0);

                sSensorEventPool = new Pools.SynchronizedPool<SensorEvent>(
                        Math.max(sFullSensorsList.size()*2, 1));
                sTriggerEventPool = new Pools.SynchronizedPool<TriggerEvent>(
                        Math.max(sFullSensorsList.size()*2, 1));
            }
        }
    }


    /** @hide */
    @Override
    protected List<Sensor> getFullSensorList() {
        return sFullSensorsList;
    }


    /** @hide */
    @Override
    protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
            int delay, Handler handler)
    {
        // Invariants to preserve:
        // - one Looper per SensorEventListener
        // - one Looper per SensorEventQueue
        // We map SensorEventListener to a SensorEventQueue, which holds the looper
        if (sensor == null) throw new IllegalArgumentException("sensor cannot be null");

        // Trigger Sensors should use the requestTriggerSensor call.
        if (Sensor.getReportingMode(sensor) == Sensor.REPORTING_MODE_ONE_SHOT) return false;

        synchronized (mSensorListeners) {
            SensorEventQueue queue = mSensorListeners.get(listener);
            if (queue == null) {
                Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;
                queue = new SensorEventQueue(listener, looper);
                if (!queue.addSensor(sensor, delay)) {
                    queue.dispose();
                    return false;
                }
                mSensorListeners.put(listener, queue);
                return true;
            } else {
                return queue.addSensor(sensor, delay);
            }
        }
    }

    /** @hide */
    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        // Trigger Sensors should use the cancelTriggerSensor call.
        if (sensor != null && Sensor.getReportingMode(sensor) == Sensor.REPORTING_MODE_ONE_SHOT) {
            return;
        }

        synchronized (mSensorListeners) {
            SensorEventQueue queue = mSensorListeners.get(listener);
            if (queue != null) {
                boolean result;
                if (sensor == null) {
                    result = queue.removeAllSensors();
                } else {
                    result = queue.removeSensor(sensor);
                }
                if (result && !queue.hasSensors()) {
                    mSensorListeners.remove(listener);
                    queue.dispose();
                }
            }
        }
    }

    /** @hide */
    @Override
    protected boolean requestTriggerSensorImpl(TriggerEventListener listener, Sensor sensor) {
        if (sensor == null) throw new IllegalArgumentException("sensor cannot be null");

        if (Sensor.getReportingMode(sensor) != Sensor.REPORTING_MODE_ONE_SHOT) return false;

        synchronized (mTriggerListeners) {
            TriggerEventQueue queue = mTriggerListeners.get(listener);
            if (queue == null) {
                queue = new TriggerEventQueue(listener, mMainLooper, this);
                if (!queue.addSensor(sensor, 0)) {
                    queue.dispose();
                    return false;
                }
                mTriggerListeners.put(listener, queue);
                return true;
            } else {
                return queue.addSensor(sensor, 0);
            }
        }
    }

    /** @hide */
    @Override
    protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor) {
        if (sensor != null && Sensor.getReportingMode(sensor) != Sensor.REPORTING_MODE_ONE_SHOT) {
            return false;
        }
        synchronized (mTriggerListeners) {
            TriggerEventQueue queue = mTriggerListeners.get(listener);
            if (queue != null) {
                boolean result;
                if (sensor == null) {
                    result = queue.removeAllSensors();
                } else {
                    result = queue.removeSensor(sensor);
                }
                if (result && !queue.hasSensors()) {
                    mTriggerListeners.remove(listener);
                    queue.dispose();
                }
                return result;
            }
            return false;
        }
    }

    /*
     * BaseEventQueue is the communication channel with the sensor service,
     * SensorEventQueue, TriggerEventQueue are subclases and there is one-to-one mapping between
     * the queues and the listeners.
     */
    private static abstract class BaseEventQueue {
        private native int nativeInitBaseEventQueue(BaseEventQueue eventQ, MessageQueue msgQ,
                float[] scratch);
        private static native int nativeEnableSensor(int eventQ, int handle, int us);
        private static native int nativeDisableSensor(int eventQ, int handle);
        private static native void nativeDestroySensorEventQueue(int eventQ);
        private int nSensorEventQueue;
        private final SparseBooleanArray mActiveSensors = new SparseBooleanArray();
        protected final SparseIntArray mSensorAccuracies = new SparseIntArray();
        protected final SparseBooleanArray mFirstEvent = new SparseBooleanArray();
        private final CloseGuard mCloseGuard = CloseGuard.get();
        private final float[] mScratch = new float[16];

        BaseEventQueue(Looper looper) {
            nSensorEventQueue = nativeInitBaseEventQueue(this, looper.getQueue(), mScratch);
            mCloseGuard.open("dispose");
        }

        public void dispose() {
            dispose(false);
        }

        public boolean addSensor(Sensor sensor, int delay) {
            // Check if already present.
            if (mActiveSensors.get(sensor.getHandle())) return false;

            if (enableSensor(sensor, delay) == 0) {
                mActiveSensors.put(sensor.getHandle(), true);
                return true;
            }
            return false;
        }

        public boolean removeAllSensors() {
            for (int i=0 ; i<mActiveSensors.size(); i++) {
                if (mActiveSensors.valueAt(i) == true) {
                    int handle = mActiveSensors.keyAt(i);
                    Sensor sensor = sHandleToSensor.get(handle);
                    if (sensor != null) {
                        disableSensor(sensor);
                        mActiveSensors.put(handle, false);
                    } else {
                        // it should never happen -- just ignore.
                    }
                }
            }
            return true;
        }

        public boolean removeSensor(Sensor sensor) {
            final int handle = sensor.getHandle();
            if (mActiveSensors.get(handle)) {
                disableSensor(sensor);
                mActiveSensors.put(sensor.getHandle(), false);
                return true;
            }
            return false;
        }

        public boolean hasSensors() {
            // no more sensors are set
            return mActiveSensors.indexOfValue(true) >= 0;
        }

        @Override
        protected void finalize() throws Throwable {
            try {
                dispose(true);
            } finally {
                super.finalize();
            }
        }

        private void dispose(boolean finalized) {
            if (mCloseGuard != null) {
                if (finalized) {
                    mCloseGuard.warnIfOpen();
                }
                mCloseGuard.close();
            }
            if (nSensorEventQueue != 0) {
                nativeDestroySensorEventQueue(nSensorEventQueue);
                nSensorEventQueue = 0;
            }
        }

        private int enableSensor(Sensor sensor, int us) {
            if (nSensorEventQueue == 0) throw new NullPointerException();
            if (sensor == null) throw new NullPointerException();
            return nativeEnableSensor(nSensorEventQueue, sensor.getHandle(), us);
        }
        private int disableSensor(Sensor sensor) {
            if (nSensorEventQueue == 0) throw new NullPointerException();
            if (sensor == null) throw new NullPointerException();
            return nativeDisableSensor(nSensorEventQueue, sensor.getHandle());
        }
        protected abstract void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp);
    }

    static final class SensorEventQueue extends BaseEventQueue {
        private final SensorEventListener mListener;

        public SensorEventQueue(SensorEventListener listener, Looper looper) {
            super(looper);
            mListener = listener;
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int inAccuracy,
                long timestamp) {
            final Sensor sensor = sHandleToSensor.get(handle);
            SensorEvent t = sSensorEventPool.acquire();
            if (t == null) t = new SensorEvent(MAX_EVENTS);
            try {
                // Copy the entire values array.
                // Any changes in length will be handled at the native layer.
                System.arraycopy(values, 0, t.values, 0, t.values.length);
                t.timestamp = timestamp;
                t.accuracy = inAccuracy;
                t.sensor = sensor;
                switch (t.sensor.getType()) {
                    // Only report accuracy for sensors that support it.
                    case Sensor.TYPE_MAGNETIC_FIELD:
                    case Sensor.TYPE_ORIENTATION:
                        // call onAccuracyChanged() only if the value changes
                        final int accuracy = mSensorAccuracies.get(handle);
                        if ((t.accuracy >= 0) && (accuracy != t.accuracy)) {
                            mSensorAccuracies.put(handle, t.accuracy);
                            mListener.onAccuracyChanged(t.sensor, t.accuracy);
                        }
                        break;
                    default:
                        // For other sensors, just report the accuracy once
                        if (mFirstEvent.get(handle) == false) {
                            mFirstEvent.put(handle, true);
                            mListener.onAccuracyChanged(
                                    t.sensor, SENSOR_STATUS_ACCURACY_HIGH);
                        }
                        break;
                }
                mListener.onSensorChanged(t);
            } finally {
                sSensorEventPool.release(t);
            }
        }
    }

    static final class TriggerEventQueue extends BaseEventQueue {
        private final TriggerEventListener mListener;
        private SensorManager mManager;

        public TriggerEventQueue(TriggerEventListener listener, Looper looper,
                SensorManager manager) {
            super(looper);
            mListener = listener;
            mManager = manager;
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int accuracy, long timestamp) {
            final Sensor sensor = sHandleToSensor.get(handle);
            TriggerEvent t = sTriggerEventPool.acquire();
            if (t == null) t = new TriggerEvent(MAX_EVENTS);

            try {
                // Copy the entire values array.
                // Any changes in length will be handled at the native layer.
                System.arraycopy(values, 0, t.values, 0, t.values.length);
                t.timestamp = timestamp;
                t.sensor = sensor;

                // A trigger sensor should be auto disabled.
                mManager.cancelTriggerSensorImpl(mListener, sensor);

                mListener.onTrigger(t);
            } finally {
                sTriggerEventPool.release(t);
            }
        }
    }
}
