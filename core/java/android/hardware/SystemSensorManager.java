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

import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

import dalvik.system.CloseGuard;

import android.os.Handler;
import android.os.Looper;
import android.os.MessageQueue;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

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
    private final ArrayList<SensorEventListenerSensorPair> mListenerDelegates = new ArrayList<SensorEventListenerSensorPair>();

    // Common pool of sensor events.
    private static SensorEventPool sPool;

    // Looper associated with the context in which this instance was created.
    private final Looper mMainLooper;

    // maps a SensorEventListener to a SensorEventQueue
    private final Hashtable<SensorEventListener, SensorEventQueue> mSensorEventQueueMap;

    /** {@hide} */
    public SystemSensorManager(Looper mainLooper) {
        mMainLooper = mainLooper;
        mSensorEventQueueMap = new Hashtable<SensorEventListener, SensorEventQueue>();

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

                sPool = new SensorEventPool( sFullSensorsList.size()*2 );
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
        // We map SensorEventListeners to a SensorEventQueue, which holds the looper

        if (sensor == null) throw new NullPointerException("sensor cannot be null");

        boolean result;
        synchronized (mSensorEventQueueMap) {
            // check if we already have this SensorEventListener, Sensor pair
            // registered -- if so, we ignore the register. This is not ideal
            // but this is what the implementation has always been doing.
            for (SensorEventListenerSensorPair l : mListenerDelegates) {
                if (l.isSameListenerSensorPair(listener, sensor)) {
                    // already added, just return silently.
                    return true;
                }
            }

            // now find the SensorEventQueue associated to this listener
            SensorEventQueue queue = mSensorEventQueueMap.get(listener);
            if (queue != null) {
                result = queue.addSensor(sensor, delay);
                if (result) {
                    // create a new ListenerDelegate for this pair
                    mListenerDelegates.add(new SensorEventListenerSensorPair(listener, sensor));
                }
            } else {
                Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;
                queue = new SensorEventQueue(listener, looper.getQueue());
                result = queue.addSensor(sensor, delay);
                if (result) {
                    // create a new ListenerDelegate for this pair
                    mListenerDelegates.add(new SensorEventListenerSensorPair(listener, sensor));
                    mSensorEventQueueMap.put(listener, queue);
                } else {
                    queue.dispose();
                }
            }
        }
        return result;
    }

    /** @hide */
    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        synchronized (mSensorEventQueueMap) {

            // remove this listener/sensor from our list
            final ArrayList<SensorEventListenerSensorPair> copy =
                    new ArrayList<SensorEventListenerSensorPair>(mListenerDelegates);
            int lastIndex = copy.size()-1;
            for (int i=lastIndex ; i>= 0 ; i--) {
                if (copy.get(i).isSameListenerSensorPair(listener, sensor)) {
                    mListenerDelegates.remove(i);
                }
            }

            // find the SensorEventQueue associated to this SensorEventListener
            SensorEventQueue queue = mSensorEventQueueMap.get(listener);
            if (queue != null) {
                if (sensor != null) {
                    queue.removeSensor(sensor);
                } else {
                    queue.removeAllSensors();
                }
                if (!queue.hasSensors()) {
                    mSensorEventQueueMap.remove(listener);
                    queue.dispose();
                }
            }
        }
    }


    /*
     * ListenerDelegate is essentially a SensorEventListener, Sensor pair
     * and is associated with a single SensorEventQueue.
     */
    private static final class SensorEventListenerSensorPair {
        private final SensorEventListener mSensorEventListener;
        private final Sensor mSensor;
        public SensorEventListenerSensorPair(SensorEventListener listener, Sensor sensor) {
            mSensorEventListener = listener;
            mSensor = sensor;
        }
        public boolean isSameListenerSensorPair(SensorEventListener listener, Sensor sensor) {
            // if sensor is null, we match only on the listener
            if (sensor != null) {
                return (listener == mSensorEventListener) &&
                        (sensor.getHandle() == mSensor.getHandle());
            } else {
                return (listener == mSensorEventListener);
            }
        }
    }

    /*
     * SensorEventQueue is the communication channel with the sensor service,
     * there is a one-to-one mapping between SensorEventQueue and
     * SensorEventListener.
     */
    private static final class SensorEventQueue {
        private static native int nativeInitSensorEventQueue(SensorEventQueue eventQ, MessageQueue msgQ, float[] scratch);
        private static native int nativeEnableSensor(int eventQ, int handle, int us);
        private static native int nativeDisableSensor(int eventQ, int handle);
        private static native void nativeDestroySensorEventQueue(int eventQ);
        private int nSensorEventQueue;
        private final SensorEventListener mListener;
        private final SparseBooleanArray mActiveSensors = new SparseBooleanArray();
        private final SparseIntArray mSensorAccuracies = new SparseIntArray();
        private final SparseBooleanArray mFirstEvent = new SparseBooleanArray();
        private final CloseGuard mCloseGuard = CloseGuard.get();
        private final float[] mScratch = new float[16];

        public SensorEventQueue(SensorEventListener listener, MessageQueue msgQ) {
            nSensorEventQueue = nativeInitSensorEventQueue(this, msgQ, mScratch);
            mListener = listener;
            mCloseGuard.open("dispose");
        }
        public void dispose() {
            dispose(false);
        }

        public boolean addSensor(Sensor sensor, int delay) {
            if (enableSensor(sensor, delay) == 0) {
                mActiveSensors.put(sensor.getHandle(), true);
                return true;
            }
            return false;
        }

        public void removeAllSensors() {
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
        }

        public void removeSensor(Sensor sensor) {
            final int handle = sensor.getHandle();
            if (mActiveSensors.get(handle)) {
                disableSensor(sensor);
                mActiveSensors.put(sensor.getHandle(), false);
            }
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

        // Called from native code.
        @SuppressWarnings("unused")
        private void dispatchSensorEvent(int handle, float[] values, int inAccuracy, long timestamp) {
            // this is always called on the same thread.
            final SensorEvent t = sPool.getFromPool();
            try {
                final Sensor sensor = sHandleToSensor.get(handle);
                final SensorEventListener listener = mListener;
                // FIXME: handle more than 3 values
                System.arraycopy(values, 0, t.values, 0, 3);
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
                            listener.onAccuracyChanged(t.sensor, t.accuracy);
                        }
                        break;
                    default:
                        // For other sensors, just report the accuracy once
                        if (mFirstEvent.get(handle) == false) {
                            mFirstEvent.put(handle, true);
                            listener.onAccuracyChanged(
                                    t.sensor, SENSOR_STATUS_ACCURACY_HIGH);
                        }
                        break;
                }
                listener.onSensorChanged(t);
            } finally {
                sPool.returnToPool(t);
            }
        }
    }

    /*
     * A dumb pool of SensorEvent
     */
    private static final class SensorEventPool {
        private final int mPoolSize;
        private final SensorEvent mPool[];
        private int mNumItemsInPool;

        private SensorEvent createSensorEvent() {
            // maximal size for all legacy events is 3
            return new SensorEvent(3);
        }

        SensorEventPool(int poolSize) {
            mPoolSize = poolSize;
            mNumItemsInPool = poolSize;
            mPool = new SensorEvent[poolSize];
        }

        SensorEvent getFromPool() {
            SensorEvent t = null;
            synchronized (this) {
                if (mNumItemsInPool > 0) {
                    // remove the "top" item from the pool
                    final int index = mPoolSize - mNumItemsInPool;
                    t = mPool[index];
                    mPool[index] = null;
                    mNumItemsInPool--;
                }
            }
            if (t == null) {
                // the pool was empty or this item was removed from the pool for
                // the first time. In any case, we need to create a new item.
                t = createSensorEvent();
            }
            return t;
        }

        void returnToPool(SensorEvent t) {
            synchronized (this) {
                // is there space left in the pool?
                if (mNumItemsInPool < mPoolSize) {
                    // if so, return the item to the pool
                    mNumItemsInPool++;
                    final int index = mPoolSize - mNumItemsInPool;
                    mPool[index] = t;
                }
            }
        }
    }
}
