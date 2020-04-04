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

import static android.service.autofill.AutofillFieldClassificationService.EXTRA_SCORES;
import static android.service.autofill.FillRequest.FLAG_MANUAL_REQUEST;
import static android.service.autofill.FillRequest.FLAG_PASSWORD_INPUT_TYPE;
import static android.service.autofill.FillRequest.INVALID_REQUEST_ID;
import static android.view.autofill.AutofillManager.ACTION_RESPONSE_EXPIRED;
import static android.view.autofill.AutofillManager.ACTION_START_SESSION;
import static android.view.autofill.AutofillManager.ACTION_VALUE_CHANGED;
import static android.view.autofill.AutofillManager.ACTION_VIEW_ENTERED;
import static android.view.autofill.AutofillManager.ACTION_VIEW_EXITED;
import static android.view.autofill.AutofillManager.FLAG_SMART_SUGGESTION_SYSTEM;
import static android.view.autofill.AutofillManager.getSmartSuggestionModeToString;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;
import static com.android.server.autofill.Helper.getNumericValue;
import static com.android.server.autofill.Helper.sDebug;
import static com.android.server.autofill.Helper.sVerbose;
import static com.android.server.autofill.Helper.toArray;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_RECEIVER_EXTRAS;
import static com.android.server.wm.ActivityTaskManagerInternal.ASSIST_KEY_STRUCTURE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityTaskManager;
import android.app.IAssistDataReceiver;
import android.app.assist.AssistStructure;
import android.app.assist.AssistStructure.AutofillOverlay;
import android.app.assist.AssistStructure.ViewNode;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentSender;
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
import android.os.Parcelable;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.SystemClock;
import android.service.autofill.AutofillFieldClassificationService.Scores;
import android.service.autofill.AutofillService;
import android.service.autofill.CompositeUserData;
import android.service.autofill.Dataset;
import android.service.autofill.FieldClassification;
import android.service.autofill.FieldClassification.Match;
import android.service.autofill.FieldClassificationUserData;
import android.service.autofill.FillContext;
import android.service.autofill.FillRequest;
import android.service.autofill.FillResponse;
import android.service.autofill.InternalSanitizer;
import android.service.autofill.InternalValidator;
import android.service.autofill.SaveInfo;
import android.service.autofill.SaveRequest;
import android.service.autofill.UserData;
import android.service.autofill.ValueFinder;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.LocalLog;
import android.util.Log;
import android.util.Slog;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.KeyEvent;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillManager;
import android.view.autofill.AutofillManager.SmartSuggestionMode;
import android.view.autofill.AutofillValue;
import android.view.autofill.IAutoFillManagerClient;
import android.view.autofill.IAutofillWindowPresenter;
import android.view.inputmethod.InlineSuggestionsRequest;
import android.view.inputmethod.InlineSuggestionsResponse;

import com.android.internal.R;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.logging.MetricsLogger;
import com.android.internal.logging.nano.MetricsProto.MetricsEvent;
import com.android.internal.util.ArrayUtils;
import com.android.server.autofill.ui.AutoFillUI;
import com.android.server.autofill.ui.InlineSuggestionFactory;
import com.android.server.autofill.ui.PendingUi;
import com.android.server.inputmethod.InputMethodManagerInternal;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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
        AutoFillUI.AutoFillUiCallback, ValueFinder {
    private static final String TAG = "AutofillSession";

    private static final String EXTRA_REQUEST_ID = "android.service.autofill.extra.REQUEST_ID";

    private final AutofillManagerServiceImpl mService;
    private final Handler mHandler;
    private final Object mLock;
    private final AutoFillUI mUi;

    private final MetricsLogger mMetricsLogger = new MetricsLogger();

    private static AtomicInteger sIdCounter = new AtomicInteger();

    /**
     * ID of the session.
     *
     * <p>It's always a positive number, to make it easier to embed it in a long.
     */
    public final int id;

    /** uid the session is for */
    public final int uid;

    /** ID of the task associated with this session's activity */
    public final int taskId;

    /** Flags used to start the session */
    public final int mFlags;

    @GuardedBy("mLock")
    @NonNull private IBinder mActivityToken;

    /** Component that's being auto-filled */
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
     * Id of the View currently being displayed.
     */
    @GuardedBy("mLock")
    @Nullable private AutofillId mCurrentViewId;

    @GuardedBy("mLock")
    private IAutoFillManagerClient mClient;

    @GuardedBy("mLock")
    private DeathRecipient mClientVulture;

    /**
     * Reference to the remote service.
     *
     * <p>Only {@code null} when the session is for augmented autofill only.
     */
    @Nullable
    private final RemoteFillService mRemoteFillService;

    @GuardedBy("mLock")
    private SparseArray<FillResponse> mResponses;

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
     * List of dataset ids selected by the user.
     */
    @GuardedBy("mLock")
    private ArrayList<String> mSelectedDatasetIds;

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

    @GuardedBy("mLock")
    private final LocalLog mWtfHistory;

    @GuardedBy("mLock")
    private boolean mExpiredResponse;

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

    /**
     * When {@code true}, the session was created only to handle Augmented Autofill requests (i.e.,
     * the session would not have existed otherwsie).
     */
    @GuardedBy("mLock")
    private boolean mForAugmentedAutofillOnly;

    @Nullable
    private final InlineSuggestionSession mInlineSuggestionSession;

    /**
     * Receiver of assist data from the app's {@link Activity}.
     */
    private final AssistDataReceiverImpl mAssistReceiver = new AssistDataReceiverImpl();

    /**
     * TODO(b/151867668): improve how asynchronous data dependencies are handled, without using
     * CountDownLatch.
     */
    private final class AssistDataReceiverImpl extends IAssistDataReceiver.Stub {

        @GuardedBy("mLock")
        private InlineSuggestionsRequest mPendingInlineSuggestionsRequest;
        @GuardedBy("mLock")
        private FillRequest mPendingFillRequest;
        @GuardedBy("mLock")
        private CountDownLatch mCountDownLatch = new CountDownLatch(0);

        @Nullable Consumer<InlineSuggestionsRequest> newAutofillRequestLocked(
                boolean isInlineRequest) {
            mCountDownLatch = new CountDownLatch(isInlineRequest ? 2 : 1);
            mPendingFillRequest = null;
            mPendingInlineSuggestionsRequest = null;
            return isInlineRequest ? (inlineSuggestionsRequest) -> {
                synchronized (mLock) {
                    if (mCountDownLatch.getCount() == 0) {
                        return;
                    }
                    mPendingInlineSuggestionsRequest = inlineSuggestionsRequest;
                    mCountDownLatch.countDown();
                    maybeRequestFillLocked();
                }
            } : null;
        }

        void maybeRequestFillLocked() {
            if (mCountDownLatch.getCount() > 0 || mPendingFillRequest == null) {
                return;
            }
            if (mPendingInlineSuggestionsRequest != null) {
                mPendingFillRequest = new FillRequest(mPendingFillRequest.getId(),
                        mPendingFillRequest.getFillContexts(), mPendingFillRequest.getClientState(),
                        mPendingFillRequest.getFlags(), mPendingInlineSuggestionsRequest);
            }
            mRemoteFillService.onFillRequest(mPendingFillRequest);
            mPendingInlineSuggestionsRequest = null;
            mPendingFillRequest = null;
        }

        @Override
        public void onHandleAssistData(Bundle resultData) throws RemoteException {
            if (mRemoteFillService == null) {
                wtf(null, "onHandleAssistData() called without a remote service. "
                        + "mForAugmentedAutofillOnly: %s", mForAugmentedAutofillOnly);
                return;
            }
            // Keeps to prevent it is cleared on multiple threads.
            final AutofillId currentViewId = mCurrentViewId;
            if (currentViewId == null) {
                Slog.w(TAG, "No current view id - session might have finished");
                return;
            }

            final AssistStructure structure = resultData.getParcelable(ASSIST_KEY_STRUCTURE);
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
                                    ViewState.STATE_URL_BAR);
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
                request = new FillRequest(requestId, contexts, mClientState, flags,
                        /*inlineSuggestionsRequest=*/null);

                if (mCountDownLatch.getCount() > 0) {
                    mPendingFillRequest = request;
                    mCountDownLatch.countDown();
                    maybeRequestFillLocked();
                } else {
                    // TODO(b/151867668): ideally this case should not happen, but it was
                    //  observed, we should figure out why and fix.
                    mRemoteFillService.onFillRequest(request);
                }
            }

            if (mActivityToken != null) {
                mService.sendActivityAssistDataToContentCapture(mActivityToken, resultData);
            }
        }

        @Override
        public void onHandleAssistScreenshot(Bitmap screenshot) {
            // Do nothing
        }
    };

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

    @Nullable
    private AutofillValue findValueFromThisSessionOnlyLocked(@NonNull AutofillId autofillId) {
        final ViewState state = mViewStates.get(autofillId);
        if (state == null) {
            if (sDebug) Slog.d(TAG, "findValueLocked(): no view state for " + autofillId);
            return null;
        }
        AutofillValue value = state.getCurrentValue();
        if (value == null) {
            if (sDebug) Slog.d(TAG, "findValueLocked(): no current value for " + autofillId);
            value = getValueFromContextsLocked(autofillId);
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
                    + "mForAugmentedAutofillOnly: %s", mForAugmentedAutofillOnly);
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

    /**
     * Returns whether inline suggestions are supported by Autofill provider (not augmented
     * Autofill provider).
     */
    private boolean isInlineSuggestionsEnabledByAutofillProviderLocked() {
        return mService.isInlineSuggestionsEnabled();
    }

    private boolean isInlineSuggestionRenderServiceAvailable() {
        return mService.getRemoteInlineSuggestionRenderServiceLocked() != null;
    }

    /**
     * Reads a new structure and then request a new fill response from the fill service.
     *
     * <p> Also asks the IME to make an inline suggestions request if it's enabled.
     */
    @GuardedBy("mLock")
    private void requestNewFillResponseLocked(@NonNull ViewState viewState, int newState,
            int flags) {
        mExpiredResponse = false;
        if (mForAugmentedAutofillOnly || mRemoteFillService == null) {
            if (sVerbose) {
                Slog.v(TAG, "requestNewFillResponse(): triggering augmented autofill instead "
                        + "(mForAugmentedAutofillOnly=" + mForAugmentedAutofillOnly
                        + ", flags=" + flags + ")");
            }
            mForAugmentedAutofillOnly = true;
            triggerAugmentedAutofillLocked(flags);
            return;
        }

        viewState.setState(newState);

        int requestId;

        do {
            requestId = sIdCounter.getAndIncrement();
        } while (requestId == INVALID_REQUEST_ID);

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

        // If the focus changes very quickly before the first request is returned each focus change
        // triggers a new partition and we end up with many duplicate partitions. This is
        // enhanced as the focus change can be much faster than the taking of the assist structure.
        // Hence remove the currently queued request and replace it with the one queued after the
        // structure is taken. This causes only one fill request per bust of focus changes.
        cancelCurrentRequestLocked();

        // Only ask IME to create inline suggestions request if Autofill provider supports it and
        // the render service is available.
        if (isInlineSuggestionsEnabledByAutofillProviderLocked()
                && isInlineSuggestionRenderServiceAvailable()) {
            Consumer<InlineSuggestionsRequest> inlineSuggestionsRequestConsumer =
                    mAssistReceiver.newAutofillRequestLocked(/*isInlineRequest=*/ true);
            if (inlineSuggestionsRequestConsumer != null) {
                mInlineSuggestionSession.onCreateInlineSuggestionsRequest(mCurrentViewId,
                        inlineSuggestionsRequestConsumer);
            }
        } else {
            mAssistReceiver.newAutofillRequestLocked(/*isInlineRequest=*/ false);
        }

        // Now request the assist structure data.
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
            @NonNull InputMethodManagerInternal inputMethodManagerInternal) {
        if (sessionId < 0) {
            wtf(null, "Non-positive sessionId: %s", sessionId);
        }
        id = sessionId;
        mFlags = flags;
        this.taskId = taskId;
        this.uid = uid;
        mStartTime = SystemClock.elapsedRealtime();
        mService = service;
        mLock = lock;
        mUi = ui;
        mHandler = handler;
        mRemoteFillService = serviceComponentName == null ? null
                : new RemoteFillService(context, serviceComponentName, userId, this,
                        bindInstantServiceAllowed);
        mActivityToken = activityToken;
        mHasCallback = hasCallback;
        mUiLatencyHistory = uiLatencyHistory;
        mWtfHistory = wtfHistory;
        mComponentName = componentName;
        mCompatMode = compatMode;
        mForAugmentedAutofillOnly = forAugmentedAutofillOnly;
        setClientLocked(client);

        mInlineSuggestionSession = new InlineSuggestionSession(inputMethodManagerInternal, userId,
                componentName, handler, mLock);

        mMetricsLogger.write(newLogMaker(MetricsEvent.AUTOFILL_SESSION_STARTED)
                .addTaggedData(MetricsEvent.FIELD_AUTOFILL_FLAGS, flags));
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
            Slog.d(TAG, "handling death of " + mActivityToken + " when saving=" + mIsSaving);
            synchronized (mLock) {
                if (mIsSaving) {
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
    public void onFillRequestSuccess(int requestId, @Nullable FillResponse response,
            @NonNull String servicePackageName, int requestFlags) {
        final AutofillId[] fieldClassificationIds;

        final LogMaker requestLog;

        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestSuccess() rejected - session: "
                        + id + " destroyed");
                return;
            }

            requestLog = mRequestLogs.get(requestId);
            if (requestLog != null) {
                requestLog.setType(MetricsEvent.TYPE_SUCCESS);
            } else {
                Slog.w(TAG, "onFillRequestSuccess(): no request log for id " + requestId);
            }
            if (response == null) {
                if (requestLog != null) {
                    requestLog.addTaggedData(MetricsEvent.FIELD_AUTOFILL_NUM_DATASETS, -1);
                }
                processNullResponseLocked(requestId, requestFlags);
                return;
            }

            fieldClassificationIds = response.getFieldClassificationIds();
            if (fieldClassificationIds != null && !mService.isFieldClassificationEnabledLocked()) {
                Slog.w(TAG, "Ignoring " + response + " because field detection is disabled");
                processNullResponseLocked(requestId, requestFlags);
                return;
            }
        }

        mService.setLastResponse(id, response);

        int sessionFinishedState = 0;
        final long disableDuration = response.getDisableDuration();
        if (disableDuration > 0) {
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

            // Although "standard" autofill is disabled, it might still trigger augmented autofill
            if (triggerAugmentedAutofillLocked(requestFlags) != null) {
                mForAugmentedAutofillOnly = true;
                if (sDebug) {
                    Slog.d(TAG, "Service disabled autofill for " + mComponentName
                            + ", but session is kept for augmented autofill only");
                }
                return;
            }
            if (sDebug) {
                final StringBuilder message = new StringBuilder("Service disabled autofill for ")
                                .append(mComponentName)
                                .append(": flags=").append(flags)
                                .append(", duration=");
                TimeUtils.formatDuration(disableDuration, message);
                Slog.d(TAG, message.toString());
            }
            sessionFinishedState = AutofillManager.STATE_DISABLED_BY_SERVICE;
        }

        if (((response.getDatasets() == null || response.getDatasets().isEmpty())
                        && response.getAuthentication() == null)
                || disableDuration > 0) {
            // Response is "empty" from an UI point of view, need to notify client.
            notifyUnavailableToClient(sessionFinishedState, /* autofillableIds= */ null);
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

        synchronized (mLock) {
            processResponseLocked(response, null, requestFlags);
        }
    }

    // FillServiceCallbacks
    @Override
    public void onFillRequestFailure(int requestId, @Nullable CharSequence message) {
        onFillRequestFailureOrTimeout(requestId, false, message);
    }

    // FillServiceCallbacks
    @Override
    public void onFillRequestTimeout(int requestId) {
        onFillRequestFailureOrTimeout(requestId, true, null);
    }

    private void onFillRequestFailureOrTimeout(int requestId, boolean timedOut,
            @Nullable CharSequence message) {
        boolean showMessage = !TextUtils.isEmpty(message);
        synchronized (mLock) {
            if (mDestroyed) {
                Slog.w(TAG, "Call to Session#onFillRequestFailureOrTimeout(req=" + requestId
                        + ") rejected - session: " + id + " destroyed");
                return;
            }
            if (sDebug) {
                Slog.d(TAG, "finishing session due to service "
                        + (timedOut ? "timeout" : "failure"));
            }
            mService.resetLastResponse();
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
        }
        notifyUnavailableToClient(AutofillManager.STATE_UNKNOWN_FAILED,
                /* autofillableIds= */ null);
        if (showMessage) {
            getUiForShowing().showError(message, this);
        }
        removeSelf();
    }

    // FillServiceCallbacks
    @Override
    public void onSaveRequestSuccess(@NonNull String servicePackageName,
            @Nullable IntentSender intentSender) {
        synchronized (mLock) {
            mIsSaving = false;

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
        removeSelf();
    }

    // FillServiceCallbacks
    @Override
    public void onSaveRequestFailure(@Nullable CharSequence message,
            @NonNull String servicePackageName) {
        boolean showMessage = !TextUtils.isEmpty(message);
        synchronized (mLock) {
            mIsSaving = false;

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
        removeSelf();
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

    // FillServiceCallbacks
    @Override
    public void authenticate(int requestId, int datasetIndex, IntentSender intent, Bundle extras,
            boolean authenticateInline) {
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

        mService.setAuthenticationSelected(id, mClientState);

        final int authenticationId = AutofillManager.makeAuthenticationId(requestId, datasetIndex);
        mHandler.sendMessage(obtainMessage(
                Session::startAuthentication,
                this, authenticationId, intent, fillInIntent, authenticateInline));
    }

    // VultureCallback
    @Override
    public void onServiceDied(@NonNull RemoteFillService service) {
        Slog.w(TAG, "removing session because service died");
        forceRemoveSelfLocked();
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
        mHandler.sendMessage(obtainMessage(
                Session::autoFill,
                this, requestId, datasetIndex, dataset, true));
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
        mHandler.sendMessage(obtainMessage(
                AutofillManagerServiceImpl::handleSessionSave,
                mService, this));
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
        mHandler.sendMessage(obtainMessage(
                Session::removeSelf, this));
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
        }
    }

    // AutoFillUiCallback
    @Override
    public void cancelSession() {
        synchronized (mLock) {
            removeSelfLocked();
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
                removeSelfLocked();
            }
        }
        mHandler.sendMessage(obtainMessage(
                Session::doStartIntentSender,
                this, intentSender, intent));
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
            Slog.w(TAG, "no authenticated response");
            removeSelf();
            return;
        }

        final int datasetIdx = AutofillManager.getDatasetIdFromAuthenticationId(
                authenticationId);
        // Authenticated a dataset - reset view state regardless if we got a response or a dataset
        if (datasetIdx != AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED) {
            final Dataset dataset = authenticatedResponse.getDatasets().get(datasetIdx);
            if (dataset == null) {
                Slog.w(TAG, "no dataset with index " + datasetIdx + " on fill response");
                removeSelf();
                return;
            }
        }

        // The client becomes invisible for the authentication, the response is effective.
        mExpiredResponse = false;

        final Parcelable result = data.getParcelable(AutofillManager.EXTRA_AUTHENTICATION_RESULT);
        final Bundle newClientState = data.getBundle(AutofillManager.EXTRA_CLIENT_STATE);
        if (sDebug) {
            Slog.d(TAG, "setAuthenticationResultLocked(): result=" + result
                    + ", clientState=" + newClientState + ", authenticationId=" + authenticationId);
        }
        if (result instanceof FillResponse) {
            logAuthenticationStatusLocked(requestId, MetricsEvent.AUTOFILL_AUTHENTICATED);
            replaceResponseLocked(authenticatedResponse, (FillResponse) result, newClientState);
        } else if (result instanceof Dataset) {
            if (datasetIdx != AutofillManager.AUTHENTICATION_ID_DATASET_ID_UNDEFINED) {
                logAuthenticationStatusLocked(requestId,
                        MetricsEvent.AUTOFILL_DATASET_AUTHENTICATED);
                if (newClientState != null) {
                    if (sDebug) Slog.d(TAG,  "Updating client state from auth dataset");
                    mClientState = newClientState;
                }
                final Dataset dataset = (Dataset) result;
                authenticatedResponse.getDatasets().set(datasetIdx, dataset);
                autoFill(requestId, datasetIdx, dataset, false);
            } else {
                Slog.w(TAG, "invalid index (" + datasetIdx + ") for authentication id "
                        + authenticationId);
                logAuthenticationStatusLocked(requestId,
                        MetricsEvent.AUTOFILL_INVALID_DATASET_AUTHENTICATION);
            }
        } else {
            if (result != null) {
                Slog.w(TAG, "service returned invalid auth type: " + result);
            }
            logAuthenticationStatusLocked(requestId,
                    MetricsEvent.AUTOFILL_INVALID_AUTHENTICATION);
            processNullResponseLocked(requestId, 0);
        }
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
        mHandler.sendMessage(obtainMessage(Session::handleLogContextCommitted, this));
    }

    private void handleLogContextCommitted() {
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
            logFieldClassificationScore(fcStrategy, userData);
        } else {
            logContextCommitted(null, null);
        }
    }

    private void logContextCommitted(@Nullable ArrayList<AutofillId> detectedFieldIds,
            @Nullable ArrayList<FieldClassification> detectedFieldClassifications) {
        synchronized (mLock) {
            logContextCommittedLocked(detectedFieldIds, detectedFieldClassifications);
        }
    }

    @GuardedBy("mLock")
    private void logContextCommittedLocked(@Nullable ArrayList<AutofillId> detectedFieldIds,
            @Nullable ArrayList<FieldClassification> detectedFieldClassifications) {
        final FillResponse lastResponse = getLastResponseLocked("logContextCommited(%s)");
        if (lastResponse == null) return;

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
        final AutofillId[] fieldClassificationIds = lastResponse.getFieldClassificationIds();

        if (!hasAtLeastOneDataset && fieldClassificationIds == null) {
            if (sVerbose) {
                Slog.v(TAG, "logContextCommittedLocked(): skipped (no datasets nor fields "
                        + "classification ids)");
            }
            return;
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
                        // Sanity check - should never happen.
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

        mService.logContextCommittedLocked(id, mClientState, mSelectedDatasetIds,
                ignoredDatasets, changedFieldIds, changedDatasetIds,
                manuallyFilledFieldIds, manuallyFilledDatasetIds, detectedFieldIds,
                detectedFieldClassifications, mComponentName, mCompatMode);
    }

    /**
     * Adds the matches to {@code detectedFieldsIds} and {@code detectedFieldClassifications} for
     * {@code fieldId} based on its {@code currentValue} and {@code userData}.
     */
    private void logFieldClassificationScore(@NonNull FieldClassificationStrategy fcStrategy,
            @NonNull FieldClassificationUserData userData) {

        final String[] userValues = userData.getValues();
        final String[] categoryIds = userData.getCategoryIds();

        final String defaultAlgorithm = userData.getFieldClassificationAlgorithm();
        final Bundle defaultArgs = userData.getDefaultFieldClassificationArgs();

        final ArrayMap<String, String> algorithms = userData.getFieldClassificationAlgorithms();
        final ArrayMap<String, Bundle> args = userData.getFieldClassificationArgs();

        // Sanity check
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
        final RemoteCallback callback = new RemoteCallback((result) -> {
            if (result == null) {
                if (sDebug) Slog.d(TAG, "setFieldClassificationScore(): no results");
                logContextCommitted(null, null);
                return;
            }
            final Scores scores = result.getParcelable(EXTRA_SCORES);
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

            logContextCommitted(detectedFieldIds, detectedFieldClassifications);
        });

        fcStrategy.calculateScores(callback, currentValues, userValues, categoryIds,
                defaultAlgorithm, defaultArgs, algorithms, args);
    }

    /**
     * Shows the save UI, when session can be saved.
     *
     * @return {@code true} if session is done and could be removed, or {@code false} if it's
     * pending user action or the service asked to keep it alive (for multi-screens workflow).
     */
    @GuardedBy("mLock")
    public boolean showSaveLocked() {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#showSaveLocked() rejected - session: "
                    + id + " destroyed");
            return false;
        }
        final FillResponse response = getLastResponseLocked("showSaveLocked(%s)");
        final SaveInfo saveInfo = response == null ? null : response.getSaveInfo();

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
            return true;
        }

        if ((saveInfo.getFlags() & SaveInfo.FLAG_DELAY_SAVE) != 0) {
            // TODO(b/113281366): log metrics
            if (sDebug) Slog.v(TAG, "showSaveLocked(" + this.id + "): service asked to delay save");
            return false;
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
        if (allRequiredAreNotEmpty) {
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
                        final AutofillValue currentValue = viewState.getCurrentValue();
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
            if (atLeastOneChanged) {
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
                        return true;
                    }
                }

                if (sDebug) {
                    Slog.d(TAG, "Good news, everyone! All checks passed, show save UI for "
                            + id + "!");
                }

                // Use handler so logContextCommitted() is logged first
                mHandler.sendMessage(obtainMessage(Session::logSaveShown, this));

                final IAutoFillManagerClient client = getClient();
                mPendingSaveUi = new PendingUi(new Binder(), id, client);

                final CharSequence serviceLabel;
                final Drawable serviceIcon;
                synchronized (mLock) {
                    serviceLabel = mService.getServiceLabelLocked();
                    serviceIcon = mService.getServiceIconLocked();
                }
                if (serviceLabel == null || serviceIcon == null) {
                    wtf(null, "showSaveLocked(): no service label or icon");
                    return true;
                }
                getUiForShowing().showSaveUi(serviceLabel, serviceIcon,
                        mService.getServicePackageName(), saveInfo, this,
                        mComponentName, this, mPendingSaveUi, isUpdate, mCompatMode);
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
            Slog.d(TAG, "showSaveLocked(" + id +"): with no changes, comes no responsibilities."
                    + "allRequiredAreNotNull=" + allRequiredAreNotEmpty
                    + ", atLeastOneChanged=" + atLeastOneChanged);
        }
        return true;
    }

    private void logSaveShown() {
        mService.logSaveShown(id, mClientState);
    }

    @Nullable
    private ArrayMap<AutofillId, InternalSanitizer> createSanitizers(@Nullable SaveInfo saveInfo) {
        if (saveInfo == null) return null;

        final InternalSanitizer[] sanitizerKeys = saveInfo.getSanitizerKeys();
        if (sanitizerKeys == null) return null;

        final int size = sanitizerKeys.length ;
        final ArrayMap<AutofillId, InternalSanitizer> sanitizers = new ArrayMap<>(size);
        if (sDebug) Slog.d(TAG, "Service provided " + size + " sanitizers");
        final AutofillId[][] sanitizerValues = saveInfo.getSanitizerValues();
        for (int i = 0; i < size; i++) {
            final InternalSanitizer sanitizer = sanitizerKeys[i];
            final AutofillId[] ids = sanitizerValues[i];
            if (sDebug) {
                Slog.d(TAG, "sanitizer #" + i + " (" + sanitizer + ") for ids "
                        + Arrays.toString(ids));
            }
            for (AutofillId id : ids) {
                sanitizers.put(id, sanitizer);
            }
        }
        return sanitizers;
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
    boolean isSavingLocked() {
        return mIsSaving;
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
    private CharSequence[] getAutofillOptionsFromContextsLocked(AutofillId id) {
        final int numContexts = mContexts.size();

        for (int i = numContexts - 1; i >= 0; i--) {
            final FillContext context = mContexts.get(i);
            final ViewNode node = Helper.findViewNodeByAutofillId(context.getStructure(), id);
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
            return;
        }
        if (mRemoteFillService == null) {
            wtf(null, "callSaveLocked() called without a remote service. "
                    + "mForAugmentedAutofillOnly: %s", mForAugmentedAutofillOnly);
            return;
        }

        if (sVerbose) Slog.v(TAG, "callSaveLocked(" + this.id + "): mViewStates=" + mViewStates);

        if (mContexts == null) {
            Slog.w(TAG, "callSaveLocked(): no contexts");
            return;
        }

        updateValuesForSaveLocked();

        // Remove pending fill requests as the session is finished.
        cancelCurrentRequestLocked();

        final ArrayList<FillContext> contexts = mergePreviousSessionLocked( /* forSave= */ true);

        final SaveRequest saveRequest =
                new SaveRequest(contexts, mClientState, mSelectedDatasetIds);
        mRemoteFillService.onSaveRequest(saveRequest);
    }

    // TODO(b/113281366): rather than merge it here, it might be better to simply reuse the old
    // session instead of creating a new one. But we need to consider what would happen on corner
    // cases such as "Main Activity M -> activity A with username -> activity B with password"
    // If user follows the normal workflow, than session A would be merged with session B as
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
        if ((flags & FLAG_MANUAL_REQUEST) != 0) {
            mForAugmentedAutofillOnly = false;
            if (sDebug) Slog.d(TAG, "Re-starting session on view " + id + " and flags " + flags);
            requestNewFillResponseLocked(viewState, ViewState.STATE_RESTARTED_SESSION, flags);
            return true;
        }

        // If it's not, then check if it it should start a partition.
        if (shouldStartNewPartitionLocked(id)) {
            if (sDebug) {
                Slog.d(TAG, "Starting partition or augmented request for view id " + id + ": "
                        + viewState.getStateAsString());
            }
            requestNewFillResponseLocked(viewState, ViewState.STATE_STARTED_PARTITION, flags);
            return true;
        } else {
            if (sVerbose) {
                Slog.v(TAG, "Not starting new partition for view " + id + ": "
                        + viewState.getStateAsString());
            }
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
    private boolean shouldStartNewPartitionLocked(@NonNull AutofillId id) {
        if (mResponses == null) {
            return true;
        }

        if (mExpiredResponse) {
            if (sDebug) {
                Slog.d(TAG, "Starting a new partition because the response has expired.");
            }
            return true;
        }

        final int numResponses = mResponses.size();
        if (numResponses >= AutofillManagerService.getPartitionMaxCount()) {
            Slog.e(TAG, "Not starting a new partition on " + id + " because session " + this.id
                    + " reached maximum of " + AutofillManagerService.getPartitionMaxCount());
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

    @GuardedBy("mLock")
    void updateLocked(AutofillId id, Rect virtualBounds, AutofillValue value, int action,
            int flags) {
        if (mDestroyed) {
            Slog.w(TAG, "Call to Session#updateLocked() rejected - session: "
                    + id + " destroyed");
            return;
        }
        if (action == ACTION_RESPONSE_EXPIRED) {
            mExpiredResponse = true;
            if (sDebug) {
                Slog.d(TAG, "Set the response has expired.");
            }
            return;
        }

        id.setSessionId(this.id);
        if (sVerbose) {
            Slog.v(TAG, "updateLocked(" + this.id + "): id=" + id + ", action="
                    + actionAsString(action) + ", flags=" + flags);
        }
        ViewState viewState = mViewStates.get(id);

        if (viewState == null) {
            if (action == ACTION_START_SESSION || action == ACTION_VALUE_CHANGED
                    || action == ACTION_VIEW_ENTERED) {
                if (sVerbose) Slog.v(TAG, "Creating viewState for " + id);
                boolean isIgnored = isIgnoredLocked(id);
                viewState = new ViewState(id, this,
                        isIgnored ? ViewState.STATE_IGNORED : ViewState.STATE_INITIAL);
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

        switch(action) {
            case ACTION_START_SESSION:
                // View is triggering autofill.
                mCurrentViewId = viewState.id;
                viewState.update(value, virtualBounds, flags);
                requestNewFillResponseLocked(viewState, ViewState.STATE_STARTED_SESSION, flags);
                break;
            case ACTION_VALUE_CHANGED:
                if (mCompatMode && (viewState.getState() & ViewState.STATE_URL_BAR) != 0) {
                    // Must cancel the session if the value of the URL bar changed
                    final String currentUrl = mUrlBar == null ? null
                            : mUrlBar.getText().toString().trim();
                    if (currentUrl == null) {
                        // Sanity check - shouldn't happen.
                        wtf(null, "URL bar value changed, but current value is null");
                        return;
                    }
                    if (value == null || ! value.isText()) {
                        // Sanity check - shouldn't happen.
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
                    forceRemoveSelfLocked(AutofillManager.STATE_UNKNOWN_COMPAT_MODE);
                    return;
                }
                if (!Objects.equals(value, viewState.getCurrentValue())) {
                    logIfViewClearedLocked(id, value, viewState);
                    updateViewStateAndUiOnValueChangedLocked(id, value, viewState, flags);
                }
                break;
            case ACTION_VIEW_ENTERED:
                if (sVerbose && virtualBounds != null) {
                    Slog.v(TAG, "entered on virtual child " + id + ": " + virtualBounds);
                }

                final boolean isSameViewEntered = Objects.equals(mCurrentViewId, viewState.id);
                // Update the view states first...
                mCurrentViewId = viewState.id;
                if (value != null) {
                    viewState.setCurrentValue(value);
                }

                if (mCompatMode && (viewState.getState() & ViewState.STATE_URL_BAR) != 0) {
                    if (sDebug) Slog.d(TAG, "Ignoring VIEW_ENTERED on URL BAR (id=" + id + ")");
                    return;
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
                            if (sDebug) Slog.d(TAG, "skip augmented autofill for same view.");
                        }
                        return;
                    } else if (mForAugmentedAutofillOnly && isSameViewEntered) {
                        // Regular autofill is disabled.
                        if (sDebug) Slog.d(TAG, "skip augmented autofill for same view.");
                        return;
                    }
                }

                if (requestNewFillResponseOnViewEnteredIfNecessaryLocked(id, viewState, flags)) {
                    return;
                }

                if (isSameViewEntered) {
                    return;
                }

                // If the ViewState is ready to be displayed, onReady() will be called.
                viewState.update(value, virtualBounds, flags);
                break;
            case ACTION_VIEW_EXITED:
                if (Objects.equals(mCurrentViewId, viewState.id)) {
                    if (sVerbose) Slog.v(TAG, "Exiting view " + id);
                    mUi.hideFillUi(this);
                    hideAugmentedAutofillLocked(viewState);
                    mInlineSuggestionSession.hideInlineSuggestionsUi(mCurrentViewId);
                    mCurrentViewId = null;
                }
                break;
            default:
                Slog.w(TAG, "updateLocked(): unknown action: " + action);
        }
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
        } else if (viewState.id.equals(this.mCurrentViewId)
                && (viewState.getState() & ViewState.STATE_INLINE_SHOWN) != 0) {
            requestShowInlineSuggestionsLocked(viewState.getResponse(), filterText);
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

    /**
     * Returns true if {@code s1} contains all characters of {@code s2}, in order.
     */
    private static boolean containsCharsInOrder(String s1, String s2) {
        int prevIndex = -1;
        for (char ch : s2.toCharArray()) {
            int index = TextUtils.indexOf(s1, ch, prevIndex + 1);
            if (index == -1) {
                return false;
            }
            prevIndex = index;
        }
        return true;
    }

    @Override
    public void onFillReady(@NonNull FillResponse response, @NonNull AutofillId filledId,
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

        final CharSequence serviceLabel;
        final Drawable serviceIcon;
        synchronized (mLock) {
            serviceLabel = mService.getServiceLabelLocked();
            serviceIcon = mService.getServiceIconLocked();
        }
        if (serviceLabel == null || serviceIcon == null) {
            wtf(null, "onFillReady(): no service label or icon");
            return;
        }

        if (response.supportsInlineSuggestions()) {
            synchronized (mLock) {
                if (requestShowInlineSuggestionsLocked(response, filterText)) {
                    final ViewState currentView = mViewStates.get(mCurrentViewId);
                    currentView.setState(ViewState.STATE_INLINE_SHOWN);
                    //TODO(b/137800469): Fix it to log showed only when IME asks for inflation,
                    // rather than here where framework sends back the response.
                    mService.logDatasetShown(id, mClientState);
                    return;
                }
            }
        }

        getUiForShowing().showFillUi(filledId, response, filterText,
                mService.getServicePackageName(), mComponentName,
                serviceLabel, serviceIcon, this, id, mCompatMode);

        mService.logDatasetShown(id, mClientState);

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

                addTaggedDataToRequestLogLocked(response.getRequestId(),
                        MetricsEvent.FIELD_AUTOFILL_DURATION, duration);
            }
        }
    }

    /**
     * Returns whether we made a request to show inline suggestions.
     */
    private boolean requestShowInlineSuggestionsLocked(@NonNull FillResponse response,
            @Nullable String filterText) {
        final Optional<InlineSuggestionsRequest> inlineSuggestionsRequest =
                mInlineSuggestionSession.getInlineSuggestionsRequest();
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

        final ViewState currentView = mViewStates.get(mCurrentViewId);
        if ((currentView.getState() & ViewState.STATE_INLINE_DISABLED) != 0) {
            response.getDatasets().clear();
        }
        InlineSuggestionsResponse inlineSuggestionsResponse =
                InlineSuggestionFactory.createInlineSuggestionsResponse(
                        inlineSuggestionsRequest.get(), response, filterText, mCurrentViewId,
                        this, () -> {
                            synchronized (mLock) {
                                mInlineSuggestionSession.hideInlineSuggestionsUi(mCurrentViewId);
                            }
                        }, remoteRenderService);
        if (inlineSuggestionsResponse == null) {
            Slog.w(TAG, "InlineSuggestionFactory created null response");
            return false;
        }

        return mInlineSuggestionSession.onInlineSuggestionsResponse(mCurrentViewId,
                inlineSuggestionsResponse);
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
            }
            flags = saveInfo.getFlags();
            mSaveOnAllViewsInvisible = (flags & SaveInfo.FLAG_SAVE_ON_ALL_VIEWS_INVISIBLE) != 0;

            // We only need to track views if we want to save once they become invisible.
            if (mSaveOnAllViewsInvisible) {
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
            if ((flags & SaveInfo.FLAG_DONT_SAVE_ON_FINISH) != 0) {
                saveOnFinish = false;
            }

        } else {
            flags = 0;
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
                    if (trackedViews == null || !trackedViews.contains(id)) {
                        fillableIds = ArrayUtils.add(fillableIds, id);
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
    }

    @GuardedBy("mLock")
    private void replaceResponseLocked(@NonNull FillResponse oldResponse,
            @NonNull FillResponse newResponse, @Nullable Bundle newClientState) {
        // Disassociate view states with the old response
        setViewStatesLocked(oldResponse, ViewState.STATE_INITIAL, true);
        // Move over the id
        newResponse.setRequestId(oldResponse.getRequestId());
        // Replace the old response
        mResponses.put(newResponse.getRequestId(), newResponse);
        // Now process the new response
        processResponseLocked(newResponse, newClientState, 0);
    }

    @GuardedBy("mLock")
    private void processNullResponseLocked(int requestId, int flags) {
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

        mService.resetLastResponse();

        // The default autofill service cannot fullfill the request, let's check if the augmented
        // autofill service can.
        mAugmentedAutofillDestroyer = triggerAugmentedAutofillLocked(flags);
        if (mAugmentedAutofillDestroyer == null && ((flags & FLAG_PASSWORD_INPUT_TYPE) == 0)) {
            if (sVerbose) {
                Slog.v(TAG, "canceling session " + id + " when service returned null and it cannot "
                        + "be augmented. AutofillableIds: " + autofillableIds);
            }
            // Nothing to be done, but need to notify client.
            notifyUnavailableToClient(AutofillManager.STATE_FINISHED, autofillableIds);
            removeSelf();
        } else {
            if (sVerbose) {
                if ((flags & FLAG_PASSWORD_INPUT_TYPE) != 0) {
                    Slog.v(TAG, "keeping session " + id + " when service returned null and "
                            + "augmented service is disabled for password fields. "
                            + "AutofillableIds: " + autofillableIds);
                } else {
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
        // (TODO: b/141703197) Fix later by passing info to service.
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

        final boolean isWhitelisted = mService
                .isWhitelistedForAugmentedAutofillLocked(mComponentName);

        final String historyItem =
                "aug:id=" + id + " u=" + uid + " m=" + mode
                + " a=" + ComponentName.flattenToShortString(mComponentName)
                + " f=" + mCurrentViewId
                + " s=" + remoteService.getComponentName()
                + " w=" + isWhitelisted;
        mService.getMaster().logRequestLocked(historyItem);

        if (!isWhitelisted) {
            if (sVerbose) {
                Slog.v(TAG, "triggerAugmentedAutofillLocked(): "
                        + ComponentName.flattenToShortString(mComponentName) + " not whitelisted ");
            }
            return null;
        }

        if (sVerbose) {
            Slog.v(TAG, "calling Augmented Autofill Service ("
                    + ComponentName.flattenToShortString(remoteService.getComponentName())
                    + ") on view " + mCurrentViewId + " using suggestion mode "
                    + getSmartSuggestionModeToString(mode)
                    + " when server returned null for session " + this.id);
        }

        final ViewState viewState = mViewStates.get(mCurrentViewId);
        viewState.setState(ViewState.STATE_TRIGGERED_AUGMENTED_AUTOFILL);
        final AutofillValue currentValue = viewState.getCurrentValue();

        if (mAugmentedRequestsLogs == null) {
            mAugmentedRequestsLogs = new ArrayList<>();
        }
        final LogMaker log = newLogMaker(MetricsEvent.AUTOFILL_AUGMENTED_REQUEST,
                remoteService.getComponentName().getPackageName());
        mAugmentedRequestsLogs.add(log);

        final AutofillId focusedId = AutofillId.withoutSession(mCurrentViewId);

        final Consumer<InlineSuggestionsRequest> requestAugmentedAutofill =
                (inlineSuggestionsRequest) -> {
                    remoteService.onRequestAutofillLocked(id, mClient, taskId, mComponentName,
                            focusedId,
                            currentValue, inlineSuggestionsRequest,
                            /*inlineSuggestionsCallback=*/
                            response -> mInlineSuggestionSession.onInlineSuggestionsResponse(
                                    mCurrentViewId, response),
                            /*onErrorCallback=*/ () -> {
                                synchronized (mLock) {
                                    cancelAugmentedAutofillLocked();
                                }
                            }, mService.getRemoteInlineSuggestionRenderServiceLocked());
                };

        // When the inline suggestion render service is available, there are 2 cases when
        // augmented autofill should ask IME for inline suggestion request, because standard
        // autofill flow didn't:
        // 1. the field is augmented autofill only (when standard autofill provider is None or
        // when it returns null response)
        // 2. standard autofill provider doesn't support inline suggestion
        if (isInlineSuggestionRenderServiceAvailable()
                && (mForAugmentedAutofillOnly
                || !isInlineSuggestionsEnabledByAutofillProviderLocked())) {
            if (sDebug) Slog.d(TAG, "Create inline request for augmented autofill");
            mInlineSuggestionSession.onCreateInlineSuggestionsRequest(mCurrentViewId,
                    /*requestConsumer=*/ requestAugmentedAutofill);
        } else {
            requestAugmentedAutofill.accept(
                    mInlineSuggestionSession.getInlineSuggestionsRequest().orElse(null));
        }
        if (mAugmentedAutofillDestroyer == null) {
            mAugmentedAutofillDestroyer = () -> remoteService.onDestroyAutofillWindowsRequest();
        }
        return mAugmentedAutofillDestroyer;
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

        final int requestId = newResponse.getRequestId();
        if (sVerbose) {
            Slog.v(TAG, "processResponseLocked(): mCurrentViewId=" + mCurrentViewId
                    + ",flags=" + flags + ", reqId=" + requestId + ", resp=" + newResponse
                    + ",newClientState=" + newClientState);
        }

        if (mResponses == null) {
            // Set initial capacity as 2 to handle cases where service always requires auth.
            // TODO: add a metric for number of responses set by server, so we can use its average
            // as the initial array capacitiy.
            mResponses = new SparseArray<>(2);
        }
        mResponses.put(requestId, newResponse);
        mClientState = newClientState != null ? newClientState : newResponse.getClientState();

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
    @GuardedBy("mLock")
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
     * Sets the state and response of all views in the given dataset.
     */
    @GuardedBy("mLock")
    private void setViewStatesLocked(@Nullable FillResponse response, @NonNull Dataset dataset,
            int state, boolean clearResponse) {
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
                viewState.setResponse(null);
            } else if (response != null) {
                viewState.setResponse(response);
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
            viewState = new ViewState(id, this, state);
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
                    mService.logDatasetSelected(dataset.getId(), id, mClientState);
                }

                autoFillApp(dataset);
                return;
            }

            // ...or handle authentication.
            mService.logDatasetAuthenticationSelected(dataset.getId(), id, mClientState);
            setViewStatesLocked(null, dataset, ViewState.STATE_WAITING_DATASET_AUTH, false);
            final Intent fillInIntent = createAuthFillInIntentLocked(requestId, mClientState);
            if (fillInIntent == null) {
                forceRemoveSelfLocked();
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
        fillInIntent.putExtra(AutofillManager.EXTRA_ASSIST_STRUCTURE, context.getStructure());
        fillInIntent.putExtra(AutofillManager.EXTRA_CLIENT_STATE, extras);
        return fillInIntent;
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

    @Override
    public String toString() {
        return "Session: [id=" + id + ", component=" + mComponentName + "]";
    }

    @GuardedBy("mLock")
    void dumpLocked(String prefix, PrintWriter pw) {
        final String prefix2 = prefix + "  ";
        pw.print(prefix); pw.print("id: "); pw.println(id);
        pw.print(prefix); pw.print("uid: "); pw.println(uid);
        pw.print(prefix); pw.print("taskId: "); pw.println(taskId);
        pw.print(prefix); pw.print("flags: "); pw.println(mFlags);
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
        pw.print(prefix); pw.print("mIsSaving: "); pw.println(mIsSaving);
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
        if (mForAugmentedAutofillOnly) {
            pw.print(prefix); pw.println("For Augmented Autofill Only");
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

                    mClient.autofill(id, ids, values, hideHighlight);
                    if (dataset.getId() != null) {
                        if (mSelectedDatasetIds == null) {
                            mSelectedDatasetIds = new ArrayList<>();
                        }
                        mSelectedDatasetIds.add(dataset.getId());
                    }
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
    @GuardedBy("mLock")
    RemoteFillService destroyLocked() {
        if (mDestroyed) {
            return null;
        }

        unlinkClientVultureLocked();
        mUi.destroyAll(mPendingSaveUi, this, true);
        mUi.clearCallback(this);
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
        if (mForAugmentedAutofillOnly) {
            log.addTaggedData(MetricsEvent.FIELD_AUTOFILL_AUGMENTED_ONLY, 1);
        }
        mMetricsLogger.write(log);

        return mRemoteFillService;
    }

    /**
     * Cleans up this session and remove it from the service always, even if it does have a pending
     * Save UI.
     */
    @GuardedBy("mLock")
    void forceRemoveSelfLocked() {
        forceRemoveSelfLocked(AutofillManager.STATE_UNKNOWN);
    }

    @GuardedBy("mLock")
    void forceRemoveSelfIfForAugmentedAutofillOnlyLocked() {
        if (sVerbose) {
            Slog.v(TAG, "forceRemoveSelfIfForAugmentedAutofillOnly(" + this.id + "): "
                    + mForAugmentedAutofillOnly);
        }
        if (!mForAugmentedAutofillOnly) return;

        forceRemoveSelfLocked();
    }

    @GuardedBy("mLock")
    void forceRemoveSelfLocked(int clientState) {
        if (sVerbose) Slog.v(TAG, "forceRemoveSelfLocked(): " + mPendingSaveUi);

        final boolean isPendingSaveUi = isSaveUiPendingLocked();
        mPendingSaveUi = null;
        removeSelfLocked();
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
    @GuardedBy("mLock")
    void removeSelfLocked() {
        if (sVerbose) Slog.v(TAG, "removeSelfLocked(" + this.id + "): " + mPendingSaveUi);
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
}
