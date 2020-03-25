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

package com.android.server.incident;

import android.app.ActivityManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.IIncidentAuthListener;
import android.os.IIncidentCompanion;
import android.os.IIncidentManager;
import android.os.IncidentManager;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.Log;

import com.android.internal.util.DumpUtils;
import com.android.server.SystemService;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.List;

/**
 * Helper service for incidentd and dumpstated to provide user feedback
 * and authorization for bug and inicdent reports to be taken.
 */
public class IncidentCompanionService extends SystemService {
    static final String TAG = "IncidentCompanionService";
    // TODO(b/152289743): Expose below intent.
    private static final String INTENT_CHECK_USER_CONSENT =
            "com.android.internal.intent.action.CHECK_USER_CONSENT";

    /**
     * Dump argument for proxying restricted image dumps to the services
     * listed in the config.
     */
    private static String[] RESTRICTED_IMAGE_DUMP_ARGS = new String[] {
        "--hal", "--restricted_image" };

    /**
     * The two permissions, for sendBroadcastAsUserMultiplePermissions.
     */
    private static final String[] DUMP_AND_USAGE_STATS_PERMISSIONS = new String[] {
        android.Manifest.permission.DUMP,
        android.Manifest.permission.PACKAGE_USAGE_STATS
    };

    /**
     * Tracker for reports pending approval.
     */
    private PendingReports mPendingReports;

    /**
     * Implementation of the IIncidentCompanion binder interface.
     */
    private final class BinderService extends IIncidentCompanion.Stub {
        /**
         * ONEWAY binder call to initiate authorizing the report. If you don't need
         * IncidentCompanionService to check whether the calling UID matches then
         * pass 0 for callingUid.  Either way, the caller must have DUMP and USAGE_STATS
         * permissions to retrieve the data, so it ends up being about the same.
         */
        @Override
        public void authorizeReport(int callingUid, final String callingPackage,
                final String receiverClass, final String reportId,
                final int flags, final IIncidentAuthListener listener) {
            enforceRequestAuthorizationPermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                Intent intent = new Intent(INTENT_CHECK_USER_CONSENT);
                intent.setPackage(callingPackage);
                intent.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                intent.addFlags(Intent.FLAG_RECEIVER_INCLUDE_BACKGROUND);
                getContext().sendBroadcast(intent, android.Manifest.permission.DUMP);

                mPendingReports.authorizeReport(callingUid, callingPackage,
                        receiverClass, reportId, flags, listener);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * ONEWAY binder call to cancel the inbound authorization request.
         * <p>
         * This is a oneway call, and so is authorizeReport, so the
         * caller's ordering is preserved.  The other calls on this object are synchronous, so
         * their ordering is not guaranteed with respect to these calls.  So the implementation
         * sends out extra broadcasts to allow for eventual consistency.
         */
        public void cancelAuthorization(final IIncidentAuthListener listener) {
            enforceRequestAuthorizationPermission();

            // Caller can cancel if they don't want it anymore, and mRequestQueue elides
            // authorize/cancel pairs.
            final long ident = Binder.clearCallingIdentity();
            try {
                mPendingReports.cancelAuthorization(listener);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * ONEWAY implementation to send broadcast from incidentd, which is native.
         */
        @Override
        public void sendReportReadyBroadcast(String pkg, String cls) {
            enforceRequestAuthorizationPermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                final Context context = getContext();

                final int primaryUser = getAndValidateUser(context);
                if (primaryUser == UserHandle.USER_NULL) {
                    return;
                }

                final Intent intent = new Intent(Intent.ACTION_INCIDENT_REPORT_READY);
                intent.setComponent(new ComponentName(pkg, cls));

                Log.d(TAG, "sendReportReadyBroadcast sending primaryUser=" + primaryUser
                        + " userHandle=" + UserHandle.getUserHandleForUid(primaryUser)
                        + " intent=" + intent);

                // Send it to the primary user.  Only they can do incident reports.
                context.sendBroadcastAsUserMultiplePermissions(intent,
                        UserHandle.getUserHandleForUid(primaryUser),
                        DUMP_AND_USAGE_STATS_PERMISSIONS);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS binder call to get the list of reports that are pending confirmation
         * by the user.
         */
        @Override
        public List<String> getPendingReports() {
            enforceAuthorizePermission();
            return mPendingReports.getPendingReports();
        }

        /**
         * SYNCHRONOUS binder call to mark a report as approved.
         */
        @Override
        public void approveReport(String uri) {
            enforceAuthorizePermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                mPendingReports.approveReport(uri);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS binder call to mark a report as NOT approved.
         */
        @Override
        public void denyReport(String uri) {
            enforceAuthorizePermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                mPendingReports.denyReport(uri);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS binder call to get the list of incident reports waiting for a receiver.
         */
        @Override
        public List<String> getIncidentReportList(String pkg, String cls) throws RemoteException {
            enforceAccessReportsPermissions(null);

            final long ident = Binder.clearCallingIdentity();
            try {
                return getIIncidentManager().getIncidentReportList(pkg, cls);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS binder call to commit an incident report
         */
        @Override
        public void deleteIncidentReports(String pkg, String cls, String id)
                throws RemoteException {
            if (pkg == null || cls == null || id == null
                    || pkg.length() == 0 || cls.length() == 0 || id.length() == 0) {
                throw new RuntimeException("Invalid pkg, cls or id");
            }
            enforceAccessReportsPermissions(pkg);

            final long ident = Binder.clearCallingIdentity();
            try {
                getIIncidentManager().deleteIncidentReports(pkg, cls, id);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS binder call to delete all incident reports for a package.
         */
        @Override
        public void deleteAllIncidentReports(String pkg) throws RemoteException {
            if (pkg == null || pkg.length() == 0) {
                throw new RuntimeException("Invalid pkg");
            }
            enforceAccessReportsPermissions(pkg);

            final long ident = Binder.clearCallingIdentity();
            try {
                getIIncidentManager().deleteAllIncidentReports(pkg);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS binder call to get the IncidentReport object.
         */
        @Override
        public IncidentManager.IncidentReport getIncidentReport(String pkg, String cls, String id)
                throws RemoteException {
            if (pkg == null || cls == null || id == null
                    || pkg.length() == 0 || cls.length() == 0 || id.length() == 0) {
                throw new RuntimeException("Invalid pkg, cls or id");
            }
            enforceAccessReportsPermissions(pkg);

            final long ident = Binder.clearCallingIdentity();
            try {
                return getIIncidentManager().getIncidentReport(pkg, cls, id);
            } finally {
                Binder.restoreCallingIdentity(ident);
            }
        }

        /**
         * SYNCHRONOUS implementation of adb shell dumpsys debugreportcompanion.
         */
        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, writer)) {
                return;
            }

            if (args.length == 1 && "--restricted_image".equals(args[0])) {
                // Does NOT clearCallingIdentity
                dumpRestrictedImages(fd);
            } else {
                // Regular dump
                mPendingReports.dump(fd, writer, args);
            }
        }

        /**
         * Proxy for the restricted images section.
         */
        private void dumpRestrictedImages(FileDescriptor fd) {
            // Only supported on eng or userdebug.
            if (!(Build.IS_ENG || Build.IS_USERDEBUG)) {
                return;
            }

            final Resources res = getContext().getResources();
            final String[] services = res.getStringArray(
                    com.android.internal.R.array.config_restrictedImagesServices);
            final int servicesCount = services.length;
            for (int i = 0; i < servicesCount; i++) {
                final String name = services[i];
                Log.d(TAG, "Looking up service " + name);
                final IBinder service = ServiceManager.getService(name);
                if (service != null) {
                    Log.d(TAG, "Calling dump on service: " + name);
                    try {
                        service.dump(fd, RESTRICTED_IMAGE_DUMP_ARGS);
                    } catch (RemoteException ex) {
                        Log.w(TAG, "dump --restricted_image of " + name + " threw", ex);
                    }
                }
            }
        }

        /**
         * Inside the binder interface class because we want to do all of the authorization
         * here, before calling out to the helper objects.
         */
        private void enforceRequestAuthorizationPermission() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.REQUEST_INCIDENT_REPORT_APPROVAL, null);
        }

        /**
         * Inside the binder interface class because we want to do all of the authorization
         * here, before calling out to the helper objects.
         */
        private void enforceAuthorizePermission() {
            getContext().enforceCallingOrSelfPermission(
                    android.Manifest.permission.APPROVE_INCIDENT_REPORTS, null);
        }

        /**
         * Enforce that the calling process either has APPROVE_INCIDENT_REPORTS or
         * (DUMP and PACKAGE_USAGE_STATS). This lets the approver get, because showing
         * information about the report is a prerequisite for letting the user decide.
         *
         * If pkg is null, it is not checked, so make sure that you check it for null first
         * if you do need the packages to match.
         *
         * Inside the binder interface class because we want to do all of the authorization
         * here, before calling out to the helper objects.
         */
        private void enforceAccessReportsPermissions(String pkg) {
            if (getContext().checkCallingPermission(
                        android.Manifest.permission.APPROVE_INCIDENT_REPORTS)
                    != PackageManager.PERMISSION_GRANTED) {
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.DUMP, null);
                getContext().enforceCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS, null);
                if (pkg != null) {
                    enforceCallerIsSameApp(pkg);
                }
            }
        }

        /**
         * Throw a SecurityException if the incoming binder call is not from pkg.
         */
        private void enforceCallerIsSameApp(String pkg) throws SecurityException {
            try {
                final int uid = Binder.getCallingUid();
                final int userId = UserHandle.getCallingUserId();
                final ApplicationInfo ai = getContext().getPackageManager()
                        .getApplicationInfoAsUser(pkg, 0, userId);
                if (ai == null) {
                    throw new SecurityException("Unknown package " + pkg);
                }
                if (!UserHandle.isSameApp(ai.uid, uid)) {
                    throw new SecurityException("Calling uid " + uid + " gave package "
                            + pkg + " which is owned by uid " + ai.uid);
                }
            } catch (PackageManager.NameNotFoundException re) {
                throw new SecurityException("Unknown package " + pkg + "\n" + re);
            }
        }
    }

    /**
     * Construct new IncidentCompanionService with the context.
     */
    public IncidentCompanionService(Context context) {
        super(context);
        mPendingReports = new PendingReports(context);
    }

    /**
     * Initialize the service.  It is still not safe to do UI until
     * onBootPhase(SystemService.PHASE_BOOT_COMPLETED).
     */
    @Override
    public void onStart() {
        publishBinderService(Context.INCIDENT_COMPANION_SERVICE, new BinderService());
    }

    /**
     * Handle the boot process... Starts everything running once the system is
     * up enough for us to do UI.
     */
    @Override
    public void onBootPhase(int phase) {
        super.onBootPhase(phase);
        switch (phase) {
            case SystemService.PHASE_BOOT_COMPLETED:
                mPendingReports.onBootCompleted();
                break;
        }
    }

    /**
     * Looks up incidentd every time, so we don't need a complex handshake between
     * incidentd and IncidentCompanionService.
     */
    private IIncidentManager getIIncidentManager() throws RemoteException {
        return IIncidentManager.Stub.asInterface(
                ServiceManager.getService(Context.INCIDENT_SERVICE));
    }

    /**
     * Check whether the current user is the primary user, and return the user id if they are.
     * Returns UserHandle.USER_NULL if not valid.
     */
    public static int getAndValidateUser(Context context) {
        // Current user
        UserInfo currentUser;
        try {
            currentUser = ActivityManager.getService().getCurrentUser();
        } catch (RemoteException ex) {
            // We're already inside the system process.
            throw new RuntimeException(ex);
        }

        // Primary user
        final UserManager um = UserManager.get(context);
        final UserInfo primaryUser = um.getPrimaryUser();

        // Check that we're using the right user.
        if (currentUser == null) {
            Log.w(TAG, "No current user.  Nobody to approve the report."
                    + " The report will be denied.");
            return UserHandle.USER_NULL;
        }
        if (primaryUser == null) {
            Log.w(TAG, "No primary user.  Nobody to approve the report."
                    + " The report will be denied.");
            return UserHandle.USER_NULL;
        }
        if (primaryUser.id != currentUser.id) {
            Log.w(TAG, "Only the primary user can approve bugreports, but they are not"
                    + " the current user. The report will be denied.");
            return UserHandle.USER_NULL;
        }

        return primaryUser.id;
    }
}

