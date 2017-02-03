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

import static android.view.autofill.AutoFillManager.FLAG_UPDATE_UI_SHOW;
import static android.view.autofill.AutoFillManager.FLAG_UPDATE_UI_HIDE;

import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.VERBOSE;

import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.IActivityManager;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.ViewNode;
import android.app.assist.AssistStructure.WindowNode;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.IBinder;
import android.os.ICancellationSignal;
import android.os.RemoteException;
import android.service.autofill.AutoFillService;
import android.service.autofill.AutoFillServiceInfo;
import android.service.autofill.FillCallback;
import android.service.autofill.IAutoFillAppCallback;
import android.service.autofill.IAutoFillService;
import android.service.autofill.IFillCallback;
import android.service.voice.VoiceInteractionSession;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.PrintWriterPrinter;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutoFillId;
import android.view.autofill.AutoFillValue;
import android.view.autofill.Dataset;
import android.view.autofill.FillResponse;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.server.FgThread;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;

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
    private final ComponentName mComponent;
    private final String mComponentName;
    private final Context mContext;
    private final IActivityManager mAm;
    private final Object mLock;
    private final AutoFillServiceInfo mInfo;
    private final AutoFillUI mUi;

    private final LocalLog mRequestsHistory;

    private final BroadcastReceiver mBroadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (Intent.ACTION_CLOSE_SYSTEM_DIALOGS.equals(intent.getAction())) {
                final String reason = intent.getStringExtra("reason");
                if (DEBUG) Slog.d(TAG, "close system dialogs: " + reason);
                mUi.hideAll();
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
    private final SparseArray<Session> mSessions = new SparseArray<>();

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
            if (structure == null) {
                Slog.w(TAG, "no assist structure for id " + resultCode);
                return;
            }

            final Session session;
            synchronized (mLock) {
                session = mSessions.get(resultCode);
                if (session == null) {
                    Slog.w(TAG, "no server callback for id " + resultCode);
                    return;
                }
            }

            // TODO(b/33197203): since service is fetching the data (to use for save later),
            // we should optimize what's sent (for example, remove layout containers,
            // color / font info, etc...)

            // TODO(b/33197203, b/33269702): Must fetch the data so it's available later on
            // handleSave(), even if if the activity is gone by then, but structure.ensureData()
            // gives a ONE_WAY warning because system_service could block on app calls.
            // We need to change AssistStructure so it provides a "one-way" writeToParcel()
            // method that sends all the data
            structure.ensureData();

            structure.sanitizeForParceling(true);

            if (VERBOSE) {
                Slog.v(TAG, "Dumping " + structure + " before calling service.autoFill()");
                structure.dump();
            }

            session.onApplicationDataAvailable(structure, appBinder);
        }
    };

    AutoFillManagerServiceImpl(Context context, Object lock, LocalLog requestsHistory,
            int userId, ComponentName component, AutoFillUI ui)
            throws PackageManager.NameNotFoundException {
        mContext = context;
        mLock = lock;
        mRequestsHistory = requestsHistory;
        mUserId = userId;
        mComponent = component;
        mComponentName = mComponent.flattenToShortString();
        mAm = ActivityManager.getService();
        mUi = ui;
        mInfo = new AutoFillServiceInfo(context.getPackageManager(), component, mUserId);

        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_CLOSE_SYSTEM_DIALOGS);
        mContext.registerReceiver(mBroadcastReceiver, filter, null, FgThread.getHandler());
    }


    /**
     * Used by {@link AutoFillManagerServiceShellCommand} to request save for the current top app.
     */
    void requestSaveForUserLocked(IBinder activityToken) {
        final Session session = getOrCreateSessionByTokenLocked(activityToken);
        session.onSaveLocked();
    }

    /**
     * Asks service to auto-fill an activity.
     *
     * @param activityToken activity token.
     * @param autoFillId id of the view that requested auto-fill.
     * @param bounds boundaries of the view that requested auto-fill.
     * @param flags optional flags.
     */
    void requestAutoFillLocked(IBinder activityToken, @Nullable AutoFillId autoFillId,
          @Nullable Rect bounds, int flags) {
        final String historyItem = "s=" + mComponentName + " u=" + mUserId + " f=" + flags
                + " a=" + activityToken + " i=" + autoFillId + " b=" + bounds;
        mRequestsHistory.log(historyItem);

        // TODO(b/33197203): Handle partitioning
        Session session = getOrCreateSessionByTokenLocked(activityToken);
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
        if (DEBUG) Slog.d(TAG, "creating session for " + activityToken + ": " + sessionId);

        final Session newSession = new Session(mContext, activityToken, sessionId);
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
            if (!mAm.requestAutoFillData(mAssistReceiver, null, sessionId, activityToken)) {
                // TODO(b/33197203): might need a way to warn user (perhaps a new method on
                // AutoFillService).
                Slog.w(TAG, "failed to request auto-fill data for " + activityToken);
            }
        } catch (RemoteException e) {
            // Should not happen, it's a local call.
        }
        return newSession;
    }

    /**
     * Callback indicating the value of a field change in the app.
     */
    void onValueChangeLocked(IBinder activityToken, AutoFillId autoFillId, AutoFillValue newValue) {
        // TODO(b/33197203): add MetricsLogger call
        final Session session = getOrCreateSessionByTokenLocked(activityToken);
        session.updateValueLocked(autoFillId, newValue);
    }

    void removeSessionLocked(int id) {
        if (DEBUG) Slog.d(TAG, "Removing session " + id);
        mSessions.get(id);
    }

    void destroyLocked() {
        mContext.unregisterReceiver(mBroadcastReceiver);
        final int sessionCount = mSessions.size();
        for (int i = sessionCount - 1; i >= 0; i--) {
            Session session = mSessions.valueAt(i);
            session.destroy();
            mSessions.removeAt(i);
        }
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";
        if (DEBUG) {
            // ServiceInfo dump is too noisy and redundant (it can be obtained through other dumps)
            pw.print(prefix); pw.println("ServiceInfo:");
            mInfo.getServiceInfo().dump(new PrintWriterPrinter(pw), prefix + prefix);
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
        return "AutoFillManagerServiceImpl: [userId=" + mUserId
                + ", component=" + mComponentName + "]";
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
        // // TODO(b/33197203): does it really need a reference to the session's response?
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
        private final int mId;

        private final WeakReference<IBinder> mActivityToken;

        @GuardedBy("mLock")
        private final Map<AutoFillId, ViewState> mViewStates = new ArrayMap<>();

        @GuardedBy("mLock")
        @Nullable
        private ViewState mCurrentViewState;

        private IAutoFillAppCallback mAppCallback;

        @GuardedBy("mLock")
        RemoteFillService mRemoteFillService;

        // TODO(b/33197203): Get a response per view instead of per activity.
        @GuardedBy("mLock")
        private FillResponse mCurrentResponse;

        /**
         * Map of ids that must be updated so they're send to {@link #onSaveLocked()}.
         */
        @GuardedBy("mLock")
        private Map<AutoFillId, AutoFillValue> mUpdatedValues;

        /**
         * Assist structure sent by the app; it will be updated (sanitized, change values for save)
         * before sent to {@link AutoFillService}.
         */
        @GuardedBy("mLock")
        private AssistStructure mStructure;

        private Session(Context context, IBinder activityToken, int id) {
            mActivityToken = new WeakReference<>(activityToken);
            mRemoteFillService = new RemoteFillService(context, mComponent, mUserId, this);
            mId = id;
        }

        // FillServiceCallbacks
        @Override
        public void onFillRequestSuccess(FillResponse response) {
            // TODO(b/33197203): add MetricsLogger call
            if (response == null) {
                destroy();
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
            destroy();
        }

        // FillServiceCallbacks
        @Override
        public void onSaveRequestSuccess() {
            // TODO: Implement
        }

        // FillServiceCallbacks
        @Override
        public void onSaveRequestFailure(CharSequence message) {
            // TODO(b/33197203): add MetricsLogger call
            getUiForShowing().showError(message);
            destroy();
        }

        // FillServiceCallbacks
        @Override
        public void authenticate(IntentSender intent, Intent fillInIntent) {
            startAuthIntent(intent, fillInIntent);
        }

        // FillServiceCallbacks
        @Override
        public void onServiceDied(RemoteFillService service) {
            // TODO: Implement
        }

        // AutoFillUiCallback
        @Override
        public void fill(Dataset dataset) {
            autoFill(dataset);
        }

        // AutoFillUiCallback
        @Override
        public void save() {
            synchronized (mLock) {
                onSaveLocked();
            }
        }

        private Session(int id, IBinder activityToken) {
            mId = id;
            mActivityToken = new WeakReference<>(activityToken);
        }

        /**
         * Callback used to indivate a field has been updated.
         */
        void updateValueLocked(AutoFillId id, AutoFillValue newValue) {
          if (DEBUG) Slog.d(TAG, "updateValueLocked(): id=" + id + ", newValue=" + newValue);

          // TODO(b/33197203): ignore if not part of the savable ids.
          if (mUpdatedValues == null) {
                // Lazy initializes it
                mUpdatedValues = new HashMap<>();
            }
            mUpdatedValues.put(id, newValue);
        }

        /**
         * Calls service when user requested save.
         */
        void onSaveLocked() {
            if (DEBUG) Slog.d(TAG, "onSaveLocked(): mUpdateValues=" + mUpdatedValues);

            if (mStructure == null) {
                // Sanity check; should not happen...
                Slog.wtf(TAG, "onSaveLocked(): no mStructure");
                return;
            }

            if (mUpdatedValues == null || mUpdatedValues.isEmpty()) {
                // Nothing changed
                if (DEBUG) Slog.d(TAG, "onSave(): when no changes, comes no responsibilities");
                return;
            }

            // TODO(b/33197203): make sure the extras are tested by CTS
            for (Entry<AutoFillId, AutoFillValue> entry : mUpdatedValues.entrySet()) {
                final AutoFillId id = entry.getKey();
                final ViewNode node = findViewNodeByIdLocked(id);
                if (node == null) {
                    Slog.w(TAG, "onSaveLocked(): did not find node with id " + id);
                    continue;
                }
                final AutoFillValue value = entry.getValue();
                if (DEBUG) Slog.d(TAG, "onSaveLocked(): updating " + id + " to " + value);
                node.updateAutoFillValue(value);
            }

            mStructure.sanitizeForParceling(false);

            if (VERBOSE) {
                Slog.v(TAG, "Dumping " + mStructure + " before calling service.save()");
                mStructure.dump();
            }

            mRemoteFillService.onSaveRequest(mStructure, mCurrentResponse.getExtras());
        }

        void onApplicationDataAvailable(AssistStructure structure, IBinder appCallback) {
            setAppCallback(appCallback);
            mStructure = structure;
            // TODO(b/33197203): Need to pipe the bundle
            mRemoteFillService.onFillRequest(structure, null);
        }

        private void setAppCallback(IBinder appBinder) {
            try {
                appBinder.linkToDeath(() -> {
                    if (DEBUG) Slog.d(TAG, "app callback died");
                    // TODO(b/33197203): more cleanup here?
                    mAppCallback = null;
                    destroy();
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
            getUiForShowing().showFillUi(viewState, response.getDatasets(), bounds, filterText);
        }

        private void processResponseLocked(FillResponse response) {
            if (DEBUG) Slog.d(TAG, "showResponse(authRequired="
                    + response.getAuthentication() +"):" + response);

            // TODO(b/33197203): add MetricsLogger calls

            mCurrentResponse = response;

            if (mCurrentResponse.getAuthentication() != null) {
                // ...or handle authentication.
                Intent fillInIntent = createAuthFillInIntent(response.getId(), mStructure,
                        new Bundle(), new FillCallback(new IFillCallback.Stub() {
                            @Override
                            public void onCancellable(ICancellationSignal cancellation) {
                                // TODO(b/33197203): Handle cancellation
                            }

                            @Override
                            public void onSuccess(FillResponse response) {
                                mCurrentResponse = createAuthenticatedResponse(
                                        mCurrentResponse, response);
                                processResponseLocked(mCurrentResponse);
                            }

                            @Override
                            public void onFailure(CharSequence message) {
                                getUiForShowing().showError(message);
                                destroy();
                            }
                        }));

                 getUiForShowing().showFillResponseAuthRequest(
                         mCurrentResponse.getAuthentication(), fillInIntent);
            } else {
                // TODO(b/33197203): Consider using mCurrentResponse, depends on partitioning design
                if (mCurrentViewState != null) {
                    mCurrentViewState.setResponse(mCurrentResponse);
                }
            }
        }

        void autoFill(Dataset dataset) {
            synchronized (mLock) {
                // Autofill it directly...
                if (dataset.getAuthentication() == null) {
                    autoFillApp(dataset);
                    // For now just show this on every fill
                    getUiForShowing().showSaveUi();
                    return;
                }

                // ...or handle authentication.
                Intent fillInIntent = createAuthFillInIntent(dataset.getId(), mStructure,
                        new Bundle(), new FillCallback(new IFillCallback.Stub() {
                    @Override
                    public void onCancellable(ICancellationSignal cancellation) {
                        // TODO(b/33197203): Handle cancellation
                    }

                    @Override
                    public void onSuccess(FillResponse response) {
                        mCurrentResponse = createAuthenticatedResponse(
                                mCurrentResponse, response);
                        Dataset augmentedDataset = Helper.findDatasetById(dataset.getId(),
                                mCurrentResponse);
                        if (augmentedDataset != null) {
                            autoFill(augmentedDataset);
                        }
                    }

                    @Override
                    public void onFailure(CharSequence message) {
                        getUiForShowing().showError(message);
                        destroy();
                    }
                }));

                startAuthIntent(dataset.getAuthentication(), fillInIntent);
            }
        }

        private Intent createAuthFillInIntent(String itemId, AssistStructure structure,
                Bundle extras, FillCallback fillCallback) {
            Intent fillInIntent = new Intent();
            fillInIntent.putExtra(Intent.EXTRA_AUTO_FILL_ITEM_ID, itemId);
            fillInIntent.putExtra(Intent.EXTRA_AUTO_FILL_ASSIST_STRUCTURE, structure);
            fillInIntent.putExtra(Intent.EXTRA_AUTO_FILL_EXTRAS, extras);
            fillInIntent.putExtra(Intent.EXTRA_AUTO_FILL_CALLBACK, fillCallback);
            return fillInIntent;
        }

        private void startAuthIntent(IntentSender intent, Intent fillInIntent) {
            try {
                mAppCallback.startIntentSender(intent, fillInIntent);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error launching auth intent", e);
            }
        }

        void dumpLocked(String prefix, PrintWriter pw) {
            pw.print(prefix); pw.print("mId: "); pw.println(mId);
            pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken.get());
            pw.print(prefix); pw.print("mCurrentResponse: "); pw.println(mCurrentResponse);
            pw.print(prefix); pw.print("mCurrentViewStates: "); pw.println(mCurrentViewState);
            pw.print(prefix); pw.print("mViewStates: "); pw.println(mViewStates.size());
            final String prefix2 = prefix + "  ";
            for (Map.Entry<AutoFillId, ViewState> entry : mViewStates.entrySet()) {
                pw.print(prefix2);
                pw.print(entry.getKey()); pw.print(": " ); pw.println(entry.getValue());
            }
            pw.print(prefix); pw.print("mUpdatedValues: "); pw.println(mUpdatedValues);
            pw.print(prefix); pw.print("mStructure: " );
            // TODO(b/33197203): add method do dump AssistStructure on pw
            if (mStructure != null) {
                pw.println("look at logcat" );
                mStructure.dump(); // dumps to logcat
            } else {
                pw.println("null");
            }

            mRemoteFillService.dump(prefix, pw);
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

        private AutoFillUI getUiForShowing() {
            mUi.setCallback(this, mId);
            return mUi;
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

        private void destroy() {
            synchronized (mLock) {
                mRemoteFillService.destroy();
                mUi.hideAll();
                mUi.setCallback(null, 0);
                removeSessionLocked(mId);
            }
        }

        /**
         * Creates a response from the {@code original} and an {@code update} by
         * replacing all items that needed authentication (response or datasets)
         * with their updated version if the latter does not need authentication.
         * New datasets that don't require auth are appended.
         *
         * @param original The original response requiring auth at some level.
         * @param update An updated response with auth not needed anymore at some level.
         * @return A new response with updated items where auth is not needed anymore.
         */
        // TODO(b/33197203) Unit test
        FillResponse createAuthenticatedResponse(FillResponse original, FillResponse update) {
            // Can update only if ids match
            if (!original.getId().equals(update.getId())) {
                return original;
            }

            // If the original required auth and the update doesn't, the update wins
            // but only if none of the update's datasets requires authentication.
            if (original.getAuthentication() != null && update.getAuthentication() == null) {
                ArraySet<Dataset> updateDatasets = update.getDatasets();
                final int udpateDatasetCount = updateDatasets.size();
                for (int i = 0; i < udpateDatasetCount; i++) {
                    Dataset updateDataset = updateDatasets.valueAt(i);
                    if (updateDataset.getAuthentication() != null) {
                        return original;
                    }
                }
                return update;
            }

            // If no auth on response level we create a response that has all
            // datasets from the original with the ones that required auth but
            // not anymore updated and new ones not requiring auth appended.

            // The update shouldn't require auth
            if (update.getAuthentication() != null) {
                return original;
            }

            final FillResponse.Builder builder = new FillResponse.Builder(original.getId());

            // Update existing datasets
            final ArraySet<Dataset> origDatasets = original.getDatasets();
            final int origDatasetCount = origDatasets.size();
            for (int i = 0; i < origDatasetCount; i++) {
                Dataset origDataset = origDatasets.valueAt(i);
                ArraySet<Dataset> updateDatasets = update.getDatasets();
                final int updateDatasetCount = updateDatasets.size();
                for (int j = 0; j < updateDatasetCount; j++) {
                    Dataset updateDataset = updateDatasets.valueAt(j);
                    if (origDataset.getId().equals(updateDataset.getId())) {
                        // The update shouldn't require auth
                        if (updateDataset.getAuthentication() == null) {
                            origDataset = updateDataset;
                            updateDatasets.removeAt(j);
                        }
                        break;
                    }
                }
                builder.addDataset(origDataset);
            }

            // Add new datasets
            final ArraySet<Dataset> updateDatasets = update.getDatasets();
            final int updateDatasetCount = updateDatasets.size();
            for (int i = 0; i < updateDatasetCount; i++) {
                final Dataset updateDataset = updateDatasets.valueAt(i);
                builder.addDataset(updateDataset);
            }

            // For now no extras and savable id updates.

            return builder.build();
        }
    }
}
