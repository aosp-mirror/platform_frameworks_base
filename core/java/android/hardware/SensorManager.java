/*
 * Copyright (C) 2008 The Android Open Source Project
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
import android.os.Binder;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcelable;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.util.Log;
import android.util.SparseArray;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

/**
 * Class that lets you access the device's sensors. Get an instance of this
 * class by calling {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with an argument of {@link android.content.Context#SENSOR_SERVICE}.
 */
public class SensorManager
{
    private static final String TAG = "SensorManager";
    private static final float[] mTempMatrix = new float[16];

    /* NOTE: sensor IDs must be a power of 2 */

    /**
     * A constant describing an orientation sensor.
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_ORIENTATION = 1 << 0;

    /**
     * A constant describing an accelerometer.
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_ACCELEROMETER = 1 << 1;

    /**
     * A constant describing a temperature sensor
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_TEMPERATURE = 1 << 2;

    /**
     * A constant describing a magnetic sensor
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_MAGNETIC_FIELD = 1 << 3;

    /**
     * A constant describing an ambient light sensor
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_LIGHT = 1 << 4;

    /**
     * A constant describing a proximity sensor
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_PROXIMITY = 1 << 5;

    /**
     * A constant describing a Tricorder
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_TRICORDER = 1 << 6;

    /**
     * A constant describing an orientation sensor.
     * See {@link android.hardware.SensorListener SensorListener} for more details.
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_ORIENTATION_RAW = 1 << 7;

    /** A constant that includes all sensors
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_ALL = 0x7F;

    /** Smallest sensor ID 
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_MIN = SENSOR_ORIENTATION;

    /** Largest sensor ID
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int SENSOR_MAX = ((SENSOR_ALL + 1)>>1);


    /** Index of the X value in the array returned by
     * {@link android.hardware.SensorListener#onSensorChanged}
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int DATA_X = 0;
    /** Index of the Y value in the array returned by
     * {@link android.hardware.SensorListener#onSensorChanged}
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int DATA_Y = 1;
    /** Index of the Z value in the array returned by
     * {@link android.hardware.SensorListener#onSensorChanged}
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int DATA_Z = 2;

    /** Offset to the untransformed values in the array returned by
     * {@link android.hardware.SensorListener#onSensorChanged}
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int RAW_DATA_INDEX = 3;

    /** Index of the untransformed X value in the array returned by
     * {@link android.hardware.SensorListener#onSensorChanged}
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int RAW_DATA_X = 3;
    /** Index of the untransformed Y value in the array returned by
     * {@link android.hardware.SensorListener#onSensorChanged}
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int RAW_DATA_Y = 4;
    /** Index of the untransformed Z value in the array returned by
     * {@link android.hardware.SensorListener#onSensorChanged}
     * @deprecated use {@link android.hardware.Sensor Sensor} instead.
     */
    @Deprecated
    public static final int RAW_DATA_Z = 5;


    /** Standard gravity (g) on Earth. This value is equivalent to 1G */
    public static final float STANDARD_GRAVITY = 9.80665f;

    /** values returned by the accelerometer in various locations in the universe.
     * all values are in SI units (m/s^2) */
    public static final float GRAVITY_SUN             = 275.0f;
    public static final float GRAVITY_MERCURY         = 3.70f;
    public static final float GRAVITY_VENUS           = 8.87f;
    public static final float GRAVITY_EARTH           = 9.80665f;
    public static final float GRAVITY_MOON            = 1.6f;
    public static final float GRAVITY_MARS            = 3.71f;
    public static final float GRAVITY_JUPITER         = 23.12f;
    public static final float GRAVITY_SATURN          = 8.96f;
    public static final float GRAVITY_URANUS          = 8.69f;
    public static final float GRAVITY_NEPTUNE         = 11.0f;
    public static final float GRAVITY_PLUTO           = 0.6f;
    public static final float GRAVITY_DEATH_STAR_I    = 0.000000353036145f;
    public static final float GRAVITY_THE_ISLAND      = 4.815162342f;


    /** Maximum magnetic field on Earth's surface */
    public static final float MAGNETIC_FIELD_EARTH_MAX = 60.0f;

    /** Minimum magnetic field on Earth's surface */
    public static final float MAGNETIC_FIELD_EARTH_MIN = 30.0f;


    /** Various luminance values during the day (lux) */
    public static final float LIGHT_SUNLIGHT_MAX = 120000.0f;
    public static final float LIGHT_SUNLIGHT     = 110000.0f;
    public static final float LIGHT_SHADE        = 20000.0f;
    public static final float LIGHT_OVERCAST     = 10000.0f;
    public static final float LIGHT_SUNRISE      = 400.0f;
    public static final float LIGHT_CLOUDY       = 100.0f;
    /** Various luminance values during the night (lux) */
    public static final float LIGHT_FULLMOON     = 0.25f;
    public static final float LIGHT_NO_MOON      = 0.001f;

    /** get sensor data as fast as possible */
    public static final int SENSOR_DELAY_FASTEST = 0;
    /** rate suitable for games */
    public static final int SENSOR_DELAY_GAME = 1;
    /** rate suitable for the user interface  */
    public static final int SENSOR_DELAY_UI = 2;
    /** rate (default) suitable for screen orientation changes */
    public static final int SENSOR_DELAY_NORMAL = 3;


    /** The values returned by this sensor cannot be trusted, calibration
     * is needed or the environment doesn't allow readings */
    public static final int SENSOR_STATUS_UNRELIABLE = 0;

    /** This sensor is reporting data with low accuracy, calibration with the
     * environment is needed */
    public static final int SENSOR_STATUS_ACCURACY_LOW = 1;

    /** This sensor is reporting data with an average level of accuracy,
     * calibration with the environment may improve the readings */
    public static final int SENSOR_STATUS_ACCURACY_MEDIUM = 2;

    /** This sensor is reporting data with maximum accuracy */
    public static final int SENSOR_STATUS_ACCURACY_HIGH = 3;

    /** see {@link #remapCoordinateSystem} */
    public static final int AXIS_X = 1;
    /** see {@link #remapCoordinateSystem} */
    public static final int AXIS_Y = 2;
    /** see {@link #remapCoordinateSystem} */
    public static final int AXIS_Z = 3;
    /** see {@link #remapCoordinateSystem} */
    public static final int AXIS_MINUS_X = AXIS_X | 0x80;
    /** see {@link #remapCoordinateSystem} */
    public static final int AXIS_MINUS_Y = AXIS_Y | 0x80;
    /** see {@link #remapCoordinateSystem} */
    public static final int AXIS_MINUS_Z = AXIS_Z | 0x80;

    /*-----------------------------------------------------------------------*/

    private ISensorService mSensorService;
    Looper mMainLooper;
    @SuppressWarnings("deprecation")
    private HashMap<SensorListener, LegacyListener> mLegacyListenersMap =
        new HashMap<SensorListener, LegacyListener>();

    /*-----------------------------------------------------------------------*/

    private static final int SENSOR_DISABLE = -1;
    private static boolean sSensorModuleInitialized = false;
    private static ArrayList<Sensor> sFullSensorsList = new ArrayList<Sensor>();
    private static SparseArray<List<Sensor>> sSensorListByType = new SparseArray<List<Sensor>>();
    private static IWindowManager sWindowManager;
    private static int sRotation = Surface.ROTATION_0;
    /* The thread and the sensor list are global to the process
     * but the actual thread is spawned on demand */
    private static SensorThread sSensorThread;

    // Used within this module from outside SensorManager, don't make private
    static SparseArray<Sensor> sHandleToSensor = new SparseArray<Sensor>();
    static final ArrayList<ListenerDelegate> sListeners =
        new ArrayList<ListenerDelegate>();

    /*-----------------------------------------------------------------------*/

    static private class SensorThread {

        Thread mThread;
        boolean mSensorsReady;

        SensorThread() {
            // this gets to the sensor module. We can have only one per process.
            sensors_data_init();
        }

        @Override
        protected void finalize() {
            sensors_data_uninit();
        }

        // must be called with sListeners lock
        boolean startLocked(ISensorService service) {
            try {
                if (mThread == null) {
                    Bundle dataChannel = service.getDataChannel();
                    if (dataChannel != null) {
                        mSensorsReady = false;
                        SensorThreadRunnable runnable = new SensorThreadRunnable(dataChannel);
                        Thread thread = new Thread(runnable, SensorThread.class.getName());
                        thread.start();
                        synchronized (runnable) {
                            while (mSensorsReady == false) {
                                runnable.wait();
                            }
                        }
                        mThread = thread;
                    }
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in startLocked: ", e);
            } catch (InterruptedException e) {
            }
            return mThread == null ? false : true;
        }

        private class SensorThreadRunnable implements Runnable {
            private Bundle mDataChannel;
            SensorThreadRunnable(Bundle dataChannel) {
                mDataChannel = dataChannel;
            }

            private boolean open() {
                // NOTE: this cannot synchronize on sListeners, since
                // it's held in the main thread at least until we
                // return from here.

                // this thread is guaranteed to be unique
                Parcelable[] pfds = mDataChannel.getParcelableArray("fds");
                FileDescriptor[] fds;
                if (pfds != null) {
                    int length = pfds.length;
                    fds = new FileDescriptor[length];
                    for (int i = 0; i < length; i++) {
                        ParcelFileDescriptor pfd = (ParcelFileDescriptor)pfds[i];
                        fds[i] = pfd.getFileDescriptor();
                    }
                } else {
                    fds = null;
                }
                int[] ints = mDataChannel.getIntArray("ints");
                sensors_data_open(fds, ints);
                if (pfds != null) {
                    try {
                        // close our copies of the file descriptors,
                        // since we are just passing these to the JNI code and not using them here.
                        for (int i = pfds.length - 1; i >= 0; i--) {
                            ParcelFileDescriptor pfd = (ParcelFileDescriptor)pfds[i];
                            pfd.close();
                        }
                    } catch (IOException e) {
                        // *shrug*
                        Log.e(TAG, "IOException: ", e);
                    }
                }
                mDataChannel = null;
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
                    final int sensor = sensors_data_poll(values, status, timestamp);

                    int accuracy = status[0];
                    synchronized (sListeners) {
                        if (sensor == -1 || sListeners.isEmpty()) {
                            if (sensor == -1) {
                                // we lost the connection to the event stream. this happens
                                // when the last listener is removed.
                                Log.d(TAG, "_sensors_data_poll() failed, we bail out.");
                            }

                            // we have no more listeners or polling failed, terminate the thread
                            sensors_data_close();
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

    private class ListenerDelegate extends Binder {
        final SensorEventListener mSensorEventListener;
        private final ArrayList<Sensor> mSensorList = new ArrayList<Sensor>();
        private final Handler mHandler;
        private SensorEvent mValuesPool;
        public int mSensors;

        ListenerDelegate(SensorEventListener listener, Sensor sensor, Handler handler) {
            mSensorEventListener = listener;
            Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;
            // currently we create one Handler instance per listener, but we could
            // have one per looper (we'd need to pass the ListenerDelegate
            // instance to handleMessage and keep track of them separately).
            mHandler = new Handler(looper) {
                @Override
                public void handleMessage(Message msg) {
                    SensorEvent t = (SensorEvent)msg.obj;
                    if (t.accuracy >= 0) {
                        mSensorEventListener.onAccuracyChanged(t.sensor, t.accuracy);
                    }
                    mSensorEventListener.onSensorChanged(t);
                    returnToPool(t);
                }
            };
            addSensor(sensor);
        }

        protected SensorEvent createSensorEvent() {
            // maximal size for all legacy events is 3
            return new SensorEvent(3);
        }

        protected SensorEvent getFromPool() {
            SensorEvent t = null;
            synchronized (this) {
                // remove the array from the pool
                t = mValuesPool;
                mValuesPool = null;
            }
            if (t == null) {
                // the pool was empty, we need a new one
                t = createSensorEvent();
            }
            return t;
        }

        protected void returnToPool(SensorEvent t) {
            synchronized (this) {
                // put back the array into the pool
                if (mValuesPool == null) {
                    mValuesPool = t;
                }
            }
        }

        Object getListener() {
            return mSensorEventListener;
        }

        int addSensor(Sensor sensor) {
            mSensors |= 1<<sensor.getHandle();
            mSensorList.add(sensor);
            return mSensors;
        }
        int removeSensor(Sensor sensor) {
            mSensors &= ~(1<<sensor.getHandle());
            mSensorList.remove(sensor);
            return mSensors;
        }
        boolean hasSensor(Sensor sensor) {
            return ((mSensors & (1<<sensor.getHandle())) != 0);
        }
        List<Sensor> getSensors() {
            return mSensorList;
        }

        void onSensorChangedLocked(Sensor sensor, float[] values, long[] timestamp, int accuracy) {
            SensorEvent t = getFromPool();
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
            mHandler.sendMessage(msg);
        }
    }

    /**
     * {@hide}
     */
    public SensorManager(Looper mainLooper) {
        mSensorService = ISensorService.Stub.asInterface(
                ServiceManager.getService(Context.SENSOR_SERVICE));
        mMainLooper = mainLooper;


        synchronized(sListeners) {
            if (!sSensorModuleInitialized) {
                sSensorModuleInitialized = true;

                nativeClassInit();

                sWindowManager = IWindowManager.Stub.asInterface(
                        ServiceManager.getService("window"));
                if (sWindowManager != null) {
                    // if it's null we're running in the system process
                    // which won't get the rotated values
                    try {
                        sRotation = sWindowManager.watchRotation(
                            new IRotationWatcher.Stub() {
                                public void onRotationChanged(int rotation) {
                                    SensorManager.this.onRotationChanged(rotation);
                                }
                            }
                        );
                    } catch (RemoteException e) {
                    }
                }

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
                        sensor.setLegacyType(getLegacySensorType(sensor.getType()));
                        fullList.add(sensor);
                        sHandleToSensor.append(sensor.getHandle(), sensor);
                    }
                } while (i>0);

                sSensorThread = new SensorThread();
            }
        }
    }

    private int getLegacySensorType(int type) {
        switch (type) {
            case Sensor.TYPE_ACCELEROMETER:
                return SENSOR_ACCELEROMETER;
            case Sensor.TYPE_MAGNETIC_FIELD:
                return SENSOR_MAGNETIC_FIELD;
            case Sensor.TYPE_ORIENTATION:
                return SENSOR_ORIENTATION_RAW;
            case Sensor.TYPE_TEMPERATURE:
                return SENSOR_TEMPERATURE;
        }
        return 0;
    }

    /** @return available sensors.
     * @deprecated This method is deprecated, use
     * {@link SensorManager#getSensorList(int)} instead
     */
    @Deprecated
    public int getSensors() {
        int result = 0;
        final ArrayList<Sensor> fullList = sFullSensorsList;
        for (Sensor i : fullList) {
            switch (i.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    result |= SensorManager.SENSOR_ACCELEROMETER;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    result |= SensorManager.SENSOR_MAGNETIC_FIELD;
                    break;
                case Sensor.TYPE_ORIENTATION:
                    result |= SensorManager.SENSOR_ORIENTATION |
                                    SensorManager.SENSOR_ORIENTATION_RAW;
                    break;
            }
        }
        return result;
    }

    /**
     * Use this method to get the list of available sensors of a certain
     * type. Make multiple calls to get sensors of different types or use
     * {@link android.hardware.Sensor#TYPE_ALL Sensor.TYPE_ALL} to get all
     * the sensors.
     *
     * @param type of sensors requested
     * @return a list of sensors matching the asked type.
     */
    public List<Sensor> getSensorList(int type) {
        // cache the returned lists the first time
        List<Sensor> list;
        final ArrayList<Sensor> fullList = sFullSensorsList;
        synchronized(fullList) {
            list = sSensorListByType.get(type);
            if (list == null) {
                if (type == Sensor.TYPE_ALL) {
                    list = fullList;
                } else {
                    list = new ArrayList<Sensor>();
                    for (Sensor i : fullList) {
                        if (i.getType() == type)
                            list.add(i);
                    }
                }
                list = Collections.unmodifiableList(list);
                sSensorListByType.append(type, list);
            }
        }
        return list;
    }

    /**
     * Use this method to get the default sensor for a given type. Note that
     * the returned sensor could be a composite sensor, and its data could be
     * averaged or filtered. If you need to access the raw sensors use
     * {@link SensorManager#getSensorList(int) getSensorList}.
     *
     *
     * @param type of sensors requested
     * @return the default sensors matching the asked type.
     */
    public Sensor getDefaultSensor(int type) {
        // TODO: need to be smarter, for now, just return the 1st sensor
        List<Sensor> l = getSensorList(type);
        return l.isEmpty() ? null : l.get(0);
    }


    /**
     * Registers a listener for given sensors.
     * @deprecated This method is deprecated, use
     * {@link SensorManager#registerListener(SensorEventListener, Sensor, int)}
     * instead.
     *
     * @param listener sensor listener object
     * @param sensors a bit masks of the sensors to register to
     *
     * @return true if the sensor is supported and successfully enabled
     */
    @Deprecated
    public boolean registerListener(SensorListener listener, int sensors) {
        return registerListener(listener, sensors, SENSOR_DELAY_NORMAL);
    }

    /**
     * Registers a SensorListener for given sensors.
     * @deprecated This method is deprecated, use
     * {@link SensorManager#registerListener(SensorEventListener, Sensor, int)}
     * instead.
     *
     * @param listener sensor listener object
     * @param sensors a bit masks of the sensors to register to
     * @param rate rate of events. This is only a hint to the system. events
     * may be received faster or slower than the specified rate. Usually events
     * are received faster. The value must be one of {@link #SENSOR_DELAY_NORMAL},
     * {@link #SENSOR_DELAY_UI}, {@link #SENSOR_DELAY_GAME}, or {@link #SENSOR_DELAY_FASTEST}.
     *
     * @return true if the sensor is supported and successfully enabled
     */
    @Deprecated
    public boolean registerListener(SensorListener listener, int sensors, int rate) {
        if (listener == null) {
            return false;
        }
        boolean result = false;
        result = registerLegacyListener(SENSOR_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER,
                listener, sensors, rate) || result;
        result = registerLegacyListener(SENSOR_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD,
                listener, sensors, rate) || result;
        result = registerLegacyListener(SENSOR_ORIENTATION_RAW, Sensor.TYPE_ORIENTATION,
                listener, sensors, rate) || result;
        result = registerLegacyListener(SENSOR_ORIENTATION, Sensor.TYPE_ORIENTATION,
                listener, sensors, rate) || result;
        result = registerLegacyListener(SENSOR_TEMPERATURE, Sensor.TYPE_TEMPERATURE,
                listener, sensors, rate) || result;
        return result;
    }

    @SuppressWarnings("deprecation")
    private boolean registerLegacyListener(int legacyType, int type,
            SensorListener listener, int sensors, int rate)
    {
        if (listener == null) {
            return false;
        }
        boolean result = false;
        // Are we activating this legacy sensor?
        if ((sensors & legacyType) != 0) {
            // if so, find a suitable Sensor
            Sensor sensor = getDefaultSensor(type);
            if (sensor != null) {
                // If we don't already have one, create a LegacyListener
                // to wrap this listener and process the events as
                // they are expected by legacy apps.
                LegacyListener legacyListener = null;
                synchronized (mLegacyListenersMap) {
                    legacyListener = mLegacyListenersMap.get(listener);
                    if (legacyListener == null) {
                        // we didn't find a LegacyListener for this client,
                        // create one, and put it in our list.
                        legacyListener = new LegacyListener(listener);
                        mLegacyListenersMap.put(listener, legacyListener);
                    }
                }
                // register this legacy sensor with this legacy listener
                legacyListener.registerSensor(legacyType);
                // and finally, register the legacy listener with the new apis
                result = registerListener(legacyListener, sensor, rate);
            }
        }
        return result;
    }

    /**
     * Unregisters a listener for the sensors with which it is registered.
     * @deprecated This method is deprecated, use
     * {@link SensorManager#unregisterListener(SensorEventListener, Sensor)}
     * instead.
     *
     * @param listener a SensorListener object
     * @param sensors a bit masks of the sensors to unregister from
     */
    @Deprecated
    public void unregisterListener(SensorListener listener, int sensors) {
        unregisterLegacyListener(SENSOR_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER,
                listener, sensors);
        unregisterLegacyListener(SENSOR_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD,
                listener, sensors);
        unregisterLegacyListener(SENSOR_ORIENTATION_RAW, Sensor.TYPE_ORIENTATION,
                listener, sensors);
        unregisterLegacyListener(SENSOR_ORIENTATION, Sensor.TYPE_ORIENTATION,
                listener, sensors);
        unregisterLegacyListener(SENSOR_TEMPERATURE, Sensor.TYPE_TEMPERATURE,
                listener, sensors);
    }

    @SuppressWarnings("deprecation")
    private void unregisterLegacyListener(int legacyType, int type,
            SensorListener listener, int sensors)
    {
        if (listener == null) {
            return;
        }
        // do we know about this listener?
        LegacyListener legacyListener = null;
        synchronized (mLegacyListenersMap) {
            legacyListener = mLegacyListenersMap.get(listener);
        }
        if (legacyListener != null) {
            // Are we deactivating this legacy sensor?
            if ((sensors & legacyType) != 0) {
                // if so, find the corresponding Sensor
                Sensor sensor = getDefaultSensor(type);
                if (sensor != null) {
                    // unregister this legacy sensor and if we don't
                    // need the corresponding Sensor, unregister it too
                    if (legacyListener.unregisterSensor(legacyType)) {
                        // corresponding sensor not needed, unregister
                        unregisterListener(legacyListener, sensor);
                        // finally check if we still need the legacyListener
                        // in our mapping, if not, get rid of it too.
                        synchronized(sListeners) {
                            boolean found = false;
                            for (ListenerDelegate i : sListeners) {
                                if (i.getListener() == legacyListener) {
                                    found = true;
                                    break;
                                }
                            }
                            if (!found) {
                                synchronized (mLegacyListenersMap) {
                                    mLegacyListenersMap.remove(listener);
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Unregisters a listener for all sensors.
     * @deprecated This method is deprecated, use
     * {@link SensorManager#unregisterListener(SensorEventListener)}
     * instead.
     *
     * @param listener a SensorListener object
     */
    @Deprecated
    public void unregisterListener(SensorListener listener) {
        unregisterListener(listener, SENSOR_ALL | SENSOR_ORIENTATION_RAW);
    }

    /**
     * Unregisters a listener for the sensors with which it is registered.
     *
     * @param listener a SensorEventListener object
     * @param sensor the sensor to unregister from
     *
     */
    public void unregisterListener(SensorEventListener listener, Sensor sensor) {
        unregisterListener((Object)listener, sensor);
    }

    /**
     * Unregisters a listener for all sensors.
     *
     * @param listener a SensorListener object
     *
     */
    public void unregisterListener(SensorEventListener listener) {
        unregisterListener((Object)listener);
    }


    /**
     * Registers a {@link android.hardware.SensorEventListener SensorEventListener}
     * for the given sensor.
     *
     * @param listener A {@link android.hardware.SensorEventListener SensorEventListener} object.
     * @param sensor The {@link android.hardware.Sensor Sensor} to register to.
     * @param rate The rate {@link android.hardware.SensorEvent sensor events} are delivered at.
     * This is only a hint to the system. Events may be received faster or
     * slower than the specified rate. Usually events are received faster. The value must be
     * one of {@link #SENSOR_DELAY_NORMAL}, {@link #SENSOR_DELAY_UI}, {@link #SENSOR_DELAY_GAME},
     * or {@link #SENSOR_DELAY_FASTEST}.
     *
     * @return true if the sensor is supported and successfully enabled.
     *
     */
    public boolean registerListener(SensorEventListener listener, Sensor sensor, int rate) {
        return registerListener(listener, sensor, rate, null);
    }

    /**
     * Registers a {@link android.hardware.SensorEventListener SensorEventListener}
     * for the given sensor.
     *
     * @param listener A {@link android.hardware.SensorEventListener SensorEventListener} object.
     * @param sensor The {@link android.hardware.Sensor Sensor} to register to.
     * @param rate The rate {@link android.hardware.SensorEvent sensor events} are delivered at.
     * This is only a hint to the system. Events may be received faster or
     * slower than the specified rate. Usually events are received faster. The value must be one
     * of {@link #SENSOR_DELAY_NORMAL}, {@link #SENSOR_DELAY_UI}, {@link #SENSOR_DELAY_GAME}, or
     * {@link #SENSOR_DELAY_FASTEST}.
     * @param handler The {@link android.os.Handler Handler} the
     * {@link android.hardware.SensorEvent sensor events} will be delivered to.
     *
     * @return true if the sensor is supported and successfully enabled.
     *
     */
    public boolean registerListener(SensorEventListener listener, Sensor sensor, int rate,
            Handler handler) {
        if (listener == null || sensor == null) {
            return false;
        }
        boolean result;
        int delay = -1;
        switch (rate) {
            case SENSOR_DELAY_FASTEST:
                delay = 0;
                break;
            case SENSOR_DELAY_GAME:
                delay = 20;
                break;
            case SENSOR_DELAY_UI:
                delay = 60;
                break;
            case SENSOR_DELAY_NORMAL:
                delay = 200;
                break;
            default:
                return false;
        }

        try {
            synchronized (sListeners) {
                ListenerDelegate l = null;
                for (ListenerDelegate i : sListeners) {
                    if (i.getListener() == listener) {
                        l = i;
                        break;
                    }
                }

                String name = sensor.getName();
                int handle = sensor.getHandle();
                if (l == null) {
                    result = false;
                    l = new ListenerDelegate(listener, sensor, handler);
                    sListeners.add(l);
                    if (!sListeners.isEmpty()) {
                        result = sSensorThread.startLocked(mSensorService);
                        if (result) {
                            result = mSensorService.enableSensor(l, name, handle, delay);
                            if (!result) {
                                // there was an error, remove the listeners
                                sListeners.remove(l);
                            }
                        }
                    }
                } else {
                    result = mSensorService.enableSensor(l, name, handle, delay);
                    if (result) {
                        l.addSensor(sensor);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in registerListener: ", e);
            result = false;
        }
        return result;
    }

    private void unregisterListener(Object listener, Sensor sensor) {
        if (listener == null || sensor == null) {
            return;
        }
        try {
            synchronized (sListeners) {
                final int size = sListeners.size();
                for (int i=0 ; i<size ; i++) {
                    ListenerDelegate l = sListeners.get(i);
                    if (l.getListener() == listener) {
                        // disable these sensors
                        String name = sensor.getName();
                        int handle = sensor.getHandle();
                        mSensorService.enableSensor(l, name, handle, SENSOR_DISABLE);
                        // if we have no more sensors enabled on this listener,
                        // take it off the list.
                        if (l.removeSensor(sensor) == 0) {
                            sListeners.remove(i);
                        }
                        break;
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in unregisterListener: ", e);
        }
    }

    private void unregisterListener(Object listener) {
        if (listener == null) {
            return;
        }
        try {
            synchronized (sListeners) {
                final int size = sListeners.size();
                for (int i=0 ; i<size ; i++) {
                    ListenerDelegate l = sListeners.get(i);
                    if (l.getListener() == listener) {
                        // disable all sensors for this listener
                        for (Sensor sensor : l.getSensors()) {
                            String name = sensor.getName();
                            int handle = sensor.getHandle();
                            mSensorService.enableSensor(l, name, handle, SENSOR_DISABLE);
                        }
                        sListeners.remove(i);
                        break;
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in unregisterListener: ", e);
        }
    }

    /**
     * Computes the inclination matrix <b>I</b> as well as the rotation
     * matrix <b>R</b> transforming a vector from the
     * device coordinate system to the world's coordinate system which is
     * defined as a direct orthonormal basis, where:
     * 
     * <li>X is defined as the vector product <b>Y.Z</b> (It is tangential to
     * the ground at the device's current location and roughly points East).</li>
     * <li>Y is tangential to the ground at the device's current location and
     * points towards the magnetic North Pole.</li>
     * <li>Z points towards the sky and is perpendicular to the ground.</li>
     * <p>
     * <hr>
     * <p>By definition:
     * <p>[0 0 g] = <b>R</b> * <b>gravity</b> (g = magnitude of gravity)
     * <p>[0 m 0] = <b>I</b> * <b>R</b> * <b>geomagnetic</b>
     * (m = magnitude of geomagnetic field)
     * <p><b>R</b> is the identity matrix when the device is aligned with the
     * world's coordinate system, that is, when the device's X axis points
     * toward East, the Y axis points to the North Pole and the device is facing
     * the sky.
     *
     * <p><b>I</b> is a rotation matrix transforming the geomagnetic
     * vector into the same coordinate space as gravity (the world's coordinate
     * space). <b>I</b> is a simple rotation around the X axis.
     * The inclination angle in radians can be computed with
     * {@link #getInclination}.
     * <hr>
     * 
     * <p> Each matrix is returned either as a 3x3 or 4x4 row-major matrix
     * depending on the length of the passed array:
     * <p><u>If the array length is 16:</u>
     * <pre>
     *   /  M[ 0]   M[ 1]   M[ 2]   M[ 3]  \
     *   |  M[ 4]   M[ 5]   M[ 6]   M[ 7]  |
     *   |  M[ 8]   M[ 9]   M[10]   M[11]  |
     *   \  M[12]   M[13]   M[14]   M[15]  /
     *</pre>
     * This matrix is ready to be used by OpenGL ES's 
     * {@link javax.microedition.khronos.opengles.GL10#glLoadMatrixf(float[], int) 
     * glLoadMatrixf(float[], int)}. 
     * <p>Note that because OpenGL matrices are column-major matrices you must
     * transpose the matrix before using it. However, since the matrix is a 
     * rotation matrix, its transpose is also its inverse, conveniently, it is
     * often the inverse of the rotation that is needed for rendering; it can
     * therefore be used with OpenGL ES directly.
     * <p>
     * Also note that the returned matrices always have this form:
     * <pre>
     *   /  M[ 0]   M[ 1]   M[ 2]   0  \
     *   |  M[ 4]   M[ 5]   M[ 6]   0  |
     *   |  M[ 8]   M[ 9]   M[10]   0  |
     *   \      0       0       0   1  /
     *</pre>
     * <p><u>If the array length is 9:</u>
     * <pre>
     *   /  M[ 0]   M[ 1]   M[ 2]  \
     *   |  M[ 3]   M[ 4]   M[ 5]  |
     *   \  M[ 6]   M[ 7]   M[ 8]  /
     *</pre>
     *
     * <hr>
     * <p>The inverse of each matrix can be computed easily by taking its
     * transpose.
     *
     * <p>The matrices returned by this function are meaningful only when the
     * device is not free-falling and it is not close to the magnetic north.
     * If the device is accelerating, or placed into a strong magnetic field,
     * the returned matrices may be inaccurate.
     *
     * @param R is an array of 9 floats holding the rotation matrix <b>R</b>
     * when this function returns. R can be null.<p>
     * @param I is an array of 9 floats holding the rotation matrix <b>I</b>
     * when this function returns. I can be null.<p>
     * @param gravity is an array of 3 floats containing the gravity vector
     * expressed in the device's coordinate. You can simply use the
     * {@link android.hardware.SensorEvent#values values}
     * returned by a {@link android.hardware.SensorEvent SensorEvent} of a
     * {@link android.hardware.Sensor Sensor} of type
     * {@link android.hardware.Sensor#TYPE_ACCELEROMETER TYPE_ACCELEROMETER}.<p>
     * @param geomagnetic is an array of 3 floats containing the geomagnetic
     * vector expressed in the device's coordinate. You can simply use the
     * {@link android.hardware.SensorEvent#values values}
     * returned by a {@link android.hardware.SensorEvent SensorEvent} of a
     * {@link android.hardware.Sensor Sensor} of type
     * {@link android.hardware.Sensor#TYPE_MAGNETIC_FIELD TYPE_MAGNETIC_FIELD}.
     * @return
     *   true on success<p>
     *   false on failure (for instance, if the device is in free fall).
     *   On failure the output matrices are not modified.
     */

    public static boolean getRotationMatrix(float[] R, float[] I,
            float[] gravity, float[] geomagnetic) {
        // TODO: move this to native code for efficiency
        float Ax = gravity[0];
        float Ay = gravity[1];
        float Az = gravity[2];
        final float Ex = geomagnetic[0];
        final float Ey = geomagnetic[1];
        final float Ez = geomagnetic[2];
        float Hx = Ey*Az - Ez*Ay;
        float Hy = Ez*Ax - Ex*Az;
        float Hz = Ex*Ay - Ey*Ax;
        final float normH = (float)Math.sqrt(Hx*Hx + Hy*Hy + Hz*Hz);
        if (normH < 0.1f) {
            // device is close to free fall (or in space?), or close to
            // magnetic north pole. Typical values are  > 100.
            return false;
        }
        final float invH = 1.0f / normH;
        Hx *= invH;
        Hy *= invH;
        Hz *= invH;
        final float invA = 1.0f / (float)Math.sqrt(Ax*Ax + Ay*Ay + Az*Az);
        Ax *= invA;
        Ay *= invA;
        Az *= invA;
        final float Mx = Ay*Hz - Az*Hy;
        final float My = Az*Hx - Ax*Hz;
        final float Mz = Ax*Hy - Ay*Hx;
        if (R != null) {
            if (R.length == 9) {
                R[0] = Hx;     R[1] = Hy;     R[2] = Hz;
                R[3] = Mx;     R[4] = My;     R[5] = Mz;
                R[6] = Ax;     R[7] = Ay;     R[8] = Az;
            } else if (R.length == 16) {
                R[0]  = Hx;    R[1]  = Hy;    R[2]  = Hz;   R[3]  = 0;
                R[4]  = Mx;    R[5]  = My;    R[6]  = Mz;   R[7]  = 0;
                R[8]  = Ax;    R[9]  = Ay;    R[10] = Az;   R[11] = 0;
                R[12] = 0;     R[13] = 0;     R[14] = 0;    R[15] = 1;
            }
        }
        if (I != null) {
            // compute the inclination matrix by projecting the geomagnetic
            // vector onto the Z (gravity) and X (horizontal component
            // of geomagnetic vector) axes.
            final float invE = 1.0f / (float)Math.sqrt(Ex*Ex + Ey*Ey + Ez*Ez);
            final float c = (Ex*Mx + Ey*My + Ez*Mz) * invE;
            final float s = (Ex*Ax + Ey*Ay + Ez*Az) * invE;
            if (I.length == 9) {
                I[0] = 1;     I[1] = 0;     I[2] = 0;
                I[3] = 0;     I[4] = c;     I[5] = s;
                I[6] = 0;     I[7] =-s;     I[8] = c;
            } else if (I.length == 16) {
                I[0] = 1;     I[1] = 0;     I[2] = 0;
                I[4] = 0;     I[5] = c;     I[6] = s;
                I[8] = 0;     I[9] =-s;     I[10]= c;
                I[3] = I[7] = I[11] = I[12] = I[13] = I[14] = 0;
                I[15] = 1;
            }
        }
        return true;
    }

    /**
     * Computes the geomagnetic inclination angle in radians from the
     * inclination matrix <b>I</b> returned by {@link #getRotationMatrix}.
     * @param I inclination matrix see {@link #getRotationMatrix}.
     * @return The geomagnetic inclination angle in radians.
     */
    public static float getInclination(float[] I) {
        if (I.length == 9) {
            return (float)Math.atan2(I[5], I[4]);
        } else {
            return (float)Math.atan2(I[6], I[5]);            
        }
    }

    /**
     * Rotates the supplied rotation matrix so it is expressed in a
     * different coordinate system. This is typically used when an application
     * needs to compute the three orientation angles of the device (see
     * {@link #getOrientation}) in a different coordinate system.
     * 
     * <p>When the rotation matrix is used for drawing (for instance with 
     * OpenGL ES), it usually <b>doesn't need</b> to be transformed by this 
     * function, unless the screen is physically rotated, in which case you
     * can use {@link android.view.Display#getRotation() Display.getRotation()}
     * to retrieve the current rotation of the screen.  Note that because the
     * user is generally free to rotate their screen, you often should
     * consider the rotation in deciding the parameters to use here.
     *
     * <p><u>Examples:</u><p>
     *
     * <li>Using the camera (Y axis along the camera's axis) for an augmented 
     * reality application where the rotation angles are needed: </li><p>
     *
     * <code>remapCoordinateSystem(inR, AXIS_X, AXIS_Z, outR);</code><p>
     *
     * <li>Using the device as a mechanical compass when rotation is
     * {@link android.view.Surface#ROTATION_90 Surface.ROTATION_90}:</li><p>
     *
     * <code>remapCoordinateSystem(inR, AXIS_Y, AXIS_MINUS_X, outR);</code><p>
     *
     * Beware of the above example. This call is needed only to account for
     * a rotation from its natural orientation when calculating the
     * rotation angles (see {@link #getOrientation}).
     * If the rotation matrix is also used for rendering, it may not need to 
     * be transformed, for instance if your {@link android.app.Activity
     * Activity} is running in landscape mode.
     *
     * <p>Since the resulting coordinate system is orthonormal, only two axes
     * need to be specified.
     *
     * @param inR the rotation matrix to be transformed. Usually it is the
     * matrix returned by {@link #getRotationMatrix}.
     * @param X defines on which world axis and direction the X axis of the
     *        device is mapped.
     * @param Y defines on which world axis and direction the Y axis of the
     *        device is mapped.
     * @param outR the transformed rotation matrix. inR and outR can be the same
     *        array, but it is not recommended for performance reason.
     * @return true on success. false if the input parameters are incorrect, for
     * instance if X and Y define the same axis. Or if inR and outR don't have 
     * the same length.
     */

    public static boolean remapCoordinateSystem(float[] inR, int X, int Y,
            float[] outR)
    {
        if (inR == outR) {
            final float[] temp = mTempMatrix;
            synchronized(temp) {
                // we don't expect to have a lot of contention
                if (remapCoordinateSystemImpl(inR, X, Y, temp)) {
                    final int size = outR.length;
                    for (int i=0 ; i<size ; i++)
                        outR[i] = temp[i];
                    return true;
                }
            }
        }
        return remapCoordinateSystemImpl(inR, X, Y, outR);
    }

    private static boolean remapCoordinateSystemImpl(float[] inR, int X, int Y,
            float[] outR)
    {
        /*
         * X and Y define a rotation matrix 'r':
         *
         *  (X==1)?((X&0x80)?-1:1):0    (X==2)?((X&0x80)?-1:1):0    (X==3)?((X&0x80)?-1:1):0
         *  (Y==1)?((Y&0x80)?-1:1):0    (Y==2)?((Y&0x80)?-1:1):0    (Y==3)?((X&0x80)?-1:1):0
         *                              r[0] ^ r[1]
         *
         * where the 3rd line is the vector product of the first 2 lines
         *
         */

        final int length = outR.length;
        if (inR.length != length)
            return false;   // invalid parameter
        if ((X & 0x7C)!=0 || (Y & 0x7C)!=0)
            return false;   // invalid parameter
        if (((X & 0x3)==0) || ((Y & 0x3)==0))
            return false;   // no axis specified
        if ((X & 0x3) == (Y & 0x3))
            return false;   // same axis specified

        // Z is "the other" axis, its sign is either +/- sign(X)*sign(Y)
        // this can be calculated by exclusive-or'ing X and Y; except for
        // the sign inversion (+/-) which is calculated below.
        int Z = X ^ Y;

        // extract the axis (remove the sign), offset in the range 0 to 2.
        final int x = (X & 0x3)-1;
        final int y = (Y & 0x3)-1;
        final int z = (Z & 0x3)-1;

        // compute the sign of Z (whether it needs to be inverted)
        final int axis_y = (z+1)%3;
        final int axis_z = (z+2)%3;
        if (((x^axis_y)|(y^axis_z)) != 0)
            Z ^= 0x80;

        final boolean sx = (X>=0x80);
        final boolean sy = (Y>=0x80);
        final boolean sz = (Z>=0x80);

        // Perform R * r, in avoiding actual muls and adds.
        final int rowLength = ((length==16)?4:3);
        for (int j=0 ; j<3 ; j++) {
            final int offset = j*rowLength;
            for (int i=0 ; i<3 ; i++) {
                if (x==i)   outR[offset+i] = sx ? -inR[offset+0] : inR[offset+0];
                if (y==i)   outR[offset+i] = sy ? -inR[offset+1] : inR[offset+1];
                if (z==i)   outR[offset+i] = sz ? -inR[offset+2] : inR[offset+2];
            }
        }
        if (length == 16) {
            outR[3] = outR[7] = outR[11] = outR[12] = outR[13] = outR[14] = 0;
            outR[15] = 1;
        }
        return true;
    }

    /**
     * Computes the device's orientation based on the rotation matrix.
     * <p> When it returns, the array values is filled with the result:
     * <li>values[0]: <i>azimuth</i>, rotation around the Z axis.</li>
     * <li>values[1]: <i>pitch</i>, rotation around the X axis.</li>
     * <li>values[2]: <i>roll</i>, rotation around the Y axis.</li>
     * <p>
     * All three angles above are in <b>radians</b> and <b>positive</b> in the
     * <b>counter-clockwise</b> direction.
     *
     * @param R rotation matrix see {@link #getRotationMatrix}.
     * @param values an array of 3 floats to hold the result.
     * @return The array values passed as argument.
     */
    public static float[] getOrientation(float[] R, float values[]) {
        /*
         * 4x4 (length=16) case:
         *   /  R[ 0]   R[ 1]   R[ 2]   0  \
         *   |  R[ 4]   R[ 5]   R[ 6]   0  |
         *   |  R[ 8]   R[ 9]   R[10]   0  |
         *   \      0       0       0   1  /
         *   
         * 3x3 (length=9) case:
         *   /  R[ 0]   R[ 1]   R[ 2]  \
         *   |  R[ 3]   R[ 4]   R[ 5]  |
         *   \  R[ 6]   R[ 7]   R[ 8]  /
         * 
         */
        if (R.length == 9) {
            values[0] = (float)Math.atan2(R[1], R[4]);
            values[1] = (float)Math.asin(-R[7]);
            values[2] = (float)Math.atan2(-R[6], R[8]);
        } else {
            values[0] = (float)Math.atan2(R[1], R[5]);
            values[1] = (float)Math.asin(-R[9]);
            values[2] = (float)Math.atan2(-R[8], R[10]);
        }
        return values;
    }


    /**
     * {@hide}
     */
    public void onRotationChanged(int rotation) {
        synchronized(sListeners) {
            sRotation  = rotation;
        }
    }

    static int getRotation() {
        synchronized(sListeners) {
            return sRotation;
        }
    }

    private class LegacyListener implements SensorEventListener {
        private float mValues[] = new float[6];
        @SuppressWarnings("deprecation")
        private SensorListener mTarget;
        private int mSensors;
        private final LmsFilter mYawfilter = new LmsFilter();

        @SuppressWarnings("deprecation")
        LegacyListener(SensorListener target) {
            mTarget = target;
            mSensors = 0;
        }

        void registerSensor(int legacyType) {
            mSensors |= legacyType;
        }

        boolean unregisterSensor(int legacyType) {
            mSensors &= ~legacyType;
            int mask = SENSOR_ORIENTATION|SENSOR_ORIENTATION_RAW;
            if (((legacyType&mask)!=0) && ((mSensors&mask)!=0)) {
                return false;
            }
            return true;
        }

        @SuppressWarnings("deprecation")
        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            try {
                mTarget.onAccuracyChanged(sensor.getLegacyType(), accuracy);
            } catch (AbstractMethodError e) {
                // old app that doesn't implement this method
                // just ignore it.
            }
        }

        @SuppressWarnings("deprecation")
        public void onSensorChanged(SensorEvent event) {
            final float v[] = mValues;
            v[0] = event.values[0];
            v[1] = event.values[1];
            v[2] = event.values[2];
            int legacyType = event.sensor.getLegacyType();
            mapSensorDataToWindow(legacyType, v, SensorManager.getRotation());
            if (event.sensor.getType() == Sensor.TYPE_ORIENTATION) {
                if ((mSensors & SENSOR_ORIENTATION_RAW)!=0) {
                    mTarget.onSensorChanged(SENSOR_ORIENTATION_RAW, v);
                }
                if ((mSensors & SENSOR_ORIENTATION)!=0) {
                    v[0] = mYawfilter.filter(event.timestamp, v[0]);
                    mTarget.onSensorChanged(SENSOR_ORIENTATION, v);
                }
            } else {
                mTarget.onSensorChanged(legacyType, v);
            }
        }

        /*
         * Helper function to convert the specified sensor's data to the windows's
         * coordinate space from the device's coordinate space.
         *
         * output: 3,4,5: values in the old API format
         *         0,1,2: transformed values in the old API format
         *
         */
        private void mapSensorDataToWindow(int sensor,
                float[] values, int orientation) {
            float x = values[0];
            float y = values[1];
            float z = values[2];

            switch (sensor) {
                case SensorManager.SENSOR_ORIENTATION:
                case SensorManager.SENSOR_ORIENTATION_RAW:
                    z = -z;
                    break;
                case SensorManager.SENSOR_ACCELEROMETER:
                    x = -x;
                    y = -y;
                    z = -z;
                    break;
                case SensorManager.SENSOR_MAGNETIC_FIELD:
                    x = -x;
                    y = -y;
                    break;
            }
            values[0] = x;
            values[1] = y;
            values[2] = z;
            values[3] = x;
            values[4] = y;
            values[5] = z;

            if ((orientation & Surface.ROTATION_90) != 0) {
                // handles 90 and 270 rotation
                switch (sensor) {
                    case SENSOR_ACCELEROMETER:
                    case SENSOR_MAGNETIC_FIELD:
                        values[0] =-y;
                        values[1] = x;
                        values[2] = z;
                        break;
                    case SENSOR_ORIENTATION:
                    case SENSOR_ORIENTATION_RAW:
                        values[0] = x + ((x < 270) ? 90 : -270);
                        values[1] = z;
                        values[2] = y;
                        break;
                }
            }
            if ((orientation & Surface.ROTATION_180) != 0) {
                x = values[0];
                y = values[1];
                z = values[2];
                // handles 180 (flip) and 270 (flip + 90) rotation
                switch (sensor) {
                    case SENSOR_ACCELEROMETER:
                    case SENSOR_MAGNETIC_FIELD:
                        values[0] =-x;
                        values[1] =-y;
                        values[2] = z;
                        break;
                    case SENSOR_ORIENTATION:
                    case SENSOR_ORIENTATION_RAW:
                        values[0] = (x >= 180) ? (x - 180) : (x + 180);
                        values[1] =-y;
                        values[2] =-z;
                        break;
                }
            }
        }
    }
    
    class LmsFilter {
        private static final int SENSORS_RATE_MS = 20;
        private static final int COUNT = 12;
        private static final float PREDICTION_RATIO = 1.0f/3.0f;
        private static final float PREDICTION_TIME = (SENSORS_RATE_MS*COUNT/1000.0f)*PREDICTION_RATIO;
        private float mV[] = new float[COUNT*2];
        private float mT[] = new float[COUNT*2];
        private int mIndex;

        public LmsFilter() {
            mIndex = COUNT;
        }

        public float filter(long time, float in) {
            float v = in;
            final float ns = 1.0f / 1000000000.0f;
            final float t = time*ns;
            float v1 = mV[mIndex];
            if ((v-v1) > 180) {
                v -= 360;
            } else if ((v1-v) > 180) {
                v += 360;
            }
            /* Manage the circular buffer, we write the data twice spaced
             * by COUNT values, so that we don't have to copy the array
             * when it's full
             */
            mIndex++;
            if (mIndex >= COUNT*2)
                mIndex = COUNT;
            mV[mIndex] = v;
            mT[mIndex] = t;
            mV[mIndex-COUNT] = v;
            mT[mIndex-COUNT] = t;

            float A, B, C, D, E;
            float a, b;
            int i;

            A = B = C = D = E = 0;
            for (i=0 ; i<COUNT-1 ; i++) {
                final int j = mIndex - 1 - i;
                final float Z = mV[j];
                final float T = 0.5f*(mT[j] + mT[j+1]) - t;
                float dT = mT[j] - mT[j+1];
                dT *= dT;
                A += Z*dT;
                B += T*(T*dT);
                C +=   (T*dT);
                D += Z*(T*dT);
                E += dT;
            }
            b = (A*B + C*D) / (E*B + C*C);
            a = (E*b - A) / C;
            float f = b + PREDICTION_TIME*a;

            // Normalize
            f *= (1.0f / 360.0f);
            if (((f>=0)?f:-f) >= 0.5f)
                f = f - (float)Math.ceil(f + 0.5f) + 1.0f;
            if (f < 0)
                f += 1.0f;
            f *= 360.0f;
            return f;
        }
    }


    /** Helper function to compute the angle change between two rotation matrices.
     *  Given a current rotation matrix (R) and a previous rotation matrix
     *  (prevR) computes the rotation around the x,y, and z axes which
     *  transforms prevR to R.
     *  outputs a 3 element vector containing the x,y, and z angle
     *  change at indexes 0, 1, and 2 respectively.
     * <p> Each input matrix is either as a 3x3 or 4x4 row-major matrix
     * depending on the length of the passed array:
     * <p>If the array length is 9, then the array elements represent this matrix
     * <pre>
     *   /  R[ 0]   R[ 1]   R[ 2]   \
     *   |  R[ 3]   R[ 4]   R[ 5]   |
     *   \  R[ 6]   R[ 7]   R[ 8]   /
     *</pre>
     * <p>If the array length is 16, then the array elements represent this matrix
     * <pre>
     *   /  R[ 0]   R[ 1]   R[ 2]   R[ 3]  \
     *   |  R[ 4]   R[ 5]   R[ 6]   R[ 7]  |
     *   |  R[ 8]   R[ 9]   R[10]   R[11]  |
     *   \  R[12]   R[13]   R[14]   R[15]  /
     *</pre>
     * @param R current rotation matrix see {@link android.hardware.Sensor#TYPE_ROTATION_MATRIX}.
     * @param prevR previous rotation matrix
     * @param angleChange an array of floats in which the angle change is stored
     */

    public static void getAngleChange( float[] angleChange, float[] R, float[] prevR) {
        float rd1=0,rd4=0, rd6=0,rd7=0, rd8=0;
        float ri0=0,ri1=0,ri2=0,ri3=0,ri4=0,ri5=0,ri6=0,ri7=0,ri8=0;
        float pri0=0, pri1=0, pri2=0, pri3=0, pri4=0, pri5=0, pri6=0, pri7=0, pri8=0;
        int i, j, k;

        if(R.length == 9) {
            ri0 = R[0];
            ri1 = R[1];
            ri2 = R[2];
            ri3 = R[3];
            ri4 = R[4];
            ri5 = R[5];
            ri6 = R[6];
            ri7 = R[7];
            ri8 = R[8];
        } else if(R.length == 16) {
            ri0 = R[0];
            ri1 = R[1];
            ri2 = R[2];
            ri3 = R[4];
            ri4 = R[5];
            ri5 = R[6];
            ri6 = R[8];
            ri7 = R[9];
            ri8 = R[10];
        }

        if(prevR.length == 9) {
            pri0 = R[0];
            pri1 = R[1];
            pri2 = R[2];
            pri3 = R[3];
            pri4 = R[4];
            pri5 = R[5];
            pri6 = R[6];
            pri7 = R[7];
            pri8 = R[8];
        } else if(prevR.length == 16) {
            pri0 = R[0];
            pri1 = R[1];
            pri2 = R[2];
            pri3 = R[4];
            pri4 = R[5];
            pri5 = R[6];
            pri6 = R[8];
            pri7 = R[9];
            pri8 = R[10];
        }

        // calculate the parts of the rotation difference matrix we need
        // rd[i][j] = pri[0][i] * ri[0][j] + pri[1][i] * ri[1][j] + pri[2][i] * ri[2][j];

        rd1 = pri0 * ri1 + pri3 * ri4 + pri6 * ri7; //rd[0][1]
        rd4 = pri1 * ri1 + pri4 * ri4 + pri7 * ri7; //rd[1][1]
        rd6 = pri2 * ri0 + pri5 * ri3 + pri8 * ri6; //rd[2][0]
        rd7 = pri2 * ri1 + pri5 * ri4 + pri8 * ri7; //rd[2][1]
        rd8 = pri2 * ri2 + pri5 * ri5 + pri8 * ri8; //rd[2][2]

        angleChange[0] = (float)Math.atan2(rd1, rd4);
        angleChange[1] = (float)Math.asin(-rd7);
        angleChange[2] = (float)Math.atan2(-rd6, rd8);

    }

    /** Helper function to convert a rotation vector to a rotation matrix.
     *  Given a rotation vector (presumably from a ROTATION_VECTOR sensor), returns a
     *  9  or 16 element rotation matrix in the array R.  R must have length 9 or 16.
     *  If R.length == 9, the following matrix is returned:
     * <pre>
     *   /  R[ 0]   R[ 1]   R[ 2]   \
     *   |  R[ 3]   R[ 4]   R[ 5]   |
     *   \  R[ 6]   R[ 7]   R[ 8]   /
     *</pre>
     * If R.length == 16, the following matrix is returned:
     * <pre>
     *   /  R[ 0]   R[ 1]   R[ 2]   0  \
     *   |  R[ 4]   R[ 5]   R[ 6]   0  |
     *   |  R[ 8]   R[ 9]   R[10]   0  |
     *   \  0       0       0       1  /
     *</pre>
     *  @param rotationVector the rotation vector to convert
     *  @param R an array of floats in which to store the rotation matrix
     */
    public static void getRotationMatrixFromVector(float[] R, float[] rotationVector) {
        float q0 = (float)Math.sqrt(1 - rotationVector[0]*rotationVector[0] -
                                    rotationVector[1]*rotationVector[1] -
                                    rotationVector[2]*rotationVector[2]);
        float q1 = rotationVector[0];
        float q2 = rotationVector[1];
        float q3 = rotationVector[2];

        float sq_q1 = 2 * q1 * q1;
        float sq_q2 = 2 * q2 * q2;
        float sq_q3 = 2 * q3 * q3;
        float q1_q2 = 2 * q1 * q2;
        float q3_q0 = 2 * q3 * q0;
        float q1_q3 = 2 * q1 * q3;
        float q2_q0 = 2 * q2 * q0;
        float q2_q3 = 2 * q2 * q3;
        float q1_q0 = 2 * q1 * q0;

        if(R.length == 9) {
            R[0] = 1 - sq_q2 - sq_q3;
            R[1] = q1_q2 - q3_q0;
            R[2] = q1_q3 + q2_q0;

            R[3] = q1_q2 + q3_q0;
            R[4] = 1 - sq_q1 - sq_q3;
            R[5] = q2_q3 - q1_q0;

            R[6] = q1_q3 - q2_q0;
            R[7] = q2_q3 + q1_q0;
            R[8] = 1 - sq_q1 - sq_q2;
        } else if (R.length == 16) {
            R[0] = 1 - sq_q2 - sq_q3;
            R[1] = q1_q2 - q3_q0;
            R[2] = q1_q3 + q2_q0;
            R[3] = 0.0f;

            R[4] = q1_q2 + q3_q0;
            R[5] = 1 - sq_q1 - sq_q3;
            R[6] = q2_q3 - q1_q0;
            R[7] = 0.0f;

            R[8] = q1_q3 - q2_q0;
            R[9] = q2_q3 + q1_q0;
            R[10] = 1 - sq_q1 - sq_q2;
            R[11] = 0.0f;

            R[12] = R[13] = R[14] = 0.0f;
            R[15] = 1.0f;
        }
    }

    /** Helper function to convert a rotation vector to a normalized quaternion.
     *  Given a rotation vector (presumably from a ROTATION_VECTOR sensor), returns a normalized
     *  quaternion in the array Q.  The quaternion is stored as [w, x, y, z]
     *  @param rv the rotation vector to convert
     *  @param Q an array of floats in which to store the computed quaternion
     */
    public static void getQuaternionFromVector(float[] Q, float[] rv) {
        float w = (float)Math.sqrt(1 - rv[0]*rv[0] - rv[1]*rv[1] - rv[2]*rv[2]);
        //In this case, the w component of the quaternion is known to be a positive number

        Q[0] = w;
        Q[1] = rv[0];
        Q[2] = rv[1];
        Q[3] = rv[2];
    }

    private static native void nativeClassInit();

    private static native int sensors_module_init();
    private static native int sensors_module_get_next_sensor(Sensor sensor, int next);

    // Used within this module from outside SensorManager, don't make private
    static native int sensors_data_init();
    static native int sensors_data_uninit();
    static native int sensors_data_open(FileDescriptor[] fds, int[] ints);
    static native int sensors_data_close();
    static native int sensors_data_poll(float[] values, int[] status, long[] timestamp);
}
