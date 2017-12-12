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
import android.os.SystemClock;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.InternalValidator;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.service.autofill.ValueFinder;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
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
import com.android.server.autofill.ui.PendingUi;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
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

    /** Whether the session is currently saving. */
    @GuardedBy("mLock")
    private boolean mIsSaving;

    /**
     * Helper used to handle state of Save UI when it must be hiding to show a custom description
     * link and later recovered.
     */
    @GuardedBy("mLock")
    private PendingUi mPendingSaveUi;

    /**
     * When the session started (using elapsed time since boot).
     */
    private final long mStartTime;

    /**
     * When the UI was shown for the first time (using elapsed time since boot).
     */
    @GuardedBy("mLock")
    private long mUiShownTime;

    @GuardedBy("mLock")
    private final LocalLog mUiLatencyHistory;

    /**
     * Receiver of assist data from the app's {@link Activity}.
     */
    private final IResultReceiver mAssistReceiver = new IResultReceiver.Stub() {
        @Override
        public void send(int resultCode, Bundle resultData) throws RemoteException {
            final AssistStructure structure = resultData.getParcelable(KEY_STRUCTURE);
            if (structure == null) {
                Slog.e(TAG, "No assist structure - app might have crashed providing it");
                return;
            }

            final Bundle receiverExtras = resultData.getBundle(KEY_RECEIVER_EXTRAS);
            if (receiverExtras == null) {
                Slog.e(TAG, "No receiver extras - app might have crashed providing it");
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
                    fillContextWithAllowedValuesLocked(mContexts.get(i), flags);
                }

                // Dispatch a snapshot of the current contexts list since it may change
                // until the dispatch happens. The items in the list don't need to be cloned
                // since we don't hold on them anywhere else. The client state is not touched
                // by us, so no need to copy.
                request = new FillRequest(requestId, new ArrayList<>(mContexts),
                        mClientState, flags);
            }

            mRemoteFillService.onFillRequest(request);
        }
    };

    /**
     * Returns the ids of all entries in {@link #mViewStates} in the same order.
     */
    private AutofillId[] getIdsOfAllViewStatesLocked() {
        final int numViewState = mViewStates.size();
        final AutofillId[] ids = new AutofillId[numViewState];
        for (int i = 0; i < numViewState; i++) {
            ids[i] = mViewStates.valueAt(i).id;
        }

        return ids;
    }

    /**
     * Gets the value of a field, using either the {@code viewStates} or the {@code mContexts}, or
     * {@code null} when not found on either of them.
     */
    @Nullable
    private String getValueAsString(@NonNull AutofillId id) {
        AutofillValue value = null;
        synchronized (mLock) {
            final ViewState state = mViewStates.get(id);
            if (state == null) {
                if (sDebug) Slog.d(TAG, "getValue(): no view state for " + id);
                return null;
            }
            value = state.getCurrentValue();
            if (value == null) {
                if (sDebug) Slog.d(TAG, "getValue(): no current value for " + id);
                value = getValueFromContextsLocked(id);
            }
        }
        if (value != null) {
            if (value.isText()) {
                return value.getTextValue().toString();
            }
            if (value.isList()) {
                final CharSequence[] options = getAutofillOptionsFromContextsLocked(id);
                if (options != null) {
                    final int index = value.getListValue();
                    final CharSequence option = options[index];
                    return option != null ? option.toString() : null;
                } else {
                    Slog.w(TAG, "getValueAsString(): no autofill options for id " + id);
                }
            }
        }
        return null;
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
    private void fillContextWithAllowedValuesLocked(@NonNull FillContext fillContext, int flags) {
        final ViewNode[] nodes = fillContext
                .findViewNodesByAutofillIds(getIdsOfAllViewStatesLocked());

        final int numViewState = mViewStates.size();
        for (int i = 0; i < numViewState; i++) {
            final ViewState viewState = mViewStates.valueAt(i);

            final ViewNode node = nodes[i];
            if (node == null) {
                if (sVerbose) {
                    Slog.v(TAG, "fillStructureWithAllowedValues(): no node for " + viewState.id);
                }
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
        final int canceledRequest = mRemoteFillService.cancelCurrentRequest();

        // Remove the FillContext as there will never be a response for the service
        if (canceledRequest != INVALID_REQUEST_ID && mContexts != null) {
            final int numContexts = mContexts.size();

            // It is most likely the last context, hence search backwards
            for (int i = numContexts - 1; i >= 0; i--) {
                if (mContexts.get(i).getRequestId() == canceledRequest) {
                    if (sDebug) Slog.d(TAG, "cancelCurrentRequest(): id = " + canceledRequest);
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
            @NonNull IBinder client, boolean hasCallback, @NonNull LocalLog uiLatencyHistory,
            @NonNull ComponentName componentName, @NonNull String packageName) {
        id = sessionId;
        this.uid = uid;
        mStartTime = SystemClock.elapsedRealtime();
        mService = service;
        mLock = lock;
        mUi = ui;
        mHandlerCaller = handlerCaller;
        mRemoteFillService = new RemoteFillService(context, componentName, userId, this);
        mActivityToken = activityToken;
        mHasCallback = hasCallback;
        mUiLatencyHistory = uiLatencyHistory;
        mPackageName = packageName;
        mClient = IAutoFillManagerClient.Stub.asInterface(client);

        writeLog(MetricsEvent.AUTOFILL_SESSION_STARTED);
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
            processNullResponseLocked(requestFlags);
            return;
        }

        mService.setLastResponse(serviceUid, id, response);

        if ((response.getDatasets() == null || response.getDatasets().isEmpty())
                        && response.getAuthentication() == null) {
            // Response is "empty" from an UI point of view, need to notify client.
            notifyUnavailableToClient(false);
        }
        synchronized (mLock) {
            processResponseLocked(response, requestFlags);
        }

        final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_REQUEST, servicePackageName)
                .setType(MetricsEvent.TYPE_SUCCESS)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS,
                        response.getDatasets() == null ? 0 : response.getDatasets().size());
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
            mService.resetLastResponse();
        }
        LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_REQUEST, servicePackageName)
                .setType(MetricsEvent.TYPE_FAILURE);
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
        LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_DATA_SAVE_REQUEST, servicePackageName)
                .setType(MetricsEvent.TYPE_SUCCESS);
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
        LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_DATA_SAVE_REQUEST, servicePackageName)
                .setType(MetricsEvent.TYPE_FAILURE);
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
        if (sDebug) {
            Slog.d(TAG, "authenticate(): requestId=" + requestId + "; datasetIdx=" + datasetIndex
                    + "; intentSender=" + intent);
        }
        final Intent fillInIntent;
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#authenticate() rejected - session: "
                        + id + " destroyed");
                return;
            }
            fillInIntent = createAuthFillInIntentLocked(requestId, extras);
            if (fillInIntent == null) {
                forceRemoveSelfLocked();
                return;
            }
        }
        mService.setAuthenticationSelected(id);

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
        mHandlerCaller.getHandler().post(() -> autoFill(requestId, datasetIndex, dataset, true));
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
                    mClient.startIntentSender(intentSender, null);
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
        if (mResponses == null) {
            // Typically happens when app explicitly called cancel() while the service was showing
            // the auth UI.
            Slog.w(TAG, "setAuthenticationResultLocked(" + authenticationId + "): no responses");
            removeSelf();
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
        }

        final Parcelable result = data.getParcelable(AutofillManager.EXTRA_AUTHENTICATION_RESULT);
        if (sDebug) Slog.d(TAG, "setAuthenticationResultLocked(): result=" + result);
        if (result instanceof FillResponse) {
            writeLog(MetricsEvent.AUTOFILL_AUTHENTICATED);
            replaceResponseLocked(authenticatedResponse, (FillResponse) result);
        } else if (result instanceof Dataset) {
            if (datasetIdx != AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED) {
                writeLog(MetricsEvent.AUTOFILL_DATASET_AUTHENTICATED);
                final Dataset dataset = (Dataset) result;
                authenticatedResponse.getDatasets().set(datasetIdx, dataset);
                autoFill(requestId, datasetIdx, dataset, false);
            } else {
                writeLog(MetricsEvent.AUTOFILL_INVALID_DATASET_AUTHENTICATION);
            }
        } else {
            if (result != null) {
                Slog.w(TAG, "service returned invalid auth type: " + result);
            }
            writeLog(MetricsEvent.AUTOFILL_INVALID_AUTHENTICATION);
            processNullResponseLocked(0);
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

    @Nullable
    private FillResponse getLastResponseLocked(@Nullable String logPrefix) {
        if (mContexts == null) {
            if (sDebug && logPrefix != null) Slog.d(TAG, logPrefix + ": no contexts");
            return null;
        }
        if (mResponses == null) {
            // Happens when the activity / session was finished before the service replied, or
            // when the service cannot autofill it (and returned a null response).
            if (sVerbose && logPrefix != null) {
                Slog.v(TAG, logPrefix + ": no responses on session");
            }
            return null;
        }

        final int lastResponseIdx = getLastResponseIndexLocked();
        if (lastResponseIdx < 0) {
            if (logPrefix != null) {
                Slog.w(TAG, logPrefix + ": did not get last response. mResponses=" + mResponses
                        + ", mViewStates=" + mViewStates);
            }
            return null;
        }

        final FillResponse response = mResponses.valueAt(lastResponseIdx);
        if (sVerbose && logPrefix != null) {
            Slog.v(TAG, logPrefix + ": mResponses=" + mResponses + ", mContexts=" + mContexts
                    + ", mViewStates=" + mViewStates);
        }
        return response;
    }

    @Nullable
    private SaveInfo getSaveInfoLocked() {
        final FillResponse response = getLastResponseLocked(null);
        return response == null ? null : response.getSaveInfo();
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
        final FillResponse response = getLastResponseLocked("showSaveLocked()");
        final SaveInfo saveInfo = response == null ? null : response.getSaveInfo();

        /*
         * The Save dialog is only shown if all conditions below are met:
         *
         * - saveInfo is not null.
         * - autofillValue of all required ids is not null.
         * - autofillValue of at least one id (required or optional) has changed.
         * - there is no Dataset in the last FillResponse whose values of all dataset fields matches
         *   the current values of all fields in the screen.
         */
        if (saveInfo == null) {
            return true;
        }

        // Cache used to make sure changed fields do not belong to a dataset.
        final ArrayMap<AutofillId, AutofillValue> currentValues = new ArrayMap<>();
        final ArraySet<AutofillId> allIds = new ArraySet<>();

        final AutofillId[] requiredIds = saveInfo.getRequiredIds();
        boolean allRequiredAreNotEmpty = true;
        boolean atLeastOneChanged = false;
        if (requiredIds != null) {
            for (int i = 0; i < requiredIds.length; i++) {
                final AutofillId id = requiredIds[i];
                if (id == null) {
                    Slog.w(TAG, "null autofill id on " + Arrays.toString(requiredIds));
                    continue;
                }
                allIds.add(id);
                final ViewState viewState = mViewStates.get(id);
                if (viewState == null) {
                    Slog.w(TAG, "showSaveLocked(): no ViewState for required " + id);
                    allRequiredAreNotEmpty = false;
                    break;
                }

                AutofillValue value = viewState.getCurrentValue();
                if (value == null || value.isEmpty()) {
                    final AutofillValue initialValue = getValueFromContextsLocked(id);
                    if (initialValue != null) {
                        if (sDebug) {
                            Slog.d(TAG, "Value of required field " + id + " didn't change; "
                                    + "using initial value (" + initialValue + ") instead");
                        }
                        value = initialValue;
                    } else {
                        if (sDebug) {
                            Slog.d(TAG, "empty value for required " + id );
                        }
                        allRequiredAreNotEmpty = false;
                        break;
                    }
                }
                currentValues.put(id, value);
                final AutofillValue filledValue = viewState.getAutofilledValue();

                if (!value.equals(filledValue)) {
                    if (sDebug) {
                        Slog.d(TAG, "found a change on required " + id + ": " + filledValue
                                + " => " + value);
                    }
                    atLeastOneChanged = true;
                }
            }
        }

        final AutofillId[] optionalIds = saveInfo.getOptionalIds();
        if (allRequiredAreNotEmpty) {
            if (!atLeastOneChanged && optionalIds != null) {
                // No change on required ids yet, look for changes on optional ids.
                for (int i = 0; i < optionalIds.length; i++) {
                    final AutofillId id = optionalIds[i];
                    allIds.add(id);
                    final ViewState viewState = mViewStates.get(id);
                    if (viewState == null) {
                        Slog.w(TAG, "no ViewState for optional " + id);
                        continue;
                    }
                    if ((viewState.getState() & ViewState.STATE_CHANGED) != 0) {
                        final AutofillValue currentValue = viewState.getCurrentValue();
                        currentValues.put(id, currentValue);
                        final AutofillValue filledValue = viewState.getAutofilledValue();
                        if (currentValue != null && !currentValue.equals(filledValue)) {
                            if (sDebug) {
                                Slog.d(TAG, "found a change on optional " + id + ": " + filledValue
                                        + " => " + currentValue);
                            }
                            atLeastOneChanged = true;
                            break;
                        }
                    } else {
                        // Update current values cache based on initial value
                        final AutofillValue initialValue = getValueFromContextsLocked(id);
                        if (sDebug) {
                            Slog.d(TAG, "no current value for " + id + "; initial value is "
                                    + initialValue);
                        }
                        if (initialValue != null) {
                            currentValues.put(id, initialValue);
                        }
                    }
                }
            }
            if (atLeastOneChanged) {
                if (sDebug) {
                    Slog.d(TAG, "at least one field changed, validate fields for save UI");
                }
                final ValueFinder valueFinder = (id) -> {return getValueAsString(id);};

                final InternalValidator validator = saveInfo.getValidator();
                if (validator != null) {
                    final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_SAVE_VALIDATION);
                    boolean isValid;
                    try {
                        isValid = validator.isValid(valueFinder);
                        log.setType(isValid
                                ? MetricsEvent.TYPE_SUCCESS
                                : MetricsEvent.TYPE_DISMISS);
                    } catch (Exception e) {
                        Slog.e(TAG, "Not showing save UI because validation failed:", e);
                        log.setType(MetricsEvent.TYPE_FAILURE);
                        mMetricsLogger.write(log);
                        return true;
                    }

                    mMetricsLogger.write(log);
                    if (!isValid) {
                        Slog.i(TAG, "not showing save UI because fields failed validation");
                        return true;
                    }
                }

                // Make sure the service doesn't have the fields already by checking the datasets
                // content.
                final List<Dataset> datasets = response.getDatasets();
                if (datasets != null) {
                    datasets_loop: for (int i = 0; i < datasets.size(); i++) {
                        final Dataset dataset = datasets.get(i);
                        final ArrayMap<AutofillId, AutofillValue> datasetValues =
                                Helper.getFields(dataset);
                        if (sVerbose) {
                            Slog.v(TAG, "Checking if saved fields match contents of dataset #" + i
                                    + ": " + dataset + "; allIds=" + allIds);
                        }
                        for (int j = 0; j < allIds.size(); j++) {
                            final AutofillId id = allIds.valueAt(j);
                            final AutofillValue currentValue = currentValues.get(id);
                            if (currentValue == null) {
                                if (sDebug) {
                                    Slog.d(TAG, "dataset has value for field that is null: " + id);
                                }
                                continue datasets_loop;
                            }
                            final AutofillValue datasetValue = datasetValues.get(id);
                            if (!currentValue.equals(datasetValue)) {
                                if (sDebug) Slog.d(TAG, "found a change on id " + id);
                                continue datasets_loop;
                            }
                            if (sVerbose) Slog.v(TAG, "no changes for id " + id);
                        }
                        if (sDebug) {
                            Slog.d(TAG, "ignoring Save UI because all fields match contents of "
                                    + "dataset #" + i + ": " + dataset);
                        }
                        return true;
                    }
                }

                if (sDebug) Slog.d(TAG, "Good news, everyone! All checks passed, show save UI!");
                mService.logSaveShown(id);
                final IAutoFillManagerClient client = getClient();
                mPendingSaveUi = new PendingUi(mActivityToken, id, client);
                getUiForShowing().showSaveUi(mService.getServiceLabel(), mService.getServiceIcon(),
                        mService.getServicePackageName(), saveInfo, valueFinder, mPackageName, this,
                        mPendingSaveUi);
                if (client != null) {
                    try {
                        client.setSaveUiState(id, true);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error notifying client to set save UI state to shown: " + e);
                    }
                }
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
     * Gets the latest non-empty value for the given id in the autofill contexts.
     */
    @Nullable
    private AutofillValue getValueFromContextsLocked(AutofillId id) {
        final int numContexts = mContexts.size();
        for (int i = numContexts - 1; i >= 0; i--) {
            final FillContext context = mContexts.get(i);
            final ViewNode node = context.findViewNodeByAutofillId(id);
            if (node != null) {
                final AutofillValue value = node.getAutofillValue();
                if (sDebug) {
                    Slog.d(TAG, "getValueFromContexts(" + id + ") at " + i + ": " + value);
                }
                if (value != null && !value.isEmpty()) {
                    return value;
                }
            }
        }
        return null;
    }

    /**
     * Gets the latest autofill options for the given id in the autofill contexts.
     */
    @Nullable
    private CharSequence[] getAutofillOptionsFromContextsLocked(AutofillId id) {
        final int numContexts = mContexts.size();

        for (int i = numContexts - 1; i >= 0; i--) {
            final FillContext context = mContexts.get(i);
            final ViewNode node = context.findViewNodeByAutofillId(id);
            if (node != null && node.getAutofillOptions() != null) {
                return node.getAutofillOptions();
            }
        }
        return null;
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

        if (mContexts == null) {
            Slog.w(TAG, "callSaveLocked(): no contexts");
            return;
        }

        final int numContexts = mContexts.size();

        for (int contextNum = 0; contextNum < numContexts; contextNum++) {
            final FillContext context = mContexts.get(contextNum);

            final ViewNode[] nodes =
                context.findViewNodesByAutofillIds(getIdsOfAllViewStatesLocked());

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

        // Dispatch a snapshot of the current contexts list since it may change
        // until the dispatch happens. The items in the list don't need to be cloned
        // since we don't hold on them anywhere else. The client state is not touched
        // by us, so no need to copy.
        final SaveRequest saveRequest = new SaveRequest(new ArrayList<>(mContexts), mClientState);
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

            final List<Dataset> datasets = response.getDatasets();
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
                viewState = new ViewState(this, id, this,
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
                viewState.update(value, virtualBounds, flags);
                viewState.setState(ViewState.STATE_STARTED_SESSION);
                requestNewFillResponseLocked(flags);
                break;
            case ACTION_VALUE_CHANGED:
                if (value != null && !value.equals(viewState.getCurrentValue())) {
                    if (value.isEmpty()
                            && viewState.getCurrentValue() != null
                            && viewState.getCurrentValue().isText()
                            && viewState.getCurrentValue().getTextValue() != null
                            && getSaveInfoLocked() != null) {
                        final int length = viewState.getCurrentValue().getTextValue().length();
                        if (sDebug) {
                            Slog.d(TAG, "updateLocked(" + id + "): resetting value that was "
                                    + length + " chars long");
                        }
                        final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_VALUE_RESET)
                                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_PREVIOUS_LENGTH, length);
                        mMetricsLogger.write(log);
                    }

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
                    mUi.hideFillUi(this);
                    mCurrentViewId = viewState.id;
                }

                // If the ViewState is ready to be displayed, onReady() will be called.
                viewState.update(value, virtualBounds, flags);
                break;
            case ACTION_VIEW_EXITED:
                if (mCurrentViewId == viewState.id) {
                    if (sVerbose) Slog.d(TAG, "Exiting view " + id);
                    mUi.hideFillUi(this);
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

        getUiForShowing().showFillUi(filledId, response, filterText,
                mService.getServicePackageName(), mPackageName, this);

        synchronized (mLock) {
            if (mUiShownTime == 0) {
                // Log first time UI is shown.
                mUiShownTime = SystemClock.elapsedRealtime();
                final long duration = mUiShownTime - mStartTime;
                if (sDebug) {
                    final StringBuilder msg = new StringBuilder("1st UI for ")
                            .append(mActivityToken)
                            .append(" shown in ");
                    TimeUtils.formatDuration(duration, msg);
                    Slog.d(TAG, msg.toString());
                }
                final StringBuilder historyLog = new StringBuilder("id=").append(id)
                        .append(" app=").append(mActivityToken)
                        .append(" svc=").append(mService.getServicePackageName())
                        .append(" latency=");
                TimeUtils.formatDuration(duration, historyLog);
                mUiLatencyHistory.log(historyLog.toString());

                final LogMaker metricsLog = newLogMaker(MetricsEvent.AUTOFILL_UI_LATENCY)
                        .setCounterValue((int) duration);
                mMetricsLogger.write(metricsLog);
            }
        }
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

    private void notifyUnavailableToClient(boolean sessionFinished) {
        synchronized (mLock) {
            if (mCurrentViewId == null) return;
            try {
                if (mHasCallback) {
                    mClient.notifyNoFillUi(id, mCurrentViewId, sessionFinished);
                } else if (sessionFinished) {
                    mClient.setSessionFinished(AutofillManager.STATE_FINISHED);
                }
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
        final FillResponse response = mResponses.valueAt(getLastResponseIndexLocked());

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

        final List<Dataset> datasets = response.getDatasets();
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
        processResponseLocked(newResponse, 0);
    }

    private void processNullResponseLocked(int flags) {
        if (sVerbose) Slog.v(TAG, "canceling session " + id + " when server returned null");
        if ((flags & FLAG_MANUAL_REQUEST) != 0) {
            getUiForShowing().showError(R.string.autofill_error_cannot_autofill, this);
        }
        mService.resetLastResponse();
        // Nothing to be done, but need to notify client.
        notifyUnavailableToClient(true);
        removeSelf();
    }

    private void processResponseLocked(@NonNull FillResponse newResponse, int flags) {
        // Make sure we are hiding the UI which will be shown
        // only if handling the current response requires it.
        mUi.hideAll(this);

        final int requestId = newResponse.getRequestId();
        if (sVerbose) {
            Slog.v(TAG, "processResponseLocked(): mCurrentViewId=" + mCurrentViewId
                    + ",flags=" + flags + ", reqId=" + requestId + ", resp=" + newResponse);
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

        // Updates the UI, if necessary.
        final ViewState currentView = mViewStates.get(mCurrentViewId);
        currentView.maybeCallOnFillReady(flags);
    }

    /**
     * Sets the state of all views in the given response.
     */
    private void setViewStatesLocked(FillResponse response, int state, boolean clearResponse) {
        final List<Dataset> datasets = response.getDatasets();
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
            if (requiredIds != null) {
                for (AutofillId id : requiredIds) {
                    createOrUpdateViewStateLocked(id, state, null);
                }
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
            viewState = new ViewState(this, id, this, state);
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

    void autoFill(int requestId, int datasetIndex, Dataset dataset, boolean generateEvent) {
        if (sDebug) {
            Slog.d(TAG, "autoFill(): requestId=" + requestId  + "; datasetIdx=" + datasetIndex
                    + "; dataset=" + dataset);
        }
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#autoFill() rejected - session: "
                        + id + " destroyed");
                return;
            }
            // Autofill it directly...
            if (dataset.getAuthentication() == null) {
                if (generateEvent) {
                    mService.logDatasetSelected(dataset.getId(), id);
                }

                autoFillApp(dataset);
                return;
            }

            // ...or handle authentication.
            mService.logDatasetAuthenticationSelected(dataset.getId(), id);
            setViewStatesLocked(null, dataset, ViewState.STATE_WAITING_DATASET_AUTH, false);
            final Intent fillInIntent = createAuthFillInIntentLocked(requestId, mClientState);
            if (fillInIntent == null) {
                forceRemoveSelfLocked();
                return;
            }
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

    // TODO: this should never be null, but we got at least one occurrence, probably due to a race.
    @Nullable
    private Intent createAuthFillInIntentLocked(int requestId, Bundle extras) {
        final Intent fillInIntent = new Intent();

        final FillContext context = getFillContextByRequestIdLocked(requestId);
        if (context == null) {
            Slog.wtf(TAG, "createAuthFillInIntentLocked(): no FillContext. requestId=" + requestId
                    + "; mContexts= " + mContexts);
            return null;
        }
        fillInIntent.putExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, context.getStructure());
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

    @Override
    public String toString() {
        return "Session: [id=" + id + ", pkg=" + mPackageName + "]";
    }

    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";
        pw.print(prefix); pw.print("id: "); pw.println(id);
        pw.print(prefix); pw.print("uid: "); pw.println(uid);
        pw.print(prefix); pw.print("mPackagename: "); pw.println(mPackageName);
        pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken);
        pw.print(prefix); pw.print("mStartTime: "); pw.println(mStartTime);
        pw.print(prefix); pw.print("Time to show UI: ");
        if (mUiShownTime == 0) {
            pw.println("N/A");
        } else {
            TimeUtils.formatDuration(mUiShownTime - mStartTime, pw);
            pw.println();
        }
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
        pw.print(prefix); pw.print("mPendingSaveUi: "); pw.println(mPendingSaveUi);
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
                // Skip null values as a null values means no change
                final int entryCount = dataset.getFieldIds().size();
                final List<AutofillId> ids = new ArrayList<>(entryCount);
                final List<AutofillValue> values = new ArrayList<>(entryCount);
                boolean waitingDatasetAuth = false;
                for (int i = 0; i < entryCount; i++) {
                    if (dataset.getFieldValues().get(i) == null) {
                        continue;
                    }
                    final AutofillId viewId = dataset.getFieldIds().get(i);
                    ids.add(viewId);
                    values.add(dataset.getFieldValues().get(i));
                    final ViewState viewState = mViewStates.get(viewId);
                    if (viewState != null
                            && (viewState.getState() & ViewState.STATE_WAITING_DATASET_AUTH) != 0) {
                        if (sVerbose) {
                            Slog.v(TAG, "autofillApp(): view " + viewId + " waiting auth");
                        }
                        waitingDatasetAuth = true;
                        viewState.resetState(ViewState.STATE_WAITING_DATASET_AUTH);
                    }
                }
                if (!ids.isEmpty()) {
                    if (waitingDatasetAuth) {
                        mUi.hideFillUi(this);
                    }
                    if (sDebug) Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);

                    mClient.autofill(id, ids, values);
                    setViewStatesLocked(null, dataset, ViewState.STATE_AUTOFILLED, false);
                }
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

    /**
     * Cleans up this session.
     *
     * <p>Typically called in 2 scenarios:
     *
     * <ul>
     *   <li>When the session naturally finishes (i.e., from {@link #removeSelfLocked()}.
     *   <li>When the service hosting the session is finished (for example, because the user
     *       disabled it).
     * </ul>
     */
    RemoteFillService destroyLocked() {
        if (mDestroyed) {
            return null;
        }
        mUi.destroyAll(mPendingSaveUi, this, true);
        mUi.clearCallback(this);
        mDestroyed = true;
        writeLog(MetricsEvent.AUTOFILL_SESSION_FINISHED);
        return mRemoteFillService;
    }

    /**
     * Cleans up this session and remove it from the service always, even if it does have a pending
     * Save UI.
     */
    void forceRemoveSelfLocked() {
        if (sVerbose) Slog.v(TAG, "forceRemoveSelfLocked(): " + mPendingSaveUi);

        final boolean isPendingSaveUi = isSaveUiPendingLocked();
        mPendingSaveUi = null;
        removeSelfLocked();
        mUi.destroyAll(mPendingSaveUi, this, false);
        if (!isPendingSaveUi) {
            try {
                mClient.setSessionFinished(AutofillManager.STATE_UNKNOWN);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client to finish session", e);
            }
        }
    }

    /**
     * Thread-safe version of {@link #removeSelfLocked()}.
     */
    private void removeSelf() {
        synchronized (mLock) {
            removeSelfLocked();
        }
    }

    /**
     * Cleans up this session and remove it from the service, but but only if it does not have a
     * pending Save UI.
     */
    void removeSelfLocked() {
        if (sVerbose) Slog.v(TAG, "removeSelfLocked(): " + mPendingSaveUi);
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#removeSelfLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        if (isSaveUiPendingLocked()) {
            Slog.i(TAG, "removeSelfLocked() ignored, waiting for pending save ui");
            return;
        }

        final RemoteFillService remoteFillService = destroyLocked();
        mService.removeSessionLocked(id);
        if (remoteFillService != null) {
            remoteFillService.destroy();
        }
    }

    void onPendingSaveUi(int operation, @NonNull IBinder token) {
        getUiForShowing().onPendingSaveUi(operation, token);
    }

    /**
     * Checks whether this session is hiding the Save UI to handle a custom description link for
     * a specific {@code token} created by
     * {@link PendingUi#PendingUi(IBinder, int, IAutoFillManagerClient)}.
     */
    boolean isSaveUiPendingForTokenLocked(@NonNull IBinder token) {
        return isSaveUiPendingLocked() && token.equals(mPendingSaveUi.getToken());
    }

    /**
     * Checks whether this session is hiding the Save UI to handle a custom description link.
     */
    private boolean isSaveUiPendingLocked() {
        return mPendingSaveUi != null && mPendingSaveUi.getState() == PendingUi.STATE_PENDING;
    }

    private int getLastResponseIndexLocked() {
        // The response ids are monotonically increasing so
        // we just find the largest id which is the last. We
        // do not rely on the internal ordering in sparse
        // array to avoid - wow this stopped working!?
        int lastResponseIdx = -1;
        int lastResponseId = -1;
        if (mResponses != null) {
            final int responseCount = mResponses.size();
            for (int i = 0; i < responseCount; i++) {
                if (mResponses.keyAt(i) > lastResponseId) {
                    lastResponseIdx = i;
                }
            }
        }
        return lastResponseIdx;
    }

    private LogMaker newLogMaker(int category) {
        return newLogMaker(category, mService.getServicePackageName());
    }

    private LogMaker newLogMaker(int category, String servicePackageName) {
        return Helper.newLogMaker(category, mPackageName, servicePackageName);
    }

    private void writeLog(int category) {
        mMetricsLogger.write(newLogMaker(category));
    }
}
