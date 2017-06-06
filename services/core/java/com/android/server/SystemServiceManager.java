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

import android.annotation.NonNull;
import android.content.Context;
import android.os.Trace;
import android.util.Slog;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;

/**
 * Manages creating, starting, and other lifecycle events of
 * {@link com.android.server.SystemService system services}.
 *
 * {@hide}
 */
public class SystemServiceManager {
    private static final String TAG = "SystemServiceManager";

    private final Context mContext;
    private boolean mSafeMode;
    private boolean mRuntimeRestarted;

    // Services that should receive lifecycle events.
    private final ArrayList<SystemService> mServices = new ArrayList<SystemService>();

    private int mCurrentPhase = -1;

    public SystemServiceManager(Context context) {
        mContext = context;
    }

    /**
     * Starts a service by class name.
     *
     * @return The service instance.
     */
    @SuppressWarnings("unchecked")
    public SystemService startService(String className) {
        final Class<SystemService> serviceClass;
        try {
            serviceClass = (Class<SystemService>)Class.forName(className);
        } catch (ClassNotFoundException ex) {
            Slog.i(TAG, "Starting " + className);
            throw new RuntimeException("Failed to create service " + className
                    + ": service class not found, usually indicates that the caller should "
                    + "have called PackageManager.hasSystemFeature() to check whether the "
                    + "feature is available on this device before trying to start the "
                    + "services that implement it", ex);
        }
        return startService(serviceClass);
    }

    /**
     * Creates and starts a system service. The class must be a subclass of
     * {@link com.android.server.SystemService}.
     *
     * @param serviceClass A Java class that implements the SystemService interface.
     * @return The service instance, never null.
     * @throws RuntimeException if the service fails to start.
     */
    @SuppressWarnings("unchecked")
    public <T extends SystemService> T startService(Class<T> serviceClass) {
        try {
            final String name = serviceClass.getName();
            Slog.i(TAG, "Starting " + name);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "StartService " + name);

            // Create the service.
            if (!SystemService.class.isAssignableFrom(serviceClass)) {
                throw new RuntimeException("Failed to create " + name
                        + ": service must extend " + SystemService.class.getName());
            }
            final T service;
            try {
                Constructor<T> constructor = serviceClass.getConstructor(Context.class);
                service = constructor.newInstance(mContext);
            } catch (InstantiationException ex) {
                throw new RuntimeException("Failed to create service " + name
                        + ": service could not be instantiated", ex);
            } catch (IllegalAccessException ex) {
                throw new RuntimeException("Failed to create service " + name
                        + ": service must have a public constructor with a Context argument", ex);
            } catch (NoSuchMethodException ex) {
                throw new RuntimeException("Failed to create service " + name
                        + ": service must have a public constructor with a Context argument", ex);
            } catch (InvocationTargetException ex) {
                throw new RuntimeException("Failed to create service " + name
                        + ": service constructor threw an exception", ex);
            }

            startService(service);
            return service;
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    public void startService(@NonNull final SystemService service) {
        // Register it.
        mServices.add(service);
        // Start it.
        try {
            service.onStart();
        } catch (RuntimeException ex) {
            throw new RuntimeException("Failed to start service " + service.getClass().getName()
                    + ": onStart threw an exception", ex);
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
        try {
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "OnBootPhase " + phase);
            final int serviceLen = mServices.size();
            for (int i = 0; i < serviceLen; i++) {
                final SystemService service = mServices.get(i);
                try {
                    service.onBootPhase(mCurrentPhase);
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to boot service "
                            + service.getClass().getName()
                            + ": onBootPhase threw an exception during phase "
                            + mCurrentPhase, ex);
                }
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    public void startUser(final int userHandle) {
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "onStartUser "
                    + service.getClass().getName());
            try {
                service.onStartUser(userHandle);
            } catch (Exception ex) {
                Slog.wtf(TAG, "Failure reporting start of user " + userHandle
                        + " to service " + service.getClass().getName(), ex);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    public void unlockUser(final int userHandle) {
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "onUnlockUser "
                    + service.getClass().getName());
            try {
                service.onUnlockUser(userHandle);
            } catch (Exception ex) {
                Slog.wtf(TAG, "Failure reporting unlock of user " + userHandle
                        + " to service " + service.getClass().getName(), ex);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    public void switchUser(final int userHandle) {
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "onSwitchUser "
                    + service.getClass().getName());
            try {
                service.onSwitchUser(userHandle);
            } catch (Exception ex) {
                Slog.wtf(TAG, "Failure reporting switch of user " + userHandle
                        + " to service " + service.getClass().getName(), ex);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    public void stopUser(final int userHandle) {
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "onStopUser "
                    + service.getClass().getName());
            try {
                service.onStopUser(userHandle);
            } catch (Exception ex) {
                Slog.wtf(TAG, "Failure reporting stop of user " + userHandle
                        + " to service " + service.getClass().getName(), ex);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
        }
    }

    public void cleanupUser(final int userHandle) {
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            Trace.traceBegin(Trace.TRACE_TAG_SYSTEM_SERVER, "onCleanupUser "
                    + service.getClass().getName());
            try {
                service.onCleanupUser(userHandle);
            } catch (Exception ex) {
                Slog.wtf(TAG, "Failure reporting cleanup of user " + userHandle
                        + " to service " + service.getClass().getName(), ex);
            }
            Trace.traceEnd(Trace.TRACE_TAG_SYSTEM_SERVER);
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
     * @return true if runtime was restarted, false if it's normal boot
     */
    public boolean isRuntimeRestarted() {
        return mRuntimeRestarted;
    }

    void setRuntimeRestarted(boolean runtimeRestarted) {
        mRuntimeRestarted = runtimeRestarted;
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
}
