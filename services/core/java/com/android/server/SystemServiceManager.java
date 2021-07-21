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
import android.annotation.Nullable;
import android.annotation.UserIdInt;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.ClassLoaderFactory;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService.TargetUser;
import com.android.server.am.EventLogTags;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.TimingsTraceAndSlog;

import dalvik.system.PathClassLoader;

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
public final class SystemServiceManager implements Dumpable {
    private static final String TAG = SystemServiceManager.class.getSimpleName();
    private static final boolean DEBUG = false;
    private static final int SERVICE_CALL_WARN_TIME_MS = 50;

    // Constants used on onUser(...)
    // NOTE: do not change their values, as they're used on Trace calls and changes might break
    // performance tests that rely on them.
    private static final String USER_STARTING = "Start"; // Logged as onStartUser
    private static final String USER_UNLOCKING = "Unlocking"; // Logged as onUnlockingUser
    private static final String USER_UNLOCKED = "Unlocked"; // Logged as onUnlockedUser
    private static final String USER_SWITCHING = "Switch"; // Logged as onSwitchUser
    private static final String USER_STOPPING = "Stop"; // Logged as onStopUser
    private static final String USER_STOPPED = "Cleanup"; // Logged as onCleanupUser

    private static File sSystemDir;
    private final Context mContext;
    private boolean mSafeMode;
    private boolean mRuntimeRestarted;
    private long mRuntimeStartElapsedTime;
    private long mRuntimeStartUptime;

    // Services that should receive lifecycle events.
    private final ArrayList<SystemService> mServices = new ArrayList<SystemService>();

    // Map of paths to PathClassLoader, so we don't load the same path multiple times.
    private final ArrayMap<String, PathClassLoader> mLoadedPaths = new ArrayMap<>();

    private int mCurrentPhase = -1;

    private UserManagerInternal mUserManagerInternal;

    /**
     * Map of started {@link TargetUser TargetUsers} by user id; users are added on start and
     * removed after they're completely shut down.
     */
    @GuardedBy("mTargetUsers")
    private final SparseArray<TargetUser> mTargetUsers = new SparseArray<>();

    /**
     * Reference to the current user, it's used to set the {@link TargetUser} on
     * {@link #onUserSwitching(int, int)} as the previous user might have been removed already.
     */
    @GuardedBy("mTargetUsers")
    private @Nullable TargetUser mCurrentUser;

    SystemServiceManager(Context context) {
        mContext = context;
    }

    /**
     * Starts a service by class name.
     *
     * @return The service instance.
     */
    public SystemService startService(String className) {
        final Class<SystemService> serviceClass = loadClassFromLoader(className,
                this.getClass().getClassLoader());
        return startService(serviceClass);
    }

    /**
     * Starts a service by class name and a path that specifies the jar where the service lives.
     *
     * @return The service instance.
     */
    public SystemService startServiceFromJar(String className, String path) {
        PathClassLoader pathClassLoader = mLoadedPaths.get(path);
        if (pathClassLoader == null) {
            // NB: the parent class loader should always be the system server class loader.
            // Changing it has implications that require discussion with the mainline team.
            pathClassLoader = (PathClassLoader) ClassLoaderFactory.createClassLoader(
                    path, null /* librarySearchPath */, null /* libraryPermittedPath */,
                    this.getClass().getClassLoader(), Build.VERSION.SDK_INT,
                    true /* isNamespaceShared */, null /* classLoaderName */);
            mLoadedPaths.put(path, pathClassLoader);
        }
        final Class<SystemService> serviceClass = loadClassFromLoader(className, pathClassLoader);
        return startService(serviceClass);
    }

    /*
     * Loads and initializes a class from the given classLoader. Returns the class.
     */
    @SuppressWarnings("unchecked")
    private static Class<SystemService> loadClassFromLoader(String className,
            ClassLoader classLoader) {
        try {
            return (Class<SystemService>) Class.forName(className, true, classLoader);
        } catch (ClassNotFoundException ex) {
            throw new RuntimeException("Failed to create service " + className
                    + " from class loader " + classLoader.toString() + ": service class not "
                    + "found, usually indicates that the caller should "
                    + "have called PackageManager.hasSystemFeature() to check whether the "
                    + "feature is available on this device before trying to start the "
                    + "services that implement it. Also ensure that the correct path for the "
                    + "classloader is supplied, if applicable.", ex);
        }
    }

    /**
     * Creates and starts a system service. The class must be a subclass of
     * {@link com.android.server.SystemService}.
     *
     * @param serviceClass A Java class that implements the SystemService interface.
     * @return The service instance, never null.
     * @throws RuntimeException if the service fails to start.
     */
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
            t.traceBegin("OnBootPhase_" + phase);
            final int serviceLen = mServices.size();
            for (int i = 0; i < serviceLen; i++) {
                final SystemService service = mServices.get(i);
                long time = SystemClock.elapsedRealtime();
                t.traceBegin("OnBootPhase_" + phase + "_" + service.getClass().getName());
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
            SystemServerInitThreadPool.shutdown();
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

    private @NonNull TargetUser getTargetUser(@UserIdInt int userId) {
        final TargetUser targetUser;
        synchronized (mTargetUsers) {
            targetUser = mTargetUsers.get(userId);
        }
        Preconditions.checkState(targetUser != null, "No TargetUser for " + userId);
        return targetUser;
    }

    private @NonNull TargetUser newTargetUser(@UserIdInt int userId) {
        final UserInfo userInfo = mUserManagerInternal.getUserInfo(userId);
        Preconditions.checkState(userInfo != null, "No UserInfo for " + userId);
        return new TargetUser(userInfo);
    }

    /**
     * Starts the given user.
     */
    public void onUserStarting(@NonNull TimingsTraceAndSlog t, @UserIdInt int userId) {
        EventLog.writeEvent(EventLogTags.SSM_USER_STARTING, userId);

        final TargetUser targetUser = newTargetUser(userId);
        synchronized (mTargetUsers) {
            mTargetUsers.put(userId, targetUser);
        }

        onUser(t, USER_STARTING, /* prevUser= */ null, targetUser);
    }

    /**
     * Unlocks the given user.
     */
    public void onUserUnlocking(@UserIdInt int userId) {
        EventLog.writeEvent(EventLogTags.SSM_USER_UNLOCKING, userId);
        onUser(USER_UNLOCKING, userId);
    }

    /**
     * Called after the user was unlocked.
     */
    public void onUserUnlocked(@UserIdInt int userId) {
        EventLog.writeEvent(EventLogTags.SSM_USER_UNLOCKED, userId);
        onUser(USER_UNLOCKED, userId);
    }

    /**
     * Switches to the given user.
     */
    public void onUserSwitching(@UserIdInt int from, @UserIdInt int to) {
        EventLog.writeEvent(EventLogTags.SSM_USER_SWITCHING, from, to);
        final TargetUser curUser, prevUser;
        synchronized (mTargetUsers) {
            if (mCurrentUser == null) {
                if (DEBUG) {
                    Slog.d(TAG, "First user switch: from " + from + " to " + to);
                }
                prevUser = newTargetUser(from);
            } else {
                if (from != mCurrentUser.getUserIdentifier()) {
                    Slog.wtf(TAG, "switchUser(" + from + "," + to + "): mCurrentUser is "
                            + mCurrentUser + ", it should be " + from);
                }
                prevUser = mCurrentUser;
            }
            curUser = mCurrentUser = getTargetUser(to);
            if (DEBUG) {
                Slog.d(TAG, "Set mCurrentUser to " + mCurrentUser);
            }
        }
        onUser(TimingsTraceAndSlog.newAsyncLog(), USER_SWITCHING, prevUser, curUser);
    }

    /**
     * Stops the given user.
     */
    public void onUserStopping(@UserIdInt int userId) {
        EventLog.writeEvent(EventLogTags.SSM_USER_STOPPING, userId);
        onUser(USER_STOPPING, userId);
    }

    /**
     * Cleans up the given user.
     */
    public void onUserStopped(@UserIdInt int userId) {
        EventLog.writeEvent(EventLogTags.SSM_USER_STOPPED, userId);
        onUser(USER_STOPPED, userId);

        // Remove cached TargetUser
        synchronized (mTargetUsers) {
            mTargetUsers.remove(userId);
        }
    }

    private void onUser(@NonNull String onWhat, @UserIdInt int userId) {
        onUser(TimingsTraceAndSlog.newAsyncLog(), onWhat, /* prevUser= */ null,
                getTargetUser(userId));
    }

    private void onUser(@NonNull TimingsTraceAndSlog t, @NonNull String onWhat,
            @Nullable TargetUser prevUser, @NonNull TargetUser curUser) {
        final int curUserId = curUser.getUserIdentifier();
        // NOTE: do not change label below, or it might break performance tests that rely on it.
        t.traceBegin("ssm." + onWhat + "User-" + curUserId);
        Slog.i(TAG, "Calling on" + onWhat + "User " + curUserId
                + (prevUser != null ? " (from " + prevUser + ")" : ""));
        final int serviceLen = mServices.size();
        for (int i = 0; i < serviceLen; i++) {
            final SystemService service = mServices.get(i);
            final String serviceName = service.getClass().getName();
            boolean supported = service.isUserSupported(curUser);

            // Must check if either curUser or prevUser is supported (for example, if switching from
            // unsupported to supported, we still need to notify the services)
            if (!supported && prevUser != null) {
                supported = service.isUserSupported(prevUser);
            }

            if (!supported) {
                if (DEBUG) {
                    Slog.d(TAG, "Skipping " + onWhat + "User-" + curUserId + " on service "
                            + serviceName + " because it's not supported (curUser: "
                            + curUser + ", prevUser:" + prevUser + ")");
                } else {
                    Slog.i(TAG,  "Skipping " + onWhat + "User-" + curUserId + " on "
                            + serviceName);
                }
                continue;
            }
            t.traceBegin("ssm.on" + onWhat + "User-" + curUserId + "_" + serviceName);
            long time = SystemClock.elapsedRealtime();
            try {
                switch (onWhat) {
                    case USER_SWITCHING:
                        service.onUserSwitching(prevUser, curUser);
                        break;
                    case USER_STARTING:
                        service.onUserStarting(curUser);
                        break;
                    case USER_UNLOCKING:
                        service.onUserUnlocking(curUser);
                        break;
                    case USER_UNLOCKED:
                        service.onUserUnlocked(curUser);
                        break;
                    case USER_STOPPING:
                        service.onUserStopping(curUser);
                        break;
                    case USER_STOPPED:
                        service.onUserStopped(curUser);
                        break;
                    default:
                        throw new IllegalArgumentException(onWhat + " what?");
                }
            } catch (Exception ex) {
                Slog.wtf(TAG, "Failure reporting " + onWhat + " of user " + curUser
                        + " to service " + serviceName, ex);
            }
            warnIfTooLong(SystemClock.elapsedRealtime() - time, service,
                    "on" + onWhat + "User-" + curUserId);
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

    @Override
    public void dump(IndentingPrintWriter pw, String[] args) {
        pw.printf("Current phase: %d\n", mCurrentPhase);
        synchronized (mTargetUsers) {
            if (mCurrentUser != null) {
                pw.print("Current user: "); mCurrentUser.dump(pw); pw.println();
            } else {
                pw.println("Current user not set!");
            }

            final int targetUsersSize = mTargetUsers.size();
            if (targetUsersSize > 0) {
                pw.printf("%d target users: ", targetUsersSize);
                for (int i = 0; i < targetUsersSize; i++) {
                    mTargetUsers.valueAt(i).dump(pw);
                    if (i != targetUsersSize - 1) pw.print(", ");
                }
                pw.println();
            } else {
                pw.println("No target users");
            }
        }
        final int startedLen = mServices.size();
        if (startedLen > 0) {
            pw.printf("%d started services:\n", startedLen);
            pw.increaseIndent();
            for (int i = 0; i < startedLen; i++) {
                final SystemService service = mServices.get(i);
                pw.println(service.getClass().getCanonicalName());
            }
            pw.decreaseIndent();
        } else {
            pw.println("No started services");
        }
    }
}
