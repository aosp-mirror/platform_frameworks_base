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
import static android.service.autofill.FillRequest.FLAG_PASSWORD_INPUT_TYPE;
import static android.view.autofill.Helper.sDebug;
import static android.view.autofill.Helper.sVerbose;
import static android.view.autofill.Helper.toList;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.RequiresFeature;
import android.annotation.SystemApi;
import android.annotation.SystemService;
import android.annotation.TestApi;
import android.content.AutofillOptions;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.metrics.LogMaker;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcelable;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.autofill.AutofillService;
import android.service.autofill.FillEventHistory;
import android.service.autofill.UserData;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.DebugUtils;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.view.Choreographer;
import android.view.KeyEvent;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityNodeProvider;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.TextView;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;
import com.android.internal.util.SyncResultReceiver;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import sun.misc.Cleaner;

//TODO: use java.lang.ref.Cleaner once Android supports Java 9

/**
 * <p>The {@link AutofillManager} class provides ways for apps and custom views to
 * integrate with the Autofill Framework lifecycle.
 *
 * <p>To learn about using Autofill in your app, read
 * the <a href="/guide/topics/text/autofill">Autofill Framework</a> guides.
 *
 * <h3 id="autofill-lifecycle">Autofill lifecycle</h3>
 *
 * <p>The autofill lifecycle starts with the creation of an autofill context associated with an
 * activity context. The autofill context is created when one of the following methods is called for
 * the first time in an activity context, and the current user has an enabled autofill service:
 *
 * <ul>
 *   <li>{@link #notifyViewEntered(View)}
 *   <li>{@link #notifyViewEntered(View, int, Rect)}
 *   <li>{@link #requestAutofill(View)}
 * </ul>
 *
 * <p>Typically, the context is automatically created when the first view of the activity is
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
 * UI associated with the view, when the view is focused on and is part of a dataset.
 * The application can be notified when the UI is shown by registering an
 * {@link AutofillCallback} through {@link #registerCallback(AutofillCallback)}. When the user
 * selects a dataset from the UI, all views present in the dataset are autofilled, through
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
 * shows an autofill save UI if the value of savable views have changed. If the user selects the
 * option to Save, the current value of the views is then sent to the autofill service.
 *
 * <h3 id="additional-notes">Additional notes</h3>
 *
 * <p>It is safe to call <code>AutofillManager</code> methods from any thread.
 */
@SystemService(Context.AUTOFILL_MANAGER_SERVICE)
@RequiresFeature(PackageManager.FEATURE_AUTOFILL)
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
     * <p>On Android {@link android.os.Build.VERSION_CODES#P} and higher, the autofill service
     * can also add this bundle to the {@link Intent} set as the
     * {@link android.app.Activity#setResult(int, Intent) result} for an authentication request,
     * so the bundle can be recovered later on
     * {@link android.service.autofill.SaveRequest#getClientState()}.
     *
     * <p>
     * Type: {@link android.os.Bundle}
     */
    public static final String EXTRA_CLIENT_STATE =
            "android.view.autofill.extra.CLIENT_STATE";

    /** @hide */
    public static final String EXTRA_RESTORE_SESSION_TOKEN =
            "android.view.autofill.extra.RESTORE_SESSION_TOKEN";

    /** @hide */
    public static final String EXTRA_RESTORE_CROSS_ACTIVITY =
            "android.view.autofill.extra.RESTORE_CROSS_ACTIVITY";

    /**
     * Internal extra used to pass a binder to the {@link IAugmentedAutofillManagerClient}.
     *
     * @hide
     */
    public static final String EXTRA_AUGMENTED_AUTOFILL_CLIENT =
            "android.view.autofill.extra.AUGMENTED_AUTOFILL_CLIENT";

    private static final String SESSION_ID_TAG = "android:sessionId";
    private static final String STATE_TAG = "android:state";
    private static final String LAST_AUTOFILLED_DATA_TAG = "android:lastAutoFilledData";

    /** @hide */ public static final int ACTION_START_SESSION = 1;
    /** @hide */ public static final int ACTION_VIEW_ENTERED =  2;
    /** @hide */ public static final int ACTION_VIEW_EXITED = 3;
    /** @hide */ public static final int ACTION_VALUE_CHANGED = 4;
    /** @hide */ public static final int ACTION_RESPONSE_EXPIRED = 5;

    /** @hide */ public static final int NO_LOGGING = 0;
    /** @hide */ public static final int FLAG_ADD_CLIENT_ENABLED = 0x1;
    /** @hide */ public static final int FLAG_ADD_CLIENT_DEBUG = 0x2;
    /** @hide */ public static final int FLAG_ADD_CLIENT_VERBOSE = 0x4;
    /** @hide */ public static final int FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY = 0x8;

    // NOTE: flag below is used by the session start receiver only, hence it can have values above
    /** @hide */ public static final int RECEIVER_FLAG_SESSION_FOR_AUGMENTED_AUTOFILL_ONLY = 0x1;

    /** @hide */
    public static final int DEFAULT_LOGGING_LEVEL = Build.IS_DEBUGGABLE
            ? AutofillManager.FLAG_ADD_CLIENT_DEBUG
            : AutofillManager.NO_LOGGING;

    /** @hide */
    public static final int DEFAULT_MAX_PARTITIONS_SIZE = 10;

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
     * service could not autofill the activity.
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
     * State where the autofill is disabled because the service cannot autofill the activity at all.
     *
     * <p>In this state, every call is ignored, even {@link #requestAutofill(View)}
     * (and {@link #requestAutofill(View, int, Rect)}).
     *
     * @hide
     */
    public static final int STATE_DISABLED_BY_SERVICE = 4;

    /**
     * Same as {@link #STATE_UNKNOWN}, but used on
     * {@link AutofillManagerClient#setSessionFinished(int, List)} when the session was finished
     * because the URL bar changed on client mode
     *
     * @hide
     */
    public static final int STATE_UNKNOWN_COMPAT_MODE = 5;

    /**
     * Same as {@link #STATE_UNKNOWN}, but used on
     * {@link AutofillManagerClient#setSessionFinished(int, List)} when the session was finished
     * because the service failed to fullfil a request.
     *
     * @hide
     */
    public static final int STATE_UNKNOWN_FAILED = 6;

    /**
     * Timeout in ms for calls to the field classification service.
     * @hide
     */
    public static final int FC_SERVICE_TIMEOUT = 5000;

    /**
     * Timeout for calls to system_server.
     */
    private static final int SYNC_CALLS_TIMEOUT_MS = 5000;

    /**
     * @hide
     */
    @TestApi
    public static final int MAX_TEMP_AUGMENTED_SERVICE_DURATION_MS = 1_000 * 60 * 2; // 2 minutes

    /**
     * Disables Augmented Autofill.
     *
     * @hide
     */
    @TestApi
    public static final int FLAG_SMART_SUGGESTION_OFF = 0x0;

    /**
     * Displays the Augment Autofill window using the same mechanism (such as a popup-window
     * attached to the focused view) as the standard autofill.
     *
     * @hide
     */
    @TestApi
    public static final int FLAG_SMART_SUGGESTION_SYSTEM = 0x1;

    /** @hide */
    @IntDef(flag = false, value = { FLAG_SMART_SUGGESTION_OFF, FLAG_SMART_SUGGESTION_SYSTEM })
    @Retention(RetentionPolicy.SOURCE)
    public @interface SmartSuggestionMode {}

    /**
     * {@code DeviceConfig} property used to set which Smart Suggestion modes for Augmented Autofill
     * are available.
     *
     * @hide
     */
    @TestApi
    public static final String DEVICE_CONFIG_AUTOFILL_SMART_SUGGESTION_SUPPORTED_MODES =
            "smart_suggestion_supported_modes";

    /**
     * Sets how long (in ms) the augmented autofill service is bound while idle.
     *
     * <p>Use {@code 0} to keep it permanently bound.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUGMENTED_SERVICE_IDLE_UNBIND_TIMEOUT =
            "augmented_service_idle_unbind_timeout";

    /**
     * Sets how long (in ms) the augmented autofill service request is killed if not replied.
     *
     * @hide
     */
    public static final String DEVICE_CONFIG_AUGMENTED_SERVICE_REQUEST_TIMEOUT =
            "augmented_service_request_timeout";

    /** @hide */
    public static final int RESULT_OK = 0;
    /** @hide */
    public static final int RESULT_CODE_NOT_SERVICE = -1;

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
    public static final int NO_SESSION = Integer.MAX_VALUE;

    private final IAutoFillManager mService;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private IAutoFillManagerClient mServiceClient;

    @GuardedBy("mLock")
    private Cleaner mServiceClientCleaner;

    @GuardedBy("mLock")
    private IAugmentedAutofillManagerClient mAugmentedAutofillServiceClient;

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

    /** id of last requested autofill ui */
    @Nullable private AutofillId mIdShownFillUi;

    /**
     * Views that were already "entered" - if they're entered again when the session is not active,
     * they're ignored
     * */
    @GuardedBy("mLock")
    @Nullable private ArraySet<AutofillId> mEnteredIds;

    /**
     * Views that were otherwised not important for autofill but triggered a session because the
     * context is whitelisted for augmented autofill.
     */
    @GuardedBy("mLock")
    @Nullable private Set<AutofillId> mEnteredForAugmentedAutofillIds;

    /** If set, session is commited when the field is clicked. */
    @GuardedBy("mLock")
    @Nullable private AutofillId mSaveTriggerId;

    /** set to true when onInvisibleForAutofill is called, used by onAuthenticationResult */
    @GuardedBy("mLock")
    private boolean mOnInvisibleCalled;

    /** If set, session is commited when the activity is finished; otherwise session is canceled. */
    @GuardedBy("mLock")
    private boolean mSaveOnFinish;

    /** If compatibility mode is enabled - this is a bridge to interact with a11y */
    @GuardedBy("mLock")
    private CompatibilityBridge mCompatibilityBridge;

    @Nullable
    private final AutofillOptions mOptions;

    /** When set, session is only used for augmented autofill requests. */
    @GuardedBy("mLock")
    private boolean mForAugmentedAutofillOnly;

    /**
     * When set, standard autofill is disabled, but sessions can still be created for augmented
     * autofill only.
     */
    @GuardedBy("mLock")
    private boolean mEnabledForAugmentedAutofillOnly;

    /** @hide */
    public interface AutofillClient {
        /**
         * Asks the client to start an authentication flow.
         *
         * @param authenticationId A unique id of the authentication operation.
         * @param intent The authentication intent.
         * @param fillInIntent The authentication fill-in intent.
         */
        void autofillClientAuthenticate(int authenticationId, IntentSender intent,
                Intent fillInIntent, boolean authenticateInline);

        /**
         * Tells the client this manager has state to be reset.
         */
        void autofillClientResetableStateAvailable();

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
        boolean autofillClientRequestShowFillUi(@NonNull View anchor, int width, int height,
                @Nullable Rect virtualBounds, IAutofillWindowPresenter presenter);

        /**
         * Dispatch unhandled keyevent from Autofill window
         * @param anchor The real view the UI needs to anchor to.
         * @param keyEvent Unhandled KeyEvent from autofill window.
         */
        void autofillClientDispatchUnhandledKey(@NonNull View anchor, @NonNull KeyEvent keyEvent);

        /**
         * Request hiding the autofill UI.
         *
         * @return Whether the UI was hidden.
         */
        boolean autofillClientRequestHideFillUi();

        /**
         * Gets whether the fill UI is currenlty being shown.
         *
         * @return Whether the fill UI is currently being shown
         */
        boolean autofillClientIsFillUiShowing();

        /**
         * Checks if views are currently attached and visible.
         *
         * @return And array with {@code true} iff the view is attached or visible
         */
        @NonNull boolean[] autofillClientGetViewVisibility(@NonNull AutofillId[] autofillIds);

        /**
         * Checks is the client is currently visible as understood by autofill.
         *
         * @return {@code true} if the client is currently visible
         */
        boolean autofillClientIsVisibleForAutofill();

        /**
         * Client might disable enter/exit event e.g. when activity is paused.
         */
        boolean isDisablingEnterExitEventForAutofill();

        /**
         * Finds views by traversing the hierarchies of the client.
         *
         * @param autofillIds The autofill ids of the views to find
         *
         * @return And array containing the views (empty if no views found).
         */
        @NonNull View[] autofillClientFindViewsByAutofillIdTraversal(
                @NonNull AutofillId[] autofillIds);

        /**
         * Finds a view by traversing the hierarchies of the client.
         *
         * @param autofillId The autofill id of the views to find
         *
         * @return The view, or {@code null} if not found
         */
        @Nullable View autofillClientFindViewByAutofillIdTraversal(@NonNull AutofillId autofillId);

        /**
         * Finds a view by a11y id in a given client window.
         *
         * @param viewId The accessibility id of the views to find
         * @param windowId The accessibility window id where to search
         *
         * @return The view, or {@code null} if not found
         */
        @Nullable View autofillClientFindViewByAccessibilityIdTraversal(int viewId, int windowId);

        /**
         * Runs the specified action on the UI thread.
         */
        void autofillClientRunOnUiThread(Runnable action);

        /**
         * Gets the complete component name of this client.
         */
        ComponentName autofillClientGetComponentName();

        /**
         * Gets the activity token
         */
        @Nullable IBinder autofillClientGetActivityToken();

        /**
          * @return Whether compatibility mode is enabled.
          */
        boolean autofillClientIsCompatibilityModeEnabled();

        /**
         * Gets the next unique autofill ID.
         *
         * <p>Typically used to manage views whose content is recycled - see
         * {@link View#setAutofillId(AutofillId)} for more info.
         *
         * @return An ID that is unique in the activity.
         */
        @Nullable AutofillId autofillClientGetNextAutofillId();
    }

    /**
     * @hide
     */
    public AutofillManager(Context context, IAutoFillManager service) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
        mService = service;
        mOptions = context.getAutofillOptions();

        if (mOptions != null) {
            sDebug = (mOptions.loggingLevel & FLAG_ADD_CLIENT_DEBUG) != 0;
            sVerbose = (mOptions.loggingLevel & FLAG_ADD_CLIENT_VERBOSE) != 0;
        }
    }

    /**
     * @hide
     */
    public void enableCompatibilityMode() {
        synchronized (mLock) {
            // The accessibility manager is a singleton so we may need to plug
            // different bridge based on which activity is currently focused
            // in the current process. Since compat would be rarely used, just
            // create and register a new instance every time.
            if (sDebug) {
                Slog.d(TAG, "creating CompatibilityBridge for " + mContext);
            }
            mCompatibilityBridge = new CompatibilityBridge();
        }
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

                final AutofillClient client = getClient();
                if (client != null) {
                    final SyncResultReceiver receiver = new SyncResultReceiver(
                            SYNC_CALLS_TIMEOUT_MS);
                    try {
                        mService.restoreSession(mSessionId, client.autofillClientGetActivityToken(),
                                mServiceClient.asBinder(), receiver);
                        final boolean sessionWasRestored = receiver.getIntResult() == 1;

                        if (!sessionWasRestored) {
                            Log.w(TAG, "Session " + mSessionId + " could not be restored");
                            mSessionId = NO_SESSION;
                            mState = STATE_UNKNOWN;
                        } else {
                            if (sDebug) {
                                Log.d(TAG, "session " + mSessionId + " was restored");
                            }

                            client.autofillClientResetableStateAvailable();
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
     * @see AutofillClient#autofillClientIsVisibleForAutofill()
     *
     * {@hide}
     */
    public void onVisibleForAutofill() {
        // This gets called when the client just got visible at which point the visibility
        // of the tracked views may not have been computed (due to a pending layout, etc).
        // While generally we have no way to know when the UI has settled. We will evaluate
        // the tracked views state at the end of next frame to guarantee that everything
        // that may need to be laid out is laid out.
        Choreographer.getInstance().postCallback(Choreographer.CALLBACK_COMMIT, () -> {
            synchronized (mLock) {
                if (mEnabled && isActiveLocked() && mTrackedViews != null) {
                    mTrackedViews.onVisibleForAutofillChangedLocked();
                }
            }
        }, null);
    }

    /**
     * Called once the client becomes invisible.
     *
     * @see AutofillClient#autofillClientIsVisibleForAutofill()
     *
     * @param isExpiredResponse The response has expired or not
     *
     * {@hide}
     */
    public void onInvisibleForAutofill(boolean isExpiredResponse) {
        synchronized (mLock) {
            mOnInvisibleCalled = true;

            if (isExpiredResponse) {
                // Notify service the response has expired.
                updateSessionLocked(/* id= */ null, /* bounds= */ null, /* value= */ null,
                        ACTION_RESPONSE_EXPIRED, /* flags= */ 0);
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
     * @hide
     */
    @GuardedBy("mLock")
    public boolean isCompatibilityModeEnabledLocked() {
        return mCompatibilityBridge != null;
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
            if (isDisabledByServiceLocked()) {
                return false;
            }
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
            final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
            mService.getFillEventHistory(receiver);
            return receiver.getParcelableResult();
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

    @GuardedBy("mLock")
    private boolean shouldIgnoreViewEnteredLocked(@NonNull AutofillId id, int flags) {
        if (isDisabledByServiceLocked()) {
            if (sVerbose) {
                Log.v(TAG, "ignoring notifyViewEntered(flags=" + flags + ", view=" + id
                        + ") on state " + getStateAsStringLocked() + " because disabled by svc");
            }
            return true;
        }
        if (isFinishedLocked()) {
            // Session already finished: ignore if automatic request and view already entered
            if ((flags & FLAG_MANUAL_REQUEST) == 0 && mEnteredIds != null
                    && mEnteredIds.contains(id)) {
                if (sVerbose) {
                    Log.v(TAG, "ignoring notifyViewEntered(flags=" + flags + ", view=" + id
                            + ") on state " + getStateAsStringLocked()
                            + " because view was already entered: " + mEnteredIds);
                }
                return true;
            }
        }
        return false;
    }

    private boolean isClientVisibleForAutofillLocked() {
        final AutofillClient client = getClient();
        return client != null && client.autofillClientIsVisibleForAutofill();
    }

    private boolean isClientDisablingEnterExitEvent() {
        final AutofillClient client = getClient();
        return client != null && client.isDisablingEnterExitEventForAutofill();
    }

    private void notifyViewEntered(@NonNull View view, int flags) {
        if (!hasAutofillFeature()) {
            return;
        }
        AutofillCallback callback;
        synchronized (mLock) {
            callback = notifyViewEnteredLocked(view, flags);
        }

        if (callback != null) {
            mCallback.onAutofillEvent(view, AutofillCallback.EVENT_INPUT_UNAVAILABLE);
        }
    }

    /** Returns AutofillCallback if need fire EVENT_INPUT_UNAVAILABLE */
    @GuardedBy("mLock")
    private AutofillCallback notifyViewEnteredLocked(@NonNull View view, int flags) {
        final AutofillId id = view.getAutofillId();
        if (shouldIgnoreViewEnteredLocked(id, flags)) return null;

        AutofillCallback callback = null;

        ensureServiceClientAddedIfNeededLocked();

        if (!mEnabled && !mEnabledForAugmentedAutofillOnly) {
            if (sVerbose) Log.v(TAG, "ignoring notifyViewEntered(" + id + "): disabled");

            if (mCallback != null) {
                callback = mCallback;
            }
        } else {
            // don't notify entered when Activity is already in background
            if (!isClientDisablingEnterExitEvent()) {
                final AutofillValue value = view.getAutofillValue();

                if (view instanceof TextView && ((TextView) view).isAnyPasswordInputType()) {
                    flags |= FLAG_PASSWORD_INPUT_TYPE;
                }

                if (!isActiveLocked()) {
                    // Starts new session.
                    startSessionLocked(id, null, value, flags);
                } else {
                    // Update focus on existing session.
                    if (mForAugmentedAutofillOnly && (flags & FLAG_MANUAL_REQUEST) != 0) {
                        if (sDebug) {
                            Log.d(TAG, "notifyViewEntered(" + id + "): resetting "
                                    + "mForAugmentedAutofillOnly on manual request");
                        }
                        mForAugmentedAutofillOnly = false;
                    }
                    updateSessionLocked(id, null, value, ACTION_VIEW_ENTERED, flags);
                }
                addEnteredIdLocked(id);
            }
        }
        return callback;
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
            notifyViewExitedLocked(view);
        }
    }

    @GuardedBy("mLock")
    void notifyViewExitedLocked(@NonNull View view) {
        ensureServiceClientAddedIfNeededLocked();

        if ((mEnabled || mEnabledForAugmentedAutofillOnly) && isActiveLocked()) {
            // dont notify exited when Activity is already in background
            if (!isClientDisablingEnterExitEvent()) {
                final AutofillId id = view.getAutofillId();

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
            if (mForAugmentedAutofillOnly) {
                if (sVerbose) {
                    Log.v(TAG,  "notifyViewVisibilityChanged(): ignoring on augmented only mode");
                }
                return;
            }
            if (mEnabled && isActiveLocked()) {
                final AutofillId id = virtual ? getAutofillId(view, virtualId)
                        : view.getAutofillId();
                if (sVerbose) Log.v(TAG, "visibility changed for " + id + ": " + isVisible);
                if (!isVisible && mFillableIds != null) {
                    if (mFillableIds.contains(id)) {
                        if (sDebug) Log.d(TAG, "Hidding UI when view " + id + " became invisible");
                        requestHideFillUi(id, view);
                    }
                }
                if (mTrackedViews != null) {
                    mTrackedViews.notifyViewVisibilityChangedLocked(id, isVisible);
                } else if (sVerbose) {
                    Log.v(TAG, "Ignoring visibility change on " + id + ": no tracked views");
                }
            } else if (!virtual && isVisible) {
                startAutofillIfNeededLocked(view);
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
        AutofillCallback callback;
        synchronized (mLock) {
            callback = notifyViewEnteredLocked(view, virtualId, bounds, flags);
        }

        if (callback != null) {
            callback.onAutofillEvent(view, virtualId,
                    AutofillCallback.EVENT_INPUT_UNAVAILABLE);
        }
    }

    /** Returns AutofillCallback if need fire EVENT_INPUT_UNAVAILABLE */
    @GuardedBy("mLock")
    private AutofillCallback notifyViewEnteredLocked(View view, int virtualId, Rect bounds,
                                                     int flags) {
        final AutofillId id = getAutofillId(view, virtualId);
        AutofillCallback callback = null;
        if (shouldIgnoreViewEnteredLocked(id, flags)) return callback;

        ensureServiceClientAddedIfNeededLocked();

        if (!mEnabled && !mEnabledForAugmentedAutofillOnly) {
            if (sVerbose) {
                Log.v(TAG, "ignoring notifyViewEntered(" + id + "): disabled");
            }
            if (mCallback != null) {
                callback = mCallback;
            }
        } else {
            // don't notify entered when Activity is already in background
            if (!isClientDisablingEnterExitEvent()) {
                if (view instanceof TextView && ((TextView) view).isAnyPasswordInputType()) {
                    flags |= FLAG_PASSWORD_INPUT_TYPE;
                }

                if (!isActiveLocked()) {
                    // Starts new session.
                    startSessionLocked(id, bounds, null, flags);
                } else {
                    // Update focus on existing session.
                    if (mForAugmentedAutofillOnly && (flags & FLAG_MANUAL_REQUEST) != 0) {
                        if (sDebug) {
                            Log.d(TAG, "notifyViewEntered(" + id + "): resetting "
                                    + "mForAugmentedAutofillOnly on manual request");
                        }
                        mForAugmentedAutofillOnly = false;
                    }
                    updateSessionLocked(id, bounds, null, ACTION_VIEW_ENTERED, flags);
                }
                addEnteredIdLocked(id);
            }
        }
        return callback;
    }

    @GuardedBy("mLock")
    private void addEnteredIdLocked(@NonNull AutofillId id) {
        if (mEnteredIds == null) {
            mEnteredIds = new ArraySet<>(1);
        }
        id.resetSessionId();
        mEnteredIds.add(id);
    }

    /**
     * Called when a virtual view that supports autofill is exited.
     *
     * @param view the virtual view parent.
     * @param virtualId id identifying the virtual child inside the parent view.
     */
    public void notifyViewExited(@NonNull View view, int virtualId) {
        if (sVerbose) Log.v(TAG, "notifyViewExited(" + view.getAutofillId() + ", " + virtualId);
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            notifyViewExitedLocked(view, virtualId);
        }
    }

    @GuardedBy("mLock")
    private void notifyViewExitedLocked(@NonNull View view, int virtualId) {
        ensureServiceClientAddedIfNeededLocked();

        if ((mEnabled || mEnabledForAugmentedAutofillOnly) && isActiveLocked()) {
            // don't notify exited when Activity is already in background
            if (!isClientDisablingEnterExitEvent()) {
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
                view.setAutofilled(false, false);
            } else {
                id = view.getAutofillId();
                if (mLastAutofilledData.containsKey(id)) {
                    value = view.getAutofillValue();
                    valueWasRead = true;

                    if (Objects.equals(mLastAutofilledData.get(id), value)) {
                        view.setAutofilled(true, false);
                    } else {
                        view.setAutofilled(false, false);
                        mLastAutofilledData.remove(id);
                    }
                } else {
                    view.setAutofilled(false, false);
                }
            }

            if (mForAugmentedAutofillOnly) {
                if (sVerbose) {
                    Log.v(TAG,  "notifyValueChanged(): not notifying system server on "
                            + "augmented-only mode");
                }
                return;
            }
            if (!mEnabled || !isActiveLocked()) {
                if (!startAutofillIfNeededLocked(view)) {
                    if (sVerbose) {
                        Log.v(TAG, "notifyValueChanged(" + view.getAutofillId()
                                + "): ignoring on state " + getStateAsStringLocked());
                    }
                }
                return;
            }

            if (id == null) {
                id = view.getAutofillId();
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
            if (mForAugmentedAutofillOnly) {
                if (sVerbose) Log.v(TAG,  "notifyValueChanged(): ignoring on augmented only mode");
                return;
            }
            if (!mEnabled || !isActiveLocked()) {
                if (sVerbose) {
                    Log.v(TAG, "notifyValueChanged(" + view.getAutofillId() + ":" + virtualId
                            + "): ignoring on state " + getStateAsStringLocked());
                }
                return;
            }

            final AutofillId id = getAutofillId(view, virtualId);
            updateSessionLocked(id, null, value, ACTION_VALUE_CHANGED, 0);
        }
    }

    /**
     * Called to indicate a {@link View} is clicked.
     *
     * @param view view that has been clicked.
     */
    public void notifyViewClicked(@NonNull View view) {
        notifyViewClicked(view.getAutofillId());
    }

    /**
     * Called to indicate a virtual view has been clicked.
     *
     * @param view the virtual view parent.
     * @param virtualId id identifying the virtual child inside the parent view.
     */
    public void notifyViewClicked(@NonNull View view, int virtualId) {
        notifyViewClicked(getAutofillId(view, virtualId));
    }

    private void notifyViewClicked(AutofillId id) {
        if (!hasAutofillFeature()) {
            return;
        }
        if (sVerbose) Log.v(TAG, "notifyViewClicked(): id=" + id + ", trigger=" + mSaveTriggerId);

        synchronized (mLock) {
            if (!mEnabled || !isActiveLocked()) {
                return;
            }
            if (mSaveTriggerId != null && mSaveTriggerId.equals(id)) {
                if (sDebug) Log.d(TAG, "triggering commit by click of " + id);
                commitLocked();
                mMetricsLogger.write(newLog(MetricsEvent.AUTOFILL_SAVE_EXPLICITLY_TRIGGERED));
            }
        }
    }

    /**
     * Called by {@link android.app.Activity} to commit or cancel the session on finish.
     *
     * @hide
     */
    public void onActivityFinishing() {
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            if (mSaveOnFinish) {
                if (sDebug) Log.d(TAG, "onActivityFinishing(): calling commitLocked()");
                commitLocked();
            } else {
                if (sDebug) Log.d(TAG, "onActivityFinishing(): calling cancelLocked()");
                cancelLocked();
            }
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
        if (sVerbose) Log.v(TAG, "commit() called by app");
        synchronized (mLock) {
            commitLocked();
        }
    }

    @GuardedBy("mLock")
    private void commitLocked() {
        if (!mEnabled && !isActiveLocked()) {
            return;
        }
        finishSessionLocked();
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
        if (sVerbose) Log.v(TAG, "cancel() called by app");
        if (!hasAutofillFeature()) {
            return;
        }
        synchronized (mLock) {
            cancelLocked();
        }
    }

    @GuardedBy("mLock")
    private void cancelLocked() {
        if (!mEnabled && !isActiveLocked()) {
            return;
        }
        cancelSessionLocked();
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

        final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.isServiceEnabled(mContext.getUserId(), mContext.getPackageName(), receiver);
            return receiver.getIntResult() == 1;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Returns the component name of the {@link AutofillService} that is enabled for the current
     * user.
     */
    @Nullable
    public ComponentName getAutofillServiceComponentName() {
        if (mService == null) return null;

        final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.getAutofillServiceComponentName(receiver);
            return receiver.getParcelableResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Gets the id of the {@link UserData} used for
     * <a href="AutofillService.html#FieldClassification">field classification</a>.
     *
     * <p>This method is useful when the service must check the status of the {@link UserData} in
     * the device without fetching the whole object.
     *
     * <p><b>Note:</b> This method should only be called by an app providing an autofill service,
     * and it's ignored if the caller currently doesn't have an enabled autofill service for
     * the user.
     *
     * @return id of the {@link UserData} previously set by {@link #setUserData(UserData)}
     * or {@code null} if it was reset or if the caller currently does not have an enabled autofill
     * service for the user.
     */
    @Nullable public String getUserDataId() {
        try {
            final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
            mService.getUserDataId(receiver);
            return receiver.getStringResult();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Gets the user data used for
     * <a href="AutofillService.html#FieldClassification">field classification</a>.
     *
     * <p><b>Note:</b> This method should only be called by an app providing an autofill service,
     * and it's ignored if the caller currently doesn't have an enabled autofill service for
     * the user.
     *
     * @return value previously set by {@link #setUserData(UserData)} or {@code null} if it was
     * reset or if the caller currently does not have an enabled autofill service for the user.
     */
    @Nullable public UserData getUserData() {
        try {
            final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
            mService.getUserData(receiver);
            return receiver.getParcelableResult();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Sets the {@link UserData} used for
     * <a href="AutofillService.html#FieldClassification">field classification</a>
     *
     * <p><b>Note:</b> This method should only be called by an app providing an autofill service,
     * and it's ignored if the caller currently doesn't have an enabled autofill service for
     * the user.
     */
    public void setUserData(@Nullable UserData userData) {
        try {
            mService.setUserData(userData);
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
        }
    }

    /**
     * Checks if <a href="AutofillService.html#FieldClassification">field classification</a> is
     * enabled.
     *
     * <p>As field classification is an expensive operation, it could be disabled, either
     * temporarily (for example, because the service exceeded a rate-limit threshold) or
     * permanently (for example, because the device is a low-level device).
     *
     * <p><b>Note:</b> This method should only be called by an app providing an autofill service,
     * and it's ignored if the caller currently doesn't have an enabled autofill service for
     * the user.
     */
    public boolean isFieldClassificationEnabled() {
        final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.isFieldClassificationEnabled(receiver);
            return receiver.getIntResult() == 1;
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return false;
        }
    }

    /**
     * Gets the name of the default algorithm used for
     * <a href="AutofillService.html#FieldClassification">field classification</a>.
     *
     * <p>The default algorithm is used when the algorithm on {@link UserData} is invalid or not
     * set.
     *
     * <p><b>Note:</b> This method should only be called by an app providing an autofill service,
     * and it's ignored if the caller currently doesn't have an enabled autofill service for
     * the user.
     */
    @Nullable
    public String getDefaultFieldClassificationAlgorithm() {
        final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.getDefaultFieldClassificationAlgorithm(receiver);
            return receiver.getStringResult();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
        }
    }

    /**
     * Gets the name of all algorithms currently available for
     * <a href="AutofillService.html#FieldClassification">field classification</a>.
     *
     * <p><b>Note:</b> This method should only be called by an app providing an autofill service,
     * and it returns an empty list if the caller currently doesn't have an enabled autofill service
     * for the user.
     */
    @NonNull
    public List<String> getAvailableFieldClassificationAlgorithms() {
        final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.getAvailableFieldClassificationAlgorithms(receiver);
            final String[] algorithms = receiver.getStringArrayResult();
            return algorithms != null ? Arrays.asList(algorithms) : Collections.emptyList();
        } catch (RemoteException e) {
            e.rethrowFromSystemServer();
            return null;
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

        final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        try {
            mService.isServiceSupported(mContext.getUserId(), receiver);
            return receiver.getIntResult() == 1;
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    // Note: don't need to use locked suffix because mContext is final.
    private AutofillClient getClient() {
        final AutofillClient client = mContext.getAutofillClient();
        if (client == null && sVerbose) {
            Log.v(TAG, "No AutofillClient for " + mContext.getPackageName() + " on context "
                    + mContext);
        }
        return client;
    }

    /**
     * Check if autofill ui is showing, must be called on UI thread.
     * @hide
     */
    public boolean isAutofillUiShowing() {
        final AutofillClient client = mContext.getAutofillClient();
        return client != null && client.autofillClientIsFillUiShowing();
    }

    /** @hide */
    public void onAuthenticationResult(int authenticationId, Intent data, View focusView) {
        if (!hasAutofillFeature()) {
            return;
        }
        // TODO: the result code is being ignored, so this method is not reliably
        // handling the cases where it's not RESULT_OK: it works fine if the service does not
        // set the EXTRA_AUTHENTICATION_RESULT extra, but it could cause weird results if the
        // service set the extra and returned RESULT_CANCELED...

        if (sDebug) {
            Log.d(TAG, "onAuthenticationResult(): id= " + authenticationId + ", data=" + data);
        }

        synchronized (mLock) {
            if (!isActiveLocked()) {
                return;
            }
            // If authenticate activity closes itself during onCreate(), there is no onStop/onStart
            // of app activity.  We enforce enter event to re-show fill ui in such case.
            // CTS example:
            //     LoginActivityTest#testDatasetAuthTwoFieldsUserCancelsFirstAttempt
            //     LoginActivityTest#testFillResponseAuthBothFieldsUserCancelsFirstAttempt
            if (!mOnInvisibleCalled && focusView != null
                    && focusView.canNotifyAutofillEnterExitEvent()) {
                notifyViewExitedLocked(focusView);
                notifyViewEnteredLocked(focusView, 0);
            }
            if (data == null) {
                // data is set to null when result is not RESULT_OK
                return;
            }

            final Parcelable result = data.getParcelableExtra(EXTRA_AUTHENTICATION_RESULT);
            final Bundle responseData = new Bundle();
            responseData.putParcelable(EXTRA_AUTHENTICATION_RESULT, result);
            final Bundle newClientState = data.getBundleExtra(EXTRA_CLIENT_STATE);
            if (newClientState != null) {
                responseData.putBundle(EXTRA_CLIENT_STATE, newClientState);
            }
            try {
                mService.setAuthenticationResult(responseData, mSessionId, authenticationId,
                        mContext.getUserId());
            } catch (RemoteException e) {
                Log.e(TAG, "Error delivering authentication result", e);
            }
        }
    }

    /**
     * Gets the next unique autofill ID for the activity context.
     *
     * <p>Typically used to manage views whose content is recycled - see
     * {@link View#setAutofillId(AutofillId)} for more info.
     *
     * @return An ID that is unique in the activity, or {@code null} if autofill is not supported in
     * the {@link Context} associated with this {@link AutofillManager}.
     */
    @Nullable
    public AutofillId getNextAutofillId() {
        final AutofillClient client = getClient();
        if (client == null) return null;

        final AutofillId id = client.autofillClientGetNextAutofillId();

        if (id == null && sDebug) {
            Log.d(TAG, "getNextAutofillId(): client " + client + " returned null");
        }

        return id;
    }

    private static AutofillId getAutofillId(View parent, int virtualId) {
        return new AutofillId(parent.getAutofillViewId(), virtualId);
    }

    @GuardedBy("mLock")
    private void startSessionLocked(@NonNull AutofillId id, @NonNull Rect bounds,
            @NonNull AutofillValue value, int flags) {
        if (mEnteredForAugmentedAutofillIds != null
                && mEnteredForAugmentedAutofillIds.contains(id)
                || mEnabledForAugmentedAutofillOnly) {
            if (sVerbose) Log.v(TAG, "Starting session for augmented autofill on " + id);
            flags |= FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY;
        }
        if (sVerbose) {
            Log.v(TAG, "startSessionLocked(): id=" + id + ", bounds=" + bounds + ", value=" + value
                    + ", flags=" + flags + ", state=" + getStateAsStringLocked()
                    + ", compatMode=" + isCompatibilityModeEnabledLocked()
                    + ", augmentedOnly=" + mForAugmentedAutofillOnly
                    + ", enabledAugmentedOnly=" + mEnabledForAugmentedAutofillOnly
                    + ", enteredIds=" + mEnteredIds);
        }
        // We need to reset the augmented-only state when a manual request is made, as it's possible
        // that the service returned null for the first request and now the user is manually
        // requesting autofill to trigger a custom UI provided by the service.
        if (mForAugmentedAutofillOnly && !mEnabledForAugmentedAutofillOnly
                && (flags & FLAG_MANUAL_REQUEST) != 0) {
            if (sVerbose) {
                Log.v(TAG, "resetting mForAugmentedAutofillOnly on manual autofill request");
            }
            mForAugmentedAutofillOnly = false;
        }
        if (mState != STATE_UNKNOWN && !isFinishedLocked() && (flags & FLAG_MANUAL_REQUEST) == 0) {
            if (sVerbose) {
                Log.v(TAG, "not automatically starting session for " + id
                        + " on state " + getStateAsStringLocked() + " and flags " + flags);
            }
            return;
        }
        try {
            final AutofillClient client = getClient();
            if (client == null) return; // NOTE: getClient() already logged it..

            final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
            final ComponentName componentName = client.autofillClientGetComponentName();

            if (!mEnabledForAugmentedAutofillOnly && mOptions != null
                    && mOptions.isAutofillDisabledLocked(componentName)) {
                if (mOptions.isAugmentedAutofillEnabled(mContext)) {
                    if (sDebug) {
                        Log.d(TAG, "startSession(" + componentName + "): disabled by service but "
                                + "whitelisted for augmented autofill");
                        flags |= FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY;
                    }
                } else {
                    if (sDebug) {
                        Log.d(TAG, "startSession(" + componentName + "): ignored because "
                                + "disabled by service and not whitelisted for augmented autofill");
                    }
                    setSessionFinished(AutofillManager.STATE_DISABLED_BY_SERVICE, null);
                    client.autofillClientResetableStateAvailable();
                    return;
                }
            }

            mService.startSession(client.autofillClientGetActivityToken(),
                    mServiceClient.asBinder(), id, bounds, value, mContext.getUserId(),
                    mCallback != null, flags, componentName,
                    isCompatibilityModeEnabledLocked(), receiver);
            mSessionId = receiver.getIntResult();
            if (mSessionId != NO_SESSION) {
                mState = STATE_ACTIVE;
            }
            final int extraFlags = receiver.getOptionalExtraIntResult(0);
            if ((extraFlags & RECEIVER_FLAG_SESSION_FOR_AUGMENTED_AUTOFILL_ONLY) != 0) {
                if (sDebug) Log.d(TAG, "startSession(" + componentName + "): for augmented only");
                mForAugmentedAutofillOnly = true;
            }
            client.autofillClientResetableStateAvailable();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        } catch (SyncResultReceiver.TimeoutException e) {
            // no-op, just log the error message.
            Log.w(TAG, "Exception getting result from SyncResultReceiver: " + e);
        }
    }

    @GuardedBy("mLock")
    private void finishSessionLocked() {
        if (sVerbose) Log.v(TAG, "finishSessionLocked(): " + getStateAsStringLocked());

        if (!isActiveLocked()) return;

        try {
            mService.finishSession(mSessionId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        resetSessionLocked(/* resetEnteredIds= */ true);
    }

    @GuardedBy("mLock")
    private void cancelSessionLocked() {
        if (sVerbose) Log.v(TAG, "cancelSessionLocked(): " + getStateAsStringLocked());

        if (!isActiveLocked()) return;

        try {
            mService.cancelSession(mSessionId, mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        resetSessionLocked(/* resetEnteredIds= */ true);
    }

    @GuardedBy("mLock")
    private void resetSessionLocked(boolean resetEnteredIds) {
        mSessionId = NO_SESSION;
        mState = STATE_UNKNOWN;
        mTrackedViews = null;
        mFillableIds = null;
        mSaveTriggerId = null;
        mIdShownFillUi = null;
        if (resetEnteredIds) {
            mEnteredIds = null;
        }
    }

    @GuardedBy("mLock")
    private void updateSessionLocked(AutofillId id, Rect bounds, AutofillValue value, int action,
            int flags) {
        if (sVerbose) {
            Log.v(TAG, "updateSessionLocked(): id=" + id + ", bounds=" + bounds
                    + ", value=" + value + ", action=" + action + ", flags=" + flags);
        }
        try {
            mService.updateSession(mSessionId, id, bounds, value, action, flags,
                    mContext.getUserId());
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    @GuardedBy("mLock")
    private void ensureServiceClientAddedIfNeededLocked() {
        final AutofillClient client = getClient();
        if (client == null) {
            return;
        }

        if (mServiceClient == null) {
            mServiceClient = new AutofillManagerClient(this);
            try {
                final int userId = mContext.getUserId();
                final SyncResultReceiver receiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
                mService.addClient(mServiceClient, client.autofillClientGetComponentName(),
                        userId, receiver);
                final int flags = receiver.getIntResult();
                mEnabled = (flags & FLAG_ADD_CLIENT_ENABLED) != 0;
                sDebug = (flags & FLAG_ADD_CLIENT_DEBUG) != 0;
                sVerbose = (flags & FLAG_ADD_CLIENT_VERBOSE) != 0;
                mEnabledForAugmentedAutofillOnly = (flags
                        & FLAG_ADD_CLIENT_ENABLED_FOR_AUGMENTED_AUTOFILL_ONLY) != 0;
                if (sVerbose) {
                    Log.v(TAG, "receiver results: flags=" + flags + " enabled=" + mEnabled
                            + ", enabledForAugmentedOnly: " + mEnabledForAugmentedAutofillOnly);
                }
                final IAutoFillManager service = mService;
                final IAutoFillManagerClient serviceClient = mServiceClient;
                mServiceClientCleaner = Cleaner.create(this, () -> {
                    // TODO(b/123100811): call service to also remove reference to
                    // mAugmentedAutofillServiceClient
                    try {
                        service.removeClient(serviceClient, userId);
                    } catch (RemoteException e) {
                    }
                });
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    @GuardedBy("mLock")
    private boolean startAutofillIfNeededLocked(View view) {
        if (mState == STATE_UNKNOWN
                && mSessionId == NO_SESSION
                && view instanceof EditText
                && !TextUtils.isEmpty(((EditText) view).getText())
                && !view.isFocused()
                && view.isImportantForAutofill()
                && view.isLaidOut()
                && view.isVisibleToUser()) {

            ensureServiceClientAddedIfNeededLocked();

            if (sVerbose) {
                Log.v(TAG, "startAutofillIfNeededLocked(): enabled=" + mEnabled);
            }
            if (mEnabled && !isClientDisablingEnterExitEvent()) {
                final AutofillId id = view.getAutofillId();
                final AutofillValue value = view.getAutofillValue();
                // Starts new session.
                startSessionLocked(id, /* bounds= */ null, /* value= */ null, /* flags= */ 0);
                // Updates value.
                updateSessionLocked(id, /* bounds= */ null, value, ACTION_VALUE_CHANGED,
                        /* flags= */ 0);
                addEnteredIdLocked(id);
                return true;
            }
        }
        return false;
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

    /**
     * Explicitly limits augmented autofill to the given packages and activities.
     *
     * <p>To reset the whitelist, call it passing {@code null} to both arguments.
     *
     * <p>Useful when the service wants to restrict augmented autofill to a category of apps, like
     * apps that uses addresses. For example, if the service wants to support augmented autofill on
     * all activities of app {@code AddressApp1} and just activities {@code act1} and {@code act2}
     * of {@code AddressApp2}, it would call:
     * {@code setAugmentedAutofillWhitelist(Arrays.asList("AddressApp1"),
     * Arrays.asList(new ComponentName("AddressApp2", "act1"),
     * new ComponentName("AddressApp2", "act2")));}
     *
     * <p><b>Note:</b> This method should only be called by the app providing the augmented autofill
     * service, and it's ignored if the caller isn't it.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public void setAugmentedAutofillWhitelist(@Nullable Set<String> packages,
            @Nullable Set<ComponentName> activities) {
        if (!hasAutofillFeature()) {
            return;
        }

        final SyncResultReceiver resultReceiver = new SyncResultReceiver(SYNC_CALLS_TIMEOUT_MS);
        final int resultCode;
        try {
            mService.setAugmentedAutofillWhitelist(toList(packages), toList(activities),
                    resultReceiver);
            resultCode = resultReceiver.getIntResult();
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
        switch (resultCode) {
            case RESULT_OK:
                return;
            case RESULT_CODE_NOT_SERVICE:
                throw new SecurityException("caller is not user's Augmented Autofill Service");
            default:
                Log.wtf(TAG, "setAugmentedAutofillWhitelist(): received invalid result: "
                        + resultCode);
        }
    }

    /**
     * Notifies that a non-autofillable view was entered because the activity is whitelisted for
     * augmented autofill.
     *
     * <p>This method is necessary to set the right flag on start, so the server-side session
     * doesn't trigger the standard autofill workflow, but the augmented's instead.
     *
     * @hide
     */
    public void notifyViewEnteredForAugmentedAutofill(@NonNull View view) {
        final AutofillId id = view.getAutofillId();
        synchronized (mLock) {
            if (mEnteredForAugmentedAutofillIds == null) {
                mEnteredForAugmentedAutofillIds = new ArraySet<>(1);
            }
            mEnteredForAugmentedAutofillIds.add(id);
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
                AutofillClient client = getClient();

                if (client != null) {
                    if (client.autofillClientRequestShowFillUi(anchor, width, height,
                            anchorBounds, presenter)) {
                        callback = mCallback;
                        mIdShownFillUi = id;
                    }
                }
            }
        }

        if (callback != null) {
            if (id.isVirtualInt()) {
                callback.onAutofillEvent(anchor, id.getVirtualChildIntId(),
                        AutofillCallback.EVENT_INPUT_SHOWN);
            } else {
                callback.onAutofillEvent(anchor, AutofillCallback.EVENT_INPUT_SHOWN);
            }
        }
    }

    private void authenticate(int sessionId, int authenticationId, IntentSender intent,
            Intent fillInIntent, boolean authenticateInline) {
        synchronized (mLock) {
            if (sessionId == mSessionId) {
                final AutofillClient client = getClient();
                if (client != null) {
                    // clear mOnInvisibleCalled and we will see if receive onInvisibleForAutofill()
                    // before onAuthenticationResult()
                    mOnInvisibleCalled = false;
                    client.autofillClientAuthenticate(authenticationId, intent, fillInIntent,
                            authenticateInline);
                }
            }
        }
    }

    private void dispatchUnhandledKey(int sessionId, AutofillId id, KeyEvent keyEvent) {
        final View anchor = findView(id);
        if (anchor == null) {
            return;
        }

        synchronized (mLock) {
            if (mSessionId == sessionId) {
                AutofillClient client = getClient();

                if (client != null) {
                    client.autofillClientDispatchUnhandledKey(anchor, keyEvent);
                }
            }
        }
    }

    /** @hide */
    public static final int SET_STATE_FLAG_ENABLED = 0x01;
    /** @hide */
    public static final int SET_STATE_FLAG_RESET_SESSION = 0x02;
    /** @hide */
    public static final int SET_STATE_FLAG_RESET_CLIENT = 0x04;
    /** @hide */
    public static final int SET_STATE_FLAG_DEBUG = 0x08;
    /** @hide */
    public static final int SET_STATE_FLAG_VERBOSE = 0x10;
    /** @hide */
    public static final int SET_STATE_FLAG_FOR_AUTOFILL_ONLY = 0x20;

    private void setState(int flags) {
        if (sVerbose) {
            Log.v(TAG, "setState(" + flags + ": " + DebugUtils.flagsToString(AutofillManager.class,
                    "SET_STATE_FLAG_", flags) + ")");
        }
        synchronized (mLock) {
            if ((flags & SET_STATE_FLAG_FOR_AUTOFILL_ONLY) != 0) {
                mForAugmentedAutofillOnly = true;
                // NOTE: returning right away as this is the only flag set, at least currently...
                return;
            }
            mEnabled = (flags & SET_STATE_FLAG_ENABLED) != 0;
            if (!mEnabled || (flags & SET_STATE_FLAG_RESET_SESSION) != 0) {
                // Reset the session state
                resetSessionLocked(/* resetEnteredIds= */ true);
            }
            if ((flags & SET_STATE_FLAG_RESET_CLIENT) != 0) {
                // Reset connection to system
                mServiceClient = null;
                mAugmentedAutofillServiceClient = null;
                if (mServiceClientCleaner != null) {
                    mServiceClientCleaner.clean();
                    mServiceClientCleaner = null;
                }
                notifyReenableAutofill();
            }
        }
        sDebug = (flags & SET_STATE_FLAG_DEBUG) != 0;
        sVerbose = (flags & SET_STATE_FLAG_VERBOSE) != 0;
    }

    /**
     * Sets a view as autofilled if the current value is the {code targetValue}.
     *
     * @param view The view that is to be autofilled
     * @param targetValue The value we want to fill into view
     */
    private void setAutofilledIfValuesIs(@NonNull View view, @Nullable AutofillValue targetValue,
            boolean hideHighlight) {
        AutofillValue currentValue = view.getAutofillValue();
        if (Objects.equals(currentValue, targetValue)) {
            synchronized (mLock) {
                if (mLastAutofilledData == null) {
                    mLastAutofilledData = new ParcelableMap(1);
                }
                mLastAutofilledData.put(view.getAutofillId(), targetValue);
            }
            view.setAutofilled(true, hideHighlight);
        }
    }

    private void autofill(int sessionId, List<AutofillId> ids, List<AutofillValue> values,
            boolean hideHighlight) {
        synchronized (mLock) {
            if (sessionId != mSessionId) {
                return;
            }

            final AutofillClient client = getClient();
            if (client == null) {
                return;
            }

            final int itemCount = ids.size();
            int numApplied = 0;
            ArrayMap<View, SparseArray<AutofillValue>> virtualValues = null;
            final View[] views = client.autofillClientFindViewsByAutofillIdTraversal(
                    Helper.toArray(ids));

            ArrayList<AutofillId> failedIds = null;

            for (int i = 0; i < itemCount; i++) {
                final AutofillId id = ids.get(i);
                final AutofillValue value = values.get(i);
                final View view = views[i];
                if (view == null) {
                    // Most likely view has been removed after the initial request was sent to the
                    // the service; this is fine, but we need to update the view status in the
                    // server side so it can be triggered again.
                    Log.d(TAG, "autofill(): no View with id " + id);
                    if (failedIds == null) {
                        failedIds = new ArrayList<>();
                    }
                    failedIds.add(id);
                    continue;
                }
                if (id.isVirtualInt()) {
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
                    valuesByParent.put(id.getVirtualChildIntId(), value);
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
                    setAutofilledIfValuesIs(view, value, hideHighlight);

                    numApplied++;
                }
            }

            if (failedIds != null) {
                if (sVerbose) {
                    Log.v(TAG, "autofill(): total failed views: " + failedIds);
                }
                try {
                    mService.setAutofillFailure(mSessionId, failedIds, mContext.getUserId());
                } catch (RemoteException e) {
                    // In theory, we could ignore this error since it's not a big deal, but
                    // in reality, we rather crash the app anyways, as the failure could be
                    // a consequence of something going wrong on the server side...
                    e.rethrowFromSystemServer();
                }
            }

            if (virtualValues != null) {
                for (int i = 0; i < virtualValues.size(); i++) {
                    final View parent = virtualValues.keyAt(i);
                    final SparseArray<AutofillValue> childrenValues = virtualValues.valueAt(i);
                    parent.autofill(childrenValues);
                    numApplied += childrenValues.size();
                    // TODO: we should provide a callback so the parent can call failures; something
                    // like notifyAutofillFailed(View view, int[] childrenIds);
                }
            }

            mMetricsLogger.write(newLog(MetricsEvent.AUTOFILL_DATASET_APPLIED)
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_VALUES, itemCount)
                    .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_VIEWS_FILLED, numApplied));
        }
    }

    private LogMaker newLog(int category) {
        final LogMaker log = new LogMaker(category)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_SESSION_ID, mSessionId);

        if (isCompatibilityModeEnabledLocked()) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_COMPAT_MODE, 1);
        }
        final AutofillClient client = getClient();
        if (client == null) {
            // Client should never be null here, but it doesn't hurt to check...
            log.setPackageName(mContext.getPackageName());
        } else {
            log.setComponentName(client.autofillClientGetComponentName());
        }
        return log;
    }

    /**
     *  Set the tracked views.
     *
     * @param trackedIds The views to be tracked.
     * @param saveOnAllViewsInvisible Finish the session once all tracked views are invisible.
     * @param saveOnFinish Finish the session once the activity is finished.
     * @param fillableIds Views that might anchor FillUI.
     * @param saveTriggerId View that when clicked triggers commit().
     */
    private void setTrackedViews(int sessionId, @Nullable AutofillId[] trackedIds,
            boolean saveOnAllViewsInvisible, boolean saveOnFinish,
            @Nullable AutofillId[] fillableIds, @Nullable AutofillId saveTriggerId) {
        if (saveTriggerId != null) {
            saveTriggerId.resetSessionId();
        }
        synchronized (mLock) {
            if (sVerbose) {
                Log.v(TAG, "setTrackedViews(): sessionId=" + sessionId
                        + ", trackedIds=" + Arrays.toString(trackedIds)
                        + ", saveOnAllViewsInvisible=" + saveOnAllViewsInvisible
                        + ", saveOnFinish=" + saveOnFinish
                        + ", fillableIds=" + Arrays.toString(fillableIds)
                        + ", saveTrigerId=" + saveTriggerId
                        + ", mFillableIds=" + mFillableIds
                        + ", mEnabled=" + mEnabled
                        + ", mSessionId=" + mSessionId);

            }
            if (mEnabled && mSessionId == sessionId) {
                if (saveOnAllViewsInvisible) {
                    mTrackedViews = new TrackedViews(trackedIds);
                } else {
                    mTrackedViews = null;
                }
                mSaveOnFinish = saveOnFinish;
                if (fillableIds != null) {
                    if (mFillableIds == null) {
                        mFillableIds = new ArraySet<>(fillableIds.length);
                    }
                    for (AutofillId id : fillableIds) {
                        id.resetSessionId();
                        mFillableIds.add(id);
                    }
                }

                if (mSaveTriggerId != null && !mSaveTriggerId.equals(saveTriggerId)) {
                    // Turn off trigger on previous view id.
                    setNotifyOnClickLocked(mSaveTriggerId, false);
                }

                if (saveTriggerId != null && !saveTriggerId.equals(mSaveTriggerId)) {
                    // Turn on trigger on new view id.
                    mSaveTriggerId = saveTriggerId;
                    setNotifyOnClickLocked(mSaveTriggerId, true);
                }
            }
        }
    }

    private void setNotifyOnClickLocked(@NonNull AutofillId id, boolean notify) {
        final View view = findView(id);
        if (view == null) {
            Log.w(TAG, "setNotifyOnClick(): invalid id: " + id);
            return;
        }
        view.setNotifyAutofillManagerOnClick(notify);
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
     *  FillResponse), {@link #STATE_UNKNOWN} (because the session was removed),
     *  {@link #STATE_UNKNOWN_COMPAT_MODE} (beucase the session was finished when the URL bar
     *  changed on compat mode), {@link #STATE_UNKNOWN_FAILED} (because the session was finished
     *  when the service failed to fullfil the request, or {@link #STATE_DISABLED_BY_SERVICE}
     *  (because the autofill service or {@link #STATE_DISABLED_BY_SERVICE} (because the autofill
     *  service disabled further autofill requests for the activity).
     * @param autofillableIds list of ids that could trigger autofill, use to not handle a new
     *  session when they're entered.
     */
    private void setSessionFinished(int newState, @Nullable List<AutofillId> autofillableIds) {
        if (autofillableIds != null) {
            for (int i = 0; i < autofillableIds.size(); i++) {
                autofillableIds.get(i).resetSessionId();
            }
        }
        synchronized (mLock) {
            if (sVerbose) {
                Log.v(TAG, "setSessionFinished(): from " + getStateAsStringLocked() + " to "
                        + getStateAsString(newState) + "; autofillableIds=" + autofillableIds);
            }
            if (autofillableIds != null) {
                mEnteredIds = new ArraySet<>(autofillableIds);
            }
            if (newState == STATE_UNKNOWN_COMPAT_MODE || newState == STATE_UNKNOWN_FAILED) {
                resetSessionLocked(/* resetEnteredIds= */ true);
                mState = STATE_UNKNOWN;
            } else {
                resetSessionLocked(/* resetEnteredIds= */ false);
                mState = newState;
            }
        }
    }

    /**
     * Gets a {@link AugmentedAutofillManagerClient} for this {@link AutofillManagerClient}.
     *
     * <p>These are 2 distinct objects because we need to restrict what the Augmented Autofill
     * service can do (which is defined by {@code IAugmentedAutofillManagerClient.aidl}).
     */
    private void getAugmentedAutofillClient(@NonNull IResultReceiver result) {
        synchronized (mLock) {
            if (mAugmentedAutofillServiceClient == null) {
                mAugmentedAutofillServiceClient = new AugmentedAutofillManagerClient(this);
            }
            final Bundle resultData = new Bundle();
            resultData.putBinder(EXTRA_AUGMENTED_AUTOFILL_CLIENT,
                    mAugmentedAutofillServiceClient.asBinder());

            try {
                result.send(0, resultData);
            } catch (RemoteException e) {
                Log.w(TAG, "Could not send AugmentedAutofillClient back: " + e);
            }
        }
    }

    private void requestShowSoftInput(@NonNull AutofillId id) {
        if (sVerbose) Log.v(TAG, "requestShowSoftInput(" + id + ")");
        final AutofillClient client = getClient();
        if (client == null) {
            return;
        }
        final View view = client.autofillClientFindViewByAutofillIdTraversal(id);
        if (view == null) {
            if (sVerbose) Log.v(TAG, "View is not found");
            return;
        }
        final Handler handler = view.getHandler();
        if (handler == null) {
            if (sVerbose) Log.v(TAG, "Ignoring requestShowSoftInput due to no handler in view");
            return;
        }
        if (handler.getLooper() != Looper.myLooper()) {
            // The view is running on a different thread than our own, so we need to reschedule
            // our work for over there.
            if (sVerbose) Log.v(TAG, "Scheduling showSoftInput() on the view UI thread");
            handler.post(() -> requestShowSoftInputInViewThread(view));
        } else {
            requestShowSoftInputInViewThread(view);
        }
    }

    // This method must be called from within the View thread.
    private static void requestShowSoftInputInViewThread(@NonNull View view) {
        if (!view.isFocused()) {
            Log.w(TAG, "Ignoring requestShowSoftInput() due to non-focused view");
            return;
        }
        final InputMethodManager inputMethodManager = view.getContext().getSystemService(
                InputMethodManager.class);
        boolean ret = inputMethodManager.showSoftInput(view, /*flags=*/ 0);
        if (sVerbose) Log.v(TAG, " InputMethodManager.showSoftInput returns " + ret);
    }

    /** @hide */
    public void requestHideFillUi() {
        requestHideFillUi(mIdShownFillUi, true);
    }

    private void requestHideFillUi(AutofillId id, boolean force) {
        final View anchor = id == null ? null : findView(id);
        if (sVerbose) Log.v(TAG, "requestHideFillUi(" + id + "): anchor = " + anchor);
        if (anchor == null) {
            if (force) {
                // When user taps outside autofill window, force to close fill ui even id does
                // not match.
                AutofillClient client = getClient();
                if (client != null) {
                    client.autofillClientRequestHideFillUi();
                }
            }
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
            AutofillClient client = getClient();
            if (client != null) {
                if (client.autofillClientRequestHideFillUi()) {
                    mIdShownFillUi = null;
                    callback = mCallback;
                }
            }
        }

        if (callback != null) {
            if (id.isVirtualInt()) {
                callback.onAutofillEvent(anchor, id.getVirtualChildIntId(),
                        AutofillCallback.EVENT_INPUT_HIDDEN);
            } else {
                callback.onAutofillEvent(anchor, AutofillCallback.EVENT_INPUT_HIDDEN);
            }
        }
    }

    private void notifyDisableAutofill(long disableDuration, ComponentName componentName) {
        synchronized (mLock) {
            if (mOptions == null) {
                return;
            }
            long expiration = SystemClock.elapsedRealtime() + disableDuration;
            // Protect it against overflow
            if (expiration < 0) {
                expiration = Long.MAX_VALUE;
            }
            if (componentName != null) {
                if (mOptions.disabledActivities == null) {
                    mOptions.disabledActivities = new ArrayMap<>();
                }
                mOptions.disabledActivities.put(componentName.flattenToString(), expiration);
            } else {
                mOptions.appDisabledExpiration = expiration;
            }
        }
    }

    void notifyReenableAutofill() {
        synchronized (mLock) {
            if (mOptions == null) {
                return;
            }
            mOptions.appDisabledExpiration = 0;
            mOptions.disabledActivities = null;
        }
    }

    private void notifyNoFillUi(int sessionId, AutofillId id, int sessionFinishedState) {
        if (sVerbose) {
            Log.v(TAG, "notifyNoFillUi(): sessionId=" + sessionId + ", autofillId=" + id
                    + ", sessionFinishedState=" + sessionFinishedState);
        }
        final View anchor = findView(id);
        if (anchor == null) {
            return;
        }

        AutofillCallback callback = null;
        synchronized (mLock) {
            if (mSessionId == sessionId && getClient() != null) {
                callback = mCallback;
            }
        }

        if (callback != null) {
            if (id.isVirtualInt()) {
                callback.onAutofillEvent(anchor, id.getVirtualChildIntId(),
                        AutofillCallback.EVENT_INPUT_UNAVAILABLE);
            } else {
                callback.onAutofillEvent(anchor, AutofillCallback.EVENT_INPUT_UNAVAILABLE);
            }
        }

        if (sessionFinishedState != STATE_UNKNOWN) {
            // Callback call was "hijacked" to also update the session state.
            setSessionFinished(sessionFinishedState, /* autofillableIds= */ null);
        }
    }

    /**
     * Find a single view by its id.
     *
     * @param autofillId The autofill id of the view
     *
     * @return The view or {@code null} if view was not found
     */
    private View findView(@NonNull AutofillId autofillId) {
        final AutofillClient client = getClient();
        if (client != null) {
            return client.autofillClientFindViewByAutofillIdTraversal(autofillId);
        }
        return null;
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
        pw.print(pfx); pw.print("context: "); pw.println(mContext);
        final AutofillClient client = getClient();
        if (client != null) {
            pw.print(pfx); pw.print("client: "); pw.print(client);
            pw.print(" ("); pw.print(client.autofillClientGetActivityToken()); pw.println(')');
        }
        pw.print(pfx); pw.print("enabled: "); pw.println(mEnabled);
        pw.print(pfx); pw.print("enabledAugmentedOnly: "); pw.println(mForAugmentedAutofillOnly);
        pw.print(pfx); pw.print("hasService: "); pw.println(mService != null);
        pw.print(pfx); pw.print("hasCallback: "); pw.println(mCallback != null);
        pw.print(pfx); pw.print("onInvisibleCalled "); pw.println(mOnInvisibleCalled);
        pw.print(pfx); pw.print("last autofilled data: "); pw.println(mLastAutofilledData);
        pw.print(pfx); pw.print("id of last fill UI shown: "); pw.println(mIdShownFillUi);
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
        pw.print(pfx); pw.print("entered ids: "); pw.println(mEnteredIds);
        if (mEnteredForAugmentedAutofillIds != null) {
            pw.print(pfx); pw.print("entered ids for augmented autofill: ");
            pw.println(mEnteredForAugmentedAutofillIds);
        }
        if (mForAugmentedAutofillOnly) {
            pw.print(pfx); pw.println("For Augmented Autofill Only");
        }
        pw.print(pfx); pw.print("save trigger id: "); pw.println(mSaveTriggerId);
        pw.print(pfx); pw.print("save on finish(): "); pw.println(mSaveOnFinish);
        if (mOptions != null) {
            pw.print(pfx); pw.print("options: "); mOptions.dumpShort(pw); pw.println();
        }
        pw.print(pfx); pw.print("compat mode enabled: ");
        synchronized (mLock) {
            if (mCompatibilityBridge != null) {
                final String pfx2 = pfx + "  ";
                pw.println("true");
                pw.print(pfx2); pw.print("windowId: ");
                pw.println(mCompatibilityBridge.mFocusedWindowId);
                pw.print(pfx2); pw.print("nodeId: ");
                pw.println(mCompatibilityBridge.mFocusedNodeId);
                pw.print(pfx2); pw.print("virtualId: ");
                pw.println(AccessibilityNodeInfo
                        .getVirtualDescendantId(mCompatibilityBridge.mFocusedNodeId));
                pw.print(pfx2); pw.print("focusedBounds: ");
                pw.println(mCompatibilityBridge.mFocusedBounds);
            } else {
                pw.println("false");
            }
        }
        pw.print(pfx); pw.print("debug: "); pw.print(sDebug);
        pw.print(" verbose: "); pw.println(sVerbose);
    }

    @GuardedBy("mLock")
    private String getStateAsStringLocked() {
        return getStateAsString(mState);
    }

    @NonNull
    private static String getStateAsString(int state) {
        switch (state) {
            case STATE_UNKNOWN:
                return "UNKNOWN";
            case STATE_ACTIVE:
                return "ACTIVE";
            case STATE_FINISHED:
                return "FINISHED";
            case STATE_SHOWING_SAVE_UI:
                return "SHOWING_SAVE_UI";
            case STATE_DISABLED_BY_SERVICE:
                return "DISABLED_BY_SERVICE";
            case STATE_UNKNOWN_COMPAT_MODE:
                return "UNKNOWN_COMPAT_MODE";
            case STATE_UNKNOWN_FAILED:
                return "UNKNOWN_FAILED";
            default:
                return "INVALID:" + state;
        }
    }

    /** @hide */
    public static String getSmartSuggestionModeToString(@SmartSuggestionMode int flags) {
        switch (flags) {
            case FLAG_SMART_SUGGESTION_OFF:
                return "OFF";
            case FLAG_SMART_SUGGESTION_SYSTEM:
                return "SYSTEM";
            default:
                return "INVALID:" + flags;
        }
    }

    @GuardedBy("mLock")
    private boolean isActiveLocked() {
        return mState == STATE_ACTIVE;
    }

    @GuardedBy("mLock")
    private boolean isDisabledByServiceLocked() {
        return mState == STATE_DISABLED_BY_SERVICE;
    }

    @GuardedBy("mLock")
    private boolean isFinishedLocked() {
        return mState == STATE_FINISHED;
    }

    private void post(Runnable runnable) {
        final AutofillClient client = getClient();
        if (client == null) {
            if (sVerbose) Log.v(TAG, "ignoring post() because client is null");
            return;
        }
        client.autofillClientRunOnUiThread(runnable);
    }

    /**
     * Implementation of the accessibility based compatibility.
     */
    private final class CompatibilityBridge implements AccessibilityManager.AccessibilityPolicy {
        @GuardedBy("mLock")
        private final Rect mFocusedBounds = new Rect();
        @GuardedBy("mLock")
        private final Rect mTempBounds = new Rect();

        @GuardedBy("mLock")
        private int mFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
        @GuardedBy("mLock")
        private long mFocusedNodeId = AccessibilityNodeInfo.UNDEFINED_NODE_ID;

        // Need to report a fake service in case a11y clients check the service list
        @NonNull
        @GuardedBy("mLock")
        AccessibilityServiceInfo mCompatServiceInfo;

        CompatibilityBridge() {
            final AccessibilityManager am = AccessibilityManager.getInstance(mContext);
            am.setAccessibilityPolicy(this);
        }

        private AccessibilityServiceInfo getCompatServiceInfo() {
            synchronized (mLock) {
                if (mCompatServiceInfo != null) {
                    return mCompatServiceInfo;
                }
                final Intent intent = new Intent();
                intent.setComponent(new ComponentName("android",
                        "com.android.server.autofill.AutofillCompatAccessibilityService"));
                final ResolveInfo resolveInfo = mContext.getPackageManager().resolveService(
                        intent, PackageManager.MATCH_SYSTEM_ONLY | PackageManager.GET_META_DATA);
                try {
                    mCompatServiceInfo = new AccessibilityServiceInfo(resolveInfo, mContext);
                } catch (XmlPullParserException | IOException e) {
                    Log.e(TAG, "Cannot find compat autofill service:" + intent);
                    throw new IllegalStateException("Cannot find compat autofill service");
                }
                return mCompatServiceInfo;
            }
        }

        @Override
        public boolean isEnabled(boolean accessibilityEnabled) {
            return true;
        }

        @Override
        public int getRelevantEventTypes(int relevantEventTypes) {
            return relevantEventTypes | AccessibilityEvent.TYPE_VIEW_FOCUSED
                    | AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED
                    | AccessibilityEvent.TYPE_VIEW_CLICKED
                    | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED;
        }

        @Override
        public List<AccessibilityServiceInfo> getInstalledAccessibilityServiceList(
               List<AccessibilityServiceInfo> installedServices) {
            if (installedServices == null) {
                installedServices = new ArrayList<>();
            }
            installedServices.add(getCompatServiceInfo());
            return installedServices;
        }

        @Override
        public List<AccessibilityServiceInfo> getEnabledAccessibilityServiceList(
                int feedbackTypeFlags, List<AccessibilityServiceInfo> enabledService) {
            if (enabledService == null) {
                enabledService = new ArrayList<>();
            }
            enabledService.add(getCompatServiceInfo());
            return enabledService;
        }

        @Override
        public AccessibilityEvent onAccessibilityEvent(AccessibilityEvent event,
                boolean accessibilityEnabled, int relevantEventTypes) {
            final int type = event.getEventType();
            if (sVerbose) {
                // NOTE: this is waaay spammy, but that's life.
                Log.v(TAG, "onAccessibilityEvent(" + AccessibilityEvent.eventTypeToString(type)
                        + "): virtualId="
                        + AccessibilityNodeInfo.getVirtualDescendantId(event.getSourceNodeId())
                        + ", client=" + getClient());
            }
            switch (type) {
                case AccessibilityEvent.TYPE_VIEW_FOCUSED: {
                    synchronized (mLock) {
                        if (mFocusedWindowId == event.getWindowId()
                                && mFocusedNodeId == event.getSourceNodeId()) {
                            return event;
                        }
                        if (mFocusedWindowId != AccessibilityWindowInfo.UNDEFINED_WINDOW_ID
                                && mFocusedNodeId != AccessibilityNodeInfo.UNDEFINED_NODE_ID) {
                            notifyViewExited(mFocusedWindowId, mFocusedNodeId);
                            mFocusedWindowId = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
                            mFocusedNodeId = AccessibilityNodeInfo.UNDEFINED_NODE_ID;
                            mFocusedBounds.set(0, 0, 0, 0);
                        }
                        final int windowId = event.getWindowId();
                        final long nodeId = event.getSourceNodeId();
                        if (notifyViewEntered(windowId, nodeId, mFocusedBounds)) {
                            mFocusedWindowId = windowId;
                            mFocusedNodeId = nodeId;
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED: {
                    synchronized (mLock) {
                        if (mFocusedWindowId == event.getWindowId()
                                && mFocusedNodeId == event.getSourceNodeId()) {
                            notifyValueChanged(event.getWindowId(), event.getSourceNodeId());
                        }
                    }
                } break;

                case AccessibilityEvent.TYPE_VIEW_CLICKED: {
                    synchronized (mLock) {
                        notifyViewClicked(event.getWindowId(), event.getSourceNodeId());
                    }
                } break;

                case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED: {
                    final AutofillClient client = getClient();
                    if (client != null) {
                        synchronized (mLock) {
                            if (client.autofillClientIsFillUiShowing()) {
                                notifyViewEntered(mFocusedWindowId, mFocusedNodeId, mFocusedBounds);
                            }
                            updateTrackedViewsLocked();
                        }
                    }
                } break;
            }

            return accessibilityEnabled ? event : null;
        }

        private boolean notifyViewEntered(int windowId, long nodeId, Rect focusedBounds) {
            final int virtualId = AccessibilityNodeInfo.getVirtualDescendantId(nodeId);
            if (!isVirtualNode(virtualId)) {
                return false;
            }
            final View view = findViewByAccessibilityId(windowId, nodeId);
            if (view == null) {
                return false;
            }
            final AccessibilityNodeInfo node = findVirtualNodeByAccessibilityId(view, virtualId);
            if (node == null) {
                return false;
            }
            if (!node.isEditable()) {
                return false;
            }
            final Rect newBounds = mTempBounds;
            node.getBoundsInScreen(newBounds);
            if (newBounds.equals(focusedBounds)) {
                return false;
            }
            focusedBounds.set(newBounds);
            AutofillManager.this.notifyViewEntered(view, virtualId, newBounds);
            return true;
        }

        private void notifyViewExited(int windowId, long nodeId) {
            final int virtualId = AccessibilityNodeInfo.getVirtualDescendantId(nodeId);
            if (!isVirtualNode(virtualId)) {
                return;
            }
            final View view = findViewByAccessibilityId(windowId, nodeId);
            if (view == null) {
                return;
            }
            AutofillManager.this.notifyViewExited(view, virtualId);
        }

        private void notifyValueChanged(int windowId, long nodeId) {
            final int virtualId = AccessibilityNodeInfo.getVirtualDescendantId(nodeId);
            if (!isVirtualNode(virtualId)) {
                return;
            }
            final View view = findViewByAccessibilityId(windowId, nodeId);
            if (view == null) {
                return;
            }
            final AccessibilityNodeInfo node = findVirtualNodeByAccessibilityId(view, virtualId);
            if (node == null) {
                return;
            }
            AutofillManager.this.notifyValueChanged(view, virtualId,
                    AutofillValue.forText(node.getText()));
        }

        private void notifyViewClicked(int windowId, long nodeId) {
            final int virtualId = AccessibilityNodeInfo.getVirtualDescendantId(nodeId);
            if (!isVirtualNode(virtualId)) {
                return;
            }
            final View view = findViewByAccessibilityId(windowId, nodeId);
            if (view == null) {
                return;
            }
            final AccessibilityNodeInfo node = findVirtualNodeByAccessibilityId(view, virtualId);
            if (node == null) {
                return;
            }
            AutofillManager.this.notifyViewClicked(view, virtualId);
        }

        @GuardedBy("mLock")
        private void updateTrackedViewsLocked() {
            if (mTrackedViews != null) {
                mTrackedViews.onVisibleForAutofillChangedLocked();
            }
        }

        private View findViewByAccessibilityId(int windowId, long nodeId) {
            final AutofillClient client = getClient();
            if (client == null) {
                return null;
            }
            final int viewId = AccessibilityNodeInfo.getAccessibilityViewId(nodeId);
            return client.autofillClientFindViewByAccessibilityIdTraversal(viewId, windowId);
        }

        private AccessibilityNodeInfo findVirtualNodeByAccessibilityId(View view, int virtualId) {
            final AccessibilityNodeProvider provider = view.getAccessibilityNodeProvider();
            if (provider == null) {
                return null;
            }
            return provider.createAccessibilityNodeInfo(virtualId);
        }

        private boolean isVirtualNode(int nodeId) {
            return nodeId != AccessibilityNodeProvider.HOST_VIEW_ID
                    && nodeId != AccessibilityNodeInfo.UNDEFINED_ITEM_ID;
        }
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
            final AutofillClient client = getClient();
            if (!ArrayUtils.isEmpty(trackedIds) && client != null) {
                final boolean[] isVisible;

                if (client.autofillClientIsVisibleForAutofill()) {
                    if (sVerbose) Log.v(TAG, "client is visible, check tracked ids");
                    isVisible = client.autofillClientGetViewVisibility(trackedIds);
                } else {
                    // All false
                    isVisible = new boolean[trackedIds.length];
                }

                final int numIds = trackedIds.length;
                for (int i = 0; i < numIds; i++) {
                    final AutofillId id = trackedIds[i];
                    id.resetSessionId();

                    if (isVisible[i]) {
                        mVisibleTrackedIds = addToSet(mVisibleTrackedIds, id);
                    } else {
                        mInvisibleTrackedIds = addToSet(mInvisibleTrackedIds, id);
                    }
                }
            }

            if (sVerbose) {
                Log.v(TAG, "TrackedViews(trackedIds=" + Arrays.toString(trackedIds) + "): "
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
        @GuardedBy("mLock")
        void notifyViewVisibilityChangedLocked(@NonNull AutofillId id, boolean isVisible) {
            if (sDebug) {
                Log.d(TAG, "notifyViewVisibilityChangedLocked(): id=" + id + " isVisible="
                        + isVisible);
            }

            if (isClientVisibleForAutofillLocked()) {
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
         * @see AutofillClient#autofillClientIsVisibleForAutofill()
         */
        @GuardedBy("mLock")
        void onVisibleForAutofillChangedLocked() {
            // The visibility of the views might have changed while the client was not be visible,
            // hence update the visibility state for all views.
            AutofillClient client = getClient();
            ArraySet<AutofillId> updatedVisibleTrackedIds = null;
            ArraySet<AutofillId> updatedInvisibleTrackedIds = null;
            if (client != null) {
                if (sVerbose) {
                    Log.v(TAG, "onVisibleForAutofillChangedLocked(): inv= " + mInvisibleTrackedIds
                            + " vis=" + mVisibleTrackedIds);
                }
                if (mInvisibleTrackedIds != null) {
                    final ArrayList<AutofillId> orderedInvisibleIds =
                            new ArrayList<>(mInvisibleTrackedIds);
                    final boolean[] isVisible = client.autofillClientGetViewVisibility(
                            Helper.toArray(orderedInvisibleIds));

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
                    final boolean[] isVisible = client.autofillClientGetViewVisibility(
                            Helper.toArray(orderedVisibleIds));

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
                if (sVerbose) {
                    Log.v(TAG, "onVisibleForAutofillChangedLocked(): no more visible ids");
                }
                finishSessionLocked();
            }
        }
    }

    /**
     * Callback for autofill related events.
     *
     * <p>Typically used for applications that display their own "auto-complete" views, so they can
     * enable / disable such views when the autofill UI is shown / hidden.
     */
    public abstract static class AutofillCallback {

        /** @hide */
        @IntDef(prefix = { "EVENT_INPUT_" }, value = {
                EVENT_INPUT_SHOWN,
                EVENT_INPUT_HIDDEN,
                EVENT_INPUT_UNAVAILABLE
        })
        @Retention(RetentionPolicy.SOURCE)
        public @interface AutofillEventType {}

        /**
         * The autofill input UI associated with the view was shown.
         *
         * <p>If the view provides its own auto-complete UI and its currently shown, it
         * should be hidden upon receiving this event.
         */
        public static final int EVENT_INPUT_SHOWN = 1;

        /**
         * The autofill input UI associated with the view was hidden.
         *
         * <p>If the view provides its own auto-complete UI that was hidden upon a
         * {@link #EVENT_INPUT_SHOWN} event, it could be shown again now.
         */
        public static final int EVENT_INPUT_HIDDEN = 2;

        /**
         * The autofill input UI associated with the view isn't shown because
         * autofill is not available.
         *
         * <p>If the view provides its own auto-complete UI but was not displaying it
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

        private AutofillManagerClient(AutofillManager autofillManager) {
            mAfm = new WeakReference<>(autofillManager);
        }

        @Override
        public void setState(int flags) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.setState(flags));
            }
        }

        @Override
        public void autofill(int sessionId, List<AutofillId> ids, List<AutofillValue> values,
                boolean hideHighlight) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.autofill(sessionId, ids, values, hideHighlight));
            }
        }

        @Override
        public void authenticate(int sessionId, int authenticationId, IntentSender intent,
                Intent fillInIntent, boolean authenticateInline) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.authenticate(sessionId, authenticationId, intent, fillInIntent,
                        authenticateInline));
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
                afm.post(() -> afm.requestHideFillUi(id, false));
            }
        }

        @Override
        public void notifyNoFillUi(int sessionId, AutofillId id, int sessionFinishedState) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.notifyNoFillUi(sessionId, id, sessionFinishedState));
            }
        }

        @Override
        public void notifyDisableAutofill(long disableDuration, ComponentName componentName)
                throws RemoteException {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.notifyDisableAutofill(disableDuration, componentName));
            }
        }

        @Override
        public void dispatchUnhandledKey(int sessionId, AutofillId id, KeyEvent fullScreen) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.dispatchUnhandledKey(sessionId, id, fullScreen));
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
                boolean saveOnAllViewsInvisible, boolean saveOnFinish, AutofillId[] fillableIds,
                AutofillId saveTriggerId) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.setTrackedViews(sessionId, ids, saveOnAllViewsInvisible,
                        saveOnFinish, fillableIds, saveTriggerId));
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
        public void setSessionFinished(int newState, List<AutofillId> autofillableIds) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.setSessionFinished(newState, autofillableIds));
            }
        }

        @Override
        public void getAugmentedAutofillClient(IResultReceiver result) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.getAugmentedAutofillClient(result));
            }
        }

        @Override
        public void requestShowSoftInput(@NonNull AutofillId id) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.requestShowSoftInput(id));
            }
        }
    }

    private static final class AugmentedAutofillManagerClient
            extends IAugmentedAutofillManagerClient.Stub {
        private final WeakReference<AutofillManager> mAfm;

        private AugmentedAutofillManagerClient(AutofillManager autofillManager) {
            mAfm = new WeakReference<>(autofillManager);
        }

        @Override
        public Rect getViewCoordinates(@NonNull AutofillId id) {
            final AutofillManager afm = mAfm.get();
            if (afm == null) return null;

            final View view = getView(afm, id);
            if (view == null) {
                return null;
            }
            final Rect windowVisibleDisplayFrame = new Rect();
            view.getWindowVisibleDisplayFrame(windowVisibleDisplayFrame);
            final int[] location = new int[2];
            view.getLocationOnScreen(location);
            final Rect rect = new Rect(location[0], location[1] - windowVisibleDisplayFrame.top,
                    location[0] + view.getWidth(),
                    location[1] - windowVisibleDisplayFrame.top + view.getHeight());
            if (sVerbose) {
                Log.v(TAG, "Coordinates for " + id + ": " + rect);
            }
            return rect;
        }

        @Override
        public void autofill(int sessionId, List<AutofillId> ids, List<AutofillValue> values,
                boolean hideHighlight) {
            final AutofillManager afm = mAfm.get();
            if (afm != null) {
                afm.post(() -> afm.autofill(sessionId, ids, values, hideHighlight));
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
                afm.post(() -> afm.requestHideFillUi(id, false));
            }
        }

        @Override
        public boolean requestAutofill(int sessionId, AutofillId id) {
            final AutofillManager afm = mAfm.get();
            if (afm == null || afm.mSessionId != sessionId) {
                if (sDebug) {
                    Slog.d(TAG, "Autofill not available or sessionId doesn't match");
                }
                return false;
            }
            final View view = getView(afm, id);
            if (view == null || !view.isFocused()) {
                if (sDebug) {
                    Slog.d(TAG, "View not available or is not on focus");
                }
                return false;
            }
            if (sVerbose) {
                Log.v(TAG, "requestAutofill() by AugmentedAutofillService.");
            }
            afm.post(() -> afm.requestAutofill(view));
            return true;
        }

        @Nullable
        private View getView(@NonNull AutofillManager afm, @NonNull AutofillId id) {
            final AutofillClient client = afm.getClient();
            if (client == null) {
                Log.w(TAG, "getView(" + id + "): no autofill client");
                return null;
            }
            View view = client.autofillClientFindViewByAutofillIdTraversal(id);
            if (view == null) {
                Log.w(TAG, "getView(" + id + "): could not find view");
            }
            return view;
        }
    }
}
