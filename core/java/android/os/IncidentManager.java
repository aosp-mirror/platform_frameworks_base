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

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresPermission;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.Context;
import android.net.Uri;
import android.util.Slog;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;

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
     * Query parameter for the uris for the incident report id.
     *
     * @hide
     */
    public static final String URI_PARAM_REPORT_ID = "r";

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
     * Query parameter for the uris for the pending report id.
     *
     * @hide
     */
    public static final String URI_PARAM_RECEIVER_CLASS = "receiver";

    /**
     * Do the confirmation with a dialog instead of the default, which is a notification.
     * It is possible for the dialog to be downgraded to a notification in some cases.
     */
    public static final int FLAG_CONFIRMATION_DIALOG = 0x1;

    /**
     * Flag marking fields and incident reports than can be taken
     * off the device only via adb.
     */
    public static final int PRIVACY_POLICY_LOCAL = 0;

    /**
     * Flag marking fields and incident reports than can be taken
     * off the device with contemporary consent.
     */
    public static final int PRIVACY_POLICY_EXPLICIT = 100;

    /**
     * Flag marking fields and incident reports than can be taken
     * off the device with prior consent.
     */
    public static final int PRIVACY_POLICY_AUTO = 200;

    /** @hide */
    @IntDef(flag = false, prefix = { "PRIVACY_POLICY_" }, value = {
            PRIVACY_POLICY_AUTO,
            PRIVACY_POLICY_EXPLICIT,
            PRIVACY_POLICY_LOCAL,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface PrivacyPolicy {}

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

        /**
         * @inheritDoc
         */
        @Override
        public boolean equals(@Nullable Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof PendingReport)) {
                return false;
            }
            final PendingReport that = (PendingReport) obj;
            return this.mUri.equals(that.mUri)
                    && this.mFlags == that.mFlags
                    && this.mRequestingPackage.equals(that.mRequestingPackage)
                    && this.mTimestamp == that.mTimestamp;
        }
    }

    /**
     * Record of an incident report that has previously been taken.
     * @hide
     */
    @SystemApi
    @TestApi
    public static class IncidentReport implements Parcelable, Closeable {
        private final long mTimestampNs;
        private final int mPrivacyPolicy;
        private ParcelFileDescriptor mFileDescriptor;

        public IncidentReport(Parcel in) {
            mTimestampNs = in.readLong();
            mPrivacyPolicy = in.readInt();
            if (in.readInt() != 0) {
                mFileDescriptor = ParcelFileDescriptor.CREATOR.createFromParcel(in);
            } else {
                mFileDescriptor = null;
            }
        }

        /**
         * Close the input stream associated with this entry.
         */
        public void close() {
            try {
                if (mFileDescriptor != null) {
                    mFileDescriptor.close();
                    mFileDescriptor = null;
                }
            } catch (IOException e) {
            }
        }

        /**
         * Get the time at which this incident report was taken, in wall clock time
         * ({@link System#currenttimeMillis System.currenttimeMillis()} time base).
         */
        public long getTimestamp() {
            return mTimestampNs / 1000000;
        }

        /**
         * Get the privacy level to which this report has been filtered.
         *
         * @see #PRIVACY_POLICY_AUTO
         * @see #PRIVACY_POLICY_EXPLICIT
         * @see #PRIVACY_POLICY_LOCAL
         */
        public long getPrivacyPolicy() {
            return mPrivacyPolicy;
        }

        /**
         * Get the contents of this incident report.
         */
        public InputStream getInputStream() throws IOException {
            if (mFileDescriptor == null) {
                return null;
            }
            return new ParcelFileDescriptor.AutoCloseInputStream(mFileDescriptor);
        }

        /**
         * @inheritDoc
         */
        public int describeContents() {
            return mFileDescriptor != null ? Parcelable.CONTENTS_FILE_DESCRIPTOR : 0;
        }

        /**
         * @inheritDoc
         */
        public void writeToParcel(Parcel out, int flags) {
            out.writeLong(mTimestampNs);
            out.writeInt(mPrivacyPolicy);
            if (mFileDescriptor != null) {
                out.writeInt(1);
                mFileDescriptor.writeToParcel(out, flags);
            } else {
                out.writeInt(0);
            }
        }

        /**
         * {@link Parcelable.Creator Creator} for {@link IncidentReport}.
         */
        public static final @android.annotation.NonNull Parcelable.Creator<IncidentReport> CREATOR = new Parcelable.Creator() {
            /**
             * @inheritDoc
             */
            public IncidentReport[] newArray(int size) {
                return new IncidentReport[size];
            }

            /**
             * @inheritDoc
             */
            public IncidentReport createFromParcel(Parcel in) {
                return new IncidentReport(in);
            }
        };
    }

    /**
     * Listener for the status of an incident report being authorized or denied.
     *
     * @see #requestAuthorization
     * @see #cancelAuthorization
     */
    public static class AuthListener {
        Executor mExecutor;

        IIncidentAuthListener.Stub mBinder = new IIncidentAuthListener.Stub() {
            @Override
            public void onReportApproved() {
                if (mExecutor != null) {
                    mExecutor.execute(() -> {
                        AuthListener.this.onReportApproved();
                    });
                } else {
                    AuthListener.this.onReportApproved();
                }
            }

            @Override
            public void onReportDenied() {
                if (mExecutor != null) {
                    mExecutor.execute(() -> {
                        AuthListener.this.onReportDenied();
                    });
                } else {
                    AuthListener.this.onReportDenied();
                }
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
     * Callback for dumping an extended (usually vendor-supplied) incident report section
     *
     * @see #registerSection
     * @see #unregisterSection
     */
    public static class DumpCallback {
        private int mId;
        private Executor mExecutor;

        IIncidentDumpCallback.Stub mBinder = new IIncidentDumpCallback.Stub() {
            @Override
            public void onDumpSection(ParcelFileDescriptor pfd) {
                if (mExecutor != null) {
                    mExecutor.execute(() -> {
                        DumpCallback.this.onDumpSection(mId,
                                new ParcelFileDescriptor.AutoCloseOutputStream(pfd));
                    });
                } else {
                    DumpCallback.this.onDumpSection(mId,
                            new ParcelFileDescriptor.AutoCloseOutputStream(pfd));
                }
            }
        };

        /**
         * Dump the registered section as a protobuf message to the given OutputStream. Called when
         * incidentd requests to dump this section.
         *
         * @param id  the id of the registered section. The same id used in calling
         *            {@link #registerSection(int, String, DumpCallback)} will be passed in here.
         * @param out the OutputStream to write the protobuf message
         */
        public void onDumpSection(int id, @NonNull OutputStream out) {
        }
    }

    /**
     * @hide
     */
    public IncidentManager(Context context) {
        mContext = context;
    }

    /**
     * Take an incident report.
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
        requestAuthorization(callingUid, callingPackage, flags,
                mContext.getMainExecutor(), listener);
    }

    /**
     * Request authorization of an incident report.
     * @hide
     */
    @RequiresPermission(android.Manifest.permission.REQUEST_INCIDENT_REPORT_APPROVAL)
    public void requestAuthorization(int callingUid, @NonNull String callingPackage, int flags,
             @NonNull @CallbackExecutor Executor executor, @NonNull AuthListener listener) {
        try {
            if (listener.mExecutor != null) {
                throw new RuntimeException("Do not reuse AuthListener objects when calling"
                        + " requestAuthorization");
            }
            listener.mExecutor = executor;
            getCompanionServiceLocked().authorizeReport(callingUid, callingPackage, null, null,
                    flags, listener.mBinder);
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

    /**
     * Register a callback to dump an extended incident report section with the given id and name,
     * running on the supplied executor.
     *
     * Calling <code>registerSection</code> with a duplicate id will override previous registration.
     * However, the request must come from the same calling uid.
     *
     * @param id       the ID of the extended section. It should be unique system-wide, and be
     *                 different from IDs of all existing section in
     *                 frameworks/base/core/proto/android/os/incident.proto.
     *                 Also see incident.proto for other rules about the ID.
     * @param name     the name to display in logs and/or stderr when taking an incident report
     *                 containing this section, mainly for debugging purpose
     * @param executor the executor used to run the callback
     * @param callback the callback function to be invoked when an incident report with all sections
     *                 or sections matching the given id is being taken
     */
    public void registerSection(int id, @NonNull String name,
                @NonNull @CallbackExecutor Executor executor, @NonNull DumpCallback callback) {
        Objects.requireNonNull(executor, "executor cannot be null");
        Objects.requireNonNull(callback, "callback cannot be null");
        try {
            if (callback.mExecutor != null) {
                throw new RuntimeException("Do not reuse DumpCallback objects when calling"
                        + " registerSection");
            }
            callback.mExecutor = executor;
            callback.mId = id;
            final IIncidentManager service = getIIncidentManagerLocked();
            if (service == null) {
                Slog.e(TAG, "registerSection can't find incident binder service");
                return;
            }
            service.registerSection(id, name, callback.mBinder);
        } catch (RemoteException ex) {
            Slog.e(TAG, "registerSection failed", ex);
        }
    }

    /**
     * Unregister an extended section dump function. The section must be previously registered with
     * {@link #registerSection(int, String, DumpCallback)} by the same calling uid.
     */
    public void unregisterSection(int id) {
        try {
            final IIncidentManager service = getIIncidentManagerLocked();
            if (service == null) {
                Slog.e(TAG, "unregisterSection can't find incident binder service");
                return;
            }
            service.unregisterSection(id);
        } catch (RemoteException ex) {
            Slog.e(TAG, "unregisterSection failed", ex);
        }
    }

    /**
     * Get the incident reports that are available for upload for the supplied
     * broadcast recevier.
     *
     * @param receiverClass Class name of broadcast receiver in this package that
     *   was registered to retrieve reports.
     *
     * @return A list of {@link Uri Uris} that are awaiting upload.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DUMP,
            android.Manifest.permission.PACKAGE_USAGE_STATS
    })
    public @NonNull List<Uri> getIncidentReportList(String receiverClass) {
        List<String> strings;
        try {
            strings = getCompanionServiceLocked().getIncidentReportList(
                    mContext.getPackageName(), receiverClass);
        } catch (RemoteException ex) {
            throw new RuntimeException("System server or incidentd going down", ex);
        }
        final int size = strings.size();
        ArrayList<Uri> result = new ArrayList(size);
        for (int i = 0; i < size; i++) {
            result.add(Uri.parse(strings.get(i)));
        }
        return result;
    }

    /**
     * Get the incident report with the given URI id.
     *
     * @param uri Identifier of the incident report.
     *
     * @return an IncidentReport object, or null if the incident report has been
     *  expired from disk.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DUMP,
            android.Manifest.permission.PACKAGE_USAGE_STATS
    })
    public @Nullable IncidentReport getIncidentReport(Uri uri) {
        final String id = uri.getQueryParameter(URI_PARAM_REPORT_ID);
        if (id == null) {
            // If there's no report id, it's a bug report, so we can't return the incident
            // report.
            return null;
        }

        final String pkg = uri.getQueryParameter(URI_PARAM_CALLING_PACKAGE);
        if (pkg == null) {
            throw new RuntimeException("Invalid URI: No "
                    + URI_PARAM_CALLING_PACKAGE + " parameter. " + uri);
        }

        final String cls = uri.getQueryParameter(URI_PARAM_RECEIVER_CLASS);
        if (cls == null) {
            throw new RuntimeException("Invalid URI: No "
                    + URI_PARAM_RECEIVER_CLASS + " parameter. " + uri);
        }

        try {
            return getCompanionServiceLocked().getIncidentReport(pkg, cls, id);
        } catch (RemoteException ex) {
            throw new RuntimeException("System server or incidentd going down", ex);
        }
    }

    /**
     * Delete the incident report with the given URI id.
     *
     * @param uri Identifier of the incident report. Pass null to delete all
     *              incident reports owned by this application.
     */
    @RequiresPermission(allOf = {
            android.Manifest.permission.DUMP,
            android.Manifest.permission.PACKAGE_USAGE_STATS
    })
    public void deleteIncidentReports(Uri uri) {
        if (uri == null) {
            try {
                getCompanionServiceLocked().deleteAllIncidentReports(mContext.getPackageName());
            } catch (RemoteException ex) {
                throw new RuntimeException("System server or incidentd going down", ex);
            }
        } else {
            final String pkg = uri.getQueryParameter(URI_PARAM_CALLING_PACKAGE);
            if (pkg == null) {
                throw new RuntimeException("Invalid URI: No "
                        + URI_PARAM_CALLING_PACKAGE + " parameter. " + uri);
            }

            final String cls = uri.getQueryParameter(URI_PARAM_RECEIVER_CLASS);
            if (cls == null) {
                throw new RuntimeException("Invalid URI: No "
                        + URI_PARAM_RECEIVER_CLASS + " parameter. " + uri);
            }

            final String id = uri.getQueryParameter(URI_PARAM_REPORT_ID);
            if (id == null) {
                throw new RuntimeException("Invalid URI: No "
                        + URI_PARAM_REPORT_ID + " parameter. " + uri);
            }

            try {
                getCompanionServiceLocked().deleteIncidentReports(pkg, cls, id);
            } catch (RemoteException ex) {
                throw new RuntimeException("System server or incidentd going down", ex);
            }
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

