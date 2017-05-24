/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST;
import static android.service.autofill.FillRequest.INVALID_REQUEST_ID;
import static android.service.voice.VoiceInteractionSession.KEY_RECEIVER_EXTRAS;
import static android.service.voice.VoiceInteractionSession.KEY_STRUCTURE;
import static android.view.autofill.AutofillManager.ACTION_START_SESSION;
import static android.view.autofill.AutofillManager.ACTION_VALUE_CHANGED;
import static android.view.autofill.AutofillManager.ACTION_VIEW_ENTERED;
import static android.view.autofill.AutofillManager.ACTION_VIEW_EXITED;

import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sPartitionMaxCount;
import static com.android.server.autofill.Helper.sVerbose;
import static com.android.server.autofill.Helper.toArray;
import static com.android.server.autofill.ViewState.STATE_AUTOFILLED;
import static com.android.server.autofill.ViewState.STATE_RESTARTED_SESSION;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.AutofillOverlay;
import android.app.assist.AssistStructure.ViewNode;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcelable;
import android.os.RemoteException;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Slog;
import android.util.SparseArray;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import android.view.autofill.IAutofillWindowPresenter;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.HandlerCaller;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.ArrayUtils;
import com.android.server.autofill.ui.AutoFillUI;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A session for a given activity.
 *
 * <p>This class manages the multiple {@link ViewState}s for each view it has, and keeps track
 * of the current {@link ViewState} to display the appropriate UI.
 *
 * <p>Although the autofill requests and callbacks are stateless from the service's point of
 * view, we need to keep state in the framework side for cases such as authentication. For
 * example, when service return a {@link FillResponse} that contains all the fields needed
 * to fill the activity but it requires authentication first, that response need to be held
 * until the user authenticates or it times out.
 */
final class Session implements RemoteFillService.FillServiceCallbacks, ViewState.Listener,
        AutoFillUI.AutoFillUiCallback {
    private static final String TAG = "AutofillSession";

    private static final String EXTRA_REQUEST_ID = "android.service.autofill.extra.REQUEST_ID";

    private final AutofillManagerServiceImpl mService;
    private final HandlerCaller mHandlerCaller;
    private final Object mLock;
    private final AutoFillUI mUi;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private static AtomicInteger sIdCounter = new AtomicInteger();

    /** Id of the session */
    public final int id;

    /** uid the session is for */
    public final int uid;

    @GuardedBy("mLock")
    @NonNull private IBinder mActivityToken;

    /** Package name of the app that is auto-filled */
    @NonNull private final String mPackageName;

    @GuardedBy("mLock")
    private final ArrayMap<AutofillId, ViewState> mViewStates = new ArrayMap<>();

    /**
     * Id of the View currently being displayed.
     */
    @GuardedBy("mLock")
    @Nullable private AutofillId mCurrentViewId;

    @GuardedBy("mLock")
    private IAutoFillManagerClient mClient;

    private final RemoteFillService mRemoteFillService;

    @GuardedBy("mLock")
    private SparseArray<FillResponse> mResponses;

    /**
     * Contexts read from the app; they will be updated (sanitized, change values for save) before
     * sent to {@link AutofillService}. Ordered by the time they we read.
     */
    @GuardedBy("mLock")
    private ArrayList<FillContext> mContexts;

    /**
     * Whether the client has an {@link android.view.autofill.AutofillManager.AutofillCallback}.
     */
    private boolean mHasCallback;

    /**
     * Extras sent by service on {@code onFillRequest()} calls; the first non-null extra is saved
     * and used on subsequent {@code onFillRequest()} and {@code onSaveRequest()} calls.
     */
    @GuardedBy("mLock")
    private Bundle mClientState;

    @GuardedBy("mLock")
    private boolean mDestroyed;

    /** Whether the session is currently saving */
    @GuardedBy("mLock")
    private boolean mIsSaving;


    /**
     * Receiver of assist data from the app's {@link Activity}.
     */
    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            final AssistStructure structure = resultData.getParcelable(KEY_STRUCTURE);
            if (structure == null) {
                Slog.wtf(TAG, "no assist structure");
                return;
            }

            final Bundle receiverExtras = resultData.getBundle(KEY_RECEIVER_EXTRAS);
            if (receiverExtras == null) {
                Slog.wtf(TAG, "No " + KEY_RECEIVER_EXTRAS + " on receiver");
                return;
            }

            final int requestId = receiverExtras.getInt(EXTRA_REQUEST_ID);

            if (sVerbose) {
                Slog.v(TAG, "New structure for requestId " + requestId + ": " + structure);
            }

            final FillRequest request;
            synchronized (mLock) {
                // TODO(b/35708678): Must fetch the data so it's available later on handleSave(),
                // even if if the activity is gone by then, but structure .ensureData() gives a
                // ONE_WAY warning because system_service could block on app calls. We need to
                // change AssistStructure so it provides a "one-way" writeToParcel() method that
                // sends all the data
                structure.ensureData();

                // Sanitize structure before it's sent to service.
                structure.sanitizeForParceling(true);

                // Flags used to start the session.
                final int flags = structure.getFlags();

                if (mContexts == null) {
                    mContexts = new ArrayList<>(1);
                }
                mContexts.add(new FillContext(requestId, structure));

                cancelCurrentRequestLocked();

                final int numContexts = mContexts.size();
                for (int i = 0; i < numContexts; i++) {
                    fillContextWithAllowedValues(mContexts.get(i), flags);
                }

                request = new FillRequest(requestId, mContexts, mClientState, flags);
            }

            mRemoteFillService.onFillRequest(request);
        }
    };

    /**
     * Returns the ids of all entries in {@link #mViewStates} in the same order.
     */
    private AutofillId[] getIdsOfAllViewStates() {
        final int numViewState = mViewStates.size();
        final AutofillId[] ids = new AutofillId[numViewState];
        for (int i = 0; i < numViewState; i++) {
            ids[i] = mViewStates.valueAt(i).id;
        }

        return ids;
    }

    /**
     * Updates values of the nodes in the context's structure so that:
     * - proper node is focused
     * - autofillValue is sent back to service when it was previously autofilled
     * - autofillValue is sent in the view used to force a request
     *
     * @param fillContext The context to be filled
     * @param flags The flags that started the session
     */
    private void fillContextWithAllowedValues(@NonNull FillContext fillContext, int flags) {
        final ViewNode[] nodes = fillContext.findViewNodesByAutofillIds(getIdsOfAllViewStates());

        final int numViewState = mViewStates.size();
        for (int i = 0; i < numViewState; i++) {
            final ViewState viewState = mViewStates.valueAt(i);

            final ViewNode node = nodes[i];
            if (node == null) {
                Slog.w(TAG, "fillStructureWithAllowedValues(): no node for " + viewState.id);
                continue;
            }

            final AutofillValue currentValue = viewState.getCurrentValue();
            final AutofillValue filledValue = viewState.getAutofilledValue();
            final AutofillOverlay overlay = new AutofillOverlay();

            // Sanitizes the value if the current value matches what the service sent.
            if (filledValue != null && filledValue.equals(currentValue)) {
                overlay.value = currentValue;
            }

            if (mCurrentViewId != null) {
                // Updates the focus value.
                overlay.focused = mCurrentViewId.equals(viewState.id);
                // Sanitizes the value of the focused field in a manual request.
                if (overlay.focused && (flags & FLAG_MANUAL_REQUEST) != 0) {
                    overlay.value = currentValue;
                }
            }
            node.setAutofillOverlay(overlay);
        }
    }

    /**
     * Cancels the last request sent to the {@link #mRemoteFillService}.
     */
    private void cancelCurrentRequestLocked() {
        int canceledRequest = mRemoteFillService.cancelCurrentRequest();

        // Remove the FillContext as there will never be a response for the service
        if (canceledRequest != INVALID_REQUEST_ID && mContexts != null) {
            int numContexts = mContexts.size();

            // It is most likely the last context, hence search backwards
            for (int i = numContexts - 1; i >= 0; i--) {
                if (mContexts.get(i).getRequestId() == canceledRequest) {
                    mContexts.remove(i);
                    break;
                }
            }
        }

    }

    /**
     * Reads a new structure and then request a new fill response from the fill service.
     */
    private void requestNewFillResponseLocked(int flags) {
        int requestId;

        do {
            requestId = sIdCounter.getAndIncrement();
        } while (requestId == INVALID_REQUEST_ID);

        if (sVerbose) {
            Slog.v(TAG, "Requesting structure for requestId=" + requestId + ", flags=" + flags);
        }

        // If the focus changes very quickly before the first request is returned each focus change
        // triggers a new partition and we end up with many duplicate partitions. This is
        // enhanced as the focus change can be much faster than the taking of the assist structure.
        // Hence remove the currently queued request and replace it with the one queued after the
        // structure is taken. This causes only one fill request per bust of focus changes.
        cancelCurrentRequestLocked();

        try {
            final Bundle receiverExtras = new Bundle();
            receiverExtras.putInt(EXTRA_REQUEST_ID, requestId);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (!ActivityManager.getService().requestAutofillData(mAssistReceiver,
                        receiverExtras, mActivityToken, flags)) {
                    Slog.w(TAG, "failed to request autofill data for " + mActivityToken);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (RemoteException e) {
            // Should not happen, it's a local call.
        }
    }

    Session(@NonNull AutofillManagerServiceImpl service, @NonNull AutoFillUI ui,
            @NonNull Context context, @NonNull HandlerCaller handlerCaller, int userId,
            @NonNull Object lock, int sessionId, int uid, @NonNull IBinder activityToken,
            @NonNull IBinder client, boolean hasCallback,
            @NonNull ComponentName componentName, @NonNull String packageName) {
        id = sessionId;
        this.uid = uid;
        mService = service;
        mLock = lock;
        mUi = ui;
        mHandlerCaller = handlerCaller;
        mRemoteFillService = new RemoteFillService(context, componentName, userId, this);
        mActivityToken = activityToken;
        mHasCallback = hasCallback;
        mPackageName = packageName;
        mClient = IAutoFillManagerClient.Stub.asInterface(client);

        mMetricsLogger.action(MetricsEvent.AUTOFILL_SESSION_STARTED, mPackageName);
    }

    /**
     * Gets the currently registered activity token
     *
     * @return The activity token
     */
    @NonNull IBinder getActivityTokenLocked() {
        return mActivityToken;
    }

    /**
     * Sets new activity and client for this session.
     *
     * @param newActivity The token of the new activity
     * @param newClient The client receiving autofill callbacks
     */
    void switchActivity(@NonNull IBinder newActivity, @NonNull IBinder newClient) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#switchActivity() rejected - session: "
                        + id + " destroyed");
                return;
            }
            mActivityToken = newActivity;
            mClient = IAutoFillManagerClient.Stub.asInterface(newClient);

            // The tracked id are not persisted in the client, hence update them
            updateTrackedIdsLocked();
        }
    }

    // FillServiceCallbacks
    @Override
    public void onFillRequestSuccess(int requestFlags, @Nullable FillResponse response,
            int serviceUid, @NonNull String servicePackageName) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestSuccess() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        if (response == null) {
            if (sVerbose) Slog.v(TAG, "canceling session " + id + " when server returned null");
            if ((requestFlags & FLAG_MANUAL_REQUEST) != 0) {
                getUiForShowing().showError(R.string.autofill_error_cannot_autofill, this);
            }
            // Nothing to be done, but need to notify client.
            notifyUnavailableToClient();
            removeSelf();
            return;
        }

        mService.setLastResponse(serviceUid, response);

        if ((response.getDatasets() == null || response.getDatasets().isEmpty())
                        && response.getAuthentication() == null) {
            // Response is "empty" from an UI point of view, need to notify client.
            notifyUnavailableToClient();
        }
        synchronized (mLock) {
            processResponseLocked(response);
        }

        final LogMaker log = (new LogMaker(MetricsEvent.AUTOFILL_REQUEST))
                .setType(MetricsEvent.TYPE_SUCCESS)
                .setPackageName(mPackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS,
                        response.getDatasets() == null ? 0 : response.getDatasets().size())
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE,
                        servicePackageName);
        mMetricsLogger.write(log);
    }

    // FillServiceCallbacks
    @Override
    public void onFillRequestFailure(@Nullable CharSequence message,
            @NonNull String servicePackageName) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestFailure() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        LogMaker log = (new LogMaker(MetricsEvent.AUTOFILL_REQUEST))
                .setType(MetricsEvent.TYPE_FAILURE)
                .setPackageName(mPackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName);
        mMetricsLogger.write(log);

        getUiForShowing().showError(message, this);
        removeSelf();
    }

    // FillServiceCallbacks
    @Override
    public void onSaveRequestSuccess(@NonNull String servicePackageName) {
        synchronized (mLock) {
            mIsSaving = false;

            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onSaveRequestSuccess() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        LogMaker log = (new LogMaker(
                MetricsEvent.AUTOFILL_DATA_SAVE_REQUEST))
                .setType(MetricsEvent.TYPE_SUCCESS)
                .setPackageName(mPackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName);
        mMetricsLogger.write(log);

        // Nothing left to do...
        removeSelf();
    }

    // FillServiceCallbacks
    @Override
    public void onSaveRequestFailure(@Nullable CharSequence message,
            @NonNull String servicePackageName) {
        synchronized (mLock) {
            mIsSaving = false;

            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onSaveRequestFailure() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        LogMaker log = (new LogMaker(
                MetricsEvent.AUTOFILL_DATA_SAVE_REQUEST))
                .setType(MetricsEvent.TYPE_FAILURE)
                .setPackageName(mPackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName);
        mMetricsLogger.write(log);

        getUiForShowing().showError(message, this);
        removeSelf();
    }

    /**
     * Gets the {@link FillContext} for a request.
     *
     * @param requestId The id of the request
     *
     * @return The context or {@code null} if there is no context
     */
    @Nullable private FillContext getFillContextByRequestIdLocked(int requestId) {
        if (mContexts == null) {
            return null;
        }

        int numContexts = mContexts.size();
        for (int i = 0; i < numContexts; i++) {
            FillContext context = mContexts.get(i);

            if (context.getRequestId() == requestId) {
                return context;
            }
        }

        return null;
    }

    // FillServiceCallbacks
    @Override
    public void authenticate(int requestId, int datasetIndex, IntentSender intent, Bundle extras) {
        final Intent fillInIntent;
        synchronized (mLock) {
            synchronized (mLock) {
                if (mDestroyed) {
                    Slog.w(TAG, "Call to Session#authenticate() rejected - session: "
                            + id + " destroyed");
                    return;
                }
            }
            fillInIntent = createAuthFillInIntent(
                    getFillContextByRequestIdLocked(requestId).getStructure(), extras);
        }

        mService.setAuthenticationSelected();

        final int authenticationId = AutofillManager.makeAuthenticationId(requestId, datasetIndex);
        mHandlerCaller.getHandler().post(() -> startAuthentication(authenticationId,
                intent, fillInIntent));
    }

    // FillServiceCallbacks
    @Override
    public void onServiceDied(RemoteFillService service) {
        // TODO(b/337565347): implement
    }

    // AutoFillUiCallback
    @Override
    public void fill(int requestId, int datasetIndex, Dataset dataset) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#fill() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        mHandlerCaller.getHandler().post(() -> autoFill(requestId, datasetIndex, dataset));
    }

    // AutoFillUiCallback
    @Override
    public void save() {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#save() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        mHandlerCaller.getHandler()
                .obtainMessage(AutofillManagerServiceImpl.MSG_SERVICE_SAVE, id, 0)
                .sendToTarget();
    }

    // AutoFillUiCallback
    @Override
    public void cancelSave() {
        synchronized (mLock) {
            mIsSaving = false;

            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#cancelSave() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        mHandlerCaller.getHandler().post(() -> removeSelf());
    }

    // AutoFillUiCallback
    @Override
    public void requestShowFillUi(AutofillId id, int width, int height,
            IAutofillWindowPresenter presenter) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#requestShowFillUi() rejected - session: "
                        + id + " destroyed");
                return;
            }
            if (id.equals(mCurrentViewId)) {
                try {
                    final ViewState view = mViewStates.get(id);
                    mClient.requestShowFillUi(this.id, id, width, height, view.getVirtualBounds(),
                            presenter);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error requesting to show fill UI", e);
                }
            } else {
                if (sDebug) {
                    Slog.d(TAG, "Do not show full UI on " + id + " as it is not the current view ("
                            + mCurrentViewId + ") anymore");
                }
            }
        }
    }

    // AutoFillUiCallback
    @Override
    public void requestHideFillUi(AutofillId id) {
        synchronized (mLock) {
            // NOTE: We allow this call in a destroyed state as the UI is
            // asked to go away after we get destroyed, so let it do that.
            try {
                mClient.requestHideFillUi(this.id, id);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error requesting to hide fill UI", e);
            }
        }
    }

    // AutoFillUiCallback
    @Override
    public void startIntentSender(IntentSender intentSender) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#startIntentSender() rejected - session: "
                        + id + " destroyed");
                return;
            }
            removeSelfLocked();
        }
        mHandlerCaller.getHandler().post(() -> {
            try {
                synchronized (mLock) {
                    mClient.startIntentSender(intentSender);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error launching auth intent", e);
            }
        });
    }

    void setAuthenticationResultLocked(Bundle data, int authenticationId) {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#setAuthenticationResultLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }

        final int requestId = AutofillManager.getRequestIdFromAuthenticationId(authenticationId);
        final FillResponse authenticatedResponse = mResponses.get(requestId);
        if (authenticatedResponse == null || data == null) {
            removeSelf();
            return;
        }

        final int datasetIdx = AutofillManager.getDatasetIdFromAuthenticationId(
                authenticationId);
        // Authenticated a dataset - reset view state regardless if we got a response or a dataset
        if (datasetIdx != AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED) {
            final Dataset dataset = authenticatedResponse.getDatasets().get(datasetIdx);
            if (dataset == null) {
                removeSelf();
                return;
            }
            resetViewStatesLocked(dataset, ViewState.STATE_WAITING_DATASET_AUTH);
        }

        final Parcelable result = data.getParcelable(AutofillManager.EXTRA_AUTHENTICATION_RESULT);
        if (result instanceof FillResponse) {
            final FillResponse response = (FillResponse) result;
            mMetricsLogger.action(MetricsEvent.AUTOFILL_AUTHENTICATED, mPackageName);
            replaceResponseLocked(authenticatedResponse, response);
        } else if (result instanceof Dataset) {
            if (datasetIdx != AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED) {
                final Dataset dataset = (Dataset) result;
                authenticatedResponse.getDatasets().set(datasetIdx, dataset);
                autoFill(requestId, datasetIdx, dataset);
            }
        }
    }

    void setHasCallbackLocked(boolean hasIt) {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#setHasCallbackLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        mHasCallback = hasIt;
    }

    /**
     * Shows the save UI, when session can be saved.
     *
     * @return {@code true} if session is done, or {@code false} if it's pending user action.
     */
    public boolean showSaveLocked() {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#showSaveLocked() rejected - session: "
                    + id + " destroyed");
            return false;
        }
        if (mContexts == null) {
            Slog.d(TAG, "showSaveLocked(): no contexts");
            return true;
        }
        if (mResponses == null) {
            // Happens when the activity / session was finished before the service replied, or
            // when the service cannot autofill it (and returned a null response).
            if (sVerbose) {
                Slog.v(TAG, "showSaveLocked(): no responses on session");
            }
            return true;
        }

        final int lastResponseIdx = getLastResponseIndex();
        if (lastResponseIdx < 0) {
            Slog.w(TAG, "showSaveLocked(): did not get last response. mResponses=" + mResponses
                    + ", mViewStates=" + mViewStates);
            return true;
        }

        final FillResponse response = mResponses.valueAt(lastResponseIdx);
        final SaveInfo saveInfo = response.getSaveInfo();
        if (sVerbose) {
            Slog.v(TAG, "showSaveLocked(): mResponses=" + mResponses + ", mContexts=" + mContexts
                    + ", mViewStates=" + mViewStates);
        }

        /*
         * The Save dialog is only shown if all conditions below are met:
         *
         * - saveInfo is not null
         * - autofillValue of all required ids is not null
         * - autofillValue of at least one id (required or optional) has changed.
         */

        if (saveInfo == null) {
            return true;
        }

        final AutofillId[] requiredIds = saveInfo.getRequiredIds();
        if (requiredIds == null || requiredIds.length == 0) {
            Slog.w(TAG, "showSaveLocked(): no required ids on saveInfo");
            return true;
        }

        boolean allRequiredAreNotEmpty = true;
        boolean atLeastOneChanged = false;
        for (int i = 0; i < requiredIds.length; i++) {
            final AutofillId id = requiredIds[i];
            final ViewState viewState = mViewStates.get(id);
            if (viewState == null) {
                Slog.w(TAG, "showSaveLocked(): no ViewState for required " + id);
                allRequiredAreNotEmpty = false;
                break;
            }

            final AutofillValue currentValue = viewState.getCurrentValue();
            if (currentValue == null || currentValue.isEmpty()) {
                if (sDebug) {
                    Slog.d(TAG, "showSaveLocked(): empty value for required " + id );
                }
                allRequiredAreNotEmpty = false;
                break;
            }
            final AutofillValue filledValue = viewState.getAutofilledValue();

            if (!currentValue.equals(filledValue)) {
                if (sDebug) {
                    Slog.d(TAG, "showSaveLocked(): found a change on required " + id + ": "
                            + filledValue + " => " + currentValue);
                }
                atLeastOneChanged = true;
            }
        }

        final AutofillId[] optionalIds = saveInfo.getOptionalIds();
        if (allRequiredAreNotEmpty) {
            if (!atLeastOneChanged && optionalIds != null) {
                // No change on required ids yet, look for changes on optional ids.
                for (int i = 0; i < optionalIds.length; i++) {
                    final AutofillId id = optionalIds[i];
                    final ViewState viewState = mViewStates.get(id);
                    if (viewState == null) {
                        Slog.w(TAG, "showSaveLocked(): no ViewState for optional " + id);
                        continue;
                    }
                    if ((viewState.getState() & ViewState.STATE_CHANGED) != 0) {
                        final AutofillValue currentValue = viewState.getCurrentValue();
                        final AutofillValue filledValue = viewState.getAutofilledValue();
                        if (currentValue != null && !currentValue.equals(filledValue)) {
                            if (sDebug) {
                                Slog.d(TAG, "finishSessionLocked(): found a change on optional "
                                        + id + ": " + filledValue + " => " + currentValue);
                            }
                            atLeastOneChanged = true;
                            break;
                        }
                    }
                }
            }
            if (atLeastOneChanged) {
                if (sDebug) Slog.d(TAG, "at least one field changed - showing save UI");
                mService.setSaveShown();
                getUiForShowing().showSaveUi(mService.getServiceLabel(), saveInfo, mPackageName,
                        this);

                mIsSaving = true;
                return false;
            }
        }
        // Nothing changed...
        if (sDebug) {
            Slog.d(TAG, "showSaveLocked(): with no changes, comes no responsibilities."
                    + "allRequiredAreNotNull=" + allRequiredAreNotEmpty
                    + ", atLeastOneChanged=" + atLeastOneChanged);
        }
        return true;
    }

    /**
     * Returns whether the session is currently showing the save UI
     */
    boolean isSavingLocked() {
        return mIsSaving;
    }

    /**
     * Calls service when user requested save.
     */
    void callSaveLocked() {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#callSaveLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }

        if (sVerbose) Slog.v(TAG, "callSaveLocked(): mViewStates=" + mViewStates);

        final int numContexts = mContexts.size();

        for (int contextNum = 0; contextNum < numContexts; contextNum++) {
            final FillContext context = mContexts.get(contextNum);

            final ViewNode[] nodes = context.findViewNodesByAutofillIds(getIdsOfAllViewStates());

            if (sVerbose) Slog.v(TAG, "callSaveLocked(): updating " + context);

            for (int viewStateNum = 0; viewStateNum < mViewStates.size(); viewStateNum++) {
                final ViewState state = mViewStates.valueAt(viewStateNum);

                final AutofillId id = state.id;
                final AutofillValue value = state.getCurrentValue();
                if (value == null) {
                    if (sVerbose) Slog.v(TAG, "callSaveLocked(): skipping " + id);
                    continue;
                }
                final ViewNode node = nodes[viewStateNum];
                if (node == null) {
                    Slog.w(TAG, "callSaveLocked(): did not find node with id " + id);
                    continue;
                }
                if (sVerbose) Slog.v(TAG, "callSaveLocked(): updating " + id + " to " + value);

                node.updateAutofillValue(value);
            }

            // Sanitize structure before it's sent to service.
            context.getStructure().sanitizeForParceling(false);

            if (sVerbose) {
                Slog.v(TAG, "Dumping structure of " + context + " before calling service.save()");
                context.getStructure().dump(false);
            }
        }

        // Remove pending fill requests as the session is finished.
        cancelCurrentRequestLocked();

        final SaveRequest saveRequest = new SaveRequest(mContexts, mClientState);
        mRemoteFillService.onSaveRequest(saveRequest);
    }

    /**
     * Starts (if necessary) a new fill request upon entering a view.
     *
     * <p>A new request will be started in 2 scenarios:
     * <ol>
     *   <li>If the user manually requested autofill after the view was already filled.
     *   <li>If the view is part of a new partition.
     * </ol>
     *
     * @param id The id of the view that is entered.
     * @param viewState The view that is entered.
     * @param flags The flag that was passed by the AutofillManager.
     */
    private void requestNewFillResponseIfNecessaryLocked(@NonNull AutofillId id,
            @NonNull ViewState viewState, int flags) {
        // First check if this is a manual request after view was autofilled.
        final int state = viewState.getState();
        final boolean restart = (state & STATE_AUTOFILLED) != 0
                && (flags & FLAG_MANUAL_REQUEST) != 0;
        if (restart) {
            if (sDebug) Slog.d(TAG, "Re-starting session on view  " + id);
            viewState.setState(STATE_RESTARTED_SESSION);
            requestNewFillResponseLocked(flags);
            return;
        }

        // If it's not, then check if it it should start a partition.
        if (shouldStartNewPartitionLocked(id)) {
            if (sDebug) {
                Slog.d(TAG, "Starting partition for view id " + id + ": "
                        + viewState.getStateAsString());
            }
            viewState.setState(ViewState.STATE_STARTED_PARTITION);
            requestNewFillResponseLocked(flags);
        }
    }

    /**
     * Determines if a new partition should be started for an id.
     *
     * @param id The id of the view that is entered
     *
     * @return {@code true} iff a new partition should be started
     */
    private boolean shouldStartNewPartitionLocked(@NonNull AutofillId id) {
        if (mResponses == null) {
            return true;
        }

        final int numResponses = mResponses.size();
        if (numResponses >= sPartitionMaxCount) {
            Slog.e(TAG, "Not starting a new partition on " + id + " because session " + this.id
                    + " reached maximum of " + sPartitionMaxCount);
            return false;
        }

        for (int responseNum = 0; responseNum < numResponses; responseNum++) {
            final FillResponse response = mResponses.valueAt(responseNum);

            if (ArrayUtils.contains(response.getIgnoredIds(), id)) {
                return false;
            }

            final SaveInfo saveInfo = response.getSaveInfo();
            if (saveInfo != null) {
                if (ArrayUtils.contains(saveInfo.getOptionalIds(), id)
                        || ArrayUtils.contains(saveInfo.getRequiredIds(), id)) {
                    return false;
                }
            }

            final ArrayList<Dataset> datasets = response.getDatasets();
            if (datasets != null) {
                final int numDatasets = datasets.size();

                for (int dataSetNum = 0; dataSetNum < numDatasets; dataSetNum++) {
                    final ArrayList<AutofillId> fields = datasets.get(dataSetNum).getFieldIds();

                    if (fields != null && fields.contains(id)) {
                        return false;
                    }
                }
            }

            if (ArrayUtils.contains(response.getAuthenticationIds(), id)) {
                return false;
            }
        }

        return true;
    }

    void updateLocked(AutofillId id, Rect virtualBounds, AutofillValue value, int action,
            int flags) {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#updateLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        if (sVerbose) {
            Slog.v(TAG, "updateLocked(): id=" + id + ", action=" + action + ", flags=" + flags);
        }
        ViewState viewState = mViewStates.get(id);

        if (viewState == null) {
            if (action == ACTION_START_SESSION || action == ACTION_VALUE_CHANGED
                    || action == ACTION_VIEW_ENTERED) {
                if (sVerbose) Slog.v(TAG, "Creating viewState for " + id + " on " + action);
                boolean isIgnored = isIgnoredLocked(id);
                viewState = new ViewState(this, id, value, this,
                        isIgnored ? ViewState.STATE_IGNORED : ViewState.STATE_INITIAL);
                mViewStates.put(id, viewState);
                if (isIgnored) {
                    if (sDebug) Slog.d(TAG, "updateLocked(): ignoring view " + id);
                    return;
                }
            } else {
                if (sVerbose) Slog.v(TAG, "Ignored action " + action + " for " + id);
                return;
            }
        }

        switch(action) {
            case ACTION_START_SESSION:
                // View is triggering autofill.
                mCurrentViewId = viewState.id;
                viewState.update(value, virtualBounds);
                viewState.setState(ViewState.STATE_STARTED_SESSION);
                requestNewFillResponseLocked(flags);
                break;
            case ACTION_VALUE_CHANGED:
                if (value != null && !value.equals(viewState.getCurrentValue())) {
                    // Always update the internal state.
                    viewState.setCurrentValue(value);

                    // Must check if this update was caused by autofilling the view, in which
                    // case we just update the value, but not the UI.
                    final AutofillValue filledValue = viewState.getAutofilledValue();
                    if (value.equals(filledValue)) {
                        return;
                    }
                    // Update the internal state...
                    viewState.setState(ViewState.STATE_CHANGED);

                    //..and the UI
                    if (value.isText()) {
                        getUiForShowing().filterFillUi(value.getTextValue().toString(), this);
                    } else {
                        getUiForShowing().filterFillUi(null, this);
                    }
                }
                break;
            case ACTION_VIEW_ENTERED:
                if (sVerbose && virtualBounds != null) {
                    Slog.w(TAG, "entered on virtual child " + id + ": " + virtualBounds);
                }
                requestNewFillResponseIfNecessaryLocked(id, viewState, flags);

                // Remove the UI if the ViewState has changed.
                if (mCurrentViewId != viewState.id) {
                    hideFillUiIfOwnedByMe();
                    mCurrentViewId = viewState.id;
                }

                // If the ViewState is ready to be displayed, onReady() will be called.
                viewState.update(value, virtualBounds);
                break;
            case ACTION_VIEW_EXITED:
                if (mCurrentViewId == viewState.id) {
                    if (sVerbose) Slog.d(TAG, "Exiting view " + id);
                    hideFillUiIfOwnedByMe();
                    mCurrentViewId = null;
                }
                break;
            default:
                Slog.w(TAG, "updateLocked(): unknown action: " + action);
        }
    }

    /**
     * Checks whether a view should be ignored.
     */
    private boolean isIgnoredLocked(AutofillId id) {
        if (mResponses == null || mResponses.size() == 0) {
            return false;
        }
        // Always check the latest response only
        final FillResponse response = mResponses.valueAt(mResponses.size() - 1);
        return ArrayUtils.contains(response.getIgnoredIds(), id);
    }

    @Override
    public void onFillReady(FillResponse response, AutofillId filledId,
            @Nullable AutofillValue value) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillReady() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }

        String filterText = null;
        if (value != null && value.isText()) {
            filterText = value.getTextValue().toString();
        }

        getUiForShowing().showFillUi(filledId, response, filterText, mPackageName, this);
    }

    boolean isDestroyed() {
        synchronized (mLock) {
            return mDestroyed;
        }
    }

    IAutoFillManagerClient getClient() {
        synchronized (mLock) {
            return mClient;
        }
    }

    private void notifyUnavailableToClient() {
        synchronized (mLock) {
            if (!mHasCallback || mCurrentViewId == null) return;
            try {
                mClient.notifyNoFillUi(id, mCurrentViewId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client no fill UI: id=" + mCurrentViewId, e);
            }
        }
    }

    private void updateTrackedIdsLocked() {
        if (mResponses == null || mResponses.size() == 0) {
            return;
        }

        // Only track the views of the last response as only those are reported back to the
        // service, see #showSaveLocked
        final FillResponse response = mResponses.valueAt(getLastResponseIndex());

        ArraySet<AutofillId> trackedViews = null;
        boolean saveOnAllViewsInvisible = false;
        final SaveInfo saveInfo = response.getSaveInfo();
        if (saveInfo != null) {
            saveOnAllViewsInvisible =
                    (saveInfo.getFlags() & SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE) != 0;

            // We only need to track views if we want to save once they become invisible.
            if (saveOnAllViewsInvisible) {
                if (trackedViews == null) {
                    trackedViews = new ArraySet<>();
                }
                if (saveInfo.getRequiredIds() != null) {
                    Collections.addAll(trackedViews, saveInfo.getRequiredIds());
                }

                if (saveInfo.getOptionalIds() != null) {
                    Collections.addAll(trackedViews, saveInfo.getOptionalIds());
                }
            }
        }

        // Must also track that are part of datasets, otherwise the FillUI won't be hidden when
        // they go away (if they're not savable).

        final ArrayList<Dataset> datasets = response.getDatasets();
        ArraySet<AutofillId> fillableIds = null;
        if (datasets != null) {
            for (int i = 0; i < datasets.size(); i++) {
                final Dataset dataset = datasets.get(i);
                final ArrayList<AutofillId> fieldIds = dataset.getFieldIds();
                if (fieldIds == null) continue;

                for (int j = 0; j < fieldIds.size(); j++) {
                    final AutofillId id = fieldIds.get(j);
                    if (trackedViews == null || !trackedViews.contains(id)) {
                        fillableIds = ArrayUtils.add(fillableIds, id);
                    }
                }
            }
        }

        try {
            if (sVerbose) {
                Slog.v(TAG, "updateTrackedIdsLocked(): " + trackedViews + " => " + fillableIds);
            }
            mClient.setTrackedViews(id, toArray(trackedViews), saveOnAllViewsInvisible,
                    toArray(fillableIds));
        } catch (RemoteException e) {
            Slog.w(TAG, "Cannot set tracked ids", e);
        }
    }

    private void replaceResponseLocked(@NonNull FillResponse oldResponse,
            @NonNull FillResponse newResponse) {
        // Disassociate view states with the old response
        setViewStatesLocked(oldResponse, ViewState.STATE_INITIAL, true);
        // Move over the id
        newResponse.setRequestId(oldResponse.getRequestId());
        // Replace the old response
        mResponses.put(newResponse.getRequestId(), newResponse);
        // Now process the new response
        processResponseLocked(newResponse);
    }

    private void processResponseLocked(@NonNull FillResponse newResponse) {
        // Make sure we are hiding the UI which will be shown
        // only if handling the current response requires it.
        hideAllUiIfOwnedByMe();

        final int requestId = newResponse.getRequestId();
        if (sVerbose) {
            Slog.v(TAG, "processResponseLocked(): mCurrentViewId=" + mCurrentViewId
                    + ", reqId=" + requestId + ", resp=" + newResponse);
        }

        if (mResponses == null) {
            mResponses = new SparseArray<>(4);
        }
        mResponses.put(requestId, newResponse);
        mClientState = newResponse.getClientState();

        setViewStatesLocked(newResponse, ViewState.STATE_FILLABLE, false);
        updateTrackedIdsLocked();

        if (mCurrentViewId == null) {
            return;
        }

        final ArrayList<Dataset> datasets = newResponse.getDatasets();

        if (datasets != null && datasets.size() == 1) {
            // Check if it its a single response for a manual request, in which case it should
            // be automatically filled
            final FillContext context = getFillContextByRequestIdLocked(requestId);
            if (context != null && (context.getStructure().getFlags() & FLAG_MANUAL_REQUEST) != 0) {
                Slog.d(TAG, "autofilling manual request directly");
                autoFill(requestId, 0, datasets.get(0));
                return;
            }
        }
        // Updates the UI, if necessary.
        final ViewState currentView = mViewStates.get(mCurrentViewId);
        currentView.maybeCallOnFillReady();
    }

    /**
     * Sets the state of all views in the given response.
     */
    private void setViewStatesLocked(FillResponse response, int state, boolean clearResponse) {
        final ArrayList<Dataset> datasets = response.getDatasets();
        if (datasets != null) {
            for (int i = 0; i < datasets.size(); i++) {
                final Dataset dataset = datasets.get(i);
                if (dataset == null) {
                    Slog.w(TAG, "Ignoring null dataset on " + datasets);
                    continue;
                }
                setViewStatesLocked(response, dataset, state, clearResponse);
            }
        } else if (response.getAuthentication() != null) {
            for (AutofillId autofillId : response.getAuthenticationIds()) {
                final ViewState viewState = createOrUpdateViewStateLocked(autofillId, state, null);
                if (!clearResponse) {
                    viewState.setResponse(response);
                } else {
                    viewState.setResponse(null);
                }
            }
        }
        final SaveInfo saveInfo = response.getSaveInfo();
        if (saveInfo != null) {
            final AutofillId[] requiredIds = saveInfo.getRequiredIds();
            for (AutofillId id : requiredIds) {
                createOrUpdateViewStateLocked(id, state, null);
            }
            final AutofillId[] optionalIds = saveInfo.getOptionalIds();
            if (optionalIds != null) {
                for (AutofillId id : optionalIds) {
                    createOrUpdateViewStateLocked(id, state, null);
                }
            }
        }

        final AutofillId[] authIds = response.getAuthenticationIds();
        if (authIds != null) {
            for (AutofillId id : authIds) {
                createOrUpdateViewStateLocked(id, state, null);
            }
        }
    }

    /**
     * Sets the state of all views in the given dataset and response.
     */
    private void setViewStatesLocked(@Nullable FillResponse response, @NonNull Dataset dataset,
            int state, boolean clearResponse) {
        final ArrayList<AutofillId> ids = dataset.getFieldIds();
        final ArrayList<AutofillValue> values = dataset.getFieldValues();
        for (int j = 0; j < ids.size(); j++) {
            final AutofillId id = ids.get(j);
            final AutofillValue value = values.get(j);
            final ViewState viewState = createOrUpdateViewStateLocked(id, state, value);
            if (response != null) {
                viewState.setResponse(response);
            } else if (clearResponse) {
                viewState.setResponse(null);
            }
        }
    }

    private ViewState createOrUpdateViewStateLocked(@NonNull AutofillId id, int state,
            @Nullable AutofillValue value) {
        ViewState viewState = mViewStates.get(id);
        if (viewState != null)  {
            viewState.setState(state);
        } else {
            viewState = new ViewState(this, id, null, this, state);
            if (sVerbose) {
                Slog.v(TAG, "Adding autofillable view with id " + id + " and state " + state);
            }
            mViewStates.put(id, viewState);
        }
        if ((state & ViewState.STATE_AUTOFILLED) != 0) {
            viewState.setAutofilledValue(value);
        }
        return viewState;
    }

    /**
     * Resets the given state from all existing views in the given dataset.
     */
    private void resetViewStatesLocked(@NonNull Dataset dataset, int state) {
        final ArrayList<AutofillId> ids = dataset.getFieldIds();
        for (int j = 0; j < ids.size(); j++) {
            final AutofillId id = ids.get(j);
            final ViewState viewState = mViewStates.get(id);
            if (viewState != null)  {
                viewState.resetState(state);
            }
        }
    }

    void autoFill(int requestId, int datasetIndex, Dataset dataset) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#autoFill() rejected - session: "
                        + id + " destroyed");
                return;
            }
            // Autofill it directly...
            if (dataset.getAuthentication() == null) {
                mService.setDatasetSelected(dataset.getId());

                autoFillApp(dataset);
                return;
            }

            // ...or handle authentication.
            // TODO(b/37424539): proper implementation
            mService.setDatasetAuthenticationSelected(dataset.getId());
            setViewStatesLocked(null, dataset, ViewState.STATE_WAITING_DATASET_AUTH, false);
            final Intent fillInIntent = createAuthFillInIntent(
                    getFillContextByRequestIdLocked(requestId).getStructure(), mClientState);

            final int authenticationId = AutofillManager.makeAuthenticationId(requestId,
                    datasetIndex);
            startAuthentication(authenticationId, dataset.getAuthentication(), fillInIntent);
        }
    }

    CharSequence getServiceName() {
        synchronized (mLock) {
            return mService.getServiceName();
        }
    }

    private Intent createAuthFillInIntent(AssistStructure structure, Bundle extras) {
        final Intent fillInIntent = new Intent();
        fillInIntent.putExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, structure);
        fillInIntent.putExtra(AutofillManager.EXTRA_CLIENT_STATE, extras);
        return fillInIntent;
    }

    private void startAuthentication(int authenticationId, IntentSender intent,
            Intent fillInIntent) {
        try {
            synchronized (mLock) {
                mClient.authenticate(id, authenticationId, intent, fillInIntent);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Error launching auth intent", e);
        }
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";
        pw.print(prefix); pw.print("id: "); pw.println(id);
        pw.print(prefix); pw.print("uid: "); pw.println(uid);
        pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken);
        pw.print(prefix); pw.print("mResponses: ");
        if (mResponses == null) {
            pw.println("null");
        } else {
            pw.println(mResponses.size());
            for (int i = 0; i < mResponses.size(); i++) {
                pw.print(prefix2); pw.print('#'); pw.print(i);
                pw.print(' '); pw.println(mResponses.valueAt(i));
            }
        }
        pw.print(prefix); pw.print("mCurrentViewId: "); pw.println(mCurrentViewId);
        pw.print(prefix); pw.print("mViewStates size: "); pw.println(mViewStates.size());
        pw.print(prefix); pw.print("mDestroyed: "); pw.println(mDestroyed);
        pw.print(prefix); pw.print("mIsSaving: "); pw.println(mIsSaving);
        for (Map.Entry<AutofillId, ViewState> entry : mViewStates.entrySet()) {
            pw.print(prefix); pw.print("State for id "); pw.println(entry.getKey());
            entry.getValue().dump(prefix2, pw);
        }

        pw.print(prefix); pw.print("mContexts: " );
        if (mContexts != null) {
            int numContexts = mContexts.size();
            for (int i = 0; i < numContexts; i++) {
                FillContext context = mContexts.get(i);

                pw.print(prefix2); pw.print(context);
                if (sVerbose) {
                    pw.println(context.getStructure() + " (look at logcat)");

                    // TODO: add method on AssistStructure to dump on pw
                    context.getStructure().dump(false);
                }
            }
        } else {
            pw.println("null");
        }

        pw.print(prefix); pw.print("mHasCallback: "); pw.println(mHasCallback);
        pw.print(prefix); pw.print("mClientState: "); pw.println(
                Helper.bundleToString(mClientState));
        mRemoteFillService.dump(prefix, pw);
    }

    void autoFillApp(Dataset dataset) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#autoFillApp() rejected - session: "
                        + id + " destroyed");
                return;
            }
            try {
                if (sDebug) Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                // Skip null values as a null values means no change
                final int entryCount = dataset.getFieldIds().size();
                final List<AutofillId> ids = new ArrayList<>(entryCount);
                final List<AutofillValue> values = new ArrayList<>(entryCount);
                for (int i = 0; i < entryCount; i++) {
                    if (dataset.getFieldValues().get(i) == null) {
                        continue;
                    }
                    ids.add(dataset.getFieldIds().get(i));
                    values.add(dataset.getFieldValues().get(i));
                }
                if (!ids.isEmpty()) {
                    mClient.autofill(id, ids, values);
                }
                setViewStatesLocked(null, dataset, ViewState.STATE_AUTOFILLED, false);
            } catch (RemoteException e) {
                Slog.w(TAG, "Error autofilling activity: " + e);
            }
        }
    }

    private AutoFillUI getUiForShowing() {
        synchronized (mLock) {
            mUi.setCallback(this);
            return mUi;
        }
    }

    void destroyLocked() {
        if (mDestroyed) {
            return;
        }
        mRemoteFillService.destroy();
        hideAllUiIfOwnedByMe();
        mUi.clearCallback(this);
        mDestroyed = true;
        mMetricsLogger.action(MetricsEvent.AUTOFILL_SESSION_FINISHED, mPackageName);
    }

    private void hideAllUiIfOwnedByMe() {
        mUi.hideAll(this);
    }

    private void hideFillUiIfOwnedByMe() {
        mUi.hideFillUi(this);
    }

    private void removeSelf() {
        synchronized (mLock) {
            removeSelfLocked();
        }
    }

    void removeSelfLocked() {
        if (sVerbose) Slog.v(TAG, "removeSelfLocked()");
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#removeSelfLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        destroyLocked();
        mService.removeSessionLocked(id);
    }

    private int getLastResponseIndex() {
        // The response ids are monotonically increasing so
        // we just find the largest id which is the last. We
        // do not rely on the internal ordering in sparse
        // array to avoid - wow this stopped working!?
        int lastResponseIdx = -1;
        int lastResponseId = -1;
        final int responseCount = mResponses.size();
        for (int i = 0; i < responseCount; i++) {
            if (mResponses.keyAt(i) > lastResponseId) {
                lastResponseIdx = i;
            }
        }
        return lastResponseIdx;
    }
}
