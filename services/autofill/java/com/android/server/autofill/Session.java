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
import static com.android.server.autofill.Helper.findValue;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.assist.AssistStructure;
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
import android.util.Slog;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import android.view.autofill.IAutofillWindowPresenter;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.HandlerCaller;
import com.android.server.autofill.ui.AutoFillUI;

import java.io.PrintWriter;
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
    private final Map<AutofillId, ViewState> mViewStates = new ArrayMap<>();

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
     * before sent to {@link AutofillService}.
     */
    @GuardedBy("mLock") AssistStructure mStructure;

    /**
     * Whether the client has an {@link android.view.autofill.AutofillManager.AutofillCallback}.
     */
    private boolean mHasCallback;

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
                if (DEBUG) {
                    Slog.d(TAG, "app binder died");
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
            processResponseLocked(response);
        }

        LogMaker log = (new LogMaker(MetricsEvent.AUTOFILL_REQUEST))
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
        try {
            mClient.requestShowFillUi(mWindowToken, id, width, height,
                    mCurrentViewState.mVirtualBounds, presenter);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error requesting to show fill UI", e);
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
        if (mCurrentResponse == null || data == null) {
            removeSelf();
        } else {
            final Parcelable result = data.getParcelable(
                    AutofillManager.EXTRA_AUTHENTICATION_RESULT);
            if (result instanceof FillResponse) {
                mMetricsLogger.action(MetricsEvent.AUTOFILL_AUTHENTICATED, mPackageName);

                mCurrentResponse = (FillResponse) result;
                processResponseLocked(mCurrentResponse);
            } else if (result instanceof Dataset) {
                final Dataset dataset = (Dataset) result;
                final int index = mCurrentResponse.getDatasets().indexOf(mAutoFilledDataset);
                if (index >= 0) {
                    mCurrentResponse.getDatasets().set(index, dataset);
                    autoFill(dataset);
                }
            }
        }
    }

    public void setHasCallback(boolean hasIt) {
        mHasCallback = hasIt;
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
        if (mCurrentResponse == null) {
            // Happens when the activity / session was finished before the service replied, or
            // when the service cannot autofill it (and returned a null response).
            if (DEBUG) {
                Slog.d(TAG, "showSaveLocked(): no mCurrentResponse");
            }
            return true;
        }
        final SaveInfo saveInfo = mCurrentResponse.getSaveInfo();
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
            final ViewState state = mViewStates.get(id);
            if (state == null || state.mAutofillValue == null
                     || state.mAutofillValue.isEmpty()) {
                final ViewNode node = findViewNodeByIdLocked(id);
                if (node == null) {
                    Slog.w(TAG, "Service passed invalid id on SavableInfo: " + id);
                    allRequiredAreNotEmpty = false;
                    break;
                }
                final AutofillValue initialValue = node.getAutofillValue();
                if (initialValue == null || initialValue.isEmpty()) {
                    if (DEBUG) {
                        Slog.d(TAG, "finishSessionLocked(): empty initial value for " + id );
                    }
                    allRequiredAreNotEmpty = false;
                    break;
                }
            }
            if (state.mValueUpdated) {
                final AutofillValue filledValue = findValue(mAutoFilledDataset, id);
                if (!state.mAutofillValue.equals(filledValue)) {
                    if (DEBUG) {
                        Slog.d(TAG, "finishSessionLocked(): found a change on " + id + ": "
                                + filledValue + " => " + state.mAutofillValue);
                    }
                    atLeastOneChanged = true;
                }
            } else {
                if (state.mAutofillValue == null || state.mAutofillValue.isEmpty()) {
                    if (DEBUG) {
                        Slog.d(TAG, "finishSessionLocked(): empty value for " + id + ": "
                                + state.mAutofillValue);
                    }
                    allRequiredAreNotEmpty = false;
                    break;

                }
            }
        }

        if (allRequiredAreNotEmpty) {
            if (!atLeastOneChanged && saveInfo.getOptionalIds() != null) {
                for (int i = 0; i < saveInfo.getOptionalIds().length; i++) {
                    final AutofillId id = saveInfo.getOptionalIds()[i];
                    final ViewState state = mViewStates.get(id);
                    if (state != null && state.mAutofillValue != null && state.mValueUpdated) {
                        final AutofillValue filledValue = findValue(mAutoFilledDataset, id);
                        if (!state.mAutofillValue.equals(filledValue)) {
                            if (DEBUG) {
                                Slog.d(TAG, "finishSessionLocked(): found a change on optional "
                                        + id + ": " + filledValue + " => "
                                        + state.mAutofillValue);
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

        final Bundle extras = this.mCurrentResponse.getExtras();

        for (Entry<AutofillId, ViewState> entry : mViewStates.entrySet()) {
            final AutofillValue value = entry.getValue().mAutofillValue;
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

        mRemoteFillService.onSaveRequest(mStructure, extras);
    }

    void updateLocked(AutofillId id, Rect virtualBounds, AutofillValue value, int flags) {
        if (mAutoFilledDataset != null && (flags & FLAG_VALUE_CHANGED) == 0) {
            // TODO(b/33197203): ignoring because we don't support partitions yet
            Slog.d(TAG, "updateLocked(): ignoring " + flags + " after app was autofilled");
            return;
        }

        ViewState viewState = mViewStates.get(id);
        if (viewState == null) {
            viewState = new ViewState(this, id, this);
            mViewStates.put(id, viewState);
        }

        if ((flags & FLAG_START_SESSION) != 0) {
            // View is triggering autofill.
            mCurrentViewState = viewState;
            viewState.update(value, virtualBounds);
            return;
        }

        if ((flags & FLAG_VALUE_CHANGED) != 0) {
            if (value != null && !value.equals(viewState.mAutofillValue)) {
                viewState.mValueUpdated = true;

                // Must check if this update was caused by autofilling the view, in which
                // case we just update the value, but not the UI.
                if (mAutoFilledDataset != null) {
                    final AutofillValue filledValue = findValue(mAutoFilledDataset, id);
                    if (value.equals(filledValue)) {
                        viewState.mAutofillValue = value;
                        return;
                    }
                }

                // Change value
                viewState.mAutofillValue = value;

                // Update the chooser UI
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
            if (mCurrentViewState != viewState) {
                mUi.hideFillUi(mCurrentViewState != null ? mCurrentViewState.mId : null);
                mCurrentViewState = viewState;
            }

            // If the ViewState is ready to be displayed, onReady() will be called.
            viewState.update(value, virtualBounds);

            // TODO(b/33197203): Remove when there is a response per activity.
            if (mCurrentResponse != null) {
                viewState.setResponse(mCurrentResponse);
            }

            return;
        }

        if ((flags & FLAG_VIEW_EXITED) != 0) {
            if (mCurrentViewState == viewState) {
                mUi.hideFillUi(viewState.mId);
                mCurrentViewState = null;
            }
            return;
        }

        Slog.w(TAG, "updateLocked(): unknown flags " + flags);
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

    private void notifyUnavailableToClient() {
        if (mCurrentViewState == null) {
            // TODO(b/33197203): temporary sanity check; should never happen
            Slog.w(TAG, "notifyUnavailable(): mCurrentViewState is null");
            return;
        }
        if (!mHasCallback) return;
        try {
            mClient.notifyNoFillUi(mWindowToken, mCurrentViewState.mId);
        } catch (RemoteException e) {
            Slog.e(TAG, "Error notifying client no fill UI: windowToken=" + mWindowToken
                    + " id=" + mCurrentViewState.mId, e);
        }
    }

    private void processResponseLocked(FillResponse response) {
        if (DEBUG) {
            Slog.d(TAG, "processResponseLocked(auth=" + response.getAuthentication()
                + "):" + response);
        }

        if (mCurrentViewState == null) {
            // TODO(b/33197203): temporary sanity check; should never happen
            Slog.w(TAG, "processResponseLocked(): mCurrentViewState is null");
            return;
        }

        mCurrentResponse = response;

        if (mCurrentResponse.getAuthentication() != null) {
            // Handle authentication.
            final Intent fillInIntent = createAuthFillInIntent(mStructure,
                    mCurrentResponse.getExtras());
            mCurrentViewState.setResponse(mCurrentResponse, fillInIntent);
            return;
        }

        if ((mFlags & FLAG_MANUAL_REQUEST) != 0 && response.getDatasets() != null
                && response.getDatasets().size() == 1) {
            Slog.d(TAG, "autofilling manual request directly");
            autoFill(response.getDatasets().get(0));
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
            final Intent fillInIntent = createAuthFillInIntent(mStructure, null);
            startAuthentication(dataset.getAuthentication(), fillInIntent);
        }
    }

    CharSequence getServiceName() {
        return mService.getServiceName();
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
        pw.print(prefix); pw.print("mCurrentResponse: "); pw.println(mCurrentResponse);
        pw.print(prefix); pw.print("mAutoFilledDataset: "); pw.println(mAutoFilledDataset);
        pw.print(prefix); pw.print("mCurrentViewStates: "); pw.println(mCurrentViewState);
        pw.print(prefix); pw.print("mViewStates: "); pw.println(mViewStates.size());
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
        mRemoteFillService.dump(prefix, pw);
    }

    void autoFillApp(Dataset dataset) {
        synchronized (mLock) {
            try {
                if (DEBUG) {
                    Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                }
                mClient.autofill(mWindowToken, dataset.getFieldIds(), dataset.getFieldValues());
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