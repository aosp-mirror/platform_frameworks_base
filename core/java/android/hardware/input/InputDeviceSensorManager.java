/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.hardware.input;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorDirectChannel;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEventListener;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.MemoryFile;
import android.os.Message;
import android.os.RemoteException;
import android.util.Slog;
import android.util.SparseArray;
import android.view.InputDevice;

import com.android.internal.annotations.GuardedBy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sensor manager implementation that communicates with the input device
 * sensors.
 * @hide
 */
public class InputDeviceSensorManager {
    private static final String TAG = "InputDeviceSensorManager";
    private static final boolean DEBUG = false;

    private static final int MSG_SENSOR_ACCURACY_CHANGED = 1;
    private static final int MSG_SENSOR_CHANGED = 2;

    private final InputManagerGlobal mGlobal;

    // sensor map from device id to sensor list
    @GuardedBy("mInputSensorLock")
    private final Map<Integer, List<Sensor>> mSensors = new HashMap<>();

    private final Object mInputSensorLock = new Object();
    private InputSensorEventListener mInputServiceSensorListener;
    @GuardedBy("mInputSensorLock")
    private final ArrayList<InputSensorEventListenerDelegate> mInputSensorEventListeners =
            new ArrayList<>();

    // The sensor thread is only initialized if there is a listener added without a handler.
    @GuardedBy("mInputSensorLock")
    @Nullable
    private HandlerThread mSensorThread;

    public InputDeviceSensorManager(InputManagerGlobal inputManagerGlobal) {
        mGlobal = inputManagerGlobal;

        // Initialize the sensor list
        initializeSensors();
    }

    /*
     * Get SensorManager object for specific input device
     *
     * @param deviceId Input device ID
     * @return SensorManager object for input device
     */
    SensorManager getSensorManager(int deviceId) {
        return new InputSensorManager(deviceId);
    }

    /*
     * Update input device sensor info for specified input device ID.
     */
    private void updateInputDeviceSensorInfoLocked(int deviceId) {
        final InputDevice inputDevice = InputDevice.getDevice(deviceId);
        if (inputDevice != null && inputDevice.hasSensor()) {
            final InputSensorInfo[] sensorInfos =
                    mGlobal.getSensorList(deviceId);
            populateSensorsForInputDeviceLocked(deviceId, sensorInfos);
        }
    }

    public void onInputDeviceAdded(int deviceId) {
        synchronized (mInputSensorLock) {
            if (!mSensors.containsKey(deviceId)) {
                updateInputDeviceSensorInfoLocked(deviceId);
            } else {
                Slog.e(TAG, "Received 'device added' notification for device " + deviceId
                        + ", but it is already in the list");
            }
        }
    }

    public void onInputDeviceRemoved(int deviceId) {
        synchronized (mInputSensorLock) {
            mSensors.remove(deviceId);
        }
    }

    public void onInputDeviceChanged(int deviceId) {
        synchronized (mInputSensorLock) {
            mSensors.remove(deviceId);
            updateInputDeviceSensorInfoLocked(deviceId);
        }
    }

    private static boolean sensorEquals(@NonNull Sensor lhs, @NonNull Sensor rhs) {
        return lhs.getType() == rhs.getType() && lhs.getId() == rhs.getId();
    }

    private void populateSensorsForInputDeviceLocked(int deviceId, InputSensorInfo[] sensorInfos) {
        List<Sensor> sensors = new ArrayList<>();
        for (int i = 0; i < sensorInfos.length; i++) {
            Sensor sensor = new Sensor(sensorInfos[i]);
            if (DEBUG) {
                Slog.d(TAG, "Device " + deviceId + " sensor " + sensor.getStringType() + " added");
            }
            sensors.add(sensor);
        }
        mSensors.put(deviceId, sensors);
    }

    private void initializeSensors() {
        synchronized (mInputSensorLock) {
            mSensors.clear();
            int[] deviceIds = mGlobal.getInputDeviceIds();
            for (int i = 0; i < deviceIds.length; i++) {
                final int deviceId = deviceIds[i];
                updateInputDeviceSensorInfoLocked(deviceId);
            }
        }
    }

    /**
     * Get a sensor object for input device, with specific sensor type.
     * @param deviceId The input devicd ID
     * @param sensorType The sensor type
     * @return The sensor object if exists or null
     */
    @GuardedBy("mInputSensorLock")
    private Sensor getInputDeviceSensorLocked(int deviceId, int sensorType) {
        List<Sensor> sensors = mSensors.get(deviceId);
        for (Sensor sensor : sensors) {
            if (sensor.getType() == sensorType) {
                return sensor;
            }
        }
        return null;
    }

    @GuardedBy("mInputSensorLock")
    private int findSensorEventListenerLocked(SensorEventListener listener) {
        for (int i = 0; i < mInputSensorEventListeners.size(); i++) {
            if (mInputSensorEventListeners.get(i).getListener() == listener) {
                return i;
            }
        }
        return Integer.MIN_VALUE;
    }

    private void onInputSensorChanged(int deviceId, int sensorType, int accuracy, long timestamp,
            float[] values) {
        if (DEBUG) {
            Slog.d(TAG, "Sensor changed: deviceId =" + deviceId
                    + " timestamp=" + timestamp + " sensorType=" + sensorType);
        }
        synchronized (mInputSensorLock) {
            Sensor sensor = getInputDeviceSensorLocked(deviceId, sensorType);
            if (sensor == null) {
                Slog.wtf(TAG, "onInputSensorChanged: Got sensor update for device " + deviceId
                        + " but the sensor was not found.");
                return;
            }
            for (int i = 0; i < mInputSensorEventListeners.size(); i++) {
                InputSensorEventListenerDelegate listener =
                        mInputSensorEventListeners.get(i);
                if (listener.hasSensorRegistered(deviceId, sensorType)) {
                    SensorEvent event = listener.getSensorEvent(sensor);
                    if (event == null) {
                        Slog.wtf(TAG, "Failed to get SensorEvent.");
                        return;
                    }
                    event.sensor = sensor;
                    event.accuracy = accuracy;
                    event.timestamp = timestamp;
                    System.arraycopy(values, 0, event.values, 0, event.values.length);
                    // Call listener for sensor changed
                    listener.sendSensorChanged(event);
                }
            }
        }
    }

    private void onInputSensorAccuracyChanged(int deviceId, int sensorType, int accuracy) {
        if (DEBUG) {
            Slog.d(TAG, "Sensor accuracy changed: "
                    + "accuracy=" + accuracy + ", sensorType=" + sensorType);
        }
        synchronized (mInputSensorLock) {
            for (int i = 0; i < mInputSensorEventListeners.size(); i++) {
                InputSensorEventListenerDelegate listener =
                        mInputSensorEventListeners.get(i);
                if (listener.hasSensorRegistered(deviceId, sensorType)) {
                    listener.sendSensorAccuracyChanged(deviceId, sensorType, accuracy);
                }
            }
        }
    }

    private final class InputSensorEventListener extends IInputSensorEventListener.Stub {
        @Override
        public void onInputSensorChanged(int deviceId, int sensorType, int accuracy, long timestamp,
                float[] values) throws RemoteException {
            InputDeviceSensorManager.this.onInputSensorChanged(
                    deviceId, sensorType, accuracy, timestamp, values);
        }

        @Override
        public void onInputSensorAccuracyChanged(int deviceId, int sensorType, int accuracy)
                throws RemoteException {
            InputDeviceSensorManager.this.onInputSensorAccuracyChanged(deviceId, sensorType,
                    accuracy);
        }

    }

    private static final class InputSensorEventListenerDelegate extends Handler {
        private final SensorEventListener mListener;
        // List of sensors being listened to
        private final List<Sensor> mSensors = new ArrayList<>();
        // Sensor event array by sensor type, preallocate sensor events for each sensor of listener
        // to avoid allocation and garbage collection for each listener callback.
        private final SparseArray<SensorEvent> mSensorEvents = new SparseArray<>();

        InputSensorEventListenerDelegate(SensorEventListener listener, Sensor sensor,
                Looper looper) {
            super(looper);
            mListener = listener;
            addSensor(sensor);
        }

        public List<Sensor> getSensors() {
            return mSensors;
        }

        public boolean isEmpty() {
            return mSensors.isEmpty();
        }

        /**
         * Remove sensor from sensor list for listener
         */
        public void removeSensor(@Nullable Sensor sensor) {
            // If sensor is not specified the listener will be unregistered for all sensors
            // and the sensor list is cleared.
            if (sensor == null) {
                mSensors.clear();
                mSensorEvents.clear();
                return;
            }
            for (Sensor s : mSensors) {
                if (sensorEquals(s, sensor)) {
                    mSensors.remove(sensor);
                    mSensorEvents.remove(sensor.getType());
                }
            }
        }

        /**
         * Add a sensor to listener's sensor list
         */
        public void addSensor(@NonNull Sensor sensor) {
            for (Sensor s : mSensors) {
                if (sensorEquals(s, sensor)) {
                    Slog.w(TAG, "Adding sensor " + sensor + " already exist!");
                    return;
                }
            }
            mSensors.add(sensor);
            final int vecLength = Sensor.getMaxLengthValuesArray(sensor, Build.VERSION.SDK_INT);
            SensorEvent event = new SensorEvent(sensor, SensorManager.SENSOR_STATUS_NO_CONTACT,
                    0 /* timestamp */, new float[vecLength]);
            mSensorEvents.put(sensor.getType(), event);
        }

        /**
         * Check if the listener has been registered to the sensor
         * @param deviceId The input device ID of the sensor
         * @param sensorType The sensor type of the sensor
         * @return true if specified sensor is registered for the listener.
         */
        public boolean hasSensorRegistered(int deviceId, int sensorType) {
            for (Sensor sensor : mSensors) {
                if (sensor.getType() == sensorType && sensor.getId() == deviceId) {
                    return true;
                }
            }
            return false;
        }

        /**
         * Get listener handle for the delegate
         */
        public SensorEventListener getListener() {
            return mListener;
        }

        /**
         * Get SensorEvent object for input device, with specified sensor.
         */
        private SensorEvent getSensorEvent(@NonNull Sensor sensor) {
            return mSensorEvents.get(sensor.getType());
        }

        /**
         * Send sensor changed message
         */
        public void sendSensorChanged(SensorEvent event) {
            obtainMessage(MSG_SENSOR_CHANGED, event).sendToTarget();
        }

        /**
         * Send sensor accuracy changed message
         */
        public void sendSensorAccuracyChanged(int deviceId, int sensorType, int accuracy) {
            obtainMessage(MSG_SENSOR_ACCURACY_CHANGED, deviceId, sensorType, accuracy)
                    .sendToTarget();
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SENSOR_ACCURACY_CHANGED: {
                    final int deviceId = msg.arg1;
                    final int sensorType = msg.arg2;
                    final int accuracy = (int) msg.obj;
                    for (Sensor sensor : mSensors) {
                        if (sensor.getId() == deviceId && sensor.getType() == sensorType) {
                            mListener.onAccuracyChanged(sensor, accuracy);
                        }
                    }
                    break;
                }
                case MSG_SENSOR_CHANGED: {
                    SensorEvent event = (SensorEvent) msg.obj;
                    mListener.onSensorChanged(event);
                    break;
                }
            }
        }
    }

    /**
     * Return the default sensor object for input device, for specific sensor type.
     */
    private Sensor getSensorForInputDevice(int deviceId, int type) {
        synchronized (mInputSensorLock) {
            for (Map.Entry<Integer, List<Sensor>> entry : mSensors.entrySet()) {
                for (Sensor sensor : entry.getValue()) {
                    if (sensor.getId() == deviceId && sensor.getType() == type) {
                        if (DEBUG) {
                            Slog.d(TAG, "Device " + deviceId + " sensor " + sensor.getStringType());
                        }
                        return sensor;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Return list of sensors that belong to an input device, specified by input device ID.
     */
    private List<Sensor> getFullSensorListForDevice(int deviceId) {
        List<Sensor> sensors = new ArrayList<Sensor>();
        synchronized (mInputSensorLock) {
            for (Map.Entry<Integer, List<Sensor>> entry : mSensors.entrySet()) {
                for (Sensor sensor : entry.getValue()) {
                    if (sensor.getId() == deviceId) {
                        if (DEBUG) {
                            Slog.d(TAG, "Device " + deviceId + " sensor " + sensor.getStringType());
                        }
                        sensors.add(sensor);
                    }
                }
            }
        }
        return sensors;
    }

    private boolean registerListenerInternal(SensorEventListener listener, Sensor sensor,
            int delayUs, int maxBatchReportLatencyUs, Handler handler) {
        if (DEBUG) {
            Slog.d(TAG, "registerListenerImpl listener=" + listener + " sensor=" + sensor
                    + " delayUs=" + delayUs
                    + " maxBatchReportLatencyUs=" + maxBatchReportLatencyUs);
        }
        if (listener == null) {
            Slog.e(TAG, "listener is null");
            return false;
        }

        if (sensor == null) {
            Slog.e(TAG, "sensor is null");
            return false;
        }

        // Trigger Sensors should use the requestTriggerSensor call.
        if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            Slog.e(TAG, "Trigger Sensors should use the requestTriggerSensor.");
            return false;
        }

        if (maxBatchReportLatencyUs < 0 || delayUs < 0) {
            Slog.e(TAG, "maxBatchReportLatencyUs and delayUs should be non-negative");
            return false;
        }

        synchronized (mInputSensorLock) {
            if (getSensorForInputDevice(sensor.getId(), sensor.getType()) != null) {
                final int deviceId = sensor.getId();
                final InputDevice inputDevice = mGlobal.getInputDevice(deviceId);
                if (inputDevice == null) {
                    Slog.e(TAG, "input device not found for sensor " + sensor.getId());
                    return false;
                }

                if (!inputDevice.hasSensor()) {
                    Slog.e(TAG, "The device doesn't have the sensor:" + sensor);
                    return false;
                }
                if (!mGlobal.enableSensor(deviceId, sensor.getType(), delayUs,
                        maxBatchReportLatencyUs)) {
                    Slog.e(TAG, "Can't enable the sensor:" + sensor);
                    return false;
                }
            }

            // Register the InputManagerService sensor listener if not yet.
            if (mInputServiceSensorListener == null) {
                mInputServiceSensorListener = new InputSensorEventListener();
                if (!mGlobal.registerSensorListener(mInputServiceSensorListener)) {
                    Slog.e(TAG, "Failed registering the sensor listener");
                    return false;
                }
            }

            int idx = findSensorEventListenerLocked(listener);
            if (idx < 0) {
                InputSensorEventListenerDelegate d =
                        new InputSensorEventListenerDelegate(listener, sensor,
                                getLooperForListenerLocked(handler));
                mInputSensorEventListeners.add(d);
            } else {
                // The listener is already registered, see if it wants to listen to more sensors.
                mInputSensorEventListeners.get(idx).addSensor(sensor);
            }
        }

        return true;
    }

    @GuardedBy("mInputSensorLock")
    @NonNull
    private Looper getLooperForListenerLocked(@Nullable Handler requestedHandler) {
        if (requestedHandler != null) {
            return requestedHandler.getLooper();
        }
        if (mSensorThread == null) {
            mSensorThread = new HandlerThread("SensorThread");
            mSensorThread.start();
        }
        return mSensorThread.getLooper();
    }

    private void unregisterListenerInternal(SensorEventListener listener, Sensor sensor) {
        if (DEBUG) {
            Slog.d(TAG, "unregisterListenerImpl listener=" + listener + " sensor=" + sensor);
        }
        if (listener == null) {  // it's OK for the sensor to be null
            throw new IllegalArgumentException("listener must not be null");
        }
        synchronized (mInputSensorLock) {
            int idx = findSensorEventListenerLocked(listener);
            // Track the sensor types and the device Id the listener has registered.
            final List<Sensor> sensorsRegistered;
            if (idx >= 0) {
                InputSensorEventListenerDelegate delegate =
                        mInputSensorEventListeners.get(idx);
                sensorsRegistered = new ArrayList<>(delegate.getSensors());
                // Get the sensor types the listener is listening to
                delegate.removeSensor(sensor);
                if (delegate.isEmpty()) {
                    // If no sensors to listen, remove the listener delegate
                    mInputSensorEventListeners.remove(idx);
                }
            } else {
                Slog.e(TAG, "Listener is not registered");
                return;
            }
            // If no delegation remains, unregister the listener to input service
            if (mInputServiceSensorListener != null && mInputSensorEventListeners.isEmpty()) {
                mGlobal.unregisterSensorListener(mInputServiceSensorListener);
                mInputServiceSensorListener = null;
            }
            // For each sensor type check if it is still in use by other listeners.
            for (Sensor s : sensorsRegistered) {
                final int deviceId = s.getId();
                final int sensorType = s.getType();
                // See if we can disable the sensor
                boolean enableSensor = false;
                for (int i = 0; i < mInputSensorEventListeners.size(); i++) {
                    InputSensorEventListenerDelegate delegate =
                            mInputSensorEventListeners.get(i);
                    if (delegate.hasSensorRegistered(deviceId, sensorType)) {
                        enableSensor = true;
                        Slog.w(TAG, "device " + deviceId + " still uses sensor " + sensorType);
                        break;
                    }
                }
                // Sensor is not listened, disable it.
                if (!enableSensor) {
                    if (DEBUG) {
                        Slog.d(TAG, "device " + deviceId + " sensor " + sensorType + " disabled");
                    }
                    mGlobal.disableSensor(deviceId, sensorType);
                }
            }
        }
    }

    private boolean flushInternal(SensorEventListener listener) {
        synchronized (mInputSensorLock) {
            int idx = findSensorEventListenerLocked(listener);
            if (idx < 0) {
                return false;
            }
            for (Sensor sensor : mInputSensorEventListeners.get(idx).getSensors()) {
                final int deviceId = sensor.getId();
                if (!mGlobal.flushSensor(deviceId, sensor.getType())) {
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * Sensor Manager class associated with specific input device
     */
    public class InputSensorManager extends SensorManager {
        // Input device ID that the sensors belong to
        final int mId;

        InputSensorManager(int deviceId) {
            mId = deviceId;
        }

        @Override
        public Sensor getDefaultSensor(int type) {
            return getSensorForInputDevice(mId, type);
        }

        @Override
        protected List<Sensor> getFullSensorList() {
            return getFullSensorListForDevice(mId);
        }

        @Override
        protected List<Sensor> getFullDynamicSensorList() {
            return new ArrayList<>();
        }

        @Override
        protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
                int delayUs, Handler handler, int maxBatchReportLatencyUs, int reservedFlags) {
            return registerListenerInternal(listener, sensor, delayUs,
                    maxBatchReportLatencyUs, handler);
        }

        @Override
        protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
            unregisterListenerInternal(listener, sensor);
        }

        @Override
        protected boolean flushImpl(SensorEventListener listener) {
            return flushInternal(listener);
        }

        @Override
        protected SensorDirectChannel createDirectChannelImpl(MemoryFile memoryFile,
                HardwareBuffer hardwareBuffer) {
            return null;
        }

        @Override
        protected void destroyDirectChannelImpl(SensorDirectChannel channel) {

        }

        @Override
        protected int configureDirectChannelImpl(SensorDirectChannel channel, Sensor s, int rate) {
            return 0;
        }

        @Override
        protected void registerDynamicSensorCallbackImpl(DynamicSensorCallback callback,
                Handler handler) {

        }

        @Override
        protected void unregisterDynamicSensorCallbackImpl(
                DynamicSensorCallback callback) {

        }

        @Override
        protected boolean requestTriggerSensorImpl(TriggerEventListener listener, Sensor sensor) {
            return true;
        }

        @Override
        protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor,
                boolean disable) {
            return true;
        }

        @Override
        protected boolean initDataInjectionImpl(boolean enable, int mode) {
            return false;
        }

        @Override
        protected boolean injectSensorDataImpl(Sensor sensor, float[] values, int accuracy,
                long timestamp) {
            return false;
        }

        @Override
        protected boolean setOperationParameterImpl(SensorAdditionalInfo parameter) {
            return false;
        }
    }

}
