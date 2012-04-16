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

import android.os.Looper;
import android.os.Process;
import android.os.Handler;
import android.os.Message;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.util.ArrayList;
import java.util.List;

/**
 * Sensor manager implementation that communicates with the built-in
 * system sensors.
 *
 * @hide
 */
public class SystemSensorManager extends SensorManager {
    private static final int SENSOR_DISABLE = -1;
    private static boolean sSensorModuleInitialized = false;
    private static ArrayList<Sensor> sFullSensorsList = new ArrayList<Sensor>();
    /* The thread and the sensor list are global to the process
     * but the actual thread is spawned on demand */
    private static SensorThread sSensorThread;
    private static int sQueue;

    // Used within this module from outside SensorManager, don't make private
    static SparseArray<Sensor> sHandleToSensor = new SparseArray<Sensor>();
    static final ArrayList<ListenerDelegate> sListeners =
        new ArrayList<ListenerDelegate>();

    // Common pool of sensor events.
    static SensorEventPool sPool;

    // Looper associated with the context in which this instance was created.
    final Looper mMainLooper;

    /*-----------------------------------------------------------------------*/

    static private class SensorThread {

        Thread mThread;
        boolean mSensorsReady;

        SensorThread() {
        }

        @Override
        protected void finalize() {
        }

        // must be called with sListeners lock
        boolean startLocked() {
            try {
                if (mThread == null) {
                    mSensorsReady = false;
                    SensorThreadRunnable runnable = new SensorThreadRunnable();
                    Thread thread = new Thread(runnable, SensorThread.class.getName());
                    thread.start();
                    synchronized (runnable) {
                        while (mSensorsReady == false) {
                            runnable.wait();
                        }
                    }
                    mThread = thread;
                }
            } catch (InterruptedException e) {
            }
            return mThread == null ? false : true;
        }

        private class SensorThreadRunnable implements Runnable {
            SensorThreadRunnable() {
            }

            private boolean open() {
                // NOTE: this cannot synchronize on sListeners, since
                // it's held in the main thread at least until we
                // return from here.
                sQueue = sensors_create_queue();
                return true;
            }

            public void run() {
                //Log.d(TAG, "entering main sensor thread");
                final float[] values = new float[3];
                final int[] status = new int[1];
                final long timestamp[] = new long[1];
                Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_DISPLAY);

                if (!open()) {
                    return;
                }

                synchronized (this) {
                    // we've open the driver, we're ready to open the sensors
                    mSensorsReady = true;
                    this.notify();
                }

                while (true) {
                    // wait for an event
                    final int sensor = sensors_data_poll(sQueue, values, status, timestamp);

                    int accuracy = status[0];
                    synchronized (sListeners) {
                        if (sensor == -1 || sListeners.isEmpty()) {
                            // we lost the connection to the event stream. this happens
                            // when the last listener is removed or if there is an error
                            if (sensor == -1 && !sListeners.isEmpty()) {
                                // log a warning in case of abnormal termination
                                Log.e(TAG, "_sensors_data_poll() failed, we bail out: sensors=" + sensor);
                            }
                            // we have no more listeners or polling failed, terminate the thread
                            sensors_destroy_queue(sQueue);
                            sQueue = 0;
                            mThread = null;
                            break;
                        }
                        final Sensor sensorObject = sHandleToSensor.get(sensor);
                        if (sensorObject != null) {
                            // report the sensor event to all listeners that
                            // care about it.
                            final int size = sListeners.size();
                            for (int i=0 ; i<size ; i++) {
                                ListenerDelegate listener = sListeners.get(i);
                                if (listener.hasSensor(sensorObject)) {
                                    // this is asynchronous (okay to call
                                    // with sListeners lock held).
                                    listener.onSensorChangedLocked(sensorObject,
                                            values, timestamp, accuracy);
                                }
                            }
                        }
                    }
                }
                //Log.d(TAG, "exiting main sensor thread");
            }
        }
    }

    /*-----------------------------------------------------------------------*/

    private class ListenerDelegate {
        private final SensorEventListener mSensorEventListener;
        private final ArrayList<Sensor> mSensorList = new ArrayList<Sensor>();
        private final Handler mHandler;
        public SparseBooleanArray mSensors = new SparseBooleanArray();
        public SparseBooleanArray mFirstEvent = new SparseBooleanArray();
        public SparseIntArray mSensorAccuracies = new SparseIntArray();

        ListenerDelegate(SensorEventListener listener, Sensor sensor, Handler handler) {
            mSensorEventListener = listener;
            Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;
            // currently we create one Handler instance per listener, but we could
            // have one per looper (we'd need to pass the ListenerDelegate
            // instance to handleMessage and keep track of them separately).
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    final SensorEvent t = (SensorEvent)msg.obj;
                    final int handle = t.sensor.getHandle();

                    switch (t.sensor.getType()) {
                        // Only report accuracy for sensors that support it.
                        case Sensor.TYPE_MAGNETIC_FIELD:
                        case Sensor.TYPE_ORIENTATION:
                            // call onAccuracyChanged() only if the value changes
                            final int accuracy = mSensorAccuracies.get(handle);
                            if ((t.accuracy >= 0) && (accuracy != t.accuracy)) {
                                mSensorAccuracies.put(handle, t.accuracy);
                                mSensorEventListener.onAccuracyChanged(t.sensor, t.accuracy);
                            }
                            break;
                        default:
                            // For other sensors, just report the accuracy once
                            if (mFirstEvent.get(handle) == false) {
                                mFirstEvent.put(handle, true);
                                mSensorEventListener.onAccuracyChanged(
                                        t.sensor, SENSOR_STATUS_ACCURACY_HIGH);
                            }
                            break;
                    }

                    mSensorEventListener.onSensorChanged(t);
                    sPool.returnToPool(t);
                }
            };
            addSensor(sensor);
        }

        Object getListener() {
            return mSensorEventListener;
        }

        void addSensor(Sensor sensor) {
            mSensors.put(sensor.getHandle(), true);
            mSensorList.add(sensor);
        }
        int removeSensor(Sensor sensor) {
            mSensors.delete(sensor.getHandle());
            mSensorList.remove(sensor);
            return mSensors.size();
        }
        boolean hasSensor(Sensor sensor) {
            return mSensors.get(sensor.getHandle());
        }
        List<Sensor> getSensors() {
            return mSensorList;
        }

        void onSensorChangedLocked(Sensor sensor, float[] values, long[] timestamp, int accuracy) {
            SensorEvent t = sPool.getFromPool();
            final float[] v = t.values;
            v[0] = values[0];
            v[1] = values[1];
            v[2] = values[2];
            t.timestamp = timestamp[0];
            t.accuracy = accuracy;
            t.sensor = sensor;
            Message msg = Message.obtain();
            msg.what = 0;
            msg.obj = t;
            msg.setAsynchronous(true);
            mHandler.sendMessage(msg);
        }
    }

    /**
     * {@hide}
     */
    public SystemSensorManager(Looper mainLooper) {
        mMainLooper = mainLooper;

        synchronized(sListeners) {
            if (!sSensorModuleInitialized) {
                sSensorModuleInitialized = true;

                nativeClassInit();

                // initialize the sensor list
                sensors_module_init();
                final ArrayList<Sensor> fullList = sFullSensorsList;
                int i = 0;
                do {
                    Sensor sensor = new Sensor();
                    i = sensors_module_get_next_sensor(sensor, i);

                    if (i>=0) {
                        //Log.d(TAG, "found sensor: " + sensor.getName() +
                        //        ", handle=" + sensor.getHandle());
                        fullList.add(sensor);
                        sHandleToSensor.append(sensor.getHandle(), sensor);
                    }
                } while (i>0);

                sPool = new SensorEventPool( sFullSensorsList.size()*2 );
                sSensorThread = new SensorThread();
            }
        }
    }

    /** @hide */
    @Override
    protected List<Sensor> getFullSensorList() {
        return sFullSensorsList;
    }

    private boolean enableSensorLocked(Sensor sensor, int delay) {
        boolean result = false;
        for (ListenerDelegate i : sListeners) {
            if (i.hasSensor(sensor)) {
                String name = sensor.getName();
                int handle = sensor.getHandle();
                result = sensors_enable_sensor(sQueue, name, handle, delay);
                break;
            }
        }
        return result;
    }

    private boolean disableSensorLocked(Sensor sensor) {
        for (ListenerDelegate i : sListeners) {
            if (i.hasSensor(sensor)) {
                // not an error, it's just that this sensor is still in use
                return true;
            }
        }
        String name = sensor.getName();
        int handle = sensor.getHandle();
        return sensors_enable_sensor(sQueue, name, handle, SENSOR_DISABLE);
    }

    /** @hide */
    @Override
    protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
            int delay, Handler handler) {
        boolean result = true;
        synchronized (sListeners) {
            // look for this listener in our list
            ListenerDelegate l = null;
            for (ListenerDelegate i : sListeners) {
                if (i.getListener() == listener) {
                    l = i;
                    break;
                }
            }

            // if we don't find it, add it to the list
            if (l == null) {
                l = new ListenerDelegate(listener, sensor, handler);
                sListeners.add(l);
                // if the list is not empty, start our main thread
                if (!sListeners.isEmpty()) {
                    if (sSensorThread.startLocked()) {
                        if (!enableSensorLocked(sensor, delay)) {
                            // oops. there was an error
                            sListeners.remove(l);
                            result = false;
                        }
                    } else {
                        // there was an error, remove the listener
                        sListeners.remove(l);
                        result = false;
                    }
                } else {
                    // weird, we couldn't add the listener
                    result = false;
                }
            } else if (!l.hasSensor(sensor)) {
                l.addSensor(sensor);
                if (!enableSensorLocked(sensor, delay)) {
                    // oops. there was an error
                    l.removeSensor(sensor);
                    result = false;
                }
            }
        }

        return result;
    }

    /** @hide */
    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        synchronized (sListeners) {
            final int size = sListeners.size();
            for (int i=0 ; i<size ; i++) {
                ListenerDelegate l = sListeners.get(i);
                if (l.getListener() == listener) {
                    if (sensor == null) {
                        sListeners.remove(i);
                        // disable all sensors for this listener
                        for (Sensor s : l.getSensors()) {
                            disableSensorLocked(s);
                        }
                    } else if (l.removeSensor(sensor) == 0) {
                        // if we have no more sensors enabled on this listener,
                        // take it off the list.
                        sListeners.remove(i);
                        disableSensorLocked(sensor);
                    }
                    break;
                }
            }
        }
    }

    private static native void nativeClassInit();

    private static native int sensors_module_init();
    private static native int sensors_module_get_next_sensor(Sensor sensor, int next);

    // Used within this module from outside SensorManager, don't make private
    static native int sensors_create_queue();
    static native void sensors_destroy_queue(int queue);
    static native boolean sensors_enable_sensor(int queue, String name, int sensor, int enable);
    static native int sensors_data_poll(int queue, float[] values, int[] status, long[] timestamp);
}
