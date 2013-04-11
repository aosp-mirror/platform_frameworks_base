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

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.Log;
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

    // Looper associated with the context in which this instance was created.
    private final Looper mMainLooper;
    private final int mTargetSdkLevel;

    /** {@hide} */
    public SystemSensorManager(Context context, Looper mainLooper) {
        mMainLooper = mainLooper;
        mTargetSdkLevel = context.getApplicationInfo().targetSdkVersion;
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
                queue = new SensorEventQueue(listener, looper, this);
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
                    result = queue.removeSensor(sensor, true);
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
    protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor,
            boolean disable) {
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
                    result = queue.removeSensor(sensor, disable);
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
        protected final SystemSensorManager mManager;

        BaseEventQueue(Looper looper, SystemSensorManager manager) {
            nSensorEventQueue = nativeInitBaseEventQueue(this, looper.getQueue(), mScratch);
            mCloseGuard.open("dispose");
            mManager = manager;
        }

        public void dispose() {
            dispose(false);
        }

        public boolean addSensor(Sensor sensor, int delay) {
            // Check if already present.
            int handle = sensor.getHandle();
            if (mActiveSensors.get(handle)) return false;

            // Get ready to receive events before calling enable.
            mActiveSensors.put(handle, true);
            addSensorEvent(sensor);
            if (enableSensor(sensor, delay) != 0) {
                removeSensor(sensor, false);
                return false;
            }
            return true;
        }

        public boolean removeAllSensors() {
            for (int i=0 ; i<mActiveSensors.size(); i++) {
                if (mActiveSensors.valueAt(i) == true) {
                    int handle = mActiveSensors.keyAt(i);
                    Sensor sensor = sHandleToSensor.get(handle);
                    if (sensor != null) {
                        disableSensor(sensor);
                        mActiveSensors.put(handle, false);
                        removeSensorEvent(sensor);
                    } else {
                        // it should never happen -- just ignore.
                    }
                }
            }
            return true;
        }

        public boolean removeSensor(Sensor sensor, boolean disable) {
            final int handle = sensor.getHandle();
            if (mActiveSensors.get(handle)) {
                if (disable) disableSensor(sensor);
                mActiveSensors.put(sensor.getHandle(), false);
                removeSensorEvent(sensor);
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

        protected abstract void addSensorEvent(Sensor sensor);
        protected abstract void removeSensorEvent(Sensor sensor);
    }

    static final class SensorEventQueue extends BaseEventQueue {
        private final SensorEventListener mListener;
        private final SparseArray<SensorEvent> mSensorsEvents = new SparseArray<SensorEvent>();

        public SensorEventQueue(SensorEventListener listener, Looper looper,
                SystemSensorManager manager) {
            super(looper, manager);
            mListener = listener;
        }

        public void addSensorEvent(Sensor sensor) {
            SensorEvent t = new SensorEvent(Sensor.getMaxLengthValuesArray(sensor,
                    mManager.mTargetSdkLevel));
            mSensorsEvents.put(sensor.getHandle(), t);
        }

        public void removeSensorEvent(Sensor sensor) {
            mSensorsEvents.delete(sensor.getHandle());
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int inAccuracy,
                long timestamp) {
            final Sensor sensor = sHandleToSensor.get(handle);
            SensorEvent t = mSensorsEvents.get(handle);
            if (t == null) {
                Log.e(TAG, "Error: Sensor Event is null for Sensor: " + sensor);
                return;
            }
            // Copy from the values array.
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
        }
    }

    static final class TriggerEventQueue extends BaseEventQueue {
        private final TriggerEventListener mListener;
        private final SparseArray<TriggerEvent> mTriggerEvents = new SparseArray<TriggerEvent>();

        public TriggerEventQueue(TriggerEventListener listener, Looper looper,
                SystemSensorManager manager) {
            super(looper, manager);
            mListener = listener;
        }

        public void addSensorEvent(Sensor sensor) {
            TriggerEvent t = new TriggerEvent(Sensor.getMaxLengthValuesArray(sensor,
                    mManager.mTargetSdkLevel));
            mTriggerEvents.put(sensor.getHandle(), t);
        }

        public void removeSensorEvent(Sensor sensor) {
            mTriggerEvents.delete(sensor.getHandle());
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp) {
            final Sensor sensor = sHandleToSensor.get(handle);
            TriggerEvent t = mTriggerEvents.get(handle);
            if (t == null) {
                Log.e(TAG, "Error: Trigger Event is null for Sensor: " + sensor);
                return;
            }

            // Copy from the values array.
            System.arraycopy(values, 0, t.values, 0, t.values.length);
            t.timestamp = timestamp;
            t.sensor = sensor;

            // A trigger sensor is auto disabled. So just clean up and don't call native
            // disable.
            mManager.cancelTriggerSensorImpl(mListener, sensor, false);

            mListener.onTrigger(t);
        }
    }
}
