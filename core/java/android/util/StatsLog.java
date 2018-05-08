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

    private StatsLog() {}

    /**
     * Logs a start event.
     *
     * @param label developer-chosen label.
     * @return True if the log request was sent to statsd.
     */
    public static boolean logStart(int label) {
        synchronized (StatsLog.class) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) Slog.d(TAG, "Failed to find statsd when logging start");
                    return false;
                }
                service.sendAppBreadcrumbAtom(label,
                        StatsLog.APP_BREADCRUMB_REPORTED__STATE__START);
                return true;
            } catch (RemoteException e) {
                sService = null;
                if (DEBUG) Slog.d(TAG, "Failed to connect to statsd when logging start");
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
        synchronized (StatsLog.class) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) Slog.d(TAG, "Failed to find statsd when logging stop");
                    return false;
                }
                service.sendAppBreadcrumbAtom(label, StatsLog.APP_BREADCRUMB_REPORTED__STATE__STOP);
                return true;
            } catch (RemoteException e) {
                sService = null;
                if (DEBUG) Slog.d(TAG, "Failed to connect to statsd when logging stop");
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
        synchronized (StatsLog.class) {
            try {
                IStatsManager service = getIStatsManagerLocked();
                if (service == null) {
                    if (DEBUG) Slog.d(TAG, "Failed to find statsd when logging event");
                    return false;
                }
                service.sendAppBreadcrumbAtom(
                        label, StatsLog.APP_BREADCRUMB_REPORTED__STATE__UNSPECIFIED);
                return true;
            } catch (RemoteException e) {
                sService = null;
                if (DEBUG) Slog.d(TAG, "Failed to connect to statsd when logging event");
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
}
