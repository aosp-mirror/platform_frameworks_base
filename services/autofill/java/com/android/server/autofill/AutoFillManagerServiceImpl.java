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

package com.android.server.autofill;

import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.bundleToString;
import static android.service.autofill.AutoFillService.FLAG_AUTHENTICATION_ERROR;
import static android.service.autofill.AutoFillService.FLAG_AUTHENTICATION_REQUESTED;
import static android.service.autofill.AutoFillService.FLAG_AUTHENTICATION_SUCCESS;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.ActivityManagerInternal;
import android.app.IActivityManager;
import android.app.assist.AssistStructure;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.hardware.fingerprint.Fingerprint;
import android.hardware.fingerprint.IFingerprintService;
import android.hardware.fingerprint.IFingerprintServiceReceiver;
import android.os.Binder;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.autofill.AutoFillService;
import android.service.autofill.AutoFillServiceInfo;
import android.service.autofill.IAutoFillAppCallback;
import android.service.autofill.IAutoFillServerCallback;
import android.service.autofill.IAutoFillService;
import android.service.voice.VoiceInteractionSession;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.autofill.AutoFillId;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.LocalServices;

import java.io.PrintWriter;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

/**
 * Bridge between the {@code system_server}'s {@link AutoFillManagerService} and the
 * app's {@link IAutoFillService} implementation.
 *
 */
final class AutoFillManagerServiceImpl {

    private static final String TAG = "AutoFillManagerServiceImpl";

    /** Used do assign ids to new ServerCallback instances. */
    private static int sSessionIdCounter = 0;

    private final int mUserId;
    private final int mUid;
    private final ComponentName mComponent;
    private final Context mContext;
    private final IActivityManager mAm;
    private final Object mLock;
    private final AutoFillServiceInfo mInfo;
    private final AutoFillManagerService mManagerService;
    private final AutoFillUI mUi;

    // TODO(b/33197203): improve its usage
    // - set maximum number of entries
    // - disable on low-memory devices.
    private final List<String> mRequestHistory = new LinkedList<>();

    @GuardedBy("mLock")
    private final List<QueuedRequest> mQueuedRequests = new LinkedList<>();

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                final String reason = intent.getStringExtra("reason");
                if (DEBUG) Slog.d(TAG, "close system dialogs: " + reason);
                // TODO(b/33197203): close any pending UI like account selection (or remove this
                // receiver)
            }
        }
    };

    /**
     * Cache of pending {@link Session}s, keyed by {@link Session#mId}.
     *
     * <p>They're kept until the {@link AutoFillService} finished handling a request, an error
     * occurs, or the session times out.
     */
    // TODO(b/33197203): need to make sure service is bound while callback is pending and/or
    // use WeakReference
    @GuardedBy("mLock")
    private static final SparseArray<Session> mSessions = new SparseArray<>();

    private final ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            if (DEBUG) Slog.d(TAG, "onServiceConnected():" + name);
            synchronized (mLock) {
                mService = IAutoFillService.Stub.asInterface(service);
                try {
                    mService.onConnected();
                } catch (RemoteException e) {
                    Slog.w(TAG, "Exception on service.onConnected(): " + e);
                    return;
                }
                if (!mQueuedRequests.isEmpty()) {
                    if (DEBUG) Slog.d(TAG, "queued requests:" + mQueuedRequests.size());
                }
                for (final QueuedRequest request: mQueuedRequests) {
                    requestAutoFillLocked(request.activityToken, request.extras, request.flags,
                            false);
                }
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Slog.d(TAG, name + " disconnected");
            synchronized (mLock) {
                mService = null;
                mManagerService.removeCachedServiceForUserLocked(mUserId);
            }
        }
    };

    /**
     * Receiver of assist data from the app's {@link Activity}, uses the {@code resultData} as
     * the {@link Session#mId}.
     */
    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            if (DEBUG) Slog.d(TAG, "resultCode on mAssistReceiver: " + resultCode);

            final IBinder appBinder = resultData.getBinder(AutoFillService.KEY_CALLBACK);
            if (appBinder == null) {
                Slog.w(TAG, "no app callback on mAssistReceiver's resultData");
                return;
            }
            final AssistStructure structure = resultData
                    .getParcelable(VoiceInteractionSession.KEY_STRUCTURE);
            final int flags = resultData.getInt(VoiceInteractionSession.KEY_FLAGS, 0);

            final Session session;
            synchronized (mLock) {
                session = mSessions.get(resultCode);
                if (session == null) {
                    Slog.w(TAG, "no server callback for id " + resultCode);
                    return;
                }
                session.mAppCallback = IAutoFillAppCallback.Stub.asInterface(appBinder);
            }
            mService.autoFill(structure, session.mServerCallback, session.mExtras, flags);
        }
    };

    @GuardedBy("mLock")
    private IAutoFillService mService;
    @GuardedBy("mLock")
    private boolean mBound;
    @GuardedBy("mLock")
    private boolean mValid;

    // Estimated time when the service will be evicted from the cache.
    long mEstimateTimeOfDeath;

    AutoFillManagerServiceImpl(AutoFillManagerService managerService, AutoFillUI ui,
            Context context, Object lock, Handler handler, int userId, int uid,
            ComponentName component, long ttl) {
        mManagerService = managerService;
        mUi = ui;
        mContext = context;
        mLock = lock;
        mUserId = userId;
        mUid = uid;
        mComponent = component;
        mAm = ActivityManager.getService();
        setLifeExpectancy(ttl);

        final AutoFillServiceInfo info;
        try {
            info = new AutoFillServiceInfo(context.getPackageManager(), component, mUserId);
        } catch (PackageManager.NameNotFoundException e) {
            Slog.w(TAG, "Auto-fill service not found: " + component, e);
            mInfo = null;
            mValid = false;
            return;
        }
        mInfo = info;
        if (mInfo.getParseError() != null) {
            Slog.w(TAG, "Bad auto-fill service: " + mInfo.getParseError());
            mValid = false;
            return;
        }

        mValid = true;
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, handler);
    }

    void setLifeExpectancy(long ttl) {
        mEstimateTimeOfDeath = SystemClock.uptimeMillis() + ttl;
    }

    void startLocked() {
        if (DEBUG) Slog.d(TAG, "startLocked()");

        final Intent intent = new Intent(AutoFillService.SERVICE_INTERFACE);
        intent.setComponent(mComponent);
        mBound = mContext.bindServiceAsUser(intent, mConnection,
                Context.BIND_AUTO_CREATE | Context.BIND_FOREGROUND_SERVICE, new UserHandle(mUserId));

        if (!mBound) {
            Slog.w(TAG, "Failed binding to auto-fill service " + mComponent);
            return;
        }
        if (DEBUG) Slog.d(TAG, "Bound to " + mComponent);
    }

    /**
     * Asks service to auto-fill an activity.
     *
     * @param activityToken activity token
     * @param extras bundle to be passed to the {@link AutoFillService} method.
     * @param flags optional flags.
     */
    void requestAutoFill(@Nullable IBinder activityToken, @Nullable Bundle extras, int flags) {
        synchronized (mLock) {
            if (!mBound) {
                Slog.w(TAG, "requestAutoFill() failed because it's not bound to service");
                return;
            }
        }

        // TODO(b/33197203): activityToken should probably not be null, but we need to wait until
        // the UI is triggering the call (for now it's trough 'adb shell cmd autofill request'
        if (activityToken == null) {
            // Let's get top activities from all visible stacks.

            // TODO(b/33197203): overload getTopVisibleActivities() to take userId, otherwise it
            // could return activities for different users when a work profile app is displayed in
            // another window (in a multi-window environment).
            final List<IBinder> topActivities = LocalServices
                    .getService(ActivityManagerInternal.class).getTopVisibleActivities();
            if (DEBUG)
                Slog.d(TAG, "Top activities (" + topActivities.size() + "): " + topActivities);
            if (topActivities.isEmpty()) {
                Slog.w(TAG, "Could not get top activity");
                return;
            }
            activityToken = topActivities.get(0);
        }

        final String historyItem = TimeUtils.formatForLogging(System.currentTimeMillis())
                + " - " + activityToken;
        synchronized (mLock) {
            mRequestHistory.add(historyItem);
            requestAutoFillLocked(activityToken, extras, flags, true);
        }
    }

    private void requestAutoFillLocked(IBinder activityToken, @Nullable Bundle extras, int flags,
            boolean queueIfNecessary) {
        if (mService == null) {
            if (!queueIfNecessary) {
                Slog.w(TAG, "requestAutoFillLocked(): service is null");
                return;
            }
            if (DEBUG) Slog.d(TAG, "requestAutoFill(): service not set yet, queuing it");
            mQueuedRequests.add(new QueuedRequest(activityToken, extras, flags));
            return;
        }

        final int sessionId = ++sSessionIdCounter;
        final Session session = new Session(sessionId, extras);
        mSessions.put(sessionId, session);

        /*
         * TODO(b/33197203): apply security checks below:
         * - checks if disabled by secure settings / device policy
         * - log operation using noteOp()
         * - check flags
         * - display disclosure if needed
         */
        try {
            // TODO(b/33197203): add MetricsLogger call
            if (!mAm.requestAutoFillData(mAssistReceiver, null, sessionId, activityToken, flags)) {
                // TODO(b/33197203): might need a way to warn user (perhaps a new method on
                // AutoFillService).
                Slog.w(TAG, "failed to request auto-fill data for " + activityToken);
            }
        } catch (RemoteException e) {
            // Should happen, it's a local call.
        }
    }

    void stopLocked() {
        if (DEBUG) Slog.d(TAG, "stopLocked()");

        // Sanity check.
        if (mService == null) {
            Slog.w(TAG, "service already null on shutdown");
            return;
        }
        try {
            mService.onDisconnected();
        } catch (RemoteException e) {
            if (! (e instanceof DeadObjectException)) {
                Slog.w(TAG, "Exception calling service.onDisconnected(): " + e);
            }
        } finally {
            mService = null;
        }

        if (mBound) {
            mContext.unbindService(mConnection);
            mBound = false;
        }
        if (mValid) {
            mContext.unregisterReceiver(mBroadcastReceiver);
        }
    }

    /**
     * Called by {@link AutoFillUI} to fill an activity after the user selected a dataset.
     */
    void autoFillApp(int sessionId, Dataset dataset) {
        // TODO(b/33197203): add MetricsLogger call

        if (dataset == null) {
            Slog.w(TAG, "autoFillApp(): no dataset for callback id " + sessionId);
            return;
        }


        final Session session;
        synchronized (mLock) {
            session = mSessions.get(sessionId);
            if (session == null) {
                Slog.w(TAG, "autoFillApp(): no session with id " + sessionId);
                return;
            }
            if (session.mAppCallback == null) {
                Slog.w(TAG, "autoFillApp(): no app callback for session " + sessionId);
                return;
            }

            // TODO(b/33197203): use a handler?
            session.autoFill(dataset);
        }
    }

    void removeSessionLocked(int id) {
        if (DEBUG) Slog.d(TAG, "Removing session " + id);
        mSessions.remove(id);

        // TODO(b/33197203): notify mService so it can invalidate the FillCallback / SaveCallback?
    }

    /**
     * Notifies the result of a {@link FillResponse} authentication request to the service.
     *
     * <p>Typically called by the UI after user taps the "Tap to autofill" affordance, or after user
     * used the fingerprint sensors to authenticate.
     */
    void notifyResponseAuthenticationResult(Bundle extras, int flags) {
        if (DEBUG) Slog.d(TAG, "notifyResponseAuthenticationResult(): flags=" + flags
                + ", extras=" + bundleToString(extras));

        synchronized (mLock) {
            try {
                mService.authenticateFillResponse(extras, flags);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error sending authentication result back to service: " + e);
            }
        }
    }

    /**
     * Notifies the result of a {@link Dataset} authentication request to the service.
     *
     * <p>Typically called by the UI after user taps the "Tap to autofill" affordance, or after
     * it gets the results from a fingerprint authentication.
     */
    void notifyDatasetAuthenticationResult(Bundle extras, int flags) {
        if (DEBUG) Slog.d(TAG, "notifyDatasetAuthenticationResult(): flags=" + flags
                + ", extras=" + bundleToString(extras));
        synchronized (mLock) {
            try {
                mService.authenticateDataset(extras, flags);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error sending authentication result back to service: " + e);
            }
        }
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        if (!mValid) {
            pw.print("  NOT VALID: ");
            if (mInfo == null) {
                pw.println("no info");
            } else {
                pw.println(mInfo.getParseError());
            }
            return;
        }

        final String prefix2 = prefix + "  ";

        pw.print(prefix); pw.print("mUserId="); pw.println(mUserId);
        pw.print(prefix); pw.print("mUid="); pw.println(mUid);
        pw.print(prefix); pw.print("mComponent="); pw.println(mComponent.flattenToShortString());
        pw.print(prefix); pw.print("mService: "); pw.println(mService);
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
        pw.print(prefix); pw.print("mEstimateTimeOfDeath=");
            TimeUtils.formatDuration(mEstimateTimeOfDeath, SystemClock.uptimeMillis(), pw);
        pw.println();

        if (DEBUG) {
            // ServiceInfo dump is too noisy and redundant (it can be obtained through other dumps)
            pw.print(prefix); pw.println("ServiceInfo:");
            mInfo.getServiceInfo().dump(new PrintWriterPrinter(pw), prefix + prefix);
        }

        if (mRequestHistory.isEmpty()) {
            pw.print(prefix); pw.println("No history");
        } else {
            pw.print(prefix); pw.println("History:");
            for (int i = 0; i < mRequestHistory.size(); i++) {
                pw.print(prefix2); pw.print(i); pw.print(": "); pw.println(mRequestHistory.get(i));
            }
        }
        if (mQueuedRequests.isEmpty()) {
            pw.print(prefix); pw.println("No queued requests");
        } else {
            pw.print(prefix); pw.println("Queued requests:");
            for (int i = 0; i < mQueuedRequests.size(); i++) {
                pw.print(prefix2); pw.print(i); pw.print(": "); pw.println(mQueuedRequests.get(i));
            }
        }

        pw.print(prefix); pw.print("sSessionIdCounter="); pw.println(sSessionIdCounter);
        final int size = mSessions.size();
        if (size == 0) {
            pw.print(prefix); pw.println("No sessions");
        } else {
            pw.print(prefix); pw.print(size); pw.println(" sessions:");
            for (int i = 0; i < size; i++) {
                pw.print(prefix2); pw.print(mSessions.keyAt(i));
                final Session session = mSessions.valueAt(i);
                if (session.mAppCallback == null) {
                    pw.println("(no appCallback)");
                } else {
                    pw.print(" (app callback: "); pw.print(session.mAppCallback) ; pw.println(")");
                }
            }
            pw.println();
        }
    }

    @Override
    public String toString() {
        return "AutoFillManagerServiceImpl: [userId=" + mUserId + ", uid=" + mUid
                + ", component=" + mComponent.flattenToShortString() + "]";
    }

    private static final class QueuedRequest {
        final IBinder activityToken;
        final Bundle extras;
        final int flags;

        QueuedRequest(IBinder activityToken, Bundle extras, int flags) {
            this.activityToken = activityToken;
            this.extras = extras;
            this.flags = flags;
        }

        @Override
        public String toString() {
            return "flags: " + flags + " token: " + activityToken;
        }
    }

    /**
     * A bridge between the {@link AutoFillService} implementation and the activity being
     * auto-filled (represented through the {@link IAutoFillAppCallback}).
     *
     * <p>Although the auto-fill requests and callbacks are stateless from the service's point of
     * view, we need to keep state in the framework side for cases such as authentication. For
     * example, when service return a {@link FillResponse} that contains all the fields needed
     * to fill the activity but it requires authentication first, that response need to be held
     * until the user authenticates or it times out.
     */
    // TODO(b/33197203): make sure sessions are removed (and tested by CTS):
    // - On all authentication scenarios.
    // - When user does not interact back after a while.
    // - When service is unbound.
    private final class Session {

        private final int mId;
        private final Bundle mExtras;
        private IAutoFillAppCallback mAppCallback;

        // Token used on fingerprint authentication
        private final IBinder mToken = new Binder();

        private final IFingerprintService mFingerprintService;

        @GuardedBy("mLock")
        private FillResponse mResponseRequiringAuth;
        @GuardedBy("mLock")
        private Dataset mDatasetRequiringAuth;

        // Used to auto-fill the activity directly when the FillCallback.onResponse() is called as
        // the result of a successful user authentication on service's side.
        @GuardedBy("mLock")
        private boolean mAutoFillDirectly;

        // TODO(b/33197203): use handler to handle results?
        // TODO(b/33197203): handle all callback methods and/or cancelation?
        private IFingerprintServiceReceiver mServiceReceiver =
                new IFingerprintServiceReceiver.Stub() {

            @Override
            public void onEnrollResult(long deviceId, int fingerId, int groupId, int remaining) {
                if (DEBUG) Slog.d(TAG, "onEnrollResult()");
            }

            @Override
            public void onAcquired(long deviceId, int acquiredInfo, int vendorCode) {
                if (DEBUG) Slog.d(TAG, "onAcquired()");
            }

            @Override
            public void onAuthenticationSucceeded(long deviceId, Fingerprint fp, int userId) {
                if (DEBUG) Slog.d(TAG, "onAuthenticationSucceeded(): " + fp.getGroupId());

                // First, check what was authenticated, a response or a dataset.
                // Then, decide how to handle it:
                // - If service provided data, handle them directly.
                // - Otherwise, notify service.

                mAutoFillDirectly = true;

                if (mDatasetRequiringAuth != null) {
                    if (mDatasetRequiringAuth.isEmpty()) {
                        notifyDatasetAuthenticationResult(mDatasetRequiringAuth.getExtras(),
                                FLAG_AUTHENTICATION_SUCCESS);
                    } else {
                        autoFillAppLocked(mDatasetRequiringAuth, true);
                    }
                } else if (mResponseRequiringAuth != null) {
                    final List<Dataset> datasets = mResponseRequiringAuth.getDatasets();
                    if (datasets.isEmpty()) {
                        notifyResponseAuthenticationResult(mResponseRequiringAuth.getExtras(),
                                FLAG_AUTHENTICATION_SUCCESS);
                    } else {
                        showResponseLocked(mResponseRequiringAuth, true);
                    }
                } else {
                    Slog.w(TAG, "onAuthenticationSucceeded(): no response or dataset");
                }

                mUi.dismissFingerprintRequest(mUserId, true);
            }

            @Override
            public void onAuthenticationFailed(long deviceId) {
                if (DEBUG) Slog.d(TAG, "onAuthenticationFailed()");
                // Do nothing - onError() will be called after a few failures...
            }

            @Override
            public void onError(long deviceId, int error, int vendorCode) {
                if (DEBUG) Slog.d(TAG, "onError()");

                // Notify service so it can fallback to its own authentication
                if (mDatasetRequiringAuth != null) {
                    notifyDatasetAuthenticationResult(mDatasetRequiringAuth.getExtras(),
                            FLAG_AUTHENTICATION_ERROR);
                } else if (mResponseRequiringAuth != null) {
                    notifyResponseAuthenticationResult(mResponseRequiringAuth.getExtras(),
                            FLAG_AUTHENTICATION_ERROR);
                } else {
                    Slog.w(TAG, "onError(): no response or dataset");
                }

                mUi.dismissFingerprintRequest(mUserId, false);
            }

            @Override
            public void onRemoved(long deviceId, int fingerId, int groupId, int remaining) {
                if (DEBUG) Slog.d(TAG, "onRemoved()");
            }

            @Override
            public void onEnumerated(long deviceId, int fingerId, int groupId, int remaining) {
                if (DEBUG) Slog.d(TAG, "onEnumerated()");
            }
        };

        private IAutoFillServerCallback mServerCallback = new IAutoFillServerCallback.Stub() {
            @Override
            public void showResponse(FillResponse response) {
                // TODO(b/33197203): add MetricsLogger call
                if (response == null) {
                    if (DEBUG) Slog.d(TAG, "showResponse(): null response");

                    removeSelf();
                    return;
                }

                synchronized (mLock) {
                    showResponseLocked(response, response.isAuthRequired());
                }
            }

            @Override
            public void showError(CharSequence message) {
                // TODO(b/33197203): add MetricsLogger call
                if (DEBUG) Slog.d(TAG, "showError(): " + message);

                mUi.showError(message);

                removeSelf();
            }

            @Override
            public void highlightSavedFields(AutoFillId[] ids) {
                // TODO(b/33197203): add MetricsLogger call
                if (DEBUG) Slog.d(TAG, "highlightSavedFields(): " + Arrays.toString(ids));

                mUi.highlightSavedFields(ids);

                removeSelf();
            }

            @Override
            public void unlockFillResponse(int flags) {
                // TODO(b/33197203): add proper MetricsLogger calls?
                if (DEBUG) Log.d(TAG, "unlockUser(): flags=" + flags);

                synchronized (mLock) {
                    if ((flags & FLAG_AUTHENTICATION_SUCCESS) != 0) {
                        if (mResponseRequiringAuth == null) {
                            Log.wtf(TAG, "unlockUser(): no mResponseRequiringAuth on flags "
                                    + flags);
                            removeSelf();
                            return;
                        }
                        final List<Dataset> datasets = mResponseRequiringAuth.getDatasets();
                        if (datasets.isEmpty()) {
                            Log.w(TAG, "unlockUser(): no dataset on previous response: "
                                    + mResponseRequiringAuth);
                            removeSelf();
                            return;
                        }
                        mAutoFillDirectly = true;
                        showResponseLocked(mResponseRequiringAuth, false);
                    }
                    // TODO(b/33197203): show UI error on authentication failure?
                    // Or let service handle it?
                }
            }

            @Override
            public void unlockDataset(Dataset dataset, int flags) {
                // TODO(b/33197203): add proper MetricsLogger calls?
                if (DEBUG) Log.d(TAG, "unlockDataset(): dataset=" + dataset + ", flags=" + flags);

                if ((flags & FLAG_AUTHENTICATION_SUCCESS) != 0) {
                    autoFillAppLocked(dataset != null ? dataset : mDatasetRequiringAuth, true);
                    return;
                }
                removeSelf();
            }
        };

        private Session(int id, Bundle extras) {
            this.mId = id;
            this.mExtras = extras;
            this.mFingerprintService = IFingerprintService.Stub
                    .asInterface(ServiceManager.getService("fingerprint"));
        }

        private void showResponseLocked(FillResponse response, boolean authRequired) {
            if (DEBUG) Slog.d(TAG, "showResponse(directly=" + mAutoFillDirectly
                    + ", authRequired=" + authRequired +"):" + response);

            if (mAutoFillDirectly && response != null) {
                final List<Dataset> datasets = response.getDatasets();
                if (datasets.size() == 1) {
                    // User authenticated and provider returned just 1 dataset - auto-fill it now!
                    final Dataset dataset = datasets.get(0);
                    if (DEBUG) Slog.d(TAG, "auto-filling directly from auth: " + dataset);

                    autoFillAppLocked(dataset, true);
                    return;
                }
            }

            if (!authRequired) {
                // TODO(b/33197203): add MetricsLogger call
                mUi.showOptions(mUserId, mId, response);
                return;
            }

            // Handles response that requires authentication.
            // TODO(b/33197203): add MetricsLogger call, including if fingerprint requested

            mResponseRequiringAuth = response;
            final boolean requiresFingerprint = response.hasCryptoObject();
            if (requiresFingerprint) {
                // TODO(b/33197203): check if fingerprint is available first and call error callback
                // with FLAG_FINGERPRINT_AUTHENTICATION_NOT_AVAILABLE if it's not.
                // Start scanning for the fingerprint.
                scanFingerprint(response.getCryptoObjectOpId());
            }
            // Displays the message asking the user to tap (or fingerprint) for AutoFill.
            mUi.showFillResponseAuthenticationRequest(mUserId, mId, requiresFingerprint,
                    response.getExtras(), response.getFlags());
        }

        void autoFill(Dataset dataset) {
            synchronized (mLock) {
                // Autofill it directly...
                if (!dataset.isAuthRequired()) {
                    autoFillAppLocked(dataset, true);
                    return;
                }

                // ...or handle authentication.

                mDatasetRequiringAuth = dataset;
                final boolean requiresFingerprint = dataset.hasCryptoObject();
                if (requiresFingerprint) {
                    // TODO(b/33197203): check if fingerprint is available first and call error callback
                    // with FLAG_FINGERPRINT_AUTHENTICATION_NOT_AVAILABLE if it's not.
                    // Start scanning for the fingerprint.
                    scanFingerprint(dataset.getCryptoObjectOpId());
                    // Displays the message asking the user to tap (or fingerprint) for AutoFill.
                    mUi.showDatasetFingerprintAuthenticationRequest(dataset);
                } else {
                    try {
                        mService.authenticateDataset(dataset.getExtras(),
                                FLAG_AUTHENTICATION_REQUESTED);
                    } catch (RemoteException e) {
                        Slog.w(TAG, "Error authenticating dataset: " + e);
                    }
                }
            }
        }

        private void autoFillAppLocked(Dataset dataset, boolean removeSelf) {
            try {
                if (DEBUG) Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                mAppCallback.autoFill(dataset);

                // TODO(b/33197203): temporarily hack: show the save notification, since save is
                // not integrated with IME yet.
                mUi.showSaveNotification(mUserId, null, dataset);

            } catch (RemoteException e) {
                Slog.w(TAG, "Error auto-filling activity: " + e);
            }
            if (removeSelf) {
                removeSelf();
            }
        }

        private void scanFingerprint(long opId) {
            // TODO(b/33197203): add MetricsLogger call
            if (DEBUG) Slog.d(TAG, "Starting fingerprint scan for op id: " + opId);

            // TODO(b/33197203): since we're clearing the AutoFillService's identity, make sure
            // this method is only called at the proper times, otherwise a malicious provider could
            // keep the callback refence to bypass the check
            final long token = Binder.clearCallingIdentity();
            try {
                // TODO(b/33197203): set a timeout?
                mFingerprintService.authenticate(mToken, opId, mUserId, mServiceReceiver, 0, null);
            } catch (RemoteException e) {
                // Local call, shouldn't happen.
            } finally {
                Binder.restoreCallingIdentity(token);
            }
        }

        private void removeSelf() {
            synchronized (mLock) {
                removeSessionLocked(mId);
            }
        }
    }
}
