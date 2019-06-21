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

package com.android.server.incident;

import android.app.AppOpsManager;
import android.app.BroadcastOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Handler;
import android.os.IIncidentAuthListener;
import android.os.IncidentManager;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

// TODO: User changes should deny everything that's pending.

/**
 * Tracker for reports pending approval.
 */
class PendingReports {
    static final String TAG = IncidentCompanionService.TAG;

    private final Handler mHandler = new Handler();
    private final RequestQueue mRequestQueue = new RequestQueue(mHandler);
    private final Context mContext;
    private final PackageManager mPackageManager;
    private final AppOpsManager mAppOpsManager;

    //
    // All fields below must be protected by mLock
    //
    private final Object mLock = new Object();
    private final ArrayList<PendingReportRec> mPending = new ArrayList();

    /**
     * The next ID we'll use when we make a PendingReportRec.
     */
    private int mNextPendingId = 1;

    /**
     * One for each authorization that's pending.
     */
    private final class PendingReportRec {
        public int id;
        public String callingPackage;
        public int flags;
        public IIncidentAuthListener listener;
        public long addedRealtime;
        public long addedWalltime;
        public String receiverClass;
        public String reportId;

        /**
         * Construct a PendingReportRec, with an auto-incremented id.
         */
        PendingReportRec(String callingPackage, String receiverClass, String reportId, int flags,
                IIncidentAuthListener listener) {
            this.id = mNextPendingId++;
            this.callingPackage = callingPackage;
            this.flags = flags;
            this.listener = listener;
            this.addedRealtime = SystemClock.elapsedRealtime();
            this.addedWalltime = System.currentTimeMillis();
            this.receiverClass = receiverClass;
            this.reportId = reportId;
        }

        /**
         * Get the Uri that contains the flattened data.
         */
        Uri getUri() {
            final Uri.Builder builder = (new Uri.Builder())
                    .scheme(IncidentManager.URI_SCHEME)
                    .authority(IncidentManager.URI_AUTHORITY)
                    .path(IncidentManager.URI_PATH)
                    .appendQueryParameter(IncidentManager.URI_PARAM_ID, Integer.toString(id))
                    .appendQueryParameter(IncidentManager.URI_PARAM_CALLING_PACKAGE, callingPackage)
                    .appendQueryParameter(IncidentManager.URI_PARAM_FLAGS, Integer.toString(flags))
                    .appendQueryParameter(IncidentManager.URI_PARAM_TIMESTAMP,
                            Long.toString(addedWalltime));
            if (receiverClass != null && receiverClass.length() > 0) {
                builder.appendQueryParameter(IncidentManager.URI_PARAM_RECEIVER_CLASS,
                        receiverClass);
            }
            if (reportId != null && reportId.length() > 0) {
                builder.appendQueryParameter(IncidentManager.URI_PARAM_REPORT_ID, reportId);
            }
            return builder.build();
        }
    }

    /**
     * Construct new PendingReports with the context.
     */
    PendingReports(Context context) {
        mContext = context;
        mPackageManager = context.getPackageManager();
        mAppOpsManager = context.getSystemService(AppOpsManager.class);
    }

    /**
     * ONEWAY binder call to initiate authorizing the report.  The actual logic is posted
     * to mRequestQueue, and may happen later.
     * <p>
     * The security checks are handled by IncidentCompanionService.
     */
    public void authorizeReport(int callingUid, final String callingPackage,
            final String receiverClass, final String reportId, final int flags,
            final IIncidentAuthListener listener) {
        // Starting the system server is complicated, and rather than try to
        // have a complicated lifecycle that we share with dumpstated and incidentd,
        // we will accept the request, and then display it whenever it becomes possible to.
        mRequestQueue.enqueue(listener.asBinder(), true, () -> {
            authorizeReportImpl(callingUid, callingPackage, receiverClass, reportId,
                    flags, listener);
        });
    }

    /**
     * ONEWAY binder call to cancel the inbound authorization request.
     * <p>
     * This is a oneway call, and so is authorizeReport, so the
     * caller's ordering is preserved.  The other calls on this object are synchronous, so
     * their ordering is not guaranteed with respect to these calls.  So the implementation
     * sends out extra broadcasts to allow for eventual consistency.
     * <p>
     * The security checks are handled by IncidentCompanionService.
     */
    public void cancelAuthorization(final IIncidentAuthListener listener) {
        mRequestQueue.enqueue(listener.asBinder(), false, () -> {
            cancelReportImpl(listener);
        });
    }

    /**
     * SYNCHRONOUS binder call to get the list of reports that are pending confirmation
     * by the user.
     * <p>
     * The security checks are handled by IncidentCompanionService.
     */
    public List<String> getPendingReports() {
        synchronized (mLock) {
            final int size = mPending.size();
            final ArrayList<String> result = new ArrayList(size);
            for (int i = 0; i < size; i++) {
                result.add(mPending.get(i).getUri().toString());
            }
            return result;
        }
    }

    /**
     * SYNCHRONOUS binder call to mark a report as approved.
     * <p>
     * The security checks are handled by IncidentCompanionService.
     */
    public void approveReport(String uri) {
        final PendingReportRec rec;
        synchronized (mLock) {
            rec = findAndRemovePendingReportRecLocked(uri);
            if (rec == null) {
                Log.e(TAG, "confirmApproved: Couldn't find record for uri: " + uri);
                return;
            }
        }

        // Re-do the broadcast, so whoever is listening knows the list changed,
        // in case another one was added in the meantime.
        sendBroadcast();

        Log.i(TAG, "Approved report: " + uri);
        try {
            rec.listener.onReportApproved();
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed calling back for approval for: " + uri, ex);
        }
    }

    /**
     * SYNCHRONOUS binder call to mark a report as NOT approved.
     */
    public void denyReport(String uri) {
        final PendingReportRec rec;
        synchronized (mLock) {
            rec = findAndRemovePendingReportRecLocked(uri);
            if (rec == null) {
                Log.e(TAG, "confirmDenied: Couldn't find record for uri: " + uri);
                return;
            }
        }

        // Re-do the broadcast, so whoever is listening knows the list changed,
        // in case another one was added in the meantime.
        sendBroadcast();

        Log.i(TAG, "Denied report: " + uri);
        try {
            rec.listener.onReportDenied();
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed calling back for denial for: " + uri, ex);
        }
    }

    /**
     * Implementation of adb shell dumpsys debugreportcompanion.
     */
    protected void dump(FileDescriptor fd, final PrintWriter writer, String[] args) {
        if (args.length == 0) {
            // Standard text dumpsys
            final SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            synchronized (mLock) {
                final int size = mPending.size();
                writer.println("mPending: (" + size + ")");
                for (int i = 0; i < size; i++) {
                    final PendingReportRec entry = mPending.get(i);
                    writer.println(String.format("  %11d %s: %s", entry.addedRealtime,
                                df.format(new Date(entry.addedWalltime)),
                                entry.getUri().toString()));
                }
            }
        }
    }

    /**
     * Handle the boot process... Starts everything running once the system is
     * up enough for us to do UI.
     */
    public void onBootCompleted() {
        // Release the enqueued work.
        mRequestQueue.start();
    }

    /**
     * Start the confirmation process.
     */
    private void authorizeReportImpl(int callingUid, final String callingPackage,
            final String receiverClass, final String reportId,
            int flags, final IIncidentAuthListener listener) {
        // Enforce that the calling package pertains to the callingUid.
        if (callingUid != 0 && !isPackageInUid(callingUid, callingPackage)) {
            Log.w(TAG, "Calling uid " + callingUid + " doesn't match package "
                    + callingPackage);
            denyReportBeforeAddingRec(listener, callingPackage);
            return;
        }

        // Find the primary user of this device.
        final int primaryUser = getAndValidateUser();
        if (primaryUser == UserHandle.USER_NULL) {
            denyReportBeforeAddingRec(listener, callingPackage);
            return;
        }

        // Find the approver app (hint: it's PermissionController).
        final ComponentName receiver = getApproverComponent(primaryUser);
        if (receiver == null) {
            // We couldn't find an approver... so deny the request here and now, before we
            // do anything else.
            denyReportBeforeAddingRec(listener, callingPackage);
            return;
        }

        // Save the record for when the PermissionController comes back to authorize it.
        PendingReportRec rec = null;
        synchronized (mLock) {
            rec = new PendingReportRec(callingPackage, receiverClass, reportId, flags, listener);
            mPending.add(rec);
        }

        try {
            listener.asBinder().linkToDeath(() -> {
                Log.i(TAG, "Got death notification listener=" + listener);
                cancelReportImpl(listener, receiver, primaryUser);
            }, 0);
        } catch (RemoteException ex) {
            Log.e(TAG, "Remote died while trying to register death listener: " + rec.getUri());
            // First, remove from our list.
            cancelReportImpl(listener, receiver, primaryUser);
        }

        // Go tell Permission controller to start asking the user.
        sendBroadcast(receiver, primaryUser);
    }

    /**
     * Cancel a pending report request (because of an explicit call to cancel)
     */
    private void cancelReportImpl(IIncidentAuthListener listener) {
        final int primaryUser = getAndValidateUser();
        final ComponentName receiver = getApproverComponent(primaryUser);
        if (primaryUser != UserHandle.USER_NULL && receiver != null) {
            cancelReportImpl(listener, receiver, primaryUser);
        }
    }

    /**
     * Cancel a pending report request (either because of an explicit call to cancel
     * by the calling app, or because of a binder death).
     */
    private void cancelReportImpl(IIncidentAuthListener listener, ComponentName receiver,
            int primaryUser) {
        // First, remove from our list.
        synchronized (mLock) {
            removePendingReportRecLocked(listener);
        }
        // Second, call back to PermissionController to say it's canceled.
        sendBroadcast(receiver, primaryUser);
    }

    /**
     * Send an extra copy of the broadcast, to tell them that the list has changed
     * because of an addition or removal.  This function is less aggressive than
     * authorizeReportImpl in logging about failures, because this is for use in
     * cleanup cases to keep the apps' list in sync with ours.
     */
    private void sendBroadcast() {
        final int primaryUser = getAndValidateUser();
        if (primaryUser == UserHandle.USER_NULL) {
            return;
        }
        final ComponentName receiver = getApproverComponent(primaryUser);
        if (receiver == null) {
            return;
        }
        sendBroadcast(receiver, primaryUser);
    }

    /**
     * Send the confirmation broadcast.
     */
    private void sendBroadcast(ComponentName receiver, int primaryUser) {
        final Intent intent = new Intent(Intent.ACTION_PENDING_INCIDENT_REPORTS_CHANGED);
        intent.setComponent(receiver);
        final BroadcastOptions options = BroadcastOptions.makeBasic();
        options.setBackgroundActivityStartsAllowed(true);

        // Send it to the primary user.
        mContext.sendBroadcastAsUser(intent, UserHandle.getUserHandleForUid(primaryUser),
                android.Manifest.permission.APPROVE_INCIDENT_REPORTS, options.toBundle());
    }

    /**
     * Remove a PendingReportRec keyed by uri, and return it.
     */
    private PendingReportRec findAndRemovePendingReportRecLocked(String uriString) {
        final Uri uri = Uri.parse(uriString);
        final int id;
        try {
            final String idStr = uri.getQueryParameter(IncidentManager.URI_PARAM_ID);
            id = Integer.parseInt(idStr);
        } catch (NumberFormatException ex) {
            Log.w(TAG, "Can't parse id from: " + uriString);
            return null;
        }

        for (Iterator<PendingReportRec> i = mPending.iterator(); i.hasNext();) {
            final PendingReportRec rec = i.next();
            if (rec.id == id) {
                i.remove();
                return rec;
            }
        }
        return null;
    }

    /**
     * Remove a PendingReportRec keyed by listener.
     */
    private void removePendingReportRecLocked(IIncidentAuthListener listener) {

        for (Iterator<PendingReportRec> i = mPending.iterator(); i.hasNext();) {
            final PendingReportRec rec = i.next();
            if (rec.listener.asBinder() == listener.asBinder()) {
                Log.i(TAG, "  ...Removed PendingReportRec index=" + i + ": " + rec.getUri());
                i.remove();
            }
        }
    }

    /**
     * Just call listener.deny() (wrapping the RemoteException), without try to
     * add it to the list.
     */
    private void denyReportBeforeAddingRec(IIncidentAuthListener listener, String pkg) {
        try {
            listener.onReportDenied();
        } catch (RemoteException ex) {
            Log.w(TAG, "Failed calling back for denial for " + pkg, ex);
        }
    }

    /**
     * Check whether the current user is the primary user, and return the user id if they are.
     * Returns UserHandle.USER_NULL if not valid.
     */
    private int getAndValidateUser() {
        return IncidentCompanionService.getAndValidateUser(mContext);
    }

    /**
     * Return the ComponentName of the BroadcastReceiver that will approve reports.
     * The system must have zero or one of these installed.  We only look on the
     * system partition.  When the broadcast happens, the component will also need
     * have the APPROVE_INCIDENT_REPORTS permission.
     */
    private ComponentName getApproverComponent(int userId) {
        // Find the one true BroadcastReceiver
        final Intent intent = new Intent(Intent.ACTION_PENDING_INCIDENT_REPORTS_CHANGED);
        final List<ResolveInfo> matches = mPackageManager.queryBroadcastReceiversAsUser(intent,
                PackageManager.MATCH_SYSTEM_ONLY | PackageManager.MATCH_DIRECT_BOOT_AWARE
                | PackageManager.MATCH_DIRECT_BOOT_UNAWARE, userId);
        if (matches.size() == 1) {
            return matches.get(0).getComponentInfo().getComponentName();
        } else {
            Log.w(TAG, "Didn't find exactly one BroadcastReceiver to handle "
                    + Intent.ACTION_PENDING_INCIDENT_REPORTS_CHANGED
                    + ". The report will be denied. size="
                    + matches.size() + ": matches=" + matches);
            return null;
        }
    }

    /**
     * Return whether the package is one of the packages installed for the uid.
     */
    private boolean isPackageInUid(int uid, String packageName) {
        try {
            mAppOpsManager.checkPackage(uid, packageName);
            return true;
        } catch (SecurityException ex) {
            return false;
        }
    }
}

