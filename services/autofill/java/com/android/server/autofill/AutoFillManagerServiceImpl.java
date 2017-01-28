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

import static android.service.autofill.AutoFillService.FLAG_AUTHENTICATION_ERROR;
import static android.service.autofill.AutoFillService.FLAG_AUTHENTICATION_REQUESTED;
import static android.service.autofill.AutoFillService.FLAG_AUTHENTICATION_SUCCESS;
import static android.view.View.AUTO_FILL_FLAG_TYPE_FILL;
import static android.view.View.AUTO_FILL_FLAG_TYPE_SAVE;
import static android.view.autofill.AutoFillManager.FLAG_UPDATE_UI_SHOW;
import static android.view.autofill.AutoFillManager.FLAG_UPDATE_UI_HIDE;

import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.bundleToString;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.assist.AssistStructure;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.graphics.Rect;
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
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

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
    private final String mComponentName;
    private final Context mContext;
    private final IActivityManager mAm;
    private final Object mLock;
    private final AutoFillServiceInfo mInfo;
    private final AutoFillManagerService mManagerService;

    // Token used for fingerprint authentication
    // TODO(b/33197203): create on demand?
    private final IBinder mAuthToken = new Binder();

    private final IFingerprintService mFingerprintService =
            IFingerprintService.Stub.asInterface(ServiceManager.getService("fingerprint"));

    @GuardedBy("mLock")
    private final List<QueuedRequest> mQueuedRequests = new LinkedList<>();

    private final LocalLog mRequestsHistory;

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
                    requestAutoFillLocked(request.activityToken, request.autoFillId,
                            request.bounds, request.flags, false);
                }
                mQueuedRequests.clear();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            if (DEBUG) Slog.d(TAG, name + " disconnected");
            synchronized (mLock) {
                mService = null;
                mManagerService.removeCachedServiceLocked(mUserId);
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
                session.setAppCallback(appBinder);
            }
            mService.autoFill(structure, session.mServerCallback, flags);
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

    AutoFillManagerServiceImpl(AutoFillManagerService managerService, Context context, Object lock,
            LocalLog requestsHistory, Handler handler, int userId, int uid, ComponentName component,
            long ttl) {
        mManagerService = managerService;
        mContext = context;
        mLock = lock;
        mRequestsHistory = requestsHistory;
        mUserId = userId;
        mUid = uid;
        mComponent = component;
        mComponentName = mComponent.flattenToShortString();
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
     * @param activityToken activity token.
     * @param autoFillId id of the view that requested auto-fill.
     * @param flags optional flags.
     */
    void requestAutoFillLocked(IBinder activityToken, @Nullable AutoFillId autoFillId,
            @Nullable Rect bounds, int flags) {
        if (!mBound) {
            Slog.w(TAG, "requestAutoFill() failed because it's not bound to service");
            return;
        }

        requestAutoFillLocked(activityToken, autoFillId, bounds, flags, true);
    }

    private void requestAutoFillLocked(IBinder activityToken, @Nullable AutoFillId autoFillId,
          @Nullable Rect bounds, int flags, boolean queueIfNecessary) {
        if (mService == null) {
            if (!queueIfNecessary) {
                Slog.w(TAG, "requestAutoFillLocked(): service is null");
                return;
            }
            if (DEBUG) Slog.d(TAG, "requestAutoFillLocked(): service not set yet, queuing it");
            mQueuedRequests.add(new QueuedRequest(activityToken, autoFillId, bounds, flags));
            return;
        }
        if (activityToken == null) {
            // Sanity check
            Slog.wtf(TAG, "requestAutoFillLocked(): null activityToken");
            return;
        }

        final String historyItem = "s=" + mComponentName + " u=" + mUserId + " f=" + flags
                + " a=" + activityToken + " i=" + autoFillId + " b=" + bounds;
        mRequestsHistory.log(historyItem);

        // TODO(b/33197203): Handle partitioning
        Session session = getOrCreateSessionByTokenLocked(activityToken);
        if (DEBUG) Slog.d(TAG, "using Session: " + session.mId);

        session.updateAutoFillInput(flags, autoFillId, null, bounds);
    }

    private Session getOrCreateSessionByTokenLocked(IBinder activityToken) {
        final int size = mSessions.size();
        for (int i = 0; i < size; i++) {
            final Session session = mSessions.valueAt(i);
            if (activityToken.equals(session.mActivityToken.get())) {
                return session;
            }
        }
        return createSessionByTokenLocked(activityToken);
    }

    private Session createSessionByTokenLocked(IBinder activityToken) {
        final int sessionId = ++sSessionIdCounter;
        if (DEBUG) Slog.d(TAG, "creating Session: " + sessionId);

        final Session newSession = new Session(sessionId, activityToken);
        mSessions.put(sessionId, newSession);

        /*
         * TODO(b/33197203): apply security checks below:
         * - checks if disabled by secure settings / device policy
         * - log operation using noteOp()
         * - check flags
         * - display disclosure if needed
         */
        try {
            // TODO(b/33197203): add MetricsLogger call
            if (!mAm.requestAutoFillData(
                    mAssistReceiver, null, sessionId, activityToken, AUTO_FILL_FLAG_TYPE_FILL)) {
                // TODO(b/33197203): might need a way to warn user (perhaps a new method on
                // AutoFillService).
                Slog.w(TAG, "failed to request auto-fill data for " + activityToken);
            }
        } catch (RemoteException e) {
            // Should not happen, it's a local call.
        }
        return newSession;
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

    void removeSessionLocked(int id) {
        if (DEBUG) Slog.d(TAG, "Removing session " + id);
        mSessions.remove(id);

        // TODO(b/33197203): notify mService so it can invalidate the FillCallback / SaveCallback?
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
        pw.print(prefix); pw.print("mComponent="); pw.println(mComponentName);
        pw.print(prefix); pw.print("mService: "); pw.println(mService);
        pw.print(prefix); pw.print("mBound="); pw.println(mBound);
        pw.print(prefix); pw.print("mEstimateTimeOfDeath=");
            TimeUtils.formatDuration(mEstimateTimeOfDeath, SystemClock.uptimeMillis(), pw);
            pw.println();
        pw.print(prefix); pw.print("mAuthToken: "); pw.println(mAuthToken);

        if (DEBUG) {
            // ServiceInfo dump is too noisy and redundant (it can be obtained through other dumps)
            pw.print(prefix); pw.println("ServiceInfo:");
            mInfo.getServiceInfo().dump(new PrintWriterPrinter(pw), prefix + prefix);
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
                pw.print(prefix); pw.print("#"); pw.println(i + 1);
                mSessions.valueAt(i).dumpLocked(prefix2, pw);
            }
        }
    }

    @Override
    public String toString() {
        return "AutoFillManagerServiceImpl: [userId=" + mUserId + ", uid=" + mUid
                + ", component=" + mComponentName + "]";
    }

    private static final class QueuedRequest {
        final IBinder activityToken;
        final AutoFillId autoFillId;
        final Rect bounds;
        final int flags;

        QueuedRequest(IBinder activityToken, AutoFillId autoFillId, Rect bounds, int flags) {
            this.activityToken = activityToken;
            this.autoFillId = autoFillId;
            this.bounds = bounds;
            this.flags = flags;
        }

        @Override
        public String toString() {
            if (!DEBUG) return super.toString();

            return "QueuedRequest: [flags=" + flags + ", token=" + activityToken
                    + ", id=" + autoFillId + ", bounds=" + bounds;
        }
    }

    /**
     * State for a given view with a AutoFillId.
     *
     * <p>This class holds state about a view and calls its listener when the fill UI is ready to
     * be displayed for the view.
     */
    static final class ViewState {
        interface Listener {
            /**
             * Called when the fill UI is ready to be shown for this view.
             */
            void onFillReady(ViewState viewState, FillResponse fillResponse, Rect bounds,
                    @Nullable AutoFillValue value);
        }

        private final Listener mListener;
        @Nullable
        private FillResponse mResponse;
        private AutoFillValue mAutoFillValue;
        private Rect mBounds;

        ViewState(Listener listener) {
            mListener = listener;
        }

        /**
         * Response should only be set once.
         */
        void setResponse(FillResponse response) {
            if (mResponse != null) {
                Slog.e(TAG, "ViewState response set more than once");
                return;
            }
            mResponse = response;

            maybeCallOnFillReady();
        }

        void update(@Nullable AutoFillValue autoFillValue, @Nullable Rect bounds) {
            if (autoFillValue != null) {
                mAutoFillValue = autoFillValue;
            }
            if (bounds != null) {
                mBounds = bounds;
            }

            maybeCallOnFillReady();
        }

        /**
         * Calls {@link Listener#onFillReady(ViewState, FillResponse, Rect, AutoFillValue)} if the
         * fill UI is ready to be displayed (i.e. when response and bounds are set).
         */
        void maybeCallOnFillReady() {
            if (mResponse != null && mBounds != null) {
                mListener.onFillReady(this, mResponse, mBounds, mAutoFillValue);
            }
        }

        @Override
        public String toString() {
            if (!DEBUG) return super.toString();

            return "ViewState: [response=" + mResponse + ", value=" + mAutoFillValue
                    + ", bounds=" + mBounds + "]";
        }
    }

    /**
     * A session for a given activity.
     *
     * <p>This class manages the multiple {@link ViewState}s for each view it has, and keeps track
     * of the current view session to display the appropriate UI.
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
    final class Session implements ViewState.Listener {

        private final AutoFillUI mUi;
        final int mId;
        private final WeakReference<IBinder> mActivityToken;

        @GuardedBy("mLock")
        private final Map<AutoFillId, ViewState> mViewStates = new ArrayMap<>();
        @GuardedBy("mLock")
        @Nullable
        private ViewState mCurrentViewState;

        private IAutoFillAppCallback mAppCallback;

        // TODO(b/33197203): Get a response per view instead of per activity.
        @GuardedBy("mLock")
        private FillResponse mCurrentResponse;
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
                        autoFillApp(mDatasetRequiringAuth);
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

                mUi.dismissFingerprintRequest(true);
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

                mUi.dismissFingerprintRequest(false);
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
                    autoFillApp(dataset != null ? dataset : mDatasetRequiringAuth);
                    return;
                }
            }
        };

        private Session(int id, IBinder activityToken) {
            mUi = new AutoFillUI(mContext, this);
            mId = id;
            mActivityToken = new WeakReference<>(activityToken);
        }

        void setAppCallback(IBinder appBinder) {
            try {
                appBinder.linkToDeath(() -> {
                    if (DEBUG) Slog.d(TAG, "app callback died");
                    // TODO(b/33197203): more cleanup here?
                    mAppCallback = null;
                }, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "linkToDeath() failed: " + e);
            }
            mAppCallback = IAutoFillAppCallback.Stub.asInterface(appBinder);
        }

        void updateAutoFillInput(int flags, AutoFillId autoFillId,
                @Nullable AutoFillValue autoFillValue, @Nullable Rect bounds) {
            synchronized (mLock) {
                ViewState viewState = mViewStates.get(autoFillId);
                if (viewState == null) {
                    viewState = new ViewState(this);
                    mViewStates.put(autoFillId, viewState);
                }

                if ((flags & FLAG_UPDATE_UI_SHOW) != 0) {
                    // Remove the UI if the ViewState has changed.
                    if (mCurrentViewState != viewState) {
                        mUi.hideFillUi();
                        mCurrentViewState = viewState;
                    }

                    // If the ViewState is ready to be displayed, onReady() will be called.
                    viewState.update(autoFillValue, bounds);

                    // TODO(b/33197203): Remove when there is a response per activity.
                    if (mCurrentResponse != null) {
                        viewState.setResponse(mCurrentResponse);
                    }
                } else if ((flags & FLAG_UPDATE_UI_HIDE) != 0) {
                    if (mCurrentViewState == viewState) {
                        mUi.hideFillUi();
                        mCurrentViewState = null;
                    }
                } else {
                    Slog.w(TAG, "unknown flags " + flags);
                }
            }
        }

        @Override
        public void onFillReady(ViewState viewState, FillResponse response, Rect bounds,
                @Nullable AutoFillValue value) {
            String filterText = "";
            if (value != null) {
                // TODO(b/33197203): Handle other AutoFillValue types
                final CharSequence text = value.getTextValue();
                if (text != null) {
                    filterText = text.toString();
                }
            }
            mUi.showFillUi(viewState, response.getDatasets(), bounds, filterText);
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

                    autoFillApp(dataset);
                    return;
                }
            }

            if (!authRequired) {
                // TODO(b/33197203): add MetricsLogger call
                mCurrentResponse = response;
                // TODO(b/33197203): Consider using mCurrentResponse, depends on partitioning design
                if (mCurrentViewState != null) {
                    mCurrentViewState.setResponse(mCurrentResponse);
                }
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
            mUi.showFillResponseAuthenticationRequest(requiresFingerprint,
                    response.getExtras(), response.getFlags());
        }

        void autoFill(Dataset dataset) {
            synchronized (mLock) {
                // Autofill it directly...
                if (!dataset.isAuthRequired()) {
                    autoFillApp(dataset);
                    return;
                }

                // ...or handle authentication.

                mDatasetRequiringAuth = dataset;
                final boolean requiresFingerprint = dataset.hasCryptoObject();
                if (requiresFingerprint) {
                    // TODO(b/33197203): check if fingerprint is available first and call error
                    // callback with FLAG_FINGERPRINT_AUTHENTICATION_NOT_AVAILABLE if it's not.
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

        void dumpLocked(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("mId: "); pw.println(mId);
            pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken.get());
            pw.print(prefix); pw.print("mCurrentResponse: "); pw.println(mCurrentResponse);
            pw.print(prefix);
                pw.print("mResponseRequiringAuth: "); pw.println(mResponseRequiringAuth);
            pw.print(prefix);
                pw.print("mDatasetRequiringAuth: "); pw.println(mDatasetRequiringAuth);
            pw.print(prefix); pw.print("mAutoFillDirectly: "); pw.println(mAutoFillDirectly);
            pw.print(prefix); pw.print("mCurrentViewStates: "); pw.println(mCurrentViewState);
            pw.print(prefix); pw.print("mViewStates: "); pw.println(mViewStates.size());
            final String prefix2 = prefix + "  ";
            for (Map.Entry<AutoFillId, ViewState> entry : mViewStates.entrySet()) {
                pw.print(prefix2);
                pw.print(entry.getKey()); pw.print(": " ); pw.println(entry.getValue());
            }
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

        void autoFillApp(Dataset dataset) {
            synchronized (mLock) {
                try {
                    if (DEBUG) Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                    mAppCallback.autoFill(dataset);
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error auto-filling activity: " + e);
                }
            }
        }

        void requestSave() {
            synchronized (mLock) {
                requestSaveLocked(mId);
            }
        }

        /**
         * Called by UI to trigger a save request to the service.
         */
        void requestSaveLocked(int sessionId) {
            // TODO(b/33197203): add MetricsLogger call
            // TODO(b/33197203): use handler?
            // TODO(b/33197203): show error on UI on Slog.w situations below???

            if (mService == null) {
                Slog.w(TAG, "requestSave(): service is null");
                return;
            }
            final Session session = mSessions.get(sessionId);
            if (session == null) {
                Slog.w(TAG, "requestSave(): no session with id " + sessionId);
                return;
            }
            final IBinder activityToken = session.mActivityToken.get();
            if (activityToken == null) {
                Slog.w(TAG, "activity token for session " + sessionId + " already GCed");
                return;
            }

            /*
             * TODO(b/33197203): apply security checks below:
             * - checks if disabled by secure settings / device policy
             * - log operation using noteOp()
             * - check flags
             * - display disclosure if needed
             */
            try {
                /* TODO(b/33197203): refactor save logic so it uses a cached AssistStructure, and
                   get the extras to be sent to the service based on the response / dataset in the
                   session. */
                if (!mAm.requestAutoFillData(mAssistReceiver, null, sessionId, activityToken,
                    AUTO_FILL_FLAG_TYPE_SAVE)) {
                    Slog.w(TAG, "failed to save for " + activityToken);
                }
            } catch (RemoteException e) {
                // Should not happen, it's a local call.
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
                mFingerprintService.authenticate(mAuthToken, opId, mUserId, mServiceReceiver, 0,
                        null);
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
