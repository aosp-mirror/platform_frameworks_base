/*
 * Copyright (C) 2017 The Android Open Source Project
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

package android.util;

import static android.Manifest.permission.DUMP;
import static android.Manifest.permission.PACKAGE_USAGE_STATS;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.app.IActivityManager;
import android.content.Context;
import android.os.IStatsManager;
import android.os.RemoteException;
import android.os.ServiceManager;

/**
 * StatsLog provides an API for developers to send events to statsd. The events can be used to
 * define custom metrics inside statsd.
 */
public final class StatsLog extends StatsLogInternal {
    private static final String TAG = "StatsLog";
    private static final boolean DEBUG = false;

    private static IStatsManager sService;

    private static Object sLogLock = new Object();

    private StatsLog() {
    }

    /**
     * Logs a start event.
     *
     * @param label developer-chosen label.
     * @return True if the log request was sent to statsd.
     */
    public static boolean logStart(int label) {
        synchronized (sLogLock) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Failed to find statsd when logging start");
                    }
                    return false;
                }
                service.sendAppBreadcrumbAtom(label,
                        StatsLog.APP_BREADCRUMB_REPORTED__STATE__START);
                return true;
            } catch (RemoteException e) {
                sService = null;
                if (DEBUG) {
                    Slog.d(TAG, "Failed to connect to statsd when logging start");
                }
                return false;
            }
        }
    }

    /**
     * Logs a stop event.
     *
     * @param label developer-chosen label.
     * @return True if the log request was sent to statsd.
     */
    public static boolean logStop(int label) {
        synchronized (sLogLock) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Failed to find statsd when logging stop");
                    }
                    return false;
                }
                service.sendAppBreadcrumbAtom(label, StatsLog.APP_BREADCRUMB_REPORTED__STATE__STOP);
                return true;
            } catch (RemoteException e) {
                sService = null;
                if (DEBUG) {
                    Slog.d(TAG, "Failed to connect to statsd when logging stop");
                }
                return false;
            }
        }
    }

    /**
     * Logs an event that does not represent a start or stop boundary.
     *
     * @param label developer-chosen label.
     * @return True if the log request was sent to statsd.
     */
    public static boolean logEvent(int label) {
        synchronized (sLogLock) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Failed to find statsd when logging event");
                    }
                    return false;
                }
                service.sendAppBreadcrumbAtom(
                        label, StatsLog.APP_BREADCRUMB_REPORTED__STATE__UNSPECIFIED);
                return true;
            } catch (RemoteException e) {
                sService = null;
                if (DEBUG) {
                    Slog.d(TAG, "Failed to connect to statsd when logging event");
                }
                return false;
            }
        }
    }

    /**
     * Logs an event for binary push for module updates.
     *
     * @param trainName        name of install train.
     * @param trainVersionCode version code of the train.
     * @param options          optional flags about this install.
     * @param state            current install state.
     * @param experimentIds    experiment ids.
     * @return True if the log request was sent to statsd.
     */
    @RequiresPermission(allOf = {DUMP, PACKAGE_USAGE_STATS})
    public static boolean logBinaryPushStateChanged(@NonNull String trainName,
            long trainVersionCode, int options, int state,
            @NonNull long[] experimentIds) {
        synchronized (sLogLock) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) {
                        Slog.d(TAG, "Failed to find statsd when logging event");
                    }
                    return false;
                }
                int userId = IActivityManager.Stub.asInterface(
                        ServiceManager.getService("activity"))
                        .getCurrentUser()
                        .id;
                service.sendBinaryPushStateChangedAtom(
                        trainName, trainVersionCode, options, state, experimentIds);
                return true;
            } catch (RemoteException e) {
                sService = null;
                if (DEBUG) {
                    Slog.d(TAG,
                            "Failed to connect to StatsCompanionService when logging "
                                    + "BinaryPushStateChanged");
                }
                return false;
            }
        }
    }

    private static IStatsManager getIStatsManagerLocked() throws RemoteException {
        if (sService != null) {
            return sService;
        }
        sService = IStatsManager.Stub.asInterface(ServiceManager.getService("stats"));
        return sService;
    }

    /**
     * Add a log to the stats log.
     *
     * @param id     The id of the atom
     * @param params The parameters of the atom's message.
     */
    public static void write(int id, @NonNull Object... params) {
        switch (id) {
            case PERMISSION_GRANT_REQUEST_RESULT_REPORTED:
                write(id, (long) params[0], (int) params[1], (String) params[2], (String) params[3],
                        (boolean) params[4], (int) params[5]);
                break;
            case DATA_STALL_EVENT:
                // Refer to the defintion in frameworks/base/cmds/statsd/src/atoms.proto.
                write(id, (int) params[0], (int) params[1], (int) params[2], (byte[]) params[3],
                        (byte[]) params[4], (byte[]) params[5]);
                break;
        }
    }

    private static void enforceDumpCallingPermission(Context context) {
        context.enforceCallingPermission(android.Manifest.permission.DUMP, "Need DUMP permission.");
    }

    private static void enforcesageStatsCallingPermission(Context context) {
        context.enforceCallingPermission(Manifest.permission.PACKAGE_USAGE_STATS,
                "Need PACKAGE_USAGE_STATS permission.");
    }
}
