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

import static android.companion.virtual.VirtualDeviceManager.ACTION_VIRTUAL_DEVICE_REMOVED;
import static android.companion.virtual.VirtualDeviceManager.EXTRA_VIRTUAL_DEVICE_ID;
import static android.companion.virtual.VirtualDeviceParams.DEVICE_POLICY_DEFAULT;
import static android.companion.virtual.VirtualDeviceParams.POLICY_TYPE_SENSORS;
import static android.content.Context.DEVICE_ID_DEFAULT;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.companion.virtual.VirtualDeviceManager;
import android.compat.Compatibility;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledAfter;
import android.compat.annotation.UnsupportedAppUsage;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.MemoryFile;
import android.os.MessageQueue;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.annotations.GuardedBy;

import dalvik.system.CloseGuard;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Sensor manager implementation that communicates with the built-in
 * system sensors.
 *
 * @hide
 */
public class SystemSensorManager extends SensorManager {
    //TODO: disable extra logging before release
    private static final boolean DEBUG_DYNAMIC_SENSOR = true;
    private static final int MIN_DIRECT_CHANNEL_BUFFER_SIZE = 104;
    private static final int MAX_LISTENER_COUNT = 128;
    private static final int CAPPED_SAMPLING_PERIOD_US = 5000;
    private static final int CAPPED_SAMPLING_RATE_LEVEL = SensorDirectChannel.RATE_NORMAL;

    private static final String HIGH_SAMPLING_RATE_SENSORS_PERMISSION =
                                        "android.permission.HIGH_SAMPLING_RATE_SENSORS";
    /**
     * For apps targeting S and above, a SecurityException is thrown when they do not have
     * HIGH_SAMPLING_RATE_SENSORS permission, run in debug mode, and request sampling rates that
     * are faster than 200 Hz.
     */
    @ChangeId
    @EnabledAfter(targetSdkVersion = Build.VERSION_CODES.R)
    static final long CHANGE_ID_SAMPLING_RATE_SENSORS_PERMISSION = 136069189L;

    private static native void nativeClassInit();
    private static native long nativeCreate(String opPackageName);
    private static native boolean nativeGetSensorAtIndex(long nativeInstance,
            Sensor sensor, int index);
    private static native boolean nativeGetDefaultDeviceSensorAtIndex(long nativeInstance,
            Sensor sensor, int index);
    private static native void nativeGetDynamicSensors(long nativeInstance, List<Sensor> list);
    private static native void nativeGetRuntimeSensors(
            long nativeInstance, int deviceId, List<Sensor> list);
    private static native boolean nativeIsDataInjectionEnabled(long nativeInstance);
    private static native boolean nativeIsReplayDataInjectionEnabled(long nativeInstance);
    private static native boolean nativeIsHalBypassReplayDataInjectionEnabled(long nativeInstance);

    private static native int nativeCreateDirectChannel(
            long nativeInstance, int deviceId, long size, int channelType, int fd,
            HardwareBuffer buffer);
    private static native void nativeDestroyDirectChannel(
            long nativeInstance, int channelHandle);
    private static native int nativeConfigDirectChannel(
            long nativeInstance, int channelHandle, int sensorHandle, int rate);

    private static native int nativeSetOperationParameter(
            long nativeInstance, int handle, int type, float[] floatValues, int[] intValues);

    private static final Object sLock = new Object();
    @GuardedBy("sLock")
    private static boolean sNativeClassInited = false;
    @GuardedBy("sLock")
    private static InjectEventQueue sInjectEventQueue = null;

    private final ArrayList<Sensor> mFullSensorsList = new ArrayList<>();
    private List<Sensor> mFullDynamicSensorsList = new ArrayList<>();
    private final SparseArray<List<Sensor>> mFullRuntimeSensorListByDevice = new SparseArray<>();
    private final SparseArray<SparseArray<List<Sensor>>> mRuntimeSensorListByDeviceByType =
            new SparseArray<>();

    private boolean mDynamicSensorListDirty = true;

    private final HashMap<Integer, Sensor> mHandleToSensor = new HashMap<>();

    // Listener list
    private final HashMap<SensorEventListener, SensorEventQueue> mSensorListeners =
            new HashMap<SensorEventListener, SensorEventQueue>();
    private final HashMap<TriggerEventListener, TriggerEventQueue> mTriggerListeners =
            new HashMap<TriggerEventListener, TriggerEventQueue>();

    // Dynamic Sensor callbacks
    private HashMap<DynamicSensorCallback, Handler>
            mDynamicSensorCallbacks = new HashMap<>();
    private BroadcastReceiver mDynamicSensorBroadcastReceiver;
    private BroadcastReceiver mRuntimeSensorBroadcastReceiver;
    private VirtualDeviceManager.VirtualDeviceListener mVirtualDeviceListener;

    // Looper associated with the context in which this instance was created.
    private final Looper mMainLooper;
    private final int mTargetSdkLevel;
    private final boolean mIsPackageDebuggable;
    private final Context mContext;
    private final long mNativeInstance;
    private VirtualDeviceManager mVdm;

    private Optional<Boolean> mHasHighSamplingRateSensorsPermission = Optional.empty();

    /** {@hide} */
    public SystemSensorManager(Context context, Looper mainLooper) {
        synchronized (sLock) {
            if (!sNativeClassInited) {
                sNativeClassInited = true;
                nativeClassInit();
            }
        }

        mMainLooper = mainLooper;
        ApplicationInfo appInfo = context.getApplicationInfo();
        mTargetSdkLevel = appInfo.targetSdkVersion;
        mContext = context;
        mNativeInstance = nativeCreate(context.getOpPackageName());
        mIsPackageDebuggable = (0 != (appInfo.flags & ApplicationInfo.FLAG_DEBUGGABLE));

        // initialize the sensor list
        for (int index = 0;; ++index) {
            Sensor sensor = new Sensor();
            if (android.companion.virtual.flags.Flags.enableNativeVdm()) {
                if (!nativeGetDefaultDeviceSensorAtIndex(mNativeInstance, sensor, index)) break;
            } else {
                if (!nativeGetSensorAtIndex(mNativeInstance, sensor, index)) break;
            }
            mFullSensorsList.add(sensor);
            mHandleToSensor.put(sensor.getHandle(), sensor);
        }
    }

    /** @hide */
    @Override
    public List<Sensor> getSensorList(int type) {
        final int deviceId = mContext.getDeviceId();
        if (isDeviceSensorPolicyDefault(deviceId)) {
            return super.getSensorList(type);
        }

        // Cache the per-device lists on demand.
        List<Sensor> list;
        synchronized (mFullRuntimeSensorListByDevice) {
            List<Sensor> fullList = mFullRuntimeSensorListByDevice.get(deviceId);
            if (fullList == null) {
                fullList = createRuntimeSensorListLocked(deviceId);
            }
            SparseArray<List<Sensor>> deviceSensorListByType =
                    mRuntimeSensorListByDeviceByType.get(deviceId);
            list = deviceSensorListByType.get(type);
            if (list == null) {
                if (type == Sensor.TYPE_ALL) {
                    list = fullList;
                } else {
                    list = new ArrayList<>();
                    for (Sensor i : fullList) {
                        if (i.getType() == type) {
                            list.add(i);
                        }
                    }
                }
                list = Collections.unmodifiableList(list);
                deviceSensorListByType.append(type, list);
            }
        }
        return list;
    }

    /** @hide */
    @Override
    protected List<Sensor> getFullSensorList() {
        final int deviceId = mContext.getDeviceId();
        if (isDeviceSensorPolicyDefault(deviceId)) {
            return mFullSensorsList;
        }

        List<Sensor> fullList;
        synchronized (mFullRuntimeSensorListByDevice) {
            fullList = mFullRuntimeSensorListByDevice.get(deviceId);
            if (fullList == null) {
                fullList = createRuntimeSensorListLocked(deviceId);
            }
        }
        return fullList;
    }

    /** @hide */
    @Override
    public Sensor getSensorByHandle(int sensorHandle) {
        return mHandleToSensor.get(sensorHandle);
    }

    /** @hide */
    @Override
    protected List<Sensor> getFullDynamicSensorList() {
        // only set up broadcast receiver if the application tries to find dynamic sensors or
        // explicitly register a DynamicSensorCallback
        setupDynamicSensorBroadcastReceiver();
        updateDynamicSensorList();
        return mFullDynamicSensorsList;
    }

    /** @hide */
    @Override
    protected boolean registerListenerImpl(SensorEventListener listener, Sensor sensor,
            int delayUs, Handler handler, int maxBatchReportLatencyUs, int reservedFlags) {
        if (listener == null || sensor == null) {
            Log.e(TAG, "sensor or listener is null");
            return false;
        }
        // Trigger Sensors should use the requestTriggerSensor call.
        if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            Log.e(TAG, "Trigger Sensors should use the requestTriggerSensor.");
            return false;
        }
        if (maxBatchReportLatencyUs < 0 || delayUs < 0) {
            Log.e(TAG, "maxBatchReportLatencyUs and delayUs should be non-negative");
            return false;
        }
        if (mSensorListeners.size() >= MAX_LISTENER_COUNT) {
            throw new IllegalStateException("register failed, "
                + "the sensor listeners size has exceeded the maximum limit "
                + MAX_LISTENER_COUNT);
        }

        // Invariants to preserve:
        // - one Looper per SensorEventListener
        // - one Looper per SensorEventQueue
        // We map SensorEventListener to a SensorEventQueue, which holds the looper
        synchronized (mSensorListeners) {
            SensorEventQueue queue = mSensorListeners.get(listener);
            if (queue == null) {
                Looper looper = (handler != null) ? handler.getLooper() : mMainLooper;
                final String fullClassName =
                        listener.getClass().getEnclosingClass() != null
                            ? listener.getClass().getEnclosingClass().getName()
                            : listener.getClass().getName();
                queue = new SensorEventQueue(listener, looper, this, fullClassName);
                if (!queue.addSensor(sensor, delayUs, maxBatchReportLatencyUs)) {
                    queue.dispose();
                    return false;
                }
                mSensorListeners.put(listener, queue);
                return true;
            } else {
                return queue.addSensor(sensor, delayUs, maxBatchReportLatencyUs);
            }
        }
    }

    /** @hide */
    @Override
    protected void unregisterListenerImpl(SensorEventListener listener, Sensor sensor) {
        // Trigger Sensors should use the cancelTriggerSensor call.
        if (sensor != null && sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
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

        if (listener == null) throw new IllegalArgumentException("listener cannot be null");

        if (sensor.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) return false;

        if (mTriggerListeners.size() >= MAX_LISTENER_COUNT) {
            throw new IllegalStateException("request failed, "
                    + "the trigger listeners size has exceeded the maximum limit "
                    + MAX_LISTENER_COUNT);
        }

        synchronized (mTriggerListeners) {
            TriggerEventQueue queue = mTriggerListeners.get(listener);
            if (queue == null) {
                final String fullClassName =
                        listener.getClass().getEnclosingClass() != null
                            ? listener.getClass().getEnclosingClass().getName()
                            : listener.getClass().getName();
                queue = new TriggerEventQueue(listener, mMainLooper, this, fullClassName);
                if (!queue.addSensor(sensor, 0, 0)) {
                    queue.dispose();
                    return false;
                }
                mTriggerListeners.put(listener, queue);
                return true;
            } else {
                return queue.addSensor(sensor, 0, 0);
            }
        }
    }

    /** @hide */
    @Override
    protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor,
            boolean disable) {
        if (sensor != null && sensor.getReportingMode() != Sensor.REPORTING_MODE_ONE_SHOT) {
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

    protected boolean flushImpl(SensorEventListener listener) {
        if (listener == null) throw new IllegalArgumentException("listener cannot be null");

        synchronized (mSensorListeners) {
            SensorEventQueue queue = mSensorListeners.get(listener);
            if (queue == null) {
                return false;
            } else {
                return (queue.flush() == 0);
            }
        }
    }

    protected boolean initDataInjectionImpl(boolean enable, @DataInjectionMode int mode) {
        synchronized (sLock) {
            boolean isDataInjectionModeEnabled = false;
            if (enable) {
                switch (mode) {
                    case DATA_INJECTION:
                        isDataInjectionModeEnabled = nativeIsDataInjectionEnabled(mNativeInstance);
                        break;
                    case REPLAY_DATA_INJECTION:
                        isDataInjectionModeEnabled = nativeIsReplayDataInjectionEnabled(
                                mNativeInstance);
                        break;
                    case HAL_BYPASS_REPLAY_DATA_INJECTION:
                        isDataInjectionModeEnabled = nativeIsHalBypassReplayDataInjectionEnabled(
                                mNativeInstance);
                        break;
                    default:
                        break;
                }
                // The HAL does not support injection OR SensorService hasn't been set in DI mode.
                if (!isDataInjectionModeEnabled) {
                    Log.e(TAG, "The correct Data Injection mode has not been enabled");
                    return false;
                }
                if (sInjectEventQueue != null && sInjectEventQueue.getDataInjectionMode() != mode) {
                    // The inject event queue has been initialized for a different type of DI
                    // close it and create a new one
                    sInjectEventQueue.dispose();
                    sInjectEventQueue = null;
                }
                // Initialize a client for data_injection.
                if (sInjectEventQueue == null) {
                    try {
                        sInjectEventQueue = new InjectEventQueue(
                                mMainLooper, this, mode, mContext.getPackageName());
                    } catch (RuntimeException e) {
                        Log.e(TAG, "Cannot create InjectEventQueue: " + e);
                    }
                }
                return sInjectEventQueue != null;
            } else {
                // If data injection is being disabled clean up the native resources.
                if (sInjectEventQueue != null) {
                    sInjectEventQueue.dispose();
                    sInjectEventQueue = null;
                }
                return true;
            }
        }
    }

    protected boolean injectSensorDataImpl(Sensor sensor, float[] values, int accuracy,
            long timestamp) {
        synchronized (sLock) {
            if (sInjectEventQueue == null) {
                Log.e(TAG, "Data injection mode not activated before calling injectSensorData");
                return false;
            }
            if (sInjectEventQueue.getDataInjectionMode() != HAL_BYPASS_REPLAY_DATA_INJECTION
                    && !sensor.isDataInjectionSupported()) {
                // DI mode and Replay DI mode require support from the sensor HAL
                // HAL Bypass mode doesn't require this.
                throw new IllegalArgumentException("sensor does not support data injection");
            }
            int ret = sInjectEventQueue.injectSensorData(sensor.getHandle(), values, accuracy,
                                                         timestamp);
            // If there are any errors in data injection clean up the native resources.
            if (ret != 0) {
                sInjectEventQueue.dispose();
                sInjectEventQueue = null;
            }
            return ret == 0;
        }
    }

    private void cleanupSensorConnection(Sensor sensor) {
        mHandleToSensor.remove(sensor.getHandle());

        if (sensor.getReportingMode() == Sensor.REPORTING_MODE_ONE_SHOT) {
            synchronized (mTriggerListeners) {
                HashMap<TriggerEventListener, TriggerEventQueue> triggerListeners =
                        new HashMap<TriggerEventListener, TriggerEventQueue>(mTriggerListeners);

                for (TriggerEventListener l : triggerListeners.keySet()) {
                    if (DEBUG_DYNAMIC_SENSOR) {
                        Log.i(TAG, "removed trigger listener" + l.toString()
                                + " due to sensor disconnection");
                    }
                    cancelTriggerSensorImpl(l, sensor, true);
                }
            }
        } else {
            synchronized (mSensorListeners) {
                HashMap<SensorEventListener, SensorEventQueue> sensorListeners =
                        new HashMap<SensorEventListener, SensorEventQueue>(mSensorListeners);

                for (SensorEventListener l: sensorListeners.keySet()) {
                    if (DEBUG_DYNAMIC_SENSOR) {
                        Log.i(TAG, "removed event listener" + l.toString()
                                + " due to sensor disconnection");
                    }
                    unregisterListenerImpl(l, sensor);
                }
            }
        }
    }

    private void updateDynamicSensorList() {
        synchronized (mFullDynamicSensorsList) {
            if (mDynamicSensorListDirty) {
                List<Sensor> list = new ArrayList<>();
                nativeGetDynamicSensors(mNativeInstance, list);

                final List<Sensor> updatedList = new ArrayList<>();
                final List<Sensor> addedList = new ArrayList<>();
                final List<Sensor> removedList = new ArrayList<>();

                boolean changed = diffSortedSensorList(
                        mFullDynamicSensorsList, list, updatedList, addedList, removedList);

                if (changed) {
                    if (DEBUG_DYNAMIC_SENSOR) {
                        Log.i(TAG, "DYNS dynamic sensor list cached should be updated");
                    }
                    mFullDynamicSensorsList = updatedList;

                    for (Sensor s: addedList) {
                        mHandleToSensor.put(s.getHandle(), s);
                    }

                    Handler mainHandler = new Handler(mContext.getMainLooper());

                    synchronized (mDynamicSensorCallbacks) {
                        for (Map.Entry<DynamicSensorCallback, Handler> entry :
                                mDynamicSensorCallbacks.entrySet()) {
                            final DynamicSensorCallback callback = entry.getKey();
                            Handler handler =
                                    entry.getValue() == null ? mainHandler : entry.getValue();

                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    for (Sensor s: addedList) {
                                        callback.onDynamicSensorConnected(s);
                                    }
                                    for (Sensor s: removedList) {
                                        callback.onDynamicSensorDisconnected(s);
                                    }
                                }
                            });
                        }
                    }

                    for (Sensor s: removedList) {
                        cleanupSensorConnection(s);
                    }
                }

                mDynamicSensorListDirty = false;
            }
        }
    }

    private List<Sensor> createRuntimeSensorListLocked(int deviceId) {
        if (android.companion.virtual.flags.Flags.vdmPublicApis()) {
            setupVirtualDeviceListener();
        } else {
            setupRuntimeSensorBroadcastReceiver();
        }
        List<Sensor> list = new ArrayList<>();
        nativeGetRuntimeSensors(mNativeInstance, deviceId, list);
        mFullRuntimeSensorListByDevice.put(deviceId, list);
        mRuntimeSensorListByDeviceByType.put(deviceId, new SparseArray<>());
        for (Sensor s : list) {
            mHandleToSensor.put(s.getHandle(), s);
        }
        return list;
    }

    private void setupRuntimeSensorBroadcastReceiver() {
        if (mRuntimeSensorBroadcastReceiver == null) {
            mRuntimeSensorBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(ACTION_VIRTUAL_DEVICE_REMOVED)) {
                        synchronized (mFullRuntimeSensorListByDevice) {
                            final int deviceId = intent.getIntExtra(
                                    EXTRA_VIRTUAL_DEVICE_ID, DEVICE_ID_DEFAULT);
                            List<Sensor> removedSensors =
                                    mFullRuntimeSensorListByDevice.removeReturnOld(deviceId);
                            if (removedSensors != null) {
                                for (Sensor s : removedSensors) {
                                    cleanupSensorConnection(s);
                                }
                            }
                            mRuntimeSensorListByDeviceByType.remove(deviceId);
                        }
                    }
                }
            };

            IntentFilter filter = new IntentFilter("virtual_device_removed");
            filter.addAction(ACTION_VIRTUAL_DEVICE_REMOVED);
            mContext.registerReceiver(mRuntimeSensorBroadcastReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }

    private void setupVirtualDeviceListener() {
        if (mVirtualDeviceListener != null) {
            return;
        }
        if (mVdm == null) {
            mVdm = mContext.getSystemService(VirtualDeviceManager.class);
            if (mVdm == null) {
                return;
            }
        }
        mVirtualDeviceListener = new VirtualDeviceManager.VirtualDeviceListener() {
            @Override
            public void onVirtualDeviceClosed(int deviceId) {
                synchronized (mFullRuntimeSensorListByDevice) {
                    List<Sensor> removedSensors =
                            mFullRuntimeSensorListByDevice.removeReturnOld(deviceId);
                    if (removedSensors != null) {
                        for (Sensor s : removedSensors) {
                            cleanupSensorConnection(s);
                        }
                    }
                    mRuntimeSensorListByDeviceByType.remove(deviceId);
                }
            }
        };
        mVdm.registerVirtualDeviceListener(mContext.getMainExecutor(), mVirtualDeviceListener);
    }

    private void setupDynamicSensorBroadcastReceiver() {
        if (mDynamicSensorBroadcastReceiver == null) {
            mDynamicSensorBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (intent.getAction().equals(Intent.ACTION_DYNAMIC_SENSOR_CHANGED)) {
                        if (DEBUG_DYNAMIC_SENSOR) {
                            Log.i(TAG, "DYNS received DYNAMIC_SENSOR_CHANGED broadcast");
                        }
                        // Dynamic sensors probably changed
                        mDynamicSensorListDirty = true;
                        updateDynamicSensorList();
                    }
                }
            };

            IntentFilter filter = new IntentFilter("dynamic_sensor_change");
            filter.addAction(Intent.ACTION_DYNAMIC_SENSOR_CHANGED);
            mContext.registerReceiver(mDynamicSensorBroadcastReceiver, filter,
                    Context.RECEIVER_NOT_EXPORTED);
        }
    }

    /** @hide */
    protected void registerDynamicSensorCallbackImpl(
            DynamicSensorCallback callback, Handler handler) {
        if (DEBUG_DYNAMIC_SENSOR) {
            Log.i(TAG, "DYNS Register dynamic sensor callback");
        }

        if (callback == null) {
            throw new IllegalArgumentException("callback cannot be null");
        }
        synchronized (mDynamicSensorCallbacks) {
            if (mDynamicSensorCallbacks.containsKey(callback)) {
                // has been already registered, ignore
                return;
            }

            setupDynamicSensorBroadcastReceiver();
            mDynamicSensorCallbacks.put(callback, handler);
        }
    }

    /** @hide */
    protected void unregisterDynamicSensorCallbackImpl(
            DynamicSensorCallback callback) {
        if (DEBUG_DYNAMIC_SENSOR) {
            Log.i(TAG, "Removing dynamic sensor listener");
        }
        synchronized (mDynamicSensorCallbacks) {
            mDynamicSensorCallbacks.remove(callback);
        }
    }

    /*
     * Find the difference of two List<Sensor> assuming List are sorted by handle of sensor,
     * assuming the input list is already sorted by handle. Inputs are ol and nl; outputs are
     * updated, added and removed. Any of the output lists can be null in case the result is not
     * interested.
     */
    private static boolean diffSortedSensorList(
            List<Sensor> oldList, List<Sensor> newList, List<Sensor> updated,
            List<Sensor> added, List<Sensor> removed) {

        boolean changed = false;

        int i = 0, j = 0;
        while (true) {
            if (j < oldList.size() && (i >= newList.size()
                    || newList.get(i).getHandle() > oldList.get(j).getHandle())) {
                changed = true;
                if (removed != null) {
                    removed.add(oldList.get(j));
                }
                ++j;
            } else if (i < newList.size() && (j >= oldList.size()
                    || newList.get(i).getHandle() < oldList.get(j).getHandle())) {
                changed = true;
                if (added != null) {
                    added.add(newList.get(i));
                }
                if (updated != null) {
                    updated.add(newList.get(i));
                }
                ++i;
            } else if (i < newList.size() && j < oldList.size()
                    && newList.get(i).getHandle() == oldList.get(j).getHandle()) {
                if (updated != null) {
                    updated.add(oldList.get(j));
                }
                ++i;
                ++j;
            } else {
                break;
            }
        }
        return changed;
    }

    /** @hide */
    protected int configureDirectChannelImpl(
            SensorDirectChannel channel, Sensor sensor, int rate) {
        if (!channel.isOpen()) {
            throw new IllegalStateException("channel is closed");
        }

        if (rate < SensorDirectChannel.RATE_STOP
                || rate > SensorDirectChannel.RATE_VERY_FAST) {
            throw new IllegalArgumentException("rate parameter invalid");
        }

        if (sensor == null && rate != SensorDirectChannel.RATE_STOP) {
            // the stop all sensors case
            throw new IllegalArgumentException(
                    "when sensor is null, rate can only be DIRECT_RATE_STOP");
        }

        int sensorHandle = (sensor == null) ? -1 : sensor.getHandle();
        if (sensor != null
                && isSensorInCappedSet(sensor.getType())
                && rate > CAPPED_SAMPLING_RATE_LEVEL
                && mIsPackageDebuggable
                && !hasHighSamplingRateSensorsPermission()
                && Compatibility.isChangeEnabled(CHANGE_ID_SAMPLING_RATE_SENSORS_PERMISSION)) {
            throw new SecurityException("To use the sampling rate level " + rate
                    + ", app needs to declare the normal permission"
                    + " HIGH_SAMPLING_RATE_SENSORS.");
        }

        int ret = nativeConfigDirectChannel(
                mNativeInstance, channel.getNativeHandle(), sensorHandle, rate);
        if (rate == SensorDirectChannel.RATE_STOP) {
            return (ret == 0) ? 1 : 0;
        } else {
            return (ret > 0) ? ret : 0;
        }
    }

    /** @hide */
    protected SensorDirectChannel createDirectChannelImpl(
            MemoryFile memoryFile, HardwareBuffer hardwareBuffer) {
        int deviceId = mContext.getDeviceId();
        if (isDeviceSensorPolicyDefault(deviceId)) {
            deviceId = DEVICE_ID_DEFAULT;
        }
        int id;
        int type;
        long size;
        if (memoryFile != null) {
            int fd;
            try {
                fd = memoryFile.getFileDescriptor().getInt$();
            } catch (IOException e) {
                throw new IllegalArgumentException("MemoryFile object is not valid");
            }

            if (memoryFile.length() < MIN_DIRECT_CHANNEL_BUFFER_SIZE) {
                throw new IllegalArgumentException(
                        "Size of MemoryFile has to be greater than "
                        + MIN_DIRECT_CHANNEL_BUFFER_SIZE);
            }

            size = memoryFile.length();
            id = nativeCreateDirectChannel(mNativeInstance, deviceId, size,
                    SensorDirectChannel.TYPE_MEMORY_FILE, fd, null);
            if (id <= 0) {
                throw new UncheckedIOException(
                        new IOException("create MemoryFile direct channel failed " + id));
            }
            type = SensorDirectChannel.TYPE_MEMORY_FILE;
        } else if (hardwareBuffer != null) {
            if (hardwareBuffer.getFormat() != HardwareBuffer.BLOB) {
                throw new IllegalArgumentException("Format of HardwareBuffer must be BLOB");
            }
            if (hardwareBuffer.getHeight() != 1) {
                throw new IllegalArgumentException("Height of HardwareBuffer must be 1");
            }
            if (hardwareBuffer.getWidth() < MIN_DIRECT_CHANNEL_BUFFER_SIZE) {
                throw new IllegalArgumentException(
                        "Width if HardwareBuffer must be greater than "
                        + MIN_DIRECT_CHANNEL_BUFFER_SIZE);
            }
            if ((hardwareBuffer.getUsage() & HardwareBuffer.USAGE_SENSOR_DIRECT_DATA) == 0) {
                throw new IllegalArgumentException(
                        "HardwareBuffer must set usage flag USAGE_SENSOR_DIRECT_DATA");
            }
            size = hardwareBuffer.getWidth();
            id = nativeCreateDirectChannel(
                    mNativeInstance, deviceId, size, SensorDirectChannel.TYPE_HARDWARE_BUFFER,
                    -1, hardwareBuffer);
            if (id <= 0) {
                throw new UncheckedIOException(
                        new IOException("create HardwareBuffer direct channel failed " + id));
            }
            type = SensorDirectChannel.TYPE_HARDWARE_BUFFER;
        } else {
            throw new NullPointerException("shared memory object cannot be null");
        }
        return new SensorDirectChannel(this, id, type, size);
    }

    /** @hide */
    protected void destroyDirectChannelImpl(SensorDirectChannel channel) {
        if (channel != null) {
            nativeDestroyDirectChannel(mNativeInstance, channel.getNativeHandle());
        }
    }

    /*
     * BaseEventQueue is the communication channel with the sensor service,
     * SensorEventQueue, TriggerEventQueue are subclasses and there is one-to-one mapping between
     * the queues and the listeners. InjectEventQueue is also a sub-class which is a special case
     * where data is being injected into the sensor HAL through the sensor service. It is not
     * associated with any listener and there is one InjectEventQueue associated with a
     * SensorManager instance.
     */
    private abstract static class BaseEventQueue {
        private static native long nativeInitBaseEventQueue(long nativeManager,
                WeakReference<BaseEventQueue> eventQWeak, MessageQueue msgQ,
                String packageName, int mode, String opPackageName, String attributionTag);
        private static native int nativeEnableSensor(long eventQ, int handle, int rateUs,
                int maxBatchReportLatencyUs);
        private static native int nativeDisableSensor(long eventQ, int handle);
        private static native void nativeDestroySensorEventQueue(long eventQ);
        private static native int nativeFlushSensor(long eventQ);
        private static native int nativeInjectSensorData(long eventQ, int handle,
                float[] values, int accuracy, long timestamp);

        private long mNativeSensorEventQueue;
        private final SparseBooleanArray mActiveSensors = new SparseBooleanArray();
        protected final SparseIntArray mSensorAccuracies = new SparseIntArray();
        protected final SparseIntArray mSensorDiscontinuityCounts = new SparseIntArray();
        private final CloseGuard mCloseGuard = CloseGuard.get();
        protected final SystemSensorManager mManager;

        protected static final int OPERATING_MODE_NORMAL = 0;
        protected static final int OPERATING_MODE_DATA_INJECTION = 1;
        protected static final int OPERATING_MODE_REPLAY_DATA_INJECTION = 3;
        protected static final int OPERATING_MODE_HAL_BYPASS_REPLAY_DATA_INJECTION = 4;

        BaseEventQueue(Looper looper, SystemSensorManager manager, int mode, String packageName) {
            if (packageName == null) packageName = "";
            mNativeSensorEventQueue = nativeInitBaseEventQueue(manager.mNativeInstance,
                    new WeakReference<>(this), looper.getQueue(),
                    packageName, mode, manager.mContext.getOpPackageName(),
                    manager.mContext.getAttributionTag());
            mCloseGuard.open("BaseEventQueue.dispose");
            mManager = manager;
        }

        public void dispose() {
            dispose(false);
        }

        public boolean addSensor(
                Sensor sensor, int delayUs, int maxBatchReportLatencyUs) {
            // Check if already present.
            int handle = sensor.getHandle();
            if (mActiveSensors.get(handle)) return false;

            // Get ready to receive events before calling enable.
            mActiveSensors.put(handle, true);
            addSensorEvent(sensor);
            if (enableSensor(sensor, delayUs, maxBatchReportLatencyUs) != 0) {
                // Try continuous mode if batching fails.
                if (maxBatchReportLatencyUs == 0
                        || maxBatchReportLatencyUs > 0 && enableSensor(sensor, delayUs, 0) != 0) {
                    removeSensor(sensor, false);
                    return false;
                }
            }
            return true;
        }

        public boolean removeAllSensors() {
            for (int i = 0; i < mActiveSensors.size(); i++) {
                if (mActiveSensors.valueAt(i) == true) {
                    int handle = mActiveSensors.keyAt(i);
                    Sensor sensor = mManager.mHandleToSensor.get(handle);
                    if (sensor != null) {
                        disableSensor(sensor);
                        mActiveSensors.put(handle, false);
                        removeSensorEvent(sensor);
                    } else {
                        // sensor just disconnected -- just ignore.
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

        public int flush() {
            if (mNativeSensorEventQueue == 0) throw new NullPointerException();
            return nativeFlushSensor(mNativeSensorEventQueue);
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
            if (mNativeSensorEventQueue != 0) {
                nativeDestroySensorEventQueue(mNativeSensorEventQueue);
                mNativeSensorEventQueue = 0;
            }
        }

        private int enableSensor(
                Sensor sensor, int rateUs, int maxBatchReportLatencyUs) {
            if (mNativeSensorEventQueue == 0) throw new NullPointerException();
            if (sensor == null) throw new NullPointerException();
            if (mManager.isSensorInCappedSet(sensor.getType())
                    && rateUs < CAPPED_SAMPLING_PERIOD_US
                    && mManager.mIsPackageDebuggable
                    && !mManager.hasHighSamplingRateSensorsPermission()
                    && Compatibility.isChangeEnabled(CHANGE_ID_SAMPLING_RATE_SENSORS_PERMISSION)) {
                throw new SecurityException("To use the sampling rate of " + rateUs
                        + " microseconds, app needs to declare the normal permission"
                        + " HIGH_SAMPLING_RATE_SENSORS.");
            }
            return nativeEnableSensor(mNativeSensorEventQueue, sensor.getHandle(), rateUs,
                    maxBatchReportLatencyUs);
        }

        protected int injectSensorDataBase(int handle, float[] values, int accuracy,
                                           long timestamp) {
            return nativeInjectSensorData(
                    mNativeSensorEventQueue, handle, values, accuracy, timestamp);
        }

        private int disableSensor(Sensor sensor) {
            if (mNativeSensorEventQueue == 0) throw new NullPointerException();
            if (sensor == null) throw new NullPointerException();
            return nativeDisableSensor(mNativeSensorEventQueue, sensor.getHandle());
        }
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        protected abstract void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp);
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        protected abstract void dispatchFlushCompleteEvent(int handle);

        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        protected void dispatchAdditionalInfoEvent(
                int handle, int type, int serial, float[] floatValues, int[] intValues) {
            // default implementation is do nothing
        }

        protected abstract void addSensorEvent(Sensor sensor);
        protected abstract void removeSensorEvent(Sensor sensor);
    }

    static final class SensorEventQueue extends BaseEventQueue {
        private final SensorEventListener mListener;
        private final SparseArray<SensorEvent> mSensorsEvents = new SparseArray<SensorEvent>();

        public SensorEventQueue(SensorEventListener listener, Looper looper,
                SystemSensorManager manager, String packageName) {
            super(looper, manager, OPERATING_MODE_NORMAL, packageName);
            mListener = listener;
        }

        @Override
        public void addSensorEvent(Sensor sensor) {
            SensorEvent t = new SensorEvent(Sensor.getMaxLengthValuesArray(sensor,
                    mManager.mTargetSdkLevel));
            synchronized (mSensorsEvents) {
                mSensorsEvents.put(sensor.getHandle(), t);
            }
        }

        @Override
        public void removeSensorEvent(Sensor sensor) {
            synchronized (mSensorsEvents) {
                mSensorsEvents.delete(sensor.getHandle());
            }
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int inAccuracy,
                long timestamp) {
            final Sensor sensor = mManager.mHandleToSensor.get(handle);
            if (sensor == null) {
                // sensor disconnected
                return;
            }

            SensorEvent t = null;
            synchronized (mSensorsEvents) {
                t = mSensorsEvents.get(handle);
            }

            if (t == null) {
                // This may happen if the client has unregistered and there are pending events in
                // the queue waiting to be delivered. Ignore.
                return;
            }
            // Copy from the values array.
            System.arraycopy(values, 0, t.values, 0, t.values.length);
            t.timestamp = timestamp;
            t.accuracy = inAccuracy;
            t.sensor = sensor;

            // call onAccuracyChanged() only if the value changes
            final int accuracy = mSensorAccuracies.get(handle);
            if (t.accuracy >= 0 && accuracy != t.accuracy) {
                mSensorAccuracies.put(handle, t.accuracy);
                mListener.onAccuracyChanged(t.sensor, t.accuracy);
            }

            // Indicate if the discontinuity count changed
            t.firstEventAfterDiscontinuity = false;
            if (t.sensor.getType() == Sensor.TYPE_HEAD_TRACKER) {
                final int lastCount = mSensorDiscontinuityCounts.get(handle);
                final int curCount = Float.floatToIntBits(values[6]);
                if (lastCount >= 0 && lastCount != curCount) {
                    mSensorDiscontinuityCounts.put(handle, curCount);
                    t.firstEventAfterDiscontinuity = true;
                }
            }

            mListener.onSensorChanged(t);
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchFlushCompleteEvent(int handle) {
            if (mListener instanceof SensorEventListener2) {
                final Sensor sensor = mManager.mHandleToSensor.get(handle);
                if (sensor == null) {
                    // sensor disconnected
                    return;
                }
                ((SensorEventListener2) mListener).onFlushCompleted(sensor);
            }
            return;
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchAdditionalInfoEvent(
                int handle, int type, int serial, float[] floatValues, int[] intValues) {
            if (mListener instanceof SensorEventCallback) {
                final Sensor sensor = mManager.mHandleToSensor.get(handle);
                if (sensor == null) {
                    // sensor disconnected
                    return;
                }
                SensorAdditionalInfo info =
                        new SensorAdditionalInfo(sensor, type, serial, intValues, floatValues);
                ((SensorEventCallback) mListener).onSensorAdditionalInfo(info);
            }
        }
    }

    static final class TriggerEventQueue extends BaseEventQueue {
        private final TriggerEventListener mListener;
        private final SparseArray<TriggerEvent> mTriggerEvents = new SparseArray<TriggerEvent>();

        public TriggerEventQueue(TriggerEventListener listener, Looper looper,
                SystemSensorManager manager, String packageName) {
            super(looper, manager, OPERATING_MODE_NORMAL, packageName);
            mListener = listener;
        }

        @Override
        public void addSensorEvent(Sensor sensor) {
            TriggerEvent t = new TriggerEvent(Sensor.getMaxLengthValuesArray(sensor,
                    mManager.mTargetSdkLevel));
            synchronized (mTriggerEvents) {
                mTriggerEvents.put(sensor.getHandle(), t);
            }
        }

        @Override
        public void removeSensorEvent(Sensor sensor) {
            synchronized (mTriggerEvents) {
                mTriggerEvents.delete(sensor.getHandle());
            }
        }

        // Called from native code.
        @SuppressWarnings("unused")
        @Override
        protected void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp) {
            final Sensor sensor = mManager.mHandleToSensor.get(handle);
            if (sensor == null) {
                // sensor disconnected
                return;
            }
            TriggerEvent t = null;
            synchronized (mTriggerEvents) {
                t = mTriggerEvents.get(handle);
            }
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

        @SuppressWarnings("unused")
        protected void dispatchFlushCompleteEvent(int handle) {
        }
    }

    final class InjectEventQueue extends BaseEventQueue {

        private int mMode;
        public InjectEventQueue(Looper looper, SystemSensorManager manager,
                @DataInjectionMode int mode, String packageName) {
            super(looper, manager, mode, packageName);
            mMode = mode;
        }

        int injectSensorData(int handle, float[] values, int accuracy, long timestamp) {
            return injectSensorDataBase(handle, values, accuracy, timestamp);
        }

        @SuppressWarnings("unused")
        protected void dispatchSensorEvent(int handle, float[] values, int accuracy,
                long timestamp) {
        }

        @SuppressWarnings("unused")
        protected void dispatchFlushCompleteEvent(int handle) {

        }

        @SuppressWarnings("unused")
        protected void addSensorEvent(Sensor sensor) {

        }

        @SuppressWarnings("unused")
        protected void removeSensorEvent(Sensor sensor) {

        }

        int getDataInjectionMode() {
            return mMode;
        }
    }

    protected boolean setOperationParameterImpl(SensorAdditionalInfo parameter) {
        int handle = -1;
        if (parameter.sensor != null) handle = parameter.sensor.getHandle();
        return nativeSetOperationParameter(
                mNativeInstance, handle,
                parameter.type, parameter.floatValues, parameter.intValues) == 0;
    }

    private boolean isDeviceSensorPolicyDefault(int deviceId) {
        if (deviceId == DEVICE_ID_DEFAULT) {
            return true;
        }
        if (mVdm == null) {
            mVdm = mContext.getSystemService(VirtualDeviceManager.class);
        }
        return mVdm == null
                || mVdm.getDevicePolicy(deviceId, POLICY_TYPE_SENSORS) == DEVICE_POLICY_DEFAULT;
    }

    /**
     * Checks if a sensor should be capped according to HIGH_SAMPLING_RATE_SENSORS
     * permission.
     *
     * This needs to be kept in sync with the list defined on the native side
     * in frameworks/native/services/sensorservice/SensorService.cpp
     */
    private boolean isSensorInCappedSet(int sensorType) {
        return (sensorType == Sensor.TYPE_ACCELEROMETER
                || sensorType == Sensor.TYPE_ACCELEROMETER_UNCALIBRATED
                || sensorType == Sensor.TYPE_GYROSCOPE
                || sensorType == Sensor.TYPE_GYROSCOPE_UNCALIBRATED
                || sensorType == Sensor.TYPE_MAGNETIC_FIELD
                || sensorType == Sensor.TYPE_MAGNETIC_FIELD_UNCALIBRATED);
    }

    private boolean hasHighSamplingRateSensorsPermission() {
        if (!mHasHighSamplingRateSensorsPermission.isPresent()) {
            boolean granted = mContext.getPackageManager().checkPermission(
                    HIGH_SAMPLING_RATE_SENSORS_PERMISSION,
                    mContext.getApplicationInfo().packageName) == PERMISSION_GRANTED;
            mHasHighSamplingRateSensorsPermission = Optional.of(granted);
        }

        return mHasHighSamplingRateSensorsPermission.get();
    }
}
