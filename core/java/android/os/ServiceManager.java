/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.util.ArrayMap;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.BinderInternal;
import com.android.internal.util.Preconditions;
import com.android.internal.util.StatLogger;

import java.util.Map;

/**
 * Manage binder services as registered with the binder context manager. These services must be
 * declared statically on an Android device (SELinux access_vector service_manager, w/ service
 * names in service_contexts files), and they do not follow the activity lifecycle. When
 * building applications, android.app.Service should be preferred.
 *
 * @hide
 **/
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@android.ravenwood.annotation.RavenwoodKeepPartialClass
public final class ServiceManager {
    private static final String TAG = "ServiceManager";
    private static final Object sLock = new Object();

    @UnsupportedAppUsage
    private static IServiceManager sServiceManager;

    /**
     * Cache for the "well known" services, such as WM and AM.
     */
    // NOTE: this cache is designed to be populated exactly once at process
    // start to avoid any overhead from locking
    @UnsupportedAppUsage
    private static Map<String, IBinder> sCache = new ArrayMap<String, IBinder>();

    @GuardedBy("ServiceManager.class")
    // NOTE: this cache is designed to support mutation by tests, so we require
    // a lock to be held for all accesses
    private static Map<String, IBinder> sCache$ravenwood;

    /**
     * We do the "slow log" at most once every this interval.
     */
    private static final int SLOW_LOG_INTERVAL_MS = 5000;

    /**
     * We do the "stats log" at most once every this interval.
     */
    private static final int STATS_LOG_INTERVAL_MS = 5000;

    /**
     * Threshold in uS for a "slow" call, used on core UIDs. We use a more relax value to
     * avoid logspam.
     */
    private static final long GET_SERVICE_SLOW_THRESHOLD_US_CORE =
            SystemProperties.getInt("debug.servicemanager.slow_call_core_ms", 10) * 1000;

    /**
     * Threshold in uS for a "slow" call, used on non-core UIDs. We use a more relax value to
     * avoid logspam.
     */
    private static final long GET_SERVICE_SLOW_THRESHOLD_US_NON_CORE =
            SystemProperties.getInt("debug.servicemanager.slow_call_ms", 50) * 1000;

    /**
     * We log stats logging ever this many getService() calls.
     */
    private static final int GET_SERVICE_LOG_EVERY_CALLS_CORE =
            SystemProperties.getInt("debug.servicemanager.log_calls_core", 100);

    /**
     * We log stats logging ever this many getService() calls.
     */
    private static final int GET_SERVICE_LOG_EVERY_CALLS_NON_CORE =
            SystemProperties.getInt("debug.servicemanager.log_calls", 200);

    @GuardedBy("sLock")
    private static int sGetServiceAccumulatedUs;

    @GuardedBy("sLock")
    private static int sGetServiceAccumulatedCallCount;

    @GuardedBy("sLock")
    private static long sLastStatsLogUptime;

    @GuardedBy("sLock")
    private static long sLastSlowLogUptime;

    @GuardedBy("sLock")
    private static long sLastSlowLogActualTime;

    interface Stats {
        int GET_SERVICE = 0;

        int COUNT = GET_SERVICE + 1;
    }

    /** @hide */
    public static final StatLogger sStatLogger = new StatLogger(new String[] {
            "getService()",
    });

    /** @hide */
    @UnsupportedAppUsage
    @android.ravenwood.annotation.RavenwoodKeep
    public ServiceManager() {
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void init$ravenwood() {
        synchronized (ServiceManager.class) {
            sCache$ravenwood = new ArrayMap<>();
        }
    }

    /** @hide */
    @android.ravenwood.annotation.RavenwoodKeep
    public static void reset$ravenwood() {
        synchronized (ServiceManager.class) {
            sCache$ravenwood.clear();
            sCache$ravenwood = null;
        }
    }

    @UnsupportedAppUsage
    private static IServiceManager getIServiceManager() {
        if (sServiceManager != null) {
            return sServiceManager;
        }

        // Find the service manager
        sServiceManager = ServiceManagerNative
                .asInterface(Binder.allowBlocking(BinderInternal.getContextObject()));
        return sServiceManager;
    }

    /**
     * Returns a reference to a service with the given name.
     *
     * @param name the name of the service to get
     * @return a reference to the service, or <code>null</code> if the service doesn't exist
     * @hide
     */
    @UnsupportedAppUsage
    @android.ravenwood.annotation.RavenwoodReplace
    public static IBinder getService(String name) {
        try {
            IBinder service = sCache.get(name);
            if (service != null) {
                return service;
            } else {
                return Binder.allowBlocking(rawGetService(name));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "error in getService", e);
        }
        return null;
    }

    /** @hide */
    public static IBinder getService$ravenwood(String name) {
        synchronized (ServiceManager.class) {
            // Ravenwood is a single-process environment, so it only needs to store locally
            return Preconditions.requireNonNullViaRavenwoodRule(sCache$ravenwood).get(name);
        }
    }

    /**
     * Returns a reference to a service with the given name, or throws
     * {@link ServiceNotFoundException} if none is found.
     *
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeep
    public static IBinder getServiceOrThrow(String name) throws ServiceNotFoundException {
        final IBinder binder = getService(name);
        if (binder != null) {
            return binder;
        } else {
            throw new ServiceNotFoundException(name);
        }
    }

    /**
     * Place a new @a service called @a name into the service
     * manager.
     *
     * @param name the name of the new service
     * @param service the service object
     * @hide
     */
    @UnsupportedAppUsage
    @android.ravenwood.annotation.RavenwoodKeep
    public static void addService(String name, IBinder service) {
        addService(name, service, false, IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
    }

    /**
     * Place a new @a service called @a name into the service
     * manager.
     *
     * @param name the name of the new service
     * @param service the service object
     * @param allowIsolated set to true to allow isolated sandboxed processes
     * to access this service
     * @hide
     */
    @UnsupportedAppUsage
    @android.ravenwood.annotation.RavenwoodKeep
    public static void addService(String name, IBinder service, boolean allowIsolated) {
        addService(name, service, allowIsolated, IServiceManager.DUMP_FLAG_PRIORITY_DEFAULT);
    }

    /**
     * Place a new @a service called @a name into the service
     * manager.
     *
     * @param name the name of the new service
     * @param service the service object
     * @param allowIsolated set to true to allow isolated sandboxed processes
     * @param dumpPriority supported dump priority levels as a bitmask
     * to access this service
     * @hide
     */
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    @android.ravenwood.annotation.RavenwoodReplace
    public static void addService(String name, IBinder service, boolean allowIsolated,
            int dumpPriority) {
        try {
            getIServiceManager().addService(name, service, allowIsolated, dumpPriority);
        } catch (RemoteException e) {
            Log.e(TAG, "error in addService", e);
        }
    }

    /** @hide */
    public static void addService$ravenwood(String name, IBinder service, boolean allowIsolated,
            int dumpPriority) {
        synchronized (ServiceManager.class) {
            // Ravenwood is a single-process environment, so it only needs to store locally
            Preconditions.requireNonNullViaRavenwoodRule(sCache$ravenwood).put(name, service);
        }
    }

    /**
     * Retrieve an existing service called @a name from the
     * service manager.  Non-blocking.
     * @hide
     */
    @UnsupportedAppUsage
    public static IBinder checkService(String name) {
        try {
            IBinder service = sCache.get(name);
            if (service != null) {
                return service;
            } else {
                return Binder.allowBlocking(getIServiceManager().checkService(name));
            }
        } catch (RemoteException e) {
            Log.e(TAG, "error in checkService", e);
            return null;
        }
    }

    /**
     * Returns whether the specified service is declared.
     *
     * @return true if the service is declared somewhere (eg. VINTF manifest) and
     * waitForService should always be able to return the service.
     */
    public static boolean isDeclared(@NonNull String name) {
        try {
            return getIServiceManager().isDeclared(name);
        } catch (RemoteException | SecurityException e) {
            Log.e(TAG, "error in isDeclared", e);
            return false;
        }
    }

    /**
     * Returns an array of all declared instances for a particular interface.
     *
     * For instance, if 'android.foo.IFoo/foo' is declared (e.g. in VINTF
     * manifest), and 'android.foo.IFoo' is passed here, then ["foo"] would be
     * returned.
     *
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @NonNull
    public static String[] getDeclaredInstances(@NonNull String iface) {
        try {
            return getIServiceManager().getDeclaredInstances(iface);
        } catch (RemoteException e) {
            Log.e(TAG, "error in getDeclaredInstances", e);
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the specified service from the service manager.
     *
     * If the service is not running, servicemanager will attempt to start it, and this function
     * will wait for it to be ready.
     *
     * @return {@code null} only if there are permission problems or fatal errors.
     * @hide
     */
    public static IBinder waitForService(@NonNull String name) {
        return Binder.allowBlocking(waitForServiceNative(name));
    }

    private static native IBinder waitForServiceNative(@NonNull String name);

    /**
     * Returns the specified service from the service manager, if declared.
     *
     * If the service is not running, servicemanager will attempt to start it, and this function
     * will wait for it to be ready.
     *
     * @throws SecurityException if the process does not have the permissions to check
     * isDeclared() for the service.
     * @return {@code null} if the service is not declared in the manifest, or if there
     * are fatal errors.
     * @hide
     */
    @SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
    @Nullable public static IBinder waitForDeclaredService(@NonNull String name) {
        return isDeclared(name) ? waitForService(name) : null;
    }

    /**
     * Register callback for service registration notifications.
     *
     * @throws RemoteException for underlying error.
     * @hide
     */
    public static void registerForNotifications(
            @NonNull String name, @NonNull IServiceCallback callback) throws RemoteException {
        getIServiceManager().registerForNotifications(name, callback);
    }

    /**
     * Return a list of all currently running services.
     * @return an array of all currently running services, or <code>null</code> in
     * case of an exception
     * @hide
     */
    @UnsupportedAppUsage
    public static String[] listServices() {
        try {
            return getIServiceManager().listServices(IServiceManager.DUMP_FLAG_PRIORITY_ALL);
        } catch (RemoteException e) {
            Log.e(TAG, "error in listServices", e);
            return null;
        }
    }

    /**
     * Get service debug info.
     * @return an array of information for each service (like listServices, but with PIDs)
     * @hide
     */
    public static ServiceDebugInfo[] getServiceDebugInfo() {
        try {
            return getIServiceManager().getServiceDebugInfo();
        } catch (RemoteException e) {
            Log.e(TAG, "error in getServiceDebugInfo", e);
            return null;
        }
    }

    /**
     * This is only intended to be called when the process is first being brought
     * up and bound by the activity manager. There is only one thread in the process
     * at that time, so no locking is done.
     *
     * @param cache the cache of service references
     * @hide
     */
    public static void initServiceCache(Map<String, IBinder> cache) {
        if (sCache.size() != 0) {
            throw new IllegalStateException("setServiceCache may only be called once");
        }
        sCache.putAll(cache);
    }

    /**
     * Exception thrown when no service published for given name. This might be
     * thrown early during boot before certain services have published
     * themselves.
     *
     * @hide
     */
    @android.ravenwood.annotation.RavenwoodKeepWholeClass
    public static class ServiceNotFoundException extends Exception {
        public ServiceNotFoundException(String name) {
            super("No service published for: " + name);
        }
    }

    private static IBinder rawGetService(String name) throws RemoteException {
        final long start = sStatLogger.getTime();

        final IBinder binder = getIServiceManager().getService(name);

        final int time = (int) sStatLogger.logDurationStat(Stats.GET_SERVICE, start);

        final int myUid = Process.myUid();
        final boolean isCore = UserHandle.isCore(myUid);

        final long slowThreshold = isCore
                ? GET_SERVICE_SLOW_THRESHOLD_US_CORE
                : GET_SERVICE_SLOW_THRESHOLD_US_NON_CORE;

        synchronized (sLock) {
            sGetServiceAccumulatedUs += time;
            sGetServiceAccumulatedCallCount++;

            final long nowUptime = SystemClock.uptimeMillis();

            // Was a slow call?
            if (time >= slowThreshold) {
                // We do a slow log:
                // - At most once in every SLOW_LOG_INTERVAL_MS
                // - OR it was slower than the previously logged slow call.
                if ((nowUptime > (sLastSlowLogUptime + SLOW_LOG_INTERVAL_MS))
                        || (sLastSlowLogActualTime < time)) {
                    EventLogTags.writeServiceManagerSlow(time / 1000, name);

                    sLastSlowLogUptime = nowUptime;
                    sLastSlowLogActualTime = time;
                }
            }

            // Every GET_SERVICE_LOG_EVERY_CALLS calls, log the total time spent in getService().

            final int logInterval = isCore
                    ? GET_SERVICE_LOG_EVERY_CALLS_CORE
                    : GET_SERVICE_LOG_EVERY_CALLS_NON_CORE;

            if ((sGetServiceAccumulatedCallCount >= logInterval)
                    && (nowUptime >= (sLastStatsLogUptime + STATS_LOG_INTERVAL_MS))) {

                EventLogTags.writeServiceManagerStats(
                        sGetServiceAccumulatedCallCount, // Total # of getService() calls.
                        sGetServiceAccumulatedUs / 1000, // Total time spent in getService() calls.
                        (int) (nowUptime - sLastStatsLogUptime)); // Uptime duration since last log.
                sGetServiceAccumulatedCallCount = 0;
                sGetServiceAccumulatedUs = 0;
                sLastStatsLogUptime = nowUptime;
            }
        }
        return binder;
    }
}
