/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.service.watchdog.ExplicitHealthCheckService.PackageConfig;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.Nullable;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.net.ConnectivityModuleConnector;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.LongArrayQueue;
import android.util.MathUtils;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
import com.android.internal.util.IndentingPrintWriter;
import com.android.internal.util.XmlUtils;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlSerializer;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the health of packages on the system and notifies interested observers when packages
 * fail. On failure, the registered observer with the least user impacting mitigation will
 * be notified.
 */
public class PackageWatchdog {
    private static final String TAG = "PackageWatchdog";

    static final String PROPERTY_WATCHDOG_TRIGGER_DURATION_MILLIS =
            "watchdog_trigger_failure_duration_millis";
    static final String PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT =
            "watchdog_trigger_failure_count";
    static final String PROPERTY_WATCHDOG_EXPLICIT_HEALTH_CHECK_ENABLED =
            "watchdog_explicit_health_check_enabled";

    // TODO: make the following values configurable via DeviceConfig
    private static final long NATIVE_CRASH_POLLING_INTERVAL_MILLIS =
            TimeUnit.SECONDS.toMillis(30);
    private static final long NUMBER_OF_NATIVE_CRASH_POLLS = 10;


    public static final int FAILURE_REASON_UNKNOWN = 0;
    public static final int FAILURE_REASON_NATIVE_CRASH = 1;
    public static final int FAILURE_REASON_EXPLICIT_HEALTH_CHECK = 2;
    public static final int FAILURE_REASON_APP_CRASH = 3;
    public static final int FAILURE_REASON_APP_NOT_RESPONDING = 4;

    @IntDef(prefix = { "FAILURE_REASON_" }, value = {
            FAILURE_REASON_UNKNOWN,
            FAILURE_REASON_NATIVE_CRASH,
            FAILURE_REASON_EXPLICIT_HEALTH_CHECK,
            FAILURE_REASON_APP_CRASH,
            FAILURE_REASON_APP_NOT_RESPONDING
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FailureReasons {}

    // Duration to count package failures before it resets to 0
    @VisibleForTesting
    static final int DEFAULT_TRIGGER_FAILURE_DURATION_MS =
            (int) TimeUnit.MINUTES.toMillis(1);
    // Number of package failures within the duration above before we notify observers
    @VisibleForTesting
    static final int DEFAULT_TRIGGER_FAILURE_COUNT = 5;
    @VisibleForTesting
    static final long DEFAULT_OBSERVING_DURATION_MS = TimeUnit.DAYS.toMillis(2);
    // Whether explicit health checks are enabled or not
    private static final boolean DEFAULT_EXPLICIT_HEALTH_CHECK_ENABLED = true;

    @VisibleForTesting
    static final int DEFAULT_BOOT_LOOP_TRIGGER_COUNT = 5;
    static final long DEFAULT_BOOT_LOOP_TRIGGER_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);
    private static final String PROP_RESCUE_BOOT_COUNT = "sys.rescue_boot_count";
    private static final String PROP_RESCUE_BOOT_START = "sys.rescue_boot_start";

    private long mNumberOfNativeCrashPollsRemaining;

    private static final int DB_VERSION = 1;
    private static final String TAG_PACKAGE_WATCHDOG = "package-watchdog";
    private static final String TAG_PACKAGE = "package";
    private static final String TAG_OBSERVER = "observer";
    private static final String ATTR_VERSION = "version";
    private static final String ATTR_NAME = "name";
    private static final String ATTR_DURATION = "duration";
    private static final String ATTR_EXPLICIT_HEALTH_CHECK_DURATION = "health-check-duration";
    private static final String ATTR_PASSED_HEALTH_CHECK = "passed-health-check";

    @GuardedBy("PackageWatchdog.class")
    private static PackageWatchdog sPackageWatchdog;

    private final Object mLock = new Object();
    // System server context
    private final Context mContext;
    // Handler to run short running tasks
    private final Handler mShortTaskHandler;
    // Handler for processing IO and long running tasks
    private final Handler mLongTaskHandler;
    // Contains (observer-name -> observer-handle) that have ever been registered from
    // previous boots. Observers with all packages expired are periodically pruned.
    // It is saved to disk on system shutdown and repouplated on startup so it survives reboots.
    @GuardedBy("mLock")
    private final ArrayMap<String, ObserverInternal> mAllObservers = new ArrayMap<>();
    // File containing the XML data of monitored packages /data/system/package-watchdog.xml
    private final AtomicFile mPolicyFile;
    private final ExplicitHealthCheckController mHealthCheckController;
    private final ConnectivityModuleConnector mConnectivityModuleConnector;
    private final Runnable mSyncRequests = this::syncRequests;
    private final Runnable mSyncStateWithScheduledReason = this::syncStateWithScheduledReason;
    private final Runnable mSaveToFile = this::saveToFile;
    private final SystemClock mSystemClock;
    private final BootThreshold mBootThreshold;
    // The set of packages that have been synced with the ExplicitHealthCheckController
    @GuardedBy("mLock")
    private Set<String> mRequestedHealthCheckPackages = new ArraySet<>();
    @GuardedBy("mLock")
    private boolean mIsPackagesReady;
    // Flag to control whether explicit health checks are supported or not
    @GuardedBy("mLock")
    private boolean mIsHealthCheckEnabled = DEFAULT_EXPLICIT_HEALTH_CHECK_ENABLED;
    @GuardedBy("mLock")
    private int mTriggerFailureDurationMs = DEFAULT_TRIGGER_FAILURE_DURATION_MS;
    @GuardedBy("mLock")
    private int mTriggerFailureCount = DEFAULT_TRIGGER_FAILURE_COUNT;
    // SystemClock#uptimeMillis when we last executed #syncState
    // 0 if no prune is scheduled.
    @GuardedBy("mLock")
    private long mUptimeAtLastStateSync;

    @FunctionalInterface
    @VisibleForTesting
    interface SystemClock {
        // TODO: Add elapsedRealtime to this interface
        long uptimeMillis();
    }

    private PackageWatchdog(Context context) {
        // Needs to be constructed inline
        this(context, new AtomicFile(
                        new File(new File(Environment.getDataDirectory(), "system"),
                                "package-watchdog.xml")),
                new Handler(Looper.myLooper()), BackgroundThread.getHandler(),
                new ExplicitHealthCheckController(context),
                ConnectivityModuleConnector.getInstance(),
                android.os.SystemClock::uptimeMillis);
    }

    /**
     * Creates a PackageWatchdog that allows injecting dependencies.
     */
    @VisibleForTesting
    PackageWatchdog(Context context, AtomicFile policyFile, Handler shortTaskHandler,
            Handler longTaskHandler, ExplicitHealthCheckController controller,
            ConnectivityModuleConnector connectivityModuleConnector, SystemClock clock) {
        mContext = context;
        mPolicyFile = policyFile;
        mShortTaskHandler = shortTaskHandler;
        mLongTaskHandler = longTaskHandler;
        mHealthCheckController = controller;
        mConnectivityModuleConnector = connectivityModuleConnector;
        mSystemClock = clock;
        mNumberOfNativeCrashPollsRemaining = NUMBER_OF_NATIVE_CRASH_POLLS;
        mBootThreshold = new BootThreshold(DEFAULT_BOOT_LOOP_TRIGGER_COUNT,
                DEFAULT_BOOT_LOOP_TRIGGER_WINDOW_MS);
        loadFromFile();
        sPackageWatchdog = this;
    }

    /** Creates or gets singleton instance of PackageWatchdog. */
    public static PackageWatchdog getInstance(Context context) {
        synchronized (PackageWatchdog.class) {
            if (sPackageWatchdog == null) {
                new PackageWatchdog(context);
            }
            return sPackageWatchdog;
        }
    }

    /**
     * Called during boot to notify when packages are ready on the device so we can start
     * binding.
     */
    public void onPackagesReady() {
        synchronized (mLock) {
            mIsPackagesReady = true;
            mHealthCheckController.setCallbacks(packageName -> onHealthCheckPassed(packageName),
                    packages -> onSupportedPackages(packages),
                    () -> syncRequestsAsync());
            setPropertyChangedListenerLocked();
            updateConfigs();
            registerConnectivityModuleHealthListener();
        }
    }

    /**
     * Registers {@code observer} to listen for package failures. Add a new ObserverInternal for
     * this observer if it does not already exist.
     *
     * <p>Observers are expected to call this on boot. It does not specify any packages but
     * it will resume observing any packages requested from a previous boot.
     */
    public void registerHealthObserver(PackageHealthObserver observer) {
        synchronized (mLock) {
            ObserverInternal internalObserver = mAllObservers.get(observer.getName());
            if (internalObserver != null) {
                internalObserver.registeredObserver = observer;
            } else {
                internalObserver = new ObserverInternal(observer.getName(), new ArrayList<>());
                internalObserver.registeredObserver = observer;
                mAllObservers.put(observer.getName(), internalObserver);
                syncState("added new observer");
            }
        }
    }

    /**
     * Starts observing the health of the {@code packages} for {@code observer} and notifies
     * {@code observer} of any package failures within the monitoring duration.
     *
     * <p>If monitoring a package supporting explicit health check, at the end of the monitoring
     * duration if {@link #onHealthCheckPassed} was never called,
     * {@link PackageHealthObserver#execute} will be called as if the package failed.
     *
     * <p>If {@code observer} is already monitoring a package in {@code packageNames},
     * the monitoring window of that package will be reset to {@code durationMs} and the health
     * check state will be reset to a default depending on if the package is contained in
     * {@link mPackagesWithExplicitHealthCheckEnabled}.
     *
     * <p>If {@code packageNames} is empty, this will be a no-op.
     *
     * <p>If {@code durationMs} is less than 1, a default monitoring duration
     * {@link #DEFAULT_OBSERVING_DURATION_MS} will be used.
     */
    public void startObservingHealth(PackageHealthObserver observer, List<String> packageNames,
            long durationMs) {
        if (packageNames.isEmpty()) {
            Slog.wtf(TAG, "No packages to observe, " + observer.getName());
            return;
        }
        if (durationMs < 1) {
            Slog.wtf(TAG, "Invalid duration " + durationMs + "ms for observer "
                    + observer.getName() + ". Not observing packages " + packageNames);
            durationMs = DEFAULT_OBSERVING_DURATION_MS;
        }

        List<MonitoredPackage> packages = new ArrayList<>();
        for (int i = 0; i < packageNames.size(); i++) {
            // Health checks not available yet so health check state will start INACTIVE
            MonitoredPackage pkg = newMonitoredPackage(packageNames.get(i), durationMs, false);
            if (pkg != null) {
                packages.add(pkg);
            }
        }

        if (packages.isEmpty()) {
            return;
        }

        // Sync before we add the new packages to the observers. This will #pruneObservers,
        // causing any elapsed time to be deducted from all existing packages before we add new
        // packages. This maintains the invariant that the elapsed time for ALL (new and existing)
        // packages is the same.
        syncState("observing new packages");

        synchronized (mLock) {
            ObserverInternal oldObserver = mAllObservers.get(observer.getName());
            if (oldObserver == null) {
                Slog.d(TAG, observer.getName() + " started monitoring health "
                        + "of packages " + packageNames);
                mAllObservers.put(observer.getName(),
                        new ObserverInternal(observer.getName(), packages));
            } else {
                Slog.d(TAG, observer.getName() + " added the following "
                        + "packages to monitor " + packageNames);
                oldObserver.updatePackagesLocked(packages);
            }
        }

        // Register observer in case not already registered
        registerHealthObserver(observer);

        // Sync after we add the new packages to the observers. We may have received packges
        // requiring an earlier schedule than we are currently scheduled for.
        syncState("updated observers");
    }

    /**
     * Unregisters {@code observer} from listening to package failure.
     * Additionally, this stops observing any packages that may have previously been observed
     * even from a previous boot.
     */
    public void unregisterHealthObserver(PackageHealthObserver observer) {
        synchronized (mLock) {
            mAllObservers.remove(observer.getName());
        }
        syncState("unregistering observer: " + observer.getName());
    }

    /**
     * Called when a process fails due to a crash, ANR or explicit health check.
     *
     * <p>For each package contained in the process, one registered observer with the least user
     * impact will be notified for mitigation.
     *
     * <p>This method could be called frequently if there is a severe problem on the device.
     */
    public void onPackageFailure(List<VersionedPackage> packages,
            @FailureReasons int failureReason) {
        mLongTaskHandler.post(() -> {
            synchronized (mLock) {
                if (mAllObservers.isEmpty()) {
                    return;
                }
                boolean requiresImmediateAction = (failureReason == FAILURE_REASON_NATIVE_CRASH
                        || failureReason == FAILURE_REASON_EXPLICIT_HEALTH_CHECK);
                if (requiresImmediateAction) {
                    handleFailureImmediately(packages, failureReason);
                } else {
                    for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                        VersionedPackage versionedPackage = packages.get(pIndex);
                        // Observer that will receive failure for versionedPackage
                        PackageHealthObserver currentObserverToNotify = null;
                        int currentObserverImpact = Integer.MAX_VALUE;

                        // Find observer with least user impact
                        for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
                            ObserverInternal observer = mAllObservers.valueAt(oIndex);
                            PackageHealthObserver registeredObserver = observer.registeredObserver;
                            if (registeredObserver != null
                                    && observer.onPackageFailureLocked(
                                    versionedPackage.getPackageName())) {
                                int impact = registeredObserver.onHealthCheckFailed(
                                        versionedPackage, failureReason);
                                if (impact != PackageHealthObserverImpact.USER_IMPACT_NONE
                                        && impact < currentObserverImpact) {
                                    currentObserverToNotify = registeredObserver;
                                    currentObserverImpact = impact;
                                }
                            }
                        }

                        // Execute action with least user impact
                        if (currentObserverToNotify != null) {
                            currentObserverToNotify.execute(versionedPackage, failureReason);
                        }
                    }
                }
            }
        });
    }

    /**
     * For native crashes or explicit health check failures, call directly into each observer to
     * mitigate the error without going through failure threshold logic.
     */
    private void handleFailureImmediately(List<VersionedPackage> packages,
            @FailureReasons int failureReason) {
        VersionedPackage failingPackage = packages.size() > 0 ? packages.get(0) : null;
        PackageHealthObserver currentObserverToNotify = null;
        int currentObserverImpact = Integer.MAX_VALUE;
        for (ObserverInternal observer: mAllObservers.values()) {
            PackageHealthObserver registeredObserver = observer.registeredObserver;
            if (registeredObserver != null) {
                int impact = registeredObserver.onHealthCheckFailed(
                        failingPackage, failureReason);
                if (impact != PackageHealthObserverImpact.USER_IMPACT_NONE
                        && impact < currentObserverImpact) {
                    currentObserverToNotify = registeredObserver;
                    currentObserverImpact = impact;
                }
            }
        }
        if (currentObserverToNotify != null) {
            currentObserverToNotify.execute(failingPackage,  failureReason);
        }
    }

    /**
     * Called when the system server boots. If the system server is detected to be in a boot loop,
     * query each observer and perform the mitigation action with the lowest user impact.
     */
    public void noteBoot() {
        synchronized (mLock) {
            if (mBootThreshold.incrementAndTest()) {
                mBootThreshold.reset();
                PackageHealthObserver currentObserverToNotify = null;
                int currentObserverImpact = Integer.MAX_VALUE;
                for (int i = 0; i < mAllObservers.size(); i++) {
                    final ObserverInternal observer = mAllObservers.valueAt(i);
                    PackageHealthObserver registeredObserver = observer.registeredObserver;
                    if (registeredObserver != null) {
                        int impact = registeredObserver.onBootLoop();
                        if (impact != PackageHealthObserverImpact.USER_IMPACT_NONE
                                && impact < currentObserverImpact) {
                            currentObserverToNotify = registeredObserver;
                            currentObserverImpact = impact;
                        }
                    }
                }
                if (currentObserverToNotify != null) {
                    currentObserverToNotify.executeBootLoopMitigation();
                }
            }
        }
    }

    // TODO(b/120598832): Optimize write? Maybe only write a separate smaller file? Also
    // avoid holding lock?
    // This currently adds about 7ms extra to shutdown thread
    /** Writes the package information to file during shutdown. */
    public void writeNow() {
        synchronized (mLock) {
            // Must only run synchronous tasks as this runs on the ShutdownThread and no other
            // thread is guaranteed to run during shutdown.
            if (!mAllObservers.isEmpty()) {
                mLongTaskHandler.removeCallbacks(mSaveToFile);
                pruneObserversLocked();
                saveToFile();
                Slog.i(TAG, "Last write to update package durations");
            }
        }
    }

    /**
     * Enables or disables explicit health checks.
     * <p> If explicit health checks are enabled, the health check service is started.
     * <p> If explicit health checks are disabled, pending explicit health check requests are
     * passed and the health check service is stopped.
     */
    private void setExplicitHealthCheckEnabled(boolean enabled) {
        synchronized (mLock) {
            mIsHealthCheckEnabled = enabled;
            mHealthCheckController.setEnabled(enabled);
            // Prune to update internal state whenever health check is enabled/disabled
            syncState("health check state " + (enabled ? "enabled" : "disabled"));
        }
    }

    /**
     * This method should be only called on mShortTaskHandler, since it modifies
     * {@link #mNumberOfNativeCrashPollsRemaining}.
     */
    private void checkAndMitigateNativeCrashes() {
        mNumberOfNativeCrashPollsRemaining--;
        // Check if native watchdog reported a crash
        if ("1".equals(SystemProperties.get("sys.init.updatable_crashing"))) {
            // We rollback everything available when crash is unattributable
            onPackageFailure(Collections.EMPTY_LIST, FAILURE_REASON_NATIVE_CRASH);
            // we stop polling after an attempt to execute rollback, regardless of whether the
            // attempt succeeds or not
        } else {
            if (mNumberOfNativeCrashPollsRemaining > 0) {
                mShortTaskHandler.postDelayed(() -> checkAndMitigateNativeCrashes(),
                        NATIVE_CRASH_POLLING_INTERVAL_MILLIS);
            }
        }
    }

    /**
     * Since this method can eventually trigger a rollback, it should be called
     * only once boot has completed {@code onBootCompleted} and not earlier, because the install
     * session must be entirely completed before we try to rollback.
     */
    public void scheduleCheckAndMitigateNativeCrashes() {
        Slog.i(TAG, "Scheduling " + mNumberOfNativeCrashPollsRemaining + " polls to check "
                + "and mitigate native crashes");
        mShortTaskHandler.post(()->checkAndMitigateNativeCrashes());
    }

    /** Possible severity values of the user impact of a {@link PackageHealthObserver#execute}. */
    @Retention(SOURCE)
    @IntDef(value = {PackageHealthObserverImpact.USER_IMPACT_NONE,
                     PackageHealthObserverImpact.USER_IMPACT_LOW,
                     PackageHealthObserverImpact.USER_IMPACT_MEDIUM,
                     PackageHealthObserverImpact.USER_IMPACT_HIGH})
    public @interface PackageHealthObserverImpact {
        /** No action to take. */
        int USER_IMPACT_NONE = 0;
        /* Action has low user impact, user of a device will barely notice. */
        int USER_IMPACT_LOW = 1;
        /* Action has medium user impact, user of a device will likely notice. */
        int USER_IMPACT_MEDIUM = 3;
        /* Action has high user impact, a last resort, user of a device will be very frustrated. */
        int USER_IMPACT_HIGH = 5;
    }

    /** Register instances of this interface to receive notifications on package failure. */
    public interface PackageHealthObserver {
        /**
         * Called when health check fails for the {@code versionedPackage}.
         *
         * @param versionedPackage the package that is failing. This may be null if a native
         *                          service is crashing.
         * @param failureReason   the type of failure that is occurring.
         *
         *
         * @return any one of {@link PackageHealthObserverImpact} to express the impact
         * to the user on {@link #execute}
         */
        @PackageHealthObserverImpact int onHealthCheckFailed(
                @Nullable VersionedPackage versionedPackage,
                @FailureReasons int failureReason);

        /**
         * Executes mitigation for {@link #onHealthCheckFailed}.
         *
         * @param versionedPackage the package that is failing. This may be null if a native
         *                          service is crashing.
         * @param failureReason   the type of failure that is occurring.
         * @return {@code true} if action was executed successfully, {@code false} otherwise
         */
        boolean execute(@Nullable VersionedPackage versionedPackage,
                @FailureReasons int failureReason);


        /**
         * Called when the system server has booted several times within a window of time, defined
         * by {@link #mBootThreshold}
         */
        default @PackageHealthObserverImpact int onBootLoop() {
            return PackageHealthObserverImpact.USER_IMPACT_NONE;
        }

        /**
         * Executes mitigation for {@link #onBootLoop}
         */
        default boolean executeBootLoopMitigation() {
            return false;
        }

        // TODO(b/120598832): Ensure uniqueness?
        /**
         * Identifier for the observer, should not change across device updates otherwise the
         * watchdog may drop observing packages with the old name.
         */
        String getName();

        /**
         * An observer will not be pruned if this is set, even if the observer is not explicitly
         * monitoring any packages.
         */
        default boolean isPersistent() {
            return false;
        }

        /**
         * Returns {@code true} if this observer wishes to observe the given package, {@code false}
         * otherwise
         *
         * <p> A persistent observer may choose to start observing certain failing packages, even if
         * it has not explicitly asked to watch the package with {@link #startObservingHealth}.
         */
        default boolean mayObservePackage(String packageName) {
            return false;
        }
    }

    long getTriggerFailureCount() {
        synchronized (mLock) {
            return mTriggerFailureCount;
        }
    }

    /**
     * Serializes and syncs health check requests with the {@link ExplicitHealthCheckController}.
     */
    private void syncRequestsAsync() {
        mShortTaskHandler.removeCallbacks(mSyncRequests);
        mShortTaskHandler.post(mSyncRequests);
    }

    /**
     * Syncs health check requests with the {@link ExplicitHealthCheckController}.
     * Calls to this must be serialized.
     *
     * @see #syncRequestsAsync
     */
    private void syncRequests() {
        boolean syncRequired = false;
        synchronized (mLock) {
            if (mIsPackagesReady) {
                Set<String> packages = getPackagesPendingHealthChecksLocked();
                if (!packages.equals(mRequestedHealthCheckPackages)) {
                    syncRequired = true;
                    mRequestedHealthCheckPackages = packages;
                }
            } // else, we will sync requests when packages become ready
        }

        // Call outside lock to avoid holding lock when calling into the controller.
        if (syncRequired) {
            Slog.i(TAG, "Syncing health check requests for packages: "
                    + mRequestedHealthCheckPackages);
            mHealthCheckController.syncRequests(mRequestedHealthCheckPackages);
        }
    }

    /**
     * Updates the observers monitoring {@code packageName} that explicit health check has passed.
     *
     * <p> This update is strictly for registered observers at the time of the call
     * Observers that register after this signal will have no knowledge of prior signals and will
     * effectively behave as if the explicit health check hasn't passed for {@code packageName}.
     *
     * <p> {@code packageName} can still be considered failed if reported by
     * {@link #onPackageFailureLocked} before the package expires.
     *
     * <p> Triggered by components outside the system server when they are fully functional after an
     * update.
     */
    private void onHealthCheckPassed(String packageName) {
        Slog.i(TAG, "Health check passed for package: " + packageName);
        boolean isStateChanged = false;

        synchronized (mLock) {
            for (int observerIdx = 0; observerIdx < mAllObservers.size(); observerIdx++) {
                ObserverInternal observer = mAllObservers.valueAt(observerIdx);
                MonitoredPackage monitoredPackage = observer.packages.get(packageName);

                if (monitoredPackage != null) {
                    int oldState = monitoredPackage.getHealthCheckStateLocked();
                    int newState = monitoredPackage.tryPassHealthCheckLocked();
                    isStateChanged |= oldState != newState;
                }
            }
        }

        if (isStateChanged) {
            syncState("health check passed for " + packageName);
        }
    }

    private void onSupportedPackages(List<PackageConfig> supportedPackages) {
        boolean isStateChanged = false;

        Map<String, Long> supportedPackageTimeouts = new ArrayMap<>();
        Iterator<PackageConfig> it = supportedPackages.iterator();
        while (it.hasNext()) {
            PackageConfig info = it.next();
            supportedPackageTimeouts.put(info.getPackageName(), info.getHealthCheckTimeoutMillis());
        }

        synchronized (mLock) {
            Slog.d(TAG, "Received supported packages " + supportedPackages);
            Iterator<ObserverInternal> oit = mAllObservers.values().iterator();
            while (oit.hasNext()) {
                Iterator<MonitoredPackage> pit = oit.next().packages.values().iterator();
                while (pit.hasNext()) {
                    MonitoredPackage monitoredPackage = pit.next();
                    String packageName = monitoredPackage.getName();
                    int oldState = monitoredPackage.getHealthCheckStateLocked();
                    int newState;

                    if (supportedPackageTimeouts.containsKey(packageName)) {
                        // Supported packages become ACTIVE if currently INACTIVE
                        newState = monitoredPackage.setHealthCheckActiveLocked(
                                supportedPackageTimeouts.get(packageName));
                    } else {
                        // Unsupported packages are marked as PASSED unless already FAILED
                        newState = monitoredPackage.tryPassHealthCheckLocked();
                    }
                    isStateChanged |= oldState != newState;
                }
            }
        }

        if (isStateChanged) {
            syncState("updated health check supported packages " + supportedPackages);
        }
    }

    @GuardedBy("mLock")
    private Set<String> getPackagesPendingHealthChecksLocked() {
        Slog.d(TAG, "Getting all observed packages pending health checks");
        Set<String> packages = new ArraySet<>();
        Iterator<ObserverInternal> oit = mAllObservers.values().iterator();
        while (oit.hasNext()) {
            ObserverInternal observer = oit.next();
            Iterator<MonitoredPackage> pit =
                    observer.packages.values().iterator();
            while (pit.hasNext()) {
                MonitoredPackage monitoredPackage = pit.next();
                String packageName = monitoredPackage.getName();
                if (monitoredPackage.isPendingHealthChecksLocked()) {
                    packages.add(packageName);
                }
            }
        }
        return packages;
    }

    /**
     * Syncs the state of the observers.
     *
     * <p> Prunes all observers, saves new state to disk, syncs health check requests with the
     * health check service and schedules the next state sync.
     */
    private void syncState(String reason) {
        synchronized (mLock) {
            Slog.i(TAG, "Syncing state, reason: " + reason);
            pruneObserversLocked();

            saveToFileAsync();
            syncRequestsAsync();

            // Done syncing state, schedule the next state sync
            scheduleNextSyncStateLocked();
        }
    }

    private void syncStateWithScheduledReason() {
        syncState("scheduled");
    }

    @GuardedBy("mLock")
    private void scheduleNextSyncStateLocked() {
        long durationMs = getNextStateSyncMillisLocked();
        mShortTaskHandler.removeCallbacks(mSyncStateWithScheduledReason);
        if (durationMs == Long.MAX_VALUE) {
            Slog.i(TAG, "Cancelling state sync, nothing to sync");
            mUptimeAtLastStateSync = 0;
        } else {
            Slog.i(TAG, "Scheduling next state sync in " + durationMs + "ms");
            mUptimeAtLastStateSync = mSystemClock.uptimeMillis();
            mShortTaskHandler.postDelayed(mSyncStateWithScheduledReason, durationMs);
        }
    }

    /**
     * Returns the next duration in millis to sync the watchdog state.
     *
     * @returns Long#MAX_VALUE if there are no observed packages.
     */
    @GuardedBy("mLock")
    private long getNextStateSyncMillisLocked() {
        long shortestDurationMs = Long.MAX_VALUE;
        for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
            ArrayMap<String, MonitoredPackage> packages = mAllObservers.valueAt(oIndex).packages;
            for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                MonitoredPackage mp = packages.valueAt(pIndex);
                long duration = mp.getShortestScheduleDurationMsLocked();
                if (duration < shortestDurationMs) {
                    shortestDurationMs = duration;
                }
            }
        }
        return shortestDurationMs;
    }

    /**
     * Removes {@code elapsedMs} milliseconds from all durations on monitored packages
     * and updates other internal state.
     */
    @GuardedBy("mLock")
    private void pruneObserversLocked() {
        long elapsedMs = mUptimeAtLastStateSync == 0
                ? 0 : mSystemClock.uptimeMillis() - mUptimeAtLastStateSync;
        if (elapsedMs <= 0) {
            Slog.i(TAG, "Not pruning observers, elapsed time: " + elapsedMs + "ms");
            return;
        }

        Slog.i(TAG, "Removing " + elapsedMs + "ms from all packages on all observers");
        Iterator<ObserverInternal> it = mAllObservers.values().iterator();
        while (it.hasNext()) {
            ObserverInternal observer = it.next();
            Set<MonitoredPackage> failedPackages =
                    observer.prunePackagesLocked(elapsedMs);
            if (!failedPackages.isEmpty()) {
                onHealthCheckFailed(observer, failedPackages);
            }
            if (observer.packages.isEmpty() && (observer.registeredObserver == null
                    || !observer.registeredObserver.isPersistent())) {
                Slog.i(TAG, "Discarding observer " + observer.name + ". All packages expired");
                it.remove();
            }
        }
    }

    private void onHealthCheckFailed(ObserverInternal observer,
            Set<MonitoredPackage> failedPackages) {
        mLongTaskHandler.post(() -> {
            synchronized (mLock) {
                PackageHealthObserver registeredObserver = observer.registeredObserver;
                if (registeredObserver != null) {
                    Iterator<MonitoredPackage> it = failedPackages.iterator();
                    while (it.hasNext()) {
                        VersionedPackage versionedPkg = it.next().mPackage;
                        Slog.i(TAG, "Explicit health check failed for package " + versionedPkg);
                        registeredObserver.execute(versionedPkg,
                                PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK);
                    }
                }
            }
        });
    }

    @Nullable
    private VersionedPackage getVersionedPackage(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        if (pm == null || TextUtils.isEmpty(packageName)) {
            return null;
        }
        try {
            final long versionCode = pm.getPackageInfo(
                    packageName, 0 /* flags */).getLongVersionCode();
            return new VersionedPackage(packageName, versionCode);
        } catch (PackageManager.NameNotFoundException e) {
            return null;
        }
    }

    /**
     * Loads mAllObservers from file.
     *
     * <p>Note that this is <b>not</b> thread safe and should only called be called
     * from the constructor.
     */
    private void loadFromFile() {
        InputStream infile = null;
        mAllObservers.clear();
        try {
            infile = mPolicyFile.openRead();
            final XmlPullParser parser = Xml.newPullParser();
            parser.setInput(infile, StandardCharsets.UTF_8.name());
            XmlUtils.beginDocument(parser, TAG_PACKAGE_WATCHDOG);
            int outerDepth = parser.getDepth();
            while (XmlUtils.nextElementWithin(parser, outerDepth)) {
                ObserverInternal observer = ObserverInternal.read(parser, this);
                if (observer != null) {
                    mAllObservers.put(observer.name, observer);
                }
            }
        } catch (FileNotFoundException e) {
            // Nothing to monitor
        } catch (IOException | NumberFormatException | XmlPullParserException e) {
            Slog.wtf(TAG, "Unable to read monitored packages, deleting file", e);
            mPolicyFile.delete();
        } finally {
            IoUtils.closeQuietly(infile);
        }
    }

    /** Adds a {@link DeviceConfig#OnPropertiesChangedListener}. */
    private void setPropertyChangedListenerLocked() {
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_ROLLBACK,
                mContext.getMainExecutor(),
                (properties) -> {
                    if (!DeviceConfig.NAMESPACE_ROLLBACK.equals(properties.getNamespace())) {
                        return;
                    }
                    updateConfigs();
                });
    }

    /**
     * Health check is enabled or disabled after reading the flags
     * from DeviceConfig.
     */
    private void updateConfigs() {
        synchronized (mLock) {
            mTriggerFailureCount = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_WATCHDOG_TRIGGER_FAILURE_COUNT,
                    DEFAULT_TRIGGER_FAILURE_COUNT);
            if (mTriggerFailureCount <= 0) {
                mTriggerFailureCount = DEFAULT_TRIGGER_FAILURE_COUNT;
            }

            mTriggerFailureDurationMs = DeviceConfig.getInt(
                    DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_WATCHDOG_TRIGGER_DURATION_MILLIS,
                    DEFAULT_TRIGGER_FAILURE_DURATION_MS);
            if (mTriggerFailureDurationMs <= 0) {
                mTriggerFailureDurationMs = DEFAULT_TRIGGER_FAILURE_DURATION_MS;
            }

            setExplicitHealthCheckEnabled(DeviceConfig.getBoolean(
                    DeviceConfig.NAMESPACE_ROLLBACK,
                    PROPERTY_WATCHDOG_EXPLICIT_HEALTH_CHECK_ENABLED,
                    DEFAULT_EXPLICIT_HEALTH_CHECK_ENABLED));
        }
    }

    private void registerConnectivityModuleHealthListener() {
        // TODO: have an internal method to trigger a rollback by reporting high severity errors,
        // and rely on ActivityManager to inform the watchdog of severe network stack crashes
        // instead of having this listener in parallel.
        mConnectivityModuleConnector.registerHealthListener(
                packageName -> {
                    final VersionedPackage pkg = getVersionedPackage(packageName);
                    if (pkg == null) {
                        Slog.wtf(TAG, "NetworkStack failed but could not find its package");
                        return;
                    }
                    final List<VersionedPackage> pkgList = Collections.singletonList(pkg);
                    onPackageFailure(pkgList, FAILURE_REASON_EXPLICIT_HEALTH_CHECK);
                });
    }

    /**
     * Persists mAllObservers to file. Threshold information is ignored.
     */
    private boolean saveToFile() {
        Slog.i(TAG, "Saving observer state to file");
        synchronized (mLock) {
            FileOutputStream stream;
            try {
                stream = mPolicyFile.startWrite();
            } catch (IOException e) {
                Slog.w(TAG, "Cannot update monitored packages", e);
                return false;
            }

            try {
                XmlSerializer out = new FastXmlSerializer();
                out.setOutput(stream, StandardCharsets.UTF_8.name());
                out.startDocument(null, true);
                out.startTag(null, TAG_PACKAGE_WATCHDOG);
                out.attribute(null, ATTR_VERSION, Integer.toString(DB_VERSION));
                for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
                    mAllObservers.valueAt(oIndex).writeLocked(out);
                }
                out.endTag(null, TAG_PACKAGE_WATCHDOG);
                out.endDocument();
                mPolicyFile.finishWrite(stream);
                return true;
            } catch (IOException e) {
                Slog.w(TAG, "Failed to save monitored packages, restoring backup", e);
                mPolicyFile.failWrite(stream);
                return false;
            } finally {
                IoUtils.closeQuietly(stream);
            }
        }
    }

    private void saveToFileAsync() {
        if (!mLongTaskHandler.hasCallbacks(mSaveToFile)) {
            mLongTaskHandler.post(mSaveToFile);
        }
    }

    /** Dump status of every observer in mAllObservers. */
    public void dump(IndentingPrintWriter pw) {
        pw.println("Package Watchdog status");
        pw.increaseIndent();
        synchronized (mLock) {
            for (String observerName : mAllObservers.keySet()) {
                pw.println("Observer name: " + observerName);
                pw.increaseIndent();
                ObserverInternal observerInternal = mAllObservers.get(observerName);
                observerInternal.dump(pw);
                pw.decreaseIndent();
            }
        }
    }

    /**
     * Represents an observer monitoring a set of packages along with the failure thresholds for
     * each package.
     *
     * <p> Note, the PackageWatchdog#mLock must always be held when reading or writing
     * instances of this class.
     */
    private static class ObserverInternal {
        public final String name;
        @GuardedBy("mLock")
        public final ArrayMap<String, MonitoredPackage> packages = new ArrayMap<>();
        @Nullable
        @GuardedBy("mLock")
        public PackageHealthObserver registeredObserver;

        ObserverInternal(String name, List<MonitoredPackage> packages) {
            this.name = name;
            updatePackagesLocked(packages);
        }

        /**
         * Writes important {@link MonitoredPackage} details for this observer to file.
         * Does not persist any package failure thresholds.
         */
        @GuardedBy("mLock")
        public boolean writeLocked(XmlSerializer out) {
            try {
                out.startTag(null, TAG_OBSERVER);
                out.attribute(null, ATTR_NAME, name);
                for (int i = 0; i < packages.size(); i++) {
                    MonitoredPackage p = packages.valueAt(i);
                    p.writeLocked(out);
                }
                out.endTag(null, TAG_OBSERVER);
                return true;
            } catch (IOException e) {
                Slog.w(TAG, "Cannot save observer", e);
                return false;
            }
        }

        @GuardedBy("mLock")
        public void updatePackagesLocked(List<MonitoredPackage> packages) {
            for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                MonitoredPackage p = packages.get(pIndex);
                this.packages.put(p.getName(), p);
            }
        }

        /**
         * Reduces the monitoring durations of all packages observed by this observer by
         * {@code elapsedMs}. If any duration is less than 0, the package is removed from
         * observation. If any health check duration is less than 0, the health check result
         * is evaluated.
         *
         * @return a {@link Set} of packages that were removed from the observer without explicit
         * health check passing, or an empty list if no package expired for which an explicit health
         * check was still pending
         */
        @GuardedBy("mLock")
        private Set<MonitoredPackage> prunePackagesLocked(long elapsedMs) {
            Set<MonitoredPackage> failedPackages = new ArraySet<>();
            Iterator<MonitoredPackage> it = packages.values().iterator();
            while (it.hasNext()) {
                MonitoredPackage p = it.next();
                int oldState = p.getHealthCheckStateLocked();
                int newState = p.handleElapsedTimeLocked(elapsedMs);
                if (oldState != HealthCheckState.FAILED
                        && newState == HealthCheckState.FAILED) {
                    Slog.i(TAG, "Package " + p.getName() + " failed health check");
                    failedPackages.add(p);
                }
                if (p.isExpiredLocked()) {
                    it.remove();
                }
            }
            return failedPackages;
        }

        /**
         * Increments failure counts of {@code packageName}.
         * @returns {@code true} if failure threshold is exceeded, {@code false} otherwise
         */
        @GuardedBy("mLock")
        public boolean onPackageFailureLocked(String packageName) {
            if (packages.get(packageName) == null && registeredObserver.isPersistent()
                    && registeredObserver.mayObservePackage(packageName)) {
                packages.put(packageName, sPackageWatchdog.newMonitoredPackage(
                        packageName, DEFAULT_OBSERVING_DURATION_MS, false));
            }
            MonitoredPackage p = packages.get(packageName);
            if (p != null) {
                return p.onFailureLocked();
            }
            return false;
        }

        /**
         * Returns one ObserverInternal from the {@code parser} and advances its state.
         *
         * <p>Note that this method is <b>not</b> thread safe. It should only be called from
         * #loadFromFile which in turn is only called on construction of the
         * singleton PackageWatchdog.
         **/
        public static ObserverInternal read(XmlPullParser parser, PackageWatchdog watchdog) {
            String observerName = null;
            if (TAG_OBSERVER.equals(parser.getName())) {
                observerName = parser.getAttributeValue(null, ATTR_NAME);
                if (TextUtils.isEmpty(observerName)) {
                    Slog.wtf(TAG, "Unable to read observer name");
                    return null;
                }
            }
            List<MonitoredPackage> packages = new ArrayList<>();
            int innerDepth = parser.getDepth();
            try {
                while (XmlUtils.nextElementWithin(parser, innerDepth)) {
                    if (TAG_PACKAGE.equals(parser.getName())) {
                        try {
                            String packageName = parser.getAttributeValue(null, ATTR_NAME);
                            long duration = Long.parseLong(
                                    parser.getAttributeValue(null, ATTR_DURATION));
                            long healthCheckDuration = Long.parseLong(
                                    parser.getAttributeValue(null,
                                            ATTR_EXPLICIT_HEALTH_CHECK_DURATION));
                            boolean hasPassedHealthCheck = Boolean.parseBoolean(
                                    parser.getAttributeValue(null, ATTR_PASSED_HEALTH_CHECK));
                            MonitoredPackage pkg = watchdog.newMonitoredPackage(packageName,
                                    duration, healthCheckDuration, hasPassedHealthCheck);
                            if (pkg != null) {
                                packages.add(pkg);
                            }
                        } catch (NumberFormatException e) {
                            Slog.wtf(TAG, "Skipping package for observer " + observerName, e);
                            continue;
                        }
                    }
                }
            } catch (XmlPullParserException | IOException e) {
                Slog.wtf(TAG, "Unable to read observer " + observerName, e);
                return null;
            }
            if (packages.isEmpty()) {
                return null;
            }
            return new ObserverInternal(observerName, packages);
        }

        /** Dumps information about this observer and the packages it watches. */
        public void dump(IndentingPrintWriter pw) {
            boolean isPersistent = registeredObserver != null && registeredObserver.isPersistent();
            pw.println("Persistent: " + isPersistent);
            for (String packageName : packages.keySet()) {
                MonitoredPackage p = packages.get(packageName);
                pw.println(packageName +  ": ");
                pw.increaseIndent();
                pw.println("# Failures: " + p.mFailureHistory.size());
                pw.println("Monitoring duration remaining: " + p.mDurationMs + "ms");
                pw.println("Explicit health check duration: " + p.mHealthCheckDurationMs + "ms");
                pw.println("Health check state: " + p.toString(p.mHealthCheckState));
                pw.decreaseIndent();
            }
        }
    }

    @Retention(SOURCE)
    @IntDef(value = {
            HealthCheckState.ACTIVE,
            HealthCheckState.INACTIVE,
            HealthCheckState.PASSED,
            HealthCheckState.FAILED})
    public @interface HealthCheckState {
        // The package has not passed health check but has requested a health check
        int ACTIVE = 0;
        // The package has not passed health check and has not requested a health check
        int INACTIVE = 1;
        // The package has passed health check
        int PASSED = 2;
        // The package has failed health check
        int FAILED = 3;
    }

    MonitoredPackage newMonitoredPackage(
            String name, long durationMs, boolean hasPassedHealthCheck) {
        return newMonitoredPackage(name, durationMs, Long.MAX_VALUE, hasPassedHealthCheck);
    }

    MonitoredPackage newMonitoredPackage(String name, long durationMs, long healthCheckDurationMs,
            boolean hasPassedHealthCheck) {
        VersionedPackage pkg = getVersionedPackage(name);
        if (pkg == null) {
            return null;
        }
        return new MonitoredPackage(pkg, durationMs, healthCheckDurationMs, hasPassedHealthCheck);
    }

    /**
     * Represents a package and its health check state along with the time
     * it should be monitored for.
     *
     * <p> Note, the PackageWatchdog#mLock must always be held when reading or writing
     * instances of this class.
     */
    class MonitoredPackage {
        private final VersionedPackage mPackage;
        // Times when package failures happen sorted in ascending order
        @GuardedBy("mLock")
        private final LongArrayQueue mFailureHistory = new LongArrayQueue();
        // One of STATE_[ACTIVE|INACTIVE|PASSED|FAILED]. Updated on construction and after
        // methods that could change the health check state: handleElapsedTimeLocked and
        // tryPassHealthCheckLocked
        private int mHealthCheckState = HealthCheckState.INACTIVE;
        // Whether an explicit health check has passed.
        // This value in addition with mHealthCheckDurationMs determines the health check state
        // of the package, see #getHealthCheckStateLocked
        @GuardedBy("mLock")
        private boolean mHasPassedHealthCheck;
        // System uptime duration to monitor package.
        @GuardedBy("mLock")
        private long mDurationMs;
        // System uptime duration to check the result of an explicit health check
        // Initially, MAX_VALUE until we get a value from the health check service
        // and request health checks.
        // This value in addition with mHasPassedHealthCheck determines the health check state
        // of the package, see #getHealthCheckStateLocked
        @GuardedBy("mLock")
        private long mHealthCheckDurationMs = Long.MAX_VALUE;

        private MonitoredPackage(VersionedPackage pkg, long durationMs,
                long healthCheckDurationMs, boolean hasPassedHealthCheck) {
            mPackage = pkg;
            mDurationMs = durationMs;
            mHealthCheckDurationMs = healthCheckDurationMs;
            mHasPassedHealthCheck = hasPassedHealthCheck;
            updateHealthCheckStateLocked();
        }

        /** Writes the salient fields to disk using {@code out}. */
        @GuardedBy("mLock")
        public void writeLocked(XmlSerializer out) throws IOException {
            out.startTag(null, TAG_PACKAGE);
            out.attribute(null, ATTR_NAME, getName());
            out.attribute(null, ATTR_DURATION, String.valueOf(mDurationMs));
            out.attribute(null, ATTR_EXPLICIT_HEALTH_CHECK_DURATION,
                    String.valueOf(mHealthCheckDurationMs));
            out.attribute(null, ATTR_PASSED_HEALTH_CHECK,
                    String.valueOf(mHasPassedHealthCheck));
            out.endTag(null, TAG_PACKAGE);
        }

        /**
         * Increment package failures or resets failure count depending on the last package failure.
         *
         * @return {@code true} if failure count exceeds a threshold, {@code false} otherwise
         */
        @GuardedBy("mLock")
        public boolean onFailureLocked() {
            // Sliding window algorithm: find out if there exists a window containing failures >=
            // mTriggerFailureCount.
            final long now = mSystemClock.uptimeMillis();
            mFailureHistory.addLast(now);
            while (now - mFailureHistory.peekFirst() > mTriggerFailureDurationMs) {
                // Prune values falling out of the window
                mFailureHistory.removeFirst();
            }
            boolean failed = mFailureHistory.size() >= mTriggerFailureCount;
            if (failed) {
                mFailureHistory.clear();
            }
            return failed;
        }

        /**
         * Sets the initial health check duration.
         *
         * @return the new health check state
         */
        @GuardedBy("mLock")
        public int setHealthCheckActiveLocked(long initialHealthCheckDurationMs) {
            if (initialHealthCheckDurationMs <= 0) {
                Slog.wtf(TAG, "Cannot set non-positive health check duration "
                        + initialHealthCheckDurationMs + "ms for package " + getName()
                        + ". Using total duration " + mDurationMs + "ms instead");
                initialHealthCheckDurationMs = mDurationMs;
            }
            if (mHealthCheckState == HealthCheckState.INACTIVE) {
                // Transitions to ACTIVE
                mHealthCheckDurationMs = initialHealthCheckDurationMs;
            }
            return updateHealthCheckStateLocked();
        }

        /**
         * Updates the monitoring durations of the package.
         *
         * @return the new health check state
         */
        @GuardedBy("mLock")
        public int handleElapsedTimeLocked(long elapsedMs) {
            if (elapsedMs <= 0) {
                Slog.w(TAG, "Cannot handle non-positive elapsed time for package " + getName());
                return mHealthCheckState;
            }
            // Transitions to FAILED if now <= 0 and health check not passed
            mDurationMs -= elapsedMs;
            if (mHealthCheckState == HealthCheckState.ACTIVE) {
                // We only update health check durations if we have #setHealthCheckActiveLocked
                // This ensures we don't leave the INACTIVE state for an unexpected elapsed time
                // Transitions to FAILED if now <= 0 and health check not passed
                mHealthCheckDurationMs -= elapsedMs;
            }
            return updateHealthCheckStateLocked();
        }

        /**
         * Marks the health check as passed and transitions to {@link HealthCheckState.PASSED}
         * if not yet {@link HealthCheckState.FAILED}.
         *
         * @return the new {@link HealthCheckState health check state}
         */
        @GuardedBy("mLock")
        @HealthCheckState
        public int tryPassHealthCheckLocked() {
            if (mHealthCheckState != HealthCheckState.FAILED) {
                // FAILED is a final state so only pass if we haven't failed
                // Transition to PASSED
                mHasPassedHealthCheck = true;
            }
            return updateHealthCheckStateLocked();
        }

        /** Returns the monitored package name. */
        private String getName() {
            return mPackage.getPackageName();
        }

        /**
         * Returns the current {@link HealthCheckState health check state}.
         */
        @GuardedBy("mLock")
        @HealthCheckState
        public int getHealthCheckStateLocked() {
            return mHealthCheckState;
        }

        /**
         * Returns the shortest duration before the package should be scheduled for a prune.
         *
         * @return the duration or {@link Long#MAX_VALUE} if the package should not be scheduled
         */
        @GuardedBy("mLock")
        public long getShortestScheduleDurationMsLocked() {
            // Consider health check duration only if #isPendingHealthChecksLocked is true
            return Math.min(toPositive(mDurationMs),
                    isPendingHealthChecksLocked()
                    ? toPositive(mHealthCheckDurationMs) : Long.MAX_VALUE);
        }

        /**
         * Returns {@code true} if the total duration left to monitor the package is less than or
         * equal to 0 {@code false} otherwise.
         */
        @GuardedBy("mLock")
        public boolean isExpiredLocked() {
            return mDurationMs <= 0;
        }

        /**
         * Returns {@code true} if the package, {@link #getName} is expecting health check results
         * {@code false} otherwise.
         */
        @GuardedBy("mLock")
        public boolean isPendingHealthChecksLocked() {
            return mHealthCheckState == HealthCheckState.ACTIVE
                    || mHealthCheckState == HealthCheckState.INACTIVE;
        }

        /**
         * Updates the health check state based on {@link #mHasPassedHealthCheck}
         * and {@link #mHealthCheckDurationMs}.
         *
         * @return the new {@link HealthCheckState health check state}
         */
        @GuardedBy("mLock")
        @HealthCheckState
        private int updateHealthCheckStateLocked() {
            int oldState = mHealthCheckState;
            if (mHasPassedHealthCheck) {
                // Set final state first to avoid ambiguity
                mHealthCheckState = HealthCheckState.PASSED;
            } else if (mHealthCheckDurationMs <= 0 || mDurationMs <= 0) {
                // Set final state first to avoid ambiguity
                mHealthCheckState = HealthCheckState.FAILED;
            } else if (mHealthCheckDurationMs == Long.MAX_VALUE) {
                mHealthCheckState = HealthCheckState.INACTIVE;
            } else {
                mHealthCheckState = HealthCheckState.ACTIVE;
            }
            Slog.i(TAG, "Updated health check state for package " + getName() + ": "
                    + toString(oldState) + " -> " + toString(mHealthCheckState));
            return mHealthCheckState;
        }

        /** Returns a {@link String} representation of the current health check state. */
        private String toString(@HealthCheckState int state) {
            switch (state) {
                case HealthCheckState.ACTIVE:
                    return "ACTIVE";
                case HealthCheckState.INACTIVE:
                    return "INACTIVE";
                case HealthCheckState.PASSED:
                    return "PASSED";
                case HealthCheckState.FAILED:
                    return "FAILED";
                default:
                    return "UNKNOWN";
            }
        }

        /** Returns {@code value} if it is greater than 0 or {@link Long#MAX_VALUE} otherwise. */
        private long toPositive(long value) {
            return value > 0 ? value : Long.MAX_VALUE;
        }
    }

    /**
     * Handles the thresholding logic for system server boots.
     */
    static class BootThreshold {

        private final int mBootTriggerCount;
        private final long mTriggerWindow;

        BootThreshold(int bootTriggerCount, long triggerWindow) {
            this.mBootTriggerCount = bootTriggerCount;
            this.mTriggerWindow = triggerWindow;
        }

        public void reset() {
            setStart(0);
            setCount(0);
        }

        private int getCount() {
            return SystemProperties.getInt(PROP_RESCUE_BOOT_COUNT, 0);
        }

        private void setCount(int count) {
            SystemProperties.set(PROP_RESCUE_BOOT_COUNT, Integer.toString(count));
        }

        public long getStart() {
            return SystemProperties.getLong(PROP_RESCUE_BOOT_START, 0);
        }

        public void setStart(long start) {
            final long now = android.os.SystemClock.elapsedRealtime();
            final long newStart = MathUtils.constrain(start, 0, now);
            SystemProperties.set(PROP_RESCUE_BOOT_START, Long.toString(newStart));
        }

        /** Increments the boot counter, and returns whether the device is bootlooping. */
        public boolean incrementAndTest() {
            final long now = android.os.SystemClock.elapsedRealtime();
            if (now - getStart() < 0) {
                Slog.e(TAG, "Window was less than zero. Resetting start to current time.");
                setStart(now);
            }
            final long window = now - getStart();
            if (window >= mTriggerWindow) {
                setCount(1);
                setStart(now);
                return false;
            } else {
                int count = getCount() + 1;
                setCount(count);
                EventLogTags.writeRescueNote(Process.ROOT_UID, count, window);
                return count >= mBootTriggerCount;
            }
        }

    }
}
