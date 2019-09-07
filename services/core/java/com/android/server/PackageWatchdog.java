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
import android.provider.DeviceConfig;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.Slog;
import android.util.Xml;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.BackgroundThread;
import com.android.internal.util.FastXmlSerializer;
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

    // Duration to count package failures before it resets to 0
    private static final int DEFAULT_TRIGGER_FAILURE_DURATION_MS =
            (int) TimeUnit.MINUTES.toMillis(1);
    // Number of package failures within the duration above before we notify observers
    private static final int DEFAULT_TRIGGER_FAILURE_COUNT = 5;
    // Whether explicit health checks are enabled or not
    private static final boolean DEFAULT_EXPLICIT_HEALTH_CHECK_ENABLED = true;

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
        loadFromFile();
    }

    /** Creates or gets singleton instance of PackageWatchdog. */
    public static PackageWatchdog getInstance(Context context) {
        synchronized (PackageWatchdog.class) {
            if (sPackageWatchdog == null) {
                sPackageWatchdog = new PackageWatchdog(context);
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
     * Registers {@code observer} to listen for package failures
     *
     * <p>Observers are expected to call this on boot. It does not specify any packages but
     * it will resume observing any packages requested from a previous boot.
     */
    public void registerHealthObserver(PackageHealthObserver observer) {
        synchronized (mLock) {
            ObserverInternal internalObserver = mAllObservers.get(observer.getName());
            if (internalObserver != null) {
                internalObserver.mRegisteredObserver = observer;
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
     * @throws IllegalArgumentException if {@code packageNames} is empty
     * or {@code durationMs} is less than 1
     */
    public void startObservingHealth(PackageHealthObserver observer, List<String> packageNames,
            long durationMs) {
        if (packageNames.isEmpty()) {
            Slog.wtf(TAG, "No packages to observe, " + observer.getName());
            return;
        }
        if (durationMs < 1) {
            // TODO: Instead of failing, monitor for default? 48hrs?
            throw new IllegalArgumentException("Invalid duration " + durationMs + "ms for observer "
                    + observer.getName() + ". Not observing packages " + packageNames);
        }

        List<MonitoredPackage> packages = new ArrayList<>();
        for (int i = 0; i < packageNames.size(); i++) {
            // Health checks not available yet so health check state will start INACTIVE
            packages.add(new MonitoredPackage(packageNames.get(i), durationMs, false));
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
     * Called when a process fails either due to a crash or ANR.
     *
     * <p>For each package contained in the process, one registered observer with the least user
     * impact will be notified for mitigation.
     *
     * <p>This method could be called frequently if there is a severe problem on the device.
     */
    public void onPackageFailure(List<VersionedPackage> packages) {
        mLongTaskHandler.post(() -> {
            synchronized (mLock) {
                if (mAllObservers.isEmpty()) {
                    return;
                }

                for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                    VersionedPackage versionedPackage = packages.get(pIndex);
                    // Observer that will receive failure for versionedPackage
                    PackageHealthObserver currentObserverToNotify = null;
                    int currentObserverImpact = Integer.MAX_VALUE;

                    // Find observer with least user impact
                    for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
                        ObserverInternal observer = mAllObservers.valueAt(oIndex);
                        PackageHealthObserver registeredObserver = observer.mRegisteredObserver;
                        if (registeredObserver != null
                                && observer.onPackageFailureLocked(
                                        versionedPackage.getPackageName())) {
                            int impact = registeredObserver.onHealthCheckFailed(versionedPackage);
                            if (impact != PackageHealthObserverImpact.USER_IMPACT_NONE
                                    && impact < currentObserverImpact) {
                                currentObserverToNotify = registeredObserver;
                                currentObserverImpact = impact;
                            }
                        }
                    }

                    // Execute action with least user impact
                    if (currentObserverToNotify != null) {
                        currentObserverToNotify.execute(versionedPackage);
                    }
                }
            }
        });
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
         * @return any one of {@link PackageHealthObserverImpact} to express the impact
         * to the user on {@link #execute}
         */
        @PackageHealthObserverImpact int onHealthCheckFailed(VersionedPackage versionedPackage);

        /**
         * Executes mitigation for {@link #onHealthCheckFailed}.
         *
         * @return {@code true} if action was executed successfully, {@code false} otherwise
         */
        boolean execute(VersionedPackage versionedPackage);

        // TODO(b/120598832): Ensure uniqueness?
        /**
         * Identifier for the observer, should not change across device updates otherwise the
         * watchdog may drop observing packages with the old name.
         */
        String getName();
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
        Set<String> packages = null;
        synchronized (mLock) {
            if (mIsPackagesReady) {
                packages = getPackagesPendingHealthChecksLocked();
            } // else, we will sync requests when packages become ready
        }

        // Call outside lock to avoid holding lock when calling into the controller.
        if (packages != null) {
            Slog.i(TAG, "Syncing health check requests for packages: " + packages);
            mHealthCheckController.syncRequests(packages);
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
                MonitoredPackage monitoredPackage = observer.mPackages.get(packageName);

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
                Iterator<MonitoredPackage> pit = oit.next().mPackages.values().iterator();
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
                    observer.mPackages.values().iterator();
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
            ArrayMap<String, MonitoredPackage> packages = mAllObservers.valueAt(oIndex).mPackages;
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
            if (observer.mPackages.isEmpty()) {
                Slog.i(TAG, "Discarding observer " + observer.mName + ". All packages expired");
                it.remove();
            }
        }
    }

    private void onHealthCheckFailed(ObserverInternal observer,
            Set<MonitoredPackage> failedPackages) {
        mLongTaskHandler.post(() -> {
            synchronized (mLock) {
                PackageHealthObserver registeredObserver = observer.mRegisteredObserver;
                if (registeredObserver != null) {
                    Iterator<MonitoredPackage> it = failedPackages.iterator();
                    while (it.hasNext()) {
                        String failedPackage = it.next().getName();
                        Slog.i(TAG, "Explicit health check failed for package " + failedPackage);
                        VersionedPackage versionedPkg = getVersionedPackage(failedPackage);
                        if (versionedPkg == null) {
                            Slog.w(TAG, "Explicit health check failed but could not find package "
                                    + failedPackage);
                            // TODO(b/120598832): Skip. We only continue to pass tests for now since
                            // the tests don't install any packages
                            versionedPkg = new VersionedPackage(failedPackage, 0L);
                        }
                        registeredObserver.execute(versionedPkg);
                    }
                }
            }
        });
    }

    @Nullable
    private VersionedPackage getVersionedPackage(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
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
                    mAllObservers.put(observer.mName, observer);
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
                mTriggerFailureDurationMs = DEFAULT_TRIGGER_FAILURE_COUNT;
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
                    // This is a severe failure and recovery should be attempted immediately.
                    // TODO: have a better way to handle such failures.
                    final List<VersionedPackage> pkgList = Collections.singletonList(pkg);
                    final long failureCount = getTriggerFailureCount();
                    for (int i = 0; i < failureCount; i++) {
                        onPackageFailure(pkgList);
                    }
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

    /**
     * Represents an observer monitoring a set of packages along with the failure thresholds for
     * each package.
     *
     * <p> Note, the PackageWatchdog#mLock must always be held when reading or writing
     * instances of this class.
     */
    //TODO(b/120598832): Remove 'm' from non-private fields
    private static class ObserverInternal {
        public final String mName;
        //TODO(b/120598832): Add getter for mPackages
        @GuardedBy("mLock")
        public final ArrayMap<String, MonitoredPackage> mPackages = new ArrayMap<>();
        @Nullable
        @GuardedBy("mLock")
        public PackageHealthObserver mRegisteredObserver;

        ObserverInternal(String name, List<MonitoredPackage> packages) {
            mName = name;
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
                out.attribute(null, ATTR_NAME, mName);
                for (int i = 0; i < mPackages.size(); i++) {
                    MonitoredPackage p = mPackages.valueAt(i);
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
                mPackages.put(p.mName, p);
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
            Iterator<MonitoredPackage> it = mPackages.values().iterator();
            while (it.hasNext()) {
                MonitoredPackage p = it.next();
                int oldState = p.getHealthCheckStateLocked();
                int newState = p.handleElapsedTimeLocked(elapsedMs);
                if (oldState != MonitoredPackage.STATE_FAILED
                        && newState == MonitoredPackage.STATE_FAILED) {
                    Slog.i(TAG, "Package " + p.mName + " failed health check");
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
            MonitoredPackage p = mPackages.get(packageName);
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
                            if (!TextUtils.isEmpty(packageName)) {
                                packages.add(watchdog.new MonitoredPackage(packageName, duration,
                                        healthCheckDuration, hasPassedHealthCheck));
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
    }

    /**
     * Represents a package and its health check state along with the time
     * it should be monitored for.
     *
     * <p> Note, the PackageWatchdog#mLock must always be held when reading or writing
     * instances of this class.
     */
    class MonitoredPackage {
        // Health check states
        // TODO(b/120598832): Prefix with HEALTH_CHECK
        // mName has not passed health check but has requested a health check
        public static final int STATE_ACTIVE = 0;
        // mName has not passed health check and has not requested a health check
        public static final int STATE_INACTIVE = 1;
        // mName has passed health check
        public static final int STATE_PASSED = 2;
        // mName has failed health check
        public static final int STATE_FAILED = 3;

        //TODO(b/120598832): VersionedPackage?
        private final String mName;
        // One of STATE_[ACTIVE|INACTIVE|PASSED|FAILED]. Updated on construction and after
        // methods that could change the health check state: handleElapsedTimeLocked and
        // tryPassHealthCheckLocked
        private int mHealthCheckState = STATE_INACTIVE;
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
        // System uptime of first package failure
        @GuardedBy("mLock")
        private long mUptimeStartMs;
        // Number of failures since mUptimeStartMs
        @GuardedBy("mLock")
        private int mFailures;

        MonitoredPackage(String name, long durationMs, boolean hasPassedHealthCheck) {
            this(name, durationMs, Long.MAX_VALUE, hasPassedHealthCheck);
        }

        MonitoredPackage(String name, long durationMs, long healthCheckDurationMs,
                boolean hasPassedHealthCheck) {
            mName = name;
            mDurationMs = durationMs;
            mHealthCheckDurationMs = healthCheckDurationMs;
            mHasPassedHealthCheck = hasPassedHealthCheck;
            updateHealthCheckStateLocked();
        }

        /** Writes the salient fields to disk using {@code out}. */
        @GuardedBy("mLock")
        public void writeLocked(XmlSerializer out) throws IOException {
            out.startTag(null, TAG_PACKAGE);
            out.attribute(null, ATTR_NAME, mName);
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
            final long now = mSystemClock.uptimeMillis();
            final long duration = now - mUptimeStartMs;
            if (duration > mTriggerFailureDurationMs) {
                // TODO(b/120598832): Reseting to 1 is not correct
                // because there may be more than 1 failure in the last trigger window from now
                // This is the RescueParty impl, will leave for now
                mFailures = 1;
                mUptimeStartMs = now;
            } else {
                mFailures++;
            }
            boolean failed = mFailures >= mTriggerFailureCount;
            if (failed) {
                mFailures = 0;
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
                        + initialHealthCheckDurationMs + "ms for package " + mName
                        + ". Using total duration " + mDurationMs + "ms instead");
                initialHealthCheckDurationMs = mDurationMs;
            }
            if (mHealthCheckState == STATE_INACTIVE) {
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
                Slog.w(TAG, "Cannot handle non-positive elapsed time for package " + mName);
                return mHealthCheckState;
            }
            // Transitions to FAILED if now <= 0 and health check not passed
            mDurationMs -= elapsedMs;
            if (mHealthCheckState == STATE_ACTIVE) {
                // We only update health check durations if we have #setHealthCheckActiveLocked
                // This ensures we don't leave the INACTIVE state for an unexpected elapsed time
                // Transitions to FAILED if now <= 0 and health check not passed
                mHealthCheckDurationMs -= elapsedMs;
            }
            return updateHealthCheckStateLocked();
        }

        /**
         * Marks the health check as passed and transitions to {@link #STATE_PASSED}
         * if not yet {@link #STATE_FAILED}.
         *
         * @return the new health check state
         */
        @GuardedBy("mLock")
        public int tryPassHealthCheckLocked() {
            if (mHealthCheckState != STATE_FAILED) {
                // FAILED is a final state so only pass if we haven't failed
                // Transition to PASSED
                mHasPassedHealthCheck = true;
            }
            return updateHealthCheckStateLocked();
        }

        /** Returns the monitored package name. */
        private String getName() {
            return mName;
        }

        //TODO(b/120598832): IntDef
        /**
         * Returns the current health check state, any of {@link #STATE_ACTIVE},
         * {@link #STATE_INACTIVE} or {@link #STATE_PASSED}
         */
        @GuardedBy("mLock")
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
            return mHealthCheckState == STATE_ACTIVE || mHealthCheckState == STATE_INACTIVE;
        }

        /**
         * Updates the health check state based on {@link #mHasPassedHealthCheck}
         * and {@link #mHealthCheckDurationMs}.
         *
         * @return the new health check state
         */
        @GuardedBy("mLock")
        private int updateHealthCheckStateLocked() {
            int oldState = mHealthCheckState;
            if (mHasPassedHealthCheck) {
                // Set final state first to avoid ambiguity
                mHealthCheckState = STATE_PASSED;
            } else if (mHealthCheckDurationMs <= 0 || mDurationMs <= 0) {
                // Set final state first to avoid ambiguity
                mHealthCheckState = STATE_FAILED;
            } else if (mHealthCheckDurationMs == Long.MAX_VALUE) {
                mHealthCheckState = STATE_INACTIVE;
            } else {
                mHealthCheckState = STATE_ACTIVE;
            }
            Slog.i(TAG, "Updated health check state for package " + mName + ": "
                    + toString(oldState) + " -> " + toString(mHealthCheckState));
            return mHealthCheckState;
        }

        /** Returns a {@link String} representation of the current health check state. */
        private String toString(int state) {
            switch (state) {
                case STATE_ACTIVE:
                    return "ACTIVE";
                case STATE_INACTIVE:
                    return "INACTIVE";
                case STATE_PASSED:
                    return "PASSED";
                case STATE_FAILED:
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
}
