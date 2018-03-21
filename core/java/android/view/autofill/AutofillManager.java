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
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.graphics.Rect;
import android.metrics.LogMaker;
import android.os.Bundle;
import android.os.IBinder;
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
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The {@link AutofillManager} provides ways for apps and custom views to integrate with the
 * Autofill Framework lifecycle.
 *
 * <p>The autofill lifecycle starts with the creation of an autofill context associated with an
 * activity context; the autofill context is created when one of the following methods is called for
 * the first time in an activity context, and the current user has an enabled autofill service:
 *
 * <ul>
 *   <li>{@link #notifyViewEntered(View)}
 *   <li>{@link #notifyViewEntered(View, int, Rect)}
 *   <li>{@link #requestAutofill(View)}
 * </ul>
 *
 * <p>Tipically, the context is automatically created when the first view of the activity is
 * focused because {@code View.onFocusChanged()} indirectly calls
 * {@link #notifyViewEntered(View)}. App developers can call {@link #requestAutofill(View)} to
 * explicitly create it (for example, a custom view developer could offer a contextual menu action
 * in a text-field view to let users manually request autofill).
 *
 * <p>After the context is created, the Android System creates a {@link android.view.ViewStructure}
 * that represents the view hierarchy by calling
 * {@link View#dispatchProvideAutofillStructure(android.view.ViewStructure, int)} in the root views
 * of all application windows. By default, {@code dispatchProvideAutofillStructure()} results in
 * subsequent calls to {@link View#onProvideAutofillStructure(android.view.ViewStructure, int)} and
 * {@link View#onProvideAutofillVirtualStructure(android.view.ViewStructure, int)} for each view in
 * the hierarchy.
 *
 * <p>The resulting {@link android.view.ViewStructure} is then passed to the autofill service, which
 * parses it looking for views that can be autofilled. If the service finds such views, it returns
 * a data structure to the Android System containing the following optional info:
 *
 * <ul>
 *   <li>Datasets used to autofill subsets of views in the activity.
 *   <li>Id of views that the service can save their values for future autofilling.
 * </ul>
 *
 * <p>When the service returns datasets, the Android System displays an autofill dataset picker
 * UI affordance associated with the view, when the view is focused on and is part of a dataset.
 * The application can be notified when the affordance is shown by registering an
 * {@link AutofillCallback} through {@link #registerCallback(AutofillCallback)}. When the user
 * selects a dataset from the affordance, all views present in the dataset are autofilled, through
 * calls to {@link View#autofill(AutofillValue)} or {@link View#autofill(SparseArray)}.
 *
 * <p>When the service returns ids of savable views, the Android System keeps track of changes
 * made to these views, so they can be used to determine if the autofill save UI is shown later.
 *
 * <p>The context is then finished when one of the following occurs:
 *
 * <ul>
 *   <li>{@link #commit()} is called or all savable views are gone.
 *   <li>{@link #cancel()} is called.
 * </ul>
 *
 * <p>Finally, after the autofill context is commited (i.e., not cancelled), the Android System
 * shows a save UI affordance if the value of savable views have changed. If the user selects the
 * option to Save, the current value of the views is then sent to the autofill service.
 *
 * <p>It is safe to call into its methods from any thread.
 */
@SystemService(Context.AUTOFILL_MANAGER_SERVICE)
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


    /** @hide */
    public static final String EXTRA_RESTORE_SESSION_TOKEN =
            "android.view.autofill.extra.RESTORE_SESSION_TOKEN";

    private static final String SESSION_ID_TAG = "android:sessionId";
    private static final String STATE_TAG = "android:state";
    private static final String LAST_AUTOFILLED_DATA_TAG = "android:lastAutoFilledData";


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
     * Used on {@link #onPendingSaveUi(int, IBinder)} to cancel the pending UI.
     *
     * @hide
     */
    public static final int PENDING_UI_OPERATION_CANCEL = 1;

    /**
     * Used on {@link #onPendingSaveUi(int, IBinder)} to restore the pending UI.
     *
     * @hide
     */
    public static final int PENDING_UI_OPERATION_RESTORE = 2;

    /**
     * Initial state of the autofill context, set when there is no session (i.e., when
     * {@link #mSessionId} is {@link #NO_SESSION}).
     *
     * <p>In this state, app callbacks (such as {@link #notifyViewEntered(View)}) are notified to
     * the server.
     *
     * @hide
     */
    public static final int STATE_UNKNOWN = 0;

    /**
     * State where the autofill context hasn't been {@link #commit() finished} nor
     * {@link #cancel() canceled} yet.
     *
     * @hide
     */
    public static final int STATE_ACTIVE = 1;

    /**
     * State where the autofill context was finished by the server because the autofill
     * service could not autofill the page.
     *
     * <p>In this state, most apps callback (such as {@link #notifyViewEntered(View)}) are ignored,
     * exception {@link #requestAutofill(View)} (and {@link #requestAutofill(View, int, Rect)}).
     *
     * @hide
     */
    public static final int STATE_FINISHED = 2;

    /**
     * State where the autofill context has been {@link #commit() finished} but the server still has
     * a session because the Save UI hasn't been dismissed yet.
     *
     * @hide
     */
    public static final int STATE_SHOWING_SAVE_UI = 3;

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
    private int mState = STATE_UNKNOWN;

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
         * @param viewIds The autofill ids of the views to find
         *
         * @return And array containing the views (empty if no views found).
         */
        @NonNull View[] findViewsByAutofillIdTraversal(@NonNull int[] viewIds);

        /**
         * Finds a view by traversing the hierarchies of the client.
         *
         * @param viewId The autofill id of the views to find
         *
         * @return The view, or {@code null} if not found
         */
        @Nullable View findViewByAutofillIdTraversal(int viewId);

        /**
         * Runs the specified action on the UI thread.
         */
        void runOnUiThread(Runnable action);

        /**
         * Gets the complete component name of this client.
         *
         * <p>Temporary method on O-MR1 only.
         */
        ComponentName getComponentNameForAutofill();
    }

    /**
     * @hide
     */
    public AutofillManager(Context context, IAutoFillManager service) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
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

            if (isActiveLocked()) {
                Log.w(TAG, "New session was started before onCreate()");
                return;
            }

            mSessionId = savedInstanceState.getInt(SESSION_ID_TAG, NO_SESSION);
            mState = savedInstanceState.getInt(STATE_TAG, STATE_UNKNOWN);

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
                            mState = STATE_UNKNOWN;
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
            if (mEnabled && isActiveLocked() && mTrackedViews != null) {
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
            if (mState != STATE_UNKNOWN) {
                outState.putInt(STATE_TAG, mState);
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
     * <p>Normally, the autofill context is automatically started if necessary when
     * {@link #notifyViewEntered(View)} is called, but this method should be used in the
     * cases where it must be explicitly started. For example, when the view offers an AUTOFILL
     * option on its contextual overflow menu, and the user selects it.
     *
     * @param view view requesting the new autofill context.
     */
    public void requestAutofill(@NonNull View view) {
        notifyViewEntered(view, FLAG_MANUAL_REQUEST);
    }

    /**
     * Explicitly requests a new autofill context for virtual views.
     *
     * <p>Normally, the autofill context is automatically started if necessary when
     * {@link #notifyViewEntered(View, int, Rect)} is called, but this method should be used in the
     * cases where it must be explicitly started. For example, when the virtual view offers an
     * AUTOFILL option on its contextual overflow menu, and the user selects it.
     *
     * <p>The virtual view boundaries must be absolute screen coordinates. For example, if the
     * parent view uses {@code bounds} to draw the virtual view inside its Canvas,
     * the absolute bounds could be calculated by:
     *
     * <pre class="prettyprint">
     *   int offset[] = new int[2];
     *   getLocationOnScreen(offset);
     *   Rect absBounds = new Rect(bounds.left + offset[0],
     *       bounds.top + offset[1],
     *       bounds.right + offset[0], bounds.bottom + offset[1]);
     * </pre>
     *
     * @param view the virtual view parent.
     * @param virtualId id identifying the virtual child inside the parent view.
     * @param absBounds absolute boundaries of the virtual view in the screen.
     */
    public void requestAutofill(@NonNull View view, int virtualId, @NonNull Rect absBounds) {
        notifyViewEntered(view, virtualId, absBounds, FLAG_MANUAL_REQUEST);
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
            if (isFinishedLocked() && (flags & FLAG_MANUAL_REQUEST) == 0) {
                if (sVerbose) {
                    Log.v(TAG, "notifyViewEntered(flags=" + flags + ", view=" + view
                            + "): ignored on state " + getStateAsStringLocked());
                }
                return;
            }

            ensureServiceClientAddedIfNeededLocked();

            if (!mEnabled) {
                if (mCallback != null) {
                    callback = mCallback;
                }
            } else {
                final AutofillId id = getAutofillId(view);
                final AutofillValue value = view.getAutofillValue();

                if (!isActiveLocked()) {
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

            if (mEnabled && isActiveLocked()) {
                final AutofillId id = getAutofillId(view);

                // Update focus on existing session.
                updateSessionLocked(id, null, null, ACTION_VIEW_EXITED, 0);
            }
        }
    }

    /**
     * Called when a {@link View view's} visibility changed.
     *
     * @param view {@link View} that was exited.
     * @param isVisible visible if the view is visible in the view hierarchy.
     */
    public void notifyViewVisibilityChanged(@NonNull View view, boolean isVisible) {
        notifyViewVisibilityChangedInternal(view, 0, isVisible, false);
    }

    /**
     * Called when a virtual view's visibility changed.
     *
     * @param view {@link View} that was exited.
     * @param virtualId id identifying the virtual child inside the parent view.
     * @param isVisible visible if the view is visible in the view hierarchy.
     */
    public void notifyViewVisibilityChanged(@NonNull View view, int virtualId, boolean isVisible) {
        notifyViewVisibilityChangedInternal(view, virtualId, isVisible, true);
    }

    /**
     * Called when a view/virtual view's visibility changed.
     *
     * @param view {@link View} that was exited.
     * @param virtualId id identifying the virtual child inside the parent view.
     * @param isVisible visible if the view is visible in the view hierarchy.
     * @param virtual Whether the view is virtual.
     */
    private void notifyViewVisibilityChangedInternal(@NonNull View view, int virtualId,
            boolean isVisible, boolean virtual) {
        synchronized (mLock) {
            if (mEnabled && isActiveLocked()) {
                final AutofillId id = virtual ? getAutofillId(view, virtualId)
                        : view.getAutofillId();
                if (!isVisible && mFillableIds != null) {
                    if (mFillableIds.contains(id)) {
                        if (sDebug) Log.d(TAG, "Hidding UI when view " + id + " became invisible");
                        requestHideFillUi(id, view);
                    }
                }
                if (mTrackedViews != null) {
                    mTrackedViews.notifyViewVisibilityChanged(id, isVisible);
                }
            }
        }
    }

    /**
     * Called when a virtual view that supports autofill is entered.
     *
     * <p>The virtual view boundaries must be absolute screen coordinates. For example, if the
     * parent, non-virtual view uses {@code bounds} to draw the virtual view inside its Canvas,
     * the absolute bounds could be calculated by:
     *
     * <pre class="prettyprint">
     *   int offset[] = new int[2];
     *   getLocationOnScreen(offset);
     *   Rect absBounds = new Rect(bounds.left + offset[0],
     *       bounds.top + offset[1],
     *       bounds.right + offset[0], bounds.bottom + offset[1]);
     * </pre>
     *
     * @param view the virtual view parent.
     * @param virtualId id identifying the virtual child inside the parent view.
     * @param absBounds absolute boundaries of the virtual view in the screen.
     */
    public void notifyViewEntered(@NonNull View view, int virtualId, @NonNull Rect absBounds) {
        notifyViewEntered(view, virtualId, absBounds, 0);
    }

    private void notifyViewEntered(View view, int virtualId, Rect bounds, int flags) {
        if (!hasAutofillFeature()) {
            return;
        }
        AutofillCallback callback = null;
        synchronized (mLock) {
            if (isFinishedLocked() && (flags & FLAG_MANUAL_REQUEST) == 0) {
                if (sVerbose) {
                    Log.v(TAG, "notifyViewEntered(flags=" + flags + ", view=" + view
                            + ", virtualId=" + virtualId
                            + "): ignored on state " + getStateAsStringLocked());
                }
                return;
            }
            ensureServiceClientAddedIfNeededLocked();

            if (!mEnabled) {
                if (mCallback != null) {
                    callback = mCallback;
                }
            } else {
                final AutofillId id = getAutofillId(view, virtualId);

                if (!isActiveLocked()) {
                    // Starts new session.
                    startSessionLocked(id, bounds, null, flags);
                } else {
                    // Update focus on existing session.
                    updateSessionLocked(id, bounds, null, ACTION_VIEW_ENTERED, flags);
                }
            }
        }

        if (callback != null) {
            callback.onAutofillEvent(view, virtualId,
                    AutofillCallback.EVENT_INPUT_UNAVAILABLE);
        }
    }

    /**
     * Called when a virtual view that supports autofill is exited.
     *
     * @param view the virtual view parent.
     * @param virtualId id identifying the virtual child inside the parent view.
     */
    public void notifyViewExited(@NonNull View view, int virtualId) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            ensureServiceClientAddedIfNeededLocked();

            if (mEnabled && isActiveLocked()) {
                final AutofillId id = getAutofillId(view, virtualId);

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

            if (!mEnabled || !isActiveLocked()) {
                if (sVerbose && mEnabled) {
                    Log.v(TAG, "notifyValueChanged(" + view + "): ignoring on state "
                            + getStateAsStringLocked());
                }
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
     * Called to indicate the value of an autofillable virtual view has changed.
     *
     * @param view the virtual view parent.
     * @param virtualId id identifying the virtual child inside the parent view.
     * @param value new value of the child.
     */
    public void notifyValueChanged(View view, int virtualId, AutofillValue value) {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (!mEnabled || !isActiveLocked()) {
                return;
            }

            final AutofillId id = getAutofillId(view, virtualId);
            updateSessionLocked(id, null, value, ACTION_VALUE_CHANGED, 0);
        }
    }

    /**
     * Called to indicate the current autofill context should be commited.
     *
     * <p>This method is typically called by {@link View Views} that manage virtual views; for
     * example, when the view is rendering an {@code HTML} page with a form and virtual views
     * that represent the HTML elements, it should call this method after the form is submitted and
     * another page is rendered.
     *
     * <p><b>Note:</b> This method does not need to be called on regular application lifecycle
     * methods such as {@link android.app.Activity#finish()}.
     */
    public void commit() {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (!mEnabled && !isActiveLocked()) {
                return;
            }

            finishSessionLocked();
        }
    }

    /**
     * Called to indicate the current autofill context should be cancelled.
     *
     * <p>This method is typically called by {@link View Views} that manage virtual views; for
     * example, when the view is rendering an {@code HTML} page with a form and virtual views
     * that represent the HTML elements, it should call this method if the user does not post the
     * form but moves to another form in this page.
     *
     * <p><b>Note:</b> This method does not need to be called on regular application lifecycle
     * methods such as {@link android.app.Activity#finish()}.
     */
    public void cancel() {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (!mEnabled && !isActiveLocked()) {
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
     * Returns {@code true} if autofill is supported by the current device and
     * is supported for this user.
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
        return mContext.getAutofillClient();
    }

    private ComponentName getComponentNameFromContext(AutofillClient client) {
        return client == null ? null : client.getComponentNameForAutofill();
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
            if (!isActiveLocked() || data == null) {
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
        return new AutofillId(view.getAutofillViewId());
    }

    private static AutofillId getAutofillId(View parent, int virtualId) {
        return new AutofillId(parent.getAutofillViewId(), virtualId);
    }

    private void startSessionLocked(@NonNull AutofillId id, @NonNull Rect bounds,
            @NonNull AutofillValue value, int flags) {
        if (sVerbose) {
            Log.v(TAG, "startSessionLocked(): id=" + id + ", bounds=" + bounds + ", value=" + value
                    + ", flags=" + flags + ", state=" + getStateAsStringLocked());
        }
        if (mState != STATE_UNKNOWN && (flags & FLAG_MANUAL_REQUEST) == 0) {
            if (sVerbose) {
                Log.v(TAG, "not automatically starting session for " + id
                        + " on state " + getStateAsStringLocked());
            }
            return;
        }
        try {
            final AutofillClient client = getClientLocked();
            final ComponentName componentName = getComponentNameFromContext(client);
            if (componentName == null) {
                Log.w(TAG, "startSessionLocked(): context is not activity: " + mContext);
                return;
            }
            mSessionId = mService.startSession(mContext.getActivityToken(),
                    mServiceClient.asBinder(), id, bounds, value, mContext.getUserId(),
                    mCallback != null, flags, componentName);
            if (mSessionId != NO_SESSION) {
                mState = STATE_ACTIVE;
            }
            if (client != null) {
                client.autofillCallbackResetableStateAvailable();
            }
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    private void finishSessionLocked() {
        if (sVerbose) Log.v(TAG, "finishSessionLocked(): " + getStateAsStringLocked());

        if (!isActiveLocked()) return;

        try {
            mService.finishSession(mSessionId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        resetSessionLocked();
    }

    private void cancelSessionLocked() {
        if (sVerbose) Log.v(TAG, "cancelSessionLocked(): " + getStateAsStringLocked());

        if (!isActiveLocked()) return;

        try {
            mService.cancelSession(mSessionId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        resetSessionLocked();
    }

    private void resetSessionLocked() {
        mSessionId = NO_SESSION;
        mState = STATE_UNKNOWN;
        mTrackedViews = null;
        mFillableIds = null;
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
                final AutofillClient client = getClientLocked();
                final ComponentName componentName = getComponentNameFromContext(client);
                if (componentName == null) {
                    Log.w(TAG, "startSessionLocked(): context is not activity: " + mContext);
                    return;
                }
                final int newId = mService.updateOrRestartSession(mContext.getActivityToken(),
                        mServiceClient.asBinder(), id, bounds, value, mContext.getUserId(),
                        mCallback != null, flags, componentName, mSessionId, action);
                if (newId != mSessionId) {
                    if (sDebug) Log.d(TAG, "Session restarted: " + mSessionId + "=>" + newId);
                    mSessionId = newId;
                    mState = (mSessionId == NO_SESSION) ? STATE_UNKNOWN : STATE_ACTIVE;
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
            final View[] views = client.findViewsByAutofillIdTraversal(getViewIds(ids));

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

            final LogMaker log = new LogMaker(MetricsEvent.AUTOFILL_DATASET_APPLIED)
                    .setPackageName(mContext.getPackageName())
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_VALUES, itemCount)
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_VIEWS_FILLED, numApplied);
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

    private void setSaveUiState(int sessionId, boolean shown) {
        if (sDebug) Log.d(TAG, "setSaveUiState(" + sessionId + "): " + shown);
        synchronized (mLock) {
            if (mSessionId != NO_SESSION) {
                // Race condition: app triggered a new session after the previous session was
                // finished but before server called setSaveUiState() - need to cancel the new
                // session to avoid further inconsistent behavior.
                Log.w(TAG, "setSaveUiState(" + sessionId + ", " + shown
                        + ") called on existing session " + mSessionId + "; cancelling it");
                cancelSessionLocked();
            }
            if (shown) {
                mSessionId = sessionId;
                mState = STATE_SHOWING_SAVE_UI;
            } else {
                mSessionId = NO_SESSION;
                mState = STATE_UNKNOWN;
            }
        }
    }

    /**
     * Marks the state of the session as finished.
     *
     * @param newState {@link #STATE_FINISHED} (because the autofill service returned a {@code null}
     *  FillResponse) or {@link #STATE_UNKNOWN} (because the session was removed).
     */
    private void setSessionFinished(int newState) {
        synchronized (mLock) {
            if (sVerbose) Log.v(TAG, "setSessionFinished(): from " + mState + " to " + newState);
            resetSessionLocked();
            mState = newState;
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

    private void notifyNoFillUi(int sessionId, AutofillId id, boolean sessionFinished) {
        if (sVerbose) {
            Log.v(TAG, "notifyNoFillUi(): sessionId=" + sessionId + ", autofillId=" + id
                    + ", finished=" + sessionFinished);
        }
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

        if (sessionFinished) {
            // Callback call was "hijacked" to also update the session state.
            setSessionFinished(STATE_FINISHED);
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

        return client.findViewByAutofillIdTraversal(autofillId.getViewId());
    }

    /** @hide */
    public boolean hasAutofillFeature() {
        return mService != null;
    }

    /** @hide */
    public void onPendingSaveUi(int operation, IBinder token) {
        if (sVerbose) Log.v(TAG, "onPendingSaveUi(" + operation + "): " + token);

        synchronized (mLock) {
            try {
                mService.onPendingSaveUi(operation, token);
            } catch (RemoteException e) {
                e.rethrowFromSystemServer();
            }
        }
    }

    /** @hide */
    public void dump(String outerPrefix, PrintWriter pw) {
        pw.print(outerPrefix); pw.println("AutofillManager:");
        final String pfx = outerPrefix + "  ";
        pw.print(pfx); pw.print("sessionId: "); pw.println(mSessionId);
        pw.print(pfx); pw.print("state: "); pw.println(getStateAsStringLocked());
        pw.print(pfx); pw.print("enabled: "); pw.println(mEnabled);
        pw.print(pfx); pw.print("hasService: "); pw.println(mService != null);
        pw.print(pfx); pw.print("hasCallback: "); pw.println(mCallback != null);
        pw.print(pfx); pw.print("last autofilled data: "); pw.println(mLastAutofilledData);
        pw.print(pfx); pw.print("tracked views: ");
        if (mTrackedViews == null) {
            pw.println("null");
        } else {
            final String pfx2 = pfx + "  ";
            pw.println();
            pw.print(pfx2); pw.print("visible:"); pw.println(mTrackedViews.mVisibleTrackedIds);
            pw.print(pfx2); pw.print("invisible:"); pw.println(mTrackedViews.mInvisibleTrackedIds);
        }
        pw.print(pfx); pw.print("fillable ids: "); pw.println(mFillableIds);
    }

    private String getStateAsStringLocked() {
        switch (mState) {
            case STATE_UNKNOWN:
                return "STATE_UNKNOWN";
            case STATE_ACTIVE:
                return "STATE_ACTIVE";
            case STATE_FINISHED:
                return "STATE_FINISHED";
            case STATE_SHOWING_SAVE_UI:
                return "STATE_SHOWING_SAVE_UI";
            default:
                return "INVALID:" + mState;
        }
    }

    private boolean isActiveLocked() {
        return mState == STATE_ACTIVE;
    }

    private boolean isFinishedLocked() {
        return mState == STATE_FINISHED;
    }

    private void post(Runnable runnable) {
        final AutofillClient client = getClientLocked();
        if (client == null) {
            if (sVerbose) Log.v(TAG, "ignoring post() because client is null");
            return;
        }
        client.runOnUiThread(runnable);
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
         * @param id the id of the view/virtual view whose visibility changed.
         * @param isVisible visible if the view is visible in the view hierarchy.
         */
        void notifyViewVisibilityChanged(@NonNull AutofillId id, boolean isVisible) {
            AutofillClient client = getClientLocked();

            if (sDebug) {
                Log.d(TAG, "notifyViewVisibilityChanged(): id=" + id + " isVisible="
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
     * Callback for autofill related events.
     *
     * <p>Typically used for applications that display their own "auto-complete" views, so they can
     * enable / disable such views when the autofill UI affordance is shown / hidden.
     */
    public abstract static class AutofillCallback {

        /** @hide */
        @IntDef({EVENT_INPUT_SHOWN, EVENT_INPUT_HIDDEN})
        @Retention(RetentionPolicy.SOURCE)
        public @interface AutofillEventType {}

        /**
         * The autofill input UI affordance associated with the view was shown.
         *
         * <p>If the view provides its own auto-complete UI affordance and its currently shown, it
         * should be hidden upon receiving this event.
         */
        public static final int EVENT_INPUT_SHOWN = 1;

        /**
         * The autofill input UI affordance associated with the view was hidden.
         *
         * <p>If the view provides its own auto-complete UI affordance that was hidden upon a
         * {@link #EVENT_INPUT_SHOWN} event, it could be shown again now.
         */
        public static final int EVENT_INPUT_HIDDEN = 2;

        /**
         * The autofill input UI affordance associated with the view isn't shown because
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
         * @param virtualId id identifying the virtual child inside the parent view.
         *
         * @param event currently either {@link #EVENT_INPUT_SHOWN} or {@link #EVENT_INPUT_HIDDEN}.
         */
        public void onAutofillEvent(@NonNull View view, int virtualId,
                @AutofillEventType int event) {
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
                afm.post(() -> afm.setState(enabled, resetSession, resetClient));
            }
        }

        @Override
        public void autofill(int sessionId, List<AutofillId> ids, List<AutofillValue> values) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.autofill(sessionId, ids, values));
            }
        }

        @Override
        public void authenticate(int sessionId, int authenticationId, IntentSender intent,
                Intent fillInIntent) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.authenticate(sessionId, authenticationId, intent, fillInIntent));
            }
        }

        @Override
        public void requestShowFillUi(int sessionId, AutofillId id, int width, int height,
                Rect anchorBounds, IAutofillWindowPresenter presenter) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.requestShowFillUi(sessionId, id, width, height, anchorBounds,
                        presenter));
            }
        }

        @Override
        public void requestHideFillUi(int sessionId, AutofillId id) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.requestHideFillUi(id));
            }
        }

        @Override
        public void notifyNoFillUi(int sessionId, AutofillId id, boolean sessionFinished) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.notifyNoFillUi(sessionId, id, sessionFinished));
            }
        }

        @Override
        public void startIntentSender(IntentSender intentSender, Intent intent) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> {
                    try {
                        afm.mContext.startIntentSender(intentSender, intent, 0, 0, 0);
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
                afm.post(() ->
                        afm.setTrackedViews(sessionId, ids, saveOnAllViewsInvisible, fillableIds)
                );
            }
        }

        @Override
        public void setSaveUiState(int sessionId, boolean shown) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.setSaveUiState(sessionId, shown));
            }
        }

        @Override
        public void setSessionFinished(int newState) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.setSessionFinished(newState));
            }
        }
    }
}
