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
import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.UserInfo;
import android.os.Environment;
import android.os.SystemClock;
import android.os.Trace;
import android.os.UserHandle;
import android.util.ArraySet;
import android.util.Dumpable;
import android.util.EventLog;
import android.util.Slog;
import android.util.SparseArray;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.SystemServerClassLoaderFactory;
import com.android.internal.util.Preconditions;
import com.android.server.SystemService.TargetUser;
import com.android.server.SystemService.UserCompletedEventType;
import com.android.server.am.EventLogTags;
import com.android.server.pm.ApexManager;
import com.android.server.pm.UserManagerInternal;
import com.android.server.utils.TimingsTraceAndSlog;

import dalvik.system.PathClassLoader;

import java.io.File;
import java.io.PrintWriter;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

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
    private static final String USER_COMPLETED_EVENT = "CompletedEvent"; // onCompletedEventUser

    // Whether to use multiple threads to run user lifecycle phases in parallel.
    private static boolean sUseLifecycleThreadPool = true;
    // The default number of threads to use if lifecycle thread pool is enabled.
    private static final int DEFAULT_MAX_USER_POOL_THREADS = 3;
    // The number of threads to use if lifecycle thread pool is enabled, dependent on the number of
    // available cores on the device.
    private final int mNumUserPoolThreads;
    // Maximum time to wait for a particular lifecycle phase to finish.
    private static final long USER_POOL_SHUTDOWN_TIMEOUT_SECONDS = 30;
    // Indirectly indicates how many services belong in the bootstrap and core service categories.
    // This is used to decide which services the user lifecycle phases should be parallelized for.
    private static volatile int sOtherServicesStartIndex;

    private static File sSystemDir;
    private final Context mContext;
    private boolean mSafeMode;
    private boolean mRuntimeRestarted;
    private long mRuntimeStartElapsedTime;
    private long mRuntimeStartUptime;

    // Services that should receive lifecycle events.
    private List<SystemService> mServices;
    private Set<String> mServiceClassnames;

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
        mServices = new ArrayList<>();
        mServiceClassnames = new ArraySet<>();
        // Disable using the thread pool for low ram devices
        sUseLifecycleThreadPool = sUseLifecycleThreadPool
                && !ActivityManager.isLowRamDeviceStatic();
        mNumUserPoolThreads = Math.min(Runtime.getRuntime().availableProcessors(),
                DEFAULT_MAX_USER_POOL_THREADS);
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
        PathClassLoader pathClassLoader =
                SystemServerClassLoaderFactory.getOrCreateClassLoader(
                        path, this.getClass().getClassLoader(), isJarInTestApex(path));
        final Class<SystemService> serviceClass = loadClassFromLoader(className, pathClassLoader);
        return startService(serviceClass);
    }

    /**
     * Returns true if the jar is in a test APEX.
     */
    private static boolean isJarInTestApex(String pathStr) {
        Path path = Paths.get(pathStr);
        if (path.getNameCount() >= 2 && path.getName(0).toString().equals("apex")) {
            String apexModuleName = path.getName(1).toString();
            ApexManager apexManager = ApexManager.getInstance();
            String packageName = apexManager.getActivePackageNameForApexModuleName(apexModuleName);
            PackageInfo packageInfo = apexManager.getPackageInfo(
                    packageName, ApexManager.MATCH_ACTIVE_PACKAGE);
            if (packageInfo != null) {
                return (packageInfo.applicationInfo.flags & ApplicationInfo.FLAG_TEST_ONLY) != 0;
            }
        }
        return false;
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
        // Check if already started
        String className = service.getClass().getName();
        if (mServiceClassnames.contains(className)) {
            Slog.i(TAG, "Not starting an already started service " + className);
            return;
        }
        mServiceClassnames.add(className);

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

    /** Disallow starting new services after this call. */
    void sealStartedServices() {
        mServiceClassnames = Collections.emptySet();
        mServices = Collections.unmodifiableList(mServices);
    }

    /**
     * Starts the specified boot phase for all system services that have been started up to
     * this point.
     *
     * @param t     trace logger
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
     * Called from SystemServer to indicate that services in the other category are now starting.
     * This is used to keep track of how many services are in the bootstrap and core service
     * categories for the purposes of user lifecycle parallelization.
     */
    public void updateOtherServicesStartIndex() {
        // Only update the index if the boot phase has not been completed yet
        if (!isBootCompleted()) {
            sOtherServicesStartIndex = mServices.size();
        }
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
        final TargetUser targetUser = newTargetUser(userId);
        synchronized (mTargetUsers) {
            // On Automotive / Headless System User Mode, the system user will be started twice:
            // - Once by some external or local service that switches the system user to
            //   the background.
            // - Once by the ActivityManagerService, when the system is marked ready.
            // These two events are not synchronized and the order of execution is
            // non-deterministic. To avoid starting the system user twice, verify whether
            // the system user has already been started by checking the mTargetUsers.
            // TODO(b/242195409): this workaround shouldn't be necessary once we move
            // the headless-user start logic to UserManager-land.
            if (userId == UserHandle.USER_SYSTEM && mTargetUsers.contains(userId)) {
                Slog.e(TAG, "Skipping starting system user twice");
                return;
            }
            mTargetUsers.put(userId, targetUser);
        }
        EventLog.writeEvent(EventLogTags.SSM_USER_STARTING, userId);
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

    /**
     * Called some time <i>after</i> an onUser... event has completed, for the events delineated in
     * {@link UserCompletedEventType}.
     *
     * @param eventFlags the events that completed, per {@link UserCompletedEventType}, or 0.
     * @see SystemService#onUserCompletedEvent
     */
    public void onUserCompletedEvent(@UserIdInt int userId,
            @UserCompletedEventType.EventTypesFlag int eventFlags) {
        EventLog.writeEvent(EventLogTags.SSM_USER_COMPLETED_EVENT, userId, eventFlags);
        if (eventFlags == 0) {
            return;
        }
        onUser(TimingsTraceAndSlog.newAsyncLog(),
                USER_COMPLETED_EVENT,
                /* prevUser= */ null,
                getTargetUser(userId),
                new UserCompletedEventType(eventFlags));
    }

    private void onUser(@NonNull String onWhat, @UserIdInt int userId) {
        onUser(TimingsTraceAndSlog.newAsyncLog(), onWhat, /* prevUser= */ null,
                getTargetUser(userId));
    }

    private void onUser(@NonNull TimingsTraceAndSlog t, @NonNull String onWhat,
            @Nullable TargetUser prevUser, @NonNull TargetUser curUser) {
        onUser(t, onWhat, prevUser, curUser, /* completedEventType=*/ null);
    }

    private void onUser(@NonNull TimingsTraceAndSlog t, @NonNull String onWhat,
            @Nullable TargetUser prevUser, @NonNull TargetUser curUser,
            @Nullable UserCompletedEventType completedEventType) {
        final int curUserId = curUser.getUserIdentifier();
        // NOTE: do not change label below, or it might break performance tests that rely on it.
        t.traceBegin("ssm." + onWhat + "User-" + curUserId);
        Slog.i(TAG, "Calling on" + onWhat + "User " + curUserId
                + (prevUser != null ? " (from " + prevUser + ")" : ""));

        final boolean useThreadPool = useThreadPool(curUserId, onWhat);
        final ExecutorService threadPool =
                useThreadPool ? Executors.newFixedThreadPool(mNumUserPoolThreads) : null;

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
                    Slog.i(TAG, "Skipping " + onWhat + "User-" + curUserId + " on "
                            + serviceName);
                }
                continue;
            }
            final boolean submitToThreadPool = useThreadPool && useThreadPoolForService(onWhat, i);
            if (!submitToThreadPool) {
                t.traceBegin("ssm.on" + onWhat + "User-" + curUserId + "_" + serviceName);
            }
            long time = SystemClock.elapsedRealtime();
            try {
                switch (onWhat) {
                    case USER_SWITCHING:
                        service.onUserSwitching(prevUser, curUser);
                        break;
                    case USER_STARTING:
                        if (submitToThreadPool) {
                            threadPool.submit(getOnUserStartingRunnable(t, service, curUser));
                        } else {
                            service.onUserStarting(curUser);
                        }
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
                    case USER_COMPLETED_EVENT:
                        threadPool.submit(getOnUserCompletedEventRunnable(
                                t, service, serviceName, curUser, completedEventType));
                        break;
                    default:
                        throw new IllegalArgumentException(onWhat + " what?");
                }
            } catch (Exception ex) {
                logFailure(onWhat, curUser, serviceName, ex);
            }
            if (!submitToThreadPool) {
                warnIfTooLong(SystemClock.elapsedRealtime() - time, service,
                        "on" + onWhat + "User-" + curUserId);
                t.traceEnd(); // what on service
            }
        }
        if (useThreadPool) {
            boolean terminated = false;
            threadPool.shutdown();
            try {
                terminated = threadPool.awaitTermination(
                        USER_POOL_SHUTDOWN_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Slog.wtf(TAG, "User lifecycle thread pool was interrupted while awaiting completion"
                        + " of " + onWhat + " of user " + curUser, e);
                if (!onWhat.equals(USER_COMPLETED_EVENT)) {
                    Slog.e(TAG, "Couldn't terminate, disabling thread pool. "
                            + "Please capture a bug report.");
                    sUseLifecycleThreadPool = false;
                }
            }
            if (!terminated) {
                Slog.wtf(TAG, "User lifecycle thread pool was not terminated.");
            }
        }
        t.traceEnd(); // main entry
    }

    /**
     * Whether the given onWhat should use a thread pool.
     * IMPORTANT: changing the logic to return true won't necessarily make it multi-threaded.
     *            There needs to be a corresponding logic change in onUser() to actually submit
     *            to a threadPool for the given onWhat.
     */
    private boolean useThreadPool(int userId, @NonNull String onWhat) {
        switch (onWhat) {
            case USER_STARTING:
                // Limit the lifecycle parallelization to all users other than the system user
                // and only for the user start lifecycle phase for now.
                return sUseLifecycleThreadPool && userId != UserHandle.USER_SYSTEM;
            case USER_COMPLETED_EVENT:
                return true;
            default:
                return false;
        }
    }

    private boolean useThreadPoolForService(@NonNull String onWhat, int serviceIndex) {
        switch (onWhat) {
            case USER_STARTING:
                // Only submit this service to the thread pool if it's in the "other" category.
                return serviceIndex >= sOtherServicesStartIndex;
            case USER_COMPLETED_EVENT:
                return true;
            default:
                return false;
        }
    }

    private Runnable getOnUserStartingRunnable(TimingsTraceAndSlog oldTrace, SystemService service,
            TargetUser curUser) {
        return () -> {
            final TimingsTraceAndSlog t = new TimingsTraceAndSlog(oldTrace);
            final String serviceName = service.getClass().getName();
            final int curUserId = curUser.getUserIdentifier();
            t.traceBegin("ssm.on" + USER_STARTING + "User-" + curUserId + "_" + serviceName);
            try {
                long time = SystemClock.elapsedRealtime();
                service.onUserStarting(curUser);
                warnIfTooLong(SystemClock.elapsedRealtime() - time, service,
                        "on" + USER_STARTING + "User-" + curUserId);
            } catch (Exception e) {
                logFailure(USER_STARTING, curUser, serviceName, e);
                Slog.e(TAG, "Disabling thread pool - please capture a bug report.");
                sUseLifecycleThreadPool = false;
            } finally {
                t.traceEnd();
            }
        };
    }

    private Runnable getOnUserCompletedEventRunnable(TimingsTraceAndSlog oldTrace,
            SystemService service, String serviceName, TargetUser curUser,
            UserCompletedEventType eventType) {
        return () -> {
            final TimingsTraceAndSlog t = new TimingsTraceAndSlog(oldTrace);
            final int curUserId = curUser.getUserIdentifier();
            t.traceBegin("ssm.on" + USER_COMPLETED_EVENT + "User-" + curUserId
                    + "_" + eventType + "_" + serviceName);
            try {
                long time = SystemClock.elapsedRealtime();
                service.onUserCompletedEvent(curUser, eventType);
                warnIfTooLong(SystemClock.elapsedRealtime() - time, service,
                        "on" + USER_COMPLETED_EVENT + "User-" + curUserId);
            } catch (Exception e) {
                logFailure(USER_COMPLETED_EVENT, curUser, serviceName, e);
                throw e;
            } finally {
                t.traceEnd();
            }
        };
    }

    /** Logs the failure. That's all. Tests may rely on parsing it, so only modify carefully. */
    private void logFailure(String onWhat, TargetUser curUser, String serviceName, Exception ex) {
        Slog.wtf(TAG, "SystemService failure: Failure reporting " + onWhat + " of user "
                + curUser + " to service " + serviceName, ex);
    }

    /** Sets the safe mode flag for services to query. */
    void setSafeMode(boolean safeMode) {
        mSafeMode = safeMode;
    }

    /**
     * Returns whether we are booting into safe mode.
     *
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
     *
     * @return The system directory.
     * @deprecated Use {@link Environment#getDataSystemCeDirectory()}
     * or {@link Environment#getDataSystemDeDirectory()} instead.
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
    public String getDumpableName() {
        return SystemServiceManager.class.getSimpleName();
    }

    @Override
    public void dump(PrintWriter pw, String[] args) {
        pw.printf("Current phase: %d\n", mCurrentPhase);
        synchronized (mTargetUsers) {
            if (mCurrentUser != null) {
                pw.print("Current user: ");
                mCurrentUser.dump(pw);
                pw.println();
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
        String prefix = "  ";
        if (startedLen > 0) {
            pw.printf("%d started services:\n", startedLen);
            for (int i = 0; i < startedLen; i++) {
                final SystemService service = mServices.get(i);
                pw.print(prefix); pw.println(service.getClass().getCanonicalName());
            }
        } else {
            pw.println("No started services");
        }
    }
}
