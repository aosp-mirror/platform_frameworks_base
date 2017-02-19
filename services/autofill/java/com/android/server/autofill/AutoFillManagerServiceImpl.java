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

import static android.service.autofill.AutoFillService.EXTRA_ACTIVITY_TOKEN;
import static android.service.voice.VoiceInteractionSession.KEY_RECEIVER_EXTRAS;
import static android.service.voice.VoiceInteractionSession.KEY_STRUCTURE;
import static android.view.autofill.AutoFillManager.FLAG_FOCUS_GAINED;
import static android.view.autofill.AutoFillManager.FLAG_FOCUS_LOST;
import static android.view.autofill.AutoFillManager.FLAG_START_SESSION;
import static android.view.autofill.AutoFillManager.FLAG_VALUE_CHANGED;

import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.VERBOSE;
import static com.android.server.autofill.Helper.findValue;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.AppGlobals;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.app.assist.AssistStructure.WindowNode;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Rect;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteCallbackList;
import android.os.RemoteException;
import android.provider.Settings;
import android.service.autofill.AutoFillService;
import android.service.autofill.AutoFillServiceInfo;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.IAutoFillService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillManager;
import android.view.autofill.AutoFillValue;

import android.view.autofill.IAutoFillManagerClient;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Bridge between the {@code system_server}'s {@link AutoFillManagerService} and the
 * app's {@link IAutoFillService} implementation.
 *
 */
final class AutoFillManagerServiceImpl {

    private static final String TAG = "AutoFillManagerServiceImpl";

    private static final int MSG_SERVICE_SAVE = 1;

    private final int mUserId;
    private final Context mContext;
    private final Object mLock;
    private final AutoFillUI mUi;

    private RemoteCallbackList<IAutoFillManagerClient> mClients;
    private AutoFillServiceInfo mInfo;

    private final LocalLog mRequestsHistory;

    private final HandlerCaller.Callback mHandlerCallback = (msg) -> {
        switch (msg.what) {
            case MSG_SERVICE_SAVE:
                handleSessionSave((IBinder) msg.obj);
                break;
            default:
                Slog.d(TAG, "invalid msg: " + msg);
        }
    };

    private final HandlerCaller mHandlerCaller = new HandlerCaller(null, Looper.getMainLooper(),
            mHandlerCallback, true);

    /**
     * Cache of pending {@link Session}s, keyed by {@code activityToken}.
     *
     * <p>They're kept until the {@link AutoFillService} finished handling a request, an error
     * occurs, or the session times out.
     */
    // TODO(b/33197203): need to make sure service is bound while callback is pending and/or
    // use WeakReference
    @GuardedBy("mLock")
    private final ArrayMap<IBinder, Session> mSessions = new ArrayMap<>();

    /**
     * Receiver of assist data from the app's {@link Activity}.
     */
    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            if (DEBUG) Slog.d(TAG, "resultCode on mAssistReceiver: " + resultCode);

            final AssistStructure structure = resultData.getParcelable(KEY_STRUCTURE);
            if (structure == null) {
                Slog.w(TAG, "no assist structure for id " + resultCode);
                return;
            }

            final Bundle receiverExtras = resultData.getBundle(KEY_RECEIVER_EXTRAS);
            if (receiverExtras == null) {
                // Should not happen
                Slog.wtf(TAG, "No " + KEY_RECEIVER_EXTRAS + " on receiver");
                return;
            }

            final IBinder activityToken = receiverExtras.getBinder(EXTRA_ACTIVITY_TOKEN);
            final Session session;
            synchronized (mLock) {
                session = mSessions.get(activityToken);
                if (session == null) {
                    Slog.w(TAG, "no server session for activityToken " + activityToken);
                    return;
                }
                // TODO(b/33197203): since service is fetching the data (to use for save later),
                // we should optimize what's sent (for example, remove layout containers,
                // color / font info, etc...)
                session.mStructure = structure;
            }


            // TODO(b/33197203, b/33269702): Must fetch the data so it's available later on
            // handleSave(), even if if the activity is gone by then, but structure.ensureData()
            // gives a ONE_WAY warning because system_service could block on app calls.
            // We need to change AssistStructure so it provides a "one-way" writeToParcel()
            // method that sends all the data
            structure.ensureData();

            // Sanitize structure before it's sent to service.
            structure.sanitizeForParceling(true);

            // TODO(b/33197203): Need to pipe the bundle
            session.mRemoteFillService.onFillRequest(structure, null);
        }
    };

    AutoFillManagerServiceImpl(Context context, Object lock, LocalLog requestsHistory,
            int userId, AutoFillUI ui) {
        mContext = context;
        mLock = lock;
        mRequestsHistory = requestsHistory;
        mUserId = userId;
        mUi = ui;
        updateLocked();
    }

    CharSequence getServiceName() {
        if (mInfo == null) {
            return null;
        }
        final ComponentName serviceComponent = mInfo.getServiceInfo().getComponentName();
        final String packageName = serviceComponent.getPackageName();

        try {
            final PackageManager pm = mContext.getPackageManager();
            final ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            return pm.getApplicationLabel(info);
        } catch (Exception e) {
            Slog.w(TAG, "Could not get label for " + packageName + ": " + e);
            return packageName;
        }
    }

    void updateLocked() {
        ComponentName serviceComponent = null;
        ServiceInfo serviceInfo = null;
        final String componentName = Settings.Secure.getStringForUser(
                mContext.getContentResolver(), Settings.Secure.AUTO_FILL_SERVICE, mUserId);
        if (!TextUtils.isEmpty(componentName)) {
            try {
                serviceComponent = ComponentName.unflattenFromString(componentName);
                serviceInfo = AppGlobals.getPackageManager().getServiceInfo(serviceComponent,
                        0, mUserId);
            } catch (RuntimeException | RemoteException e) {
                Slog.e(TAG, "Bad auto-fill service name " + componentName, e);
                return;
            }
        }
        try {
            final boolean hadService = hasService();
            if (serviceInfo != null) {
                mInfo = new AutoFillServiceInfo(mContext.getPackageManager(),
                        serviceComponent, mUserId);
            } else {
                mInfo = null;
            }
            if (hadService != hasService()) {
                if (!hasService()) {
                    final int sessionCount = mSessions.size();
                    for (int i = sessionCount - 1; i >= 0; i--) {
                        Session session = mSessions.valueAt(i);
                        session.destroyLocked();
                        mSessions.removeAt(i);
                    }
                }
                sendStateToClients();
            }
        } catch (PackageManager.NameNotFoundException e) {
            Slog.e(TAG, "Bad auto-fill service name " + componentName, e);
        }
    }

    /**
     * Used by {@link AutoFillManagerServiceShellCommand} to request save for the current top app.
     */
    void requestSaveForUserLocked(IBinder activityToken) {
        if (!hasService()) {
            return;
        }
        final Session session = mSessions.get(activityToken);
        if (session == null) {
            Slog.w(TAG, "requestSaveForUserLocked(): no session for " + activityToken);
            return;
        }

        session.callSaveLocked();
    }

    boolean addClientLocked(IAutoFillManagerClient client) {
        if (mClients == null) {
            mClients = new RemoteCallbackList<>();
        }
        mClients.register(client);
        return hasService();
    }

    void setAuthenticationResultLocked(Bundle data, IBinder activityToken) {
        if (!hasService()) {
            return;
        }
        final Session session = mSessions.get(activityToken);
        if (session != null) {
            session.setAuthenticationResultLocked(data);
        }
    }

    void startSessionLocked(IBinder activityToken, IBinder appCallbackToken, AutoFillId autoFillId,
            Rect bounds, AutoFillValue value) {
        if (!hasService()) {
            return;
        }

        final String historyItem = "s=" + new ComponentName(mInfo.getServiceInfo().packageName,
                mInfo.getServiceInfo().name) + " u=" + mUserId + " a=" + activityToken
                + " i=" + autoFillId + " b=" + bounds + " v=" + value;
        mRequestsHistory.log(historyItem);

        // TODO(b/33197203): Handle partitioning
        final Session session = mSessions.get(activityToken);
        if (session != null) {
            // Already started...
            return;
        }

        final Session newSession = createSessionByTokenLocked(activityToken, appCallbackToken);
        newSession.updateLocked(autoFillId, bounds, value, FLAG_START_SESSION);
    }

    void finishSessionLocked(IBinder activityToken) {
        if (!hasService()) {
            return;
        }

        final Session session = mSessions.get(activityToken);
        if (session == null) {
            Slog.w(TAG, "finishSessionLocked(): no session for " + activityToken);
            return;
        }

        session.showSaveLocked();
    }

    private Session createSessionByTokenLocked(IBinder activityToken, IBinder appCallbackToken) {
        final Session newSession = new Session(mContext, activityToken, appCallbackToken);
        mSessions.put(activityToken, newSession);

        /*
         * TODO(b/33197203): apply security checks below:
         * - checks if disabled by secure settings / device policy
         * - log operation using noteOp()
         * - check flags
         * - display disclosure if needed
         */
        try {
            // TODO(b/33197203): add MetricsLogger call
            final Bundle receiverExtras = new Bundle();
            receiverExtras.putBinder(EXTRA_ACTIVITY_TOKEN, activityToken);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (!ActivityManager.getService().requestAutoFillData(mAssistReceiver,
                        receiverExtras, activityToken)) {
                    // TODO(b/33197203): might need a way to warn user (perhaps a new method on
                    // AutoFillService).
                    Slog.w(TAG, "failed to request auto-fill data for " + activityToken);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (RemoteException e) {
            // Should not happen, it's a local call.
        }
        return newSession;
    }

    void updateSessionLocked(IBinder activityToken, AutoFillId autoFillId, Rect bounds,
            AutoFillValue value, int flags) {
        // TODO(b/33197203): add MetricsLogger call
        final Session session = mSessions.get(activityToken);
        if (session == null) {
            Slog.w(TAG, "updateSessionLocked(): session gone for " + activityToken);
            return;
        }

        session.updateLocked(autoFillId, bounds, value, flags);
    }

    private void handleSessionSave(IBinder activityToken) {
        synchronized (mLock) {
            final Session session = mSessions.get(activityToken);
            if (session == null) {
                Slog.w(TAG, "handleSessionSave(): already gone: " + activityToken);

                return;
            }
            session.callSaveLocked();
        }
    }

    void destroyLocked() {
        if (VERBOSE) Slog.v(TAG, "destroyLocked()");

        for (Session session : mSessions.values()) {
            session.destroyLocked();
        }
        mSessions.clear();
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";

        pw.print(prefix); pw.println("Component:"); pw.println(mInfo != null
                ? mInfo.getServiceInfo().getComponentName() : null);

        if (VERBOSE) {
            // ServiceInfo dump is too noisy and redundant (it can be obtained through other dumps)
            pw.print(prefix); pw.println("ServiceInfo:");
            mInfo.getServiceInfo().dump(new PrintWriterPrinter(pw), prefix + prefix);
        }

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

    void listSessionsLocked(ArrayList<String> output) {
        for (IBinder activityToken : mSessions.keySet()) {
            output.add((mInfo != null ? mInfo.getServiceInfo().getComponentName()
                    : null) + ":" + activityToken);
        }
    }

    private void sendStateToClients() {
        final RemoteCallbackList<IAutoFillManagerClient> clients;
        final int userClientCount;
        synchronized (mLock) {
            if (mClients == null) {
                return;
            }
            clients = mClients;
            userClientCount = clients.beginBroadcast();
        }
        try {
            for (int i = 0; i < userClientCount; i++) {
                IAutoFillManagerClient client = clients.getBroadcastItem(i);
                try {
                    client.setState(hasService());
                } catch (RemoteException re) {
                    /* ignore */
                }
            }
        } finally {
            clients.finishBroadcast();
        }
    }

    private boolean hasService() {
        return mInfo != null;
    }

    @Override
    public String toString() {
        return "AutoFillManagerServiceImpl: [userId=" + mUserId
                + ", component=" + (mInfo != null
                ? mInfo.getServiceInfo().getComponentName() : null) + "]";
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
                    AutoFillId focusedId, @Nullable AutoFillValue value);
        }

        final AutoFillId mId;
        private final Listener mListener;
        // TODO(b/33197203): would not need a reference to response and session if it was an inner
        // class of Session...
        private final Session mSession;
        // TODO(b/33197203): encapsulate access so it's not called by UI
        FillResponse mResponse;
        Intent mAuthIntent;

        private AutoFillValue mAutoFillValue;
        private Rect mBounds;

        private boolean mValueUpdated;

        ViewState(Session session, AutoFillId id, Listener listener) {
            mSession = session;
            mId = id;
            mListener = listener;
        }

        /**
         * Response should only be set once.
         */
        void setResponse(FillResponse response) {
            mResponse = response;
            maybeCallOnFillReady();
        }

        /**
         * Used when a {@link FillResponse} requires authentication to be unlocked.
         */
        void setResponse(FillResponse response, Intent authIntent) {
            mAuthIntent = authIntent;
            setResponse(response);
        }

        CharSequence getServiceName() {
            return mSession.getServiceName();
        }

        // TODO(b/33197203): need to refactor / rename / document this method to make it clear that
        // it can change  the value and update the UI; similarly, should replace code that
        // directly sets mAutoFilLValue to use encapsulation.
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
         * Calls {@link
         * Listener#onFillReady(ViewState, FillResponse, Rect, AutoFillId, AutoFillValue)} if the
         * fill UI is ready to be displayed (i.e. when response and bounds are set).
         */
        void maybeCallOnFillReady() {
            if (mResponse != null && (mResponse.getAuthentication() != null
                    || mResponse.getDatasets() != null) && mBounds != null) {
                mListener.onFillReady(this, mResponse, mBounds, mId, mAutoFillValue);
            }
        }

        @Override
        public String toString() {
            if (!DEBUG) return super.toString();

            return "ViewState: [id=" + mId + ", value=" + mAutoFillValue + ", bounds=" + mBounds
                    + ", updated = " + mValueUpdated + "]";
        }

        void dump(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("id:" ); pw.println(mId);
            pw.print(prefix); pw.print("value:" ); pw.println(mAutoFillValue);
            pw.print(prefix); pw.print("updated:" ); pw.println(mValueUpdated);
            pw.print(prefix); pw.print("bounds:" ); pw.println(mBounds);
            pw.print(prefix); pw.print("authIntent:" ); pw.println(mAuthIntent);
        }
    }

    /**
     * A session for a given activity.
     *
     * <p>This class manages the multiple {@link ViewState}s for each view it has, and keeps track
     * of the current {@link ViewState} to display the appropriate UI.
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
    final class Session implements RemoteFillService.FillServiceCallbacks, ViewState.Listener,
            AutoFillUI.AutoFillUiCallback {
        private final IBinder mActivityToken;

        @GuardedBy("mLock")
        private final Map<AutoFillId, ViewState> mViewStates = new ArrayMap<>();

        @GuardedBy("mLock")
        @Nullable
        private ViewState mCurrentViewState;

        private final IAutoFillManagerClient mClient;

        @GuardedBy("mLock")
        RemoteFillService mRemoteFillService;

        // TODO(b/33197203): Get a response per view instead of per activity.
        @GuardedBy("mLock")
        private FillResponse mCurrentResponse;

        /**
         * Used to remember which {@link Dataset} filled the session.
         */
        // TODO(b/33197203): might need more than one once we support partitions
        @GuardedBy("mLock")
        private Dataset mAutoFilledDataset;

        /**
         * Assist structure sent by the app; it will be updated (sanitized, change values for save)
         * before sent to {@link AutoFillService}.
         */
        @GuardedBy("mLock")
        private AssistStructure mStructure;

        private Session(Context context, IBinder activityToken, IBinder client) {
            mRemoteFillService = new RemoteFillService(context,
                    mInfo.getServiceInfo().getComponentName(), mUserId, this);
            mActivityToken = activityToken;

            mClient = IAutoFillManagerClient.Stub.asInterface(client);
            try {
                client.linkToDeath(() -> {
                    if (DEBUG) Slog.d(TAG, "app binder died");

                    removeSelf();
                }, 0);
            } catch (RemoteException e) {
                Slog.w(TAG, "linkToDeath() on mClient failed: " + e);
            }
        }

        // FillServiceCallbacks
        @Override
        public void onFillRequestSuccess(FillResponse response) {
            // TODO(b/33197203): add MetricsLogger call
            if (response == null) {
                removeSelf();
                return;
            }
            synchronized (mLock) {
                processResponseLocked(response);
            }
        }

        // FillServiceCallbacks
        @Override
        public void onFillRequestFailure(CharSequence message) {
            // TODO(b/33197203): add MetricsLogger call
            getUiForShowing().showError(message);
            removeSelf();
        }

        // FillServiceCallbacks
        @Override
        public void onSaveRequestSuccess() {
            // TODO(b/33197203): add MetricsLogger call
            // Nothing left to do...
            removeSelf();
        }

        // FillServiceCallbacks
        @Override
        public void onSaveRequestFailure(CharSequence message) {
            // TODO(b/33197203): add MetricsLogger call
            getUiForShowing().showError(message);
            removeSelf();
        }

        // FillServiceCallbacks
        @Override
        public void authenticate(IntentSender intent) {
            final Intent fillInIntent;
            synchronized (mLock) {
                fillInIntent = createAuthFillInIntent(mStructure);
            }
            mHandlerCaller.getHandler().post(() -> {
                startAuthentication(intent, fillInIntent);
            });
        }

        // FillServiceCallbacks
        @Override
        public void onServiceDied(RemoteFillService service) {
            // TODO(b/33197203): implement
        }

        // AutoFillUiCallback
        @Override
        public void fill(Dataset dataset) {
            autoFill(dataset);
        }

        // AutoFillUiCallback
        @Override
        public void save() {
            mHandlerCaller.getHandler().obtainMessage(MSG_SERVICE_SAVE, mActivityToken)
                    .sendToTarget();
        }

        public void setAuthenticationResultLocked(Bundle data) {
            if (mCurrentResponse == null || data == null) {
                removeSelf();
            } else {
                Parcelable result = data.getParcelable(
                        AutoFillManager.EXTRA_AUTHENTICATION_RESULT);
                if (result instanceof FillResponse) {
                    mCurrentResponse = (FillResponse) result;
                    processResponseLocked(mCurrentResponse);
                } else if (result instanceof Dataset) {
                    Dataset dataset = (Dataset) result;
                    mCurrentResponse.getDatasets().remove(mAutoFilledDataset);
                    mCurrentResponse.getDatasets().add(dataset);
                    mAutoFilledDataset = dataset;
                    processResponseLocked(mCurrentResponse);
                }
            }
        }

        /**
         * Show the save UI, when session can be saved.
         */
        public void showSaveLocked() {
            if (mStructure == null) {
                // Sanity check; should not happen...
                Slog.wtf(TAG, "showSaveLocked(): no mStructure");
                return;
            }
            if (mCurrentResponse == null) {
                // Happens when the activity / session was finished before the service replied.
                Slog.d(TAG, "showSaveLocked(): no mCurrentResponse yet");
                return;
            }
            final ArraySet<AutoFillId> savableIds = mCurrentResponse.getSavableIds();
            if (VERBOSE) Slog.v(TAG, "showSaveLocked(): savableIds=" + savableIds);

            if (savableIds.isEmpty()) {
                if (DEBUG) Slog.d(TAG, "showSaveLocked(): service doesn't want to save");
                return;
            }

            final int size = savableIds.size();
            for (int i = 0; i < size; i++) {
                final AutoFillId id = savableIds.valueAt(i);
                final ViewState state = mViewStates.get(id);
                if (state != null && state.mValueUpdated) {
                    final AutoFillValue filledValue = findValue(mAutoFilledDataset, id);
                    if (state.mAutoFillValue == null || state.mAutoFillValue.equals(filledValue)) {
                        continue;
                    }
                    if (DEBUG) {
                        Slog.d(TAG, "finishSessionLocked(): found a change on " + id + ": "
                                + state.mAutoFillValue);
                    }
                    mUi.showSaveUi();
                    return;
                }
            }

            // Nothing changed...
            if (DEBUG) Slog.d(TAG, "showSaveLocked(): with no changes, comes no responsibilities");
        }

        /**
         * Calls service when user requested save.
         */
        private void callSaveLocked() {
            if (DEBUG) Slog.d(TAG, "callSaveLocked(): mViewStates=" + mViewStates);

            final Bundle extras = this.mCurrentResponse.getExtras();

            for (Entry<AutoFillId, ViewState> entry : mViewStates.entrySet()) {
                final AutoFillValue value = entry.getValue().mAutoFillValue;
                if (value == null) {
                    if (VERBOSE) Slog.v(TAG, "callSaveLocked(): skipping " + entry.getKey());
                    continue;
                }
                final AutoFillId id = entry.getKey();
                final ViewNode node = findViewNodeByIdLocked(id);
                if (node == null) {
                    Slog.w(TAG, "callSaveLocked(): did not find node with id " + id);
                    continue;
                }
                if (DEBUG) Slog.d(TAG, "callSaveLocked(): updating " + id + " to " + value);

                node.updateAutoFillValue(value);
            }

            mStructure.sanitizeForParceling(false);

            if (VERBOSE) {
                Slog.v(TAG, "Dumping " + mStructure + " before calling service.save()");
                mStructure.dump();
            }

            mRemoteFillService.onSaveRequest(mStructure, extras);
        }

        void updateLocked(AutoFillId id, Rect bounds, AutoFillValue value, int flags) {
            if (DEBUG) Slog.d(TAG, "updateLocked(): id=" + id + ", flags=" + flags);

            if (mAutoFilledDataset != null && (flags & FLAG_VALUE_CHANGED) == 0) {
                // TODO(b/33197203): ignoring because we don't support partitions yet
                if (DEBUG) Slog.d(TAG, "updateLocked(): ignoring " + flags + " after auto-filled");
                return;
            }

            ViewState viewState = mViewStates.get(id);
            if (viewState == null) {
                viewState = new ViewState(this, id, this);
                mViewStates.put(id, viewState);
            }

            if ((flags & FLAG_START_SESSION) != 0) {
                // View is triggering auto-fill.
                mCurrentViewState = viewState;
                viewState.update(value, bounds);
                return;
            }

            if ((flags & FLAG_VALUE_CHANGED) != 0 && value != null &&
                    !value.equals(viewState.mAutoFillValue)) {
                viewState.mValueUpdated = true;

                // Must check if this update was caused by auto-filling the view, in which
                // case we just update the value, but not the UI.
                if (mAutoFilledDataset != null) {
                    final AutoFillValue filledValue = findValue(mAutoFilledDataset, id);
                    if (value.equals(filledValue)) {
                        viewState.mAutoFillValue = value;
                        return;
                    }
                }

                // Change value
                viewState.mAutoFillValue = value;

                // Update the chooser UI
                mUi.updateFillUi(value.coerceToString());
                return;
            }

            if ((flags & FLAG_FOCUS_GAINED) != 0) {
                // Remove the UI if the ViewState has changed.
                if (mCurrentViewState != viewState) {
                    mUi.hideFillUi();
                    mCurrentViewState = viewState;
                }

                // If the ViewState is ready to be displayed, onReady() will be called.
                viewState.update(value, bounds);

                // TODO(b/33197203): Remove when there is a response per activity.
                if (mCurrentResponse != null) {
                    viewState.setResponse(mCurrentResponse);
                }

                return;
            }

            if ((flags & FLAG_FOCUS_LOST) != 0) {
                if (mCurrentViewState == viewState) {
                    mUi.hideFillUi();
                    mCurrentViewState = null;
                }
                return;
            }

            Slog.w(TAG, "unknown flags " + flags);
        }

        @Override
        public void onFillReady(ViewState viewState, FillResponse response, Rect bounds,
                AutoFillId filledId, @Nullable AutoFillValue value) {
            String filterText = "";
            if (value != null) {
                // TODO(b/33197203): Handle other AutoFillValue types
                final CharSequence text = value.getTextValue();
                if (text != null) {
                    filterText = text.toString();
                }
            }

            getUiForShowing().showFillUi(filledId, response, bounds, filterText);
        }

        private void processResponseLocked(FillResponse response) {
            if (DEBUG) Slog.d(TAG, "processResponseLocked(authRequired="
                    + response.getAuthentication() + "):" + response);

            // TODO(b/33197203): add MetricsLogger calls

            if (mCurrentViewState == null) {
                // TODO(b/33197203): temporary sanity check; should never happen
                Slog.w(TAG, "processResponseLocked(): mCurrentResponse is null");
                return;
            }

            mCurrentResponse = response;

            if (mCurrentResponse.getAuthentication() != null) {
                // Handle authentication.
                final Intent fillInIntent = createAuthFillInIntent(mStructure);
                mCurrentViewState.setResponse(mCurrentResponse, fillInIntent);
                return;
            }

            mCurrentViewState.setResponse(mCurrentResponse);
        }

        void autoFill(Dataset dataset) {
            synchronized (mLock) {
                mAutoFilledDataset = dataset;

                // Autofill it directly...
                if (dataset.getAuthentication() == null) {
                    autoFillApp(dataset);
                    return;
                }

                // ...or handle authentication.
                Intent fillInIntent = createAuthFillInIntent(mStructure);
                startAuthentication(dataset.getAuthentication(), fillInIntent);
            }
        }

        CharSequence getServiceName() {
            return AutoFillManagerServiceImpl.this.getServiceName();
        }

        private Intent createAuthFillInIntent(AssistStructure structure) {
            Intent fillInIntent = new Intent();
            fillInIntent.putExtra(AutoFillManager.EXTRA_ASSIST_STRUCTURE, structure);
            return fillInIntent;
        }

        private void startAuthentication(IntentSender intent, Intent fillInIntent) {
            try {
                mClient.authenticate(intent, fillInIntent);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error launching auth intent", e);
            }
        }

        void dumpLocked(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken);
            pw.print(prefix); pw.print("mCurrentResponse: "); pw.println(mCurrentResponse);
            pw.print(prefix); pw.print("mAutoFilledDataset: "); pw.println(mAutoFilledDataset);
            pw.print(prefix); pw.print("mCurrentViewStates: "); pw.println(mCurrentViewState);
            pw.print(prefix); pw.print("mViewStates: "); pw.println(mViewStates.size());
            final String prefix2 = prefix + "  ";
            for (Map.Entry<AutoFillId, ViewState> entry : mViewStates.entrySet()) {
                pw.print(prefix); pw.print("State for id "); pw.println(entry.getKey());
                entry.getValue().dump(prefix2, pw);
            }
            if (VERBOSE) {
                pw.print(prefix); pw.print("mStructure: " );
                // TODO(b/33197203): add method do dump AssistStructure on pw
                if (mStructure != null) {
                    pw.println("look at logcat" );
                    mStructure.dump(); // dumps to logcat
                } else {
                    pw.println("null");
                }
            }

            mRemoteFillService.dump(prefix, pw);
        }

        void autoFillApp(Dataset dataset) {
            synchronized (mLock) {
                try {
                    if (DEBUG) Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                    mClient.autoFill(dataset.getFieldIds(), dataset.getFieldValues());
                } catch (RemoteException e) {
                    Slog.w(TAG, "Error auto-filling activity: " + e);
                }
            }
        }

        private AutoFillUI getUiForShowing() {
            synchronized (mLock) {
                mUi.setCallbackLocked(this, mActivityToken);
                return mUi;
            }
        }

        private ViewNode findViewNodeByIdLocked(AutoFillId id) {
            final int size = mStructure.getWindowNodeCount();
            for (int i = 0; i < size; i++) {
                final WindowNode window = mStructure.getWindowNodeAt(i);
                final ViewNode root = window.getRootViewNode();
                if (id.equals(root.getAutoFillId())) {
                    return root;
                }
                final ViewNode child = findViewNodeByIdLocked(root, id);
                if (child != null) {
                    return child;
                }
            }
            return null;
        }

        private ViewNode findViewNodeByIdLocked(ViewNode parent, AutoFillId id) {
            final int childrenSize = parent.getChildCount();
            if (childrenSize > 0) {
                for (int i = 0; i < childrenSize; i++) {
                    final ViewNode child = parent.getChildAt(i);
                    if (id.equals(child.getAutoFillId())) {
                        return child;
                    }
                    final ViewNode grandChild = findViewNodeByIdLocked(child, id);
                    if (grandChild != null && id.equals(grandChild.getAutoFillId())) {
                        return grandChild;
                    }
                }
            }
            return null;
        }

        private void destroyLocked() {
            mRemoteFillService.destroy();
            mUi.setCallbackLocked(null, null);
        }

        private void removeSelf() {
            if (VERBOSE) Slog.v(TAG, "removeSelf()");

            synchronized (mLock) {
                destroyLocked();
                mSessions.remove(mActivityToken);
            }
        }
    }
}
