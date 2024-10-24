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

import static android.content.Intent.ACTION_REBOOT;
import static android.content.Intent.ACTION_SHUTDOWN;
import static android.service.watchdog.ExplicitHealthCheckService.PackageConfig;

import static com.android.server.crashrecovery.CrashRecoveryUtils.dumpCrashRecoveryEvents;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.FlaggedApi;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.VersionedPackage;
import android.crashrecovery.flags.Flags;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.os.SystemProperties;
import android.provider.DeviceConfig;
import android.sysprop.CrashRecoveryProperties;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.AtomicFile;
import android.util.EventLog;
import android.util.IndentingPrintWriter;
import android.util.LongArrayQueue;
import android.util.Slog;
import android.util.Xml;
import android.util.XmlUtils;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.BackgroundThread;
import com.android.modules.utils.TypedXmlPullParser;
import com.android.modules.utils.TypedXmlSerializer;

import libcore.io.IoUtils;

import org.xmlpull.v1.XmlPullParserException;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Monitors the health of packages on the system and notifies interested observers when packages
 * fail. On failure, the registered observer with the least user impacting mitigation will
 * be notified.
 * @hide
 */
@FlaggedApi(Flags.FLAG_ENABLE_CRASHRECOVERY)
@SystemApi(client = SystemApi.Client.SYSTEM_SERVER)
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


    /** Reason for package failure could not be determined. */
    public static final int FAILURE_REASON_UNKNOWN = 0;

    /** The package had a native crash. */
    public static final int FAILURE_REASON_NATIVE_CRASH = 1;

    /** The package failed an explicit health check. */
    public static final int FAILURE_REASON_EXPLICIT_HEALTH_CHECK = 2;

    /** The app crashed. */
    public static final int FAILURE_REASON_APP_CRASH = 3;

    /** The app was not responding. */
    public static final int FAILURE_REASON_APP_NOT_RESPONDING = 4;

    /** The device was boot looping. */
    public static final int FAILURE_REASON_BOOT_LOOP = 5;

    /** @hide */
    @IntDef(prefix = { "FAILURE_REASON_" }, value = {
            FAILURE_REASON_UNKNOWN,
            FAILURE_REASON_NATIVE_CRASH,
            FAILURE_REASON_EXPLICIT_HEALTH_CHECK,
            FAILURE_REASON_APP_CRASH,
            FAILURE_REASON_APP_NOT_RESPONDING,
            FAILURE_REASON_BOOT_LOOP
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
    // Sliding window for tracking how many mitigation calls were made for a package.
    @VisibleForTesting
    static final long DEFAULT_DEESCALATION_WINDOW_MS = TimeUnit.HOURS.toMillis(1);
    // Whether explicit health checks are enabled or not
    private static final boolean DEFAULT_EXPLICIT_HEALTH_CHECK_ENABLED = true;

    @VisibleForTesting
    static final int DEFAULT_BOOT_LOOP_TRIGGER_COUNT = 5;

    static final long DEFAULT_BOOT_LOOP_TRIGGER_WINDOW_MS = TimeUnit.MINUTES.toMillis(10);

    // Time needed to apply mitigation
    private static final String MITIGATION_WINDOW_MS =
            "persist.device_config.configuration.mitigation_window_ms";
    @VisibleForTesting
    static final long DEFAULT_MITIGATION_WINDOW_MS = TimeUnit.SECONDS.toMillis(5);

    // Threshold level at which or above user might experience significant disruption.
    private static final String MAJOR_USER_IMPACT_LEVEL_THRESHOLD =
            "persist.device_config.configuration.major_user_impact_level_threshold";
    private static final int DEFAULT_MAJOR_USER_IMPACT_LEVEL_THRESHOLD =
            PackageHealthObserverImpact.USER_IMPACT_LEVEL_71;

    // Comma separated list of all packages exempt from user impact level threshold. If a package
    // in the list is crash looping, all the mitigations including factory reset will be performed.
    private static final String PACKAGES_EXEMPT_FROM_IMPACT_LEVEL_THRESHOLD =
            "persist.device_config.configuration.packages_exempt_from_impact_level_threshold";

    // Comma separated list of default packages exempt from user impact level threshold.
    private static final String DEFAULT_PACKAGES_EXEMPT_FROM_IMPACT_LEVEL_THRESHOLD =
            "com.android.systemui";

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
    private static final String ATTR_MITIGATION_CALLS = "mitigation-calls";
    private static final String ATTR_MITIGATION_COUNT = "mitigation-count";

    // A file containing information about the current mitigation count in the case of a boot loop.
    // This allows boot loop information to persist in the case of an fs-checkpoint being
    // aborted.
    private static final String METADATA_FILE = "/metadata/watchdog/mitigation_count.txt";

    /**
     * EventLog tags used when logging into the event log. Note the values must be sync with
     * frameworks/base/services/core/java/com/android/server/EventLogTags.logtags to get correct
     * name translation.
     */
    private static final int LOG_TAG_RESCUE_NOTE = 2900;

    private static final Object sPackageWatchdogLock = new Object();
    @GuardedBy("sPackageWatchdogLock")
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
    private final Runnable mSyncRequests = this::syncRequests;
    private final Runnable mSyncStateWithScheduledReason = this::syncStateWithScheduledReason;
    private final Runnable mSaveToFile = this::saveToFile;
    private final SystemClock mSystemClock;
    private final BootThreshold mBootThreshold;
    private final DeviceConfig.OnPropertiesChangedListener
            mOnPropertyChangedListener = this::onPropertyChanged;

    private final Set<String> mPackagesExemptFromImpactLevelThreshold = new ArraySet<>();

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
    // If true, sync explicit health check packages with the ExplicitHealthCheckController.
    @GuardedBy("mLock")
    private boolean mSyncRequired = false;

    @GuardedBy("mLock")
    private long mLastMitigation = -1000000;

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
                android.os.SystemClock::uptimeMillis);
    }

    /**
     * Creates a PackageWatchdog that allows injecting dependencies.
     */
    @VisibleForTesting
    PackageWatchdog(Context context, AtomicFile policyFile, Handler shortTaskHandler,
            Handler longTaskHandler, ExplicitHealthCheckController controller,
            SystemClock clock) {
        mContext = context;
        mPolicyFile = policyFile;
        mShortTaskHandler = shortTaskHandler;
        mLongTaskHandler = longTaskHandler;
        mHealthCheckController = controller;
        mSystemClock = clock;
        mNumberOfNativeCrashPollsRemaining = NUMBER_OF_NATIVE_CRASH_POLLS;
        mBootThreshold = new BootThreshold(DEFAULT_BOOT_LOOP_TRIGGER_COUNT,
                DEFAULT_BOOT_LOOP_TRIGGER_WINDOW_MS);

        loadFromFile();
        sPackageWatchdog = this;
    }

    /** Creates or gets singleton instance of PackageWatchdog. */
    public static  @NonNull PackageWatchdog getInstance(@NonNull Context context) {
        synchronized (sPackageWatchdogLock) {
            if (sPackageWatchdog == null) {
                new PackageWatchdog(context);
            }
            return sPackageWatchdog;
        }
    }

    /**
     * Called during boot to notify when packages are ready on the device so we can start
     * binding.
     * @hide
     */
    public void onPackagesReady() {
        synchronized (mLock) {
            mIsPackagesReady = true;
            mHealthCheckController.setCallbacks(packageName -> onHealthCheckPassed(packageName),
                    packages -> onSupportedPackages(packages),
                    this::onSyncRequestNotified);
            setPropertyChangedListenerLocked();
            updateConfigs();
        }
    }

    /**
     * Registers {@code observer} to listen for package failures. Add a new ObserverInternal for
     * this observer if it does not already exist.
     *
     * <p>Observers are expected to call this on boot. It does not specify any packages but
     * it will resume observing any packages requested from a previous boot.
     * @hide
     */
    public void registerHealthObserver(PackageHealthObserver observer) {
        synchronized (mLock) {
            ObserverInternal internalObserver = mAllObservers.get(observer.getUniqueIdentifier());
            if (internalObserver != null) {
                internalObserver.registeredObserver = observer;
            } else {
                internalObserver = new ObserverInternal(observer.getUniqueIdentifier(),
                        new ArrayList<>());
                internalObserver.registeredObserver = observer;
                mAllObservers.put(observer.getUniqueIdentifier(), internalObserver);
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
     * {@link PackageHealthObserver#onExecuteHealthCheckMitigation} will be called as if the package failed.
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
     * @hide
     */
    public void startObservingHealth(PackageHealthObserver observer, List<String> packageNames,
            long durationMs) {
        if (packageNames.isEmpty()) {
            Slog.wtf(TAG, "No packages to observe, " + observer.getUniqueIdentifier());
            return;
        }
        if (durationMs < 1) {
            Slog.wtf(TAG, "Invalid duration " + durationMs + "ms for observer "
                    + observer.getUniqueIdentifier() + ". Not observing packages " + packageNames);
            durationMs = DEFAULT_OBSERVING_DURATION_MS;
        }

        List<MonitoredPackage> packages = new ArrayList<>();
        for (int i = 0; i < packageNames.size(); i++) {
            // Health checks not available yet so health check state will start INACTIVE
            MonitoredPackage pkg = newMonitoredPackage(packageNames.get(i), durationMs, false);
            if (pkg != null) {
                packages.add(pkg);
            } else {
                Slog.w(TAG, "Failed to create MonitoredPackage for pkg=" + packageNames.get(i));
            }
        }

        if (packages.isEmpty()) {
            return;
        }

        // Sync before we add the new packages to the observers. This will #pruneObservers,
        // causing any elapsed time to be deducted from all existing packages before we add new
        // packages. This maintains the invariant that the elapsed time for ALL (new and existing)
        // packages is the same.
        mLongTaskHandler.post(() -> {
            syncState("observing new packages");

            synchronized (mLock) {
                ObserverInternal oldObserver = mAllObservers.get(observer.getUniqueIdentifier());
                if (oldObserver == null) {
                    Slog.d(TAG, observer.getUniqueIdentifier() + " started monitoring health "
                            + "of packages " + packageNames);
                    mAllObservers.put(observer.getUniqueIdentifier(),
                            new ObserverInternal(observer.getUniqueIdentifier(), packages));
                } else {
                    Slog.d(TAG, observer.getUniqueIdentifier() + " added the following "
                            + "packages to monitor " + packageNames);
                    oldObserver.updatePackagesLocked(packages);
                }
            }

            // Register observer in case not already registered
            registerHealthObserver(observer);

            // Sync after we add the new packages to the observers. We may have received packges
            // requiring an earlier schedule than we are currently scheduled for.
            syncState("updated observers");
        });

    }

    /**
     * Unregisters {@code observer} from listening to package failure.
     * Additionally, this stops observing any packages that may have previously been observed
     * even from a previous boot.
     * @hide
     */
    public void unregisterHealthObserver(PackageHealthObserver observer) {
        mLongTaskHandler.post(() -> {
            synchronized (mLock) {
                mAllObservers.remove(observer.getUniqueIdentifier());
            }
            syncState("unregistering observer: " + observer.getUniqueIdentifier());
        });
    }

    /**
     * Called when a process fails due to a crash, ANR or explicit health check.
     *
     * <p>For each package contained in the process, one registered observer with the least user
     * impact will be notified for mitigation.
     *
     * <p>This method could be called frequently if there is a severe problem on the device.
     */
    public void onPackageFailure(@NonNull List<VersionedPackage> packages,
            @FailureReasons int failureReason) {
        if (packages == null) {
            Slog.w(TAG, "Could not resolve a list of failing packages");
            return;
        }
        synchronized (mLock) {
            final long now = mSystemClock.uptimeMillis();
            if (Flags.recoverabilityDetection()) {
                if (now >= mLastMitigation
                        && (now - mLastMitigation) < getMitigationWindowMs()) {
                    Slog.i(TAG, "Skipping onPackageFailure mitigation");
                    return;
                }
            }
        }
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
                        MonitoredPackage currentMonitoredPackage = null;

                        // Find observer with least user impact
                        for (int oIndex = 0; oIndex < mAllObservers.size(); oIndex++) {
                            ObserverInternal observer = mAllObservers.valueAt(oIndex);
                            PackageHealthObserver registeredObserver = observer.registeredObserver;
                            if (registeredObserver != null
                                    && observer.onPackageFailureLocked(
                                    versionedPackage.getPackageName())) {
                                MonitoredPackage p = observer.getMonitoredPackage(
                                        versionedPackage.getPackageName());
                                int mitigationCount = 1;
                                if (p != null) {
                                    mitigationCount = p.getMitigationCountLocked() + 1;
                                }
                                int impact = registeredObserver.onHealthCheckFailed(
                                        versionedPackage, failureReason, mitigationCount);
                                if (impact != PackageHealthObserverImpact.USER_IMPACT_LEVEL_0
                                        && impact < currentObserverImpact) {
                                    currentObserverToNotify = registeredObserver;
                                    currentObserverImpact = impact;
                                    currentMonitoredPackage = p;
                                }
                            }
                        }

                        // Execute action with least user impact
                        if (currentObserverToNotify != null) {
                            int mitigationCount = 1;
                            if (currentMonitoredPackage != null) {
                                currentMonitoredPackage.noteMitigationCallLocked();
                                mitigationCount =
                                        currentMonitoredPackage.getMitigationCountLocked();
                            }
                            if (Flags.recoverabilityDetection()) {
                                maybeExecute(currentObserverToNotify, versionedPackage,
                                        failureReason, currentObserverImpact, mitigationCount);
                            } else {
                                currentObserverToNotify.onExecuteHealthCheckMitigation(
                                        versionedPackage, failureReason, mitigationCount);
                            }
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
                        failingPackage, failureReason, 1);
                if (impact != PackageHealthObserverImpact.USER_IMPACT_LEVEL_0
                        && impact < currentObserverImpact) {
                    currentObserverToNotify = registeredObserver;
                    currentObserverImpact = impact;
                }
            }
        }
        if (currentObserverToNotify != null) {
            if (Flags.recoverabilityDetection()) {
                maybeExecute(currentObserverToNotify, failingPackage, failureReason,
                        currentObserverImpact, /*mitigationCount=*/ 1);
            } else {
                currentObserverToNotify.onExecuteHealthCheckMitigation(failingPackage,
                        failureReason, 1);
            }
        }
    }

    private void maybeExecute(PackageHealthObserver currentObserverToNotify,
                              VersionedPackage versionedPackage,
                              @FailureReasons int failureReason,
                              int currentObserverImpact,
                              int mitigationCount) {
        if (allowMitigations(currentObserverImpact, versionedPackage)) {
            synchronized (mLock) {
                mLastMitigation = mSystemClock.uptimeMillis();
            }
            currentObserverToNotify.onExecuteHealthCheckMitigation(versionedPackage, failureReason,
                    mitigationCount);
        }
    }

    private boolean allowMitigations(int currentObserverImpact,
            VersionedPackage versionedPackage) {
        return currentObserverImpact < getUserImpactLevelLimit()
                || getPackagesExemptFromImpactLevelThreshold().contains(
                versionedPackage.getPackageName());
    }

    private long getMitigationWindowMs() {
        return SystemProperties.getLong(MITIGATION_WINDOW_MS, DEFAULT_MITIGATION_WINDOW_MS);
    }


    /**
     * Called when the system server boots. If the system server is detected to be in a boot loop,
     * query each observer and perform the mitigation action with the lowest user impact.
     *
     * Note: PackageWatchdog considers system_server restart loop as bootloop. Full reboots
     * are not counted in bootloop.
     * @hide
     */
    @SuppressWarnings("GuardedBy")
    public void noteBoot() {
        synchronized (mLock) {
            // if boot count has reached threshold, start mitigation.
            // We wait until threshold number of restarts only for the first time. Perform
            // mitigations for every restart after that.
            boolean mitigate = mBootThreshold.incrementAndTest();
            if (mitigate) {
                if (!Flags.recoverabilityDetection()) {
                    mBootThreshold.reset();
                }
                int mitigationCount = mBootThreshold.getMitigationCount() + 1;
                PackageHealthObserver currentObserverToNotify = null;
                ObserverInternal currentObserverInternal = null;
                int currentObserverImpact = Integer.MAX_VALUE;
                for (int i = 0; i < mAllObservers.size(); i++) {
                    final ObserverInternal observer = mAllObservers.valueAt(i);
                    PackageHealthObserver registeredObserver = observer.registeredObserver;
                    if (registeredObserver != null) {
                        int impact = Flags.recoverabilityDetection()
                                ? registeredObserver.onBootLoop(
                                        observer.getBootMitigationCount() + 1)
                                : registeredObserver.onBootLoop(mitigationCount);
                        if (impact != PackageHealthObserverImpact.USER_IMPACT_LEVEL_0
                                && impact < currentObserverImpact) {
                            currentObserverToNotify = registeredObserver;
                            currentObserverInternal = observer;
                            currentObserverImpact = impact;
                        }
                    }
                }
                if (currentObserverToNotify != null) {
                    if (Flags.recoverabilityDetection()) {
                        int currentObserverMitigationCount =
                                currentObserverInternal.getBootMitigationCount() + 1;
                        currentObserverInternal.setBootMitigationCount(
                                currentObserverMitigationCount);
                        saveAllObserversBootMitigationCountToMetadata(METADATA_FILE);
                        currentObserverToNotify.onExecuteBootLoopMitigation(
                                currentObserverMitigationCount);
                    } else {
                        mBootThreshold.setMitigationCount(mitigationCount);
                        mBootThreshold.saveMitigationCountToMetadata();
                        currentObserverToNotify.onExecuteBootLoopMitigation(mitigationCount);
                    }
                }
            }
        }
    }

    // TODO(b/120598832): Optimize write? Maybe only write a separate smaller file? Also
    // avoid holding lock?
    // This currently adds about 7ms extra to shutdown thread
    /** @hide Writes the package information to file during shutdown. */
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
            mSyncRequired = true;
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
            // We rollback all available low impact rollbacks when crash is unattributable
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
     * @hide
     */
    public void scheduleCheckAndMitigateNativeCrashes() {
        Slog.i(TAG, "Scheduling " + mNumberOfNativeCrashPollsRemaining + " polls to check "
                + "and mitigate native crashes");
        mShortTaskHandler.post(()->checkAndMitigateNativeCrashes());
    }

    private int getUserImpactLevelLimit() {
        return SystemProperties.getInt(MAJOR_USER_IMPACT_LEVEL_THRESHOLD,
                DEFAULT_MAJOR_USER_IMPACT_LEVEL_THRESHOLD);
    }

    private Set<String> getPackagesExemptFromImpactLevelThreshold() {
        if (mPackagesExemptFromImpactLevelThreshold.isEmpty()) {
            String packageNames = SystemProperties.get(PACKAGES_EXEMPT_FROM_IMPACT_LEVEL_THRESHOLD,
                    DEFAULT_PACKAGES_EXEMPT_FROM_IMPACT_LEVEL_THRESHOLD);
            return Set.of(packageNames.split("\\s*,\\s*"));
        }
        return mPackagesExemptFromImpactLevelThreshold;
    }

    /**
     * Possible severity values of the user impact of a
     * {@link PackageHealthObserver#onExecuteHealthCheckMitigation}.
     * @hide
     */
    @Retention(SOURCE)
    @IntDef(value = {PackageHealthObserverImpact.USER_IMPACT_LEVEL_0,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_10,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_20,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_30,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_40,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_50,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_70,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_71,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_75,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_80,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_90,
                     PackageHealthObserverImpact.USER_IMPACT_LEVEL_100})
    public @interface PackageHealthObserverImpact {
        /** No action to take. */
        int USER_IMPACT_LEVEL_0 = 0;
        /* Action has low user impact, user of a device will barely notice. */
        int USER_IMPACT_LEVEL_10 = 10;
        /* Actions having medium user impact, user of a device will likely notice. */
        int USER_IMPACT_LEVEL_20 = 20;
        int USER_IMPACT_LEVEL_30 = 30;
        int USER_IMPACT_LEVEL_40 = 40;
        int USER_IMPACT_LEVEL_50 = 50;
        int USER_IMPACT_LEVEL_70 = 70;
        /* Action has high user impact, a last resort, user of a device will be very frustrated. */
        int USER_IMPACT_LEVEL_71 = 71;
        int USER_IMPACT_LEVEL_75 = 75;
        int USER_IMPACT_LEVEL_80 = 80;
        int USER_IMPACT_LEVEL_90 = 90;
        int USER_IMPACT_LEVEL_100 = 100;
    }

    /** Register instances of this interface to receive notifications on package failure. */
    @SuppressLint({"CallbackName"})
    public interface PackageHealthObserver {
        /**
         * Called when health check fails for the {@code versionedPackage}.
         *
         * @param versionedPackage the package that is failing. This may be null if a native
         *                          service is crashing.
         * @param failureReason   the type of failure that is occurring.
         * @param mitigationCount the number of times mitigation has been called for this package
         *                        (including this time).
         *
         *
         * @return any one of {@link PackageHealthObserverImpact} to express the impact
         * to the user on {@link #onExecuteHealthCheckMitigation}
         */
        @PackageHealthObserverImpact int onHealthCheckFailed(
                @Nullable VersionedPackage versionedPackage,
                @FailureReasons int failureReason,
                int mitigationCount);

        /**
         * This would be called after {@link #onHealthCheckFailed}.
         * This is called only if current observer returned least
         * {@link PackageHealthObserverImpact} mitigation for failed health
         * check.
         *
         * @param versionedPackage the package that is failing. This may be null if a native
         *                          service is crashing.
         * @param failureReason   the type of failure that is occurring.
         * @param mitigationCount the number of times mitigation has been called for this package
         *                        (including this time).
         * @return {@code true} if action was executed successfully, {@code false} otherwise
         */
        boolean onExecuteHealthCheckMitigation(@Nullable VersionedPackage versionedPackage,
                @FailureReasons int failureReason, int mitigationCount);


        /**
         * Called when the system server has booted several times within a window of time, defined
         * by {@link #mBootThreshold}
         *
         * @param mitigationCount the number of times mitigation has been attempted for this
         *                        boot loop (including this time).
         */
        default @PackageHealthObserverImpact int onBootLoop(int mitigationCount) {
            return PackageHealthObserverImpact.USER_IMPACT_LEVEL_0;
        }

        /**
         * This would be called after {@link #onBootLoop}.
         * This is called only if current observer returned least
         * {@link PackageHealthObserverImpact} mitigation for fixing boot loop
         *
         * @param mitigationCount the number of times mitigation has been attempted for this
         *                        boot loop (including this time).
         */
        default boolean onExecuteBootLoopMitigation(int mitigationCount) {
            return false;
        }

        // TODO(b/120598832): Ensure uniqueness?
        /**
         * Identifier for the observer, should not change across device updates otherwise the
         * watchdog may drop observing packages with the old name.
         */
        @NonNull String getUniqueIdentifier();

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
        default boolean mayObservePackage(@NonNull String packageName) {
            return false;
        }
    }

    @VisibleForTesting
    long getTriggerFailureCount() {
        synchronized (mLock) {
            return mTriggerFailureCount;
        }
    }

    @VisibleForTesting
    long getTriggerFailureDurationMs() {
        synchronized (mLock) {
            return mTriggerFailureDurationMs;
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
                if (mSyncRequired || !packages.equals(mRequestedHealthCheckPackages)
                        || packages.isEmpty()) {
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
            mSyncRequired = false;
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
                MonitoredPackage monitoredPackage = observer.getMonitoredPackage(packageName);

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
                Iterator<MonitoredPackage> pit = oit.next().getMonitoredPackages()
                        .values().iterator();
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

    private void onSyncRequestNotified() {
        synchronized (mLock) {
            mSyncRequired = true;
            syncRequestsAsync();
        }
    }

    @GuardedBy("mLock")
    private Set<String> getPackagesPendingHealthChecksLocked() {
        Set<String> packages = new ArraySet<>();
        Iterator<ObserverInternal> oit = mAllObservers.values().iterator();
        while (oit.hasNext()) {
            ObserverInternal observer = oit.next();
            Iterator<MonitoredPackage> pit =
                    observer.getMonitoredPackages().values().iterator();
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
            ArrayMap<String, MonitoredPackage> packages = mAllObservers.valueAt(oIndex)
                    .getMonitoredPackages();
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

        Iterator<ObserverInternal> it = mAllObservers.values().iterator();
        while (it.hasNext()) {
            ObserverInternal observer = it.next();
            Set<MonitoredPackage> failedPackages =
                    observer.prunePackagesLocked(elapsedMs);
            if (!failedPackages.isEmpty()) {
                onHealthCheckFailed(observer, failedPackages);
            }
            if (observer.getMonitoredPackages().isEmpty() && (observer.registeredObserver == null
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
                        VersionedPackage versionedPkg = getVersionedPackage(it.next().getName());
                        if (versionedPkg != null) {
                            Slog.i(TAG,
                                    "Explicit health check failed for package " + versionedPkg);
                            registeredObserver.onExecuteHealthCheckMitigation(versionedPkg,
                                    PackageWatchdog.FAILURE_REASON_EXPLICIT_HEALTH_CHECK, 1);
                        }
                    }
                }
            }
        });
    }

    /**
     * Gets PackageInfo for the given package. Matches any user and apex.
     *
     * @throws PackageManager.NameNotFoundException if no such package is installed.
     */
    private PackageInfo getPackageInfo(String packageName)
            throws PackageManager.NameNotFoundException {
        PackageManager pm = mContext.getPackageManager();
        try {
            // The MATCH_ANY_USER flag doesn't mix well with the MATCH_APEX
            // flag, so make two separate attempts to get the package info.
            // We don't need both flags at the same time because we assume
            // apex files are always installed for all users.
            return pm.getPackageInfo(packageName, PackageManager.MATCH_ANY_USER);
        } catch (PackageManager.NameNotFoundException e) {
            return pm.getPackageInfo(packageName, PackageManager.MATCH_APEX);
        }
    }

    @Nullable
    private VersionedPackage getVersionedPackage(String packageName) {
        final PackageManager pm = mContext.getPackageManager();
        if (pm == null || TextUtils.isEmpty(packageName)) {
            return null;
        }
        try {
            final long versionCode = getPackageInfo(packageName).getLongVersionCode();
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
            final TypedXmlPullParser parser = Xml.resolvePullParser(infile);
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

    private void onPropertyChanged(DeviceConfig.Properties properties) {
        try {
            updateConfigs();
        } catch (Exception ignore) {
            Slog.w(TAG, "Failed to reload device config changes");
        }
    }

    /** Adds a {@link DeviceConfig#OnPropertiesChangedListener}. */
    private void setPropertyChangedListenerLocked() {
        DeviceConfig.addOnPropertiesChangedListener(
                DeviceConfig.NAMESPACE_ROLLBACK,
                mContext.getMainExecutor(),
                mOnPropertyChangedListener);
    }

    @VisibleForTesting
    void removePropertyChangedListener() {
        DeviceConfig.removeOnPropertiesChangedListener(mOnPropertyChangedListener);
    }

    /**
     * Health check is enabled or disabled after reading the flags
     * from DeviceConfig.
     */
    @VisibleForTesting
    void updateConfigs() {
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
                TypedXmlSerializer out = Xml.resolveSerializer(stream);
                out.startDocument(null, true);
                out.startTag(null, TAG_PACKAGE_WATCHDOG);
                out.attributeInt(null, ATTR_VERSION, DB_VERSION);
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

    /** @hide Convert a {@code LongArrayQueue} to a String of comma-separated values. */
    public static String longArrayQueueToString(LongArrayQueue queue) {
        if (queue.size() > 0) {
            StringBuilder sb = new StringBuilder();
            sb.append(queue.get(0));
            for (int i = 1; i < queue.size(); i++) {
                sb.append(",");
                sb.append(queue.get(i));
            }
            return sb.toString();
        }
        return "";
    }

    /** @hide Parse a comma-separated String of longs into a LongArrayQueue. */
    public static LongArrayQueue parseLongArrayQueue(String commaSeparatedValues) {
        LongArrayQueue result = new LongArrayQueue();
        if (!TextUtils.isEmpty(commaSeparatedValues)) {
            String[] values = commaSeparatedValues.split(",");
            for (String value : values) {
                result.addLast(Long.parseLong(value));
            }
        }
        return result;
    }


    /** Dump status of every observer in mAllObservers. */
    public void dump(@NonNull PrintWriter pw) {
        if (Flags.synchronousRebootInRescueParty() && RescueParty.isRecoveryTriggeredReboot()) {
            dumpInternal(pw);
        } else {
            synchronized (mLock) {
                dumpInternal(pw);
            }
        }
    }

    private void dumpInternal(@NonNull PrintWriter pw) {
        IndentingPrintWriter ipw = new IndentingPrintWriter(pw, "  ");
        ipw.println("Package Watchdog status");
        ipw.increaseIndent();
        synchronized (mLock) {
            for (String observerName : mAllObservers.keySet()) {
                ipw.println("Observer name: " + observerName);
                ipw.increaseIndent();
                ObserverInternal observerInternal = mAllObservers.get(observerName);
                observerInternal.dump(ipw);
                ipw.decreaseIndent();
            }
        }
        ipw.decreaseIndent();
        dumpCrashRecoveryEvents(ipw);
    }

    @VisibleForTesting
    @GuardedBy("mLock")
    void registerObserverInternal(ObserverInternal observerInternal) {
        mAllObservers.put(observerInternal.name, observerInternal);
    }

    /**
     * Represents an observer monitoring a set of packages along with the failure thresholds for
     * each package.
     *
     * <p> Note, the PackageWatchdog#mLock must always be held when reading or writing
     * instances of this class.
     */
    static class ObserverInternal {
        public final String name;
        @GuardedBy("mLock")
        private final ArrayMap<String, MonitoredPackage> mPackages = new ArrayMap<>();
        @Nullable
        @GuardedBy("mLock")
        public PackageHealthObserver registeredObserver;
        private int mMitigationCount;

        ObserverInternal(String name, List<MonitoredPackage> packages) {
            this(name, packages, /*mitigationCount=*/ 0);
        }

        ObserverInternal(String name, List<MonitoredPackage> packages, int mitigationCount) {
            this.name = name;
            updatePackagesLocked(packages);
            this.mMitigationCount = mitigationCount;
        }

        /**
         * Writes important {@link MonitoredPackage} details for this observer to file.
         * Does not persist any package failure thresholds.
         */
        @GuardedBy("mLock")
        public boolean writeLocked(TypedXmlSerializer out) {
            try {
                out.startTag(null, TAG_OBSERVER);
                out.attribute(null, ATTR_NAME, name);
                if (Flags.recoverabilityDetection()) {
                    out.attributeInt(null, ATTR_MITIGATION_COUNT, mMitigationCount);
                }
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

        public int getBootMitigationCount() {
            return mMitigationCount;
        }

        public void setBootMitigationCount(int mitigationCount) {
            mMitigationCount = mitigationCount;
        }

        @GuardedBy("mLock")
        public void updatePackagesLocked(List<MonitoredPackage> packages) {
            for (int pIndex = 0; pIndex < packages.size(); pIndex++) {
                MonitoredPackage p = packages.get(pIndex);
                MonitoredPackage existingPackage = getMonitoredPackage(p.getName());
                if (existingPackage != null) {
                    existingPackage.updateHealthCheckDuration(p.mDurationMs);
                } else {
                    putMonitoredPackage(p);
                }
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
         * @hide
         */
        @GuardedBy("mLock")
        public boolean onPackageFailureLocked(String packageName) {
            if (getMonitoredPackage(packageName) == null && registeredObserver.isPersistent()
                    && registeredObserver.mayObservePackage(packageName)) {
                putMonitoredPackage(sPackageWatchdog.newMonitoredPackage(
                        packageName, DEFAULT_OBSERVING_DURATION_MS, false));
            }
            MonitoredPackage p = getMonitoredPackage(packageName);
            if (p != null) {
                return p.onFailureLocked();
            }
            return false;
        }

        /**
         * Returns the map of packages monitored by this observer.
         *
         * @return a mapping of package names to {@link MonitoredPackage} objects.
         */
        @GuardedBy("mLock")
        public ArrayMap<String, MonitoredPackage> getMonitoredPackages() {
            return mPackages;
        }

        /**
         * Returns the {@link MonitoredPackage} associated with a given package name if the
         * package is being monitored by this observer.
         *
         * @param packageName: the name of the package.
         * @return the {@link MonitoredPackage} object associated with the package name if one
         *         exists, {@code null} otherwise.
         */
        @GuardedBy("mLock")
        @Nullable
        public MonitoredPackage getMonitoredPackage(String packageName) {
            return mPackages.get(packageName);
        }

        /**
         * Associates a {@link MonitoredPackage} with the observer.
         *
         * @param p: the {@link MonitoredPackage} to store.
         */
        @GuardedBy("mLock")
        public void putMonitoredPackage(MonitoredPackage p) {
            mPackages.put(p.getName(), p);
        }

        /**
         * Returns one ObserverInternal from the {@code parser} and advances its state.
         *
         * <p>Note that this method is <b>not</b> thread safe. It should only be called from
         * #loadFromFile which in turn is only called on construction of the
         * singleton PackageWatchdog.
         **/
        public static ObserverInternal read(TypedXmlPullParser parser, PackageWatchdog watchdog) {
            String observerName = null;
            int observerMitigationCount = 0;
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
                if (Flags.recoverabilityDetection()) {
                    try {
                        observerMitigationCount =
                                parser.getAttributeInt(null, ATTR_MITIGATION_COUNT);
                    } catch (XmlPullParserException e) {
                        Slog.i(
                            TAG,
                            "ObserverInternal mitigation count was not present.");
                    }
                }
                while (XmlUtils.nextElementWithin(parser, innerDepth)) {
                    if (TAG_PACKAGE.equals(parser.getName())) {
                        try {
                            MonitoredPackage pkg = watchdog.parseMonitoredPackage(parser);
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
            return new ObserverInternal(observerName, packages, observerMitigationCount);
        }

        /** Dumps information about this observer and the packages it watches. */
        public void dump(IndentingPrintWriter pw) {
            boolean isPersistent = registeredObserver != null && registeredObserver.isPersistent();
            pw.println("Persistent: " + isPersistent);
            for (String packageName : mPackages.keySet()) {
                MonitoredPackage p = getMonitoredPackage(packageName);
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

    /** @hide */
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
        return newMonitoredPackage(name, durationMs, Long.MAX_VALUE, hasPassedHealthCheck,
                new LongArrayQueue());
    }

    MonitoredPackage newMonitoredPackage(String name, long durationMs, long healthCheckDurationMs,
            boolean hasPassedHealthCheck, LongArrayQueue mitigationCalls) {
        return new MonitoredPackage(name, durationMs, healthCheckDurationMs,
                hasPassedHealthCheck, mitigationCalls);
    }

    MonitoredPackage parseMonitoredPackage(TypedXmlPullParser parser)
            throws XmlPullParserException {
        String packageName = parser.getAttributeValue(null, ATTR_NAME);
        long duration = parser.getAttributeLong(null, ATTR_DURATION);
        long healthCheckDuration = parser.getAttributeLong(null,
                        ATTR_EXPLICIT_HEALTH_CHECK_DURATION);
        boolean hasPassedHealthCheck = parser.getAttributeBoolean(null, ATTR_PASSED_HEALTH_CHECK);
        LongArrayQueue mitigationCalls = parseLongArrayQueue(
                parser.getAttributeValue(null, ATTR_MITIGATION_CALLS));
        return newMonitoredPackage(packageName,
                duration, healthCheckDuration, hasPassedHealthCheck, mitigationCalls);
    }

    /**
     * Represents a package and its health check state along with the time
     * it should be monitored for.
     *
     * <p> Note, the PackageWatchdog#mLock must always be held when reading or writing
     * instances of this class.
     */
    class MonitoredPackage {
        private final String mPackageName;
        // Times when package failures happen sorted in ascending order
        @GuardedBy("mLock")
        private final LongArrayQueue mFailureHistory = new LongArrayQueue();
        // Times when an observer was called to mitigate this package's failure. Sorted in
        // ascending order.
        @GuardedBy("mLock")
        private final LongArrayQueue mMitigationCalls;
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

        MonitoredPackage(String packageName, long durationMs,
                long healthCheckDurationMs, boolean hasPassedHealthCheck,
                LongArrayQueue mitigationCalls) {
            mPackageName = packageName;
            mDurationMs = durationMs;
            mHealthCheckDurationMs = healthCheckDurationMs;
            mHasPassedHealthCheck = hasPassedHealthCheck;
            mMitigationCalls = mitigationCalls;
            updateHealthCheckStateLocked();
        }

        /** Writes the salient fields to disk using {@code out}.
         * @hide
         */
        @GuardedBy("mLock")
        public void writeLocked(TypedXmlSerializer out) throws IOException {
            out.startTag(null, TAG_PACKAGE);
            out.attribute(null, ATTR_NAME, getName());
            out.attributeLong(null, ATTR_DURATION, mDurationMs);
            out.attributeLong(null, ATTR_EXPLICIT_HEALTH_CHECK_DURATION, mHealthCheckDurationMs);
            out.attributeBoolean(null, ATTR_PASSED_HEALTH_CHECK, mHasPassedHealthCheck);
            LongArrayQueue normalizedCalls = normalizeMitigationCalls();
            out.attribute(null, ATTR_MITIGATION_CALLS, longArrayQueueToString(normalizedCalls));
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
         * Notes the timestamp of a mitigation call into the observer.
         */
        @GuardedBy("mLock")
        public void noteMitigationCallLocked() {
            mMitigationCalls.addLast(mSystemClock.uptimeMillis());
        }

        /**
         * Prunes any mitigation calls outside of the de-escalation window, and returns the
         * number of calls that are in the window afterwards.
         *
         * @return the number of mitigation calls made in the de-escalation window.
         */
        @GuardedBy("mLock")
        public int getMitigationCountLocked() {
            try {
                final long now = mSystemClock.uptimeMillis();
                while (now - mMitigationCalls.peekFirst() > DEFAULT_DEESCALATION_WINDOW_MS) {
                    mMitigationCalls.removeFirst();
                }
            } catch (NoSuchElementException ignore) {
            }

            return mMitigationCalls.size();
        }

        /**
         * Before writing to disk, make the mitigation call timestamps relative to the current
         * system uptime. This is because they need to be relative to the uptime which will reset
         * at the next boot.
         *
         * @return a LongArrayQueue of the mitigation calls relative to the current system uptime.
         */
        @GuardedBy("mLock")
        public LongArrayQueue normalizeMitigationCalls() {
            LongArrayQueue normalized = new LongArrayQueue();
            final long now = mSystemClock.uptimeMillis();
            for (int i = 0; i < mMitigationCalls.size(); i++) {
                normalized.addLast(mMitigationCalls.get(i) - now);
            }
            return normalized;
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

        /** Explicitly update the monitoring duration of the package. */
        @GuardedBy("mLock")
        public void updateHealthCheckDuration(long newDurationMs) {
            mDurationMs = newDurationMs;
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
            return mPackageName;
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

            if (oldState != mHealthCheckState) {
                Slog.i(TAG, "Updated health check state for package " + getName() + ": "
                        + toString(oldState) + " -> " + toString(mHealthCheckState));
            }
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

        /** Compares the equality of this object with another {@link MonitoredPackage}. */
        @VisibleForTesting
        boolean isEqualTo(MonitoredPackage pkg) {
            return (getName().equals(pkg.getName()))
                    && mDurationMs == pkg.mDurationMs
                    && mHasPassedHealthCheck == pkg.mHasPassedHealthCheck
                    && mHealthCheckDurationMs == pkg.mHealthCheckDurationMs
                    && (mMitigationCalls.toString()).equals(pkg.mMitigationCalls.toString());
        }
    }

    @GuardedBy("mLock")
    @SuppressWarnings("GuardedBy")
    void saveAllObserversBootMitigationCountToMetadata(String filePath) {
        HashMap<String, Integer> bootMitigationCounts = new HashMap<>();
        for (int i = 0; i < mAllObservers.size(); i++) {
            final ObserverInternal observer = mAllObservers.valueAt(i);
            bootMitigationCounts.put(observer.name, observer.getBootMitigationCount());
        }

        try {
            FileOutputStream fileStream = new FileOutputStream(new File(filePath));
            ObjectOutputStream objectStream = new ObjectOutputStream(fileStream);
            objectStream.writeObject(bootMitigationCounts);
            objectStream.flush();
            objectStream.close();
            fileStream.close();
        } catch (Exception e) {
            Slog.i(TAG, "Could not save observers metadata to file: " + e);
        }
    }

    /**
     * Handles the thresholding logic for system server boots.
     */
    class BootThreshold {

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

        protected int getCount() {
            return CrashRecoveryProperties.rescueBootCount().orElse(0);
        }

        protected void setCount(int count) {
            CrashRecoveryProperties.rescueBootCount(count);
        }

        public long getStart() {
            return CrashRecoveryProperties.rescueBootStart().orElse(0L);
        }

        public int getMitigationCount() {
            return CrashRecoveryProperties.bootMitigationCount().orElse(0);
        }

        public void setStart(long start) {
            CrashRecoveryProperties.rescueBootStart(getStartTime(start));
        }

        public void setMitigationStart(long start) {
            CrashRecoveryProperties.bootMitigationStart(getStartTime(start));
        }

        public long getMitigationStart() {
            return CrashRecoveryProperties.bootMitigationStart().orElse(0L);
        }

        public void setMitigationCount(int count) {
            CrashRecoveryProperties.bootMitigationCount(count);
        }

        private static long constrain(long amount, long low, long high) {
            return amount < low ? low : (amount > high ? high : amount);
        }

        public long getStartTime(long start) {
            final long now = mSystemClock.uptimeMillis();
            return constrain(start, 0, now);
        }

        public void saveMitigationCountToMetadata() {
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(METADATA_FILE))) {
                writer.write(String.valueOf(getMitigationCount()));
            } catch (Exception e) {
                Slog.e(TAG, "Could not save metadata to file: " + e);
            }
        }

        public void readMitigationCountFromMetadataIfNecessary() {
            File bootPropsFile = new File(METADATA_FILE);
            if (bootPropsFile.exists()) {
                try (BufferedReader reader = new BufferedReader(new FileReader(METADATA_FILE))) {
                    String mitigationCount = reader.readLine();
                    setMitigationCount(Integer.parseInt(mitigationCount));
                    bootPropsFile.delete();
                } catch (Exception e) {
                    Slog.i(TAG, "Could not read metadata file: " + e);
                }
            }
        }


        /** Increments the boot counter, and returns whether the device is bootlooping. */
        @GuardedBy("mLock")
        public boolean incrementAndTest() {
            if (Flags.recoverabilityDetection()) {
                readAllObserversBootMitigationCountIfNecessary(METADATA_FILE);
            } else {
                readMitigationCountFromMetadataIfNecessary();
            }

            final long now = mSystemClock.uptimeMillis();
            if (now - getStart() < 0) {
                Slog.e(TAG, "Window was less than zero. Resetting start to current time.");
                setStart(now);
                setMitigationStart(now);
            }
            if (now - getMitigationStart() > DEFAULT_DEESCALATION_WINDOW_MS) {
                setMitigationStart(now);
                if (Flags.recoverabilityDetection()) {
                    resetAllObserversBootMitigationCount();
                } else {
                    setMitigationCount(0);
                }
            }
            final long window = now - getStart();
            if (window >= mTriggerWindow) {
                setCount(1);
                setStart(now);
                return false;
            } else {
                int count = getCount() + 1;
                setCount(count);
                EventLog.writeEvent(LOG_TAG_RESCUE_NOTE, Process.ROOT_UID, count, window);
                if (Flags.recoverabilityDetection()) {
                    // After a reboot (e.g. by WARM_REBOOT or mainline rollback) we apply
                    // mitigations without waiting for DEFAULT_BOOT_LOOP_TRIGGER_COUNT.
                    return (count >= mBootTriggerCount)
                            || (performedMitigationsDuringWindow() && count > 1);
                }
                return count >= mBootTriggerCount;
            }
        }

        @GuardedBy("mLock")
        private boolean performedMitigationsDuringWindow() {
            for (ObserverInternal observerInternal: mAllObservers.values()) {
                if (observerInternal.getBootMitigationCount() > 0) {
                    return true;
                }
            }
            return false;
        }

        @GuardedBy("mLock")
        private void resetAllObserversBootMitigationCount() {
            for (int i = 0; i < mAllObservers.size(); i++) {
                final ObserverInternal observer = mAllObservers.valueAt(i);
                observer.setBootMitigationCount(0);
            }
            saveAllObserversBootMitigationCountToMetadata(METADATA_FILE);
        }

        @GuardedBy("mLock")
        @SuppressWarnings("GuardedBy")
        void readAllObserversBootMitigationCountIfNecessary(String filePath) {
            File metadataFile = new File(filePath);
            if (metadataFile.exists()) {
                try {
                    FileInputStream fileStream = new FileInputStream(metadataFile);
                    ObjectInputStream objectStream = new ObjectInputStream(fileStream);
                    HashMap<String, Integer> bootMitigationCounts =
                            (HashMap<String, Integer>) objectStream.readObject();
                    objectStream.close();
                    fileStream.close();

                    for (int i = 0; i < mAllObservers.size(); i++) {
                        final ObserverInternal observer = mAllObservers.valueAt(i);
                        if (bootMitigationCounts.containsKey(observer.name)) {
                            observer.setBootMitigationCount(
                                    bootMitigationCounts.get(observer.name));
                        }
                    }
                } catch (Exception e) {
                    Slog.i(TAG, "Could not read observer metadata file: " + e);
                }
            }
        }
    }

    /**
     * Register broadcast receiver for shutdown.
     * We would save the observer state to persist across boots.
     *
     * @hide
     */
    public void registerShutdownBroadcastReceiver() {
        BroadcastReceiver shutdownEventReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // Only write if intent is relevant to device reboot or shutdown.
                String intentAction = intent.getAction();
                if (ACTION_REBOOT.equals(intentAction)
                        || ACTION_SHUTDOWN.equals(intentAction)) {
                    writeNow();
                }
            }
        };

        // Setup receiver for device reboots or shutdowns.
        IntentFilter filter = new IntentFilter(ACTION_REBOOT);
        filter.addAction(ACTION_SHUTDOWN);
        mContext.registerReceiverForAllUsers(shutdownEventReceiver, filter, null,
                /* run on main thread */ null);
    }
}
