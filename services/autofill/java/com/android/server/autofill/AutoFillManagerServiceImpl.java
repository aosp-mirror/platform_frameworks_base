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

import static com.android.server.autofill.AutoFillManagerService.DEBUG;

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
import android.icu.text.DateFormat;
import android.os.Bundle;
import android.os.DeadObjectException;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.SystemClock;
import android.os.UserHandle;
import android.service.autofill.AutoFillService;
import android.service.autofill.AutoFillServiceInfo;
import android.service.autofill.IAutoFillAppCallback;
import android.service.autofill.IAutoFillServerCallback;
import android.service.autofill.IAutoFillService;
import android.service.voice.VoiceInteractionSession;
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
import java.util.Date;
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
    private static int sServerCallbackCounter = 0;

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
     * Cache of pending ServerCallbacks, keyed by {@link ServerCallback#id}.
     *
     * <p>They're kept until the AutoFillService handles a request, or an error occurs.
     */
    // TODO(b/33197203): need to make sure service is bound while callback is pending
    @GuardedBy("mLock")
    private static final SparseArray<ServerCallback> mServerCallbacks = new SparseArray<>();

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
     * the {@link ServerCallback#id}.
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

            final ServerCallback serverCallback;
            synchronized (mLock) {
                serverCallback = mServerCallbacks.get(resultCode);
                if (serverCallback == null) {
                    Slog.w(TAG, "no server callback for id " + resultCode);
                    return;
                }
                serverCallback.appCallback = IAutoFillAppCallback.Stub.asInterface(appBinder);
            }
            mService.autoFill(structure, serverCallback, serverCallback.extras, flags);
        }
    };

    @GuardedBy("mLock")
    private IAutoFillService mService;
    private boolean mBound;
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

        final String historyItem =
                DateFormat.getDateTimeInstance().format(new Date()) + " - " + activityToken;
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

        final int callbackId = ++sServerCallbackCounter;
        final ServerCallback serverCallback = new ServerCallback(callbackId, extras);
        mServerCallbacks.put(callbackId, serverCallback);

        /*
         * TODO(b/33197203): apply security checks below:
         * - checks if disabled by secure settings / device policy
         * - log operation using noteOp()
         * - check flags
         * - display disclosure if needed
         */
        try {
            // TODO(b/33197203): add MetricsLogger call
            if (!mAm.requestAutoFillData(mAssistReceiver, null, callbackId, activityToken, flags)) {
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
    void autoFillApp(int callbackId, Dataset dataset) {
        // TODO(b/33197203): add MetricsLogger call

        if (dataset == null) {
            Slog.w(TAG, "autoFillApp(): no dataset for callback id " + callbackId);
            return;
        }

        final ServerCallback serverCallback;
        synchronized (mLock) {
            serverCallback = mServerCallbacks.get(callbackId);
            if (serverCallback == null) {
                Slog.w(TAG, "autoFillApp(): no server callback with id " + callbackId);
                return;
            }
            if (serverCallback.appCallback == null) {
                Slog.w(TAG, "autoFillApp(): no app callback for server callback " + callbackId);
                return;
            }
            // TODO(b/33197203): use a handler?
            try {
                if (DEBUG) Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                serverCallback.appCallback.autoFill(dataset);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error auto-filling activity: " + e);
            }
            removeServerCallbackLocked(callbackId);
        }
    }

    void removeServerCallbackLocked(int id) {
        if (DEBUG) Slog.d(TAG, "Removing " + id + " from server callbacks");
        mServerCallbacks.remove(id);
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
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
        pw.print(prefix); pw.print("mService="); pw.println(mService);
        pw.print(prefix); pw.print("mEstimateTimeOfDeath=");
            TimeUtils.formatDuration(mEstimateTimeOfDeath, SystemClock.uptimeMillis(), pw);
        pw.println();

        if (DEBUG) {
            // ServiceInfo dump is too noisy and redundant (it can be obtained through other dumps)
            pw.print(prefix); pw.println("Service info:");
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

        pw.print(prefix); pw.print("sServerCallbackCounter="); pw.println(sServerCallbackCounter);
        final int size = mServerCallbacks.size();
        if (size == 0) {
            pw.print(prefix); pw.println("No server callbacks");
        } else {
            pw.print(prefix); pw.print(size); pw.println(" server callbacks:");
            for (int i = 0; i < size; i++) {
                pw.print(prefix2); pw.print(mServerCallbacks.keyAt(i));
                final ServerCallback callback = mServerCallbacks.valueAt(i);
                if (callback.appCallback == null) {
                    pw.println("(no appCallback)");
                } else {
                    pw.print(" (app callback: "); pw.print(callback.appCallback) ; pw.println(")");
                }
            }
            pw.println();
        }
    }

    @Override
    public String toString() {
        return "[AutoFillManagerServiceImpl: userId=" + mUserId + ", uid=" + mUid
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
     */
    private final class ServerCallback extends IAutoFillServerCallback.Stub {

        private final int id;
        private final Bundle extras;
        private IAutoFillAppCallback appCallback;

        private ServerCallback(int id, Bundle extras) {
            this.id = id;
            this.extras = extras;
        }

        @Override
        public void showResponse(FillResponse response) {
            // TODO(b/33197203): add MetricsLogger call
            if (DEBUG) Slog.d(TAG, "showResponse(): " + response);

            mUi.showOptions(mUserId, id, response);
        }

        @Override
        public void showError(String message) {
            // TODO(b/33197203): add MetricsLogger call
            if (DEBUG) Slog.d(TAG, "showError(): " + message);

            mUi.showError(message);

            removeSelf();
        }

        @Override
        public void highlightSavedFields(AutoFillId[] ids) {
            // TODO(b/33197203): add MetricsLogger call
            if (DEBUG) Slog.d(TAG, "showSaved(): " + Arrays.toString(ids));

            mUi.highlightSavedFields(ids);

            removeSelf();
        }

        private void removeSelf() {
            synchronized (mLock) {
                removeServerCallbackLocked(id);
            }
        }
    }
}
