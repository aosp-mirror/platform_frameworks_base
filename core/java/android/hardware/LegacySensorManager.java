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

import android.os.RemoteException;
import android.os.ServiceManager;
import android.view.IRotationWatcher;
import android.view.IWindowManager;
import android.view.Surface;

import java.util.HashMap;
import java.util.List;

/**
 * Helper class for implementing the legacy sensor manager API.
 * @hide
 */
@SuppressWarnings("deprecation")
final class LegacySensorManager {
    private static boolean sInitialized;
    private static IWindowManager sWindowManager;
    private static int sRotation = Surface.ROTATION_0;

    private final SensorManager mSensorManager;

    // List of legacy listeners.  Guarded by mLegacyListenersMap.
    private final HashMap<SensorListener, LegacyListener> mLegacyListenersMap =
            new HashMap<SensorListener, LegacyListener>();

    public LegacySensorManager(SensorManager sensorManager) {
        mSensorManager = sensorManager;

        synchronized (SensorManager.class) {
            if (!sInitialized) {
                sWindowManager = IWindowManager.Stub.asInterface(
                        ServiceManager.getService("window"));
                if (sWindowManager != null) {
                    // if it's null we're running in the system process
                    // which won't get the rotated values
                    try {
                        sRotation = sWindowManager.watchRotation(
                                new IRotationWatcher.Stub() {
                                    public void onRotationChanged(int rotation) {
                                        LegacySensorManager.onRotationChanged(rotation);
                                    }
                                }
                        );
                    } catch (RemoteException e) {
                    }
                }
            }
        }
    }

    public int getSensors() {
        int result = 0;
        final List<Sensor> fullList = mSensorManager.getFullSensorList();
        for (Sensor i : fullList) {
            switch (i.getType()) {
                case Sensor.TYPE_ACCELEROMETER:
                    result |= SensorManager.SENSOR_ACCELEROMETER;
                    break;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    result |= SensorManager.SENSOR_MAGNETIC_FIELD;
                    break;
                case Sensor.TYPE_ORIENTATION:
                    result |= SensorManager.SENSOR_ORIENTATION
                            | SensorManager.SENSOR_ORIENTATION_RAW;
                    break;
            }
        }
        return result;
    }

    public boolean registerListener(SensorListener listener, int sensors, int rate) {
        if (listener == null) {
            return false;
        }
        boolean result = false;
        result = registerLegacyListener(SensorManager.SENSOR_ACCELEROMETER,
                Sensor.TYPE_ACCELEROMETER, listener, sensors, rate) || result;
        result = registerLegacyListener(SensorManager.SENSOR_MAGNETIC_FIELD,
                Sensor.TYPE_MAGNETIC_FIELD, listener, sensors, rate) || result;
        result = registerLegacyListener(SensorManager.SENSOR_ORIENTATION_RAW,
                Sensor.TYPE_ORIENTATION, listener, sensors, rate) || result;
        result = registerLegacyListener(SensorManager.SENSOR_ORIENTATION,
                Sensor.TYPE_ORIENTATION, listener, sensors, rate) || result;
        result = registerLegacyListener(SensorManager.SENSOR_TEMPERATURE,
                Sensor.TYPE_TEMPERATURE, listener, sensors, rate) || result;
        return result;
    }

    private boolean registerLegacyListener(int legacyType, int type,
            SensorListener listener, int sensors, int rate) {
        boolean result = false;
        // Are we activating this legacy sensor?
        if ((sensors & legacyType) != 0) {
            // if so, find a suitable Sensor
            Sensor sensor = mSensorManager.getDefaultSensor(type);
            if (sensor != null) {
                // We do all of this work holding the legacy listener lock to ensure
                // that the invariants around listeners are maintained.  This is safe
                // because neither registerLegacyListener nor unregisterLegacyListener
                // are called reentrantly while sensors are being registered or unregistered.
                synchronized (mLegacyListenersMap) {
                    // If we don't already have one, create a LegacyListener
                    // to wrap this listener and process the events as
                    // they are expected by legacy apps.
                    LegacyListener legacyListener = mLegacyListenersMap.get(listener);
                    if (legacyListener == null) {
                        // we didn't find a LegacyListener for this client,
                        // create one, and put it in our list.
                        legacyListener = new LegacyListener(listener);
                        mLegacyListenersMap.put(listener, legacyListener);
                    }

                    // register this legacy sensor with this legacy listener
                    if (legacyListener.registerSensor(legacyType)) {
                        // and finally, register the legacy listener with the new apis
                        result = mSensorManager.registerListener(legacyListener, sensor, rate);
                    } else {
                        result = true; // sensor already enabled
                    }
                }
            }
        }
        return result;
    }

    public void unregisterListener(SensorListener listener, int sensors) {
        if (listener == null) {
            return;
        }
        unregisterLegacyListener(SensorManager.SENSOR_ACCELEROMETER, Sensor.TYPE_ACCELEROMETER,
                listener, sensors);
        unregisterLegacyListener(SensorManager.SENSOR_MAGNETIC_FIELD, Sensor.TYPE_MAGNETIC_FIELD,
                listener, sensors);
        unregisterLegacyListener(SensorManager.SENSOR_ORIENTATION_RAW, Sensor.TYPE_ORIENTATION,
                listener, sensors);
        unregisterLegacyListener(SensorManager.SENSOR_ORIENTATION, Sensor.TYPE_ORIENTATION,
                listener, sensors);
        unregisterLegacyListener(SensorManager.SENSOR_TEMPERATURE, Sensor.TYPE_TEMPERATURE,
                listener, sensors);
    }

    private void unregisterLegacyListener(int legacyType, int type,
            SensorListener listener, int sensors) {
        // Are we deactivating this legacy sensor?
        if ((sensors & legacyType) != 0) {
            // if so, find the corresponding Sensor
            Sensor sensor = mSensorManager.getDefaultSensor(type);
            if (sensor != null) {
                // We do all of this work holding the legacy listener lock to ensure
                // that the invariants around listeners are maintained.  This is safe
                // because neither registerLegacyListener nor unregisterLegacyListener
                // are called re-entrantly while sensors are being registered or unregistered.
                synchronized (mLegacyListenersMap) {
                    // do we know about this listener?
                    LegacyListener legacyListener = mLegacyListenersMap.get(listener);
                    if (legacyListener != null) {
                        // unregister this legacy sensor and if we don't
                        // need the corresponding Sensor, unregister it too
                        if (legacyListener.unregisterSensor(legacyType)) {
                            // corresponding sensor not needed, unregister
                            mSensorManager.unregisterListener(legacyListener, sensor);

                            // finally check if we still need the legacyListener
                            // in our mapping, if not, get rid of it too.
                            if (!legacyListener.hasSensors()) {
                                mLegacyListenersMap.remove(listener);
                            }
                        }
                    }
                }
            }
        }
    }

    static void onRotationChanged(int rotation) {
        synchronized (SensorManager.class) {
            sRotation  = rotation;
        }
    }

    static int getRotation() {
        synchronized (SensorManager.class) {
            return sRotation;
        }
    }

    private static final class LegacyListener implements SensorEventListener {
        private float mValues[] = new float[6];
        private SensorListener mTarget;
        private int mSensors;
        private final LmsFilter mYawfilter = new LmsFilter();

        LegacyListener(SensorListener target) {
            mTarget = target;
            mSensors = 0;
        }

        boolean registerSensor(int legacyType) {
            if ((mSensors & legacyType) != 0) {
                return false;
            }
            boolean alreadyHasOrientationSensor = hasOrientationSensor(mSensors);
            mSensors |= legacyType;
            if (alreadyHasOrientationSensor && hasOrientationSensor(legacyType)) {
                return false; // don't need to re-register the orientation sensor
            }
            return true;
        }

        boolean unregisterSensor(int legacyType) {
            if ((mSensors & legacyType) == 0) {
                return false;
            }
            mSensors &= ~legacyType;
            if (hasOrientationSensor(legacyType) && hasOrientationSensor(mSensors)) {
                return false; // can't unregister the orientation sensor just yet
            }
            return true;
        }

        boolean hasSensors() {
            return mSensors != 0;
        }

        private static boolean hasOrientationSensor(int sensors) {
            return (sensors & (SensorManager.SENSOR_ORIENTATION
                    | SensorManager.SENSOR_ORIENTATION_RAW)) != 0;
        }

        public void onAccuracyChanged(Sensor sensor, int accuracy) {
            try {
                mTarget.onAccuracyChanged(getLegacySensorType(sensor.getType()), accuracy);
            } catch (AbstractMethodError e) {
                // old app that doesn't implement this method
                // just ignore it.
            }
        }

        public void onSensorChanged(SensorEvent event) {
            final float v[] = mValues;
            v[0] = event.values[0];
            v[1] = event.values[1];
            v[2] = event.values[2];
            int type = event.sensor.getType();
            int legacyType = getLegacySensorType(type);
            mapSensorDataToWindow(legacyType, v, LegacySensorManager.getRotation());
            if (type == Sensor.TYPE_ORIENTATION) {
                if ((mSensors & SensorManager.SENSOR_ORIENTATION_RAW)!=0) {
                    mTarget.onSensorChanged(SensorManager.SENSOR_ORIENTATION_RAW, v);
                }
                if ((mSensors & SensorManager.SENSOR_ORIENTATION)!=0) {
                    v[0] = mYawfilter.filter(event.timestamp, v[0]);
                    mTarget.onSensorChanged(SensorManager.SENSOR_ORIENTATION, v);
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
                    case SensorManager.SENSOR_ACCELEROMETER:
                    case SensorManager.SENSOR_MAGNETIC_FIELD:
                        values[0] =-y;
                        values[1] = x;
                        values[2] = z;
                        break;
                    case SensorManager.SENSOR_ORIENTATION:
                    case SensorManager.SENSOR_ORIENTATION_RAW:
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
                    case SensorManager.SENSOR_ACCELEROMETER:
                    case SensorManager.SENSOR_MAGNETIC_FIELD:
                        values[0] =-x;
                        values[1] =-y;
                        values[2] = z;
                        break;
                    case SensorManager.SENSOR_ORIENTATION:
                    case SensorManager.SENSOR_ORIENTATION_RAW:
                        values[0] = (x >= 180) ? (x - 180) : (x + 180);
                        values[1] =-y;
                        values[2] =-z;
                        break;
                }
            }
        }

        private static int getLegacySensorType(int type) {
            switch (type) {
                case Sensor.TYPE_ACCELEROMETER:
                    return SensorManager.SENSOR_ACCELEROMETER;
                case Sensor.TYPE_MAGNETIC_FIELD:
                    return SensorManager.SENSOR_MAGNETIC_FIELD;
                case Sensor.TYPE_ORIENTATION:
                    return SensorManager.SENSOR_ORIENTATION_RAW;
                case Sensor.TYPE_TEMPERATURE:
                    return SensorManager.SENSOR_TEMPERATURE;
            }
            return 0;
        }
    }

    private static final class LmsFilter {
        private static final int SENSORS_RATE_MS = 20;
        private static final int COUNT = 12;
        private static final float PREDICTION_RATIO = 1.0f/3.0f;
        private static final float PREDICTION_TIME = (SENSORS_RATE_MS*COUNT/1000.0f)*PREDICTION_RATIO;
        private float mV[] = new float[COUNT*2];
        private long mT[] = new long[COUNT*2];
        private int mIndex;

        public LmsFilter() {
            mIndex = COUNT;
        }

        public float filter(long time, float in) {
            float v = in;
            final float ns = 1.0f / 1000000000.0f;
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
            mT[mIndex] = time;
            mV[mIndex-COUNT] = v;
            mT[mIndex-COUNT] = time;

            float A, B, C, D, E;
            float a, b;
            int i;

            A = B = C = D = E = 0;
            for (i=0 ; i<COUNT-1 ; i++) {
                final int j = mIndex - 1 - i;
                final float Z = mV[j];
                final float T = (mT[j]/2 + mT[j+1]/2 - time)*ns;
                float dT = (mT[j] - mT[j+1])*ns;
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
}
