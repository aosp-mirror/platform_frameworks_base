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
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserManagerInternal;
import android.util.Slog;

import com.android.server.utils.TimingsTraceAndSlog;

import java.io.File;
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
    private static final int SERVICE_CALL_WARN_TIME_MS = 50;

    private static File sSystemDir;
    private final Context mContext;
    private boolean mSafeMode;
    private boolean mRuntimeRestarted;
    private long mRuntimeStartElapsedTime;
    private long mRuntimeStartUptime;

    // Services that should receive lifecycle events.
    private final ArrayList<SystemService> mServices = new ArrayList<SystemService>();

    private int mCurrentPhase = -1;

    private UserManagerInternal mUserManagerInternal;

    SystemServiceManager(Context context) {
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
        long time = SystemClock.elapsedRealtime();
        try {
            service.onStart();
        } catch (RuntimeException ex) {
            throw new RuntimeException("Failed to start service " + service.getClass().getName()
                    + ": onStart threw an exception", ex);
        }
        warnIfTooLong(SystemClock.elapsedRealtime() - time, service, "onStart");
    }

    /**
     * Starts the specified boot phase for all system services that have been started up to
     * this point.
     *
     * @param t trace logger
     * @param phase The boot phase to start.
     */
    public void startBootPhase(@NonNull TimingsTraceAndSlog t, int phase) {
        if (phase <= mCurrentPhase) {
            throw new IllegalArgumentException("Next phase must be larger than previous");
        }
        mCurrentPhase = phase;

        Slog.i(TAG, "Starting phase " + mCurrentPhase);
        try {
            t.traceBegin("OnBootPhase " + phase);
            final int serviceLen = mServices.size();
            for (int i = 0; i < serviceLen; i++) {
                final SystemService service = mServices.get(i);
                long time = SystemClock.elapsedRealtime();
                t.traceBegin(service.getClass().getName());
                try {
                    service.onBootPhase(mCurrentPhase);
                } catch (Exception ex) {
                    throw new RuntimeException("Failed to boot service "
                            + service.getClass().getName()
                            + ": onBootPhase threw an exception during phase "
                            + mCurrentPhase, ex);
                }
                warnIfTooLong(SystemClock.elapsedRealtime() - time, service, "onBootPhase");
                t.traceEnd();
            }
        } finally {
            t.traceEnd();
        }

        if (phase == SystemService.PHASE_BOOT_COMPLETED) {
            final long totalBootTime = SystemClock.uptimeMillis() - mRuntimeStartUptime;
            t.logDuration("TotalBootTime", totalBootTime);
        }
    }

    /**
     * @return true if system has completed the boot; false otherwise.
     */
    public boolean isBootCompleted() {
        return mCurrentPhase >= SystemService.PHASE_BOOT_COMPLETED;
    }

    /**
     * Called at the beginning of {@code ActivityManagerService.systemReady()}.
     */
    public void preSystemReady() {
        mUserManagerInternal = LocalServices.getService(UserManagerInternal.class);
    }

    private @NonNull UserInfo getUserInfo(@UserIdInt int userHandle) {
        if (mUserManagerInternal == null) {
            throw new IllegalStateException("mUserManagerInternal not set yet");
        }
        final UserInfo userInfo = mUserManagerInternal.getUserInfo(userHandle);
        if (userInfo == null) {
            throw new IllegalStateException("No UserInfo for " + userHandle);
        }
        return userInfo;
    }

    /**
     * Starts the given user.
     */
    public void startUser(final @NonNull TimingsTraceAndSlog t, final @UserIdInt int userHandle) {
        onUser(t, "Start", userHandle, (s, u) -> s.onStartUser(u));
    }

    /**
     * Unlocks the given user.
     */
    public void unlockUser(final @UserIdInt int userHandle) {
        onUser("Unlock", userHandle, (s, u) -> s.onUnlockUser(u));
    }

    /**
     * Switches to the given user.
     */
    public void switchUser(final @UserIdInt int userHandle) {
        onUser("Switch", userHandle, (s, u) -> s.onSwitchUser(u));
    }

    /**
     * Stops the given user.
     */
    public void stopUser(final @UserIdInt int userHandle) {
        onUser("Stop", userHandle, (s, u) -> s.onStopUser(u));
    }

    /**
     * Cleans up the given user.
     */
    public void cleanupUser(final @UserIdInt int userHandle) {
        onUser("Cleanup", userHandle, (s, u) -> s.onCleanupUser(u));
    }

    private interface ServiceVisitor {
        void visit(@NonNull SystemService service, @NonNull UserInfo userInfo);
    }

    private void onUser(@NonNull String onWhat, @UserIdInt int userHandle,
            @NonNull ServiceVisitor visitor) {
        onUser(TimingsTraceAndSlog.newAsyncLog(), onWhat, userHandle, visitor);
    }

    private void onUser(@NonNull TimingsTraceAndSlog t, @NonNull String onWhat,
            @UserIdInt int userHandle, @NonNull ServiceVisitor visitor) {
        t.traceBegin("ssm." + onWhat + "User-" + userHandle);
        Slog.i(TAG, "Calling on" + onWhat + "User u" + userHandle);
        final UserInfo userInfo = getUserInfo(userHandle);
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            final String serviceName = service.getClass().getName();
            t.traceBegin("ssm.on" + onWhat + "User-" + userHandle + " " + serviceName);
            long time = SystemClock.elapsedRealtime();
            try {
                visitor.visit(service, userInfo);
            } catch (Exception ex) {
                Slog.wtf(TAG, "Failure reporting " + onWhat + " of user " + userHandle
                        + " to service " + serviceName, ex);
            }
            warnIfTooLong(SystemClock.elapsedRealtime() - time, service, "on" + onWhat + "User ");
            t.traceEnd(); // what on service
        }
        t.traceEnd(); // main entry
    }

    /** Sets the safe mode flag for services to query. */
    void setSafeMode(boolean safeMode) {
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

    /**
     * @return Time when SystemServer was started, in elapsed realtime.
     */
    public long getRuntimeStartElapsedTime() {
        return mRuntimeStartElapsedTime;
    }

    /**
     * @return Time when SystemServer was started, in uptime.
     */
    public long getRuntimeStartUptime() {
        return mRuntimeStartUptime;
    }

    void setStartInfo(boolean runtimeRestarted,
            long runtimeStartElapsedTime, long runtimeStartUptime) {
        mRuntimeRestarted = runtimeRestarted;
        mRuntimeStartElapsedTime = runtimeStartElapsedTime;
        mRuntimeStartUptime = runtimeStartUptime;
    }

    private void warnIfTooLong(long duration, SystemService service, String operation) {
        if (duration > SERVICE_CALL_WARN_TIME_MS) {
            Slog.w(TAG, "Service " + service.getClass().getName() + " took " + duration + " ms in "
                    + operation);
        }
    }

    /**
     * Ensures that the system directory exist creating one if needed.
     * @deprecated Use {@link Environment#getDataSystemCeDirectory()}
     * or {@link Environment#getDataSystemDeDirectory()} instead.
     * @return The system directory.
     */
    @Deprecated
    public static File ensureSystemDir() {
        if (sSystemDir == null) {
            File dataDir = Environment.getDataDirectory();
            sSystemDir = new File(dataDir, "system");
            sSystemDir.mkdirs();
        }
        return sSystemDir;
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
