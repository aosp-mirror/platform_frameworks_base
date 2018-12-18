/*
 * Copyright (C) 2016 The Android Open Source Project
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
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.net.Uri;
import android.util.Slog;

import java.util.ArrayList;
import java.util.List;

/**
 * Class to take an incident report.
 *
 * @hide
 */
@SystemApi
@TestApi
@SystemService(Context.INCIDENT_SERVICE)
public class IncidentManager {
    private static final String TAG = "IncidentManager";

    /**
     * Authority for pending report id urls.
     *
     * @hide
     */
    public static final String URI_SCHEME = "content";

    /**
     * Authority for pending report id urls.
     *
     * @hide
     */
    public static final String URI_AUTHORITY = "android.os.IncidentManager";

    /**
     * Authority for pending report id urls.
     *
     * @hide
     */
    public static final String URI_PATH = "/pending";

    /**
     * Query parameter for the uris for the pending report id.
     *
     * @hide
     */
    public static final String URI_PARAM_ID = "id";

    /**
     * Query parameter for the uris for the pending report id.
     *
     * @hide
     */
    public static final String URI_PARAM_CALLING_PACKAGE = "pkg";

    /**
     * Query parameter for the uris for the pending report id, in wall clock
     * ({@link System.currentTimeMillis()}) timebase.
     *
     * @hide
     */
    public static final String URI_PARAM_TIMESTAMP = "t";

    /**
     * Query parameter for the uris for the pending report id.
     *
     * @hide
     */
    public static final String URI_PARAM_FLAGS = "flags";

    /**
     * Do the confirmation with a dialog instead of the default, which is a notification.
     * It is possible for the dialog to be downgraded to a notification in some cases.
     */
    public static final int FLAG_CONFIRMATION_DIALOG = 0x1;

    private final Context mContext;

    private Object mLock = new Object();
    private IIncidentManager mIncidentService;
    private IIncidentCompanion mCompanionService;

    /**
     * Record for a report that has been taken and is pending user authorization
     * to share it.
     * @hide
     */
    @SystemApi
    @TestApi
    public static class PendingReport {
        /**
         * Encoded data.
         */
        private final Uri mUri;

        /**
         * URI_PARAM_FLAGS from the uri
         */
        private final int mFlags;

        /**
         * URI_PARAM_CALLING_PACKAGE from the uri
         */
        private final String mRequestingPackage;

        /**
         * URI_PARAM_TIMESTAMP from the uri
         */
        private final long mTimestamp;

        /**
         * Constructor.
         */
        public PendingReport(@NonNull Uri uri) {
            int flags = 0;
            try {
                flags = Integer.parseInt(uri.getQueryParameter(URI_PARAM_FLAGS));
            } catch (NumberFormatException ex) {
                throw new RuntimeException("Invalid URI: No " + URI_PARAM_FLAGS
                        + " parameter. " + uri);
            }
            mFlags = flags;

            String requestingPackage = uri.getQueryParameter(URI_PARAM_CALLING_PACKAGE);
            if (requestingPackage == null) {
                throw new RuntimeException("Invalid URI: No " + URI_PARAM_CALLING_PACKAGE
                        + " parameter. " + uri);
            }
            mRequestingPackage = requestingPackage;

            long timestamp = -1;
            try {
                timestamp = Long.parseLong(uri.getQueryParameter(URI_PARAM_TIMESTAMP));
            } catch (NumberFormatException ex) {
                throw new RuntimeException("Invalid URI: No " + URI_PARAM_TIMESTAMP
                        + " parameter. " + uri);
            }
            mTimestamp = timestamp;

            mUri = uri;
        }

        /**
         * Get the package with which this report will be shared.
         */
        public @NonNull String getRequestingPackage() {
            return mRequestingPackage;
        }

        /**
         * Get the flags requested for this pending report.
         *
         * @see #FLAG_CONFIRMATION_DIALOG
         */
        public int getFlags() {
            return mFlags;
        }

        /**
         * Get the time this pending report was posted.
         */
        public long getTimestamp() {
            return mTimestamp;
        }

        /**
         * Get the URI associated with this PendingReport.  It can be used to
         * re-retrieve it from {@link IncidentManager} or set as the data field of
         * an Intent.
         */
        public @NonNull Uri getUri() {
            return mUri;
        }

        /**
         * String representation of this PendingReport.
         */
        @Override
        public @NonNull String toString() {
            return "PendingReport(" + getUri().toString() + ")";
        }
    }

    /**
     * Listener for the status of an incident report being authroized or denied.
     *
     * @see #requestAuthorization
     * @see #cancelAuthorization
     */
    public static class AuthListener {
        IIncidentAuthListener.Stub mBinder = new IIncidentAuthListener.Stub() {
            @Override
            public void onReportApproved() {
                AuthListener.this.onReportApproved();
            }

            @Override
            public void onReportDenied() {
                AuthListener.this.onReportDenied();
            }
        };

        /**
         * Called when a report is approved.
         */
        public void onReportApproved() {
        }

        /**
         * Called when a report is denied.
         */
        public void onReportDenied() {
        }
    }

    /**
     * @hide
     */
    public IncidentManager(Context context) {
        mContext = context;
    }

    /**
     * Take an incident report and put it in dropbox.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DUMP,
            android.Manifest.permission.PACKAGE_USAGE_STATS
    })
    public void reportIncident(IncidentReportArgs args) {
        reportIncidentInternal(args);
    }

    /**
     * Request authorization of an incident report.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_INCIDENT_REPORT_APPROVAL)
    public void requestAuthorization(int callingUid, String callingPackage, int flags,
            AuthListener listener) {
        try {
            getCompanionServiceLocked().authorizeReport(callingUid, callingPackage, flags,
                    listener.mBinder);
        } catch (RemoteException ex) {
            // System process going down
            throw new RuntimeException(ex);
        }
    }

    /**
     * Cancel a previous request for incident report authorization.
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_INCIDENT_REPORT_APPROVAL)
    public void cancelAuthorization(AuthListener listener) {
        try {
            getCompanionServiceLocked().cancelAuthorization(listener.mBinder);
        } catch (RemoteException ex) {
            // System process going down
            throw new RuntimeException(ex);
        }
    }

    /**
     * Get incident (and bug) reports that are pending approval to share.
     */
    @RequiresPermission(android.Manifest.permission.APPROVE_INCIDENT_REPORTS)
    public List<PendingReport> getPendingReports() {
        List<String> strings;
        try {
            strings = getCompanionServiceLocked().getPendingReports();
        } catch (RemoteException ex) {
            throw new RuntimeException(ex);
        }
        final int size = strings.size();
        ArrayList<PendingReport> result = new ArrayList(size);
        for (int i = 0; i < size; i++) {
            result.add(new PendingReport(Uri.parse(strings.get(i))));
        }
        return result;
    }

    /**
     * Allow this report to be shared with the given app.
     */
    @RequiresPermission(android.Manifest.permission.APPROVE_INCIDENT_REPORTS)
    public void approveReport(Uri uri) {
        try {
            getCompanionServiceLocked().approveReport(uri.toString());
        } catch (RemoteException ex) {
            // System process going down
            throw new RuntimeException(ex);
        }
    }

    /**
     * Do not allow this report to be shared with the given app.
     */
    @RequiresPermission(android.Manifest.permission.APPROVE_INCIDENT_REPORTS)
    public void denyReport(Uri uri) {
        try {
            getCompanionServiceLocked().denyReport(uri.toString());
        } catch (RemoteException ex) {
            // System process going down
            throw new RuntimeException(ex);
        }
    }

    private void reportIncidentInternal(IncidentReportArgs args) {
        try {
            final IIncidentManager service = getIIncidentManagerLocked();
            if (service == null) {
                Slog.e(TAG, "reportIncident can't find incident binder service");
                return;
            }
            service.reportIncident(args);
        } catch (RemoteException ex) {
            Slog.e(TAG, "reportIncident failed", ex);
        }
    }

    private IIncidentManager getIIncidentManagerLocked() throws RemoteException {
        if (mIncidentService != null) {
            return mIncidentService;
        }

        synchronized (mLock) {
            if (mIncidentService != null) {
                return mIncidentService;
            }
            mIncidentService = IIncidentManager.Stub.asInterface(
                ServiceManager.getService(Context.INCIDENT_SERVICE));
            if (mIncidentService != null) {
                mIncidentService.asBinder().linkToDeath(() -> {
                    synchronized (mLock) {
                        mIncidentService = null;
                    }
                }, 0);
            }
            return mIncidentService;
        }
    }

    private IIncidentCompanion getCompanionServiceLocked() throws RemoteException {
        if (mCompanionService != null) {
            return mCompanionService;
        }

        synchronized (this) {
            if (mCompanionService != null) {
                return mCompanionService;
            }
            mCompanionService = IIncidentCompanion.Stub.asInterface(
                ServiceManager.getService(Context.INCIDENT_COMPANION_SERVICE));
            if (mCompanionService != null) {
                mCompanionService.asBinder().linkToDeath(() -> {
                    synchronized (mLock) {
                        mCompanionService = null;
                    }
                }, 0);
            }
            return mCompanionService;
        }
    }
}

