/*
 * Copyright (C) 2017 The Android Open Source Project
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

package com.android.systemui.util;

import android.content.Context;
import android.hardware.HardwareBuffer;
import android.hardware.Sensor;
import android.hardware.SensorAdditionalInfo;
import android.hardware.SensorDirectChannel;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.hardware.TriggerEventListener;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.MemoryFile;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.systemui.plugins.PluginListener;
import com.android.systemui.plugins.SensorManagerPlugin;
import com.android.systemui.shared.plugins.PluginManager;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Wrapper around sensor manager that hides potential sources of latency.
 *
 * Offloads fetching (non-dynamic) sensors and (un)registering listeners onto a background thread
 * without blocking. Note that this means registering listeners now always appears successful even
 * if it is not.
 */
@Singleton
public class AsyncSensorManager extends SensorManager
        implements PluginListener<SensorManagerPlugin> {

    private static final String TAG = "AsyncSensorManager";

    private final SensorManager mInner;
    private final List<Sensor> mSensorCache;
    private final HandlerThread mHandlerThread = new HandlerThread("async_sensor");
    @VisibleForTesting final Handler mHandler;
    private final List<SensorManagerPlugin> mPlugins;

    @Inject
    public AsyncSensorManager(Context context, PluginManager pluginManager) {
        this(context.getSystemService(SensorManager.class), pluginManager);
    }

    @VisibleForTesting
    AsyncSensorManager(SensorManager sensorManager, PluginManager pluginManager) {
        mInner = sensorManager;
        mHandlerThread.start();
        mHandler = new Handler(mHandlerThread.getLooper());
        mSensorCache = mInner.getSensorList(Sensor.TYPE_ALL);
        mPlugins = new ArrayList<>();
        pluginManager.addPluginListener(this, SensorManagerPlugin.class, true /* allowMultiple */);
    }

    @Override
    protected List<Sensor> getFullSensorList() {
        return mSensorCache;
    }

    @Override
    protected List<Sensor> getFullDynamicSensorList() {
        return mInner.getSensorList(Sensor.TYPE_ALL);
    }

    @Override
    protected boolean registerListenerImpl(SensorEventListener listener,
            Sensor sensor, int delayUs, Handler handler, int maxReportLatencyUs,
            int reservedFlags) {
        mHandler.post(() -> {
            if (!mInner.registerListener(listener, sensor, delayUs, maxReportLatencyUs, handler)) {
                Log.e(TAG, "Registering " + listener + " for " + sensor + " failed.");
            }
        });
        return true;
    }

    @Override
    protected boolean flushImpl(SensorEventListener listener) {
        return mInner.flush(listener);
    }

    @Override
    protected SensorDirectChannel createDirectChannelImpl(MemoryFile memoryFile,
            HardwareBuffer hardwareBuffer) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    protected void destroyDirectChannelImpl(SensorDirectChannel channel) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    protected int configureDirectChannelImpl(SensorDirectChannel channel, Sensor s, int rate) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    protected void registerDynamicSensorCallbackImpl(DynamicSensorCallback callback,
            Handler handler) {
        mHandler.post(() -> mInner.registerDynamicSensorCallback(callback, handler));
    }

    @Override
    protected void unregisterDynamicSensorCallbackImpl(DynamicSensorCallback callback) {
        mHandler.post(() -> mInner.unregisterDynamicSensorCallback(callback));
    }

    @Override
    protected boolean requestTriggerSensorImpl(TriggerEventListener listener, Sensor sensor) {
        mHandler.post(() -> {
            if (!mInner.requestTriggerSensor(listener, sensor)) {
                Log.e(TAG, "Requesting " + listener + " for " + sensor + " failed.");
            }
        });
        return true;
    }

    @Override
    protected boolean cancelTriggerSensorImpl(TriggerEventListener listener, Sensor sensor,
            boolean disable) {
        Preconditions.checkArgument(disable);

        mHandler.post(() -> {
            if (!mInner.cancelTriggerSensor(listener, sensor)) {
                Log.e(TAG, "Canceling " + listener + " for " + sensor + " failed.");
            }
        });
        return true;
    }

    /**
     * Requests for all sensors that match the given type from all plugins.
     * @param sensor
     * @param listener
     */
    public void registerPluginListener(SensorManagerPlugin.Sensor sensor,
            SensorManagerPlugin.SensorEventListener listener) {
        if (mPlugins.isEmpty()) {
            Log.w(TAG, "No plugins registered");
        }
        mHandler.post(() -> {
            for (int i = 0; i < mPlugins.size(); i++) {
                mPlugins.get(i).registerListener(sensor, listener);
            }
        });
    }

    /**
     * Unregisters all sensors that match the give type for all plugins.
     * @param sensor
     * @param listener
     */
    public void unregisterPluginListener(SensorManagerPlugin.Sensor sensor,
            SensorManagerPlugin.SensorEventListener listener) {
        mHandler.post(() -> {
            for (int i = 0; i < mPlugins.size(); i++) {
                mPlugins.get(i).unregisterListener(sensor, listener);
            }
        });
    }

    @Override
    protected boolean initDataInjectionImpl(boolean enable) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    protected boolean injectSensorDataImpl(Sensor sensor, float[] values, int accuracy,
            long timestamp) {
        throw new UnsupportedOperationException("not implemented");
    }

    @Override
    protected boolean setOperationParameterImpl(SensorAdditionalInfo parameter) {
        mHandler.post(() -> mInner.setOperationParameter(parameter));
        return true;
    }

    @Override
    protected void unregisterListenerImpl(SensorEventListener listener,
            Sensor sensor) {
        mHandler.post(() -> {
            if (sensor == null) {
                mInner.unregisterListener(listener);
            } else {
                mInner.unregisterListener(listener, sensor);
            }
        });
    }

    @Override
    public void onPluginConnected(SensorManagerPlugin plugin, Context pluginContext) {
        mPlugins.add(plugin);
    }

    @Override
    public void onPluginDisconnected(SensorManagerPlugin plugin) {
        mPlugins.remove(plugin);
    }
}
