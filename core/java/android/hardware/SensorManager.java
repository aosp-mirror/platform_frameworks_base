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
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.Handler;
import android.os.Message;
import android.os.ServiceManager;
import android.util.Log;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.Surface;

import java.io.FileDescriptor;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;

/**
 * Class that lets you access the device's sensors. Get an instance of this
 * class by calling {@link android.content.Context#getSystemService(java.lang.String)
 * Context.getSystemService()} with an argument of {@link android.content.Context#SENSOR_SERVICE}.
 */
public class SensorManager extends IRotationWatcher.Stub
{
    private static final String TAG = "SensorManager";

    /** NOTE: sensor IDs must be a power of 2 */

    /** A constant describing an orientation sensor.
     * Sensor values are yaw, pitch and roll
     *
     * Yaw is the compass heading in degrees, range [0, 360[
     * 0 = North, 90 = East, 180 = South, 270 = West
     *
     * Pitch indicates the tilt of the top of the device,
     * with range -90 to 90.
     * Positive values indicate that the bottom of the device is tilted up
     * and negative values indicate the top of the device is tilted up.
     *
     * Roll indicates the side to side tilt of the device,
     * with range -90 to 90.
     * Positive values indicate that the left side of the device is tilted up
     * and negative values indicate the right side of the device is tilted up.
     */
    public static final int SENSOR_ORIENTATION = 1 << 0;

    /** A constant describing an accelerometer.
     * Sensor values are acceleration in the X, Y and Z axis,
     * where the X axis has positive direction toward the right side of the device,
     * the Y axis has positive direction toward the top of the device
     * and the Z axis has positive direction toward the front of the device.
     *
     * The direction of the force of gravity is indicated by acceleration values in the
     * X, Y and Z axes.  The typical case where the device is flat relative to the surface
     * of the Earth appears as -STANDARD_GRAVITY in the Z axis
     * and X and Z values close to zero.
     *
     * Acceleration values are given in SI units (m/s^2)
     *
     */
    public static final int SENSOR_ACCELEROMETER = 1 << 1;

    /** A constant describing a temperature sensor
     * Only the first value is defined for this sensor and it
     * contains the ambient temperature in degree C.
     */
    public static final int SENSOR_TEMPERATURE = 1 << 2;

    /** A constant describing a magnetic sensor
     * Sensor values are the magnetic vector in the X, Y and Z axis,
     * where the X axis has positive direction toward the right side of the device,
     * the Y axis has positive direction toward the top of the device
     * and the Z axis has positive direction toward the front of the device.
     * 
     * Magnetic values are given in micro-Tesla (uT)
     * 
     */
    public static final int SENSOR_MAGNETIC_FIELD = 1 << 3;

    /** A constant describing an ambient light sensor
     * Only the first value is defined for this sensor and it contains
     * the ambient light measure in lux.
     * 
     */
    public static final int SENSOR_LIGHT = 1 << 4;

    /** A constant describing a proximity sensor
     * Only the first value is defined for this sensor and it contains
     * the distance between the sensor and the object in meters (m)
     */
    public static final int SENSOR_PROXIMITY = 1 << 5;

    /** A constant describing a Tricorder
     * When this sensor is available and enabled, the device can be
     * used as a fully functional Tricorder. All values are returned in 
     * SI units.
     */
    public static final int SENSOR_TRICORDER = 1 << 6;

    /** A constant describing an orientation sensor.
     * Sensor values are yaw, pitch and roll
     *
     * Yaw is the compass heading in degrees, 0 <= range < 360
     * 0 = North, 90 = East, 180 = South, 270 = West
     *
     * This is similar to SENSOR_ORIENTATION except the data is not 
     * smoothed or filtered in any way.
     */
    public static final int SENSOR_ORIENTATION_RAW = 1 << 7;

    /** A constant that includes all sensors */
    public static final int SENSOR_ALL = 0x7F;

    /** Smallest sensor ID */
    public static final int SENSOR_MIN = SENSOR_ORIENTATION;

    /** Largest sensor ID */
    public static final int SENSOR_MAX = ((SENSOR_ALL + 1)>>1);


    /** Index of the X value in the array returned by 
     * {@link android.hardware.SensorListener#onSensorChanged} */
    public static final int DATA_X = 0;
    /** Index of the Y value in the array returned by 
     * {@link android.hardware.SensorListener#onSensorChanged} */
    public static final int DATA_Y = 1;
    /** Index of the Z value in the array returned by 
     * {@link android.hardware.SensorListener#onSensorChanged} */
    public static final int DATA_Z = 2;
    
    /** Offset to the raw values in the array returned by 
     * {@link android.hardware.SensorListener#onSensorChanged} */
    public static final int RAW_DATA_INDEX = 3;

    /** Index of the raw X value in the array returned by 
     * {@link android.hardware.SensorListener#onSensorChanged} */
    public static final int RAW_DATA_X = 3;
    /** Index of the raw X value in the array returned by 
     * {@link android.hardware.SensorListener#onSensorChanged} */
    public static final int RAW_DATA_Y = 4;
    /** Index of the raw X value in the array returned by 
     * {@link android.hardware.SensorListener#onSensorChanged} */
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
    public static final float GRAVITY_NEPTUN          = 11.0f;
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

    

    private static final int SENSOR_DISABLE = -1;
    private static final int SENSOR_ORDER_MASK = 0x1F;
    private static final int SENSOR_STATUS_SHIFT = 28;
    private ISensorService mSensorService;
    private Looper mLooper;

    private static IWindowManager sWindowManager;
    private static int sRotation = 0;

    /* The thread and the sensor list are global to the process 
     * but the actual thread is spawned on demand */
    static final private SensorThread sSensorThread = new SensorThread();
    static final private ArrayList<ListenerDelegate> sListeners = 
        new ArrayList<ListenerDelegate>();


    static private class SensorThread {

        private Thread mThread;

        // must be called with sListeners lock
        void startLocked(ISensorService service) {
            try {
                if (mThread == null) {
                    ParcelFileDescriptor fd = service.getDataChanel();
                    mThread = new Thread(new SensorThreadRunnable(fd, service), 
                            SensorThread.class.getName());
                    mThread.start();
                }
            } catch (RemoteException e) {
                Log.e(TAG, "RemoteException in startLocked: ", e);
            }
        }

        private class SensorThreadRunnable implements Runnable {
            private ISensorService mSensorService;
            private ParcelFileDescriptor mSensorDataFd;
            private final byte mAccuracies[] = new byte[32];
            SensorThreadRunnable(ParcelFileDescriptor fd, ISensorService service) {
                mSensorDataFd = fd;
                mSensorService = service;
                Arrays.fill(mAccuracies, (byte)-1);
            }
            public void run() {
                int sensors_of_interest;
                float[] values = new float[6];
                Process.setThreadPriority(Process.THREAD_PRIORITY_DISPLAY);

                synchronized (sListeners) {
                    _sensors_data_open(mSensorDataFd.getFileDescriptor());
                    try {
                        mSensorDataFd.close();
                    } catch (IOException e) {
                        // *shrug*
                        Log.e(TAG, "IOException: ", e);
                    }
                    mSensorDataFd = null;
                    //mSensorDataFd.
                    // the first time, compute the sensors we need. this is not
                    // a big deal if it changes by the time we call
                    // _sensors_data_poll, it'll get recomputed for the next
                    // round.
                    sensors_of_interest = 0;
                    final int size = sListeners.size();
                    for (int i=0 ; i<size ; i++) {
                        sensors_of_interest |= sListeners.get(i).mSensors;
                        if ((sensors_of_interest & SENSOR_ALL) == SENSOR_ALL)
                            break;
                    }
                }

                while (true) {
                    // wait for an event
                    final int sensor_result = _sensors_data_poll(values, sensors_of_interest);
                    final int sensor_order = sensor_result & SENSOR_ORDER_MASK;
                    final int sensor = 1 << sensor_result;
                    int accuracy = sensor_result>>>SENSOR_STATUS_SHIFT;

                    if ((sensors_of_interest & sensor)!=0) {
                        // show the notification only if someone is listening for
                        // this sensor
                        if (accuracy != mAccuracies[sensor_order]) {
                            try {
                                mSensorService.reportAccuracy(sensor, accuracy);
                                mAccuracies[sensor_order] = (byte)accuracy;
                            } catch (RemoteException e) {
                                Log.e(TAG, "RemoteException in reportAccuracy: ", e);
                            }
                        } else {
                            accuracy = -1;
                        }
                    }
                    
                    synchronized (sListeners) {
                        if (sListeners.isEmpty()) {
                            // we have no more listeners, terminate the thread
                            _sensors_data_close();
                            mThread = null;
                            break;
                        }
                        // convert for the current screen orientation
                        mapSensorDataToWindow(sensor, values, SensorManager.getRotation());
                        // report the sensor event to all listeners that
                        // care about it.
                        sensors_of_interest = 0;
                        final int size = sListeners.size();
                        for (int i=0 ; i<size ; i++) {
                            ListenerDelegate listener = sListeners.get(i);
                            sensors_of_interest |= listener.mSensors;
                            if (listener.hasSensor(sensor)) {
                                // this is asynchronous (okay to call
                                // with sListeners lock held.
                                listener.onSensorChanged(sensor, values, accuracy);
                            }
                        }
                    }
                }
            }
        }
    }

    private class ListenerDelegate extends Binder {

        private SensorListener mListener;
        private int mSensors;
        private float[] mValuesPool;

        ListenerDelegate(SensorListener listener, int sensors) {
            mListener = listener;
            mSensors = sensors;
            mValuesPool = new float[6];
        }

        int addSensors(int sensors) {
            mSensors |= sensors;
            return mSensors;
        }
        int removeSensors(int sensors) {
            mSensors &= ~sensors;
            return mSensors;
        }
        boolean hasSensor(int sensor) {
            return ((mSensors & sensor) != 0);
        }

        void onSensorChanged(int sensor, float[] values, int accuracy) {
            float[] v;
            synchronized (this) {
                // remove the array from the pool
                v = mValuesPool;
                mValuesPool = null;
            }

            if (v != null) {
                v[0] = values[0];
                v[1] = values[1];
                v[2] = values[2];
                v[3] = values[3];
                v[4] = values[4];
                v[5] = values[5];
            } else {
                // the pool was empty, we need to dup the array
                v = values.clone();
            }

            Message msg = Message.obtain();
            msg.what = sensor;
            msg.obj = v;
            msg.arg1 = accuracy;
            mHandler.sendMessage(msg);
        }

        private final Handler mHandler = new Handler(mLooper) {
            @Override public void handleMessage(Message msg) {
                if (msg.arg1 >= 0) {
                    try {
                        mListener.onAccuracyChanged(msg.what, msg.arg1);
                    } catch (AbstractMethodError e) {
                        // old app that doesn't implement this method
                        // just ignore it.
                    }
                }
                mListener.onSensorChanged(msg.what, (float[])msg.obj);
                synchronized (this) {
                    // put back the array into the pool
                    if (mValuesPool == null) {
                        mValuesPool = (float[])msg.obj;
                    }
                }
            }
        };
    }

    /**
     * {@hide}
     */
    public SensorManager(Looper mainLooper) {
        mSensorService = ISensorService.Stub.asInterface(
                ServiceManager.getService(Context.SENSOR_SERVICE));
        
        sWindowManager = IWindowManager.Stub.asInterface(
                ServiceManager.getService("window"));

        if (sWindowManager != null) {
            // if it's null we're running in the system process
            // which won't get the rotated values
            try {
                sWindowManager.watchRotation(this);
            } catch (RemoteException e) {
            }
        }

        mLooper = mainLooper;
    }

    /** @return available sensors */
    public int getSensors() {
        return _sensors_data_get_sensors();
    }

    /**
     * Registers a listener for given sensors.
     *
     * @param listener sensor listener object
     * @param sensors a bit masks of the sensors to register to
     *
     * @return true if the sensor is supported and successfully enabled
     */
    public boolean registerListener(SensorListener listener, int sensors) {
        return registerListener(listener, sensors, SENSOR_DELAY_NORMAL);
    }

    /**
     * Registers a listener for given sensors.
     *
     * @param listener sensor listener object
     * @param sensors a bit masks of the sensors to register to
     * @param rate rate of events. This is only a hint to the system. events
     * may be received faster or slower than the specified rate. Usually events
     * are received faster.
     *
     * @return true if the sensor is supported and successfully enabled
     */
    public boolean registerListener(SensorListener listener, int sensors, int rate) {
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
                    if (i.mListener == listener) {
                        l = i;
                        break;
                    }
                }

                if (l == null) {
                    l = new ListenerDelegate(listener, sensors);
                    result = mSensorService.enableSensor(l, sensors, delay);
                    if (result) {
                        sListeners.add(l);
                        sListeners.notify();
                    }
                    if (!sListeners.isEmpty()) {
                        sSensorThread.startLocked(mSensorService);
                    }
                } else {
                    result = mSensorService.enableSensor(l, sensors, delay);
                    if (result) {
                        l.addSensors(sensors);
                    }
                }
            }
        } catch (RemoteException e) {
            Log.e(TAG, "RemoteException in registerListener: ", e);
            result = false;
        }
        return result;
    }

    /**
     * Unregisters a listener for the sensors with which it is registered.
     *
     * @param listener a SensorListener object
     * @param sensors a bit masks of the sensors to unregister from
     */
    public void unregisterListener(SensorListener listener, int sensors) {
        try {
            synchronized (sListeners) {
                final int size = sListeners.size();
                for (int i=0 ; i<size ; i++) {
                    ListenerDelegate l = sListeners.get(i);
                    if (l.mListener == listener) {
                        // disable these sensors
                        mSensorService.enableSensor(l, sensors, SENSOR_DISABLE);
                        // if we have no more sensors enabled on this listener,
                        // take it off the list.
                        if (l.removeSensors(sensors) == 0)
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
     * Unregisters a listener for all sensors.
     *
     * @param listener a SensorListener object
     */
    public void unregisterListener(SensorListener listener) {
        unregisterListener(listener, SENSOR_ALL);
    }


    /**
     * Helper function to convert the specified sensor's data to the windows's 
     * coordinate space from the device's coordinate space.
     */ 
    
    private static void mapSensorDataToWindow(int sensor, float[] values, int orientation) {
        final float x = values[DATA_X];
        final float y = values[DATA_Y];
        final float z = values[DATA_Z];
        // copy the raw raw values...
        values[RAW_DATA_X] = x;
        values[RAW_DATA_Y] = y;
        values[RAW_DATA_Z] = z;
        // TODO: add support for 180 and 270 orientations
        if (orientation == Surface.ROTATION_90) {
            switch (sensor) {
                case SENSOR_ACCELEROMETER:
                case SENSOR_MAGNETIC_FIELD:
                    values[DATA_X] =-y;
                    values[DATA_Y] = x;
                    values[DATA_Z] = z; 
                    break;
                case SENSOR_ORIENTATION:
                case SENSOR_ORIENTATION_RAW:
                    values[DATA_X] = x + ((x < 270) ? 90 : -270);
                    values[DATA_Y] = z;
                    values[DATA_Z] = y; 
                    break;
            }
        }
    }


    private static native int _sensors_data_open(FileDescriptor fd);
    private static native int _sensors_data_close();
    // returns the sensor's status in the top 4 bits of "res".
    private static native int _sensors_data_poll(float[] values, int sensors);
    private static native int _sensors_data_get_sensors();

    /** {@hide} */
    public void onRotationChanged(int rotation) {
        synchronized(sListeners) {
            sRotation  = rotation;
        }
    }
    
    private static int getRotation() {
        synchronized(sListeners) {
            return sRotation;
        }
    }
}

