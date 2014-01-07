/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.server;

import android.content.Context;
import android.util.Log;
import android.util.Slog;

import java.util.ArrayList;

/**
 * Manages creating, starting, and other lifecycle events of
 * {@link com.android.server.SystemService}s.
 *
 * {@hide}
 */
public class SystemServiceManager {
    private static final String TAG = "SystemServiceManager";

    private final Context mContext;
    private boolean mSafeMode;

    // Services that should receive lifecycle events.
    private final ArrayList<SystemService> mServices = new ArrayList<SystemService>();

    private int mCurrentPhase = -1;

    public SystemServiceManager(Context context) {
        mContext = context;
    }

    public void startService(String className) {
        try {
            startService(Class.forName(className));
        } catch (ClassNotFoundException cnfe) {
            Slog.i(TAG, className + " not available, ignoring.");
        }
    }

    /**
     * Creates and starts a system service. The class must be a subclass of
     * {@link com.android.server.SystemService}.
     *
     * @param serviceClass A Java class that implements the SystemService interface.
     * @throws RuntimeException if the service fails to start.
     */
    public void startService(Class<?> serviceClass) {
        final SystemService serviceInstance = createInstance(serviceClass);
        try {
            Slog.i(TAG, "Creating " + serviceClass.getSimpleName());
            serviceInstance.init(mContext, this);
        } catch (Throwable e) {
            throw new RuntimeException("Failed to create service " + serviceClass.getName(), e);
        }

        mServices.add(serviceInstance);

        try {
            Slog.i(TAG, "Starting " + serviceClass.getSimpleName());
            serviceInstance.onStart();
        } catch (Throwable e) {
            throw new RuntimeException("Failed to start service " + serviceClass.getName(), e);
        }
    }

    /**
     * Starts the specified boot phase for all system services that have been started up to
     * this point.
     *
     * @param phase The boot phase to start.
     */
    public void startBootPhase(final int phase) {
        if (phase <= mCurrentPhase) {
            throw new IllegalArgumentException("Next phase must be larger than previous");
        }
        mCurrentPhase = phase;

        Slog.i(TAG, "Starting phase " + mCurrentPhase);

        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            try {
                service.onBootPhase(mCurrentPhase);
            } catch (Throwable e) {
                reportWtf("Service " + service.getClass().getName() +
                        " threw an Exception processing boot phase " + mCurrentPhase, e);
            }
        }
    }

    /** Sets the safe mode flag for services to query. */
    public void setSafeMode(boolean safeMode) {
        mSafeMode = safeMode;
    }

    /**
     * Returns whether we are booting into safe mode.
     * @return safe mode flag
     */
    public boolean isSafeMode() {
        return mSafeMode;
    }

    /**
     * Outputs the state of this manager to the System log.
     */
    public void dump() {
        StringBuilder builder = new StringBuilder();
        builder.append("Current phase: ").append(mCurrentPhase).append("\n");
        builder.append("Services:\n");
        final int startedLen = mServices.size();
        for (int i = 0; i < startedLen; i++) {
            final SystemService service = mServices.get(i);
            builder.append("\t")
                    .append(service.getClass().getSimpleName())
                    .append("\n");
        }

        Slog.e(TAG, builder.toString());
    }

    private SystemService createInstance(Class<?> clazz) {
        // Make sure it's a type we expect
        if (!SystemService.class.isAssignableFrom(clazz)) {
            reportWtf("Class " + clazz.getName() + " does not extend " +
                    SystemService.class.getName());
        }

        try {
            return (SystemService) clazz.newInstance();
        } catch (InstantiationException e) {
            reportWtf("Class " + clazz.getName() + " is abstract", e);
        } catch (IllegalAccessException e) {
            reportWtf("Class " + clazz.getName() +
                    " must have a public no-arg constructor", e);
        }
        return null;
    }

    private static void reportWtf(String message) {
        reportWtf(message, null);
    }

    private static void reportWtf(String message, Throwable e) {
        Slog.i(TAG, "******************************");
        Log.wtf(TAG, message, e);

        // Make sure we die
        throw new RuntimeException(message, e);
    }
}
