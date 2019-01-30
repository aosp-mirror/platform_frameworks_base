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

import android.content.Context;
import android.os.Binder;
import android.os.IIncidentAuthListener;
import android.os.IIncidentCompanion;

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

    /**
     * Tracker for reports pending approval.
     */
    private PendingReports mPendingReports;

    /**
     * Implementation of the IIncidentCompanion binder interface.
     */
    private final class BinderService extends IIncidentCompanion.Stub {
        /**
         * ONEWAY binder call to initiate authorizing the report.
         */
        @Override
        public void authorizeReport(int callingUid, final String callingPackage, int flags,
                final IIncidentAuthListener listener) {
            enforceRequestAuthorizationPermission();

            final long ident = Binder.clearCallingIdentity();
            try {
                mPendingReports.authorizeReport(callingUid, callingPackage, flags, listener);
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
         * Implementation of adb shell dumpsys debugreportcompanion.
         */
        @Override
        protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
            if (!DumpUtils.checkDumpPermission(getContext(), TAG, writer)) {
                return;
            }
            mPendingReports.dump(fd, writer, args);
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
}

