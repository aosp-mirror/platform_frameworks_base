/*
 * Copyright (C) 2019 The Android Open Source Project
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
package android.net;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.util.SharedLog;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.provider.DeviceConfig;
import android.text.format.DateUtils;
import android.util.ArraySet;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

import java.io.File;
import java.io.PrintWriter;

/**
 * Class used to communicate to the various networking mainline modules running in the network stack
 * process from {@link com.android.server.SystemServer}.
 * @hide
 */
public class ConnectivityModuleConnector {
    private static final String TAG = ConnectivityModuleConnector.class.getSimpleName();
    private static final String IN_PROCESS_SUFFIX = ".InProcess";

    private static final String PREFS_FILE = "ConnectivityModuleConnector.xml";
    private static final String PREF_KEY_LAST_CRASH_TIME = "lastcrash_time";
    private static final String CONFIG_MIN_CRASH_INTERVAL_MS = "min_crash_interval";
    private static final String CONFIG_MIN_UPTIME_BEFORE_CRASH_MS = "min_uptime_before_crash";
    private static final String CONFIG_ALWAYS_RATELIMIT_NETWORKSTACK_CRASH =
            "always_ratelimit_networkstack_crash";

    // Even if the network stack is lost, do not crash the system more often than this.
    // Connectivity would be broken, but if the user needs the device for something urgent
    // (like calling emergency services) we should not bootloop the device.
    // This is the default value: the actual value can be adjusted via device config.
    private static final long DEFAULT_MIN_CRASH_INTERVAL_MS = 6 * DateUtils.HOUR_IN_MILLIS;

    // Even if the network stack is lost, do not crash the system server if it was less than
    // this much after boot. This avoids bootlooping the device, and crashes should address very
    // infrequent failures, not failures on boot.
    private static final long DEFAULT_MIN_UPTIME_BEFORE_CRASH_MS = 30 * DateUtils.MINUTE_IN_MILLIS;

    private static ConnectivityModuleConnector sInstance;

    private Context mContext;
    @GuardedBy("mLog")
    private final SharedLog mLog = new SharedLog(TAG);
    @GuardedBy("mHealthListeners")
    private final ArraySet<ConnectivityModuleHealthListener> mHealthListeners = new ArraySet<>();
    @NonNull
    private final Dependencies mDeps;

    private ConnectivityModuleConnector() {
        this(new DependenciesImpl());
    }

    @VisibleForTesting
    ConnectivityModuleConnector(@NonNull Dependencies deps) {
        mDeps = deps;
    }

    /**
     * Get the {@link ConnectivityModuleConnector} singleton instance.
     */
    public static synchronized ConnectivityModuleConnector getInstance() {
        if (sInstance == null) {
            sInstance = new ConnectivityModuleConnector();
        }
        return sInstance;
    }

    /**
     * Initialize the network stack connector. Should be called only once on device startup, before
     * any client attempts to use the network stack.
     */
    public void init(Context context) {
        log("Network stack init");
        mContext = context;
    }

    /**
     * Callback interface for severe failures of the NetworkStack.
     *
     * <p>Useful for health monitors such as PackageWatchdog.
     */
    public interface ConnectivityModuleHealthListener {
        /**
         * Called when there is a severe failure of the network stack.
         * @param packageName Package name of the network stack.
         */
        void onNetworkStackFailure(@NonNull String packageName);
    }

    /**
     * Callback invoked by the connector once the connection to the corresponding module is
     * established.
     */
    public interface ModuleServiceCallback {
        /**
         * Invoked when the corresponding service has connected.
         *
         * @param iBinder Binder object for the service.
         */
        void onModuleServiceConnected(@NonNull IBinder iBinder);
    }

    /**
     * Interface used to determine the intent to use to bind to the module. Useful for testing.
     */
    @VisibleForTesting
    protected interface Dependencies {
        /**
         * Determine the intent to use to bind to the module.
         * @return null if the intent could not be resolved, the intent otherwise.
         */
        @Nullable
        Intent getModuleServiceIntent(
                @NonNull PackageManager pm, @NonNull String serviceIntentBaseAction,
                @NonNull String servicePermissionName, boolean inSystemProcess);
    }

    private static class DependenciesImpl implements Dependencies {
        @Nullable
        @Override
        public Intent getModuleServiceIntent(
                @NonNull PackageManager pm, @NonNull String serviceIntentBaseAction,
                @NonNull String servicePermissionName, boolean inSystemProcess) {
            final Intent intent =
                    new Intent(inSystemProcess
                            ? serviceIntentBaseAction + IN_PROCESS_SUFFIX
                            : serviceIntentBaseAction);
            final ComponentName comp = intent.resolveSystemService(pm, 0);
            if (comp == null) {
                return null;
            }
            intent.setComponent(comp);

            final int uid;
            try {
                uid = pm.getPackageUidAsUser(comp.getPackageName(), UserHandle.USER_SYSTEM);
            } catch (PackageManager.NameNotFoundException e) {
                throw new SecurityException(
                        "Could not check network stack UID; package not found.", e);
            }

            final int expectedUid =
                    inSystemProcess ? Process.SYSTEM_UID : Process.NETWORK_STACK_UID;
            if (uid != expectedUid) {
                throw new SecurityException("Invalid network stack UID: " + uid);
            }

            if (!inSystemProcess) {
                checkModuleServicePermission(pm, comp, servicePermissionName);
            }

            return intent;
        }
    }


    /**
     * Add a {@link ConnectivityModuleHealthListener} to listen to network stack health events.
     */
    public void registerHealthListener(@NonNull ConnectivityModuleHealthListener listener) {
        synchronized (mHealthListeners) {
            mHealthListeners.add(listener);
        }
    }

    /**
     * Start a module running in the network stack or system_server process. Should be called only
     * once for each module per device startup.
     *
     * <p>This method will start a networking module either in the network stack
     * process, or inside the system server on devices that do not support the corresponding
     * mainline network . The corresponding networking module service's binder
     * object will then be delivered asynchronously via the provided {@link ModuleServiceCallback}.
     *
     * @param serviceIntentBaseAction Base action to use for constructing the intent needed to
     *                                bind to the corresponding module.
     * @param servicePermissionName Permission to be held by the corresponding module.
     */
    public void startModuleService(
            @NonNull String serviceIntentBaseAction,
            @NonNull String servicePermissionName,
            @NonNull ModuleServiceCallback callback) {
        log("Starting networking module " + serviceIntentBaseAction);

        final PackageManager pm = mContext.getPackageManager();

        // Try to bind in-process if the device was shipped with an in-process version
        Intent intent = mDeps.getModuleServiceIntent(pm, serviceIntentBaseAction,
                servicePermissionName, true /* inSystemProcess */);

        // Otherwise use the updatable module version
        if (intent == null) {
            intent = mDeps.getModuleServiceIntent(pm, serviceIntentBaseAction,
                    servicePermissionName, false /* inSystemProcess */);
            log("Starting networking module in network_stack process");
        } else {
            log("Starting networking module in system_server process");
        }

        if (intent == null) {
            maybeCrashWithTerribleFailure("Could not resolve the networking module", null);
            return;
        }

        final String packageName = intent.getComponent().getPackageName();

        // Start the network stack. The service will be added to the service manager by the
        // corresponding client in ModuleServiceCallback.onModuleServiceConnected().
        if (!mContext.bindServiceAsUser(
                intent, new ModuleServiceConnection(packageName, callback),
                Context.BIND_AUTO_CREATE | Context.BIND_IMPORTANT, UserHandle.SYSTEM)) {
            maybeCrashWithTerribleFailure(
                    "Could not bind to networking module in-process, or in app with "
                            + intent, packageName);
            return;
        }

        log("Networking module service start requested");
    }

    private class ModuleServiceConnection implements ServiceConnection {
        @NonNull
        private final String mPackageName;
        @NonNull
        private final ModuleServiceCallback mModuleServiceCallback;

        private ModuleServiceConnection(
                @NonNull String packageName,
                @NonNull ModuleServiceCallback moduleCallback) {
            mPackageName = packageName;
            mModuleServiceCallback = moduleCallback;
        }

        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            logi("Networking module service connected");
            mModuleServiceCallback.onModuleServiceConnected(service);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            // onServiceDisconnected is not being called on device shutdown, so this method being
            // called always indicates a bad state for the system server.
            // This code path is only run by the system server: only the system server binds
            // to the NetworkStack as a service. Other processes get the NetworkStack from
            // the ServiceManager.
            maybeCrashWithTerribleFailure(
                "Lost network stack. This is not the root cause of any issue, it is a side "
                + "effect of a crash that happened earlier. Earlier logs should point to the "
                + "actual issue.", mPackageName);
        }
    }

    private static void checkModuleServicePermission(
            @NonNull PackageManager pm, @NonNull ComponentName comp,
            @NonNull String servicePermissionName) {
        final int hasPermission =
                pm.checkPermission(servicePermissionName, comp.getPackageName());
        if (hasPermission != PERMISSION_GRANTED) {
            throw new SecurityException(
                    "Networking module does not have permission " + servicePermissionName);
        }
    }

    private synchronized void maybeCrashWithTerribleFailure(@NonNull String message,
            @Nullable String packageName) {
        logWtf(message, null);
        // uptime is monotonic even after a framework restart
        final long uptime = SystemClock.elapsedRealtime();
        final long now = System.currentTimeMillis();
        final long minCrashIntervalMs = DeviceConfig.getLong(DeviceConfig.NAMESPACE_CONNECTIVITY,
                CONFIG_MIN_CRASH_INTERVAL_MS, DEFAULT_MIN_CRASH_INTERVAL_MS);
        final long minUptimeBeforeCrash = DeviceConfig.getLong(DeviceConfig.NAMESPACE_CONNECTIVITY,
                CONFIG_MIN_UPTIME_BEFORE_CRASH_MS, DEFAULT_MIN_UPTIME_BEFORE_CRASH_MS);
        final boolean alwaysRatelimit = DeviceConfig.getBoolean(DeviceConfig.NAMESPACE_CONNECTIVITY,
                CONFIG_ALWAYS_RATELIMIT_NETWORKSTACK_CRASH, false);

        final SharedPreferences prefs = getSharedPreferences();
        final long lastCrashTime = tryGetLastCrashTime(prefs);

        // Only crash if there was enough time since boot, and (if known) enough time passed since
        // the last crash.
        // time and lastCrashTime may be unreliable if devices have incorrect clock time, but they
        // are only used to limit the number of crashes compared to only using the time since boot,
        // which would also be OK behavior by itself.
        // - If lastCrashTime is incorrectly more than the current time, only look at uptime
        // - If it is much less than current time, only look at uptime
        // - If current time is during the next few hours after last crash time, don't crash.
        //   Considering that this only matters if last boot was some time ago, it's likely that
        //   time will be set correctly. Otherwise, not crashing is not a big problem anyway. Being
        //   in this last state would also not last for long since the window is only a few hours.
        final boolean alwaysCrash = Build.IS_DEBUGGABLE && !alwaysRatelimit;
        final boolean justBooted = uptime < minUptimeBeforeCrash;
        final boolean haveLastCrashTime = (lastCrashTime != 0) && (lastCrashTime < now);
        final boolean haveKnownRecentCrash =
                haveLastCrashTime && (now < lastCrashTime + minCrashIntervalMs);
        if (alwaysCrash || (!justBooted && !haveKnownRecentCrash)) {
            // The system is not bound to its network stack (for example due to a crash in the
            // network stack process): better crash rather than stay in a bad state where all
            // networking is broken.
            // Using device-encrypted SharedPreferences as DeviceConfig does not have a synchronous
            // API to persist settings before a crash.
            tryWriteLastCrashTime(prefs, now);
            throw new IllegalStateException(message);
        }

        // Here the system crashed recently already. Inform listeners that something is
        // definitely wrong.
        if (packageName != null) {
            final ArraySet<ConnectivityModuleHealthListener> listeners;
            synchronized (mHealthListeners) {
                listeners = new ArraySet<>(mHealthListeners);
            }
            for (ConnectivityModuleHealthListener listener : listeners) {
                listener.onNetworkStackFailure(packageName);
            }
        }
    }

    @Nullable
    private SharedPreferences getSharedPreferences() {
        try {
            final File prefsFile = new File(
                    Environment.getDataSystemDeDirectory(UserHandle.USER_SYSTEM), PREFS_FILE);
            return mContext.createDeviceProtectedStorageContext()
                    .getSharedPreferences(prefsFile, Context.MODE_PRIVATE);
        } catch (Throwable e) {
            logWtf("Error loading shared preferences", e);
            return null;
        }
    }

    private long tryGetLastCrashTime(@Nullable SharedPreferences prefs) {
        if (prefs == null) return 0L;
        try {
            return prefs.getLong(PREF_KEY_LAST_CRASH_TIME, 0L);
        } catch (Throwable e) {
            logWtf("Error getting last crash time", e);
            return 0L;
        }
    }

    private void tryWriteLastCrashTime(@Nullable SharedPreferences prefs, long value) {
        if (prefs == null) return;
        try {
            prefs.edit().putLong(PREF_KEY_LAST_CRASH_TIME, value).commit();
        } catch (Throwable e) {
            logWtf("Error writing last crash time", e);
        }
    }

    private void log(@NonNull String message) {
        Slog.d(TAG, message);
        synchronized (mLog) {
            mLog.log(message);
        }
    }

    private void logWtf(@NonNull String message, @Nullable Throwable e) {
        Slog.wtf(TAG, message, e);
        synchronized (mLog) {
            mLog.e(message);
        }
    }

    private void loge(@NonNull String message, @Nullable Throwable e) {
        Slog.e(TAG, message, e);
        synchronized (mLog) {
            mLog.e(message);
        }
    }

    private void logi(@NonNull String message) {
        Slog.i(TAG, message);
        synchronized (mLog) {
            mLog.i(message);
        }
    }

    /**
     * Dump ConnectivityModuleConnector logs to the specified {@link PrintWriter}.
     */
    public void dump(PrintWriter pw) {
        // dump is thread-safe on SharedLog
        mLog.dump(null, pw, null);
    }
}
