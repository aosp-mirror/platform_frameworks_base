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

package android.view.autofill;

import static android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST;
import static android.view.autofill.Helper.sDebug;
import static android.view.autofill.Helper.sVerbose;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.Parcelable;
import android.os.RemoteException;
import android.service.autofill.AutofillService;
import android.service.autofill.FillEventHistory;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * App entry point to the Autofill Framework.
 *
 * <p>It is safe to call into this from any thread.
 */
public final class AutofillManager {

    private static final String TAG = "AutofillManager";

    /**
     * Intent extra: The assist structure which captures the filled screen.
     *
     * <p>
     * Type: {@link android.app.assist.AssistStructure}
     */
    public static final String EXTRA_ASSIST_STRUCTURE =
            "android.view.autofill.extra.ASSIST_STRUCTURE";

    /**
     * Intent extra: The result of an authentication operation. It is
     * either a fully populated {@link android.service.autofill.FillResponse}
     * or a fully populated {@link android.service.autofill.Dataset} if
     * a response or a dataset is being authenticated respectively.
     *
     * <p>
     * Type: {@link android.service.autofill.FillResponse} or a
     * {@link android.service.autofill.Dataset}
     */
    public static final String EXTRA_AUTHENTICATION_RESULT =
            "android.view.autofill.extra.AUTHENTICATION_RESULT";

    /**
     * Intent extra: The optional extras provided by the
     * {@link android.service.autofill.AutofillService}.
     *
     * <p>For example, when the service responds to a {@link
     * android.service.autofill.FillCallback#onSuccess(android.service.autofill.FillResponse)} with
     * a {@code FillResponse} that requires authentication, the Intent that launches the
     * service authentication will contain the Bundle set by
     * {@link android.service.autofill.FillResponse.Builder#setClientState(Bundle)} on this extra.
     *
     * <p>
     * Type: {@link android.os.Bundle}
     */
    public static final String EXTRA_CLIENT_STATE =
            "android.view.autofill.extra.CLIENT_STATE";

    static final String SESSION_ID_TAG = "android:sessionId";
    static final String LAST_AUTOFILLED_DATA_TAG = "android:lastAutoFilledData";

    /** @hide */ public static final int ACTION_START_SESSION = 1;
    /** @hide */ public static final int ACTION_VIEW_ENTERED =  2;
    /** @hide */ public static final int ACTION_VIEW_EXITED = 3;
    /** @hide */ public static final int ACTION_VALUE_CHANGED = 4;


    /** @hide */ public static final int FLAG_ADD_CLIENT_ENABLED = 0x1;
    /** @hide */ public static final int FLAG_ADD_CLIENT_DEBUG = 0x2;
    /** @hide */ public static final int FLAG_ADD_CLIENT_VERBOSE = 0x4;

    /** Which bits in an authentication id are used for the dataset id */
    private static final int AUTHENTICATION_ID_DATASET_ID_MASK = 0xFFFF;
    /** How many bits in an authentication id are used for the dataset id */
    private static final int AUTHENTICATION_ID_DATASET_ID_SHIFT = 16;
    /** @hide The index for an undefined data set */
    public static final int AUTHENTICATION_ID_DATASET_ID_UNDEFINED = 0xFFFF;

    /**
     * Makes an authentication id from a request id and a dataset id.
     *
     * @param requestId The request id.
     * @param datasetId The dataset id.
     * @return The authentication id.
     * @hide
     */
    public static int makeAuthenticationId(int requestId, int datasetId) {
        return (requestId << AUTHENTICATION_ID_DATASET_ID_SHIFT)
                | (datasetId & AUTHENTICATION_ID_DATASET_ID_MASK);
    }

    /**
     * Gets the request id from an authentication id.
     *
     * @param authRequestId The authentication id.
     * @return The request id.
     * @hide
     */
    public static int getRequestIdFromAuthenticationId(int authRequestId) {
        return (authRequestId >> AUTHENTICATION_ID_DATASET_ID_SHIFT);
    }

    /**
     * Gets the dataset id from an authentication id.
     *
     * @param authRequestId The authentication id.
     * @return The dataset id.
     * @hide
     */
    public static int getDatasetIdFromAuthenticationId(int authRequestId) {
        return (authRequestId & AUTHENTICATION_ID_DATASET_ID_MASK);
    }

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    /**
     * There is currently no session running.
     * {@hide}
     */
    public static final int NO_SESSION = Integer.MIN_VALUE;

    private final IAutoFillManager mService;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private IAutoFillManagerClient mServiceClient;

    @GuardedBy("mLock")
    private AutofillCallback mCallback;

    private final Context mContext;

    @GuardedBy("mLock")
    private int mSessionId = NO_SESSION;

    @GuardedBy("mLock")
    private boolean mEnabled;

    /** If a view changes to this mapping the autofill operation was successful */
    @GuardedBy("mLock")
    @Nullable private ParcelableMap mLastAutofilledData;

    /** If view tracking is enabled, contains the tracking state */
    @GuardedBy("mLock")
    @Nullable private TrackedViews mTrackedViews;

    /** Views that are only tracked because they are fillable and could be anchoring the UI. */
    @GuardedBy("mLock")
    @Nullable private ArraySet<AutofillId> mFillableIds;

    /** @hide */
    public interface AutofillClient {
        /**
         * Asks the client to start an authentication flow.
         *
         * @param authenticationId A unique id of the authentication operation.
         * @param intent The authentication intent.
         * @param fillInIntent The authentication fill-in intent.
         */
        void autofillCallbackAuthenticate(int authenticationId, IntentSender intent,
                Intent fillInIntent);

        /**
         * Tells the client this manager has state to be reset.
         */
        void autofillCallbackResetableStateAvailable();

        /**
         * Request showing the autofill UI.
         *
         * @param anchor The real view the UI needs to anchor to.
         * @param width The width of the fill UI content.
         * @param height The height of the fill UI content.
         * @param virtualBounds The bounds of the virtual decendant of the anchor.
         * @param presenter The presenter that controls the fill UI window.
         * @return Whether the UI was shown.
         */
        boolean autofillCallbackRequestShowFillUi(@NonNull View anchor, int width, int height,
                @Nullable Rect virtualBounds, IAutofillWindowPresenter presenter);

        /**
         * Request hiding the autofill UI.
         *
         * @return Whether the UI was hidden.
         */
        boolean autofillCallbackRequestHideFillUi();

        /**
         * Checks if views are currently attached and visible.
         *
         * @return And array with {@code true} iff the view is attached or visible
         */
        @NonNull boolean[] getViewVisibility(@NonNull int[] viewId);

        /**
         * Checks is the client is currently visible as understood by autofill.
         *
         * @return {@code true} if the client is currently visible
         */
        boolean isVisibleForAutofill();

        /**
         * Finds views by traversing the hierarchies of the client.
         *
         * @param viewIds The accessibility ids of the views to find
         *
         * @return And array containing the views (empty if no views found).
         */
        @NonNull View[] findViewsByAccessibilityIdTraversal(@NonNull int[] viewIds);

        /**
         * Finds a view by traversing the hierarchies of the client.
         *
         * @param viewId The accessibility id of the views to find
         *
         * @return The view, or {@code null} if not found
         */
        @Nullable View findViewByAccessibilityIdTraversal(int viewId);
    }

    /**
     * @hide
     */
    public AutofillManager(Context context, IAutoFillManager service) {
        mContext = context;
        mService = service;
    }

    /**
     * Restore state after activity lifecycle
     *
     * @param savedInstanceState The state to be restored
     *
     * {@hide}
     */
    public void onCreate(Bundle savedInstanceState) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            mLastAutofilledData = savedInstanceState.getParcelable(LAST_AUTOFILLED_DATA_TAG);

            if (mSessionId != NO_SESSION) {
                Log.w(TAG, "New session was started before onCreate()");
                return;
            }

            mSessionId = savedInstanceState.getInt(SESSION_ID_TAG, NO_SESSION);

            if (mSessionId != NO_SESSION) {
                ensureServiceClientAddedIfNeededLocked();

                final AutofillClient client = getClientLocked();
                if (client != null) {
                    try {
                        final boolean sessionWasRestored = mService.restoreSession(mSessionId,
                                mContext.getActivityToken(), mServiceClient.asBinder());

                        if (!sessionWasRestored) {
                            Log.w(TAG, "Session " + mSessionId + " could not be restored");
                            mSessionId = NO_SESSION;
                        } else {
                            if (sDebug) {
                                Log.d(TAG, "session " + mSessionId + " was restored");
                            }

                            client.autofillCallbackResetableStateAvailable();
                        }
                    } catch (RemoteException e) {
                        Log.e(TAG, "Could not figure out if there was an autofill session", e);
                    }
                }
            }
        }
    }

    /**
     * Called once the client becomes visible.
     *
     * @see AutofillClient#isVisibleForAutofill()
     *
     * {@hide}
     */
    public void onVisibleForAutofill() {
        synchronized (mLock) {
            if (mEnabled && mSessionId != NO_SESSION && mTrackedViews != null) {
                mTrackedViews.onVisibleForAutofillLocked();
            }
        }
    }

    /**
     * Save state before activity lifecycle
     *
     * @param outState Place to store the state
     *
     * {@hide}
     */
    public void onSaveInstanceState(Bundle outState) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (mSessionId != NO_SESSION) {
                outState.putInt(SESSION_ID_TAG, mSessionId);
            }

            if (mLastAutofilledData != null) {
                outState.putParcelable(LAST_AUTOFILLED_DATA_TAG, mLastAutofilledData);
            }
        }
    }

    /**
     * Checks whether autofill is enabled for the current user.
     *
     * <p>Typically used to determine whether the option to explicitly request autofill should
     * be offered - see {@link #requestAutofill(View)}.
     *
     * @return whether autofill is enabled for the current user.
     */
    public boolean isEnabled() {
        if (!hasAutofillFeature()) {
            return false;
        }
        synchronized (mLock) {
            ensureServiceClientAddedIfNeededLocked();
            return mEnabled;
        }
    }

    /**
     * Should always be called from {@link AutofillService#getFillEventHistory()}.
     *
     * @hide
     */
    @Nullable public FillEventHistory getFillEventHistory() {
        try {
            return mService.getFillEventHistory();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Explicitly requests a new autofill context.
     *
     * <p>Normally, the autofill context is automatically started when autofillable views are
     * focused, but this method should be used in the cases where it must be explicitly requested,
     * like a view that provides a contextual menu allowing users to autofill the activity.
     *
     * @param view view requesting the new autofill context.
     */
    public void requestAutofill(@NonNull View view) {
        notifyViewEntered(view, FLAG_MANUAL_REQUEST);
    }

    /**
     * Explicitly requests a new autofill context for virtual views.
     *
     * <p>Normally, the autofill context is automatically started when autofillable views are
     * focused, but this method should be used in the cases where it must be explicitly requested,
     * like a virtual view that provides a contextual menu allowing users to autofill the activity.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the view.
     * @param bounds child boundaries, relative to the top window.
     */
    public void requestAutofill(@NonNull View view, int childId, @NonNull Rect bounds) {
        notifyViewEntered(view, childId, bounds, FLAG_MANUAL_REQUEST);
    }

    /**
     * Called when a {@link View} that supports autofill is entered.
     *
     * @param view {@link View} that was entered.
     */
    public void notifyViewEntered(@NonNull View view) {
        notifyViewEntered(view, 0);
    }

    private void notifyViewEntered(@NonNull View view, int flags) {
        if (!hasAutofillFeature()) {
            return;
        }
        AutofillCallback callback = null;
        synchronized (mLock) {
            ensureServiceClientAddedIfNeededLocked();

            if (!mEnabled) {
                if (mCallback != null) {
                    callback = mCallback;
                }
            } else {
                final AutofillId id = getAutofillId(view);
                final AutofillValue value = view.getAutofillValue();

                if (mSessionId == NO_SESSION) {
                    // Starts new session.
                    startSessionLocked(id, null, value, flags);
                } else {
                    // Update focus on existing session.
                    updateSessionLocked(id, null, value, ACTION_VIEW_ENTERED, flags);
                }
            }
        }

        if (callback != null) {
            mCallback.onAutofillEvent(view, AutofillCallback.EVENT_INPUT_UNAVAILABLE);
        }
    }

    /**
     * Called when a {@link View} that supports autofill is exited.
     *
     * @param view {@link View} that was exited.
     */
    public void notifyViewExited(@NonNull View view) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            ensureServiceClientAddedIfNeededLocked();

            if (mEnabled && mSessionId != NO_SESSION) {
                final AutofillId id = getAutofillId(view);

                // Update focus on existing session.
                updateSessionLocked(id, null, null, ACTION_VIEW_EXITED, 0);
            }
        }
    }

    /**
     * Called when a {@link View view's} visibility changes.
     *
     * @param view {@link View} that was exited.
     * @param isVisible visible if the view is visible in the view hierarchy.
     *
     * @hide
     */
    public void notifyViewVisibilityChange(@NonNull View view, boolean isVisible) {
        synchronized (mLock) {
            if (mEnabled && mSessionId != NO_SESSION) {
                if (!isVisible && mFillableIds != null) {
                    final AutofillId id = view.getAutofillId();
                    if (mFillableIds.contains(id)) {
                        if (sDebug) Log.d(TAG, "Hidding UI when view " + id + " became invisible");
                        requestHideFillUi(id, view);
                    }
                }
                if (mTrackedViews != null) {
                    mTrackedViews.notifyViewVisibilityChange(view, isVisible);
                }
            }
        }
    }

    /**
     * Called when a virtual view that supports autofill is entered.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the view.
     * @param bounds child boundaries, relative to the top window.
     */
    public void notifyViewEntered(@NonNull View view, int childId, @NonNull Rect bounds) {
        notifyViewEntered(view, childId, bounds, 0);
    }

    private void notifyViewEntered(View view, int childId, Rect bounds, int flags) {
        if (!hasAutofillFeature()) {
            return;
        }
        AutofillCallback callback = null;
        synchronized (mLock) {
            ensureServiceClientAddedIfNeededLocked();

            if (!mEnabled) {
                if (mCallback != null) {
                    callback = mCallback;
                }
            } else {
                final AutofillId id = getAutofillId(view, childId);

                if (mSessionId == NO_SESSION) {
                    // Starts new session.
                    startSessionLocked(id, bounds, null, flags);
                } else {
                    // Update focus on existing session.
                    updateSessionLocked(id, bounds, null, ACTION_VIEW_ENTERED, flags);
                }
            }
        }

        if (callback != null) {
            callback.onAutofillEvent(view, childId,
                    AutofillCallback.EVENT_INPUT_UNAVAILABLE);
        }
    }

    /**
     * Called when a virtual view that supports autofill is exited.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the view.
     */
    public void notifyViewExited(@NonNull View view, int childId) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            ensureServiceClientAddedIfNeededLocked();

            if (mEnabled && mSessionId != NO_SESSION) {
                final AutofillId id = getAutofillId(view, childId);

                // Update focus on existing session.
                updateSessionLocked(id, null, null, ACTION_VIEW_EXITED, 0);
            }
        }
    }

    /**
     * Called to indicate the value of an autofillable {@link View} changed.
     *
     * @param view view whose value changed.
     */
    public void notifyValueChanged(View view) {
        if (!hasAutofillFeature()) {
            return;
        }
        AutofillId id = null;
        boolean valueWasRead = false;
        AutofillValue value = null;

        synchronized (mLock) {
            // If the session is gone some fields might still be highlighted, hence we have to
            // remove the isAutofilled property even if no sessions are active.
            if (mLastAutofilledData == null) {
                view.setAutofilled(false);
            } else {
                id = getAutofillId(view);
                if (mLastAutofilledData.containsKey(id)) {
                    value = view.getAutofillValue();
                    valueWasRead = true;

                    if (Objects.equals(mLastAutofilledData.get(id), value)) {
                        view.setAutofilled(true);
                    } else {
                        view.setAutofilled(false);
                        mLastAutofilledData.remove(id);
                    }
                } else {
                    view.setAutofilled(false);
                }
            }

            if (!mEnabled || mSessionId == NO_SESSION) {
                return;
            }

            if (id == null) {
                id = getAutofillId(view);
            }

            if (!valueWasRead) {
                value = view.getAutofillValue();
            }

            updateSessionLocked(id, null, value, ACTION_VALUE_CHANGED, 0);
        }
    }

    /**
     * Called to indicate the value of an autofillable virtual {@link View} changed.
     *
     * @param view the {@link View} whose descendant is the virtual view.
     * @param childId id identifying the virtual child inside the parent view.
     * @param value new value of the child.
     */
    public void notifyValueChanged(View view, int childId, AutofillValue value) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (!mEnabled || mSessionId == NO_SESSION) {
                return;
            }

            final AutofillId id = getAutofillId(view, childId);
            updateSessionLocked(id, null, value, ACTION_VALUE_CHANGED, 0);
        }
    }

    /**
     * Called to indicate the current autofill context should be commited.
     *
     * <p>For example, when a virtual view is rendering an {@code HTML} page with a form, it should
     * call this method after the form is submitted and another page is rendered.
     */
    public void commit() {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (!mEnabled && mSessionId == NO_SESSION) {
                return;
            }

            finishSessionLocked();
        }
    }

    /**
     * Called to indicate the current autofill context should be cancelled.
     *
     * <p>For example, when a virtual view is rendering an {@code HTML} page with a form, it should
     * call this method if the user does not post the form but moves to another form in this page.
     */
    public void cancel() {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (!mEnabled && mSessionId == NO_SESSION) {
                return;
            }

            cancelSessionLocked();
        }
    }

    /** @hide */
    public void disableOwnedAutofillServices() {
        disableAutofillServices();
    }

    /**
     * If the app calling this API has enabled autofill services they
     * will be disabled.
     */
    public void disableAutofillServices() {
        if (!hasAutofillFeature()) {
            return;
        }
        try {
            mService.disableOwnedAutofillServices(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if the calling application provides a {@link AutofillService} that is
     * enabled for the current user, or {@code false} otherwise.
     */
    public boolean hasEnabledAutofillServices() {
        if (mService == null) return false;

        try {
            return mService.isServiceEnabled(mContext.getUserId(), mContext.getPackageName());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns {@code true} if Autofill is supported for this user.
     *
     * <p>Autofill is typically supported, but it could be unsupported in cases like:
     * <ol>
     *     <li>Low-end devices.
     *     <li>Device policy rules that forbid its usage.
     * </ol>
     */
    public boolean isAutofillSupported() {
        if (mService == null) return false;

        try {
            return mService.isServiceSupported(mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private AutofillClient getClientLocked() {
        if (mContext instanceof AutofillClient) {
            return (AutofillClient) mContext;
        }
        return null;
    }

    /** @hide */
    public void onAuthenticationResult(int authenticationId, Intent data) {
        if (!hasAutofillFeature()) {
            return;
        }
        // TODO: the result code is being ignored, so this method is not reliably
        // handling the cases where it's not RESULT_OK: it works fine if the service does not
        // set the EXTRA_AUTHENTICATION_RESULT extra, but it could cause weird results if the
        // service set the extra and returned RESULT_CANCELED...

        if (sDebug) Log.d(TAG, "onAuthenticationResult(): d=" + data);

        synchronized (mLock) {
            if (mSessionId == NO_SESSION || data == null) {
                return;
            }
            final Parcelable result = data.getParcelableExtra(EXTRA_AUTHENTICATION_RESULT);
            final Bundle responseData = new Bundle();
            responseData.putParcelable(EXTRA_AUTHENTICATION_RESULT, result);
            try {
                mService.setAuthenticationResult(responseData, mSessionId, authenticationId,
                        mContext.getUserId());
            } catch (RemoteException e) {
                Log.e(TAG, "Error delivering authentication result", e);
            }
        }
    }

    private static AutofillId getAutofillId(View view) {
        return new AutofillId(view.getAccessibilityViewId());
    }

    private static AutofillId getAutofillId(View parent, int childId) {
        return new AutofillId(parent.getAccessibilityViewId(), childId);
    }

    private void startSessionLocked(@NonNull AutofillId id, @NonNull Rect bounds,
            @NonNull AutofillValue value, int flags) {
        if (sVerbose) {
            Log.v(TAG, "startSessionLocked(): id=" + id + ", bounds=" + bounds + ", value=" + value
                    + ", flags=" + flags);
        }

        try {
            mSessionId = mService.startSession(mContext.getActivityToken(),
                    mServiceClient.asBinder(), id, bounds, value, mContext.getUserId(),
                    mCallback != null, flags, mContext.getOpPackageName());
            final AutofillClient client = getClientLocked();
            if (client != null) {
                client.autofillCallbackResetableStateAvailable();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void finishSessionLocked() {
        if (sVerbose) Log.v(TAG, "finishSessionLocked()");

        try {
            mService.finishSession(mSessionId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mTrackedViews = null;
        mSessionId = NO_SESSION;
    }

    private void cancelSessionLocked() {
        if (sVerbose) Log.v(TAG, "cancelSessionLocked()");

        try {
            mService.cancelSession(mSessionId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        resetSessionLocked();
    }

    private void resetSessionLocked() {
        mSessionId = NO_SESSION;
        mTrackedViews = null;
    }

    private void updateSessionLocked(AutofillId id, Rect bounds, AutofillValue value, int action,
            int flags) {
        if (sVerbose && action != ACTION_VIEW_EXITED) {
            Log.v(TAG, "updateSessionLocked(): id=" + id + ", bounds=" + bounds
                    + ", value=" + value + ", action=" + action + ", flags=" + flags);
        }

        boolean restartIfNecessary = (flags & FLAG_MANUAL_REQUEST) != 0;

        try {
            if (restartIfNecessary) {
                final int newId = mService.updateOrRestartSession(mContext.getActivityToken(),
                        mServiceClient.asBinder(), id, bounds, value, mContext.getUserId(),
                        mCallback != null, flags, mContext.getOpPackageName(), mSessionId, action);
                if (newId != mSessionId) {
                    if (sDebug) Log.d(TAG, "Session restarted: " + mSessionId + "=>" + newId);
                    mSessionId = newId;
                    final AutofillClient client = getClientLocked();
                    if (client != null) {
                        client.autofillCallbackResetableStateAvailable();
                    }
                }
            } else {
                mService.updateSession(mSessionId, id, bounds, value, action, flags,
                        mContext.getUserId());
            }

        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void ensureServiceClientAddedIfNeededLocked() {
        if (getClientLocked() == null) {
            return;
        }

        if (mServiceClient == null) {
            mServiceClient = new AutofillManagerClient(this);
            try {
                final int flags = mService.addClient(mServiceClient, mContext.getUserId());
                mEnabled = (flags & FLAG_ADD_CLIENT_ENABLED) != 0;
                sDebug = (flags & FLAG_ADD_CLIENT_DEBUG) != 0;
                sVerbose = (flags & FLAG_ADD_CLIENT_VERBOSE) != 0;
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    /**
     * Registers a {@link AutofillCallback} to receive autofill events.
     *
     * @param callback callback to receive events.
     */
    public void registerCallback(@Nullable AutofillCallback callback) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (callback == null) return;

            final boolean hadCallback = mCallback != null;
            mCallback = callback;

            if (!hadCallback) {
                try {
                    mService.setHasCallback(mSessionId, mContext.getUserId(), true);
                } catch (RemoteException e) {
                    throw e.rethrowFromSystemServer();
                }
            }
        }
    }

    /**
     * Unregisters a {@link AutofillCallback} to receive autofill events.
     *
     * @param callback callback to stop receiving events.
     */
    public void unregisterCallback(@Nullable AutofillCallback callback) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (callback == null || mCallback == null || callback != mCallback) return;

            mCallback = null;

            try {
                mService.setHasCallback(mSessionId, mContext.getUserId(), false);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    private void requestShowFillUi(int sessionId, AutofillId id, int width, int height,
            Rect anchorBounds, IAutofillWindowPresenter presenter) {
        final View anchor = findView(id);
        if (anchor == null) {
            return;
        }

        AutofillCallback callback = null;
        synchronized (mLock) {
            if (mSessionId == sessionId) {
                AutofillClient client = getClientLocked();

                if (client != null) {
                    if (client.autofillCallbackRequestShowFillUi(anchor, width, height,
                            anchorBounds, presenter) && mCallback != null) {
                        callback = mCallback;
                    }
                }
            }
        }

        if (callback != null) {
            if (id.isVirtual()) {
                callback.onAutofillEvent(anchor, id.getVirtualChildId(),
                        AutofillCallback.EVENT_INPUT_SHOWN);
            } else {
                callback.onAutofillEvent(anchor, AutofillCallback.EVENT_INPUT_SHOWN);
            }
        }
    }

    private void authenticate(int sessionId, int authenticationId, IntentSender intent,
            Intent fillInIntent) {
        synchronized (mLock) {
            if (sessionId == mSessionId) {
                AutofillClient client = getClientLocked();
                if (client != null) {
                    client.autofillCallbackAuthenticate(authenticationId, intent, fillInIntent);
                }
            }
        }
    }

    private void setState(boolean enabled, boolean resetSession, boolean resetClient) {
        synchronized (mLock) {
            mEnabled = enabled;
            if (!mEnabled || resetSession) {
                // Reset the session state
                resetSessionLocked();
            }
            if (resetClient) {
                // Reset connection to system
                mServiceClient = null;
            }
        }
    }

    /**
     * Sets a view as autofilled if the current value is the {code targetValue}.
     *
     * @param view The view that is to be autofilled
     * @param targetValue The value we want to fill into view
     */
    private void setAutofilledIfValuesIs(@NonNull View view, @Nullable AutofillValue targetValue) {
        AutofillValue currentValue = view.getAutofillValue();
        if (Objects.equals(currentValue, targetValue)) {
            synchronized (mLock) {
                if (mLastAutofilledData == null) {
                    mLastAutofilledData = new ParcelableMap(1);
                }
                mLastAutofilledData.put(getAutofillId(view), targetValue);
            }
            view.setAutofilled(true);
        }
    }

    private void autofill(int sessionId, List<AutofillId> ids, List<AutofillValue> values) {
        synchronized (mLock) {
            if (sessionId != mSessionId) {
                return;
            }

            final AutofillClient client = getClientLocked();
            if (client == null) {
                return;
            }

            final int itemCount = ids.size();
            int numApplied = 0;
            ArrayMap<View, SparseArray<AutofillValue>> virtualValues = null;
            final View[] views = client.findViewsByAccessibilityIdTraversal(getViewIds(ids));

            for (int i = 0; i < itemCount; i++) {
                final AutofillId id = ids.get(i);
                final AutofillValue value = values.get(i);
                final int viewId = id.getViewId();
                final View view = views[i];
                if (view == null) {
                    Log.w(TAG, "autofill(): no View with id " + viewId);
                    continue;
                }
                if (id.isVirtual()) {
                    if (virtualValues == null) {
                        // Most likely there will be just one view with virtual children.
                        virtualValues = new ArrayMap<>(1);
                    }
                    SparseArray<AutofillValue> valuesByParent = virtualValues.get(view);
                    if (valuesByParent == null) {
                        // We don't know the size yet, but usually it will be just a few fields...
                        valuesByParent = new SparseArray<>(5);
                        virtualValues.put(view, valuesByParent);
                    }
                    valuesByParent.put(id.getVirtualChildId(), value);
                } else {
                    // Mark the view as to be autofilled with 'value'
                    if (mLastAutofilledData == null) {
                        mLastAutofilledData = new ParcelableMap(itemCount - i);
                    }
                    mLastAutofilledData.put(id, value);

                    view.autofill(value);

                    // Set as autofilled if the values match now, e.g. when the value was updated
                    // synchronously.
                    // If autofill happens async, the view is set to autofilled in
                    // notifyValueChanged.
                    setAutofilledIfValuesIs(view, value);

                    numApplied++;
                }
            }

            if (virtualValues != null) {
                for (int i = 0; i < virtualValues.size(); i++) {
                    final View parent = virtualValues.keyAt(i);
                    final SparseArray<AutofillValue> childrenValues = virtualValues.valueAt(i);
                    parent.autofill(childrenValues);
                    numApplied += childrenValues.size();
                }
            }

            final LogMaker log = new LogMaker(MetricsProto.MetricsEvent.AUTOFILL_DATASET_APPLIED);
            log.addTaggedData(MetricsProto.MetricsEvent.FIELD_AUTOFILL_NUM_VALUES, itemCount);
            log.addTaggedData(MetricsProto.MetricsEvent.FIELD_AUTOFILL_NUM_VIEWS_FILLED,
                    numApplied);
            mMetricsLogger.write(log);
        }
    }

    /**
     *  Set the tracked views.
     *
     * @param trackedIds The views to be tracked
     * @param saveOnAllViewsInvisible Finish the session once all tracked views are invisible.
     * @param fillableIds Views that might anchor FillUI.
     */
    private void setTrackedViews(int sessionId, @Nullable AutofillId[] trackedIds,
            boolean saveOnAllViewsInvisible, @Nullable AutofillId[] fillableIds) {
        synchronized (mLock) {
            if (mEnabled && mSessionId == sessionId) {
                if (saveOnAllViewsInvisible) {
                    mTrackedViews = new TrackedViews(trackedIds);
                } else {
                    mTrackedViews = null;
                }
                if (fillableIds != null) {
                    if (mFillableIds == null) {
                        mFillableIds = new ArraySet<>(fillableIds.length);
                    }
                    for (AutofillId id : fillableIds) {
                        mFillableIds.add(id);
                    }
                    if (sVerbose) {
                        Log.v(TAG, "setTrackedViews(): fillableIds=" + fillableIds
                                + ", mFillableIds" + mFillableIds);
                    }
                }
            }
        }
    }

    private void requestHideFillUi(AutofillId id) {
        final View anchor = findView(id);
        if (sVerbose) Log.v(TAG, "requestHideFillUi(" + id + "): anchor = " + anchor);
        if (anchor == null) {
            return;
        }
        requestHideFillUi(id, anchor);
    }

    private void requestHideFillUi(AutofillId id, View anchor) {

        AutofillCallback callback = null;
        synchronized (mLock) {
            // We do not check the session id for two reasons:
            // 1. If local and remote session id are off sync the UI would be stuck shown
            // 2. There is a race between the user state being destroyed due the fill
            //    service being uninstalled and the UI being dismissed.
            AutofillClient client = getClientLocked();
            if (client != null) {
                if (client.autofillCallbackRequestHideFillUi() && mCallback != null) {
                    callback = mCallback;
                }
            }
        }

        if (callback != null) {
            if (id.isVirtual()) {
                callback.onAutofillEvent(anchor, id.getVirtualChildId(),
                        AutofillCallback.EVENT_INPUT_HIDDEN);
            } else {
                callback.onAutofillEvent(anchor, AutofillCallback.EVENT_INPUT_HIDDEN);
            }
        }
    }

    private void notifyNoFillUi(int sessionId, AutofillId id) {
        final View anchor = findView(id);
        if (anchor == null) {
            return;
        }

        AutofillCallback callback = null;
        synchronized (mLock) {
            if (mSessionId == sessionId && getClientLocked() != null) {
                callback = mCallback;
            }
        }

        if (callback != null) {
            if (id.isVirtual()) {
                callback.onAutofillEvent(anchor, id.getVirtualChildId(),
                        AutofillCallback.EVENT_INPUT_UNAVAILABLE);
            } else {
                callback.onAutofillEvent(anchor, AutofillCallback.EVENT_INPUT_UNAVAILABLE);
            }

        }
    }

    /**
     * Get an array of viewIds from a List of {@link AutofillId}.
     *
     * @param autofillIds The autofill ids to convert
     *
     * @return The array of viewIds.
     */
    // TODO: move to Helper as static method
    @NonNull private int[] getViewIds(@NonNull AutofillId[] autofillIds) {
        final int numIds = autofillIds.length;
        final int[] viewIds = new int[numIds];
        for (int i = 0; i < numIds; i++) {
            viewIds[i] = autofillIds[i].getViewId();
        }

        return viewIds;
    }

    // TODO: move to Helper as static method
    @NonNull private int[] getViewIds(@NonNull List<AutofillId> autofillIds) {
        final int numIds = autofillIds.size();
        final int[] viewIds = new int[numIds];
        for (int i = 0; i < numIds; i++) {
            viewIds[i] = autofillIds.get(i).getViewId();
        }

        return viewIds;
    }

    /**
     * Find a single view by its id.
     *
     * @param autofillId The autofill id of the view
     *
     * @return The view or {@code null} if view was not found
     */
    private View findView(@NonNull AutofillId autofillId) {
        final AutofillClient client = getClientLocked();

        if (client == null) {
            return null;
        }

        return client.findViewByAccessibilityIdTraversal(autofillId.getViewId());
    }

    /** @hide */
    public boolean hasAutofillFeature() {
        return mService != null;
    }

    /**
     * View tracking information. Once all tracked views become invisible the session is finished.
     */
    private class TrackedViews {
        /** Visible tracked views */
        @Nullable private ArraySet<AutofillId> mVisibleTrackedIds;

        /** Invisible tracked views */
        @Nullable private ArraySet<AutofillId> mInvisibleTrackedIds;

        /**
         * Check if set is null or value is in set.
         *
         * @param set   The set or null (== empty set)
         * @param value The value that might be in the set
         *
         * @return {@code true} iff set is not empty and value is in set
         */
        // TODO: move to Helper as static method
        private <T> boolean isInSet(@Nullable ArraySet<T> set, T value) {
            return set != null && set.contains(value);
        }

        /**
         * Add a value to a set. If set is null, create a new set.
         *
         * @param set        The set or null (== empty set)
         * @param valueToAdd The value to add
         *
         * @return The set including the new value. If set was {@code null}, a set containing only
         *         the new value.
         */
        // TODO: move to Helper as static method
        @NonNull
        private <T> ArraySet<T> addToSet(@Nullable ArraySet<T> set, T valueToAdd) {
            if (set == null) {
                set = new ArraySet<>(1);
            }

            set.add(valueToAdd);

            return set;
        }

        /**
         * Remove a value from a set.
         *
         * @param set           The set or null (== empty set)
         * @param valueToRemove The value to remove
         *
         * @return The set without the removed value. {@code null} if set was null, or is empty
         *         after removal.
         */
        // TODO: move to Helper as static method
        @Nullable
        private <T> ArraySet<T> removeFromSet(@Nullable ArraySet<T> set, T valueToRemove) {
            if (set == null) {
                return null;
            }

            set.remove(valueToRemove);

            if (set.isEmpty()) {
                return null;
            }

            return set;
        }

        /**
         * Set the tracked views.
         *
         * @param trackedIds The views to be tracked
         */
        TrackedViews(@Nullable AutofillId[] trackedIds) {
            final AutofillClient client = getClientLocked();
            if (trackedIds != null && client != null) {
                final boolean[] isVisible;

                if (client.isVisibleForAutofill()) {
                    isVisible = client.getViewVisibility(getViewIds(trackedIds));
                } else {
                    // All false
                    isVisible = new boolean[trackedIds.length];
                }

                final int numIds = trackedIds.length;
                for (int i = 0; i < numIds; i++) {
                    final AutofillId id = trackedIds[i];

                    if (isVisible[i]) {
                        mVisibleTrackedIds = addToSet(mVisibleTrackedIds, id);
                    } else {
                        mInvisibleTrackedIds = addToSet(mInvisibleTrackedIds, id);
                    }
                }
            }

            if (sVerbose) {
                Log.v(TAG, "TrackedViews(trackedIds=" + trackedIds + "): "
                        + " mVisibleTrackedIds=" + mVisibleTrackedIds
                        + " mInvisibleTrackedIds=" + mInvisibleTrackedIds);
            }

            if (mVisibleTrackedIds == null) {
                finishSessionLocked();
            }
        }

        /**
         * Called when a {@link View view's} visibility changes.
         *
         * @param view {@link View} that was exited.
         * @param isVisible visible if the view is visible in the view hierarchy.
         */
        void notifyViewVisibilityChange(@NonNull View view, boolean isVisible) {
            AutofillId id = getAutofillId(view);
            AutofillClient client = getClientLocked();

            if (sDebug) {
                Log.d(TAG, "notifyViewVisibilityChange(): id=" + id + " isVisible="
                        + isVisible);
            }

            if (client != null && client.isVisibleForAutofill()) {
                if (isVisible) {
                    if (isInSet(mInvisibleTrackedIds, id)) {
                        mInvisibleTrackedIds = removeFromSet(mInvisibleTrackedIds, id);
                        mVisibleTrackedIds = addToSet(mVisibleTrackedIds, id);
                    }
                } else {
                    if (isInSet(mVisibleTrackedIds, id)) {
                        mVisibleTrackedIds = removeFromSet(mVisibleTrackedIds, id);
                        mInvisibleTrackedIds = addToSet(mInvisibleTrackedIds, id);
                    }
                }
            }

            if (mVisibleTrackedIds == null) {
                if (sVerbose) {
                    Log.v(TAG, "No more visible ids. Invisibile = " + mInvisibleTrackedIds);
                }
                finishSessionLocked();
            }
        }

        /**
         * Called once the client becomes visible.
         *
         * @see AutofillClient#isVisibleForAutofill()
         */
        void onVisibleForAutofillLocked() {
            // The visibility of the views might have changed while the client was not be visible,
            // hence update the visibility state for all views.
            AutofillClient client = getClientLocked();
            ArraySet<AutofillId> updatedVisibleTrackedIds = null;
            ArraySet<AutofillId> updatedInvisibleTrackedIds = null;
            if (client != null) {
                if (mInvisibleTrackedIds != null) {
                    final ArrayList<AutofillId> orderedInvisibleIds =
                            new ArrayList<>(mInvisibleTrackedIds);
                    final boolean[] isVisible = client.getViewVisibility(
                            getViewIds(orderedInvisibleIds));

                    final int numInvisibleTrackedIds = orderedInvisibleIds.size();
                    for (int i = 0; i < numInvisibleTrackedIds; i++) {
                        final AutofillId id = orderedInvisibleIds.get(i);
                        if (isVisible[i]) {
                            updatedVisibleTrackedIds = addToSet(updatedVisibleTrackedIds, id);

                            if (sDebug) {
                                Log.d(TAG, "onVisibleForAutofill() " + id + " became visible");
                            }
                        } else {
                            updatedInvisibleTrackedIds = addToSet(updatedInvisibleTrackedIds, id);
                        }
                    }
                }

                if (mVisibleTrackedIds != null) {
                    final ArrayList<AutofillId> orderedVisibleIds =
                            new ArrayList<>(mVisibleTrackedIds);
                    final boolean[] isVisible = client.getViewVisibility(
                            getViewIds(orderedVisibleIds));

                    final int numVisibleTrackedIds = orderedVisibleIds.size();
                    for (int i = 0; i < numVisibleTrackedIds; i++) {
                        final AutofillId id = orderedVisibleIds.get(i);

                        if (isVisible[i]) {
                            updatedVisibleTrackedIds = addToSet(updatedVisibleTrackedIds, id);
                        } else {
                            updatedInvisibleTrackedIds = addToSet(updatedInvisibleTrackedIds, id);

                            if (sDebug) {
                                Log.d(TAG, "onVisibleForAutofill() " + id + " became invisible");
                            }
                        }
                    }
                }

                mInvisibleTrackedIds = updatedInvisibleTrackedIds;
                mVisibleTrackedIds = updatedVisibleTrackedIds;
            }

            if (mVisibleTrackedIds == null) {
                finishSessionLocked();
            }
        }
    }

    /**
     * Callback for auto-fill related events.
     *
     * <p>Typically used for applications that display their own "auto-complete" views, so they can
     * enable / disable such views when the auto-fill UI affordance is shown / hidden.
     */
    public abstract static class AutofillCallback {

        /** @hide */
        @IntDef({EVENT_INPUT_SHOWN, EVENT_INPUT_HIDDEN})
        @Retention(RetentionPolicy.SOURCE)
        public @interface AutofillEventType {}

        /**
         * The auto-fill input UI affordance associated with the view was shown.
         *
         * <p>If the view provides its own auto-complete UI affordance and its currently shown, it
         * should be hidden upon receiving this event.
         */
        public static final int EVENT_INPUT_SHOWN = 1;

        /**
         * The auto-fill input UI affordance associated with the view was hidden.
         *
         * <p>If the view provides its own auto-complete UI affordance that was hidden upon a
         * {@link #EVENT_INPUT_SHOWN} event, it could be shown again now.
         */
        public static final int EVENT_INPUT_HIDDEN = 2;

        /**
         * The auto-fill input UI affordance associated with the view won't be shown because
         * autofill is not available.
         *
         * <p>If the view provides its own auto-complete UI affordance but was not displaying it
         * to avoid flickering, it could shown it upon receiving this event.
         */
        public static final int EVENT_INPUT_UNAVAILABLE = 3;

        /**
         * Called after a change in the autofill state associated with a view.
         *
         * @param view view associated with the change.
         *
         * @param event currently either {@link #EVENT_INPUT_SHOWN} or {@link #EVENT_INPUT_HIDDEN}.
         */
        public void onAutofillEvent(@NonNull View view, @AutofillEventType int event) {
        }

        /**
         * Called after a change in the autofill state associated with a virtual view.
         *
         * @param view parent view associated with the change.
         * @param childId id identifying the virtual child inside the parent view.
         *
         * @param event currently either {@link #EVENT_INPUT_SHOWN} or {@link #EVENT_INPUT_HIDDEN}.
         */
        public void onAutofillEvent(@NonNull View view, int childId, @AutofillEventType int event) {
        }
    }

    private static final class AutofillManagerClient extends IAutoFillManagerClient.Stub {
        private final WeakReference<AutofillManager> mAfm;

        AutofillManagerClient(AutofillManager autofillManager) {
            mAfm = new WeakReference<>(autofillManager);
        }

        @Override
        public void setState(boolean enabled, boolean resetSession, boolean resetClient) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(
                        () -> afm.setState(enabled, resetSession, resetClient));
            }
        }

        @Override
        public void autofill(int sessionId, List<AutofillId> ids, List<AutofillValue> values) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(
                        () -> afm.autofill(sessionId, ids, values));
            }
        }

        @Override
        public void authenticate(int sessionId, int authenticationId, IntentSender intent,
                Intent fillInIntent) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(
                        () -> afm.authenticate(sessionId, authenticationId, intent, fillInIntent));
            }
        }

        @Override
        public void requestShowFillUi(int sessionId, AutofillId id, int width, int height,
                Rect anchorBounds, IAutofillWindowPresenter presenter) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(
                        () -> afm.requestShowFillUi(sessionId, id, width, height, anchorBounds,
                                presenter));
            }
        }

        @Override
        public void requestHideFillUi(int sessionId, AutofillId id) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() -> afm.requestHideFillUi(id));
            }
        }

        @Override
        public void notifyNoFillUi(int sessionId, AutofillId id) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() -> afm.notifyNoFillUi(sessionId, id));
            }
        }

        @Override
        public void startIntentSender(IntentSender intentSender) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() -> {
                    try {
                        afm.mContext.startIntentSender(intentSender, null, 0, 0, 0);
                    } catch (IntentSender.SendIntentException e) {
                        Log.e(TAG, "startIntentSender() failed for intent:" + intentSender, e);
                    }
                });
            }
        }

        @Override
        public void setTrackedViews(int sessionId, AutofillId[] ids,
                boolean saveOnAllViewsInvisible, AutofillId[] fillableIds) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.mContext.getMainThreadHandler().post(() ->
                        afm.setTrackedViews(sessionId, ids, saveOnAllViewsInvisible, fillableIds)
                );
            }
        }
    }
}
