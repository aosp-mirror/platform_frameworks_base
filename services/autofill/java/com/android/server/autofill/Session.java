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

import static android.credentials.Constants.FAILURE_CREDMAN_SELECTOR;
import static android.credentials.Constants.SUCCESS_CREDMAN_SELECTOR;
import static android.service.autofill.AutofillFieldClassificationService.EXTRA_SCORES;
import static android.service.autofill.AutofillService.EXTRA_FILL_RESPONSE;
import static android.service.autofill.AutofillService.WEBVIEW_REQUESTED_CREDENTIAL_KEY;
import static android.service.autofill.Dataset.PICK_REASON_NO_PCC;
import static android.service.autofill.Dataset.PICK_REASON_PCC_DETECTION_ONLY;
import static android.service.autofill.Dataset.PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER;
import static android.service.autofill.Dataset.PICK_REASON_PROVIDER_DETECTION_ONLY;
import static android.service.autofill.Dataset.PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC;
import static android.service.autofill.Dataset.PICK_REASON_UNKNOWN;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_CREDMAN_BOTTOM_SHEET;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_DIALOG;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_INLINE;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_MENU;
import static android.service.autofill.FillEventHistory.Event.UI_TYPE_UNKNOWN;
import static android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST;
import static android.service.autofill.FillRequest.FLAG_PASSWORD_INPUT_TYPE;
import static android.service.autofill.FillRequest.FLAG_PCC_DETECTION;
import static android.service.autofill.FillRequest.FLAG_RESET_FILL_DIALOG_STATE;
import static android.service.autofill.FillRequest.FLAG_SCREEN_HAS_CREDMAN_FIELD;
import static android.service.autofill.FillRequest.FLAG_SUPPORTS_FILL_DIALOG;
import static android.service.autofill.FillRequest.FLAG_VIEW_NOT_FOCUSED;
import static android.service.autofill.FillRequest.FLAG_VIEW_REQUESTS_CREDMAN_SERVICE;
import static android.service.autofill.FillRequest.INVALID_REQUEST_ID;
import static android.view.autofill.AutofillManager.ACTION_RESPONSE_EXPIRED;
import static android.view.autofill.AutofillManager.ACTION_START_SESSION;
import static android.view.autofill.AutofillManager.ACTION_VALUE_CHANGED;
import static android.view.autofill.AutofillManager.ACTION_VIEW_ENTERED;
import static android.view.autofill.AutofillManager.ACTION_VIEW_EXITED;
import static android.view.autofill.AutofillManager.COMMIT_REASON_SESSION_DESTROYED;
import static android.view.autofill.AutofillManager.COMMIT_REASON_UNKNOWN;
import static android.view.autofill.AutofillManager.EXTRA_AUTOFILL_REQUEST_ID;
import static android.view.autofill.AutofillManager.FLAG_SMART_SUGGESTION_SYSTEM;
import static android.view.autofill.AutofillManager.getSmartSuggestionModeToString;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.autofill.FillRequestEventLogger.TRIGGER_REASON_EXPLICITLY_REQUESTED;
import static com.android.server.autofill.FillRequestEventLogger.TRIGGER_REASON_NORMAL_TRIGGER;
import static com.android.server.autofill.FillRequestEventLogger.TRIGGER_REASON_PRE_TRIGGER;
import static com.android.server.autofill.FillRequestEventLogger.TRIGGER_REASON_RETRIGGER;
import static com.android.server.autofill.FillRequestEventLogger.TRIGGER_REASON_SERVED_FROM_CACHED_RESPONSE;
import static com.android.server.autofill.FillResponseEventLogger.AVAILABLE_COUNT_WHEN_FILL_REQUEST_FAILED_OR_TIMEOUT;
import static com.android.server.autofill.FillResponseEventLogger.DETECTION_PREFER_AUTOFILL_PROVIDER;
import static com.android.server.autofill.FillResponseEventLogger.DETECTION_PREFER_PCC;
import static com.android.server.autofill.FillResponseEventLogger.DETECTION_PREFER_UNKNOWN;
import static com.android.server.autofill.FillResponseEventLogger.HAVE_SAVE_TRIGGER_ID;
import static com.android.server.autofill.FillResponseEventLogger.RESPONSE_STATUS_FAILURE;
import static com.android.server.autofill.FillResponseEventLogger.RESPONSE_STATUS_SESSION_DESTROYED;
import static com.android.server.autofill.FillResponseEventLogger.RESPONSE_STATUS_SUCCESS;
import static com.android.server.autofill.FillResponseEventLogger.RESPONSE_STATUS_TIMEOUT;
import static com.android.server.autofill.Helper.containsCharsInOrder;
import static com.android.server.autofill.Helper.createSanitizers;
import static com.android.server.autofill.Helper.getNumericValue;
import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;
import static com.android.server.autofill.Helper.toArray;
import static com.android.server.autofill.PresentationStatsEventLogger.AUTHENTICATION_RESULT_FAILURE;
import static com.android.server.autofill.PresentationStatsEventLogger.AUTHENTICATION_RESULT_SUCCESS;
import static com.android.server.autofill.PresentationStatsEventLogger.AUTHENTICATION_TYPE_DATASET_AUTHENTICATION;
import static com.android.server.autofill.PresentationStatsEventLogger.AUTHENTICATION_TYPE_FULL_AUTHENTICATION;
import static com.android.server.autofill.PresentationStatsEventLogger.NOT_SHOWN_REASON_NO_FOCUS;
import static com.android.server.autofill.PresentationStatsEventLogger.NOT_SHOWN_REASON_REQUEST_FAILED;
import static com.android.server.autofill.PresentationStatsEventLogger.NOT_SHOWN_REASON_REQUEST_TIMEOUT;
import static com.android.server.autofill.PresentationStatsEventLogger.NOT_SHOWN_REASON_SESSION_COMMITTED_PREMATURELY;
import static com.android.server.autofill.PresentationStatsEventLogger.NOT_SHOWN_REASON_VIEW_CHANGED;
import static com.android.server.autofill.PresentationStatsEventLogger.NOT_SHOWN_REASON_VIEW_FOCUS_CHANGED;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_DATASET_MATCH;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_FIELD_VALIDATION_FAILED;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_HAS_EMPTY_REQUIRED;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_NONE;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_NO_SAVE_INFO;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_NO_VALUE_CHANGED;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_SESSION_DESTROYED;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_WITH_DELAY_SAVE_FLAG;
import static com.android.server.autofill.SaveEventLogger.NO_SAVE_REASON_WITH_DONT_SAVE_ON_FINISH_FLAG;
import static com.android.server.autofill.SaveEventLogger.SAVE_UI_SHOWN_REASON_OPTIONAL_ID_CHANGE;
import static com.android.server.autofill.SaveEventLogger.SAVE_UI_SHOWN_REASON_REQUIRED_ID_CHANGE;
import static com.android.server.autofill.SaveEventLogger.SAVE_UI_SHOWN_REASON_TRIGGER_ID_SET;
import static com.android.server.autofill.SaveEventLogger.SAVE_UI_SHOWN_REASON_UNKNOWN;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.app.PendingIntent;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.AutofillOverlay;
import android.app.assist.AssistStructure.ViewNode;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.IntentSender;
import android.content.pm.ServiceInfo;
import android.credentials.CredentialManager;
import android.credentials.GetCredentialException;
import android.credentials.GetCredentialResponse;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.metrics.LogMaker;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.ResultReceiver;
import android.os.SystemClock;
import android.service.assist.classification.FieldClassificationRequest;
import android.service.assist.classification.FieldClassificationResponse;
import android.service.autofill.AutofillFieldClassificationService.Scores;
import android.service.autofill.AutofillService;
import android.service.autofill.CompositeUserData;
import android.service.autofill.ConvertCredentialResponse;
import android.service.autofill.Dataset;
import android.service.autofill.Dataset.DatasetEligibleReason;
import android.service.autofill.Field;
import android.service.autofill.FieldClassification;
import android.service.autofill.FieldClassification.Match;
import android.service.autofill.FieldClassificationUserData;
import android.service.autofill.FillContext;
import android.service.autofill.FillEventHistory.Event;
import android.service.autofill.FillEventHistory.Event.NoSaveReason;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.Flags;
import android.service.autofill.InlinePresentation;
import android.service.autofill.InternalSanitizer;
import android.service.autofill.InternalValidator;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.service.autofill.UserData;
import android.service.autofill.ValueFinder;
import android.service.credentials.CredentialProviderService;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.KeyEvent;
import android.view.autofill.AutofillFeatureFlags;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillManager.AutofillCommitReason;
import android.view.autofill.AutofillManager.SmartSuggestionMode;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import android.view.autofill.IAutofillWindowPresenter;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.widget.RemoteViews;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ArrayUtils;
import com.android.server.LocalServices;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.autofill.ui.InlineFillUi;
import com.android.server.autofill.ui.PendingUi;
import com.android.server.inputmethod.InputMethodManagerInternal;
import com.android.server.wm.ActivityTaskManagerInternal;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;

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
        AutoFillUI.AutoFillUiCallback, ValueFinder,
        RemoteFieldClassificationService.FieldClassificationServiceCallbacks {
    private static final String TAG = "AutofillSession";

    // This should never be true in production. This is only for local debugging.
    // Otherwise it will spam logcat.
    private static final boolean DBG = false;

    private static final String ACTION_DELAYED_FILL =
            "android.service.autofill.action.DELAYED_FILL";
    private static final String EXTRA_REQUEST_ID = "android.service.autofill.extra.REQUEST_ID";

    private static final String PCC_HINTS_DELIMITER = ",";
    public static final String EXTRA_KEY_DETECTIONS = "detections";
    private static final int DEFAULT__FILL_REQUEST_ID_SNAPSHOT = -2;
    private static final int DEFAULT__FIELD_CLASSIFICATION_REQUEST_ID_SNAPSHOT = -2;

    static final String SESSION_ID_KEY = "autofill_session_id";
    static final String REQUEST_ID_KEY = "autofill_request_id";

    final Object mLock;

    private final AutofillManagerServiceImpl mService;
    private final Handler mHandler;
    private final AutoFillUI mUi;
    /**
     * Context associated with the session, it has the same {@link Context#getDisplayId() displayId}
     * of the activity being autofilled.
     */
    private final Context mContext;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    static final int AUGMENTED_AUTOFILL_REQUEST_ID = 1;

    private static AtomicInteger sIdCounter = new AtomicInteger(2);

    private static AtomicInteger sIdCounterForPcc = new AtomicInteger(2);

    @GuardedBy("mLock")
    private @SessionState int mSessionState = STATE_UNKNOWN;

    /** Session state uninitiated. */
    public static final int STATE_UNKNOWN = 0;

    /** Session is active for filling. */
    public static final int STATE_ACTIVE = 1;

    /** Session finished for filling, staying alive for saving. */
    public static final int STATE_FINISHED = 2;

    /** Session is destroyed and removed from the manager service. */
    public static final int STATE_REMOVED = 3;

    @IntDef(prefix = { "STATE_" }, value = {
            STATE_UNKNOWN,
            STATE_ACTIVE,
            STATE_FINISHED,
            STATE_REMOVED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface SessionState{}

    @GuardedBy("mLock")
    private final SessionFlags mSessionFlags;

    /**
     * ID of the session.
     *
     * <p>It's always a positive number, to make it easier to embed it in a long.
     */
    public final int id;

    /** userId the session belongs to */
    public final int userId;

    /** The uid of the app that's being autofilled */
    public final int uid;

    /** ID of the task associated with this session's activity */
    public final int taskId;

    /** Flags used to start the session */
    public final int mFlags;

    @GuardedBy("mLock")
    @NonNull private IBinder mActivityToken;

    /** The app activity that's being autofilled */
    @NonNull private final ComponentName mComponentName;

    /** Whether the app being autofilled is running in compat mode. */
    private final boolean mCompatMode;

    /** Node representing the URL bar on compat mode. */
    @GuardedBy("mLock")
    private ViewNode mUrlBar;

    @GuardedBy("mLock")
    private boolean mSaveOnAllViewsInvisible;

    @GuardedBy("mLock")
    private final ArrayMap<AutofillId, ViewState> mViewStates = new ArrayMap<>();

    /**
     * Tracks the most recent IME inline request and the corresponding request id, for regular
     * autofill.
     */
    @GuardedBy("mLock")
    @Nullable private Pair<Integer, InlineSuggestionsRequest> mLastInlineSuggestionsRequest;

    /**
     * Id of the View currently being displayed.
     */
    @GuardedBy("mLock")
    private @Nullable AutofillId mCurrentViewId;

    @GuardedBy("mLock")
    private IAutoFillManagerClient mClient;

    @GuardedBy("mLock")
    private DeathRecipient mClientVulture;

    @GuardedBy("mLock")
    private boolean mLoggedInlineDatasetShown;

    /**
     * Reference to the remote service.
     *
     * <p>Only {@code null} when the session is for augmented autofill only.
     */
    @Nullable
    private final RemoteFillService mRemoteFillService;

    /**
     * With the credman integration, Autofill Framework handles two types of autofill flows -
     * regular autofill flow and the credman integrated autofill flow. With the credman integrated
     * autofill, the data source for the autofill is handled by the credential autofill proxy
     * service, which is hidden from users. By the time a session gets created, the framework
     * decides on one of the two flows by setting the remote fill service to be either the
     * user-elected autofill service or the hidden credential autofill service by looking at the
     * user-focused view's credential attribute. If the user needs both flows concurrently because
     * the screen has both regular autofill fields and credential fields, then secondary provider
     * handler will be used to fetch supplementary fill response. Depending on which remote fill
     * service the session was initially created with, the secondary provider handler will contain
     * the remaining autofill service.
     */
    @Nullable
    private final SecondaryProviderHandler mSecondaryProviderHandler;

    @GuardedBy("mLock")
    private SparseArray<FillResponse> mResponses;

    @GuardedBy("mLock")
    private SparseArray<FillResponse> mSecondaryResponses;

    /**
     * Contexts read from the app; they will be updated (sanitized, change values for save) before
     * sent to {@link AutofillService}. Ordered by the time they were read.
     */
    @GuardedBy("mLock")
    private ArrayList<FillContext> mContexts;

    /**
     * Whether the client has an {@link android.view.autofill.AutofillManager.AutofillCallback}.
     */
    private boolean mHasCallback;

    /** Whether the session has credential manager provider as the primary provider. */
    private boolean mIsPrimaryCredential;

    @GuardedBy("mLock")
    private boolean mDelayedFillBroadcastReceiverRegistered;

    @GuardedBy("mLock")
    private PendingIntent mDelayedFillPendingIntent;

    /**
     * Extras sent by service on {@code onFillRequest()} calls; the most recent non-null extra is
     * saved and used on subsequent {@code onFillRequest()} and {@code onSaveRequest()} calls.
     */
    @GuardedBy("mLock")
    private Bundle mClientState;

    @GuardedBy("mLock")
    boolean mDestroyed;

    /**
     * Helper used to handle state of Save UI when it must be hiding to show a custom description
     * link and later recovered.
     */
    @GuardedBy("mLock")
    private PendingUi mPendingSaveUi;

    /**
     * List of dataset ids selected by the user.
     */
    @GuardedBy("mLock")
    private ArrayList<String> mSelectedDatasetIds;

    /**
     * When the session started (using elapsed time since boot).
     */
    private final long mStartTime;

    /**
     * Count of FillRequests in the session.
     */
    private int mRequestCount;

    /**
     * Starting timestamp of latency logger.
     * This is set when Session created or when the view is reset.
     */
    @GuardedBy("mLock")
    private long mLatencyBaseTime;

    /**
     * When the UI was shown for the first time (using elapsed time since boot).
     */
    @GuardedBy("mLock")
    private long mUiShownTime;

    /**
     * Tracks the value of the fill request id at the time of issuing request for field
     * classification.
     */
    @GuardedBy("mLock")
    private int mFillRequestIdSnapshot = DEFAULT__FILL_REQUEST_ID_SNAPSHOT;

    /**
     * Tracks the value of the field classification id at the time of issuing request for fill
     * request.
     */
    @GuardedBy("mLock")
    private int mFieldClassificationIdSnapshot = DEFAULT__FIELD_CLASSIFICATION_REQUEST_ID_SNAPSHOT;

    @GuardedBy("mLock")
    private final LocalLog mUiLatencyHistory;

    @GuardedBy("mLock")
    private final LocalLog mWtfHistory;

    /**
     * Map of {@link MetricsEvent#AUTOFILL_REQUEST} metrics, keyed by fill request id.
     */
    @GuardedBy("mLock")
    private final SparseArray<LogMaker> mRequestLogs = new SparseArray<>(1);

    /**
     * Destroys the augmented Autofill UI.
     */
    // TODO(b/123099468): this runnable is called when the Autofill session is destroyed, the
    // main reason being the cases where user tap HOME.
    // Right now it's completely destroying the UI, but we need to decide whether / how to
    // properly recover it later (for example, if the user switches back to the activity,
    // should it be restored? Right now it kind of is, because Autofill's Session trigger a
    // new FillRequest, which in turn triggers the Augmented Autofill request again)
    @GuardedBy("mLock")
    @Nullable
    private Runnable mAugmentedAutofillDestroyer;

    /**
     * List of {@link MetricsEvent#AUTOFILL_AUGMENTED_REQUEST} metrics.
     */
    @GuardedBy("mLock")
    private ArrayList<LogMaker> mAugmentedRequestsLogs;


    /**
     * List of autofill ids of autofillable fields present in the AssistStructure that can be used
     * to trigger new augmented autofill requests (because the "standard" service was not interested
     * on autofilling the app.
     */
    @GuardedBy("mLock")
    private ArrayList<AutofillId> mAugmentedAutofillableIds;

    @NonNull
    final AutofillInlineSessionController mInlineSessionController;

    /**
     * Receiver of assist data from the app's {@link Activity}.
     */
    private final AssistDataReceiverImpl mAssistReceiver = new AssistDataReceiverImpl();

    /**
     * Receiver of assist data for pcc purpose
     */
    private final PccAssistDataReceiverImpl mPccAssistReceiver = new PccAssistDataReceiverImpl();

    private final ClassificationState mClassificationState = new ClassificationState();

    @Nullable
    private final ComponentName mCredentialAutofillService;

    // TODO(b/216576510): Share one BroadcastReceiver between all Sessions instead of creating a
    // new one per Session.
    private final BroadcastReceiver mDelayedFillBroadcastReceiver =
            new BroadcastReceiver() {
                // ErrorProne says mAssistReceiver#processDelayedFillLocked needs to be guarded by
                // 'Session.this.mLock', which is the same as mLock.
                @SuppressWarnings("GuardedBy")
                @Override
                public void onReceive(final Context context, final Intent intent) {
                    if (!intent.getAction().equals(ACTION_DELAYED_FILL)) {
                        Slog.wtf(TAG, "Unexpected action is received.");
                        return;
                    }
                    if (!intent.hasExtra(EXTRA_REQUEST_ID)) {
                        Slog.e(TAG, "Delay fill action is missing request id extra.");
                        return;
                    }
                    Slog.v(TAG, "mDelayedFillBroadcastReceiver delayed fill action received");
                    synchronized (mLock) {
                        int requestId = intent.getIntExtra(EXTRA_REQUEST_ID, 0);
                        FillResponse response = intent.getParcelableExtra(EXTRA_FILL_RESPONSE, android.service.autofill.FillResponse.class);
                        mFillRequestEventLogger.maybeSetRequestTriggerReason(
                                TRIGGER_REASON_RETRIGGER);
                        mAssistReceiver.processDelayedFillLocked(requestId, response);
                    }
                }
            };

    @NonNull
    @GuardedBy("mLock")
    private PresentationStatsEventLogger mPresentationStatsEventLogger;

    @NonNull
    @GuardedBy("mLock")
    private FillRequestEventLogger mFillRequestEventLogger;

    @NonNull
    @GuardedBy("mLock")
    private FillResponseEventLogger mFillResponseEventLogger;

    @NonNull
    @GuardedBy("mLock")
    private SaveEventLogger mSaveEventLogger;

    @NonNull
    @GuardedBy("mLock")
    private SessionCommittedEventLogger mSessionCommittedEventLogger;

    /**
     * Fill dialog request would likely be sent slightly later.
     */
    @NonNull
    @GuardedBy("mLock")
    private boolean mPreviouslyFillDialogPotentiallyStarted;

    /**
     * Keeps track of if the user entered view, this is used to
     * distinguish Fill Request that did not have user interaction
     * with ones that did.
     *
     * This is set to true when entering view - after FillDialog FillRequest
     * or on plain user tap.
     */
    @NonNull
    @GuardedBy("mLock")
    private boolean mLogViewEntered;

    /**
     * Keeps the fill dialog trigger ids of the last response. This invalidates
     * the trigger ids of the previous response.
     */
    @Nullable
    @GuardedBy("mLock")
    private AutofillId[] mLastFillDialogTriggerIds;

    private boolean mIgnoreViewStateResetToEmpty;

    /*
     * Id of the previous view that was entered. Once set, it would only be replaced by non-null
     * view ids.
     * When a user focuses on a field, autofill request is sent. When the keyboard pops up, or the
     * autofill dialog shows up, this field loses focus. After selecting a suggestion, focus goes
     * back to the same field. This field allows to ignore focus loss when autofill dialog comes up.
     * TODO(b/319872477): Note that there maybe some cases where we incorrectly detect focus loss.
     */
    @GuardedBy("mLock")
    private @Nullable AutofillId mPreviousNonNullEnteredViewId;

    void onSwitchInputMethodLocked() {
        // One caveat is that for the case where the focus is on a field for which regular autofill
        // returns null, and augmented autofill is triggered,  and then the user switches the input
        // method. Tapping on the field again will not trigger a new augmented autofill request.
        // This may be fixed by adding more checks such as whether mCurrentViewId is null.
        if (mSessionFlags.mExpiredResponse) {
            return;
        }
        if (shouldResetSessionStateOnInputMethodSwitch()) {
            // Set the old response expired, so the next action (ACTION_VIEW_ENTERED) can trigger
            // a new fill request.
            mSessionFlags.mExpiredResponse = true;
            // Clear the augmented autofillable ids so augmented autofill will trigger again.
            mAugmentedAutofillableIds = null;
            // In case the field is augmented autofill only, we clear the current view id, so that
            // we won't skip view entered due to same view entered, for the augmented autofill.
            if (mSessionFlags.mAugmentedAutofillOnly) {
                mCurrentViewId = null;
            }
        }
    }

    private boolean shouldResetSessionStateOnInputMethodSwitch() {
        // One of below cases will need a new fill request to update the inline spec for the new
        // input method.
        // 1. The autofill provider supports inline suggestion and the render service is available.
        // 2. Had triggered the augmented autofill and the render service is available. Whether the
        // augmented autofill triggered by:
        //    a. Augmented autofill only
        //    b. The autofill provider respond null
        if (mService.getRemoteInlineSuggestionRenderServiceLocked() == null) {
            return false;
        }

        if (mSessionFlags.mInlineSupportedByService) {
            return true;
        }

        final ViewState state = mViewStates.get(mCurrentViewId);
        if (state != null
                && (state.getState() & ViewState.STATE_TRIGGERED_AUGMENTED_AUTOFILL) != 0) {
            return true;
        }

        return false;
    }

    /**
     * Collection of flags/booleans that helps determine Session behaviors.
     */
    private final class SessionFlags {
        /** Whether autofill is disabled by the service */
        private boolean mAutofillDisabled;

        /** Whether the autofill service supports inline suggestions */
        private boolean mInlineSupportedByService;

        /** True if session is for augmented only */
        private boolean mAugmentedAutofillOnly;

        /** Whether the session is currently showing the SaveUi. */
        private boolean mShowingSaveUi;

        /** Whether the current {@link FillResponse} is expired. */
        private boolean mExpiredResponse;

        /** Whether the fill dialog UI is disabled. */
        private boolean mFillDialogDisabled;

        /** Whether current screen has credman field. */
        private boolean mScreenHasCredmanField;
    }

    /**
     * TODO(b/151867668): improve how asynchronous data dependencies are handled, without using
     * CountDownLatch.
     */
    final class AssistDataReceiverImpl extends IAssistDataReceiver.Stub {
        @GuardedBy("mLock")
        private boolean mWaitForInlineRequest;
        @GuardedBy("mLock")
        private InlineSuggestionsRequest mPendingInlineSuggestionsRequest;
        @GuardedBy("mLock")
        private FillRequest mPendingFillRequest;
        @GuardedBy("mLock")
        private FillRequest mLastFillRequest;

        @Nullable Consumer<InlineSuggestionsRequest> newAutofillRequestLocked(ViewState viewState,
                boolean isInlineRequest) {
            mPendingFillRequest = null;
            mWaitForInlineRequest = isInlineRequest;
            mPendingInlineSuggestionsRequest = null;
            if (isInlineRequest) {
                WeakReference<AssistDataReceiverImpl> assistDataReceiverWeakReference =
                    new WeakReference<AssistDataReceiverImpl>(this);
                WeakReference<ViewState> viewStateWeakReference =
                    new WeakReference<ViewState>(viewState);
                return new InlineSuggestionRequestConsumer(assistDataReceiverWeakReference,
                    viewStateWeakReference);
            }
            return null;
        }

        void handleInlineSuggestionRequest(InlineSuggestionsRequest inlineSuggestionsRequest,
                ViewState viewState) {
            synchronized (mLock) {
                if (!mWaitForInlineRequest || mPendingInlineSuggestionsRequest != null) {
                    return;
                }
                mWaitForInlineRequest = inlineSuggestionsRequest != null;
                mPendingInlineSuggestionsRequest = inlineSuggestionsRequest;
                maybeRequestFillLocked();
                viewState.resetState(ViewState.STATE_PENDING_CREATE_INLINE_REQUEST);
            }
        }

        @GuardedBy("mLock")
        void maybeRequestFillLocked() {
            if (mPendingFillRequest == null) {
                return;
            }
            mFieldClassificationIdSnapshot = sIdCounterForPcc.get();

            if (mWaitForInlineRequest) {
                if (mPendingInlineSuggestionsRequest == null) {
                    return;
                }

                mPendingFillRequest = new FillRequest(mPendingFillRequest.getId(),
                        mPendingFillRequest.getFillContexts(),
                        mPendingFillRequest.getHints(),
                        mPendingFillRequest.getClientState(),
                        mPendingFillRequest.getFlags(),
                        mPendingInlineSuggestionsRequest,
                        mPendingFillRequest.getDelayedFillIntentSender());
            }
            mLastFillRequest = mPendingFillRequest;
            if (shouldRequestSecondaryProvider(mPendingFillRequest.getFlags())
                    && mSecondaryProviderHandler != null) {
                Slog.v(TAG, "Requesting fill response to secondary provider.");
                if (!mIsPrimaryCredential) {
                    mPendingFillRequest = addCredentialManagerDataToClientState(
                            mPendingFillRequest,
                            mPendingInlineSuggestionsRequest, id);
                }
                mSecondaryProviderHandler.onFillRequest(mPendingFillRequest,
                        mPendingFillRequest.getFlags(), mClient.asBinder());
            } else if (mRemoteFillService != null) {
                if (mIsPrimaryCredential) {
                    mPendingFillRequest = addCredentialManagerDataToClientState(
                            mPendingFillRequest,
                            mPendingInlineSuggestionsRequest, id);
                    mRemoteFillService.onFillCredentialRequest(mPendingFillRequest,
                            mClient.asBinder());
                } else {
                    mRemoteFillService.onFillRequest(mPendingFillRequest);
                }
            }
            mPendingInlineSuggestionsRequest = null;
            mWaitForInlineRequest = false;
            mPendingFillRequest = null;

            final long fillRequestSentRelativeTimestamp =
                    SystemClock.elapsedRealtime() - mLatencyBaseTime;
            mPresentationStatsEventLogger.maybeSetFillRequestSentTimestampMs(
                    (int) (fillRequestSentRelativeTimestamp));
            mFillRequestEventLogger.maybeSetLatencyFillRequestSentMillis(
                    (int) (fillRequestSentRelativeTimestamp));
            mFillRequestEventLogger.logAndEndEvent();
        }

        @Override
        public void onHandleAssistData(Bundle resultData) throws RemoteException {
            if (mRemoteFillService == null) {
                wtf(null, "onHandleAssistData() called without a remote service. "
                        + "mForAugmentedAutofillOnly: %s", mSessionFlags.mAugmentedAutofillOnly);
                return;
            }
            // Keeps to prevent it is cleared on multiple threads.
            final AutofillId currentViewId = mCurrentViewId;
            if (currentViewId == null) {
                Slog.w(TAG, "No current view id - session might have finished");
                return;
            }

            final AssistStructure structure = resultData.getParcelable(ASSIST_KEY_STRUCTURE, android.app.assist.AssistStructure.class);
            if (structure == null) {
                Slog.e(TAG, "No assist structure - app might have crashed providing it");
                return;
            }

            final Bundle receiverExtras = resultData.getBundle(ASSIST_KEY_RECEIVER_EXTRAS);
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
                try {
                    structure.ensureDataForAutofill();
                } catch (RuntimeException e) {
                    wtf(e, "Exception lazy loading assist structure for %s: %s",
                            structure.getActivityComponent(), e);
                    return;
                }

                final ArrayList<AutofillId> ids = Helper.getAutofillIds(structure,
                        /* autofillableOnly= */false);
                for (int i = 0; i < ids.size(); i++) {
                    ids.get(i).setSessionId(Session.this.id);
                }

                // Flags used to start the session.
                int flags = structure.getFlags();

                if (mCompatMode) {
                    // Sanitize URL bar, if needed
                    final String[] urlBarIds = mService.getUrlBarResourceIdsForCompatMode(
                            mComponentName.getPackageName());
                    if (sDebug) {
                        Slog.d(TAG, "url_bars in compat mode: " + Arrays.toString(urlBarIds));
                    }
                    if (urlBarIds != null) {
                        mUrlBar = Helper.sanitizeUrlBar(structure, urlBarIds);
                        if (mUrlBar != null) {
                            final AutofillId urlBarId = mUrlBar.getAutofillId();
                            if (sDebug) {
                                Slog.d(TAG, "Setting urlBar as id=" + urlBarId + " and domain "
                                        + mUrlBar.getWebDomain());
                            }
                            final ViewState viewState = new ViewState(urlBarId, Session.this,
                                    ViewState.STATE_URL_BAR, mIsPrimaryCredential);
                            mViewStates.put(urlBarId, viewState);
                        }
                    }
                    flags |= FillRequest.FLAG_COMPATIBILITY_MODE_REQUEST;
                }
                structure.sanitizeForParceling(true);

                if (mContexts == null) {
                    mContexts = new ArrayList<>(1);
                }
                mContexts.add(new FillContext(requestId, structure, currentViewId));

                cancelCurrentRequestLocked();

                final int numContexts = mContexts.size();
                for (int i = 0; i < numContexts; i++) {
                    fillContextWithAllowedValuesLocked(mContexts.get(i), flags);
                }

                final ArrayList<FillContext> contexts =
                        mergePreviousSessionLocked(/* forSave= */ false);
                final List<String> hints = getTypeHintsForProvider();

                mDelayedFillPendingIntent = createPendingIntent(requestId);
                request = new FillRequest(requestId, contexts, hints, mClientState, flags,
                        /*inlineSuggestionsRequest=*/ null,
                        /*delayedFillIntentSender=*/ mDelayedFillPendingIntent == null
                            ? null
                            : mDelayedFillPendingIntent.getIntentSender());

                mPendingFillRequest = request;
                maybeRequestFillLocked();
            }

            if (mActivityToken != null) {
                mService.sendActivityAssistDataToContentCapture(mActivityToken, resultData);
            }
        }

        @Override
        public void onHandleAssistScreenshot(Bitmap screenshot) {
            // Do nothing
        }

        @GuardedBy("mLock")
        void processDelayedFillLocked(int requestId, FillResponse response) {
            if (mLastFillRequest != null && requestId == mLastFillRequest.getId()) {
                Slog.v(TAG, "processDelayedFillLocked: "
                        + "calling onFillRequestSuccess with new response");
                onFillRequestSuccess(requestId, response,
                        mService.getServicePackageName(), mLastFillRequest.getFlags());
            }
        }
    }

    private FillRequest addCredentialManagerDataToClientState(FillRequest pendingFillRequest,
            InlineSuggestionsRequest pendingInlineSuggestionsRequest, int sessionId) {

        if (pendingFillRequest.getClientState() == null) {
            pendingFillRequest = new FillRequest(pendingFillRequest.getId(),
                    pendingFillRequest.getFillContexts(),
                    pendingFillRequest.getHints(),
                    new Bundle(),
                    pendingFillRequest.getFlags(),
                    pendingInlineSuggestionsRequest,
                    pendingFillRequest.getDelayedFillIntentSender());
        }
        pendingFillRequest.getClientState().putInt(SESSION_ID_KEY, sessionId);
        pendingFillRequest.getClientState().putInt(REQUEST_ID_KEY, pendingFillRequest.getId());
        ResultReceiver resultReceiver = constructCredentialManagerCallback(
                pendingFillRequest.getId());
        pendingFillRequest.getClientState().putParcelable(
                CredentialManager.EXTRA_AUTOFILL_RESULT_RECEIVER, resultReceiver);
        return pendingFillRequest;
    }

    /**
     * Get the list of valid autofill hint types from Device flags
     * Returns empty list if PCC is off or no types available
    */
    private List<String> getTypeHintsForProvider() {
        if (!mService.isPccClassificationEnabled()) {
            return Collections.EMPTY_LIST;
        }
        final String typeHints = mService.getMaster().getPccProviderHints();
        if (sVerbose) {
            Slog.v(TAG, "TypeHints flag:" + typeHints);
        }
        if (TextUtils.isEmpty(typeHints)) {
            return new ArrayList<>();
        }

        return List.of(typeHints.split(PCC_HINTS_DELIMITER));
    }

    /**
     * Assist Data Receiver for PCC
     */
    private final class PccAssistDataReceiverImpl extends IAssistDataReceiver.Stub {

        @GuardedBy("mLock")
        void maybeRequestFieldClassificationFromServiceLocked() {
            if (mClassificationState.mPendingFieldClassificationRequest == null) {
                Slog.w(TAG, "Received AssistData without pending classification request");
                return;
            }

            RemoteFieldClassificationService remoteFieldClassificationService =
                    mService.getRemoteFieldClassificationServiceLocked();
            if (remoteFieldClassificationService != null) {
                WeakReference<RemoteFieldClassificationService.FieldClassificationServiceCallbacks>
                        fieldClassificationServiceCallbacksWeakRef =
                                new WeakReference<>(Session.this);
                remoteFieldClassificationService.onFieldClassificationRequest(
                        mClassificationState.mPendingFieldClassificationRequest,
                                fieldClassificationServiceCallbacksWeakRef);
            }
            mClassificationState.onFieldClassificationRequestSent();
        }

        @Override
        public void onHandleAssistData(Bundle resultData) throws RemoteException {
            // TODO: add a check if pcc field classification service is present
            final AssistStructure structure = resultData.getParcelable(ASSIST_KEY_STRUCTURE,
                android.app.assist.AssistStructure.class);
            if (structure == null) {
                Slog.e(TAG, "No assist structure for pcc detection - "
                    + "app might have crashed providing it");
                return;
            }

            final Bundle receiverExtras = resultData.getBundle(ASSIST_KEY_RECEIVER_EXTRAS);
            if (receiverExtras == null) {
                Slog.e(TAG, "No receiver extras for pcc detection - "
                    + "app might have crashed providing it");
                return;
            }

            final int requestId = receiverExtras.getInt(EXTRA_REQUEST_ID);

            if (sVerbose) {
                Slog.v(TAG, "New structure for PCC Detection: requestId " + requestId + ": "
                        + structure);
            }

            synchronized (mLock) {
                // TODO(b/35708678): Must fetch the data so it's available later on handleSave(),
                // even if the activity is gone by then, but structure .ensureData() gives a
                // ONE_WAY warning because system_service could block on app calls. We need to
                // change AssistStructure so it provides a "one-way" writeToParcel() method that
                // sends all the data
                try {
                    structure.ensureDataForAutofill();
                } catch (RuntimeException e) {
                    wtf(e, "Exception lazy loading assist structure for %s: %s",
                        structure.getActivityComponent(), e);
                    return;
                }

                final ArrayList<AutofillId> ids = Helper.getAutofillIds(structure,
                    /* autofillableOnly= */false);
                for (int i = 0; i < ids.size(); i++) {
                    ids.get(i).setSessionId(Session.this.id);
                }

                mClassificationState.onAssistStructureReceived(structure);

                maybeRequestFieldClassificationFromServiceLocked();
            }
        }

        @Override
        public void onHandleAssistScreenshot(Bitmap screenshot) {
            // Do nothing
        }
    }

    /** Creates {@link PendingIntent} for autofill service to send a delayed fill. */
    private PendingIntent createPendingIntent(int requestId) {
        Slog.d(TAG, "createPendingIntent for request " + requestId);
        PendingIntent pendingIntent;
        final long identity = Binder.clearCallingIdentity();
        try {
            Intent intent = new Intent(ACTION_DELAYED_FILL).setPackage("android")
                    .putExtra(EXTRA_REQUEST_ID, requestId);
            pendingIntent = PendingIntent.getBroadcast(
                    mContext, this.id, intent,
                    PendingIntent.FLAG_MUTABLE
                        | PendingIntent.FLAG_ONE_SHOT
                        | PendingIntent.FLAG_CANCEL_CURRENT);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return pendingIntent;
    }

    @GuardedBy("mLock")
    private void clearPendingIntentLocked() {
        Slog.d(TAG, "clearPendingIntentLocked");
        if (mDelayedFillPendingIntent == null) {
            return;
        }
        final long identity = Binder.clearCallingIdentity();
        try {
            mDelayedFillPendingIntent.cancel();
            mDelayedFillPendingIntent = null;
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    @GuardedBy("mLock")
    private void registerDelayedFillBroadcastLocked() {
        if (!mDelayedFillBroadcastReceiverRegistered) {
            Slog.v(TAG, "registerDelayedFillBroadcastLocked()");
            IntentFilter intentFilter = new IntentFilter(ACTION_DELAYED_FILL);
            mContext.registerReceiver(mDelayedFillBroadcastReceiver, intentFilter);
            mDelayedFillBroadcastReceiverRegistered = true;
        }
    }

    @GuardedBy("mLock")
    private void unregisterDelayedFillBroadcastLocked() {
        if (mDelayedFillBroadcastReceiverRegistered) {
            Slog.v(TAG, "unregisterDelayedFillBroadcastLocked()");
            mContext.unregisterReceiver(mDelayedFillBroadcastReceiver);
            mDelayedFillBroadcastReceiverRegistered = false;
        }
    }

    /**
     * Returns the ids of all entries in {@link #mViewStates} in the same order.
     */
    @GuardedBy("mLock")
    private AutofillId[] getIdsOfAllViewStatesLocked() {
        final int numViewState = mViewStates.size();
        final AutofillId[] ids = new AutofillId[numViewState];
        for (int i = 0; i < numViewState; i++) {
            ids[i] = mViewStates.valueAt(i).id;
        }

        return ids;
    }

    /**
     * Returns the String value of an {@link AutofillValue} by {@link AutofillId id} if it is of
     * type {@code AUTOFILL_TYPE_TEXT} or {@code AUTOFILL_TYPE_LIST}.
     */
    @Override
    @Nullable
    public String findByAutofillId(@NonNull AutofillId id) {
        synchronized (mLock) {
            AutofillValue value = findValueLocked(id);
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
                        Slog.w(TAG, "findByAutofillId(): no autofill options for id " + id);
                    }
                }
            }
        }
        return null;
    }

    @Override
    public AutofillValue findRawValueByAutofillId(AutofillId id) {
        synchronized (mLock) {
            return findValueLocked(id);
        }
    }

    /**
     * <p>Gets the value of a field, using either the {@code viewStates} or the {@code mContexts},
     * or {@code null} when not found on either of them.
     */
    @GuardedBy("mLock")
    @Nullable
    private AutofillValue findValueLocked(@NonNull AutofillId autofillId) {
        final AutofillValue value = findValueFromThisSessionOnlyLocked(autofillId);
        if (value != null) {
            return getSanitizedValue(createSanitizers(getSaveInfoLocked()), autofillId, value);
        }

        // TODO(b/113281366): rather than explicitly look for previous session, it might be better
        // to merge the sessions when created (see note on mergePreviousSessionLocked())
        final ArrayList<Session> previousSessions = mService.getPreviousSessionsLocked(this);
        if (previousSessions != null) {
            if (sDebug) {
                Slog.d(TAG, "findValueLocked(): looking on " + previousSessions.size()
                        + " previous sessions for autofillId " + autofillId);
            }
            for (int i = 0; i < previousSessions.size(); i++) {
                final Session previousSession = previousSessions.get(i);
                final AutofillValue previousValue = previousSession
                        .findValueFromThisSessionOnlyLocked(autofillId);
                if (previousValue != null) {
                    return getSanitizedValue(createSanitizers(previousSession.getSaveInfoLocked()),
                            autofillId, previousValue);
                }
            }
        }
        return null;
    }

    @GuardedBy("mLock")
    @Nullable
    private AutofillValue findValueFromThisSessionOnlyLocked(@NonNull AutofillId autofillId) {
        final ViewState state = mViewStates.get(autofillId);
        if (state == null) {
            if (sDebug) Slog.d(TAG, "findValueLocked(): no view state for " + autofillId);
            return null;
        }
        AutofillValue value = state.getCurrentValue();

        // Some app clears the form before navigating to another activities. In this case, use the
        // cached value instead.
        if (value == null || value.isEmpty()) {
            AutofillValue candidateSaveValue = state.getCandidateSaveValue();
            if (candidateSaveValue != null && !candidateSaveValue.isEmpty()) {
                if (sDebug) {
                    Slog.d(TAG, "findValueLocked(): current value for " + autofillId
                            + " is empty, using candidateSaveValue instead.");
                }
                return candidateSaveValue;
            }
        }
        if (value == null) {
            if (sDebug) {
                Slog.d(TAG, "findValueLocked(): no current value for " + autofillId
                        + ", checking value from previous fill contexts");
                value = getValueFromContextsLocked(autofillId);
            }
        }
        return value;
    }

    /**
     * Updates values of the nodes in the context's structure so that:
     *
     * - proper node is focused
     * - autofillValue is sent back to service when it was previously autofilled
     * - autofillValue is sent in the view used to force a request
     *
     * @param fillContext The context to be filled
     * @param flags The flags that started the session
     */
    @GuardedBy("mLock")
    private void fillContextWithAllowedValuesLocked(@NonNull FillContext fillContext, int flags) {
        final ViewNode[] nodes = fillContext
                .findViewNodesByAutofillIds(getIdsOfAllViewStatesLocked());

        final int numViewState = mViewStates.size();
        for (int i = 0; i < numViewState; i++) {
            final ViewState viewState = mViewStates.valueAt(i);

            final ViewNode node = nodes[i];
            if (node == null) {
                if (sVerbose) {
                    Slog.v(TAG,
                            "fillContextWithAllowedValuesLocked(): no node for " + viewState.id);
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
    @GuardedBy("mLock")
    private void cancelCurrentRequestLocked() {
        if (mRemoteFillService == null) {
            wtf(null, "cancelCurrentRequestLocked() called without a remote service. "
                    + "mForAugmentedAutofillOnly: %s", mSessionFlags.mAugmentedAutofillOnly);
            return;
        }
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

    private boolean isViewFocusedLocked(int flags) {
        return (flags & FLAG_VIEW_NOT_FOCUSED) == 0;
    }

    /**
     * Clears the existing response for the partition, reads a new structure, and then requests a
     * new fill response from the fill service.
     *
     * <p> Also asks the IME to make an inline suggestions request if it's enabled.
     */
    @GuardedBy("mLock")
    private void requestNewFillResponseLocked(@NonNull ViewState viewState, int newState,
            int flags) {
        boolean isSecondary = shouldRequestSecondaryProvider(flags);
        final FillResponse existingResponse = isSecondary
                ? viewState.getSecondaryResponse() : viewState.getResponse();
        mFillRequestEventLogger.startLogForNewRequest();
        mRequestCount++;
        mFillRequestEventLogger.maybeSetAppPackageUid(uid);
        mFillRequestEventLogger.maybeSetFlags(mFlags);
        if(mPreviouslyFillDialogPotentiallyStarted) {
            mFillRequestEventLogger.maybeSetRequestTriggerReason(TRIGGER_REASON_PRE_TRIGGER);
        } else {
            if ((flags & FLAG_MANUAL_REQUEST) != 0) {
                mFillRequestEventLogger.maybeSetRequestTriggerReason(
                        TRIGGER_REASON_EXPLICITLY_REQUESTED);
            } else {
                mFillRequestEventLogger.maybeSetRequestTriggerReason(
                        TRIGGER_REASON_NORMAL_TRIGGER);
            }
        }
        if (existingResponse != null) {
            setViewStatesLocked(
                    existingResponse,
                    ViewState.STATE_INITIAL,
                    /* clearResponse= */ true,
                    /* isPrimary= */ true);
            mFillRequestEventLogger.maybeSetRequestTriggerReason(
                    TRIGGER_REASON_SERVED_FROM_CACHED_RESPONSE);
        }
        mSessionFlags.mExpiredResponse = false;
        mSessionState = STATE_ACTIVE;
        if (mSessionFlags.mAugmentedAutofillOnly || mRemoteFillService == null) {
            if (sVerbose) {
                Slog.v(TAG, "requestNewFillResponse(): triggering augmented autofill instead "
                        + "(mForAugmentedAutofillOnly=" + mSessionFlags.mAugmentedAutofillOnly
                        + ", flags=" + flags + ")");
            }
            mSessionFlags.mAugmentedAutofillOnly = true;
            mFillRequestEventLogger.maybeSetRequestId(AUGMENTED_AUTOFILL_REQUEST_ID);
            mFillRequestEventLogger.maybeSetIsAugmented(true);
            mFillRequestEventLogger.logAndEndEvent();
            triggerAugmentedAutofillLocked(flags);
            return;
        }

        viewState.setState(newState);
        int requestId = getRequestId(isSecondary);

        // Create a metrics log for the request
        final int ordinal = mRequestLogs.size() + 1;
        final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_REQUEST)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_REQUEST_ORDINAL, ordinal);
        if (flags != 0) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_FLAGS, flags);
        }
        mRequestLogs.put(requestId, log);

        if (sVerbose) {
            Slog.v(TAG, "Requesting structure for request #" + ordinal + " ,requestId=" + requestId
                    + ", flags=" + flags);
        }
        boolean isCredmanRequested = (flags & FLAG_VIEW_REQUESTS_CREDMAN_SERVICE) != 0;
        mPresentationStatsEventLogger.maybeSetRequestId(requestId);
        mPresentationStatsEventLogger.maybeSetIsCredentialRequest(isCredmanRequested);
        mPresentationStatsEventLogger.maybeSetFieldClassificationRequestId(
                mFieldClassificationIdSnapshot);
        mPresentationStatsEventLogger.maybeSetAutofillServiceUid(getAutofillServiceUid());
        mFillRequestEventLogger.maybeSetRequestId(requestId);
        mFillRequestEventLogger.maybeSetAutofillServiceUid(getAutofillServiceUid());
        mSaveEventLogger.maybeSetAutofillServiceUid(getAutofillServiceUid());
        mSessionCommittedEventLogger.maybeSetAutofillServiceUid(getAutofillServiceUid());
        if (mSessionFlags.mInlineSupportedByService) {
            mFillRequestEventLogger.maybeSetInlineSuggestionHostUid(mContext, userId);
        }
        mFillRequestEventLogger.maybeSetIsFillDialogEligible(!mSessionFlags.mFillDialogDisabled);

        // If the focus changes very quickly before the first request is returned each focus change
        // triggers a new partition and we end up with many duplicate partitions. This is
        // enhanced as the focus change can be much faster than the taking of the assist structure.
        // Hence remove the currently queued request and replace it with the one queued after the
        // structure is taken. This causes only one fill request per burst of focus changes.
        cancelCurrentRequestLocked();

        if (mService.isPccClassificationEnabled()
                && mClassificationState.mHintsToAutofillIdMap == null) {
            if (sVerbose) {
                Slog.v(TAG, "triggering field classification");
            }
            requestAssistStructureForPccLocked(flags | FLAG_PCC_DETECTION);
        }

        // Only ask IME to create inline suggestions request if Autofill provider supports it and
        // the render service is available except the autofill is triggered manually and the view
        // is also not focused.
        final RemoteInlineSuggestionRenderService remoteRenderService =
                mService.getRemoteInlineSuggestionRenderServiceLocked();
        if (mSessionFlags.mInlineSupportedByService && remoteRenderService != null
            && (isViewFocusedLocked(flags) || isRequestSupportFillDialog(flags))) {
            Consumer<InlineSuggestionsRequest> inlineSuggestionsRequestConsumer =
                mAssistReceiver.newAutofillRequestLocked(viewState,
                    /* isInlineRequest= */ true);
            if (inlineSuggestionsRequestConsumer != null) {
                final int requestIdCopy = requestId;
                final AutofillId focusedId = mCurrentViewId;

                WeakReference sessionWeakReference = new WeakReference<Session>(this);
                InlineSuggestionRendorInfoCallbackOnResultListener
                        inlineSuggestionRendorInfoCallbackOnResultListener =
                                new InlineSuggestionRendorInfoCallbackOnResultListener(
                                        sessionWeakReference,
                                        requestIdCopy,
                                        inlineSuggestionsRequestConsumer,
                                        focusedId);
                RemoteCallback inlineSuggestionRendorInfoCallback = new RemoteCallback(
                        inlineSuggestionRendorInfoCallbackOnResultListener, mHandler);

                remoteRenderService.getInlineSuggestionsRendererInfo(
                        inlineSuggestionRendorInfoCallback);
                viewState.setState(ViewState.STATE_PENDING_CREATE_INLINE_REQUEST);
            }
        } else {
            mAssistReceiver.newAutofillRequestLocked(viewState, /* isInlineRequest= */ false);
        }

        // Now request the assist structure data.
        requestAssistStructureLocked(requestId, flags);
    }

    private static int getRequestId(boolean isSecondary) {
        // For authentication flows, there needs to be a way to know whether to retrieve the Fill
        // Response from the primary provider or the secondary provider from the requestId. A simple
        // way to achieve this is by assigning odd number request ids to secondary provider and
        // even numbers to primary provider.
        int requestId;
        // TODO(b/158623971): Update this to prevent possible overflow
        if (isSecondary) {
            do {
                requestId = sIdCounter.getAndIncrement();
            } while (!isSecondaryProviderRequestId(requestId));
        } else {
            do {
                requestId = sIdCounter.getAndIncrement();
            } while (requestId == INVALID_REQUEST_ID || isSecondaryProviderRequestId(requestId));
        }
        return requestId;
    }

    private boolean isRequestSupportFillDialog(int flags) {
        return (flags & FLAG_SUPPORTS_FILL_DIALOG) != 0;
    }

    @GuardedBy("mLock")
    private void requestAssistStructureForPccLocked(int flags) {
        if (!mClassificationState.shouldTriggerRequest()) return;
        mFillRequestIdSnapshot = sIdCounter.get();
        mClassificationState.updatePendingRequest();
        // Get request id
        int requestId;
        // TODO(b/158623971): Update this to prevent possible overflow
        do {
            requestId = sIdCounterForPcc.getAndIncrement();
        } while (requestId == INVALID_REQUEST_ID);

        if (sVerbose) {
            Slog.v(TAG, "request id is " + requestId + ", requesting assist structure for pcc");
        }
        // Call requestAutofilLData
        try {
            final Bundle receiverExtras = new Bundle();
            receiverExtras.putInt(EXTRA_REQUEST_ID, requestId);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (!ActivityTaskManager.getService().requestAutofillData(mPccAssistReceiver,
                        receiverExtras, mActivityToken, flags)) {
                    Slog.w(TAG, "failed to request autofill data for " + mActivityToken);
                }
            } finally {
                Binder.restoreCallingIdentity(identity);
            }
        } catch (RemoteException e) {
        }
    }

    @GuardedBy("mLock")
    private void requestAssistStructureLocked(int requestId, int flags) {
        try {
            final Bundle receiverExtras = new Bundle();
            receiverExtras.putInt(EXTRA_REQUEST_ID, requestId);
            final long identity = Binder.clearCallingIdentity();
            try {
                if (!ActivityTaskManager.getService().requestAutofillData(mAssistReceiver,
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
            @NonNull Context context, @NonNull Handler handler, int userId, @NonNull Object lock,
            int sessionId, int taskId, int uid, @NonNull IBinder activityToken,
            @NonNull IBinder client, boolean hasCallback, @NonNull LocalLog uiLatencyHistory,
            @NonNull LocalLog wtfHistory, @Nullable ComponentName serviceComponentName,
            @NonNull ComponentName componentName, boolean compatMode,
            boolean bindInstantServiceAllowed, boolean forAugmentedAutofillOnly, int flags,
            @NonNull InputMethodManagerInternal inputMethodManagerInternal,
            boolean isPrimaryCredential) {
        if (sessionId < 0) {
            wtf(null, "Non-positive sessionId: %s", sessionId);
        }
        id = sessionId;
        mFlags = flags;
        this.userId = userId;
        this.taskId = taskId;
        this.uid = uid;
        mService = service;
        mLock = lock;
        mUi = ui;
        mHandler = handler;

        mCredentialAutofillService = getCredentialAutofillService(context);

        ComponentName primaryServiceComponentName, secondaryServiceComponentName = null;
        if (isPrimaryCredential) {
            primaryServiceComponentName = mCredentialAutofillService;
            if (serviceComponentName != null
                    && !serviceComponentName.equals(mCredentialAutofillService)) {
                // if service component name is credential autofill service, no need to initialize
                // secondary provider. This happens if the user sets non-autofill provider as
                // password provider.
                secondaryServiceComponentName = serviceComponentName;
            }
        } else {
            primaryServiceComponentName = serviceComponentName;
            secondaryServiceComponentName = mCredentialAutofillService;
        }
        Slog.v(TAG, "Primary service component name: " + primaryServiceComponentName
                + ", secondary service component name: " + secondaryServiceComponentName);

        mRemoteFillService = primaryServiceComponentName == null ? null
                : new RemoteFillService(context, primaryServiceComponentName, userId, this,
                        bindInstantServiceAllowed, mCredentialAutofillService);
        mSecondaryProviderHandler = secondaryServiceComponentName == null ? null
                : new SecondaryProviderHandler(context, userId, bindInstantServiceAllowed,
                this::onSecondaryFillResponse, secondaryServiceComponentName,
                        mCredentialAutofillService);
        mActivityToken = activityToken;
        mHasCallback = hasCallback;
        mUiLatencyHistory = uiLatencyHistory;
        mWtfHistory = wtfHistory;
        int displayId = LocalServices.getService(ActivityTaskManagerInternal.class)
                .getDisplayId(activityToken);
        mContext = Helper.getDisplayContext(context, displayId);
        mComponentName = componentName;
        mCompatMode = compatMode;
        mSessionState = STATE_ACTIVE;
        // Initiate all loggers & counters.
        mStartTime = SystemClock.elapsedRealtime();
        mLatencyBaseTime = mStartTime;
        mRequestCount = 0;
        mPresentationStatsEventLogger = PresentationStatsEventLogger.createPresentationLog(
                sessionId, uid);
        mFillRequestEventLogger = FillRequestEventLogger.forSessionId(sessionId);
        mFillResponseEventLogger = FillResponseEventLogger.forSessionId(sessionId);
        mSessionCommittedEventLogger = SessionCommittedEventLogger.forSessionId(sessionId);
        mSessionCommittedEventLogger.maybeSetComponentPackageUid(uid);
        mSaveEventLogger = SaveEventLogger.forSessionId(sessionId, mLatencyBaseTime);
        mIsPrimaryCredential = isPrimaryCredential;
        mIgnoreViewStateResetToEmpty = AutofillFeatureFlags.shouldIgnoreViewStateResetToEmpty();

        synchronized (mLock) {
            mSessionFlags = new SessionFlags();
            mSessionFlags.mAugmentedAutofillOnly = forAugmentedAutofillOnly;
            mSessionFlags.mInlineSupportedByService = mService.isInlineSuggestionsEnabledLocked();
            setClientLocked(client);
        }

        mInlineSessionController = new AutofillInlineSessionController(inputMethodManagerInternal,
                userId, componentName, handler, mLock,
                new InlineFillUi.InlineUiEventCallback() {
                    @Override
                    public void notifyInlineUiShown(AutofillId autofillId) {
                        notifyFillUiShown(autofillId);

                        synchronized (mLock) {
                            // TODO(b/262448552): Log when chip inflates instead of here
                            final long inlineUiShownRelativeTimestamp =
                                    SystemClock.elapsedRealtime() - mLatencyBaseTime;
                            mPresentationStatsEventLogger.maybeSetSuggestionPresentedTimestampMs(
                                    (int) (inlineUiShownRelativeTimestamp));
                        }
                    }

                    @Override
                    public void notifyInlineUiHidden(AutofillId autofillId) {
                        notifyFillUiHidden(autofillId);
                    }
                });

        mMetricsLogger.write(newLogMaker(MetricsEvent.AUTOFILL_SESSION_STARTED)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_FLAGS, flags));
        mLogViewEntered = false;
    }

    private ComponentName getCredentialAutofillService(Context context) {
        ComponentName componentName = null;
        String credentialManagerAutofillCompName = context.getResources().getString(
                R.string.config_defaultCredentialManagerAutofillService);
        if (credentialManagerAutofillCompName != null
                && !credentialManagerAutofillCompName.isEmpty()) {
            componentName = ComponentName.unflattenFromString(
                    credentialManagerAutofillCompName);
        }
        if (componentName == null) {
            Slog.w(TAG, "Invalid CredentialAutofillService");
        }
        return componentName;
    }

    /**
     * Gets the currently registered activity token
     *
     * @return The activity token
     */
    @GuardedBy("mLock")
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
            setClientLocked(newClient);

            // The tracked id are not persisted in the client, hence update them
            updateTrackedIdsLocked();
        }
    }

    @GuardedBy("mLock")
    private void setClientLocked(@NonNull IBinder client) {
        unlinkClientVultureLocked();
        mClient = IAutoFillManagerClient.Stub.asInterface(client);
        mClientVulture = () -> {
            synchronized (mLock) {
                Slog.d(TAG, "handling death of " + mActivityToken + " when saving="
                        + mSessionFlags.mShowingSaveUi);
                if (mSessionFlags.mShowingSaveUi) {
                    mUi.hideFillUi(this);
                } else {
                    mUi.destroyAll(mPendingSaveUi, this, false);
                }
            }
        };
        try {
            mClient.asBinder().linkToDeath(mClientVulture, 0);
        } catch (RemoteException e) {
            Slog.w(TAG, "could not set binder death listener on autofill client: " + e);
            mClientVulture = null;
        }
    }

    @GuardedBy("mLock")
    private void unlinkClientVultureLocked() {
        if (mClient != null && mClientVulture != null) {
            final boolean unlinked = mClient.asBinder().unlinkToDeath(mClientVulture, 0);
            if (!unlinked) {
                Slog.w(TAG, "unlinking vulture from death failed for " + mActivityToken);
            }
            mClientVulture = null;
        }
    }

    // FillServiceCallbacks
    @Override
    @SuppressWarnings("GuardedBy")
    public void onFillRequestSuccess(int requestId, @Nullable FillResponse response,
            @NonNull String servicePackageName, int requestFlags) {
        final AutofillId[] fieldClassificationIds;

        final LogMaker requestLog;

        // Start a new FillResponse logger for the success case.
        mFillResponseEventLogger.startLogForNewResponse();
        mFillResponseEventLogger.maybeSetRequestId(requestId);
        mFillResponseEventLogger.maybeSetAppPackageUid(uid);
        mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_SUCCESS);
        mFillResponseEventLogger.startResponseProcessingTime();
        // Time passed since session was created
        final long fillRequestReceivedRelativeTimestamp =
            SystemClock.elapsedRealtime() - mLatencyBaseTime;
        mPresentationStatsEventLogger.maybeSetFillResponseReceivedTimestampMs(
            (int) (fillRequestReceivedRelativeTimestamp));
        mFillResponseEventLogger.maybeSetLatencyFillResponseReceivedMillis(
            (int) (fillRequestReceivedRelativeTimestamp));
        mFillResponseEventLogger.maybeSetDetectionPreference(getDetectionPreferenceForLogging());

        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestSuccess() rejected - session: "
                        + id + " destroyed");
                mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_SESSION_DESTROYED);
                mFillResponseEventLogger.logAndEndEvent();
                return;
            }

            if (mSessionFlags.mShowingSaveUi) {
                // Even though the session has not yet been destroyed at this point, after the
                // saveUi gets closed, the session will be destroyed and AutofillManager will reset
                // its state. Processing the fill request will result in a great chance of corrupt
                // state in Autofill.
                Slog.w(TAG, "Call to Session#onFillRequestSuccess() rejected - session: "
                        + id + " is showing saveUi");
                mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_SESSION_DESTROYED);
                mFillResponseEventLogger.logAndEndEvent();
                return;
            }

            requestLog = mRequestLogs.get(requestId);
            if (requestLog != null) {
                requestLog.setType(MetricsEvent.TYPE_SUCCESS);
            } else {
                Slog.w(TAG, "onFillRequestSuccess(): no request log for id " + requestId);
            }
            if (response == null) {
                mFillResponseEventLogger.maybeSetTotalDatasetsProvided(0);
                if (requestLog != null) {
                    requestLog.addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS, -1);
                }
                processNullResponseLocked(requestId, requestFlags);
                return;
            }

            // TODO: Check if this is required. We can still present datasets to the user even if
            //  traditional field classification is disabled.
            fieldClassificationIds = response.getFieldClassificationIds();
            if (fieldClassificationIds != null && !mService.isFieldClassificationEnabledLocked()) {
                Slog.w(TAG, "Ignoring " + response + " because field detection is disabled");
                processNullResponseLocked(requestId, requestFlags);
                return;
            }

            mLastFillDialogTriggerIds = response.getFillDialogTriggerIds();

            final int flags = response.getFlags();
            if ((flags & FillResponse.FLAG_DELAY_FILL) != 0) {
                Slog.v(TAG, "Service requested to wait for delayed fill response.");
                registerDelayedFillBroadcastLocked();
            }
        }

        mService.setLastResponse(id, response);

        synchronized (mLock) {
            if (mLogViewEntered) {
                mLogViewEntered = false;
                mService.logViewEntered(id, null);
            }
        }


        final long disableDuration = response.getDisableDuration();
        final boolean autofillDisabled = disableDuration > 0;
        if (autofillDisabled) {
            final int flags = response.getFlags();
            final boolean disableActivityOnly =
                    (flags & FillResponse.FLAG_DISABLE_ACTIVITY_ONLY) != 0;
            notifyDisableAutofillToClient(disableDuration,
                    disableActivityOnly ? mComponentName : null);

            if (disableActivityOnly) {
                mService.disableAutofillForActivity(mComponentName, disableDuration,
                        id, mCompatMode);
            } else {
                mService.disableAutofillForApp(mComponentName.getPackageName(), disableDuration,
                        id, mCompatMode);
            }

            synchronized (mLock) {
                mSessionFlags.mAutofillDisabled = true;

                // Although "standard" autofill is disabled, it might still trigger augmented
                // autofill
                if (triggerAugmentedAutofillLocked(requestFlags) != null) {
                    mSessionFlags.mAugmentedAutofillOnly = true;
                    if (sDebug) {
                        Slog.d(TAG, "Service disabled autofill for " + mComponentName
                                + ", but session is kept for augmented autofill only");
                    }
                    return;
                }
            }

            if (sDebug) {
                final StringBuilder message = new StringBuilder("Service disabled autofill for ")
                                .append(mComponentName)
                                .append(": flags=").append(flags)
                                .append(", duration=");
                TimeUtils.formatDuration(disableDuration, message);
                Slog.d(TAG, message.toString());
            }
        }
        List<Dataset> datasetList = response.getDatasets();
        if (((datasetList == null || datasetList.isEmpty()) && response.getAuthentication() == null)
                || autofillDisabled) {
            // Response is "empty" from a UI point of view, need to notify client.
            notifyUnavailableToClient(
                    autofillDisabled ? AutofillManager.STATE_DISABLED_BY_SERVICE : 0,
                    /* autofillableIds= */ null);
            synchronized (mLock) {
                mInlineSessionController.setInlineFillUiLocked(
                        InlineFillUi.emptyUi(mCurrentViewId));
            }
        }

        if (requestLog != null) {
            requestLog.addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS,
                            response.getDatasets() == null ? 0 : response.getDatasets().size());
            if (fieldClassificationIds != null) {
                requestLog.addTaggedData(
                        MetricsEvent.FIELD_AUTOFILL_NUM_FIELD_CLASSIFICATION_IDS,
                        fieldClassificationIds.length);
            }
        }

        int datasetCount = (datasetList == null) ? 0 : datasetList.size();
        mFillResponseEventLogger.maybeSetTotalDatasetsProvided(datasetCount);
        // It's possible that this maybe overwritten later on after PCC filtering.
        mFillResponseEventLogger.maybeSetAvailableCount(datasetCount);

        // TODO(b/266379948): Ideally wait for PCC request to finish for a while more
        // (say 100ms) before proceeding further on.

        processResponseLockedForPcc(response, response.getClientState(), requestFlags);
        mFillResponseEventLogger.maybeSetLatencyResponseProcessingMillis();
        mFillResponseEventLogger.logAndEndEvent();
    }


    @GuardedBy("mLock")
    private void processResponseLockedForPcc(@NonNull FillResponse response,
            @Nullable Bundle newClientState, int flags) {
        if (DBG) {
            Slog.d(TAG, "DBG: Initial response: " + response);
        }
        synchronized (mLock) {
            response = getEffectiveFillResponse(response);
            if (isEmptyResponse(response)) {
                // Treat it as a null response.
                processNullResponseLocked(
                        response != null ? response.getRequestId() : 0,
                        flags);
                return;
            }
            if (DBG) {
                Slog.d(TAG, "DBG: Processed response: " + response);
            }
            processResponseLocked(response, newClientState, flags);
        }
    }

    private boolean isEmptyResponse(FillResponse response) {
        if (response == null) return true;
        SaveInfo saveInfo = response.getSaveInfo();
        synchronized (mLock) {
            return ((response.getDatasets() == null || response.getDatasets().isEmpty())
                    && response.getAuthentication() == null
                    && (saveInfo == null
                        || (ArrayUtils.isEmpty(saveInfo.getOptionalIds())
                            && ArrayUtils.isEmpty(saveInfo.getRequiredIds())
                            && ((saveInfo.getFlags() & SaveInfo.FLAG_DELAY_SAVE) == 0)))
                    && (ArrayUtils.isEmpty(response.getFieldClassificationIds())));
        }
    }

    private FillResponse getEffectiveFillResponse(FillResponse response) {
        // TODO(b/266379948): label dataset source

        DatasetComputationContainer autofillProviderContainer = new DatasetComputationContainer();
        computeDatasetsForProviderAndUpdateContainer(response, autofillProviderContainer);

        if (DBG) {
            Slog.d(TAG, "DBG: computeDatasetsForProviderAndUpdateContainer: "
                    + autofillProviderContainer);
        }
        if (!mService.isPccClassificationEnabled())  {
            if (sVerbose) {
                Slog.v(TAG, "PCC classification is disabled");
            }
            return createShallowCopy(response, autofillProviderContainer);
        }
        synchronized (mLock) {
            if (mClassificationState.mState != ClassificationState.STATE_RESPONSE
                    || mClassificationState.mLastFieldClassificationResponse == null) {
                if (sVerbose) {
                    Slog.v(TAG, "PCC classification no last response:"
                            + (mClassificationState.mLastFieldClassificationResponse == null)
                            +   " ,ineligible state="
                            + (mClassificationState.mState != ClassificationState.STATE_RESPONSE));
                }
                return createShallowCopy(response, autofillProviderContainer);
            }
            if (!mClassificationState.processResponse()) return response;
        }
        boolean preferAutofillProvider = mService.getMaster().preferProviderOverPcc();
        boolean shouldUseFallback = mService.getMaster().shouldUsePccFallback();
        if (preferAutofillProvider && !shouldUseFallback) {
            if (sVerbose) {
                Slog.v(TAG, "preferAutofillProvider but no fallback");
            }
            return createShallowCopy(response, autofillProviderContainer);
        }

        if (DBG) {
            synchronized (mLock) {
                Slog.d(TAG, "DBG: ClassificationState: " + mClassificationState);
            }
        }
        DatasetComputationContainer detectionPccContainer = new DatasetComputationContainer();
        computeDatasetsForPccAndUpdateContainer(response, detectionPccContainer);
        if (DBG) {
            Slog.d(TAG, "DBG: computeDatasetsForPccAndUpdateContainer: " + detectionPccContainer);
        }

        DatasetComputationContainer resultContainer;
        if (preferAutofillProvider) {
            resultContainer = autofillProviderContainer;
            if (shouldUseFallback) {
                // add PCC datasets that are not detected by provider.
                addFallbackDatasets(autofillProviderContainer, detectionPccContainer);
            }
        } else {
            resultContainer = detectionPccContainer;
            if (shouldUseFallback) {
                // add Provider's datasets that are not detected by PCC.
                addFallbackDatasets(detectionPccContainer, autofillProviderContainer);
            }
        }
        // Create FillResponse with effectiveDatasets, and all the rest value from the original
        // response.
        return createShallowCopy(response, resultContainer);
    }

    private void onSecondaryFillResponse(@Nullable FillResponse fillResponse, int flags) {
        if (fillResponse == null) {
            return;
        }
        synchronized (mLock) {
            // TODO(b/319913595): refactor logging for fill response for primary and secondary
            //  providers
            // Start a new FillResponse logger for the success case.
            mFillResponseEventLogger.startLogForNewResponse();
            mFillResponseEventLogger.maybeSetRequestId(fillResponse.getRequestId());
            mFillResponseEventLogger.maybeSetAppPackageUid(uid);
            mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_SUCCESS);
            mFillResponseEventLogger.startResponseProcessingTime();
            // Time passed since session was created
            final long fillRequestReceivedRelativeTimestamp =
                    SystemClock.elapsedRealtime() - mLatencyBaseTime;
            mPresentationStatsEventLogger.maybeSetFillResponseReceivedTimestampMs(
                    (int) (fillRequestReceivedRelativeTimestamp));
            mFillResponseEventLogger.maybeSetLatencyFillResponseReceivedMillis(
                    (int) (fillRequestReceivedRelativeTimestamp));
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onSecondaryFillResponse() rejected - session: "
                        + id + " destroyed");
                mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_SESSION_DESTROYED);
                mFillResponseEventLogger.logAndEndEvent();
                return;
            }

            List<Dataset> datasetList = fillResponse.getDatasets();
            int datasetCount = (datasetList == null) ? 0 : datasetList.size();
            mFillResponseEventLogger.maybeSetTotalDatasetsProvided(datasetCount);
            mFillResponseEventLogger.maybeSetAvailableCount(datasetCount);
            if (mSecondaryResponses == null) {
                mSecondaryResponses = new SparseArray<>(2);
            }
            mSecondaryResponses.put(fillResponse.getRequestId(), fillResponse);
            setViewStatesLocked(fillResponse, ViewState.STATE_FILLABLE, /* clearResponse= */ false,
                    /* isPrimary= */ false);

            // Updates the UI, if necessary.
            final ViewState currentView = mViewStates.get(mCurrentViewId);
            if (currentView != null) {
                currentView.maybeCallOnFillReady(flags);
            }
            mFillResponseEventLogger.maybeSetLatencyResponseProcessingMillis();
            mFillResponseEventLogger.logAndEndEvent();
        }
    }

    private FillResponse createShallowCopy(
            FillResponse response, DatasetComputationContainer container) {
        return FillResponse.shallowCopy(
                response,
                new ArrayList<>(container.mDatasets),
                getEligibleSaveInfo(response));
    }

    private SaveInfo getEligibleSaveInfo(FillResponse response) {
        SaveInfo saveInfo = response.getSaveInfo();
        if (saveInfo == null || (!ArrayUtils.isEmpty(saveInfo.getOptionalIds())
                || !ArrayUtils.isEmpty(saveInfo.getRequiredIds())
                || (saveInfo.getFlags() & SaveInfo.FLAG_DELAY_SAVE) != 0)) {
            return saveInfo;
        }
        synchronized (mLock) {
            ArrayMap<String, Set<AutofillId>> hintsToAutofillIdMap =
                    mClassificationState.mHintsToAutofillIdMap;
            if (hintsToAutofillIdMap == null || hintsToAutofillIdMap.isEmpty()) {
                return saveInfo;
            }

            ArraySet<AutofillId> ids = new ArraySet<>();
            int saveType = saveInfo.getType();
            if (saveType == SaveInfo.SAVE_DATA_TYPE_GENERIC) {
                for (Set<AutofillId> autofillIds: hintsToAutofillIdMap.values()) {
                    ids.addAll(autofillIds);
                }
            } else {
                Set<String> hints = HintsHelper.getHintsForSaveType(saveType);
                for (Map.Entry<String, Set<AutofillId>> entry: hintsToAutofillIdMap.entrySet()) {
                    String hint = entry.getKey();
                    if (hints.contains(hint)) {
                        ids.addAll(entry.getValue());
                    }
                }
            }
            if (ids.isEmpty()) return saveInfo;
            AutofillId[] autofillIds = new AutofillId[ids.size()];
            mSaveEventLogger.maybeSetIsFrameworkCreatedSaveInfo(true);
            ids.toArray(autofillIds);
            return SaveInfo.copy(saveInfo, autofillIds);
        }
    }

    /**
     * A private class to hold & compute datasets to be shown
     */
    private static class DatasetComputationContainer {
        // List of all autofill ids that have a corresponding datasets
        Set<AutofillId> mAutofillIds = new LinkedHashSet<>();
        // Set of datasets. Kept separately, to be able to be used directly for composing
        // FillResponse.
        Set<Dataset> mDatasets = new LinkedHashSet<>();
        Map<AutofillId, Set<Dataset>> mAutofillIdToDatasetMap = new LinkedHashMap<>();

        public String toString() {
            final StringBuilder builder = new StringBuilder("DatasetComputationContainer[");
            if (mAutofillIds != null) {
                builder.append(", autofillIds=").append(mAutofillIds);
            }
            if (mDatasets != null) {
                builder.append(", mDatasets=").append(mDatasets);
            }
            if (mAutofillIdToDatasetMap != null) {
                builder.append(", mAutofillIdToDatasetMap=").append(mAutofillIdToDatasetMap);
            }
            return builder.append(']').toString();
        }
    }

    // Adds fallback datasets to the first container.
    // This function will destruct and modify c2 container.
    private void addFallbackDatasets(
            DatasetComputationContainer c1, DatasetComputationContainer c2) {
        for (AutofillId id : c2.mAutofillIds) {
            if (!c1.mAutofillIds.contains(id)) {

                // Since c2 could be modified in a previous iteration, it's possible that all
                // datasets corresponding to it have been evaluated, and it's map no longer has
                // any more datasets left. Early return in this case.
                if (c2.mAutofillIdToDatasetMap.get(id).isEmpty()) return;

                // For AutofillId id, do the following
                // 1. Add all the datasets corresponding to it to c1's dataset, and update c1
                // properly.
                // 2. All the datasets that were added should be removed from the other autofill
                // ids that were in this dataset. This prevents us from revisiting those datasets.
                // Although we are using Sets, and that'd avoid re-adding them, using this logic
                // for now to keep safe. TODO(b/266379948): Revisit this logic.

                Set<Dataset> datasets = c2.mAutofillIdToDatasetMap.get(id);
                Set<Dataset> copyDatasets = new LinkedHashSet<>(datasets);
                c1.mAutofillIds.add(id);
                c1.mAutofillIdToDatasetMap.put(id, copyDatasets);
                c1.mDatasets.addAll(copyDatasets);

                for (Dataset dataset : datasets) {
                    for (AutofillId currentId : dataset.getFieldIds()) {
                        if (currentId.equals(id)) continue;
                        // For this id, we need to remove the dataset from it's map.
                        c2.mAutofillIdToDatasetMap.get(currentId).remove(dataset);
                    }
                }
            }
        }
    }

    /**
     * Computes datasets that are eligible to be shown based on provider detections.
     * Datasets are populated in the provided container for them to be later merged with the
     * PCC eligible datasets based on preference strategy.
     * @param response
     * @param container
     */
    private void computeDatasetsForProviderAndUpdateContainer(
            FillResponse response, DatasetComputationContainer container) {
        @DatasetEligibleReason int globalPickReason = PICK_REASON_UNKNOWN;
        boolean isPccEnabled = mService.isPccClassificationEnabled();
        if (isPccEnabled) {
            globalPickReason = PICK_REASON_PROVIDER_DETECTION_ONLY;
        } else {
            globalPickReason = PICK_REASON_NO_PCC;
        }
        List<Dataset> datasets = response.getDatasets();
        if (datasets == null) return;
        Map<AutofillId, Set<Dataset>> autofillIdToDatasetMap = new LinkedHashMap<>();
        Set<Dataset> eligibleDatasets = new LinkedHashSet<>();
        Set<AutofillId> eligibleAutofillIds = new LinkedHashSet<>();
        for (Dataset dataset : response.getDatasets()) {
            if (dataset.getFieldIds() == null || dataset.getFieldIds().isEmpty()) continue;
            @DatasetEligibleReason int pickReason = globalPickReason;
            if (dataset.getAutofillDatatypes() != null
                    && !dataset.getAutofillDatatypes().isEmpty()) {
                // This dataset has information relevant for detection too, so we should filter
                // them out. It's possible that some fields are applicable to hints only, as such,
                // they need to be filtered off.
                // TODO(b/266379948): Verify the logic and add tests
                // Update dataset to only have non-null fieldValues

                // Figure out if we need to process results.
                boolean conversionRequired = false;
                int newSize = dataset.getFieldIds().size();
                for (AutofillId id : dataset.getFieldIds()) {
                    if (id == null) {
                        conversionRequired = true;
                        newSize--;
                    }
                }

                // If the dataset doesn't have any non-null autofill id's, pass over.
                if (newSize == 0) continue;

                if (conversionRequired) {
                    pickReason = PICK_REASON_PROVIDER_DETECTION_PREFERRED_WITH_PCC;
                    ArrayList<AutofillId> fieldIds = new ArrayList<>(newSize);
                    ArrayList<AutofillValue> fieldValues = new ArrayList<>(newSize);
                    ArrayList<RemoteViews> fieldPresentations = new ArrayList<>(newSize);
                    ArrayList<RemoteViews> fieldDialogPresentations = new ArrayList<>(newSize);
                    ArrayList<InlinePresentation> fieldInlinePresentations =
                            new ArrayList<>(newSize);
                    ArrayList<InlinePresentation> fieldInlineTooltipPresentations =
                            new ArrayList<>(newSize);
                    ArrayList<Dataset.DatasetFieldFilter> fieldFilters = new ArrayList<>(newSize);

                    for (int i = 0; i < dataset.getFieldIds().size(); i++) {
                        AutofillId id = dataset.getFieldIds().get(i);
                        if (id != null) {
                            // Copy over
                            fieldIds.add(id);
                            fieldValues.add(dataset.getFieldValues().get(i));
                            fieldPresentations.add(dataset.getFieldPresentation(i));
                            fieldDialogPresentations.add(dataset.getFieldDialogPresentation(i));
                            fieldInlinePresentations.add(dataset.getFieldInlinePresentation(i));
                            fieldInlineTooltipPresentations.add(
                                    dataset.getFieldInlineTooltipPresentation(i));
                            fieldFilters.add(dataset.getFilter(i));
                        }
                    }
                    dataset =
                            new Dataset(
                                    fieldIds,
                                    fieldValues,
                                    fieldPresentations,
                                    fieldDialogPresentations,
                                    fieldInlinePresentations,
                                    fieldInlineTooltipPresentations,
                                    fieldFilters,
                                    new ArrayList<>(),
                                    dataset.getFieldContent(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    dataset.getId(),
                                    dataset.getAuthentication());
                }
            }
            dataset.setEligibleReasonReason(pickReason);
            eligibleDatasets.add(dataset);
            for (AutofillId id : dataset.getFieldIds()) {
                eligibleAutofillIds.add(id);
                Set<Dataset> datasetForIds = autofillIdToDatasetMap.get(id);
                if (datasetForIds == null) {
                    datasetForIds = new LinkedHashSet<>();
                }
                datasetForIds.add(dataset);
                autofillIdToDatasetMap.put(id, datasetForIds);
            }
        }
        container.mAutofillIdToDatasetMap = autofillIdToDatasetMap;
        container.mDatasets = eligibleDatasets;
        container.mAutofillIds = eligibleAutofillIds;
    }

    /**
     * Computes datasets that are eligible to be shown based on PCC detections.
     * Datasets are populated in the provided container for them to be later merged with the
     * provider eligible datasets based on preference strategy.
     * @param response
     * @param container
     */
    private void computeDatasetsForPccAndUpdateContainer(
            FillResponse response, DatasetComputationContainer container) {
        List<Dataset> datasets = response.getDatasets();
        if (datasets == null) return;

        synchronized (mLock) {
            Map<String, Set<AutofillId>> hintsToAutofillIdMap =
                    mClassificationState.mHintsToAutofillIdMap;

            // TODO(266379948): Handle group hints too.
            Map<String, Set<AutofillId>> groupHintsToAutofillIdMap =
                    mClassificationState.mGroupHintsToAutofillIdMap;

            Map<AutofillId, Set<Dataset>> map = new LinkedHashMap<>();

            Set<Dataset> eligibleDatasets = new LinkedHashSet<>();
            Set<AutofillId> eligibleAutofillIds = new LinkedHashSet<>();

            for (int i = 0; i < datasets.size(); i++) {

                @DatasetEligibleReason int pickReason = PICK_REASON_PCC_DETECTION_ONLY;
                Dataset dataset = datasets.get(i);
                if (dataset.getAutofillDatatypes() == null
                        || dataset.getAutofillDatatypes().isEmpty()) continue;

                ArrayList<AutofillId> fieldIds = new ArrayList<>();
                ArrayList<AutofillValue> fieldValues = new ArrayList<>();
                ArrayList<RemoteViews> fieldPresentations = new ArrayList<>();
                ArrayList<RemoteViews> fieldDialogPresentations = new ArrayList<>();
                ArrayList<InlinePresentation> fieldInlinePresentations = new ArrayList<>();
                ArrayList<InlinePresentation> fieldInlineTooltipPresentations = new ArrayList<>();
                ArrayList<Dataset.DatasetFieldFilter> fieldFilters = new ArrayList<>();
                Set<AutofillId> datasetAutofillIds = new LinkedHashSet<>();

                boolean isDatasetAvailable = false;
                Set<AutofillId> additionalDatasetAutofillIds = new LinkedHashSet<>();
                Set<AutofillId> additionalEligibleAutofillIds = new LinkedHashSet<>();

                for (int j = 0; j < dataset.getAutofillDatatypes().size(); j++) {
                    if (dataset.getAutofillDatatypes().get(j) == null) {
                        // TODO : revisit pickReason logic
                        if (dataset.getFieldIds() != null && dataset.getFieldIds().get(j) != null) {
                            pickReason = PICK_REASON_PCC_DETECTION_PREFERRED_WITH_PROVIDER;
                        }
                        // Check if the autofill id at this index is detected by PCC.
                        // If not, add that id here, otherwise, we can have duplicates when later
                        // merging with provider datasets.
                        // Howover, this doesn't make datasetAvailable for PCC on its own.
                        // For that, there has to be a datatype detected by PCC, and the dataset
                        // for that datatype provided by the provider.
                        AutofillId autofillId = dataset.getFieldIds().get(j);
                        if (!mClassificationState.mClassificationCombinedHintsMap
                                .containsKey(autofillId)) {
                            additionalEligibleAutofillIds.add(autofillId);
                            additionalDatasetAutofillIds.add(autofillId);
                            // For each of the field, copy over values.
                            copyFieldsFromDataset(dataset, j, autofillId, fieldIds, fieldValues,
                                    fieldPresentations, fieldDialogPresentations,
                                    fieldInlinePresentations, fieldInlineTooltipPresentations,
                                    fieldFilters);
                        }
                        continue;
                    }
                    String hint = dataset.getAutofillDatatypes().get(j);

                    if (hintsToAutofillIdMap.containsKey(hint)) {
                        ArrayList<AutofillId> tempIds =
                                new ArrayList<>(hintsToAutofillIdMap.get(hint));
                        if (tempIds.isEmpty()) {
                            continue;
                        }
                        isDatasetAvailable = true;
                        for (AutofillId autofillId : tempIds) {
                            eligibleAutofillIds.add(autofillId);
                            datasetAutofillIds.add(autofillId);
                            // For each of the field, copy over values.
                            copyFieldsFromDataset(dataset, j, autofillId, fieldIds, fieldValues,
                                    fieldPresentations, fieldDialogPresentations,
                                    fieldInlinePresentations, fieldInlineTooltipPresentations,
                                    fieldFilters);
                        }
                    }
                    // TODO(b/266379948):  handle the case:
                    // groupHintsToAutofillIdMap.containsKey(hint))
                    // but the autofill id not being applicable to other hints.
                    // TODO(b/266379948):  also handle the case where there could be more types in
                    // the dataset, provided by the provider, however, they aren't applicable.
                }
                if (isDatasetAvailable) {
                    datasetAutofillIds.addAll(additionalDatasetAutofillIds);
                    eligibleAutofillIds.addAll(additionalEligibleAutofillIds);
                    Dataset newDataset =
                            new Dataset(
                                    fieldIds,
                                    fieldValues,
                                    fieldPresentations,
                                    fieldDialogPresentations,
                                    fieldInlinePresentations,
                                    fieldInlineTooltipPresentations,
                                    fieldFilters,
                                    new ArrayList<>(),
                                    dataset.getFieldContent(),
                                    null,
                                    null,
                                    null,
                                    null,
                                    dataset.getId(),
                                    dataset.getAuthentication());
                    newDataset.setEligibleReasonReason(pickReason);
                    eligibleDatasets.add(newDataset);
                    Set<Dataset> newDatasets;
                    for (AutofillId autofillId : datasetAutofillIds) {
                        if (map.containsKey(autofillId)) {
                            newDatasets = map.get(autofillId);
                        } else {
                            newDatasets = new LinkedHashSet<>();
                        }
                        newDatasets.add(newDataset);
                        map.put(autofillId, newDatasets);
                    }
                }
            }
            container.mAutofillIds = eligibleAutofillIds;
            container.mDatasets = eligibleDatasets;
            container.mAutofillIdToDatasetMap = map;
        }
    }

    private void copyFieldsFromDataset(
            Dataset dataset,
            int index,
            AutofillId autofillId,
            ArrayList<AutofillId> fieldIds,
            ArrayList<AutofillValue> fieldValues,
            ArrayList<RemoteViews> fieldPresentations,
            ArrayList<RemoteViews> fieldDialogPresentations,
            ArrayList<InlinePresentation> fieldInlinePresentations,
            ArrayList<InlinePresentation> fieldInlineTooltipPresentations,
            ArrayList<Dataset.DatasetFieldFilter> fieldFilters) {
        // copy over values
        fieldIds.add(autofillId);
        fieldValues.add(dataset.getFieldValues().get(index));
        //  TODO(b/266379948): might need to make it more efficient by not
        //  copying over value if it didn't exist. This would require creating
        //  a getter for the presentations arraylist.
        fieldPresentations.add(dataset.getFieldPresentation(index));
        fieldDialogPresentations.add(dataset.getFieldDialogPresentation(index));
        fieldInlinePresentations.add(dataset.getFieldInlinePresentation(index));
        fieldInlineTooltipPresentations.add(
                dataset.getFieldInlineTooltipPresentation(index));
        fieldFilters.add(dataset.getFilter(index));
    }

    // FillServiceCallbacks
    @Override
    @SuppressWarnings("GuardedBy")
    public void onFillRequestFailure(int requestId, @Nullable CharSequence message) {
        onFillRequestFailureOrTimeout(requestId, false, message);
    }

    // FillServiceCallbacks
    @Override
    @SuppressWarnings("GuardedBy")
    public void onFillRequestTimeout(int requestId) {
        onFillRequestFailureOrTimeout(requestId, true, null);
    }

    @SuppressWarnings("GuardedBy")
    private void onFillRequestFailureOrTimeout(int requestId, boolean timedOut,
            @Nullable CharSequence message) {
        boolean showMessage = !TextUtils.isEmpty(message);

        // Start a new FillResponse logger for the failure or timeout case.
        mFillResponseEventLogger.startLogForNewResponse();
        mFillResponseEventLogger.maybeSetRequestId(requestId);
        mFillResponseEventLogger.maybeSetAppPackageUid(uid);
        mFillResponseEventLogger.maybeSetAvailableCount(
                AVAILABLE_COUNT_WHEN_FILL_REQUEST_FAILED_OR_TIMEOUT);
        mFillResponseEventLogger.maybeSetTotalDatasetsProvided(
                AVAILABLE_COUNT_WHEN_FILL_REQUEST_FAILED_OR_TIMEOUT);
        mFillResponseEventLogger.maybeSetDetectionPreference(getDetectionPreferenceForLogging());
        final long fillRequestReceivedRelativeTimestamp =
            SystemClock.elapsedRealtime() - mLatencyBaseTime;
        mFillResponseEventLogger.maybeSetLatencyFillResponseReceivedMillis(
            (int)(fillRequestReceivedRelativeTimestamp));

        synchronized (mLock) {
            unregisterDelayedFillBroadcastLocked();
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestFailureOrTimeout(req=" + requestId
                        + ") rejected - session: " + id + " destroyed");
                mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_SESSION_DESTROYED);
                mFillResponseEventLogger.logAndEndEvent();

                return;
            }
            if (sDebug) {
                Slog.d(TAG, "finishing session due to service "
                        + (timedOut ? "timeout" : "failure"));
            }
            mService.resetLastResponse();
            mLastFillDialogTriggerIds = null;
            final LogMaker requestLog = mRequestLogs.get(requestId);
            if (requestLog == null) {
                Slog.w(TAG, "onFillRequestFailureOrTimeout(): no log for id " + requestId);
            } else {
                requestLog.setType(timedOut ? MetricsEvent.TYPE_CLOSE : MetricsEvent.TYPE_FAILURE);
            }
            if (showMessage) {
                final int targetSdk = mService.getTargedSdkLocked();
                if (targetSdk >= Build.VERSION_CODES.Q) {
                    showMessage = false;
                    Slog.w(TAG, "onFillRequestFailureOrTimeout(): not showing '" + message
                            + "' because service's targetting API " + targetSdk);
                }
                if (message != null) {
                    requestLog.addTaggedData(MetricsEvent.FIELD_AUTOFILL_TEXT_LEN,
                            message.length());
                }
            }

            if (timedOut) {
                mPresentationStatsEventLogger.maybeSetNoPresentationEventReason(
                        NOT_SHOWN_REASON_REQUEST_TIMEOUT);
                mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_TIMEOUT);
            } else {
                mPresentationStatsEventLogger.maybeSetNoPresentationEventReason(
                        NOT_SHOWN_REASON_REQUEST_FAILED);
                mFillResponseEventLogger.maybeSetResponseStatus(RESPONSE_STATUS_FAILURE);
            }
            mPresentationStatsEventLogger.logAndEndEvent();
            mFillResponseEventLogger.maybeSetLatencyResponseProcessingMillis();
            mFillResponseEventLogger.logAndEndEvent();
        }
        notifyUnavailableToClient(AutofillManager.STATE_UNKNOWN_FAILED,
                /* autofillableIds= */ null);
        if (showMessage) {
            getUiForShowing().showError(message, this);
        }
        removeFromService();
    }

    // FillServiceCallbacks
    @Override
    public void onSaveRequestSuccess(@NonNull String servicePackageName,
            @Nullable IntentSender intentSender) {
        synchronized (mLock) {
            mSessionFlags.mShowingSaveUi = false;
            // Log onSaveRequest result.
            mSaveEventLogger.maybeSetIsSaved(true);
            mSaveEventLogger.maybeSetLatencySaveFinishMillis();
            mSaveEventLogger.logAndEndEvent();
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onSaveRequestSuccess() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_DATA_SAVE_REQUEST, servicePackageName)
                .setType(intentSender == null ? MetricsEvent.TYPE_SUCCESS : MetricsEvent.TYPE_OPEN);
        mMetricsLogger.write(log);


        if (intentSender != null) {
            if (sDebug) Slog.d(TAG, "Starting intent sender on save()");
            startIntentSenderAndFinishSession(intentSender);
        }

        // Nothing left to do...
        removeFromService();
    }

    // FillServiceCallbacks
    @Override
    public void onSaveRequestFailure(@Nullable CharSequence message,
            @NonNull String servicePackageName) {
        boolean showMessage = !TextUtils.isEmpty(message);

        synchronized (mLock) {
            mSessionFlags.mShowingSaveUi = false;
            // Log onSaveRequest result.
            mSaveEventLogger.maybeSetLatencySaveFinishMillis();
            mSaveEventLogger.logAndEndEvent();
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onSaveRequestFailure() rejected - session: "
                        + id + " destroyed");
                return;
            }
            if (showMessage) {
                final int targetSdk = mService.getTargedSdkLocked();
                if (targetSdk >= Build.VERSION_CODES.Q) {
                    showMessage = false;
                    Slog.w(TAG, "onSaveRequestFailure(): not showing '" + message
                            + "' because service's targetting API " + targetSdk);
                }
            }
        }
        final LogMaker log =
                newLogMaker(MetricsEvent.AUTOFILL_DATA_SAVE_REQUEST, servicePackageName)
                .setType(MetricsEvent.TYPE_FAILURE);
        if (message != null) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_TEXT_LEN, message.length());
        }
        mMetricsLogger.write(log);


        if (showMessage) {
            getUiForShowing().showError(message, this);
        }
        removeFromService();
    }

    // FillServiceCallbacks
    @Override
    public void onConvertCredentialRequestSuccess(@NonNull ConvertCredentialResponse
            convertCredentialResponse) {
        Dataset dataset = convertCredentialResponse.getDataset();
        Bundle clientState = convertCredentialResponse.getClientState();
        if (dataset != null) {
            int requestId = -1;
            if (clientState != null) {
                requestId = clientState.getInt(EXTRA_AUTOFILL_REQUEST_ID);
            } else {
                Slog.e(TAG, "onConvertCredentialRequestSuccess(): client state is null, this "
                        + "would cause loss in logging.");
            }
            // TODO: Add autofill related logging; consider whether to log the index
            fill(requestId, /* datasetIndex=*/ -1, dataset, UI_TYPE_CREDMAN_BOTTOM_SHEET);
        } else {
            // TODO: Add logging to log this error case
            Slog.e(TAG, "onConvertCredentialRequestSuccess(): dataset inside response is "
                    + "null");
        }
    }

    /**
     * Gets the {@link FillContext} for a request.
     *
     * @param requestId The id of the request
     *
     * @return The context or {@code null} if there is no context
     */
    @GuardedBy("mLock")
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

    // VultureCallback
    @Override
    public void onServiceDied(@NonNull RemoteFillService service) {
        Slog.w(TAG, "removing session because service died");
        synchronized (mLock) {
            forceRemoveFromServiceLocked();
        }
    }

    // AutoFillUiCallback
    @Override
    public void authenticate(int requestId, int datasetIndex, IntentSender intent, Bundle extras,
            int uiType) {
        if (sDebug) {
            Slog.d(TAG, "authenticate(): requestId=" + requestId + "; datasetIdx=" + datasetIndex
                    + "; intentSender=" + intent);
        }
        final Intent fillInIntent;
        synchronized (mLock) {
            mPresentationStatsEventLogger.maybeSetAuthenticationType(
                AUTHENTICATION_TYPE_FULL_AUTHENTICATION);
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#authenticate() rejected - session: "
                        + id + " destroyed");
                return;
            }
            fillInIntent = createAuthFillInIntentLocked(requestId, extras);
            if (fillInIntent == null) {
                forceRemoveFromServiceLocked();
                return;
            }
        }

        mService.setAuthenticationSelected(id, mClientState, uiType);

        final int authenticationId = AutofillManager.makeAuthenticationId(requestId, datasetIndex);
        mHandler.sendMessage(obtainMessage(
                Session::startAuthentication,
                this, authenticationId, intent, fillInIntent,
                /* authenticateInline= */ uiType == UI_TYPE_INLINE));
    }

    // AutoFillUiCallback
    @Override
    public void fill(int requestId, int datasetIndex, Dataset dataset, int uiType) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#fill() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        mHandler.sendMessage(obtainMessage(
                Session::autoFill,
                this, requestId, datasetIndex, dataset, true, uiType));
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
        mSaveEventLogger.maybeSetLatencySaveRequestMillis();
        mHandler.sendMessage(obtainMessage(
                AutofillManagerServiceImpl::handleSessionSave,
                mService, this));
    }

    // AutoFillUiCallback
    @Override
    public void cancelSave() {
        synchronized (mLock) {
            mSessionFlags.mShowingSaveUi = false;
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#cancelSave() rejected - session: "
                        + id + " destroyed");
                return;
            }
        }
        mHandler.sendMessage(obtainMessage(
                Session::removeFromService, this));
    }

    // AutofillUiCallback
    @Override
    public void onShown(int uiType) {
        synchronized (mLock) {
            if (uiType == UI_TYPE_INLINE) {
                if (mLoggedInlineDatasetShown) {
                    // Chip inflation already logged, do not log again.
                    // This is needed because every chip inflation will call this.
                    return;
                }
                mLoggedInlineDatasetShown = true;
            }
            mService.logDatasetShown(this.id, mClientState, uiType);
            Slog.d(TAG, "onShown(): " + uiType);
        }
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
    public void dispatchUnhandledKey(AutofillId id, KeyEvent keyEvent) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#dispatchUnhandledKey() rejected - session: "
                        + id + " destroyed");
                return;
            }
            if (id.equals(mCurrentViewId)) {
                try {
                    mClient.dispatchUnhandledKey(this.id, id, keyEvent);
                } catch (RemoteException e) {
                    Slog.e(TAG, "Error requesting to dispatch unhandled key", e);
                }
            } else {
                Slog.w(TAG, "Do not dispatch unhandled key on " + id
                        + " as it is not the current view (" + mCurrentViewId + ") anymore");
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

            mInlineSessionController.hideInlineSuggestionsUiLocked(id);
        }
    }

    @Override
    public void requestHideFillUiWhenDestroyed(AutofillId id) {
        synchronized (mLock) {
            // NOTE: We allow this call in a destroyed state as the UI is
            // asked to go away after we get destroyed, so let it do that.
            try {
                mClient.requestHideFillUiWhenDestroyed(this.id, id);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error requesting to hide fill UI", e);
            }

            mInlineSessionController.hideInlineSuggestionsUiLocked(id);
        }
    }

    // AutoFillUiCallback
    @Override
    public void cancelSession() {
        synchronized (mLock) {
            removeFromServiceLocked();
        }
    }

    // AutoFillUiCallback
    @Override
    public void startIntentSenderAndFinishSession(IntentSender intentSender) {
        startIntentSender(intentSender, null);
    }

    // AutoFillUiCallback
    @Override
    public void startIntentSender(IntentSender intentSender, Intent intent) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#startIntentSender() rejected - session: "
                        + id + " destroyed");
                return;
            }
            if (intent == null) {
                removeFromServiceLocked();
            }
        }
        mHandler.sendMessage(obtainMessage(
                Session::doStartIntentSender,
                this, intentSender, intent));
    }

    // AutoFillUiCallback
    @Override
    public void requestShowSoftInput(AutofillId id) {
        IAutoFillManagerClient client = getClient();
        if (client != null) {
            try {
                client.requestShowSoftInput(id);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending input show up notification", e);
            }
        }
    }

    // AutoFillUiCallback
    @Override
    public void requestFallbackFromFillDialog() {
        setFillDialogDisabled();
        synchronized (mLock) {
            if (mCurrentViewId == null) {
                return;
            }
            final ViewState currentView = mViewStates.get(mCurrentViewId);
            currentView.maybeCallOnFillReady(mFlags);
        }
    }

    private void notifyFillUiHidden(@NonNull AutofillId autofillId) {
        synchronized (mLock) {
            try {
                mClient.notifyFillUiHidden(this.id, autofillId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending fill UI hidden notification", e);
            }
        }
    }

    private void notifyFillUiShown(@NonNull AutofillId autofillId) {
        synchronized (mLock) {
            try {
                mClient.notifyFillUiShown(this.id, autofillId);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error sending fill UI shown notification", e);
            }
        }
    }

    private void doStartIntentSender(IntentSender intentSender, Intent intent) {
        try {
            synchronized (mLock) {
                mClient.startIntentSender(intentSender, intent);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Error launching auth intent", e);
        }
    }

    @GuardedBy("mLock")
    void setAuthenticationResultLocked(Bundle data, int authenticationId) {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#setAuthenticationResultLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        if (sDebug) {
            Slog.d(TAG, "setAuthenticationResultLocked(): id= " + authenticationId
                    + ", data=" + data);
        }
        final int requestId = AutofillManager.getRequestIdFromAuthenticationId(authenticationId);
        if (requestId == AUGMENTED_AUTOFILL_REQUEST_ID) {
            setAuthenticationResultForAugmentedAutofillLocked(data, authenticationId);
            // Augmented autofill is not logged.
            mPresentationStatsEventLogger.logAndEndEvent();
            return;
        }
        if (mResponses == null) {
            // Typically happens when app explicitly called cancel() while the service was showing
            // the auth UI.
            Slog.w(TAG, "setAuthenticationResultLocked(" + authenticationId + "): no responses");
            mPresentationStatsEventLogger.maybeSetAuthenticationResult(
                AUTHENTICATION_RESULT_FAILURE);
            mPresentationStatsEventLogger.logAndEndEvent();
            removeFromService();
            return;
        }
        final FillResponse authenticatedResponse = isSecondaryProviderRequestId(requestId)
                ? mSecondaryResponses.get(requestId)
                : mResponses.get(requestId);
        if (authenticatedResponse == null || data == null) {
            Slog.w(TAG, "no authenticated response");
            mPresentationStatsEventLogger.maybeSetAuthenticationResult(
                AUTHENTICATION_RESULT_FAILURE);
            mPresentationStatsEventLogger.logAndEndEvent();
            removeFromService();
            return;
        }

        final int datasetIdx = AutofillManager.getDatasetIdFromAuthenticationId(
                authenticationId);
        Dataset dataset = null;
        // Authenticated a dataset - reset view state regardless if we got a response or a dataset
        if (datasetIdx != AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED) {
            dataset = authenticatedResponse.getDatasets().get(datasetIdx);
            if (dataset == null) {
                Slog.w(TAG, "no dataset with index " + datasetIdx + " on fill response");
                mPresentationStatsEventLogger.maybeSetAuthenticationResult(
                    AUTHENTICATION_RESULT_FAILURE);
                mPresentationStatsEventLogger.logAndEndEvent();
                removeFromService();
                return;
            }
        }

        // The client becomes invisible for the authentication, the response is effective.
        mSessionFlags.mExpiredResponse = false;

        final Parcelable result = data.getParcelable(AutofillManager.EXTRA_AUTHENTICATION_RESULT);
        final GetCredentialException exception = data.getSerializable(
                CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION,
                GetCredentialException.class);

        final Bundle newClientState = data.getBundle(AutofillManager.EXTRA_CLIENT_STATE);
        if (sDebug) {
            Slog.d(TAG, "setAuthenticationResultLocked(): result=" + result
                    + ", clientState=" + newClientState + ", authenticationId=" + authenticationId);
        }
        if (Flags.autofillCredmanDevIntegration() && exception != null
                && !exception.getType().equals(GetCredentialException.TYPE_USER_CANCELED)) {
            if (dataset != null && dataset.getFieldIds().size() == 1) {
                if (sDebug) {
                    Slog.d(TAG, "setAuthenticationResultLocked(): result returns with"
                            + "Credential Manager Exception");
                }
                AutofillId autofillId = dataset.getFieldIds().get(0);
                sendCredentialManagerResponseToApp(/*response=*/ null,
                        (GetCredentialException) exception, autofillId);
            }
            return;
        }

        if (result instanceof FillResponse) {
            if (sDebug) {
                Slog.d(TAG, "setAuthenticationResultLocked(): received FillResponse from"
                        + " authentication flow");
            }
            logAuthenticationStatusLocked(requestId, MetricsEvent.AUTOFILL_AUTHENTICATED);
            mPresentationStatsEventLogger.maybeSetAuthenticationResult(
                AUTHENTICATION_RESULT_SUCCESS);
            replaceResponseLocked(authenticatedResponse, (FillResponse) result, newClientState);
        } else if (result instanceof GetCredentialResponse) {
            if (sDebug) {
                Slog.d(TAG, "Received GetCredentialResponse from authentication flow");
            }
            if (Flags.autofillCredmanDevIntegration()) {
                GetCredentialResponse response = (GetCredentialResponse) result;
                if (dataset != null && dataset.getFieldIds().size() == 1) {
                    AutofillId autofillId = dataset.getFieldIds().get(0);
                    if (sDebug) {
                        Slog.d(TAG, "Received GetCredentialResponse from authentication flow,"
                                + "for autofillId: " + autofillId);
                    }
                    sendCredentialManagerResponseToApp(response,
                            /*exception=*/ null, autofillId);
                }
            } else if (Flags.autofillCredmanIntegration()) {
                Dataset datasetFromCredentialResponse = getDatasetFromCredentialResponse(
                        (GetCredentialResponse) result);
                if (datasetFromCredentialResponse != null) {
                    autoFill(requestId, datasetIdx, datasetFromCredentialResponse,
                            false, UI_TYPE_UNKNOWN);
                }
            }
        } else if (result instanceof Dataset) {
            if (sDebug) {
                Slog.d(TAG, "setAuthenticationResultLocked(): received Dataset from"
                        + " authentication flow");
            }
            if (datasetIdx != AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED) {
                logAuthenticationStatusLocked(requestId,
                        MetricsEvent.AUTOFILL_DATASET_AUTHENTICATED);
                mPresentationStatsEventLogger.maybeSetAuthenticationResult(
                    AUTHENTICATION_RESULT_SUCCESS);
                if (newClientState != null) {
                    if (sDebug) Slog.d(TAG,  "Updating client state from auth dataset");
                    mClientState = newClientState;
                }
                Dataset datasetFromResult = getEffectiveDatasetForAuthentication((Dataset) result);
                final Dataset oldDataset = authenticatedResponse.getDatasets().get(datasetIdx);
                if (!isAuthResultDatasetEphemeral(oldDataset, data)) {
                    authenticatedResponse.getDatasets().set(datasetIdx, datasetFromResult);
                }
                autoFill(requestId, datasetIdx, datasetFromResult, false, UI_TYPE_UNKNOWN);
            } else {
                Slog.w(TAG, "invalid index (" + datasetIdx + ") for authentication id "
                        + authenticationId);
                logAuthenticationStatusLocked(requestId,
                        MetricsEvent.AUTOFILL_INVALID_DATASET_AUTHENTICATION);
                mPresentationStatsEventLogger.maybeSetAuthenticationResult(
                    AUTHENTICATION_RESULT_FAILURE);
            }
        } else {
            if (result != null) {
                Slog.w(TAG, "service returned invalid auth type: " + result);
            }
            logAuthenticationStatusLocked(requestId,
                    MetricsEvent.AUTOFILL_INVALID_AUTHENTICATION);
            mPresentationStatsEventLogger.maybeSetAuthenticationResult(
                AUTHENTICATION_RESULT_FAILURE);
            processNullResponseLocked(requestId, 0);
        }
    }

    private static boolean isSecondaryProviderRequestId(int requestId) {
        return requestId % 2 == 1;
    }

    private Dataset getDatasetFromCredentialResponse(GetCredentialResponse result) {
        if (result == null) {
            return null;
        }
        Bundle bundle = result.getCredential().getData();
        if (bundle == null) {
            return null;
        }
        return bundle.getParcelable(AutofillManager.EXTRA_AUTHENTICATION_RESULT, Dataset.class);
    }

    Dataset getEffectiveDatasetForAuthentication(Dataset authenticatedDataset) {
        FillResponse response = new FillResponse.Builder().addDataset(authenticatedDataset).build();
        response = getEffectiveFillResponse(response);
        if (DBG) {
            Slog.d(TAG, "DBG: authenticated effective response: " + response);
        }
        if (response == null || response.getDatasets().size() == 0) {
            Log.wtf(TAG, "No datasets in fill response on authentication. response = "
                    + (response == null ? "null" : response.toString()));
            return authenticatedDataset;
        }
        List<Dataset> datasets = response.getDatasets();
        Dataset result = response.getDatasets().get(0);
        if (datasets.size() > 1) {
            Dataset.Builder builder = new Dataset.Builder();
            for (Dataset dataset : datasets) {
                if (!dataset.getFieldIds().isEmpty()) {
                    for (int i = 0; i < dataset.getFieldIds().size(); i++) {
                        builder.setField(dataset.getFieldIds().get(i),
                                new Field.Builder().setValue(dataset.getFieldValues().get(i))
                                        .build());
                    }
                }
            }
            result = builder.setId(authenticatedDataset.getId()).build();
        }

        if (DBG) {
            Slog.d(TAG, "DBG: authenticated effective dataset after auth: " + result);
        }
        return result;
    }

    /**
     * Returns whether the dataset returned from the authentication result is ephemeral or not.
     * See {@link AutofillManager#EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET} for more
     * information.
     */
    private static boolean isAuthResultDatasetEphemeral(@Nullable Dataset oldDataset,
            @NonNull Bundle authResultData) {
        if (authResultData.containsKey(
                AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET)) {
            return authResultData.getBoolean(
                    AutofillManager.EXTRA_AUTHENTICATION_RESULT_EPHEMERAL_DATASET);
        }
        return isPinnedDataset(oldDataset);
    }

    /**
     * A dataset can potentially have multiple fields, and it's possible that some of the fields'
     * has inline presentation and some don't. It's also possible that some of the fields'
     * inline presentation is pinned and some isn't. So the concept of whether a dataset is
     * pinned or not is ill-defined. Here we say a dataset is pinned if any of the field has a
     * pinned inline presentation in the dataset. It's not ideal but hopefully it is sufficient
     * for most of the cases.
     */
    private static boolean isPinnedDataset(@Nullable Dataset dataset) {
        if (dataset != null && dataset.getFieldIds() != null) {
            final int numOfFields = dataset.getFieldIds().size();
            for (int i = 0; i < numOfFields; i++) {
                final InlinePresentation inlinePresentation = dataset.getFieldInlinePresentation(i);
                if (inlinePresentation != null && inlinePresentation.isPinned()) {
                    return true;
                }
            }
        }
        return false;
    }

    @GuardedBy("mLock")
    void setAuthenticationResultForAugmentedAutofillLocked(Bundle data, int authId) {
        final Dataset dataset = (data == null) ? null :
                data.getParcelable(AutofillManager.EXTRA_AUTHENTICATION_RESULT, android.service.autofill.Dataset.class);
        if (sDebug) {
            Slog.d(TAG, "Auth result for augmented autofill: sessionId=" + id
                    + ", authId=" + authId + ", dataset=" + dataset);
        }
        final AutofillId fieldId = (dataset != null && dataset.getFieldIds().size() == 1)
                ? dataset.getFieldIds().get(0) : null;
        final AutofillValue value = (dataset != null && dataset.getFieldValues().size() == 1)
                ? dataset.getFieldValues().get(0) : null;
        final ClipData content = (dataset != null) ? dataset.getFieldContent() : null;
        if (fieldId == null || (value == null && content == null)) {
            if (sDebug) {
                Slog.d(TAG, "Rejecting empty/invalid auth result");
            }
            mService.resetLastAugmentedAutofillResponse();
            removeFromServiceLocked();
            return;
        }

        // Get a handle to the RemoteAugmentedAutofillService. In
        // AutofillManagerServiceImpl.updateRemoteAugmentedAutofillService() we invalidate sessions
        // whenever the service changes, so there should never be a case when we get here and the
        // remote service instance is not present or different.
        final RemoteAugmentedAutofillService remoteAugmentedAutofillService =
                mService.getRemoteAugmentedAutofillServiceIfCreatedLocked();
        if (remoteAugmentedAutofillService == null) {
            Slog.e(TAG, "Can't fill after auth: RemoteAugmentedAutofillService is null");
            mService.resetLastAugmentedAutofillResponse();
            removeFromServiceLocked();
            return;
        }

        // Update state to ensure that after filling the field here we don't end up firing another
        // autofill request that will end up showing the same suggestions to the user again. When
        // the auth activity came up, the field for which the suggestions were shown lost focus and
        // mCurrentViewId was cleared. We need to set mCurrentViewId back to the id of the field
        // that we are filling.
        fieldId.setSessionId(id);
        mCurrentViewId = fieldId;

        // Notify the Augmented Autofill provider of the dataset that was selected.
        final Bundle clientState = data.getBundle(AutofillManager.EXTRA_CLIENT_STATE);
        mService.logAugmentedAutofillSelected(id, dataset.getId(), clientState);

        // For any content URIs, grant URI permissions to the target app before filling.
        if (content != null) {
            final AutofillUriGrantsManager autofillUgm =
                    remoteAugmentedAutofillService.getAutofillUriGrantsManager();
            autofillUgm.grantUriPermissions(mComponentName, mActivityToken, userId, content);
        }

        // Fill the value into the field.
        if (sDebug) {
            Slog.d(TAG, "Filling after auth: fieldId=" + fieldId + ", value=" + value
                    + ", content=" + content);
        }
        try {
            if (content != null) {
                mClient.autofillContent(id, fieldId, content);
            } else {
                mClient.autofill(id, dataset.getFieldIds(), dataset.getFieldValues(), true);
            }
        } catch (RemoteException e) {
            Slog.w(TAG, "Error filling after auth: fieldId=" + fieldId + ", value=" + value
                    + ", content=" + content, e);
        }

        // Clear the suggestions since the user already accepted one of them.
        mInlineSessionController.setInlineFillUiLocked(InlineFillUi.emptyUi(fieldId));
    }

    @GuardedBy("mLock")
    void setHasCallbackLocked(boolean hasIt) {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#setHasCallbackLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        mHasCallback = hasIt;
    }

    @GuardedBy("mLock")
    @Nullable
    private FillResponse getLastResponseLocked(@Nullable String logPrefixFmt) {
        final String logPrefix = sDebug && logPrefixFmt != null
                ? String.format(logPrefixFmt, this.id)
                : null;
        if (mContexts == null) {
            if (logPrefix != null) Slog.d(TAG, logPrefix + ": no contexts");
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

    @GuardedBy("mLock")
    @Nullable
    private SaveInfo getSaveInfoLocked() {
        final FillResponse response = getLastResponseLocked(null);
        return response == null ? null : response.getSaveInfo();
    }

    @GuardedBy("mLock")
    int getSaveInfoFlagsLocked() {
        final SaveInfo saveInfo = getSaveInfoLocked();
        return saveInfo == null ? 0 : saveInfo.getFlags();
    }

    /**
     * Generates a {@link android.service.autofill.FillEventHistory.Event#TYPE_CONTEXT_COMMITTED}
     * when necessary.
     */
    public void logContextCommitted() {
        if (sVerbose) {
            Slog.v(TAG, "logContextCommitted (" + id + "): commit_reason:" + COMMIT_REASON_UNKNOWN
                    + " no_save_reason:" + Event.NO_SAVE_UI_REASON_NONE);
        }
        mHandler.sendMessage(obtainMessage(Session::handleLogContextCommitted, this,
                Event.NO_SAVE_UI_REASON_NONE,
                COMMIT_REASON_UNKNOWN));
        logAllEvents(COMMIT_REASON_UNKNOWN);
    }

    /**
     * Generates a {@link android.service.autofill.FillEventHistory.Event#TYPE_CONTEXT_COMMITTED}
     * when necessary. Note that it could be called before save UI is shown and the session is
     * committed.
     *
     * @param saveDialogNotShowReason The reason why a save dialog was not shown.
     * @param commitReason The reason why context is committed.
     */

    @GuardedBy("mLock")
    public void logContextCommittedLocked(@NoSaveReason int saveDialogNotShowReason,
            @AutofillCommitReason int commitReason) {
        if (sVerbose) {
            Slog.v(TAG, "logContextCommittedLocked (" + id + "): commit_reason:" + commitReason
                    + " no_save_reason:" + saveDialogNotShowReason);
        }
        mHandler.sendMessage(obtainMessage(Session::handleLogContextCommitted, this,
                saveDialogNotShowReason, commitReason));

        mSessionCommittedEventLogger.maybeSetCommitReason(commitReason);
        mSessionCommittedEventLogger.maybeSetRequestCount(mRequestCount);
        mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_NONE);
    }

    private void handleLogContextCommitted(@NoSaveReason int saveDialogNotShowReason,
            @AutofillCommitReason int commitReason) {
        final FillResponse lastResponse;
        synchronized (mLock) {
            lastResponse = getLastResponseLocked("logContextCommited(%s)");
        }

        if (lastResponse == null) {
            Slog.w(TAG, "handleLogContextCommitted(): last response is null");
            return;
        }

        // Merge UserData if necessary.
        // Fields in packageUserData will override corresponding fields in genericUserData.
        final UserData genericUserData = mService.getUserData();
        final UserData packageUserData = lastResponse.getUserData();
        final FieldClassificationUserData userData;
        if (packageUserData == null && genericUserData == null) {
            userData = null;
        } else if (packageUserData != null && genericUserData != null) {
            userData = new CompositeUserData(genericUserData, packageUserData);
        } else if (packageUserData != null) {
            userData = packageUserData;
        } else {
            userData = mService.getUserData();
        }

        final FieldClassificationStrategy fcStrategy = mService.getFieldClassificationStrategy();

        // Sets field classification scores
        if (userData != null && fcStrategy != null) {
            logFieldClassificationScore(fcStrategy, userData, saveDialogNotShowReason,
                    commitReason);
        } else {
            logContextCommitted(null, null, saveDialogNotShowReason, commitReason);
        }
    }

    private void logContextCommitted(@Nullable ArrayList<AutofillId> detectedFieldIds,
            @Nullable ArrayList<FieldClassification> detectedFieldClassifications,
            @NoSaveReason int saveDialogNotShowReason,
            @AutofillCommitReason int commitReason) {
        synchronized (mLock) {
            logContextCommittedLocked(detectedFieldIds, detectedFieldClassifications,
                    saveDialogNotShowReason, commitReason);
        }
    }

    @GuardedBy("mLock")
    private void logContextCommittedLocked(@Nullable ArrayList<AutofillId> detectedFieldIds,
            @Nullable ArrayList<FieldClassification> detectedFieldClassifications,
            @NoSaveReason int saveDialogNotShowReason,
            @AutofillCommitReason int commitReason) {
        if (sVerbose) {
            Slog.v(TAG, "logContextCommittedLocked (" + id + "): commit_reason:" + commitReason
                    + " no_save_reason:" + saveDialogNotShowReason);
        }
        final FillResponse lastResponse = getLastResponseLocked("logContextCommited(%s)");
        if (lastResponse == null) return;

        mPresentationStatsEventLogger.maybeSetNoPresentationEventReason(
                PresentationStatsEventLogger.getNoPresentationEventReason(commitReason));
        mPresentationStatsEventLogger.logAndEndEvent();

        final int flags = lastResponse.getFlags();
        if ((flags & FillResponse.FLAG_TRACK_CONTEXT_COMMITED) == 0) {
            if (sVerbose) Slog.v(TAG, "logContextCommittedLocked(): ignored by flags " + flags);
            return;
        }

        ArraySet<String> ignoredDatasets = null;
        ArrayList<AutofillId> changedFieldIds = null;
        ArrayList<String> changedDatasetIds = null;
        ArrayMap<AutofillId, ArraySet<String>> manuallyFilledIds = null;

        boolean hasAtLeastOneDataset = false;
        final int responseCount = mResponses.size();
        for (int i = 0; i < responseCount; i++) {
            final FillResponse response = mResponses.valueAt(i);
            final List<Dataset> datasets = response.getDatasets();
            if (datasets == null || datasets.isEmpty()) {
                if (sVerbose) Slog.v(TAG, "logContextCommitted() no datasets at " + i);
            } else {
                for (int j = 0; j < datasets.size(); j++) {
                    final Dataset dataset = datasets.get(j);
                    final String datasetId = dataset.getId();
                    if (datasetId == null) {
                        if (sVerbose) {
                            Slog.v(TAG, "logContextCommitted() skipping idless dataset " + dataset);
                        }
                    } else {
                        hasAtLeastOneDataset = true;
                        if (mSelectedDatasetIds == null
                                || !mSelectedDatasetIds.contains(datasetId)) {
                            if (sVerbose) Slog.v(TAG, "adding ignored dataset " + datasetId);
                            if (ignoredDatasets == null) {
                                ignoredDatasets = new ArraySet<>();
                            }
                            ignoredDatasets.add(datasetId);
                        }
                    }
                }
            }
        }

        for (int i = 0; i < mViewStates.size(); i++) {
            final ViewState viewState = mViewStates.valueAt(i);
            final int state = viewState.getState();

            // When value changed, we need to log if it was:
            // - autofilled -> changedDatasetIds
            // - not autofilled but matches a dataset value -> manuallyFilledIds
            if ((state & ViewState.STATE_CHANGED) != 0) {
                // Check if autofilled value was changed
                if ((state & ViewState.STATE_AUTOFILLED_ONCE) != 0) {
                    final String datasetId = viewState.getDatasetId();
                    if (datasetId == null) {
                        // Validation check - should never happen.
                        Slog.w(TAG, "logContextCommitted(): no dataset id on " + viewState);
                        continue;
                    }

                    // Must first check if final changed value is not the same as value sent by
                    // service.
                    final AutofillValue autofilledValue = viewState.getAutofilledValue();
                    final AutofillValue currentValue = viewState.getCurrentValue();
                    if (autofilledValue != null && autofilledValue.equals(currentValue)) {
                        if (sDebug) {
                            Slog.d(TAG, "logContextCommitted(): ignoring changed " + viewState
                                    + " because it has same value that was autofilled");
                        }
                        continue;
                    }

                    if (sDebug) {
                        Slog.d(TAG, "logContextCommitted() found changed state: " + viewState);
                    }
                    if (changedFieldIds == null) {
                        changedFieldIds = new ArrayList<>();
                        changedDatasetIds = new ArrayList<>();
                    }
                    changedFieldIds.add(viewState.id);
                    changedDatasetIds.add(datasetId);
                } else {
                    final AutofillValue currentValue = viewState.getCurrentValue();
                    if (currentValue == null) {
                        if (sDebug) {
                            Slog.d(TAG, "logContextCommitted(): skipping view without current "
                                    + "value ( " + viewState + ")");
                        }
                        continue;
                    }

                    // Check if value match a dataset.
                    if (hasAtLeastOneDataset) {
                        for (int j = 0; j < responseCount; j++) {
                            final FillResponse response = mResponses.valueAt(j);
                            final List<Dataset> datasets = response.getDatasets();
                            if (datasets == null || datasets.isEmpty()) {
                                if (sVerbose) {
                                    Slog.v(TAG,  "logContextCommitted() no datasets at " + j);
                                }
                            } else {
                                for (int k = 0; k < datasets.size(); k++) {
                                    final Dataset dataset = datasets.get(k);
                                    final String datasetId = dataset.getId();
                                    if (datasetId == null) {
                                        if (sVerbose) {
                                            Slog.v(TAG, "logContextCommitted() skipping idless "
                                                    + "dataset " + dataset);
                                        }
                                    } else {
                                        final ArrayList<AutofillValue> values =
                                                dataset.getFieldValues();
                                        for (int l = 0; l < values.size(); l++) {
                                            final AutofillValue candidate = values.get(l);
                                            if (currentValue.equals(candidate)) {
                                                if (sDebug) {
                                                    Slog.d(TAG, "field " + viewState.id + " was "
                                                            + "manually filled with value set by "
                                                            + "dataset " + datasetId);
                                                }
                                                if (manuallyFilledIds == null) {
                                                    manuallyFilledIds = new ArrayMap<>();
                                                }
                                                ArraySet<String> datasetIds =
                                                        manuallyFilledIds.get(viewState.id);
                                                if (datasetIds == null) {
                                                    datasetIds = new ArraySet<>(1);
                                                    manuallyFilledIds.put(viewState.id, datasetIds);
                                                }
                                                datasetIds.add(datasetId);
                                            }
                                        } // for l
                                        if (mSelectedDatasetIds == null
                                                || !mSelectedDatasetIds.contains(datasetId)) {
                                            if (sVerbose) {
                                                Slog.v(TAG, "adding ignored dataset " + datasetId);
                                            }
                                            if (ignoredDatasets == null) {
                                                ignoredDatasets = new ArraySet<>();
                                            }
                                            ignoredDatasets.add(datasetId);
                                        } // if
                                    } // if
                                } // for k
                            } // else
                        } // for j
                    }
                } // else
            } // else
        }

        ArrayList<AutofillId> manuallyFilledFieldIds = null;
        ArrayList<ArrayList<String>> manuallyFilledDatasetIds = null;

        // Must "flatten" the map to the parcelable collection primitives
        if (manuallyFilledIds != null) {
            final int size = manuallyFilledIds.size();
            manuallyFilledFieldIds = new ArrayList<>(size);
            manuallyFilledDatasetIds = new ArrayList<>(size);
            for (int i = 0; i < size; i++) {
                final AutofillId fieldId = manuallyFilledIds.keyAt(i);
                final ArraySet<String> datasetIds = manuallyFilledIds.valueAt(i);
                manuallyFilledFieldIds.add(fieldId);
                manuallyFilledDatasetIds.add(new ArrayList<>(datasetIds));
            }
        }

        mService.logContextCommittedLocked(id, mClientState, mSelectedDatasetIds, ignoredDatasets,
                changedFieldIds, changedDatasetIds, manuallyFilledFieldIds,
                manuallyFilledDatasetIds, detectedFieldIds, detectedFieldClassifications,
                mComponentName, mCompatMode, saveDialogNotShowReason);
        mSessionCommittedEventLogger.maybeSetCommitReason(commitReason);
        mSessionCommittedEventLogger.maybeSetRequestCount(mRequestCount);
        mSaveEventLogger.maybeSetSaveUiNotShownReason(saveDialogNotShowReason);
    }

    /**
     * Adds the matches to {@code detectedFieldsIds} and {@code detectedFieldClassifications} for
     * {@code fieldId} based on its {@code currentValue} and {@code userData}.
     */
    private void logFieldClassificationScore(@NonNull FieldClassificationStrategy fcStrategy,
            @NonNull FieldClassificationUserData userData,
            @NoSaveReason int saveDialogNotShowReason,
            @AutofillCommitReason int commitReason) {

        final String[] userValues = userData.getValues();
        final String[] categoryIds = userData.getCategoryIds();

        final String defaultAlgorithm = userData.getFieldClassificationAlgorithm();
        final Bundle defaultArgs = userData.getDefaultFieldClassificationArgs();

        final ArrayMap<String, String> algorithms = userData.getFieldClassificationAlgorithms();
        final ArrayMap<String, Bundle> args = userData.getFieldClassificationArgs();

        // Validation check
        if (userValues == null || categoryIds == null || userValues.length != categoryIds.length) {
            final int valuesLength = userValues == null ? -1 : userValues.length;
            final int idsLength = categoryIds == null ? -1 : categoryIds.length;
            Slog.w(TAG, "setScores(): user data mismatch: values.length = "
                    + valuesLength + ", ids.length = " + idsLength);
            return;
        }

        final int maxFieldsSize = UserData.getMaxFieldClassificationIdsSize();

        final ArrayList<AutofillId> detectedFieldIds = new ArrayList<>(maxFieldsSize);
        final ArrayList<FieldClassification> detectedFieldClassifications = new ArrayList<>(
                maxFieldsSize);

        final Collection<ViewState> viewStates;
        synchronized (mLock) {
            viewStates = mViewStates.values();
        }

        final int viewsSize = viewStates.size();

        // First, we get all scores.
        final AutofillId[] autofillIds = new AutofillId[viewsSize];
        final ArrayList<AutofillValue> currentValues = new ArrayList<>(viewsSize);
        int k = 0;
        for (ViewState viewState : viewStates) {
            currentValues.add(viewState.getCurrentValue());
            autofillIds[k++] = viewState.id;
        }

        // Then use the results, asynchronously
        final RemoteCallback callback = new RemoteCallback(
                new LogFieldClassificationScoreOnResultListener(
                        this,
                        saveDialogNotShowReason,
                        commitReason,
                        viewsSize,
                        autofillIds,
                        userValues,
                        categoryIds,
                        detectedFieldIds,
                        detectedFieldClassifications));

        fcStrategy.calculateScores(callback, currentValues, userValues, categoryIds,
                defaultAlgorithm, defaultArgs, algorithms, args);
    }

    void handleLogFieldClassificationScore(@Nullable Bundle result, int saveDialogNotShowReason,
            int commitReason, int viewsSize, AutofillId[] autofillIds, String[] userValues,
            String[] categoryIds, ArrayList<AutofillId> detectedFieldIds,
            ArrayList<FieldClassification> detectedFieldClassifications) {
        if (result == null) {
            if (sDebug) Slog.d(TAG, "setFieldClassificationScore(): no results");
            logContextCommitted(null, null, saveDialogNotShowReason, commitReason);
            return;
        }
        final Scores scores = result.getParcelable(EXTRA_SCORES,
                android.service.autofill.AutofillFieldClassificationService.Scores.class);
        if (scores == null) {
            Slog.w(TAG, "No field classification score on " + result);
            return;
        }
        int i = 0, j = 0;
        try {
            // Iteract over all autofill fields first
            for (i = 0; i < viewsSize; i++) {
                final AutofillId autofillId = autofillIds[i];

                // Search the best scores for each category (as some categories could have
                // multiple user values
                ArrayMap<String, Float> scoresByField = null;
                for (j = 0; j < userValues.length; j++) {
                    final String categoryId = categoryIds[j];
                    final float score = scores.scores[i][j];
                    if (score > 0) {
                        if (scoresByField == null) {
                            scoresByField = new ArrayMap<>(userValues.length);
                        }
                        final Float currentScore = scoresByField.get(categoryId);
                        if (currentScore != null && currentScore > score) {
                            if (sVerbose) {
                                Slog.v(TAG, "skipping score " + score
                                        + " because it's less than " + currentScore);
                            }
                            continue;
                        }
                        if (sVerbose) {
                            Slog.v(TAG, "adding score " + score + " at index " + j + " and id "
                                    + autofillId);
                        }
                        scoresByField.put(categoryId, score);
                    } else if (sVerbose) {
                        Slog.v(TAG, "skipping score 0 at index " + j + " and id " + autofillId);
                    }
                }
                if (scoresByField == null) {
                    if (sVerbose) Slog.v(TAG, "no score for autofillId=" + autofillId);
                    continue;
                }

                // Then create the matches for that autofill id
                final ArrayList<Match> matches = new ArrayList<>(scoresByField.size());
                for (j = 0; j < scoresByField.size(); j++) {
                    final String fieldId = scoresByField.keyAt(j);
                    final float score = scoresByField.valueAt(j);
                    matches.add(new Match(fieldId, score));
                }
                detectedFieldIds.add(autofillId);
                detectedFieldClassifications.add(new FieldClassification(matches));
            } // for i
        } catch (ArrayIndexOutOfBoundsException e) {
            wtf(e, "Error accessing FC score at [%d, %d] (%s): %s", i, j, scores, e);
            return;
        }
        logContextCommitted(detectedFieldIds, detectedFieldClassifications,
                saveDialogNotShowReason, commitReason);
    }

    /**
     * Generates a {@link android.service.autofill.FillEventHistory.Event#TYPE_SAVE_SHOWN}
     * when necessary.
     *
     * <p>Note: It is necessary to call logContextCommitted() first before calling this method.
     */
    public void logSaveUiShown() {
        mHandler.sendMessage(obtainMessage(Session::logSaveShown, this));
    }

    /**
     * Shows the save UI, when session can be saved.
     *
     * @return {@link SaveResult} that contains the save ui display status information.
     */
    @GuardedBy("mLock")
    @NonNull
    public SaveResult showSaveLocked() {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#showSaveLocked() rejected - session: "
                    + id + " destroyed");
            mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_SESSION_DESTROYED);
            mSaveEventLogger.logAndEndEvent();
            return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ false,
                    Event.NO_SAVE_UI_REASON_NONE);
        }
        mSessionState = STATE_FINISHED;
        final FillResponse response = getLastResponseLocked("showSaveLocked(%s)");
        final SaveInfo saveInfo = response == null ? null : response.getSaveInfo();

        /*
         * Don't show save if the session has credman field
         */
        if (mSessionFlags.mScreenHasCredmanField) {
            if (sVerbose) {
                Slog.v(TAG, "Call to Session#showSaveLocked() rejected - "
                        + "there is credman field in screen");
            }
            return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ true,
                    Event.NO_SAVE_UI_REASON_NONE);
        }

        /*
         * The Save dialog is only shown if all conditions below are met:
         *
         * - saveInfo is not null.
         * - autofillValue of all required ids is not null.
         * - autofillValue of at least one id (required or optional) has changed.
         * - there is no Dataset in the last FillResponse whose values of all dataset fields matches
         *   the current values of all fields in the screen.
         * - server didn't ask to keep session alive
         */
        if (saveInfo == null) {
            if (sVerbose) Slog.v(TAG, "showSaveLocked(" + this.id + "): no saveInfo from service");
            mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_NO_SAVE_INFO);
            mSaveEventLogger.logAndEndEvent();
            return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ true,
                    Event.NO_SAVE_UI_REASON_NO_SAVE_INFO);
        }

        if ((saveInfo.getFlags() & SaveInfo.FLAG_DELAY_SAVE) != 0) {
            // TODO(b/113281366): log metrics
            if (sDebug) Slog.v(TAG, "showSaveLocked(" + this.id + "): service asked to delay save");
            mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_WITH_DELAY_SAVE_FLAG);
            mSaveEventLogger.logAndEndEvent();
            return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ false,
                    Event.NO_SAVE_UI_REASON_WITH_DELAY_SAVE_FLAG);
        }

        final ArrayMap<AutofillId, InternalSanitizer> sanitizers = createSanitizers(saveInfo);

        // Cache used to make sure changed fields do not belong to a dataset.
        final ArrayMap<AutofillId, AutofillValue> currentValues = new ArrayMap<>();
        // Savable (optional or required) ids that will be checked against the dataset ids.
        final ArraySet<AutofillId> savableIds = new ArraySet<>();

        final AutofillId[] requiredIds = saveInfo.getRequiredIds();
        boolean allRequiredAreNotEmpty = true;
        boolean atLeastOneChanged = false;
        // If an autofilled field is changed, we need to change isUpdate to true so the proper UI is
        // shown.
        boolean isUpdate = false;
        if (requiredIds != null) {
            for (int i = 0; i < requiredIds.length; i++) {
                final AutofillId id = requiredIds[i];
                if (id == null) {
                    Slog.w(TAG, "null autofill id on " + Arrays.toString(requiredIds));
                    continue;
                }
                savableIds.add(id);
                final ViewState viewState = mViewStates.get(id);
                if (viewState == null) {
                    Slog.w(TAG, "showSaveLocked(): no ViewState for required " + id);
                    allRequiredAreNotEmpty = false;
                    break;
                }

                AutofillValue value = viewState.getCurrentValue();
                if (value == null || value.isEmpty()) {
                    // Some apps clear the form before navigating to other activities.
                    // If current value is empty, consider fall back to last cached
                    // non-empty result first.
                    final AutofillValue candidateSaveValue =
                            viewState.getCandidateSaveValue();
                    if (candidateSaveValue != null && !candidateSaveValue.isEmpty()) {
                        if (sVerbose) {
                            Slog.v(TAG, "current value is empty, using cached last non-empty "
                                    + "value instead");
                        }
                        value = candidateSaveValue;
                    } else {
                        // If candidate save value is also empty, consider falling back to initial
                        // value in context.
                        final AutofillValue initialValue = getValueFromContextsLocked(id);
                        if (initialValue != null) {
                            if (sDebug) {
                                Slog.d(TAG, "Value of required field " + id + " didn't change; "
                                        + "using initial value (" + initialValue + ") instead");
                            }
                            value = initialValue;
                        } else {
                            if (sDebug) {
                                Slog.d(TAG, "empty value for required " + id);
                            }
                            allRequiredAreNotEmpty = false;
                            break;
                        }
                    }
                }

                value = getSanitizedValue(sanitizers, id, value);
                if (value == null) {
                    if (sDebug) {
                        Slog.d(TAG, "value of required field " + id + " failed sanitization");
                    }
                    allRequiredAreNotEmpty = false;
                    break;
                }
                viewState.setSanitizedValue(value);
                currentValues.put(id, value);
                final AutofillValue filledValue = viewState.getAutofilledValue();

                if (!value.equals(filledValue)) {
                    boolean changed = true;
                    if (filledValue == null) {
                        // Dataset was not autofilled, make sure initial value didn't change.
                        final AutofillValue initialValue = getValueFromContextsLocked(id);
                        if (initialValue != null && initialValue.equals(value)) {
                            if (sDebug) {
                                Slog.d(TAG, "id " + id + " is part of dataset but initial value "
                                        + "didn't change: " + value);
                            }
                            changed = false;
                        } else {
                            mSaveEventLogger.maybeSetIsNewField(true);
                        }
                    } else {
                        isUpdate = true;
                    }
                    if (changed) {
                        if (sDebug) {
                            Slog.d(TAG, "found a change on required " + id + ": " + filledValue
                                    + " => " + value);
                        }
                        atLeastOneChanged = true;
                    }
                }
            }
        }

        final AutofillId[] optionalIds = saveInfo.getOptionalIds();
        if (sVerbose) {
            Slog.v(TAG, "allRequiredAreNotEmpty: " + allRequiredAreNotEmpty + " hasOptional: "
                    + (optionalIds != null));
        }
        int saveDialogNotShowReason;
        if (!allRequiredAreNotEmpty) {
            saveDialogNotShowReason = Event.NO_SAVE_UI_REASON_HAS_EMPTY_REQUIRED;

            mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_HAS_EMPTY_REQUIRED);
            mSaveEventLogger.logAndEndEvent();
        } else {
            // Must look up all optional ids in 2 scenarios:
            // - if no required id changed but an optional id did, it should trigger save / update
            // - if at least one required id changed but it was not part of a filled dataset, we
            //   need to check if an optional id is part of a filled datased (in which case we show
            //   Update instead of Save)
            if (optionalIds!= null && (!atLeastOneChanged || !isUpdate)) {
                // No change on required ids yet, look for changes on optional ids.
                for (int i = 0; i < optionalIds.length; i++) {
                    final AutofillId id = optionalIds[i];
                    savableIds.add(id);
                    final ViewState viewState = mViewStates.get(id);
                    if (viewState == null) {
                        Slog.w(TAG, "no ViewState for optional " + id);
                        continue;
                    }
                    if ((viewState.getState() & ViewState.STATE_CHANGED) != 0) {
                        AutofillValue currentValue = viewState.getCurrentValue();
                        if (currentValue == null || currentValue.isEmpty()) {
                            // Some apps clear the form before navigating to other activities.
                            // If current value is empty, consider fall back to last cached
                            // non-empty result instead.
                            final AutofillValue candidateSaveValue =
                                    viewState.getCandidateSaveValue();
                            if (candidateSaveValue != null && !candidateSaveValue.isEmpty()) {
                                if (sVerbose) {
                                    Slog.v(TAG, "current value is empty, using cached last "
                                            + "non-empty value instead");
                                }
                                currentValue = candidateSaveValue;
                            }
                        }
                        final AutofillValue value = getSanitizedValue(sanitizers, id, currentValue);
                        if (value == null) {
                            if (sDebug) {
                                Slog.d(TAG, "value of opt. field " + id + " failed sanitization");
                            }
                            continue;
                        }

                        currentValues.put(id, value);
                        final AutofillValue filledValue = viewState.getAutofilledValue();
                        if (value != null && !value.equals(filledValue)) {
                            if (sDebug) {
                                Slog.d(TAG, "found a change on optional " + id + ": " + filledValue
                                        + " => " + value);
                            }
                            if (filledValue != null) {
                                isUpdate = true;
                            } else {
                                mSaveEventLogger.maybeSetIsNewField(true);
                            }
                            atLeastOneChanged = true;
                        }
                    } else  {
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
            if (!atLeastOneChanged) {
                saveDialogNotShowReason = Event.NO_SAVE_UI_REASON_NO_VALUE_CHANGED;
                mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_NO_VALUE_CHANGED);
                mSaveEventLogger.logAndEndEvent();
            } else {
                if (sDebug) {
                    Slog.d(TAG, "at least one field changed, validate fields for save UI");
                }
                final InternalValidator validator = saveInfo.getValidator();
                if (validator != null) {
                    final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_SAVE_VALIDATION);
                    boolean isValid;
                    try {
                        isValid = validator.isValid(this);
                        if (sDebug) Slog.d(TAG, validator + " returned " + isValid);
                        log.setType(isValid
                                ? MetricsEvent.TYPE_SUCCESS
                                : MetricsEvent.TYPE_DISMISS);
                    } catch (Exception e) {
                        Slog.e(TAG, "Not showing save UI because validation failed:", e);
                        log.setType(MetricsEvent.TYPE_FAILURE);
                        mMetricsLogger.write(log);
                        mSaveEventLogger.maybeSetSaveUiNotShownReason(
                            NO_SAVE_REASON_FIELD_VALIDATION_FAILED);
                        mSaveEventLogger.logAndEndEvent();
                        return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ true,
                                Event.NO_SAVE_UI_REASON_FIELD_VALIDATION_FAILED);
                    }

                    mMetricsLogger.write(log);
                    if (!isValid) {
                        Slog.i(TAG, "not showing save UI because fields failed validation");
                        mSaveEventLogger.maybeSetSaveUiNotShownReason(
                            NO_SAVE_REASON_FIELD_VALIDATION_FAILED);
                        mSaveEventLogger.logAndEndEvent();
                        return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ true,
                                Event.NO_SAVE_UI_REASON_FIELD_VALIDATION_FAILED);
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
                                    + ": " + dataset + "; savableIds=" + savableIds);
                        }
                        savable_ids_loop: for (int j = 0; j < savableIds.size(); j++) {
                            final AutofillId id = savableIds.valueAt(j);
                            final AutofillValue currentValue = currentValues.get(id);
                            if (currentValue == null) {
                                if (sDebug) {
                                    Slog.d(TAG, "dataset has value for field that is null: " + id);
                                }
                                continue savable_ids_loop;
                            }
                            final AutofillValue datasetValue = datasetValues.get(id);
                            if (!currentValue.equals(datasetValue)) {
                                if (sDebug) {
                                    Slog.d(TAG, "found a dataset change on id " + id + ": from "
                                            + datasetValue + " to " + currentValue);
                                }
                                continue datasets_loop;
                            }
                            if (sVerbose) Slog.v(TAG, "no dataset changes for id " + id);
                        }
                        if (sDebug) {
                            Slog.d(TAG, "ignoring Save UI because all fields match contents of "
                                    + "dataset #" + i + ": " + dataset);
                        }
                        mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_DATASET_MATCH);
                        mSaveEventLogger.logAndEndEvent();
                        return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ true,
                                Event.NO_SAVE_UI_REASON_DATASET_MATCH);
                    }
                }

                final IAutoFillManagerClient client = getClient();
                mPendingSaveUi = new PendingUi(new Binder(), id, client);

                final CharSequence serviceLabel;
                final Drawable serviceIcon;
                synchronized (mLock) {
                    serviceIcon = getServiceIcon(response);
                    serviceLabel = getServiceLabel(response);
                }
                if (serviceLabel == null || serviceIcon == null) {
                    wtf(null, "showSaveLocked(): no service label or icon");
                    mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_NONE);
                    mSaveEventLogger.logAndEndEvent();
                    return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ true,
                            Event.NO_SAVE_UI_REASON_NONE);
                }
                getUiForShowing().showSaveUi(serviceLabel, serviceIcon,
                        mService.getServicePackageName(), saveInfo, this,
                        mComponentName, this, mContext,  mPendingSaveUi, isUpdate, mCompatMode,
                        response.getShowSaveDialogIcon(), mSaveEventLogger);
                if (client != null) {
                    try {
                        client.setSaveUiState(id, true);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "Error notifying client to set save UI state to shown: " + e);
                    }
                }
                mSessionFlags.mShowingSaveUi = true;
                if (sDebug) {
                    Slog.d(TAG, "Good news, everyone! All checks passed, show save UI for "
                            + id + "!");
                }
                return new SaveResult(/* logSaveShown= */ true, /* removeSession= */ false,
                        Event.NO_SAVE_UI_REASON_NONE);
            }
        }
        // Nothing changed...
        if (sDebug) {
            Slog.d(TAG, "showSaveLocked(" + id +"): with no changes, comes no responsibilities."
                    + "allRequiredAreNotNull=" + allRequiredAreNotEmpty
                    + ", atLeastOneChanged=" + atLeastOneChanged);
        }
        return new SaveResult(/* logSaveShown= */ false, /* removeSession= */ true,
                saveDialogNotShowReason);
    }

    private void logSaveShown() {
        mService.logSaveShown(id, mClientState);
    }

    @Nullable
    private AutofillValue getSanitizedValue(
            @Nullable ArrayMap<AutofillId, InternalSanitizer> sanitizers,
            @NonNull AutofillId id,
            @Nullable AutofillValue value) {
        if (sanitizers == null || value == null) return value;

        final ViewState state = mViewStates.get(id);
        AutofillValue sanitized = state == null ? null : state.getSanitizedValue();
        if (sanitized == null) {
            final InternalSanitizer sanitizer = sanitizers.get(id);
            if (sanitizer == null) {
                return value;
            }

            sanitized = sanitizer.sanitize(value);
            if (sDebug) {
                Slog.d(TAG, "Value for " + id + "(" + value + ") sanitized to " + sanitized);
            }
            if (state != null) {
                state.setSanitizedValue(sanitized);
            }
        }
        return sanitized;
    }

    /**
     * Returns whether the session is currently showing the save UI
     */
    @GuardedBy("mLock")
    boolean isSaveUiShowingLocked() {
        return mSessionFlags.mShowingSaveUi;
    }

    /**
     * Gets the latest non-empty value for the given id in the autofill contexts.
     */
    @GuardedBy("mLock")
    @Nullable
    private ViewNode getViewNodeFromContextsLocked(@NonNull AutofillId autofillId) {
        final int numContexts = mContexts.size();
        for (int i = numContexts - 1; i >= 0; i--) {
            final FillContext context = mContexts.get(i);
            final ViewNode node = Helper.findViewNodeByAutofillId(context.getStructure(),
                    autofillId);
            if (node != null) {
                return node;
            }
        }
        return null;
    }

    /**
     * Gets the latest non-empty value for the given id in the autofill contexts.
     */
    @GuardedBy("mLock")
    @Nullable
    private AutofillValue getValueFromContextsLocked(@NonNull AutofillId autofillId) {
        final int numContexts = mContexts.size();
        for (int i = numContexts - 1; i >= 0; i--) {
            final FillContext context = mContexts.get(i);
            final ViewNode node = Helper.findViewNodeByAutofillId(context.getStructure(),
                    autofillId);
            if (node != null) {
                final AutofillValue value = node.getAutofillValue();
                if (sDebug) {
                    Slog.d(TAG, "getValueFromContexts(" + this.id + "/" + autofillId + ") at "
                            + i + ": " + value);
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
    @GuardedBy("mLock")
    @Nullable
    private CharSequence[] getAutofillOptionsFromContextsLocked(@NonNull AutofillId autofillId) {
        final int numContexts = mContexts.size();
        for (int i = numContexts - 1; i >= 0; i--) {
            final FillContext context = mContexts.get(i);
            final ViewNode node = Helper.findViewNodeByAutofillId(context.getStructure(),
                    autofillId);
            if (node != null && node.getAutofillOptions() != null) {
                return node.getAutofillOptions();
            }
        }
        return null;
    }

    /**
     * Update the {@link AutofillValue values} of the {@link AssistStructure} before sending it to
     * the service on save().
     */
    private void updateValuesForSaveLocked() {
        final ArrayMap<AutofillId, InternalSanitizer> sanitizers =
                createSanitizers(getSaveInfoLocked());

        final int numContexts = mContexts.size();
        for (int contextNum = 0; contextNum < numContexts; contextNum++) {
            final FillContext context = mContexts.get(contextNum);

            final ViewNode[] nodes =
                context.findViewNodesByAutofillIds(getIdsOfAllViewStatesLocked());

            if (sVerbose) Slog.v(TAG, "updateValuesForSaveLocked(): updating " + context);

            for (int viewStateNum = 0; viewStateNum < mViewStates.size(); viewStateNum++) {
                final ViewState viewState = mViewStates.valueAt(viewStateNum);

                final AutofillId id = viewState.id;
                final AutofillValue value = viewState.getCurrentValue();
                if (value == null) {
                    if (sVerbose) Slog.v(TAG, "updateValuesForSaveLocked(): skipping " + id);
                    continue;
                }
                final ViewNode node = nodes[viewStateNum];
                if (node == null) {
                    Slog.w(TAG, "callSaveLocked(): did not find node with id " + id);
                    continue;
                }
                if (sVerbose) {
                    Slog.v(TAG, "updateValuesForSaveLocked(): updating " + id + " to " + value);
                }

                AutofillValue sanitizedValue = viewState.getSanitizedValue();

                if (sanitizedValue == null) {
                    // Field is optional and haven't been sanitized yet.
                    sanitizedValue = getSanitizedValue(sanitizers, id, value);
                }
                if (sanitizedValue != null) {
                    node.updateAutofillValue(sanitizedValue);
                } else if (sDebug) {
                    Slog.d(TAG, "updateValuesForSaveLocked(): not updating field " + id
                            + " because it failed sanitization");
                }
            }

            // Sanitize structure before it's sent to service.
            context.getStructure().sanitizeForParceling(false);

            if (sVerbose) {
                Slog.v(TAG, "updateValuesForSaveLocked(): dumping structure of " + context
                        + " before calling service.save()");
                context.getStructure().dump(false);
            }
        }
    }

    /**
     * Calls service when user requested save.
     */
    @GuardedBy("mLock")
    void callSaveLocked() {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#callSaveLocked() rejected - session: "
                    + id + " destroyed");
            mSaveEventLogger.maybeSetIsSaved(false);
            mSaveEventLogger.logAndEndEvent();
            return;
        }
        if (mRemoteFillService == null) {
            wtf(null, "callSaveLocked() called without a remote service. "
                    + "mForAugmentedAutofillOnly: %s", mSessionFlags.mAugmentedAutofillOnly);
            mSaveEventLogger.maybeSetIsSaved(false);
            mSaveEventLogger.logAndEndEvent();
            return;
        }

        if (sVerbose) Slog.v(TAG, "callSaveLocked(" + this.id + "): mViewStates=" + mViewStates);

        if (mContexts == null) {
            Slog.w(TAG, "callSaveLocked(): no contexts");
            mSaveEventLogger.maybeSetIsSaved(false);
            mSaveEventLogger.logAndEndEvent();
            return;
        }

        updateValuesForSaveLocked();

        // Remove pending fill requests as the session is finished.
        cancelCurrentRequestLocked();

        final ArrayList<FillContext> contexts = mergePreviousSessionLocked( /* forSave= */ true);

        FieldClassificationResponse fieldClassificationResponse =
                mClassificationState.mLastFieldClassificationResponse;
        if (mService.isPccClassificationEnabled()
                && fieldClassificationResponse != null
                && !fieldClassificationResponse.getClassifications().isEmpty()) {
            if (mClientState == null) {
                mClientState = new Bundle();
            }
            mClientState.putParcelableArrayList(EXTRA_KEY_DETECTIONS, new ArrayList<>(
                    fieldClassificationResponse.getClassifications()));
        }
        final SaveRequest saveRequest =
                new SaveRequest(contexts, mClientState, mSelectedDatasetIds);
        mRemoteFillService.onSaveRequest(saveRequest);
    }

    // TODO(b/113281366): rather than merge it here, it might be better to simply reuse the old
    // session instead of creating a new one. But we need to consider what would happen on corner
    // cases such as "Main Activity M -> activity A with username -> activity B with password"
    // If user follows the normal workflow, then session A would be merged with session B as
    // expected. But if when on Activity A the user taps back or somehow launches another activity,
    // session A could be merged with the wrong session.
    /**
     * Gets a list of contexts that includes not only this session's contexts but also the contexts
     * from previous sessions that were asked by the service to be delayed (if any).
     *
     * <p>As a side-effect:
     * <ul>
     *   <li>If the current {@link #mClientState} is {@code null}, sets it with the last non-
     *   {@code null} client state from previous sessions.
     *   <li>When {@code forSave} is {@code true}, calls {@link #updateValuesForSaveLocked()} in the
     *   previous sessions.
     * </ul>
     */
    @NonNull
    private ArrayList<FillContext> mergePreviousSessionLocked(boolean forSave) {
        final ArrayList<Session> previousSessions = mService.getPreviousSessionsLocked(this);
        final ArrayList<FillContext> contexts;
        if (previousSessions != null) {
            if (sDebug) {
                Slog.d(TAG, "mergeSessions(" + this.id + "): Merging the content of "
                        + previousSessions.size() + " sessions for task " + taskId);
            }
            contexts = new ArrayList<>();
            for (int i = 0; i < previousSessions.size(); i++) {
                final Session previousSession = previousSessions.get(i);
                final ArrayList<FillContext> previousContexts = previousSession.mContexts;
                if (previousContexts == null) {
                    Slog.w(TAG, "mergeSessions(" + this.id + "): Not merging null contexts from "
                            + previousSession.id);
                    continue;
                }
                if (forSave) {
                    previousSession.updateValuesForSaveLocked();
                }
                if (sDebug) {
                    Slog.d(TAG, "mergeSessions(" + this.id + "): adding " + previousContexts.size()
                            + " context from previous session #" + previousSession.id);
                }
                contexts.addAll(previousContexts);
                if (mClientState == null && previousSession.mClientState != null) {
                    if (sDebug) {
                        Slog.d(TAG, "mergeSessions(" + this.id + "): setting client state from "
                                + "previous session" + previousSession.id);
                    }
                    mClientState = previousSession.mClientState;
                }
            }
            contexts.addAll(mContexts);
        } else {
            // Dispatch a snapshot of the current contexts list since it may change
            // until the dispatch happens. The items in the list don't need to be cloned
            // since we don't hold on them anywhere else. The client state is not touched
            // by us, so no need to copy.
            contexts = new ArrayList<>(mContexts);
        }
        return contexts;
    }

    /**
     * Starts (if necessary) a new fill request upon entering a view.
     *
     * <p>A new request will be started in 2 scenarios:
     * <ol>
     *   <li>If the user manually requested autofill.
     *   <li>If the view is part of a new partition.
     * </ol>
     *
     * @param id The id of the view that is entered.
     * @param viewState The view that is entered.
     * @param flags The flag that was passed by the AutofillManager.
     *
     * @return {@code true} if a new fill response is requested.
     */
    @GuardedBy("mLock")
    private boolean requestNewFillResponseOnViewEnteredIfNecessaryLocked(@NonNull AutofillId id,
            @NonNull ViewState viewState, int flags) {
        // Force new response for manual request
        if ((flags & FLAG_MANUAL_REQUEST) != 0) {
            mSessionFlags.mAugmentedAutofillOnly = false;
            if (sDebug) Slog.d(TAG, "Re-starting session on view " + id + " and flags " + flags);
            requestNewFillResponseLocked(viewState, ViewState.STATE_RESTARTED_SESSION, flags);
            return true;
        }

        // If it's not, then check if it should start a partition.
        if (shouldStartNewPartitionLocked(id, flags)) {
            if (sDebug) {
                Slog.d(TAG, "Starting partition or augmented request for view id " + id + ": "
                        + viewState.getStateAsString());
            }
            // Fix to always let standard autofill start.
            // Sometimes activity contain IMPORTANT_FOR_AUTOFILL_NO fields which marks session as
            // augmentedOnly, but other fields are still fillable by standard autofill.
            mSessionFlags.mAugmentedAutofillOnly = false;
            requestNewFillResponseLocked(viewState, ViewState.STATE_STARTED_PARTITION, flags);
            return true;
        }

        if (sVerbose) {
            Slog.v(TAG, "Not starting new partition for view " + id + ": "
                    + viewState.getStateAsString());
        }
        return false;
    }

    /**
     * Determines if a new partition should be started for an id.
     *
     * @param id The id of the view that is entered
     *
     * @return {@code true} if a new partition should be started
     */
    @GuardedBy("mLock")
    private boolean shouldStartNewPartitionLocked(@NonNull AutofillId id, int flags) {
        final ViewState currentView = mViewStates.get(id);
        SparseArray<FillResponse> responses = shouldRequestSecondaryProvider(flags)
                ? mSecondaryResponses : mResponses;
        if (responses == null) {
            return currentView != null && (currentView.getState()
                    & ViewState.STATE_PENDING_CREATE_INLINE_REQUEST) == 0;
        }

        if (mSessionFlags.mExpiredResponse) {
            if (sDebug) {
                Slog.d(TAG, "Starting a new partition because the response has expired.");
            }
            return true;
        }

        final int numResponses = responses.size();
        if (numResponses >= AutofillManagerService.getPartitionMaxCount()) {
            Slog.e(TAG, "Not starting a new partition on " + id + " because session " + this.id
                    + " reached maximum of " + AutofillManagerService.getPartitionMaxCount());
            return false;
        }

        for (int responseNum = 0; responseNum < numResponses; responseNum++) {
            final FillResponse response = responses.valueAt(responseNum);

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

    boolean shouldRequestSecondaryProvider(int flags) {
        if (!mService.isAutofillCredmanIntegrationEnabled()
                || mSecondaryProviderHandler == null) {
            return false;
        }
        if (mIsPrimaryCredential) {
            return (flags & FLAG_VIEW_REQUESTS_CREDMAN_SERVICE) == 0;
        } else {
            return (flags & FLAG_VIEW_REQUESTS_CREDMAN_SERVICE) != 0;
        }
    }

    // ErrorProne says mAssistReceiver#mLastFillRequest needs to be guarded by
    // 'Session.this.mLock', which is the same as mLock.
    @SuppressWarnings("GuardedBy")
    @GuardedBy("mLock")
    void updateLocked(AutofillId id, Rect virtualBounds, AutofillValue value, int action,
            int flags) {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#updateLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        if (action == ACTION_RESPONSE_EXPIRED) {
            mSessionFlags.mExpiredResponse = true;
            if (sDebug) {
                Slog.d(TAG, "Set the response has expired.");
            }
            mPresentationStatsEventLogger.maybeSetNoPresentationEventReasonIfNoReasonExists(
                        NOT_SHOWN_REASON_VIEW_CHANGED);
            mPresentationStatsEventLogger.logAndEndEvent();
            return;
        }

        id.setSessionId(this.id);
        if (sVerbose) {
            Slog.v(TAG, "updateLocked(" + this.id + "): id=" + id + ", action="
                    + actionAsString(action) + ", flags=" + flags);
        }
        ViewState viewState = mViewStates.get(id);
        if (sVerbose) {
            Slog.v(TAG, "updateLocked(" + this.id + "): mCurrentViewId=" + mCurrentViewId
                    + ", mExpiredResponse=" + mSessionFlags.mExpiredResponse
                    + ", viewState=" + viewState);
        }

        if (viewState == null) {
            if (action == ACTION_START_SESSION || action == ACTION_VALUE_CHANGED
                    || action == ACTION_VIEW_ENTERED) {
                if (sVerbose) Slog.v(TAG, "Creating viewState for " + id);
                boolean isIgnored = isIgnoredLocked(id);
                viewState = new ViewState(id, this,
                        isIgnored ? ViewState.STATE_IGNORED : ViewState.STATE_INITIAL,
                        mIsPrimaryCredential);
                mViewStates.put(id, viewState);

                // TODO(b/73648631): for optimization purposes, should also ignore if change is
                // detectable, and batch-send them when the session is finished (but that will
                // require tracking detectable fields on AutofillManager)
                if (isIgnored) {
                    if (sDebug) Slog.d(TAG, "updateLocked(): ignoring view " + viewState);
                    return;
                }
            } else {
                if (sVerbose) Slog.v(TAG, "Ignoring specific action when viewState=null");
                return;
            }
        }

        if ((flags & FLAG_RESET_FILL_DIALOG_STATE) != 0) {
            if (sDebug) Log.d(TAG, "force to reset fill dialog state");
            mSessionFlags.mFillDialogDisabled = false;
        }

        /* request assist structure for pcc */
        if ((flags & FLAG_PCC_DETECTION) != 0) {
            requestAssistStructureForPccLocked(flags);
            return;
        }

        if ((flags & FLAG_SCREEN_HAS_CREDMAN_FIELD) != 0) {
            mSessionFlags.mScreenHasCredmanField = true;
        }

        switch(action) {
            case ACTION_START_SESSION:
                // View is triggering autofill.
                mCurrentViewId = viewState.id;
                mPreviousNonNullEnteredViewId = viewState.id;
                viewState.update(value, virtualBounds, flags);
                startNewEventForPresentationStatsEventLogger();
                mPresentationStatsEventLogger.maybeSetIsNewRequest(true);
                if (!isRequestSupportFillDialog(flags)) {
                    mSessionFlags.mFillDialogDisabled = true;
                    mPreviouslyFillDialogPotentiallyStarted = false;
                } else {
                    // Set the default reason for now if the user doesn't trigger any focus event
                    // on the autofillable view. This can be changed downstream when more
                    // information is available or session is committed.
                    mPresentationStatsEventLogger.maybeSetNoPresentationEventReason(
                            NOT_SHOWN_REASON_NO_FOCUS);
                    mPreviouslyFillDialogPotentiallyStarted = true;
                }
                requestNewFillResponseLocked(viewState, ViewState.STATE_STARTED_SESSION, flags);
                break;
            case ACTION_VALUE_CHANGED:
                if (mCompatMode && (viewState.getState() & ViewState.STATE_URL_BAR) != 0) {
                    // Must cancel the session if the value of the URL bar changed
                    final String currentUrl = mUrlBar == null ? null
                            : mUrlBar.getText().toString().trim();
                    if (currentUrl == null) {
                        // Validation check - shouldn't happen.
                        wtf(null, "URL bar value changed, but current value is null");
                        return;
                    }
                    if (value == null || ! value.isText()) {
                        // Validation check - shouldn't happen.
                        wtf(null, "URL bar value changed to null or non-text: %s", value);
                        return;
                    }
                    final String newUrl = value.getTextValue().toString();
                    if (newUrl.equals(currentUrl)) {
                        if (sDebug) Slog.d(TAG, "Ignoring change on URL bar as it's the same");
                        return;
                    }
                    if (mSaveOnAllViewsInvisible) {
                        // We cannot cancel the session because it could hinder Save when all views
                        // are finished, as the URL bar changed callback is usually called before
                        // the virtual views become invisible.
                        if (sDebug) {
                            Slog.d(TAG, "Ignoring change on URL because session will finish when "
                                    + "views are gone");
                        }
                        return;
                    }
                    if (sDebug) Slog.d(TAG, "Finishing session because URL bar changed");
                    forceRemoveFromServiceLocked(AutofillManager.STATE_UNKNOWN_COMPAT_MODE);
                    return;
                }
                if (!Objects.equals(value, viewState.getCurrentValue())) {
                    logIfViewClearedLocked(id, value, viewState);
                    updateViewStateAndUiOnValueChangedLocked(id, value, viewState, flags);
                }
                break;
            case ACTION_VIEW_ENTERED:
                mLatencyBaseTime = SystemClock.elapsedRealtime();
                boolean wasPreviouslyFillDialog = mPreviouslyFillDialogPotentiallyStarted;
                mPreviouslyFillDialogPotentiallyStarted = false;
                if (sVerbose && virtualBounds != null) {
                    Slog.v(TAG, "entered on virtual child " + id + ": " + virtualBounds);
                }

                final boolean isSameViewEntered = Objects.equals(mCurrentViewId, viewState.id);
                // Update the view states first...
                mCurrentViewId = viewState.id;
                if (value != null) {
                    viewState.setCurrentValue(value);
                }
                // isSameViewEntered has some limitations, where it isn't considered same view when
                // autofill suggestions pop up, user selects, and the focus lands back on the view.
                // isSameViewAgain tries to overcome that situation.
                final boolean isSameViewAgain = isSameViewEntered
                        || Objects.equals(mCurrentViewId, mPreviousNonNullEnteredViewId);
                if (mCurrentViewId != null) {
                    mPreviousNonNullEnteredViewId = mCurrentViewId;
                }
                boolean isCredmanRequested = (flags & FLAG_VIEW_REQUESTS_CREDMAN_SERVICE) != 0;
                if (shouldRequestSecondaryProvider(flags)) {
                    if (requestNewFillResponseOnViewEnteredIfNecessaryLocked(
                            id, viewState, flags)) {
                        Slog.v(TAG, "Started a new fill request for secondary provider.");
                        return;
                    }

                    FillResponse response = viewState.getSecondaryResponse();
                    if (response != null) {
                        logPresentationStatsOnViewEntered(response, isCredmanRequested);
                    }

                    // If the ViewState is ready to be displayed, onReady() will be called.
                    viewState.update(value, virtualBounds, flags);

                    // return here because primary provider logic is not applicable.
                    return;
                }

                if (mCompatMode && (viewState.getState() & ViewState.STATE_URL_BAR) != 0) {
                    if (sDebug) Slog.d(TAG, "Ignoring VIEW_ENTERED on URL BAR (id=" + id + ")");
                    return;
                }

                synchronized (mLock) {
                    if (!mLogViewEntered) {
                        // If the current request is for FillDialog (preemptive)
                        // then this is the first time that the view is entered
                        // (mLogViewEntered == false) in this case, setLastResponse()
                        // has already been called, so just log here.
                        // If the current request is not and (mLogViewEntered == false)
                        // then the last session is being tracked (setLastResponse not called)
                        // so this calling logViewEntered will be a nop.
                        // Calling logViewEntered() twice will only log it once
                        // TODO(271181979): this is broken for multiple partitions
                        mService.logViewEntered(this.id, null);
                    }

                    // If this is the first time view is entered for inline, the last
                    // session is still being tracked, so logViewEntered() needs
                    // to be delayed until setLastResponse is called.
                    // For fill dialog requests case logViewEntered is already called above
                    // so this will do nothing. Assumption: only one fill dialog per session
                    mLogViewEntered = true;
                }

                // Previously, fill request will only start whenever a view is entered.
                // With Fill Dialog, request starts prior to view getting entered. So, we can't end
                // the event at this moment, otherwise we will be wrongly attributing fill dialog
                // event as concluded.
                if (!wasPreviouslyFillDialog && !isSameViewAgain) {
                    // TODO(b/319872477): Re-consider this logic below
                    mPresentationStatsEventLogger.maybeSetNoPresentationEventReason(
                            NOT_SHOWN_REASON_VIEW_FOCUS_CHANGED);
                    mPresentationStatsEventLogger.logAndEndEvent();
                }

                if ((flags & FLAG_MANUAL_REQUEST) == 0) {
                    // Not a manual request
                    if (mAugmentedAutofillableIds != null && mAugmentedAutofillableIds.contains(
                            id)) {
                        // Regular autofill handled the view and returned null response, but it
                        // triggered augmented autofill
                        if (!isSameViewEntered) {
                            if (sDebug) Slog.d(TAG, "trigger augmented autofill.");
                            triggerAugmentedAutofillLocked(flags);
                        } else {
                            if (sDebug) {
                                Slog.d(TAG, "skip augmented autofill for same view: "
                                        + "same view entered");
                            }
                        }
                        return;
                    } else if (mSessionFlags.mAugmentedAutofillOnly && isSameViewEntered) {
                        // Regular autofill is disabled.
                        if (sDebug) {
                            Slog.d(TAG, "skip augmented autofill for same view: "
                                    + "standard autofill disabled.");
                        }
                        return;
                    }
                }
                // If previous request was FillDialog request, a logger event was already started
                if (!wasPreviouslyFillDialog) {
                    startNewEventForPresentationStatsEventLogger();
                }
                if (requestNewFillResponseOnViewEnteredIfNecessaryLocked(id, viewState, flags)) {
                    // If a new request was issued even if previously it was fill dialog request,
                    // we should end the log event, and start a new one. However, it leaves us
                    // susceptible to race condition. But since mPresentationStatsEventLogger is
                    // lock guarded, we should be safe.
                    if (wasPreviouslyFillDialog) {
                        mPresentationStatsEventLogger.logAndEndEvent();
                        startNewEventForPresentationStatsEventLogger();
                    }
                    return;
                }

                FillResponse response = viewState.getResponse();
                if (response != null) {
                    logPresentationStatsOnViewEntered(response, isCredmanRequested);
                }

                if (isSameViewEntered) {
                    setFillDialogDisabledAndStartInput();
                    return;
                }

                // If the ViewState is ready to be displayed, onReady() will be called.
                viewState.update(value, virtualBounds, flags);
                break;
            case ACTION_VIEW_EXITED:
                if (Objects.equals(mCurrentViewId, viewState.id)) {
                    if (sVerbose) Slog.v(TAG, "Exiting view " + id);
                    mUi.hideFillUi(this);
                    mUi.hideFillDialog(this);
                    hideAugmentedAutofillLocked(viewState);
                    // We don't send an empty response to IME so that it doesn't cause UI flicker
                    // on the IME side if it arrives before the input view is finished on the IME.
                    mInlineSessionController.resetInlineFillUiLocked();

                    if ((viewState.getState() &
                            ViewState.STATE_PENDING_CREATE_INLINE_REQUEST) != 0) {
                        // View was exited before Inline Request sent back, do not set it to
                        // null yet to let onHandleAssistData finish processing
                    } else {
                        mCurrentViewId = null;
                    }

                    // It's not necessary that there's no more presentation for this view. It could
                    // be that the user chose some suggestion, in which case, view exits.
                    mPresentationStatsEventLogger.maybeSetNoPresentationEventReason(
                                NOT_SHOWN_REASON_VIEW_FOCUS_CHANGED);
                }
                break;
            default:
                Slog.w(TAG, "updateLocked(): unknown action: " + action);
        }
    }

    @GuardedBy("mLock")
    private void logPresentationStatsOnViewEntered(FillResponse response,
            boolean isCredmanRequested) {
        mPresentationStatsEventLogger.maybeSetRequestId(response.getRequestId());
        mPresentationStatsEventLogger.maybeSetIsCredentialRequest(isCredmanRequested);
        mPresentationStatsEventLogger.maybeSetFieldClassificationRequestId(
                mFieldClassificationIdSnapshot);
        mPresentationStatsEventLogger.maybeSetAvailableCount(
                response.getDatasets(), mCurrentViewId);
        mPresentationStatsEventLogger.maybeSetFocusedId(mCurrentViewId);
    }

    @GuardedBy("mLock")
    private void hideAugmentedAutofillLocked(@NonNull ViewState viewState) {
        if ((viewState.getState()
                & ViewState.STATE_TRIGGERED_AUGMENTED_AUTOFILL) != 0) {
            viewState.resetState(ViewState.STATE_TRIGGERED_AUGMENTED_AUTOFILL);
            cancelAugmentedAutofillLocked();
        }
    }

    /**
     * Checks whether a view should be ignored.
     */
    @GuardedBy("mLock")
    private boolean isIgnoredLocked(AutofillId id) {
        // Always check the latest response only
        final FillResponse response = getLastResponseLocked(null);
        if (response == null) return false;

        return ArrayUtils.contains(response.getIgnoredIds(), id);
    }

    @GuardedBy("mLock")
    private void logIfViewClearedLocked(AutofillId id, AutofillValue value, ViewState viewState) {
        if ((value == null || value.isEmpty())
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
    }

    @GuardedBy("mLock")
    private void updateViewStateAndUiOnValueChangedLocked(AutofillId id, AutofillValue value,
            ViewState viewState, int flags) {
        // Cache the last non-empty value for save purpose. Some apps clear the form before
        // navigating to other activities.
        if (mIgnoreViewStateResetToEmpty && (value == null || value.isEmpty())
                && viewState.getCurrentValue() != null && viewState.getCurrentValue().isText()
                && viewState.getCurrentValue().getTextValue() != null
                && viewState.getCurrentValue().getTextValue().length() > 1) {
            if (sVerbose) {
                Slog.v(TAG, "value is resetting to empty, caching the last non-empty value");
            }
            viewState.setCandidateSaveValue(viewState.getCurrentValue());
        } else {
            viewState.setCandidateSaveValue(null);
        }
        final String textValue;
        if (value == null || !value.isText()) {
            textValue = null;
        } else {
            final CharSequence text = value.getTextValue();
            // Text should never be null, but it doesn't hurt to check to avoid a
            // system crash...
            textValue = (text == null) ? null : text.toString();
        }
        updateFilteringStateOnValueChangedLocked(textValue, viewState);

        viewState.setCurrentValue(value);

        final String filterText = textValue;

        final AutofillValue filledValue = viewState.getAutofilledValue();
        if (filledValue != null) {
            if (filledValue.equals(value)) {
                // When the update is caused by autofilling the view, just update the
                // value, not the UI.
                if (sVerbose) {
                    Slog.v(TAG, "ignoring autofilled change on id " + id);
                }
                // TODO(b/156099633): remove this once framework gets out of business of resending
                // inline suggestions when IME visibility changes.
                mInlineSessionController.hideInlineSuggestionsUiLocked(viewState.id);
                viewState.resetState(ViewState.STATE_CHANGED);
                return;
            } else if ((viewState.id.equals(this.mCurrentViewId))
                    && (viewState.getState() & ViewState.STATE_AUTOFILLED) != 0) {
                // Remove autofilled state once field is changed after autofilling.
                if (sVerbose) {
                    Slog.v(TAG, "field changed after autofill on id " + id);
                }
                viewState.resetState(ViewState.STATE_AUTOFILLED);
                final ViewState currentView = mViewStates.get(mCurrentViewId);
                currentView.maybeCallOnFillReady(flags);
            }
        }

        if (viewState.id.equals(this.mCurrentViewId)
                && (viewState.getState() & ViewState.STATE_INLINE_SHOWN) != 0) {
            if ((viewState.getState() & ViewState.STATE_INLINE_DISABLED) != 0) {
                mInlineSessionController.disableFilterMatching(viewState.id);
            }
            mInlineSessionController.filterInlineFillUiLocked(mCurrentViewId, filterText);
        } else if (viewState.id.equals(this.mCurrentViewId)
                && (viewState.getState() & ViewState.STATE_TRIGGERED_AUGMENTED_AUTOFILL) != 0) {
            if (!TextUtils.isEmpty(filterText)) {
                // TODO: we should be able to replace this with controller#filterInlineFillUiLocked
                // to accomplish filtering for augmented autofill.
                mInlineSessionController.hideInlineSuggestionsUiLocked(mCurrentViewId);
            }
        }

        viewState.setState(ViewState.STATE_CHANGED);
        getUiForShowing().filterFillUi(filterText, this);
    }

    /**
     * Disable filtering of inline suggestions for further text changes in this view if any
     * character was removed earlier and now any character is being added. Such behaviour may
     * indicate the IME attempting to probe the potentially sensitive content of inline suggestions.
     */
    @GuardedBy("mLock")
    private void updateFilteringStateOnValueChangedLocked(@Nullable String newTextValue,
            ViewState viewState) {
        if (newTextValue == null) {
            // Don't just return here, otherwise the IME can circumvent this logic using non-text
            // values.
            newTextValue = "";
        }
        final AutofillValue currentValue = viewState.getCurrentValue();
        final String currentTextValue;
        if (currentValue == null || !currentValue.isText()) {
            currentTextValue = "";
        } else {
            currentTextValue = currentValue.getTextValue().toString();
        }

        if ((viewState.getState() & ViewState.STATE_CHAR_REMOVED) == 0) {
            if (!containsCharsInOrder(newTextValue, currentTextValue)) {
                viewState.setState(ViewState.STATE_CHAR_REMOVED);
            }
        } else if (!containsCharsInOrder(currentTextValue, newTextValue)) {
            // Characters were added or replaced.
            viewState.setState(ViewState.STATE_INLINE_DISABLED);
        }
    }

    @Override
    public void onFillReady(@NonNull FillResponse response, @NonNull AutofillId filledId,
            @Nullable AutofillValue value, int flags) {
        synchronized (mLock) {
            mPresentationStatsEventLogger.maybeSetFieldClassificationRequestId(
                    mFieldClassificationIdSnapshot);
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillReady() rejected - session: "
                        + id + " destroyed");
                mSaveEventLogger.maybeSetSaveUiNotShownReason(NO_SAVE_REASON_SESSION_DESTROYED);
                mSaveEventLogger.logAndEndEvent();
                mPresentationStatsEventLogger.maybeSetNoPresentationEventReason(
                    NOT_SHOWN_REASON_SESSION_COMMITTED_PREMATURELY);
                mPresentationStatsEventLogger.logAndEndEvent();
                return;
            }
        }

        String filterText = null;
        if (value != null && value.isText()) {
            filterText = value.getTextValue().toString();
        }

        final CharSequence serviceLabel;
        final Drawable serviceIcon;
        synchronized (this.mService.mLock) {
            serviceLabel = mService.getServiceLabelLocked();
            serviceIcon = mService.getServiceIconLocked();
        }
        if (serviceLabel == null || serviceIcon == null) {
            wtf(null, "onFillReady(): no service label or icon");
            return;
        }

        synchronized (mLock) {
            // Time passed since Session was created
            final long suggestionSentRelativeTimestamp =
                    SystemClock.elapsedRealtime() - mLatencyBaseTime;
            mPresentationStatsEventLogger.maybeSetSuggestionSentTimestampMs(
                    (int) (suggestionSentRelativeTimestamp));
        }

        final AutofillId[] ids = response.getFillDialogTriggerIds();
        if (ids != null && ArrayUtils.contains(ids, filledId)) {
            if (requestShowFillDialog(response, filledId, filterText, flags)) {
                synchronized (mLock) {
                    final ViewState currentView = mViewStates.get(mCurrentViewId);
                    currentView.setState(ViewState.STATE_FILL_DIALOG_SHOWN);
                    mPresentationStatsEventLogger.maybeSetCountShown(
                            response.getDatasets(), mCurrentViewId);
                    mPresentationStatsEventLogger.maybeSetDisplayPresentationType(UI_TYPE_DIALOG);
                }
                // Just show fill dialog once, so disabled after shown.
                // Note: Cannot disable before requestShowFillDialog() because the method
                //       need to check whether fill dialog enabled.
                setFillDialogDisabled();
                synchronized (mLock) {
                    // Logs when fill dialog ui is shown; time since Session was created
                    final long fillDialogUiShownRelativeTimestamp =
                            SystemClock.elapsedRealtime() - mLatencyBaseTime;
                    mPresentationStatsEventLogger.maybeSetSuggestionPresentedTimestampMs(
                            (int) (fillDialogUiShownRelativeTimestamp));
                }
                return;
            } else {
                setFillDialogDisabled();
            }

        }

        if (response.supportsInlineSuggestions()) {
            synchronized (mLock) {
                if (requestShowInlineSuggestionsLocked(response, filterText)) {
                    // Cannot tell for sure that InlineSuggestions are shown yet, IME needs to send
                    // back a response via callback.
                    final ViewState currentView = mViewStates.get(mCurrentViewId);
                    currentView.setState(ViewState.STATE_INLINE_SHOWN);
                    // TODO(b/234475358): Log more accurate value of number of inline suggestions
                    // shown, inflated, and filtered.
                    mPresentationStatsEventLogger.maybeSetCountShown(
                            response.getDatasets(), mCurrentViewId);
                    mPresentationStatsEventLogger.maybeSetInlinePresentationAndSuggestionHostUid(
                            mContext, userId);
                    return;
                }
            }
        }

        getUiForShowing().showFillUi(filledId, response, filterText,
                mService.getServicePackageName(), mComponentName,
                serviceLabel, serviceIcon, this, mContext, id, mCompatMode,
                mService.getMaster().getMaxInputLengthForAutofill());

        synchronized (mLock) {
            mPresentationStatsEventLogger.maybeSetCountShown(
                    response.getDatasets(), mCurrentViewId);
            mPresentationStatsEventLogger.maybeSetDisplayPresentationType(UI_TYPE_MENU);
        }

        synchronized (mLock) {
            if (mUiShownTime == 0) {
                // Log first time UI is shown.
                mUiShownTime = SystemClock.elapsedRealtime();
                final long duration = mUiShownTime - mStartTime;
                // This logs when dropdown ui was shown. Timestamp is relative to
                // when the session was created
                mPresentationStatsEventLogger.maybeSetSuggestionPresentedTimestampMs(
                        (int) (mUiShownTime - mLatencyBaseTime));

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

                addTaggedDataToRequestLogLocked(response.getRequestId(),
                        MetricsEvent.FIELD_AUTOFILL_DURATION, duration);
            }
        }
    }

    private boolean isCredmanIntegrationActive(FillResponse response) {
        return Flags.autofillCredmanIntegration()
                && (response.getFlags() & FillResponse.FLAG_CREDENTIAL_MANAGER_RESPONSE) != 0;
    }

    @GuardedBy("mLock")
    private void updateFillDialogTriggerIdsLocked() {
        final FillResponse response = getLastResponseLocked(null);

        if (response == null) return;

        final AutofillId[] ids = response.getFillDialogTriggerIds();
        notifyClientFillDialogTriggerIds(ids == null ? null : Arrays.asList(ids));
    }

    private void notifyClientFillDialogTriggerIds(List<AutofillId> fieldIds) {
        try {
            if (sVerbose) {
                Slog.v(TAG, "notifyFillDialogTriggerIds(): " + fieldIds);
            }
            getClient().notifyFillDialogTriggerIds(fieldIds);
        } catch (RemoteException e) {
            Slog.w(TAG, "Cannot set trigger ids for fill dialog", e);
        }
    }

    private boolean isFillDialogUiEnabled() {
        synchronized (mLock) {
            return !mSessionFlags.mFillDialogDisabled && !mSessionFlags.mScreenHasCredmanField;
        }
    }

    private void setFillDialogDisabled() {
        synchronized (mLock) {
            mSessionFlags.mFillDialogDisabled = true;
        }
        notifyClientFillDialogTriggerIds(null);
    }

    private void setFillDialogDisabledAndStartInput() {
        if (getUiForShowing().isFillDialogShowing()) {
            setFillDialogDisabled();
            final AutofillId id;
            synchronized (mLock) {
                id = mCurrentViewId;
            }
            requestShowSoftInput(id);
        }
    }

    private boolean requestShowFillDialog(FillResponse response,
            AutofillId filledId, String filterText, int flags) {
        if (!isFillDialogUiEnabled()) {
            // Unsupported fill dialog UI
            if (sDebug) Log.w(TAG, "requestShowFillDialog: fill dialog is disabled");
            return false;
        }

        if ((flags & FillRequest.FLAG_IME_SHOWING) != 0) {
            // IME is showing, fallback to normal suggestions UI
            if (sDebug) Log.w(TAG, "requestShowFillDialog: IME is showing");
            return false;
        }

        if (mInlineSessionController.isImeShowing()) {
            // IME is showing, fallback to normal suggestions UI
            // Note: only work when inline suggestions supported
            return false;
        }

        synchronized (mLock) {
            if (mLastFillDialogTriggerIds == null
                    || !ArrayUtils.contains(mLastFillDialogTriggerIds, filledId)) {
                // Last fill dialog triggered ids are changed.
                if (sDebug) Log.w(TAG, "Last fill dialog triggered ids are changed.");
                return false;
            }

        }

        Drawable serviceIcon = null;
        synchronized (mLock) {
            serviceIcon = getServiceIcon(response);
        }

        getUiForShowing().showFillDialog(filledId, response, filterText,
                mService.getServicePackageName(), mComponentName, serviceIcon, this,
                id, mCompatMode, mPresentationStatsEventLogger);
        return true;
    }

    /**
     * Get the custom icon that was passed through FillResponse. If the custom icon wasn't able
     * to be fetched, use the default provider icon instead
     *
     * @return Drawable of the provider icon, if it was able to be fetched. Null otherwise
     */
    @SuppressWarnings("GuardedBy") // ErrorProne says we need to use mService.mLock, but it's
                                   // actually the same object as mLock.
                                   // TODO: Expose mService.mLock or redesign instead.
    @GuardedBy("mLock")
    private Drawable getServiceIcon(FillResponse response) {
        Drawable serviceIcon = null;
        // Try to get the custom Icon, if one was passed through FillResponse
        int iconResourceId = response.getIconResourceId();
        if (iconResourceId != 0) {
            serviceIcon = mService.getMaster().getContext().getPackageManager()
                .getDrawable(
                    mService.getServicePackageName(),
                    iconResourceId,
                    null);
        }

        // Custom icon wasn't fetched, use the default package icon instead
        if (serviceIcon == null) {
            serviceIcon = mService.getServiceIconLocked();
        }

        return serviceIcon;
    }

    /**
     * Get the custom label that was passed through FillResponse. If the custom label
     * wasn't able to be fetched, use the default provider icon instead
     *
     * @return Drawable of the provider icon, if it was able to be fetched. Null otherwise
     */
    @SuppressWarnings("GuardedBy") // ErrorProne says we need to use mService.mLock, but it's
                                   // actually the same object as mLock.
                                   // TODO: Expose mService.mLock or redesign instead.
    @GuardedBy("mLock")
    private CharSequence getServiceLabel(FillResponse response) {
        CharSequence serviceLabel = null;
        // Try to get the custom Service name, if one was passed through FillResponse
        int customServiceNameId = response.getServiceDisplayNameResourceId();
        if (customServiceNameId != 0) {
            serviceLabel = mService.getMaster().getContext().getPackageManager()
                .getText(
                    mService.getServicePackageName(),
                    customServiceNameId,
                    null);
        }

        // Custom label wasn't fetched, use the default package name instead
        if (serviceLabel == null) {
            serviceLabel = mService.getServiceLabelLocked();
        }

        return serviceLabel;
    }

    /**
     * Returns whether we made a request to show inline suggestions.
     */
    private boolean requestShowInlineSuggestionsLocked(@NonNull FillResponse response,
            @Nullable String filterText) {
        if (mCurrentViewId == null) {
            Log.w(TAG, "requestShowInlineSuggestionsLocked(): no view currently focused");
            return false;
        }
        final AutofillId focusedId = mCurrentViewId;

        final Optional<InlineSuggestionsRequest> inlineSuggestionsRequest =
                mInlineSessionController.getInlineSuggestionsRequestLocked();
        if (!inlineSuggestionsRequest.isPresent()) {
            Log.w(TAG, "InlineSuggestionsRequest unavailable");
            return false;
        }

        final RemoteInlineSuggestionRenderService remoteRenderService =
                mService.getRemoteInlineSuggestionRenderServiceLocked();
        if (remoteRenderService == null) {
            Log.w(TAG, "RemoteInlineSuggestionRenderService not found");
            return false;
        }

        // Set this to false - we are requesting a new inline request and haven't shown
        // anything yet
        synchronized (mLock) {
            mLoggedInlineDatasetShown = false;
        }

        final InlineFillUi.InlineFillUiInfo inlineFillUiInfo =
                new InlineFillUi.InlineFillUiInfo(inlineSuggestionsRequest.get(), focusedId,
                        filterText, remoteRenderService, userId, id);
        InlineFillUi inlineFillUi = InlineFillUi.forAutofill(inlineFillUiInfo, response,
                new InlineFillUi.InlineSuggestionUiCallback() {
                    @Override
                    public void autofill(@NonNull Dataset dataset, int datasetIndex) {
                        fill(response.getRequestId(), datasetIndex, dataset, UI_TYPE_INLINE);
                    }

                    @Override
                    public void authenticate(int requestId, int datasetIndex) {
                        Session.this.authenticate(response.getRequestId(), datasetIndex,
                                response.getAuthentication(), response.getClientState(),
                                UI_TYPE_INLINE);
                    }

                    @Override
                    public void startIntentSender(@NonNull IntentSender intentSender) {
                        Session.this.startIntentSender(intentSender, new Intent());
                    }

                    @Override
                    public void onError() {
                        synchronized (mLock) {
                            mInlineSessionController.setInlineFillUiLocked(
                                    InlineFillUi.emptyUi(focusedId));
                        }
                    }

                    @Override
                    public void onInflate() {
                        Session.this.onShown(UI_TYPE_INLINE);
                    }
                }, mService.getMaster().getMaxInputLengthForAutofill());
        return mInlineSessionController.setInlineFillUiLocked(inlineFillUi);
    }

    private ResultReceiver constructCredentialManagerCallback(int requestId) {
        final ResultReceiver resultReceiver = new ResultReceiver(mHandler) {
            final AutofillId mAutofillId = mCurrentViewId;
            @Override
            protected void onReceiveResult(int resultCode, Bundle resultData) {
                if (resultCode == SUCCESS_CREDMAN_SELECTOR) {
                    Slog.d(TAG, "onReceiveResult from Credential Manager "
                            + "bottom sheet with mCurrentViewId: " + mAutofillId);
                    GetCredentialResponse getCredentialResponse =
                            resultData.getParcelable(
                                    CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE,
                                    GetCredentialResponse.class);

                    if (Flags.autofillCredmanDevIntegration()) {
                        sendCredentialManagerResponseToApp(getCredentialResponse,
                                /*exception=*/ null, mAutofillId);
                    } else {
                        Dataset datasetFromCredential = getDatasetFromCredentialResponse(
                                getCredentialResponse);
                        if (datasetFromCredential != null) {
                            autoFill(requestId, /*datasetIndex=*/-1,
                                    datasetFromCredential, false,
                                    UI_TYPE_CREDMAN_BOTTOM_SHEET);
                        }
                    }
                } else if (resultCode == FAILURE_CREDMAN_SELECTOR) {
                    String[] exception =  resultData.getStringArray(
                            CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION);
                    if (exception != null && exception.length >= 2) {
                        String errType = exception[0];
                        String errMsg = exception[1];
                        Slog.w(TAG, "Credman bottom sheet from pinned "
                                + "entry failed with: + " + errType + " , "
                                + errMsg);
                        sendCredentialManagerResponseToApp(/*response=*/ null,
                                new GetCredentialException(errType, errMsg),
                                mAutofillId);
                    }
                } else {
                    Slog.d(TAG, "Unknown resultCode from credential "
                            + "manager bottom sheet: " + resultCode);
                }
            }
        };
        ResultReceiver ipcFriendlyResultReceiver =
                toIpcFriendlyResultReceiver(resultReceiver);

        return ipcFriendlyResultReceiver;
    }

    private ResultReceiver toIpcFriendlyResultReceiver(ResultReceiver resultReceiver) {
        final Parcel parcel = Parcel.obtain();
        resultReceiver.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);

        final ResultReceiver ipcFriendly = ResultReceiver.CREATOR.createFromParcel(parcel);
        parcel.recycle();

        return ipcFriendly;
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

    private void notifyUnavailableToClient(int sessionFinishedState,
            @Nullable ArrayList<AutofillId> autofillableIds) {
        synchronized (mLock) {
            if (mCurrentViewId == null) return;
            try {
                if (mHasCallback) {
                    mClient.notifyNoFillUi(id, mCurrentViewId, sessionFinishedState);
                } else if (sessionFinishedState != AutofillManager.STATE_UNKNOWN) {
                    mClient.setSessionFinished(sessionFinishedState, autofillableIds);
                }
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client no fill UI: id=" + mCurrentViewId, e);
            }
        }
    }

    private void notifyDisableAutofillToClient(long disableDuration, ComponentName componentName) {
        synchronized (mLock) {
            if (mCurrentViewId == null) return;
            try {
                mClient.notifyDisableAutofill(disableDuration, componentName);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client disable autofill: id=" + mCurrentViewId, e);
            }
        }
    }

    @GuardedBy("mLock")
    private void updateTrackedIdsLocked() {
        // Only track the views of the last response as only those are reported back to the
        // service, see #showSaveLocked
        final FillResponse response = getLastResponseLocked(null);
        if (response == null) return;

        ArraySet<AutofillId> trackedViews = null;
        mSaveOnAllViewsInvisible = false;
        boolean saveOnFinish = true;
        final SaveInfo saveInfo = response.getSaveInfo();
        final AutofillId saveTriggerId;
        final int flags;
        if (saveInfo != null) {
            saveTriggerId = saveInfo.getTriggerId();
            if (saveTriggerId != null) {
                writeLog(MetricsEvent.AUTOFILL_EXPLICIT_SAVE_TRIGGER_DEFINITION);
                mSaveEventLogger.maybeSetSaveUiShownReason(SAVE_UI_SHOWN_REASON_TRIGGER_ID_SET);
            }
            flags = saveInfo.getFlags();
            mSaveOnAllViewsInvisible = (flags & SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE) != 0;

            mFillResponseEventLogger.maybeSetSaveUiTriggerIds(HAVE_SAVE_TRIGGER_ID);

            // Start to log Save event.
            mSaveEventLogger.maybeSetRequestId(response.getRequestId());
            mSaveEventLogger.maybeSetAppPackageUid(uid);
            mSaveEventLogger.maybeSetSaveUiTriggerIds(HAVE_SAVE_TRIGGER_ID);
            mSaveEventLogger.maybeSetFlag(flags);

            // We only need to track views if we want to save once they become invisible.
            if (mSaveOnAllViewsInvisible) {
                if (trackedViews == null) {
                    trackedViews = new ArraySet<>();
                }
                if (saveInfo.getRequiredIds() != null) {
                    Collections.addAll(trackedViews, saveInfo.getRequiredIds());
                    mSaveEventLogger.maybeSetSaveUiShownReason(
                        SAVE_UI_SHOWN_REASON_REQUIRED_ID_CHANGE);
                }

                if (saveInfo.getOptionalIds() != null) {
                    Collections.addAll(trackedViews, saveInfo.getOptionalIds());
                    mSaveEventLogger.maybeSetSaveUiShownReason(
                        SAVE_UI_SHOWN_REASON_OPTIONAL_ID_CHANGE);
                }
            }
            if ((flags & SaveInfo.FLAG_DONT_SAVE_ON_FINISH) != 0) {
                mSaveEventLogger.maybeSetSaveUiShownReason(
                    SAVE_UI_SHOWN_REASON_UNKNOWN);
                mSaveEventLogger.maybeSetSaveUiNotShownReason(
                    NO_SAVE_REASON_WITH_DONT_SAVE_ON_FINISH_FLAG);
                saveOnFinish = false;
            }

        } else {
            flags = 0;
            mSaveEventLogger.maybeSetSaveUiNotShownReason(
                NO_SAVE_REASON_NO_SAVE_INFO);
            saveTriggerId = null;
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
                    if (id != null) {
                        if (trackedViews == null || !trackedViews.contains(id)) {
                            fillableIds = ArrayUtils.add(fillableIds, id);
                        }
                    }
                }
            }
        }

        try {
            if (sVerbose) {
                Slog.v(TAG, "updateTrackedIdsLocked(): " + trackedViews + " => " + fillableIds
                        + " triggerId: " + saveTriggerId + " saveOnFinish:" + saveOnFinish
                        + " flags: " + flags + " hasSaveInfo: " + (saveInfo != null));
            }
            mClient.setTrackedViews(id, toArray(trackedViews), mSaveOnAllViewsInvisible,
                    saveOnFinish, toArray(fillableIds), saveTriggerId);
        } catch (RemoteException e) {
            Slog.w(TAG, "Cannot set tracked ids", e);
        }
    }

    /**
     * Sets the state of views that failed to autofill.
     */
    @GuardedBy("mLock")
    void setAutofillFailureLocked(@NonNull List<AutofillId> ids) {
        if (sVerbose && !ids.isEmpty()) {
            Slog.v(TAG, "Total views that failed to populate: " + ids.size());
        }
        for (int i = 0; i < ids.size(); i++) {
            final AutofillId id = ids.get(i);
            final ViewState viewState = mViewStates.get(id);
            if (viewState == null) {
                Slog.w(TAG, "setAutofillFailure(): no view for id " + id);
                continue;
            }
            viewState.resetState(ViewState.STATE_AUTOFILLED);
            final int state = viewState.getState();
            viewState.setState(state | ViewState.STATE_AUTOFILL_FAILED);
            if (sVerbose) {
                Slog.v(TAG, "Changed state of " + id + " to " + viewState.getStateAsString());
            }
        }
        mPresentationStatsEventLogger.maybeSetViewFillFailureCounts(ids.size());
    }

    /**
     * Sets the state of views that failed to autofill.
     */
    @GuardedBy("mLock")
    void setViewAutofilled(@NonNull AutofillId id) {
        if (sVerbose) {
            Slog.v(TAG, "View autofilled: " + id);
        }
        if (id.getSessionId() == AutofillId.NO_SESSION) {
            id.setSessionId(this.id);
        }
        mPresentationStatsEventLogger.maybeAddSuccessId(id);
    }

    @GuardedBy("mLock")
    private void replaceResponseLocked(@NonNull FillResponse oldResponse,
            @NonNull FillResponse newResponse, @Nullable Bundle newClientState) {
        // Disassociate view states with the old response
        setViewStatesLocked(oldResponse, ViewState.STATE_INITIAL, /* clearResponse= */ true,
                /* isPrimary= */ true);
        // Move over the id
        newResponse.setRequestId(oldResponse.getRequestId());
        // Now process the new response
        processResponseLockedForPcc(newResponse, newClientState, 0);
    }

    @GuardedBy("mLock")
    private void processNullResponseLocked(int requestId, int flags) {
        unregisterDelayedFillBroadcastLocked();
        if ((flags & FLAG_MANUAL_REQUEST) != 0) {
            getUiForShowing().showError(R.string.autofill_error_cannot_autofill, this);
        }

        final FillContext context = getFillContextByRequestIdLocked(requestId);

        final ArrayList<AutofillId> autofillableIds;
        if (context != null) {
            final AssistStructure structure = context.getStructure();
            autofillableIds = Helper.getAutofillIds(structure, /* autofillableOnly= */true);
        } else {
            Slog.w(TAG, "processNullResponseLocked(): no context for req " + requestId);
            autofillableIds = null;
        }
        // Log the existing FillResponse event.
        mFillResponseEventLogger.maybeSetAvailableCount(0);
        mFillResponseEventLogger.maybeSetLatencyResponseProcessingMillis();
        mFillResponseEventLogger.logAndEndEvent();
        mService.resetLastResponse();

        // The default autofill service cannot fulfill the request, let's check if the augmented
        // autofill service can.
        mAugmentedAutofillDestroyer = triggerAugmentedAutofillLocked(flags);
        if (mAugmentedAutofillDestroyer == null && ((flags & FLAG_PASSWORD_INPUT_TYPE) == 0)) {
            if (sVerbose) {
                Slog.v(TAG, "canceling session " + id + " when service returned null and it cannot "
                        + "be augmented. AutofillableIds: " + autofillableIds);
            }
            // Nothing to be done, but need to notify client.
            notifyUnavailableToClient(AutofillManager.STATE_FINISHED, autofillableIds);
            removeFromService();
        } else {
            if ((flags & FLAG_PASSWORD_INPUT_TYPE) != 0) {
                if (sVerbose) {
                    Slog.v(TAG, "keeping session " + id + " when service returned null and "
                            + "augmented service is disabled for password fields. "
                            + "AutofillableIds: " + autofillableIds);
                }
                mInlineSessionController.hideInlineSuggestionsUiLocked(mCurrentViewId);
            } else {
                if (sVerbose) {
                    Slog.v(TAG, "keeping session " + id + " when service returned null but "
                            + "it can be augmented. AutofillableIds: " + autofillableIds);
                }
            }
            mAugmentedAutofillableIds = autofillableIds;
            try {
                mClient.setState(AutofillManager.SET_STATE_FLAG_FOR_AUTOFILL_ONLY);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error setting client to autofill-only", e);
            }
        }
    }

    /**
     * Tries to trigger Augmented Autofill when the standard service could not fulfill a request.
     *
     * <p> The request may not have been sent when this method returns as it may be waiting for
     * the inline suggestion request asynchronously.
     *
     * @return callback to destroy the autofill UI, or {@code null} if not supported.
     */
    // TODO(b/123099468): might need to call it in other places, like when the service returns a
    // non-null response but without datasets (for example, just SaveInfo)
    @GuardedBy("mLock")
    private Runnable triggerAugmentedAutofillLocked(int flags) {
        // TODO: (b/141703197) Fix later by passing info to service.
        if ((flags & FLAG_PASSWORD_INPUT_TYPE) != 0) {
            return null;
        }

        // Check if Smart Suggestions is supported...
        final @SmartSuggestionMode int supportedModes = mService
                .getSupportedSmartSuggestionModesLocked();
        if (supportedModes == 0) {
            if (sVerbose) Slog.v(TAG, "triggerAugmentedAutofillLocked(): no supported modes");
            return null;
        }

        // ...then if the service is set for the user

        final RemoteAugmentedAutofillService remoteService = mService
                .getRemoteAugmentedAutofillServiceLocked();
        if (remoteService == null) {
            if (sVerbose) Slog.v(TAG, "triggerAugmentedAutofillLocked(): no service for user");
            return null;
        }

        // Define which mode will be used
        final int mode;
        if ((supportedModes & FLAG_SMART_SUGGESTION_SYSTEM) != 0) {
            mode = FLAG_SMART_SUGGESTION_SYSTEM;
        } else {
            Slog.w(TAG, "Unsupported Smart Suggestion mode: " + supportedModes);
            return null;
        }

        if (mCurrentViewId == null) {
            Slog.w(TAG, "triggerAugmentedAutofillLocked(): no view currently focused");
            return null;
        }

        final boolean isAllowlisted = mService
                .isWhitelistedForAugmentedAutofillLocked(mComponentName);

        if (!isAllowlisted) {
            if (sVerbose) {
                Slog.v(TAG, "triggerAugmentedAutofillLocked(): "
                        + ComponentName.flattenToShortString(mComponentName) + " not whitelisted ");
            }
            logAugmentedAutofillRequestLocked(mode, remoteService.getComponentName(),
                    mCurrentViewId, isAllowlisted, /* isInline= */ null);
            return null;
        }

        if (sVerbose) {
            Slog.v(TAG, "calling Augmented Autofill Service ("
                    + ComponentName.flattenToShortString(remoteService.getComponentName())
                    + ") on view " + mCurrentViewId + " using suggestion mode "
                    + getSmartSuggestionModeToString(mode)
                    + " when server returned null for session " + this.id);
        }
        // Log FillRequest for Augmented Autofill.
        mFillRequestEventLogger.startLogForNewRequest();
        mRequestCount++;
        mFillRequestEventLogger.maybeSetAppPackageUid(uid);
        mFillRequestEventLogger.maybeSetFlags(mFlags);
        mFillRequestEventLogger.maybeSetRequestId(AUGMENTED_AUTOFILL_REQUEST_ID);
        mFillRequestEventLogger.maybeSetIsAugmented(true);
        mFillRequestEventLogger.logAndEndEvent();

        final ViewState viewState = mViewStates.get(mCurrentViewId);
        viewState.setState(ViewState.STATE_TRIGGERED_AUGMENTED_AUTOFILL);
        final AutofillValue currentValue = viewState.getCurrentValue();

        if (mAugmentedRequestsLogs == null) {
            mAugmentedRequestsLogs = new ArrayList<>();
        }
        final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_AUGMENTED_REQUEST,
                remoteService.getComponentName().getPackageName());
        mAugmentedRequestsLogs.add(log);

        final AutofillId focusedId = mCurrentViewId;

        final Consumer<InlineSuggestionsRequest> requestAugmentedAutofill =
                new AugmentedAutofillInlineSuggestionRequestConsumer(
                        this, focusedId, isAllowlisted, mode, currentValue);

        // When the inline suggestion render service is available and the view is focused, there
        // are 3 cases when augmented autofill should ask IME for inline suggestion request,
        // because standard autofill flow didn't:
        // 1. the field is augmented autofill only (when standard autofill provider is None or
        // when it returns null response)
        // 2. standard autofill provider doesn't support inline suggestion
        // 3. we re-entered the autofill session and standard autofill was not re-triggered, this is
        //    recognized by seeing mExpiredResponse == true
        final RemoteInlineSuggestionRenderService remoteRenderService =
                mService.getRemoteInlineSuggestionRenderServiceLocked();
        if (remoteRenderService != null
                && (mSessionFlags.mAugmentedAutofillOnly
                        || !mSessionFlags.mInlineSupportedByService
                        || mSessionFlags.mExpiredResponse)
                && (isViewFocusedLocked(flags) || isRequestSupportFillDialog(flags))) {
            if (sDebug) Slog.d(TAG, "Create inline request for augmented autofill");
            remoteRenderService.getInlineSuggestionsRendererInfo(
                    new RemoteCallback(
                            new AugmentedAutofillInlineSuggestionRendererOnResultListener(
                                    this, focusedId, requestAugmentedAutofill),
                            mHandler));
        } else {
            requestAugmentedAutofill.accept(
                    mInlineSessionController.getInlineSuggestionsRequestLocked().orElse(null));
        }
        if (mAugmentedAutofillDestroyer == null) {
            mAugmentedAutofillDestroyer = remoteService::onDestroyAutofillWindowsRequest;
        }
        return mAugmentedAutofillDestroyer;
    }

    private static class AugmentedAutofillInlineSuggestionRendererOnResultListener
            implements RemoteCallback.OnResultListener {

        WeakReference<Session> mSessionWeakRef;
        final AutofillId mFocusedId;
        Consumer<InlineSuggestionsRequest> mRequestAugmentedAutofill;

        AugmentedAutofillInlineSuggestionRendererOnResultListener(
                Session session,
                AutofillId focussedId,
                Consumer<InlineSuggestionsRequest> requestAugmentedAutofill) {
            mSessionWeakRef = new WeakReference<>(session);
            mFocusedId = focussedId;
            mRequestAugmentedAutofill = requestAugmentedAutofill;
        }

        @Override
        public void onResult(@Nullable Bundle result) {
            Session session = mSessionWeakRef.get();

            if (logIfSessionNull(
                    session, "AugmentedAutofillInlineSuggestionRendererOnResultListener:")) {
                return;
            }
            synchronized (session.mLock) {
                session.mInlineSessionController.onCreateInlineSuggestionsRequestLocked(
                        mFocusedId, /*requestConsumer=*/ mRequestAugmentedAutofill,
                        result);
            }
        }
    }

    private static class AugmentedAutofillInlineSuggestionRequestConsumer
            implements Consumer<InlineSuggestionsRequest> {

        WeakReference<Session> mSessionWeakRef;
        final AutofillId mFocusedId;
        final boolean mIsAllowlisted;
        final int mMode;
        final AutofillValue mCurrentValue;

        AugmentedAutofillInlineSuggestionRequestConsumer(
                Session session,
                AutofillId focussedId,
                boolean isAllowlisted,
                int mode,
                AutofillValue currentValue) {
            mSessionWeakRef = new WeakReference<>(session);
            mFocusedId = focussedId;
            mIsAllowlisted = isAllowlisted;
            mMode = mode;
            mCurrentValue = currentValue;

        }
        @Override
        public void accept(InlineSuggestionsRequest inlineSuggestionsRequest) {
            Session session = mSessionWeakRef.get();

            if (logIfSessionNull(
                    session, "AugmentedAutofillInlineSuggestionRequestConsumer:")) {
                return;
            }
            session.onAugmentedAutofillInlineSuggestionAccept(
                    inlineSuggestionsRequest, mFocusedId, mIsAllowlisted, mMode, mCurrentValue);

        }
    }

    private static class AugmentedAutofillInlineSuggestionsResponseCallback
            implements Function<InlineFillUi, Boolean> {

        WeakReference<Session> mSessionWeakRef;

        AugmentedAutofillInlineSuggestionsResponseCallback(Session session) {
            this.mSessionWeakRef = new WeakReference<>(session);
        }

        @Override
        public Boolean apply(InlineFillUi inlineFillUi) {
            Session session = mSessionWeakRef.get();

            if (logIfSessionNull(
                    session, "AugmentedAutofillInlineSuggestionsResponseCallback:")) {
                return false;
            }

            synchronized (session.mLock) {
                return session.mInlineSessionController.setInlineFillUiLocked(inlineFillUi);
            }
        }
    }

    private static class AugmentedAutofillErrorCallback implements Runnable {

        WeakReference<Session> mSessionWeakRef;

        AugmentedAutofillErrorCallback(Session session) {
            this.mSessionWeakRef = new WeakReference<>(session);
        }

        @Override
        public void run() {
            Session session = mSessionWeakRef.get();

            if (logIfSessionNull(session, "AugmentedAutofillErrorCallback:")) {
                return;
            }
            session.onAugmentedAutofillErrorCallback();
        }
    }

    /**
     * If the session is null or has been destroyed, log the error msg, and return true.
     * This is a helper function intended to be called when de-referencing from a weak reference.
     * @param session
     * @param logPrefix
     * @return true if the session is null, false otherwise.
     */
    private static boolean logIfSessionNull(Session session, String logPrefix) {
        if (session == null) {
            Slog.wtf(TAG, logPrefix + " Session null");
            return true;
        }
        if (session.mDestroyed) {
            // TODO: Update this to return in this block. We aren't doing this to preserve the
            //  behavior, but can be modified once we have more time to soak the changes.
            Slog.w(TAG, logPrefix + " Session destroyed, but following through");
            // Follow-through
        }
        return false;
    }

    private void onAugmentedAutofillInlineSuggestionAccept(
            InlineSuggestionsRequest inlineSuggestionsRequest,
            AutofillId focussedId,
            boolean isAllowlisted,
            int mode,
            AutofillValue currentValue) {
        synchronized (mLock) {
            final RemoteAugmentedAutofillService remoteService =
                    mService.getRemoteAugmentedAutofillServiceLocked();
            logAugmentedAutofillRequestLocked(mode, remoteService.getComponentName(),
                    focussedId, isAllowlisted, inlineSuggestionsRequest != null);
            remoteService.onRequestAutofillLocked(id, mClient,
                    taskId, mComponentName, mActivityToken,
                    AutofillId.withoutSession(focussedId), currentValue,
                    inlineSuggestionsRequest,
                    new AugmentedAutofillInlineSuggestionsResponseCallback(this),
                    new AugmentedAutofillErrorCallback(this),
                    mService.getRemoteInlineSuggestionRenderServiceLocked(), userId);
        }
    }

    private void onAugmentedAutofillErrorCallback() {
        synchronized (mLock) {
            cancelAugmentedAutofillLocked();

            // Also cancel augmented in IME
            mInlineSessionController.setInlineFillUiLocked(
                    InlineFillUi.emptyUi(mCurrentViewId));
        }
    }

    @GuardedBy("mLock")
    private void cancelAugmentedAutofillLocked() {
        final RemoteAugmentedAutofillService remoteService = mService
                .getRemoteAugmentedAutofillServiceLocked();
        if (remoteService == null) {
            Slog.w(TAG, "cancelAugmentedAutofillLocked(): no service for user");
            return;
        }
        if (sVerbose) Slog.v(TAG, "cancelAugmentedAutofillLocked() on " + mCurrentViewId);
        remoteService.onDestroyAutofillWindowsRequest();
    }

    @GuardedBy("mLock")
    private void processResponseLocked(@NonNull FillResponse newResponse,
            @Nullable Bundle newClientState, int flags) {
        // Make sure we are hiding the UI which will be shown
        // only if handling the current response requires it.
        mUi.hideAll(this);

        if ((newResponse.getFlags() & FillResponse.FLAG_DELAY_FILL) == 0) {
            Slog.d(TAG, "Service did not request to wait for delayed fill response.");
            unregisterDelayedFillBroadcastLocked();
        }

        final int requestId = newResponse.getRequestId();
        if (sVerbose) {
            Slog.v(TAG, "processResponseLocked(): mCurrentViewId=" + mCurrentViewId
                    + ",flags=" + flags + ", reqId=" + requestId + ", resp=" + newResponse
                    + ",newClientState=" + newClientState);
        }

        if (mResponses == null) {
            // Set initial capacity as 2 to handle cases where service always requires auth.
            // TODO: add a metric for number of responses set by server, so we can use its average
            // as the initial array capacity.
            mResponses = new SparseArray<>(2);
        }
        mResponses.put(requestId, newResponse);
        mClientState = newClientState != null ? newClientState : newResponse.getClientState();

        boolean webviewRequestedCredman = newClientState != null && newClientState.getBoolean(
                WEBVIEW_REQUESTED_CREDENTIAL_KEY, false);
        List<Dataset> datasetList = newResponse.getDatasets();

        mPresentationStatsEventLogger.maybeSetWebviewRequestedCredential(webviewRequestedCredman);
        mPresentationStatsEventLogger.maybeSetFieldClassificationRequestId(sIdCounterForPcc.get());
        mPresentationStatsEventLogger.maybeSetAvailableCount(datasetList, mCurrentViewId);
        mFillResponseEventLogger.maybeSetDatasetsCountAfterPotentialPccFiltering(datasetList);

        setViewStatesLocked(newResponse, ViewState.STATE_FILLABLE, /* clearResponse= */ false,
                /* isPrimary= */ true);
        updateFillDialogTriggerIdsLocked();
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
    @GuardedBy("mLock")
    private void setViewStatesLocked(FillResponse response, int state, boolean clearResponse,
                                     boolean isPrimary) {
        final List<Dataset> datasets = response.getDatasets();
        if (datasets != null && !datasets.isEmpty()) {
            for (int i = 0; i < datasets.size(); i++) {
                final Dataset dataset = datasets.get(i);
                if (dataset == null) {
                    Slog.w(TAG, "Ignoring null dataset on " + datasets);
                    continue;
                }
                setViewStatesLocked(response, dataset, state, clearResponse, isPrimary);
            }
        } else if (response.getAuthentication() != null) {
            for (AutofillId autofillId : response.getAuthenticationIds()) {
                final ViewState viewState = createOrUpdateViewStateLocked(autofillId, state, null);
                if (!clearResponse) {
                    viewState.setResponse(response, isPrimary);
                } else {
                    viewState.setResponse(null, isPrimary);
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
     * Sets the state and response of all views in the given dataset.
     */
    @GuardedBy("mLock")
    private void setViewStatesLocked(@Nullable FillResponse response, @NonNull Dataset dataset,
            int state, boolean clearResponse, boolean isPrimary) {
        final ArrayList<AutofillId> ids = dataset.getFieldIds();
        final ArrayList<AutofillValue> values = dataset.getFieldValues();
        for (int j = 0; j < ids.size(); j++) {
            final AutofillId id = ids.get(j);
            final AutofillValue value = values.get(j);
            final ViewState viewState = createOrUpdateViewStateLocked(id, state, value);
            final String datasetId = dataset.getId();
            if (datasetId != null) {
                viewState.setDatasetId(datasetId);
            }
            if (clearResponse) {
                viewState.setResponse(null, isPrimary);
            } else if (response != null) {
                viewState.setResponse(response, isPrimary);
            }
        }
    }

    @GuardedBy("mLock")
    private ViewState createOrUpdateViewStateLocked(@NonNull AutofillId id, int state,
            @Nullable AutofillValue value) {
        ViewState viewState = mViewStates.get(id);
        if (viewState != null)  {
            viewState.setState(state);
        } else {
            viewState = new ViewState(id, this, state, mIsPrimaryCredential);
            if (sVerbose) {
                Slog.v(TAG, "Adding autofillable view with id " + id + " and state " + state);
            }
            viewState.setCurrentValue(findValueLocked(id));
            mViewStates.put(id, viewState);
        }
        if ((state & ViewState.STATE_AUTOFILLED) != 0) {
            viewState.setAutofilledValue(value);
        }
        return viewState;
    }

    void autoFill(int requestId, int datasetIndex, Dataset dataset, boolean generateEvent,
            int uiType) {
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
            // Selected dataset id is logged regardless of authentication result.
            mPresentationStatsEventLogger.maybeSetSelectedDatasetId(datasetIndex);
            mPresentationStatsEventLogger.maybeSetSelectedDatasetPickReason(
                dataset.getEligibleReason());
            // Autofill it directly...
            if (dataset.getAuthentication() == null) {
                if (generateEvent) {
                    mService.logDatasetSelected(dataset.getId(), id, mClientState, uiType);
                }
                if (mCurrentViewId != null) {
                    mInlineSessionController.hideInlineSuggestionsUiLocked(mCurrentViewId);
                }
                autoFillApp(dataset);
                return;
            }

            // ...or handle authentication.
            mService.logDatasetAuthenticationSelected(dataset.getId(), id, mClientState, uiType);
            mPresentationStatsEventLogger.maybeSetAuthenticationType(
                AUTHENTICATION_TYPE_DATASET_AUTHENTICATION);
            // does not matter the value of isPrimary because null response won't be overridden.
            setViewStatesLocked(null, dataset, ViewState.STATE_WAITING_DATASET_AUTH,
                    /* clearResponse= */ false, /* isPrimary= */ true);
            final Intent fillInIntent;
            if (dataset.getCredentialFillInIntent() != null && Flags.autofillCredmanIntegration()) {
                Slog.d(TAG, "Setting credential fill intent");
                fillInIntent = dataset.getCredentialFillInIntent();
            } else {
                fillInIntent = createAuthFillInIntentLocked(requestId, mClientState);
            }

            if (fillInIntent == null) {
                forceRemoveFromServiceLocked();
                return;
            }
            final int authenticationId = AutofillManager.makeAuthenticationId(requestId,
                    datasetIndex);
            startAuthentication(authenticationId, dataset.getAuthentication(), fillInIntent,
                    /* authenticateInline= */false);

        }
    }

    // TODO: this should never be null, but we got at least one occurrence, probably due to a race.
    @GuardedBy("mLock")
    @Nullable
    private Intent createAuthFillInIntentLocked(int requestId, Bundle extras) {
        final Intent fillInIntent = new Intent();

        final FillContext context = getFillContextByRequestIdLocked(requestId);

        if (context == null) {
            wtf(null, "createAuthFillInIntentLocked(): no FillContext. requestId=%d; mContexts=%s",
                    requestId, mContexts);
            return null;
        }
        if (mLastInlineSuggestionsRequest != null
                && mLastInlineSuggestionsRequest.first == requestId) {
            fillInIntent.putExtra(AutofillManager.EXTRA_INLINE_SUGGESTIONS_REQUEST,
                    mLastInlineSuggestionsRequest.second);
        }
        fillInIntent.putExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, context.getStructure());
        fillInIntent.putExtra(AutofillManager.EXTRA_CLIENT_STATE, extras);
        return fillInIntent;
    }

    @NonNull
    Consumer<InlineSuggestionsRequest> inlineSuggestionsRequestCacheDecorator(
            @NonNull Consumer<InlineSuggestionsRequest> consumer, int requestId) {
        return inlineSuggestionsRequest -> {
            consumer.accept(inlineSuggestionsRequest);
            synchronized (mLock) {
                mLastInlineSuggestionsRequest = Pair.create(requestId, inlineSuggestionsRequest);
            }
        };
    }

    private int getDetectionPreferenceForLogging() {
        if (mService.isPccClassificationEnabled()) {
            if (mService.getMaster().preferProviderOverPcc()) {
                return DETECTION_PREFER_AUTOFILL_PROVIDER;
            }
            return DETECTION_PREFER_PCC;
        }
        return DETECTION_PREFER_UNKNOWN;
    }

    private void startNewEventForPresentationStatsEventLogger() {
        synchronized (mLock) {
            mPresentationStatsEventLogger.startNewEvent();
            mPresentationStatsEventLogger.maybeSetDetectionPreference(
                    getDetectionPreferenceForLogging());
            mPresentationStatsEventLogger.maybeSetAutofillServiceUid(getAutofillServiceUid());
        }
    }

    private void startAuthentication(int authenticationId, IntentSender intent,
            Intent fillInIntent, boolean authenticateInline) {
        try {
            synchronized (mLock) {
                mClient.authenticate(id, authenticationId, intent, fillInIntent,
                        authenticateInline);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Error launching auth intent", e);
        }
    }

    /**
     * The result of checking whether to show the save dialog, when session can be saved.
     *
     * @hide
     */
    static final class SaveResult {
        /**
         * Whether to record the save dialog has been shown.
         */
        private boolean mLogSaveShown;

        /**
         * Whether to remove the session.
         */
        private boolean mRemoveSession;

        /**
         * The reason why a save dialog was not shown.
         */
        @NoSaveReason private int mSaveDialogNotShowReason;

        SaveResult(boolean logSaveShown, boolean removeSession,
                @NoSaveReason int saveDialogNotShowReason) {
            mLogSaveShown = logSaveShown;
            mRemoveSession = removeSession;
            mSaveDialogNotShowReason = saveDialogNotShowReason;
        }

        /**
         * Returns whether to record the save dialog has been shown.
         *
         * @return Whether to record the save dialog has been shown.
         */
        public boolean isLogSaveShown() {
            return mLogSaveShown;
        }

        /**
         * Sets whether to record the save dialog has been shown.
         *
         * @param logSaveShown Whether to record the save dialog has been shown.
         */
        public void setLogSaveShown(boolean logSaveShown) {
            mLogSaveShown = logSaveShown;
        }

        /**
         * Returns whether to remove the session.
         *
         * @return Whether to remove the session.
         */
        public boolean isRemoveSession() {
            return mRemoveSession;
        }

        /**
         * Sets whether to remove the session.
         *
         * @param removeSession Whether to remove the session.
         */
        public void setRemoveSession(boolean removeSession) {
            mRemoveSession = removeSession;
        }

        /**
         * Returns the reason why a save dialog was not shown.
         *
         * @return The reason why a save dialog was not shown.
         */
        @NoSaveReason
        public int getNoSaveUiReason() {
            return mSaveDialogNotShowReason;
        }

        /**
         * Sets the reason why a save dialog was not shown.
         *
         * @param saveDialogNotShowReason The reason why a save dialog was not shown.
         */
        public void setSaveDialogNotShowReason(@NoSaveReason int saveDialogNotShowReason) {
            mSaveDialogNotShowReason = saveDialogNotShowReason;
        }

        @Override
        public String toString() {
            return "SaveResult: [logSaveShown=" + mLogSaveShown
                    + ", removeSession=" + mRemoveSession
                    + ", saveDialogNotShowReason=" + mSaveDialogNotShowReason + "]";
        }
    }

    /**
     * Class maintaining the state of the requests to
     * {@link android.service.assist.classification.FieldClassificationService}.
     */
    private static final class ClassificationState {

        /**
         * Initial state indicating that the request for classification hasn't been triggered yet.
         */
        private static final int STATE_INITIAL = 1;
        /**
         * Assist request has been triggered, but awaiting response.
         */
        private static final int STATE_PENDING_ASSIST_REQUEST = 2;
        /**
         * Classification request has been triggered, but awaiting response.
         */
        private static final int STATE_PENDING_REQUEST = 3;
        /**
         * Classification response has been received.
         */
        private static final int STATE_RESPONSE = 4;
        /**
         * Classification state has been invalidated, and the last response may no longer be valid.
         * This could occur due to various reasons like views changing their layouts, becoming
         * visible or invisible, thereby rendering previous response potentially inaccurate or
         * incomplete.
         */
        private static final int STATE_INVALIDATED = 5;

        @IntDef(prefix = { "STATE_" }, value = {
                STATE_INITIAL,
                STATE_PENDING_ASSIST_REQUEST,
                STATE_PENDING_REQUEST,
                STATE_RESPONSE,
                STATE_INVALIDATED
        })
        @Retention(RetentionPolicy.SOURCE)
        @interface ClassificationRequestState{}

        @GuardedBy("mLock")
        private @ClassificationRequestState int mState = STATE_INITIAL;

        @GuardedBy("mLock")
        private FieldClassificationRequest mPendingFieldClassificationRequest;

        @GuardedBy("mLock")
        private FieldClassificationResponse mLastFieldClassificationResponse;

        @GuardedBy("mLock")
        private ArrayMap<AutofillId, Set<String>> mClassificationHintsMap;

        @GuardedBy("mLock")
        private ArrayMap<AutofillId, Set<String>> mClassificationGroupHintsMap;

        @GuardedBy("mLock")
        private ArrayMap<AutofillId, Set<String>> mClassificationCombinedHintsMap;

        /**
         * Typically, there would be a 1:1 mapping. However, in certain cases, we may have a hint
         * being applicable to many types. An example of this being new/change password forms,
         * where you need to confirm the passward twice.
         */
        @GuardedBy("mLock")
        private ArrayMap<String, Set<AutofillId>> mHintsToAutofillIdMap;

        /**
         * Group hints are expected to have a 1:many mapping. For example, different credit card
         * fields (creditCardNumber, expiry, cvv) will all map to the same group hints.
         */
        @GuardedBy("mLock")
        private ArrayMap<String, Set<AutofillId>> mGroupHintsToAutofillIdMap;

        @GuardedBy("mLock")
        private String stateToString() {
            switch (mState) {
                case STATE_INITIAL:
                    return "STATE_INITIAL";
                case STATE_PENDING_ASSIST_REQUEST:
                    return "STATE_PENDING_ASSIST_REQUEST";
                case STATE_PENDING_REQUEST:
                    return "STATE_PENDING_REQUEST";
                case STATE_RESPONSE:
                    return "STATE_RESPONSE";
                case STATE_INVALIDATED:
                    return "STATE_INVALIDATED";
                default:
                    return "UNKNOWN_CLASSIFICATION_STATE_" + mState;
            }
        }

        /**
         * Process the response received.
         * @return true if the response was processed, false otherwise. If there wasn't any
         * response, yet this function was called, it would return false.
         */
        @GuardedBy("mLock")
        private boolean processResponse() {
            if (mClassificationHintsMap != null && !mClassificationHintsMap.isEmpty()) {
                // Already processed, so return
                return true;
            }

            FieldClassificationResponse response = mLastFieldClassificationResponse;
            if (response == null) return false;

            mClassificationHintsMap = new ArrayMap<>();
            mClassificationGroupHintsMap = new ArrayMap<>();
            mHintsToAutofillIdMap = new ArrayMap<>();
            mGroupHintsToAutofillIdMap = new ArrayMap<>();
            mClassificationCombinedHintsMap = new ArrayMap<>();
            Set<android.service.assist.classification.FieldClassification> classifications =
                    response.getClassifications();

            for (android.service.assist.classification.FieldClassification classification :
                    classifications) {
                AutofillId id = classification.getAutofillId();
                Set<String> hintDetections = classification.getHints();
                Set<String> groupHintsDetections = classification.getGroupHints();
                ArraySet<String> combinedHints = new ArraySet<>(hintDetections);
                mClassificationHintsMap.put(id, hintDetections);
                if (groupHintsDetections != null) {
                    mClassificationGroupHintsMap.put(id, groupHintsDetections);
                    combinedHints.addAll(groupHintsDetections);
                }
                mClassificationCombinedHintsMap.put(id, combinedHints);

                processDetections(hintDetections, id, mHintsToAutofillIdMap);
                processDetections(groupHintsDetections, id, mGroupHintsToAutofillIdMap);
            }
            return true;
        }

        @GuardedBy("mLock")
        private static void processDetections(Set<String> detections, AutofillId id,
                ArrayMap<String, Set<AutofillId>> currentMap) {
            for (String detection : detections) {
                Set<AutofillId> autofillIds;
                if (currentMap.containsKey(detection)) {
                    autofillIds = currentMap.get(detection);
                } else {
                    autofillIds = new ArraySet<>();
                }
                autofillIds.add(id);
                currentMap.put(detection, autofillIds);
            }
        }

        @GuardedBy("mLock")
        private void invalidateState() {
            mState = STATE_INVALIDATED;
        }

        @GuardedBy("mLock")
        private void updatePendingAssistData() {
            mState = STATE_PENDING_ASSIST_REQUEST;
        }

        @GuardedBy("mLock")
        private void updatePendingRequest() {
            mState = STATE_PENDING_REQUEST;
        }

        @GuardedBy("mLock")
        private void updateResponseReceived(FieldClassificationResponse response) {
            mState = STATE_RESPONSE;
            mLastFieldClassificationResponse = response;
            mPendingFieldClassificationRequest = null;
            processResponse();
        }

        @GuardedBy("mLock")
        private void onAssistStructureReceived(AssistStructure structure) {
            mState = STATE_PENDING_REQUEST;
            mPendingFieldClassificationRequest = new FieldClassificationRequest(structure);
        }

        @GuardedBy("mLock")
        private void onFieldClassificationRequestSent() {
            mState = STATE_PENDING_REQUEST;
            mPendingFieldClassificationRequest = null;
        }

        @GuardedBy("mLock")
        private boolean shouldTriggerRequest() {
            return mState == STATE_INITIAL || mState == STATE_INVALIDATED;
        }

        @GuardedBy("mLock")
        @Override
        public String toString() {
            return "ClassificationState: ["
                    + "state=" + stateToString()
                    + ", mPendingFieldClassificationRequest=" + mPendingFieldClassificationRequest
                    + ", mLastFieldClassificationResponse=" + mLastFieldClassificationResponse
                    + ", mClassificationHintsMap=" + mClassificationHintsMap
                    + ", mClassificationGroupHintsMap=" + mClassificationGroupHintsMap
                    + ", mHintsToAutofillIdMap=" + mHintsToAutofillIdMap
                    + ", mGroupHintsToAutofillIdMap=" + mGroupHintsToAutofillIdMap
                    + "]";
        }

    }

    @Override
    public String toString() {
        return "Session: [id=" + id + ", component=" + mComponentName
                + ", state=" + sessionStateAsString(mSessionState) + "]";
    }

    @GuardedBy("mLock")
    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";
        pw.print(prefix); pw.print("id: "); pw.println(id);
        pw.print(prefix); pw.print("uid: "); pw.println(uid);
        pw.print(prefix); pw.print("taskId: "); pw.println(taskId);
        pw.print(prefix); pw.print("flags: "); pw.println(mFlags);
        pw.print(prefix); pw.print("displayId: "); pw.println(mContext.getDisplayId());
        pw.print(prefix); pw.print("state: "); pw.println(sessionStateAsString(mSessionState));
        pw.print(prefix); pw.print("mComponentName: "); pw.println(mComponentName);
        pw.print(prefix); pw.print("mActivityToken: "); pw.println(mActivityToken);
        pw.print(prefix); pw.print("mStartTime: "); pw.println(mStartTime);
        pw.print(prefix); pw.print("Time to show UI: ");
        if (mUiShownTime == 0) {
            pw.println("N/A");
        } else {
            TimeUtils.formatDuration(mUiShownTime - mStartTime, pw);
            pw.println();
        }
        final int requestLogsSizes = mRequestLogs.size();
        pw.print(prefix); pw.print("mSessionLogs: "); pw.println(requestLogsSizes);
        for (int i = 0; i < requestLogsSizes; i++) {
            final int requestId = mRequestLogs.keyAt(i);
            final LogMaker log = mRequestLogs.valueAt(i);
            pw.print(prefix2); pw.print('#'); pw.print(i); pw.print(": req=");
            pw.print(requestId); pw.print(", log=" ); dumpRequestLog(pw, log); pw.println();
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
        pw.print(prefix); pw.print("mDestroyed: "); pw.println(mDestroyed);
        pw.print(prefix); pw.print("mShowingSaveUi: "); pw.println(mSessionFlags.mShowingSaveUi);
        pw.print(prefix); pw.print("mPendingSaveUi: "); pw.println(mPendingSaveUi);
        final int numberViews = mViewStates.size();
        pw.print(prefix); pw.print("mViewStates size: "); pw.println(mViewStates.size());
        for (int i = 0; i < numberViews; i++) {
            pw.print(prefix); pw.print("ViewState at #"); pw.println(i);
            mViewStates.valueAt(i).dump(prefix2, pw);
        }

        pw.print(prefix); pw.print("mContexts: " );
        if (mContexts != null) {
            int numContexts = mContexts.size();
            for (int i = 0; i < numContexts; i++) {
                FillContext context = mContexts.get(i);

                pw.print(prefix2); pw.print(context);
                if (sVerbose) {
                    pw.println("AssistStructure dumped at logcat)");

                    // TODO: add method on AssistStructure to dump on pw
                    context.getStructure().dump(false);
                }
            }
        } else {
            pw.println("null");
        }

        pw.print(prefix); pw.print("mHasCallback: "); pw.println(mHasCallback);
        if (mClientState != null) {
            pw.print(prefix); pw.print("mClientState: "); pw.print(mClientState.getSize()); pw
                .println(" bytes");
        }
        pw.print(prefix); pw.print("mCompatMode: "); pw.println(mCompatMode);
        pw.print(prefix); pw.print("mUrlBar: ");
        if (mUrlBar == null) {
            pw.println("N/A");
        } else {
            pw.print("id="); pw.print(mUrlBar.getAutofillId());
            pw.print(" domain="); pw.print(mUrlBar.getWebDomain());
            pw.print(" text="); Helper.printlnRedactedText(pw, mUrlBar.getText());
        }
        pw.print(prefix); pw.print("mSaveOnAllViewsInvisible: "); pw.println(
                mSaveOnAllViewsInvisible);
        pw.print(prefix); pw.print("mSelectedDatasetIds: "); pw.println(mSelectedDatasetIds);
        if (mSessionFlags.mAugmentedAutofillOnly) {
            pw.print(prefix); pw.println("For Augmented Autofill Only");
        }
        if (mSessionFlags.mFillDialogDisabled) {
            pw.print(prefix); pw.println("Fill Dialog disabled");
        }
        if (mLastFillDialogTriggerIds != null) {
            pw.print(prefix); pw.println("Last Fill Dialog trigger ids: ");
            pw.println(mSelectedDatasetIds);
        }
        if (mAugmentedAutofillDestroyer != null) {
            pw.print(prefix); pw.println("has mAugmentedAutofillDestroyer");
        }
        if (mAugmentedRequestsLogs != null) {
            pw.print(prefix); pw.print("number augmented requests: ");
            pw.println(mAugmentedRequestsLogs.size());
        }

        if (mAugmentedAutofillableIds != null) {
            pw.print(prefix); pw.print("mAugmentedAutofillableIds: ");
            pw.println(mAugmentedAutofillableIds);
        }
        if (mRemoteFillService != null) {
            mRemoteFillService.dump(prefix, pw);
        }
    }

    private static void dumpRequestLog(@NonNull PrintWriter pw, @NonNull LogMaker log) {
        pw.print("CAT="); pw.print(log.getCategory());
        pw.print(", TYPE=");
        final int type = log.getType();
        switch (type) {
            case MetricsEvent.TYPE_SUCCESS: pw.print("SUCCESS"); break;
            case MetricsEvent.TYPE_FAILURE: pw.print("FAILURE"); break;
            case MetricsEvent.TYPE_CLOSE: pw.print("CLOSE"); break;
            default: pw.print("UNSUPPORTED");
        }
        pw.print('('); pw.print(type); pw.print(')');
        pw.print(", PKG="); pw.print(log.getPackageName());
        pw.print(", SERVICE="); pw.print(log
                .getTaggedData(MetricsEvent.FIELD_AUTOFILL_SERVICE));
        pw.print(", ORDINAL="); pw.print(log
                .getTaggedData(MetricsEvent.FIELD_AUTOFILL_REQUEST_ORDINAL));
        dumpNumericValue(pw, log, "FLAGS", MetricsEvent.FIELD_AUTOFILL_FLAGS);
        dumpNumericValue(pw, log, "NUM_DATASETS", MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS);
        dumpNumericValue(pw, log, "UI_LATENCY", MetricsEvent.FIELD_AUTOFILL_DURATION);
        final int authStatus =
                getNumericValue(log, MetricsEvent.FIELD_AUTOFILL_AUTHENTICATION_STATUS);
        if (authStatus != 0) {
            pw.print(", AUTH_STATUS=");
            switch (authStatus) {
                case MetricsEvent.AUTOFILL_AUTHENTICATED:
                    pw.print("AUTHENTICATED"); break;
                case MetricsEvent.AUTOFILL_DATASET_AUTHENTICATED:
                    pw.print("DATASET_AUTHENTICATED"); break;
                case MetricsEvent.AUTOFILL_INVALID_AUTHENTICATION:
                    pw.print("INVALID_AUTHENTICATION"); break;
                case MetricsEvent.AUTOFILL_INVALID_DATASET_AUTHENTICATION:
                    pw.print("INVALID_DATASET_AUTHENTICATION"); break;
                default: pw.print("UNSUPPORTED");
            }
            pw.print('('); pw.print(authStatus); pw.print(')');
        }
        dumpNumericValue(pw, log, "FC_IDS",
                MetricsEvent.FIELD_AUTOFILL_NUM_FIELD_CLASSIFICATION_IDS);
        dumpNumericValue(pw, log, "COMPAT_MODE",
                MetricsEvent.FIELD_AUTOFILL_COMPAT_MODE);
    }

    private static void dumpNumericValue(@NonNull PrintWriter pw, @NonNull LogMaker log,
            @NonNull String field, int tag) {
        final int value = getNumericValue(log, tag);
        if (value != 0) {
            pw.print(", "); pw.print(field); pw.print('='); pw.print(value);
        }
    }

    void sendCredentialManagerResponseToApp(@Nullable GetCredentialResponse response,
            @Nullable GetCredentialException exception, @NonNull AutofillId viewId) {
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#sendCredentialManagerResponseToApp() rejected "
                        + "- session: " + id + " destroyed");
                return;
            }
            try {
                final ViewState viewState = mViewStates.get(viewId);
                if (mService.getMaster().getIsFillFieldsFromCurrentSessionOnly()
                        && viewState != null && viewState.id.getSessionId() != id) {
                    if (sVerbose) {
                        Slog.v(TAG, "Skipping sending credential response to view: "
                                + viewId + " as it isn't part of the current session: " + id);
                    }
                }
                if (exception != null) {
                    if (viewId.isVirtualInt()) {
                        sendResponseToViewNode(viewId, /*response=*/ null, exception);
                    } else {
                        mClient.onGetCredentialException(id, viewId, exception.getType(),
                                exception.getMessage());
                    }
                } else if (response != null) {
                    if (viewId.isVirtualInt()) {
                        sendResponseToViewNode(viewId, response, /*exception=*/ null);
                    } else {
                        mClient.onGetCredentialResponse(id, viewId, response);
                    }
                } else {
                    Slog.w(TAG, "sendCredentialManagerResponseToApp called with null response"
                            + "and exception");
                }
            } catch (RemoteException e) {
                Slog.w(TAG, "Error sending credential response to activity: " + e);
            }
        }
    }

    @GuardedBy("mLock")
    private void sendResponseToViewNode(AutofillId viewId, GetCredentialResponse response,
            GetCredentialException exception) {
        ViewNode viewNode = getViewNodeFromContextsLocked(viewId);
        if (viewNode != null && viewNode.getPendingCredentialCallback() != null) {
            Bundle resultData = new Bundle();
            if (response != null) {
                resultData.putParcelable(
                        CredentialProviderService.EXTRA_GET_CREDENTIAL_RESPONSE,
                        response);
                viewNode.getPendingCredentialCallback().send(SUCCESS_CREDMAN_SELECTOR,
                        resultData);
            } else if (exception != null) {
                resultData.putStringArray(
                        CredentialProviderService.EXTRA_GET_CREDENTIAL_EXCEPTION,
                        new String[] {exception.getType(), exception.getMessage()});
                viewNode.getPendingCredentialCallback().send(FAILURE_CREDMAN_SELECTOR,
                        resultData);
            }
        } else {
            Slog.w(TAG, "View node not found after GetCredentialResponse");
        }
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
                boolean hideHighlight = (entryCount == 1
                        && dataset.getFieldIds().get(0).equals(mCurrentViewId));
                for (int i = 0; i < entryCount; i++) {
                    if (dataset.getFieldValues().get(i) == null) {
                        continue;
                    }
                    final AutofillId viewId = dataset.getFieldIds().get(i);
                    final ViewState viewState = mViewStates.get(viewId);
                    if (mService.getMaster().getIsFillFieldsFromCurrentSessionOnly()
                            && viewState != null && viewState.id.getSessionId() != id) {
                        if (sVerbose) {
                            Slog.v(TAG, "Skipping filling view: " +
                                    viewId + " as it isn't part of the current session: " + id);
                        }
                        continue;
                    }
                    ids.add(viewId);
                    values.add(dataset.getFieldValues().get(i));
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
                    if (sVerbose) {
                        Slog.v(TAG, "Total views to be autofilled: " + ids.size());
                    }
                    mPresentationStatsEventLogger.maybeSetViewFillablesAndCount(ids);
                    if (sDebug) Slog.d(TAG, "autoFillApp(): the buck is on the app: " + dataset);
                    mClient.autofill(id, ids, values, hideHighlight);
                    if (dataset.getId() != null) {
                        if (mSelectedDatasetIds == null) {
                            mSelectedDatasetIds = new ArrayList<>();
                        }
                        mSelectedDatasetIds.add(dataset.getId());
                    }
                    // does not matter the value of isPrimary because null response won't be
                    // overridden.
                    setViewStatesLocked(null, dataset, ViewState.STATE_AUTOFILLED,
                            /* clearResponse= */ false, /* isPrimary= */ true);
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

    @GuardedBy("mLock")
    private void logAllEvents(@AutofillCommitReason int val) {
        if (sVerbose) {
            Slog.v(TAG, "logAllEvents(" + id + "): commitReason: " + val);
        }
        mSessionCommittedEventLogger.maybeSetCommitReason(val);
        mSessionCommittedEventLogger.maybeSetRequestCount(mRequestCount);
        mSessionCommittedEventLogger.maybeSetSessionDurationMillis(
            SystemClock.elapsedRealtime() - mStartTime);
        mFillRequestEventLogger.logAndEndEvent();
        mFillResponseEventLogger.logAndEndEvent();
        mPresentationStatsEventLogger.logAndEndEvent();
        mSaveEventLogger.logAndEndEvent();
        mSessionCommittedEventLogger.logAndEndEvent();
    }

    /**
     * Destroy this session and perform any clean up work.
     *
     * <p>Typically called in 2 scenarios:
     *
     * <ul>
     *   <li>When the session naturally finishes (i.e., from {@link #removeFromServiceLocked()}.
     *   <li>When the service hosting the session is finished (for example, because the user
     *       disabled it).
     * </ul>
     */
    @GuardedBy("mLock")
    RemoteFillService destroyLocked() {
        // Log unlogged events.
        if (sVerbose) {
            Slog.v(TAG, "destroyLocked for session: " + id);
        }
        logAllEvents(COMMIT_REASON_SESSION_DESTROYED);

        if (mDestroyed) {
            return null;
        }

        clearPendingIntentLocked();
        unregisterDelayedFillBroadcastLocked();

        unlinkClientVultureLocked();
        mUi.destroyAll(mPendingSaveUi, this, true);
        mUi.clearCallback(this);
        if (mCurrentViewId != null) {
            mInlineSessionController.destroyLocked(mCurrentViewId);
        }
        final RemoteInlineSuggestionRenderService remoteRenderService =
                mService.getRemoteInlineSuggestionRenderServiceLocked();
        if (remoteRenderService != null) {
            remoteRenderService.destroySuggestionViews(userId, id);
        }

        mDestroyed = true;

        // Log metrics
        final int totalRequests = mRequestLogs.size();
        if (totalRequests > 0) {
            if (sVerbose) Slog.v(TAG, "destroyLocked(): logging " + totalRequests + " requests");
            for (int i = 0; i < totalRequests; i++) {
                final LogMaker log = mRequestLogs.valueAt(i);
                mMetricsLogger.write(log);
            }
        }

        final int totalAugmentedRequests = mAugmentedRequestsLogs == null ? 0
                : mAugmentedRequestsLogs.size();
        if (totalAugmentedRequests > 0) {
            if (sVerbose) {
                Slog.v(TAG, "destroyLocked(): logging " + totalRequests + " augmented requests");
            }
            for (int i = 0; i < totalAugmentedRequests; i++) {
                final LogMaker log = mAugmentedRequestsLogs.get(i);
                mMetricsLogger.write(log);
            }
        }

        final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_SESSION_FINISHED)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUMBER_REQUESTS, totalRequests);
        if (totalAugmentedRequests > 0) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUMBER_AUGMENTED_REQUESTS,
                    totalAugmentedRequests);
        }
        if (mSessionFlags.mAugmentedAutofillOnly) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_AUGMENTED_ONLY, 1);
        }
        mMetricsLogger.write(log);

        return mRemoteFillService;
    }

    /**
     * Destroy this session and remove it from the service always, even if it does have a pending
     * Save UI.
     */
    @GuardedBy("mLock")
    void forceRemoveFromServiceLocked() {
        forceRemoveFromServiceLocked(AutofillManager.STATE_UNKNOWN);
    }

    @GuardedBy("mLock")
    void forceRemoveFromServiceIfForAugmentedOnlyLocked() {
        if (sVerbose) {
            Slog.v(TAG, "forceRemoveFromServiceIfForAugmentedOnlyLocked(" + this.id + "): "
                    + mSessionFlags.mAugmentedAutofillOnly);
        }
        if (!mSessionFlags.mAugmentedAutofillOnly) return;

        forceRemoveFromServiceLocked();
    }

    @GuardedBy("mLock")
    void forceRemoveFromServiceLocked(int clientState) {
        if (sVerbose) Slog.v(TAG, "forceRemoveFromServiceLocked(): " + mPendingSaveUi);

        final boolean isPendingSaveUi = isSaveUiPendingLocked();
        mPendingSaveUi = null;
        removeFromServiceLocked();
        mUi.destroyAll(mPendingSaveUi, this, false);
        if (!isPendingSaveUi) {
            try {
                mClient.setSessionFinished(clientState, /* autofillableIds= */ null);
            } catch (RemoteException e) {
                Slog.e(TAG, "Error notifying client to finish session", e);
            }
        }
        destroyAugmentedAutofillWindowsLocked();
    }

    @GuardedBy("mLock")
    void destroyAugmentedAutofillWindowsLocked() {
        if (mAugmentedAutofillDestroyer != null) {
            mAugmentedAutofillDestroyer.run();
            mAugmentedAutofillDestroyer = null;
        }
    }

    /**
     * Thread-safe version of {@link #removeFromServiceLocked()}.
     */
    private void removeFromService() {
        synchronized (mLock) {
            removeFromServiceLocked();
        }
    }

    /**
     * Destroy this session and remove it from the service, but but only if it does not have a
     * pending Save UI.
     */
    @GuardedBy("mLock")
    void removeFromServiceLocked() {
        if (sVerbose) Slog.v(TAG, "removeFromServiceLocked(" + this.id + "): " + mPendingSaveUi);
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#removeFromServiceLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        if (isSaveUiPendingLocked()) {
            Slog.i(TAG, "removeFromServiceLocked() ignored, waiting for pending save ui");
            return;
        }

        final RemoteFillService remoteFillService = destroyLocked();
        mService.removeSessionLocked(id);
        if (remoteFillService != null) {
            remoteFillService.destroy();
        }
        if (mSecondaryProviderHandler != null) {
            mSecondaryProviderHandler.destroy();
        }
        mSessionState = STATE_REMOVED;
    }

    void onPendingSaveUi(int operation, @NonNull IBinder token) {
        getUiForShowing().onPendingSaveUi(operation, token);
    }

    /**
     * Checks whether this session is hiding the Save UI to handle a custom description link for
     * a specific {@code token} created by
     * {@link PendingUi#PendingUi(IBinder, int, IAutoFillManagerClient)}.
     */
    @GuardedBy("mLock")
    boolean isSaveUiPendingForTokenLocked(@NonNull IBinder token) {
        return isSaveUiPendingLocked() && token.equals(mPendingSaveUi.getToken());
    }

    /**
     * Checks whether this session is hiding the Save UI to handle a custom description link.
     */
    @GuardedBy("mLock")
    private boolean isSaveUiPendingLocked() {
        return mPendingSaveUi != null && mPendingSaveUi.getState() == PendingUi.STATE_PENDING;
    }

    @GuardedBy("mLock")
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
                    lastResponseId = mResponses.keyAt(i);
                }
            }
        }
        return lastResponseIdx;
    }

    private LogMaker newLogMaker(int category) {
        return newLogMaker(category, mService.getServicePackageName());
    }

    private LogMaker newLogMaker(int category, String servicePackageName) {
        return Helper.newLogMaker(category, mComponentName, servicePackageName, id, mCompatMode);
    }

    private void writeLog(int category) {
        mMetricsLogger.write(newLogMaker(category));
    }

    @GuardedBy("mLock")
    private void logAuthenticationStatusLocked(int requestId, int status) {
        addTaggedDataToRequestLogLocked(requestId,
                MetricsEvent.FIELD_AUTOFILL_AUTHENTICATION_STATUS, status);
    }

    @GuardedBy("mLock")
    private void addTaggedDataToRequestLogLocked(int requestId, int tag, @Nullable Object value) {
        final LogMaker requestLog = mRequestLogs.get(requestId);
        if (requestLog == null) {
            Slog.w(TAG,
                    "addTaggedDataToRequestLogLocked(tag=" + tag + "): no log for id " + requestId);
            return;
        }
        requestLog.addTaggedData(tag, value);
    }

    @GuardedBy("mLock")
    private void logAugmentedAutofillRequestLocked(int mode,
            ComponentName augmentedRemoteServiceName, AutofillId focusedId, boolean isWhitelisted,
            Boolean isInline) {
        final String historyItem =
                "aug:id=" + id + " u=" + uid + " m=" + mode
                        + " a=" + ComponentName.flattenToShortString(mComponentName)
                        + " f=" + focusedId
                        + " s=" + augmentedRemoteServiceName
                        + " w=" + isWhitelisted
                        + " i=" + isInline;
        mService.getMaster().logRequestLocked(historyItem);
    }

    private void wtf(@Nullable Exception e, String fmt, Object...args) {
        final String message = String.format(fmt, args);
        synchronized (mLock) {
            mWtfHistory.log(message);
        }

        if (e != null) {
            Slog.wtf(TAG, message, e);
        } else {
            Slog.wtf(TAG, message);
        }
    }

    private static String actionAsString(int action) {
        switch (action) {
            case ACTION_START_SESSION:
                return "START_SESSION";
            case ACTION_VIEW_ENTERED:
                return "VIEW_ENTERED";
            case ACTION_VIEW_EXITED:
                return "VIEW_EXITED";
            case ACTION_VALUE_CHANGED:
                return "VALUE_CHANGED";
            case ACTION_RESPONSE_EXPIRED:
                return "RESPONSE_EXPIRED";
            default:
                return "UNKNOWN_" + action;
        }
    }

    private static String sessionStateAsString(@SessionState int sessionState) {
        switch (sessionState) {
            case STATE_UNKNOWN:
                return "STATE_UNKNOWN";
            case STATE_ACTIVE:
                return "STATE_ACTIVE";
            case STATE_FINISHED:
                return "STATE_FINISHED";
            case STATE_REMOVED:
                return "STATE_REMOVED";
            default:
                return "UNKNOWN_SESSION_STATE_" + sessionState;
        }
    }

    private int getAutofillServiceUid() {
        ServiceInfo serviceInfo = mService.getServiceInfo();
        return serviceInfo == null ? Process.INVALID_UID : serviceInfo.applicationInfo.uid;
    }

    // FieldClassificationServiceCallbacks start
    public void onClassificationRequestSuccess(@Nullable FieldClassificationResponse response) {
        mClassificationState.updateResponseReceived(response);
    }

    public void onClassificationRequestFailure(int requestId, @Nullable CharSequence message) {

    }

    public void onClassificationRequestTimeout(int requestId) {

    }

    @Override
    public void onServiceDied(@NonNull RemoteFieldClassificationService service) {
        Slog.w(TAG, "removing session because service died");
        synchronized (mLock) {
            // TODO(b/266379948)
            // forceRemoveFromServiceLocked();
        }
    }

    @Override
    public void logFieldClassificationEvent(
            long startTime, FieldClassificationResponse response,
            @FieldClassificationEventLogger.FieldClassificationStatus int status) {
        final FieldClassificationEventLogger logger = FieldClassificationEventLogger.createLogger();
        logger.startNewLogForRequest();
        logger.maybeSetLatencyMillis(
                SystemClock.elapsedRealtime() - startTime);
        logger.maybeSetAppPackageUid(uid);
        logger.maybeSetNextFillRequestId(mFillRequestIdSnapshot + 1);
        logger.maybeSetRequestId(sIdCounterForPcc.get());
        logger.maybeSetSessionId(id);
        int count = -1;
        if (response != null) {
            count = response.getClassifications().size();
        }
        logger.maybeSetRequestStatus(status);
        logger.maybeSetCountClassifications(count);
        logger.logAndEndEvent();
        mFillRequestIdSnapshot = DEFAULT__FILL_REQUEST_ID_SNAPSHOT;
    }
    // FieldClassificationServiceCallbacks end

}
