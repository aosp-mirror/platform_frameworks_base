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

import static android.view.autofill.AutofillManager.FLAG_MANUAL_REQUEST;
import static android.view.autofill.AutofillManager.FLAG_START_SESSION;
import static android.view.autofill.AutofillManager.FLAG_VALUE_CHANGED;
import static android.view.autofill.AutofillManager.FLAG_VIEW_ENTERED;
import static android.view.autofill.AutofillManager.FLAG_VIEW_EXITED;

import static com.android.server.autofill.Helper.DEBUG;
import static com.android.server.autofill.Helper.VERBOSE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.AutofillOverlay;
import android.app.assist.AssistStructure.ViewNode;
import android.app.assist.AssistStructure.WindowNode;
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
import android.provider.Settings;
import android.service.autofill.AutofillService;
import android.service.autofill.Dataset;
import android.service.autofill.FillResponse;
import android.service.autofill.SaveInfo;
import android.util.ArrayMap;
import android.util.DebugUtils;
import android.util.Slog;
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
import com.android.server.autofill.ui.AutoFillUI;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Map;
import java.util.Map.Entry;

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
// TODO(b/33197203): make sure sessions are removed (and tested by CTS):
// - On all authentication scenarios.
// - When user does not interact back after a while.
// - When service is unbound.
final class Session implements RemoteFillService.FillServiceCallbacks, ViewState.Listener,
        AutoFillUI.AutoFillUiCallback {
    private static final String TAG = "AutofillSession";

    private final AutofillManagerServiceImpl mService;
    private final IBinder mActivityToken;
    private final IBinder mWindowToken;
    private final HandlerCaller mHandlerCaller;
    private final Object mLock;
    private final AutoFillUI mUi;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    /** Package name of the app that is auto-filled */
    @NonNull private final String mPackageName;

    @GuardedBy("mLock")
    private final ArrayMap<AutofillId, ViewState> mViewStates = new ArrayMap<>();

    /**
     * Id of the View currently being displayed.
     */
    @GuardedBy("mLock")
    @Nullable private AutofillId mCurrentViewId;

    private final IAutoFillManagerClient mClient;

    @GuardedBy("mLock")
    RemoteFillService mRemoteFillService;

    @GuardedBy("mLock")
    private ArrayList<FillResponse> mResponses;

    /**
     * Response that requires a service authentitcation request.
     */
    @GuardedBy("mLock")
    private FillResponse mResponseWaitingAuth;

    /**
     * Dataset that when tapped launched a service authentication request.
     */
    @GuardedBy("mLock")
    private Dataset mDatasetWaitingAuth;

    /**
     * Assist structure sent by the app; it will be updated (sanitized, change values for save)
     * before sent to {@link AutofillService}.
     */
    @GuardedBy("mLock")
    private AssistStructure mStructure;

    /**
     * Whether the client has an {@link android.view.autofill.AutofillManager.AutofillCallback}.
     */
    private boolean mHasCallback;

    /**
     * Extras sent by service on {@code onFillRequest()} calls; the first non-null extra is saved
     * and used on subsequent {@code onFillRequest()} and {@code onSaveRequest()} calls.
     */
    @GuardedBy("mLock")
    private Bundle mExtras;

    /**
     * Flags used to start the session.
     */
    int mFlags;

    Session(@NonNull AutofillManagerServiceImpl service, @NonNull AutoFillUI ui,
            @NonNull Context context, @NonNull HandlerCaller handlerCaller, int userId,
            @NonNull Object lock, @NonNull IBinder activityToken,
            @Nullable IBinder windowToken, @NonNull IBinder client, boolean hasCallback,
            int flags, @NonNull ComponentName componentName, @NonNull String packageName) {
        mService = service;
        mLock = lock;
        mUi = ui;
        mHandlerCaller = handlerCaller;
        mRemoteFillService = new RemoteFillService(context, componentName, userId, this);
        mActivityToken = activityToken;
        mWindowToken = windowToken;
        mHasCallback = hasCallback;
        mPackageName = packageName;
        mFlags = flags;

        mClient = IAutoFillManagerClient.Stub.asInterface(client);
        try {
            client.linkToDeath(() -> {
                if (VERBOSE) {
                    Slog.v(TAG, "app binder died");
                }

                removeSelf();
            }, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "linkToDeath() on mClient failed: " + e);
        }

        mMetricsLogger.action(MetricsEvent.AUTOFILL_SESSION_STARTED, mPackageName);
    }

    // FillServiceCallbacks
    @Override
    public void onFillRequestSuccess(@Nullable FillResponse response,
            @NonNull String servicePackageName) {
        if (response == null) {
            if ((mFlags & FLAG_MANUAL_REQUEST) != 0) {
                getUiForShowing().showError(R.string.autofill_error_cannot_autofill);
            }
            // Nothing to be done, but need to notify client.
            notifyUnavailableToClient();
            removeSelf();
            return;
        }

        if ((response.getDatasets() == null || response.getDatasets().isEmpty())
                        && response.getAuthentication() == null) {
            // Response is "empty" from an UI point of view, need to notify client.
            notifyUnavailableToClient();
        }
        synchronized (mLock) {
            if (response.getAuthentication() != null) {
                // TODO(b/33197203 , b/35707731): make sure it's ignored if there is one already
                mResponseWaitingAuth = response;
            }
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
        LogMaker log = (new LogMaker(MetricsEvent.AUTOFILL_REQUEST))
                .setType(MetricsEvent.TYPE_FAILURE)
                .setPackageName(mPackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName);
        mMetricsLogger.write(log);

        getUiForShowing().showError(message);
        removeSelf();
    }

    // FillServiceCallbacks
    @Override
    public void onSaveRequestSuccess(@NonNull String servicePackageName) {
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
        LogMaker log = (new LogMaker(
                MetricsEvent.AUTOFILL_DATA_SAVE_REQUEST))
                .setType(MetricsEvent.TYPE_FAILURE)
                .setPackageName(mPackageName)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE, servicePackageName);
        mMetricsLogger.write(log);

        getUiForShowing().showError(message);
        removeSelf();
    }

    // FillServiceCallbacks
    @Override
    public void authenticate(IntentSender intent, Bundle extras) {
        final Intent fillInIntent;
        synchronized (mLock) {
            fillInIntent = createAuthFillInIntent(mStructure, extras);
        }
        mHandlerCaller.getHandler().post(() -> startAuthentication(intent, fillInIntent));
    }

    // FillServiceCallbacks
    @Override
    public void onDisableSelf() {
        mService.disableSelf();
        synchronized (mLock) {
            removeSelfLocked();
        }
    }

    // FillServiceCallbacks
    @Override
    public void onServiceDied(RemoteFillService service) {
        // TODO(b/33197203): implement
    }

    // AutoFillUiCallback
    @Override
    public void fill(Dataset dataset) {
        mHandlerCaller.getHandler().post(() -> autoFill(dataset));
    }

    // AutoFillUiCallback
    @Override
    public void save() {
        mHandlerCaller.getHandler()
                .obtainMessage(AutofillManagerServiceImpl.MSG_SERVICE_SAVE, mActivityToken)
                .sendToTarget();
    }

    // AutoFillUiCallback
    @Override
    public void cancelSave() {
        mHandlerCaller.getHandler().post(() -> removeSelf());
    }

    // AutoFillUiCallback
    @Override
    public void requestShowFillUi(AutofillId id, int width, int height,
            IAutofillWindowPresenter presenter) {
        synchronized (mLock) {
            if (id.equals(mCurrentViewId)) {
                try {
                    final ViewState view = mViewStates.get(id);
                    mClient.requestShowFillUi(mWindowToken, id, width, height,
                            view.getVirtualBounds(),
                            presenter);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error requesting to show fill UI", e);
                }
            } else {
                if (DEBUG) {
                    Slog.d(TAG, "Do not show full UI on " + id + " as it is not the current view ("
                            + mCurrentViewId + ") anymore");
                }
            }
        }
    }

    // AutoFillUiCallback
    @Override
    public void requestHideFillUi(AutofillId id) {
        try {
            mClient.requestHideFillUi(mWindowToken, id);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error requesting to hide fill UI", e);
        }
    }

    public void setAuthenticationResultLocked(Bundle data) {
        if ((mResponseWaitingAuth == null && mDatasetWaitingAuth == null) || data == null) {
            removeSelf();
        } else {
            final Parcelable result = data.getParcelable(
                    AutofillManager.EXTRA_AUTHENTICATION_RESULT);
            if (result instanceof FillResponse) {
                mMetricsLogger.action(MetricsEvent.AUTOFILL_AUTHENTICATED, mPackageName);
                mResponseWaitingAuth = null;
                processResponseLocked((FillResponse) result);
            } else if (result instanceof Dataset) {
                final Dataset dataset = (Dataset) result;
                for (int i = 0; i < mResponses.size(); i++) {
                    final FillResponse response = mResponses.get(i);
                    final int index = response.getDatasets().indexOf(mDatasetWaitingAuth);
                    if (index >= 0) {
                        response.getDatasets().set(index, dataset);
                        mDatasetWaitingAuth = null;
                        autoFill(dataset);
                        resetViewStatesLocked(dataset, ViewState.STATE_WAITING_DATASET_AUTH);
                        return;
                    }
                }
            }
        }
    }

    public void setHasCallback(boolean hasIt) {
        mHasCallback = hasIt;
    }

    public void setStructureLocked(AssistStructure structure) {
        mStructure = structure;
    }

    /**
     * Shows the save UI, when session can be saved.
     *
     * @return {@code true} if session is done, or {@code false} if it's pending user action.
     */
    public boolean showSaveLocked() {
        if (mStructure == null) {
            Slog.wtf(TAG, "showSaveLocked(): no mStructure");
            return true;
        }
        if (mResponses == null) {
            // Happens when the activity / session was finished before the service replied, or
            // when the service cannot autofill it (and returned a null response).
            if (DEBUG) {
                Slog.d(TAG, "showSaveLocked(): no responses on session");
            }
            return true;
        }

        // TODO(b/33197203 , b/35707731): must iterate over all responses
        final FillResponse response = mResponses.get(0);

        final SaveInfo saveInfo = response.getSaveInfo();
        if (DEBUG) {
            Slog.d(TAG, "showSaveLocked(): saveInfo=" + saveInfo);
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
                if (DEBUG) {
                    Slog.d(TAG, "showSaveLocked(): empty value for required " + id );
                }
                allRequiredAreNotEmpty = false;
                break;
            }
            final AutofillValue filledValue = viewState.getAutofilledValue();

            if (!currentValue.equals(filledValue)) {
                if (DEBUG) {
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
                            if (DEBUG) {
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
                getUiForShowing().showSaveUi(mService.getServiceLabel(), saveInfo, mPackageName);
                return false;
            }
        }
        // Nothing changed...
        if (DEBUG) {
            Slog.d(TAG, "showSaveLocked(): with no changes, comes no responsibilities."
                    + "allRequiredAreNotNull=" + allRequiredAreNotEmpty
                    + ", atLeastOneChanged=" + atLeastOneChanged);
        }
        return true;
    }

    /**
     * Calls service when user requested save.
     */
    void callSaveLocked() {
        if (DEBUG) {
            Slog.d(TAG, "callSaveLocked(): mViewStates=" + mViewStates);
        }

        for (Entry<AutofillId, ViewState> entry : mViewStates.entrySet()) {
            final AutofillValue value = entry.getValue().getCurrentValue();
            if (value == null) {
                if (VERBOSE) {
                    Slog.v(TAG, "callSaveLocked(): skipping " + entry.getKey());
                }
                continue;
            }
            final AutofillId id = entry.getKey();
            final ViewNode node = findViewNodeByIdLocked(id);
            if (node == null) {
                Slog.w(TAG, "callSaveLocked(): did not find node with id " + id);
                continue;
            }
            if (VERBOSE) {
                Slog.v(TAG, "callSaveLocked(): updating " + id + " to " + value);
            }

            node.updateAutofillValue(value);
        }

        // Sanitize structure before it's sent to service.
        mStructure.sanitizeForParceling(false);

        if (VERBOSE) {
            Slog.v(TAG, "Dumping " + mStructure + " before calling service.save()");
            mStructure.dump();
        }

        mRemoteFillService.onSaveRequest(mStructure, mExtras);
    }

    void updateLocked(AutofillId id, Rect virtualBounds, AutofillValue value, int flags) {
        ViewState viewState = mViewStates.get(id);

        if (viewState == null) {
            if ((flags & (FLAG_START_SESSION | FLAG_VALUE_CHANGED)) != 0) {
                if (DEBUG) {
                    Slog.d(TAG, "Creating viewState for " + id + " on " + getFlagAsString(flags));
                }
                viewState = new ViewState(this, id, value, this, ViewState.STATE_INITIAL);
                mViewStates.put(id, viewState);
            } else if ((flags & FLAG_VIEW_ENTERED) != 0) {
                viewState = startPartitionLocked(id, value);
            } else {
                if (VERBOSE) Slog.v(TAG, "Ignored " + getFlagAsString(flags) + " for " + id);
                return;
            }
        }

        if ((flags & FLAG_START_SESSION) != 0) {
            // View is triggering autofill.
            mCurrentViewId = viewState.id;
            viewState.update(value, virtualBounds);
            viewState.setState(ViewState.STATE_STARTED_SESSION);
            return;
        }

        if ((flags & FLAG_VALUE_CHANGED) != 0) {
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
                    getUiForShowing().filterFillUi(value.getTextValue().toString());
                } else {
                    getUiForShowing().filterFillUi(null);
                }
            }

            return;
        }

        if ((flags & FLAG_VIEW_ENTERED) != 0) {
            // Remove the UI if the ViewState has changed.
            if (mCurrentViewId != viewState.id) {
                mUi.hideFillUi(mCurrentViewId != null ? mCurrentViewId : null);
                mCurrentViewId = viewState.id;
            }

            // If the ViewState is ready to be displayed, onReady() will be called.
            viewState.update(value, virtualBounds);

            return;
        }

        if ((flags & FLAG_VIEW_EXITED) != 0) {
            if (mCurrentViewId == viewState.id) {
                mUi.hideFillUi(viewState.id);
                mCurrentViewId = null;
            }
            return;
        }

        Slog.w(TAG, "updateLocked(): unknown flags " + flags + ": " + getFlagAsString(flags));
    }

    private ViewState startPartitionLocked(AutofillId id, AutofillValue value) {
        if (DEBUG) {
            Slog.d(TAG, "Starting partition for view id " + id);
        }
        final ViewState newViewState =
                new ViewState(this, id, value, this,ViewState.STATE_STARTED_PARTITION);
        mViewStates.put(id, newViewState);

        // Must update value of nodes so:
        // - proper node is focused
        // - autofillValue is sent back to service when it was previously autofilled
        for (int i = 0; i < mViewStates.size(); i++) {
            final ViewState viewState = mViewStates.valueAt(i);

            final ViewNode node = findViewNodeByIdLocked(viewState.id);
            if (node == null) {
                Slog.w(TAG, "startPartitionLocked(): no node for " + viewState.id);
                continue;
            }

            final AutofillValue initialValue = viewState.getInitialValue();
            final AutofillValue filledValue = viewState.getAutofilledValue();
            final AutofillOverlay overlay = new AutofillOverlay();
            if (filledValue != null && !filledValue.equals(initialValue)) {
                overlay.value = filledValue;
            }
            overlay.focused = id.equals(viewState.id);
            node.setAutofillOverlay(overlay);
        }
        mRemoteFillService.onFillRequest(mStructure, mExtras, 0);

        return newViewState;
    }

    @Override
    public void onFillReady(FillResponse response, AutofillId filledId,
            @Nullable AutofillValue value) {
        String filterText = null;
        if (value != null && value.isText()) {
            filterText = value.getTextValue().toString();
        }

        getUiForShowing().showFillUi(filledId, response, filterText, mPackageName);
    }

    String getFlagAsString(int flag) {
        return DebugUtils.flagsToString(AutofillManager.class, "FLAG_", flag);
    }

    private void notifyUnavailableToClient() {
        if (mCurrentViewId == null) {
            // TODO(b/33197203): temporary sanity check; should never happen
            Slog.w(TAG, "notifyUnavailable(): mCurrentViewId is null");
            return;
        }
        if (!mHasCallback) return;
        try {
            mClient.notifyNoFillUi(mWindowToken, mCurrentViewId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error notifying client no fill UI: windowToken=" + mWindowToken
                    + " id=" + mCurrentViewId, e);
        }
    }

    private void processResponseLocked(FillResponse response) {
        if (DEBUG) {
            Slog.d(TAG, "processResponseLocked(mCurrentViewId=" + mCurrentViewId + "):" + response);
        }

        if (mCurrentViewId == null) {
            // TODO(b/33197203): temporary sanity check; should never happen
            Slog.w(TAG, "processResponseLocked(): mCurrentViewId is null");
            return;
        }

        if (mResponses == null) {
            mResponses = new ArrayList<>(4);
        }
        mResponses.add(response);
        if (response != null) {
            mExtras = response.getExtras();
        }

        setViewStatesLocked(response, ViewState.STATE_FILLABLE);

        if ((mFlags & FLAG_MANUAL_REQUEST) != 0 && response.getDatasets() != null
                && response.getDatasets().size() == 1) {
            Slog.d(TAG, "autofilling manual request directly");
            autoFill(response.getDatasets().get(0));
            return;
        }

        // Updates the UI, if necessary.
        final ViewState currentView = mViewStates.get(mCurrentViewId);
        currentView.maybeCallOnFillReady();
    }

    /**
     * Sets the state of all views in the given response.
     */
    private void setViewStatesLocked(FillResponse response, int state) {
        final ArrayList<Dataset> datasets = response.getDatasets();
        if (datasets != null) {
            for (int i = 0; i < datasets.size(); i++) {
                final Dataset dataset = datasets.get(i);
                if (dataset == null) {
                    Slog.w(TAG, "Ignoring null dataset on " + datasets);
                    continue;
                }
                setViewStatesLocked(response, dataset, state);
            }
        }
    }

    /**
     * Sets the state of all views in the given dataset and response.
     */
    private void setViewStatesLocked(@Nullable FillResponse response, @NonNull Dataset dataset,
            int state) {
        final ArrayList<AutofillId> ids = dataset.getFieldIds();
        final ArrayList<AutofillValue> values = dataset.getFieldValues();
        for (int j = 0; j < ids.size(); j++) {
            final AutofillId id = ids.get(j);
            ViewState viewState = mViewStates.get(id);
            if (viewState != null)  {
                viewState.setState(state);
            } else {
                viewState = new ViewState(this, id, null, this, state);
                if (DEBUG) { // TODO(b/33197203): change to VERBOSE once stable
                    Slog.d(TAG, "Adding autofillable view with id " + id + " and state " + state);
                }
                mViewStates.put(id, viewState);
            }
            if ((state & ViewState.STATE_AUTOFILLED) != 0) {
                viewState.setAutofilledValue(values.get(j));
            }

            if (response != null) {
                viewState.setResponse(response);
            }
        }
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

    void autoFill(Dataset dataset) {
        synchronized (mLock) {
            // Autofill it directly...
            if (dataset.getAuthentication() == null) {
                autoFillApp(dataset);
                return;
            }

            // ...or handle authentication.
            // TODO(b/33197203 , b/35707731): make sure it's ignored if there is one already
            mDatasetWaitingAuth = dataset;
            setViewStatesLocked(null, dataset, ViewState.STATE_WAITING_DATASET_AUTH);
            final Intent fillInIntent = createAuthFillInIntent(mStructure, null);
            startAuthentication(dataset.getAuthentication(), fillInIntent);
        }
    }

    CharSequence getServiceName() {
        return mService.getServiceName();
    }

    FillResponse getResponseWaitingAuth() {
        return mResponseWaitingAuth;
    }

    private Intent createAuthFillInIntent(AssistStructure structure, Bundle extras) {
        final Intent fillInIntent = new Intent();
        fillInIntent.putExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, structure);
        if (extras != null) {
            fillInIntent.putExtra(AutofillManager.EXTRA_DATA_EXTRAS, extras);
        }
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
        pw.print(prefix); pw.print("mFlags: "); pw.println(mFlags);
        pw.print(prefix); pw.print("mResponses: "); pw.println(mResponses);
        pw.print(prefix); pw.print("mResponseWaitingAuth: "); pw.println(mResponseWaitingAuth);
        pw.print(prefix); pw.print("mDatasetWaitingAuth: "); pw.println(mDatasetWaitingAuth);
        pw.print(prefix); pw.print("mCurrentViewId: "); pw.println(mCurrentViewId);
        pw.print(prefix); pw.print("mViewStates size: "); pw.println(mViewStates.size());
        final String prefix2 = prefix + "  ";
        for (Map.Entry<AutofillId, ViewState> entry : mViewStates.entrySet()) {
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
        pw.print(prefix); pw.print("mHasCallback: "); pw.println(mHasCallback);
        pw.print(prefix); pw.print("mExtras: "); pw.println(Helper.bundleToString(mExtras));
        mRemoteFillService.dump(prefix, pw);
    }

    void autoFillApp(Dataset dataset) {
        synchronized (mLock) {
            try {
                if (DEBUG) {
                    Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                }
                mClient.autofill(mWindowToken, dataset.getFieldIds(), dataset.getFieldValues());
                setViewStatesLocked(null, dataset, ViewState.STATE_AUTOFILLED);
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

    private ViewNode findViewNodeByIdLocked(AutofillId id) {
        final int size = mStructure.getWindowNodeCount();
        for (int i = 0; i < size; i++) {
            final WindowNode window = mStructure.getWindowNodeAt(i);
            final ViewNode root = window.getRootViewNode();
            if (id.equals(root.getAutofillId())) {
                return root;
            }
            final ViewNode child = findViewNodeByIdLocked(root, id);
            if (child != null) {
                return child;
            }
        }
        return null;
    }

    private ViewNode findViewNodeByIdLocked(ViewNode parent, AutofillId id) {
        final int childrenSize = parent.getChildCount();
        if (childrenSize > 0) {
            for (int i = 0; i < childrenSize; i++) {
                final ViewNode child = parent.getChildAt(i);
                if (id.equals(child.getAutofillId())) {
                    return child;
                }
                final ViewNode grandChild = findViewNodeByIdLocked(child, id);
                if (grandChild != null && id.equals(grandChild.getAutofillId())) {
                    return grandChild;
                }
            }
        }
        return null;
    }

    void destroyLocked() {
        mRemoteFillService.destroy();
        mUi.setCallback(null);
        mMetricsLogger.action(MetricsEvent.AUTOFILL_SESSION_FINISHED, mPackageName);
    }

    void removeSelf() {
        synchronized (mLock) {
            removeSelfLocked();
        }
    }

    void removeSelfLocked() {
        if (VERBOSE) {
            Slog.v(TAG, "removeSelfLocked()");
        }
        destroyLocked();
        mService.removeSessionLocked(mActivityToken);
    }
}
