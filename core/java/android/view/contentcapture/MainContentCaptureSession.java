/*
 * Copyright (C) 2018 The Android Open Source Project
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
package android.view.contentcapture;

import static android.view.contentcapture.ContentCaptureEvent.TYPE_CONTEXT_UPDATED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_FINISHED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_PAUSED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_RESUMED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_SESSION_STARTED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_APPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_DISAPPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_INSETS_CHANGED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_TREE_APPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_TREE_APPEARING;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_WINDOW_BOUNDS_CHANGED;
import static android.view.contentcapture.ContentCaptureHelper.getSanitizedString;
import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_FALSE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.pm.ParceledListSlice;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.Trace;
import android.service.contentcapture.ContentCaptureService;
import android.text.Selection;
import android.text.Spannable;
import android.text.TextUtils;
import android.util.LocalLog;
import android.util.Log;
import android.util.SparseArray;
import android.util.TimeUtils;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;
import android.view.contentprotection.ContentProtectionEventProcessor;
import android.view.inputmethod.BaseInputConnection;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;
import com.android.modules.expresslog.Counter;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Main session associated with a context.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public final class MainContentCaptureSession extends ContentCaptureSession {

    private static final String TAG = MainContentCaptureSession.class.getSimpleName();

    private static final String CONTENT_CAPTURE_WRONG_THREAD_METRIC_ID =
            "content_capture.value_content_capture_wrong_thread_count";

    // For readability purposes...
    private static final boolean FORCE_FLUSH = true;

    /**
     * Handler message used to flush the buffer.
     */
    private static final int MSG_FLUSH = 1;

    @NonNull
    private final AtomicBoolean mDisabled = new AtomicBoolean(false);

    @NonNull
    private final ContentCaptureManager.StrippedContext mContext;

    @NonNull
    private final ContentCaptureManager mManager;

    @NonNull
    private final Handler mUiHandler;

    @NonNull
    private final Handler mContentCaptureHandler;

    /**
     * Interface to the system_server binder object - it's only used to start the session (and
     * notify when the session is finished).
     */
    @NonNull
    private final IContentCaptureManager mSystemServerInterface;

    /**
     * Direct interface to the service binder object - it's used to send the events, including the
     * last ones (when the session is finished)
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    public IContentCaptureDirectManager mDirectServiceInterface;

    @Nullable
    private DeathRecipient mDirectServiceVulture;

    private int mState = UNKNOWN_STATE;

    @Nullable
    private IBinder mApplicationToken;
    @Nullable
    private IBinder mShareableActivityToken;

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    public ComponentName mComponentName;

    /**
     * Thread-safe queue of events held to be processed as a batch.
     *
     * Because it is not guaranteed that the events will be enqueued from a single thread, the
     * implementation must be thread-safe to prevent unexpected behaviour.
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @NonNull
    public final ConcurrentLinkedQueue<ContentCaptureEvent> mEventProcessQueue;

    /**
     * List of events held to be sent to the {@link ContentCaptureService} as a batch.
     *
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    public ArrayList<ContentCaptureEvent> mEvents;

    // Used just for debugging purposes (on dump)
    private long mNextFlush;

    /**
     * Whether the next buffer flush is queued by a text changed event.
     */
    private boolean mNextFlushForTextChanged = false;

    @Nullable
    private final LocalLog mFlushHistory;

    private final AtomicInteger mWrongThreadCount = new AtomicInteger(0);

    /**
     * Binder object used to update the session state.
     */
    @NonNull
    private final SessionStateReceiver mSessionStateReceiver;

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    @Nullable
    public ContentProtectionEventProcessor mContentProtectionEventProcessor;

    private static class SessionStateReceiver extends IResultReceiver.Stub {
        private final WeakReference<MainContentCaptureSession> mMainSession;

        SessionStateReceiver(MainContentCaptureSession session) {
            mMainSession = new WeakReference<>(session);
        }

        @Override
        public void send(int resultCode, Bundle resultData) {
            final MainContentCaptureSession mainSession = mMainSession.get();
            if (mainSession == null) {
                Log.w(TAG, "received result after mina session released");
                return;
            }
            final IBinder binder;
            if (resultData != null) {
                // Change in content capture enabled.
                final boolean hasEnabled = resultData.getBoolean(EXTRA_ENABLED_STATE);
                if (hasEnabled) {
                    final boolean disabled = (resultCode == RESULT_CODE_FALSE);
                    mainSession.mDisabled.set(disabled);
                    return;
                }
                binder = resultData.getBinder(EXTRA_BINDER);
                if (binder == null) {
                    Log.wtf(TAG, "No " + EXTRA_BINDER + " extra result");
                    mainSession.runOnContentCaptureThread(() -> mainSession.resetSession(
                            STATE_DISABLED | STATE_INTERNAL_ERROR));
                    return;
                }
            } else {
                binder = null;
            }
            mainSession.runOnContentCaptureThread(() ->
                    mainSession.onSessionStarted(resultCode, binder));
        }
    }

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PROTECTED)
    public MainContentCaptureSession(
            @NonNull ContentCaptureManager.StrippedContext context,
            @NonNull ContentCaptureManager manager,
            @NonNull Handler uiHandler,
            @NonNull Handler contentCaptureHandler,
            @NonNull IContentCaptureManager systemServerInterface) {
        mContext = context;
        mManager = manager;
        mUiHandler = uiHandler;
        mContentCaptureHandler = contentCaptureHandler;
        mSystemServerInterface = systemServerInterface;

        final int logHistorySize = mManager.mOptions.logHistorySize;
        mFlushHistory = logHistorySize > 0 ? new LocalLog(logHistorySize) : null;

        mSessionStateReceiver = new SessionStateReceiver(this);

        mEventProcessQueue = new ConcurrentLinkedQueue<>();
    }

    @Override
    ContentCaptureSession getMainCaptureSession() {
        return this;
    }

    @Override
    ContentCaptureSession newChild(@NonNull ContentCaptureContext clientContext) {
        final ContentCaptureSession child = new ChildContentCaptureSession(this, clientContext);
        internalNotifyChildSessionStarted(mId, child.mId, clientContext);
        return child;
    }

    /**
     * Starts this session.
     */
    @Override
    void start(@NonNull IBinder token, @NonNull IBinder shareableActivityToken,
            @NonNull ComponentName component, int flags) {
        runOnContentCaptureThread(
                () -> startImpl(token, shareableActivityToken, component, flags));
    }

    private void startImpl(@NonNull IBinder token, @NonNull IBinder shareableActivityToken,
               @NonNull ComponentName component, int flags) {
        checkOnContentCaptureThread();
        if (!isContentCaptureEnabled()) return;

        if (sVerbose) {
            Log.v(TAG, "start(): token=" + token + ", comp="
                    + ComponentName.flattenToShortString(component));
        }

        if (hasStarted()) {
            // TODO(b/122959591): make sure this is expected (and when), or use Log.w
            if (sDebug) {
                Log.d(TAG, "ignoring handleStartSession(" + token + "/"
                        + ComponentName.flattenToShortString(component) + " while on state "
                        + getStateAsString(mState));
            }
            return;
        }
        mState = STATE_WAITING_FOR_SERVER;
        mApplicationToken = token;
        mShareableActivityToken = shareableActivityToken;
        mComponentName = component;

        if (sVerbose) {
            Log.v(TAG, "handleStartSession(): token=" + token + ", act="
                    + getDebugState() + ", id=" + mId);
        }

        try {
            mSystemServerInterface.startSession(mApplicationToken, mShareableActivityToken,
                    component, mId, flags, mSessionStateReceiver);
        } catch (RemoteException e) {
            Log.w(TAG, "Error starting session for " + component.flattenToShortString() + ": " + e);
        }
    }
    @Override
    void onDestroy() {
        clearAndRunOnContentCaptureThread(() -> {
            try {
                flush(FLUSH_REASON_SESSION_FINISHED);
            } finally {
                destroySession();
            }
        }, MSG_FLUSH);
    }

    /**
     * Callback from {@code system_server} after call to {@link
     * IContentCaptureManager#startSession(IBinder, ComponentName, String, int, IResultReceiver)}.
     *
     * @param resultCode session state
     * @param binder handle to {@code IContentCaptureDirectManager}
     * @hide
     */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void onSessionStarted(int resultCode, @Nullable IBinder binder) {
        checkOnContentCaptureThread();
        if (binder != null) {
            mDirectServiceInterface = IContentCaptureDirectManager.Stub.asInterface(binder);
            mDirectServiceVulture = () -> {
                Log.w(TAG, "Keeping session " + mId + " when service died");
                mState = STATE_SERVICE_DIED;
                mDisabled.set(true);
            };
            try {
                binder.linkToDeath(mDirectServiceVulture, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to link to death on " + binder + ": " + e);
            }
        }

        if (isContentProtectionEnabled()) {
            mContentProtectionEventProcessor =
                    new ContentProtectionEventProcessor(
                            mManager.getContentProtectionEventBuffer(),
                            mContentCaptureHandler,
                            mSystemServerInterface,
                            mComponentName.getPackageName(),
                            mManager.mOptions.contentProtectionOptions);
        } else {
            mContentProtectionEventProcessor = null;
        }

        if ((resultCode & STATE_DISABLED) != 0) {
            resetSession(resultCode);
        } else {
            mState = resultCode;
            mDisabled.set(false);
            // Flush any pending data immediately as buffering forced until now.
            flushIfNeeded(FLUSH_REASON_SESSION_CONNECTED);
        }
        if (sVerbose) {
            Log.v(TAG, "handleSessionStarted() result: id=" + mId + " resultCode=" + resultCode
                    + ", state=" + getStateAsString(mState) + ", disabled=" + mDisabled.get()
                    + ", binder=" + binder + ", events=" + (mEvents == null ? 0 : mEvents.size()));
        }
    }

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void sendEvent(@NonNull ContentCaptureEvent event) {
        sendEvent(event, /* forceFlush= */ false);
    }

    private void sendEvent(@NonNull ContentCaptureEvent event, boolean forceFlush) {
        checkOnContentCaptureThread();
        final int eventType = event.getType();
        if (sVerbose) Log.v(TAG, "handleSendEvent(" + getDebugState() + "): " + event);
        if (!hasStarted() && eventType != ContentCaptureEvent.TYPE_SESSION_STARTED
                && eventType != ContentCaptureEvent.TYPE_CONTEXT_UPDATED) {
            // TODO(b/120494182): comment when this could happen (dialogs?)
            if (sVerbose) {
                Log.v(TAG, "handleSendEvent(" + getDebugState() + ", "
                        + ContentCaptureEvent.getTypeAsString(eventType)
                        + "): dropping because session not started yet");
            }
            return;
        }
        if (mDisabled.get()) {
            // This happens when the event was queued in the handler before the sesison was ready,
            // then handleSessionStarted() returned and set it as disabled - we need to drop it,
            // otherwise it will keep triggering handleScheduleFlush()
            if (sVerbose) Log.v(TAG, "handleSendEvent(): ignoring when disabled");
            return;
        }

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            if (eventType == TYPE_VIEW_TREE_APPEARING) {
                Trace.asyncTraceBegin(
                        Trace.TRACE_TAG_VIEW, /* methodName= */ "sendEventAsync", /* cookie= */ 0);
            }
        }

        if (isContentProtectionReceiverEnabled()) {
            sendContentProtectionEvent(event);
        }
        if (isContentCaptureReceiverEnabled()) {
            sendContentCaptureEvent(event, forceFlush);
        }

        if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
            if (eventType == TYPE_VIEW_TREE_APPEARED) {
                Trace.asyncTraceEnd(
                        Trace.TRACE_TAG_VIEW, /* methodName= */ "sendEventAsync", /* cookie= */ 0);
            }
        }
    }

    private void sendContentProtectionEvent(@NonNull ContentCaptureEvent event) {
        checkOnContentCaptureThread();
        if (mContentProtectionEventProcessor != null) {
            mContentProtectionEventProcessor.processEvent(event);
        }
    }

    private void sendContentCaptureEvent(@NonNull ContentCaptureEvent event, boolean forceFlush) {
        checkOnContentCaptureThread();
        final int eventType = event.getType();
        final int maxBufferSize = mManager.mOptions.maxBufferSize;
        if (mEvents == null) {
            if (sVerbose) {
                Log.v(TAG, "handleSendEvent(): creating buffer for " + maxBufferSize + " events");
            }
            mEvents = new ArrayList<>(maxBufferSize);
        }

        // Some type of events can be merged together
        boolean addEvent = true;

        if (eventType == TYPE_VIEW_TEXT_CHANGED) {
            // We determine whether to add or merge the current event by following criteria:
            // 1. Don't have composing span: always add.
            // 2. Have composing span:
            //    2.1 either last or current text is empty: add.
            //    2.2 last event doesn't have composing span: add.
            // Otherwise, merge.
            final CharSequence text = event.getText();
            final boolean hasComposingSpan = event.hasComposingSpan();
            if (hasComposingSpan) {
                ContentCaptureEvent lastEvent = null;
                for (int index = mEvents.size() - 1; index >= 0; index--) {
                    final ContentCaptureEvent tmpEvent = mEvents.get(index);
                    if (event.getId().equals(tmpEvent.getId())) {
                        lastEvent = tmpEvent;
                        break;
                    }
                }
                if (lastEvent != null && lastEvent.hasComposingSpan()) {
                    final CharSequence lastText = lastEvent.getText();
                    final boolean bothNonEmpty = !TextUtils.isEmpty(lastText)
                            && !TextUtils.isEmpty(text);
                    boolean equalContent =
                            TextUtils.equals(lastText, text)
                            && lastEvent.hasSameComposingSpan(event)
                            && lastEvent.hasSameSelectionSpan(event);
                    if (equalContent) {
                        addEvent = false;
                    } else if (bothNonEmpty) {
                        lastEvent.mergeEvent(event);
                        addEvent = false;
                    }
                    if (!addEvent && sVerbose) {
                        Log.v(TAG, "Buffering VIEW_TEXT_CHANGED event, updated text="
                                + getSanitizedString(text));
                    }
                }
            }
        }

        if (!mEvents.isEmpty() && eventType == TYPE_VIEW_DISAPPEARED) {
            final ContentCaptureEvent lastEvent = mEvents.get(mEvents.size() - 1);
            if (lastEvent.getType() == TYPE_VIEW_DISAPPEARED
                    && event.getSessionId() == lastEvent.getSessionId()) {
                if (sVerbose) {
                    Log.v(TAG, "Buffering TYPE_VIEW_DISAPPEARED events for session "
                            + lastEvent.getSessionId());
                }
                lastEvent.mergeEvent(event);
                addEvent = false;
            }
        }

        if (addEvent) {
            mEvents.add(event);
        }

        // TODO: we need to change when the flush happens so that we don't flush while the
        //  composing span hasn't changed. But we might need to keep flushing the events for the
        //  non-editable views and views that don't have the composing state; otherwise some other
        //  Content Capture features may be delayed.

        final int numberEvents = mEvents.size();

        final boolean bufferEvent = numberEvents < maxBufferSize;

        if (bufferEvent && !forceFlush) {
            final int flushReason;
            if (eventType == TYPE_VIEW_TEXT_CHANGED) {
                mNextFlushForTextChanged = true;
                flushReason = FLUSH_REASON_TEXT_CHANGE_TIMEOUT;
            } else {
                if (mNextFlushForTextChanged) {
                    if (sVerbose) {
                        Log.i(TAG, "Not scheduling flush because next flush is for text changed");
                    }
                    return;
                }

                flushReason = FLUSH_REASON_IDLE_TIMEOUT;
            }
            scheduleFlush(flushReason, /* checkExisting= */ true);
            return;
        }

        if (mState != STATE_ACTIVE && numberEvents >= maxBufferSize) {
            // Callback from startSession hasn't been called yet - typically happens on system
            // apps that are started before the system service
            // TODO(b/122959591): try to ignore session while system is not ready / boot
            // not complete instead. Similarly, the manager service should return right away
            // when the user does not have a service set
            if (sDebug) {
                Log.d(TAG, "Closing session for " + getDebugState()
                        + " after " + numberEvents + " delayed events");
            }
            resetSession(STATE_DISABLED | STATE_NO_RESPONSE);
            // TODO(b/111276913): denylist activity / use special flag to indicate that
            // when it's launched again
            return;
        }
        final int flushReason;
        switch (eventType) {
            case ContentCaptureEvent.TYPE_SESSION_STARTED:
                flushReason = FLUSH_REASON_SESSION_STARTED;
                break;
            case ContentCaptureEvent.TYPE_SESSION_FINISHED:
                flushReason = FLUSH_REASON_SESSION_FINISHED;
                break;
            case ContentCaptureEvent.TYPE_VIEW_TREE_APPEARING:
                flushReason = FLUSH_REASON_VIEW_TREE_APPEARING;
                break;
            case ContentCaptureEvent.TYPE_VIEW_TREE_APPEARED:
                flushReason = FLUSH_REASON_VIEW_TREE_APPEARED;
                break;
            default:
                flushReason = forceFlush ? FLUSH_REASON_FORCE_FLUSH : FLUSH_REASON_FULL;
        }

        flush(flushReason);
    }

    private boolean hasStarted() {
        checkOnContentCaptureThread();
        return mState != UNKNOWN_STATE;
    }

    private void scheduleFlush(@FlushReason int reason, boolean checkExisting) {
        checkOnContentCaptureThread();
        if (sVerbose) {
            Log.v(TAG, "handleScheduleFlush(" + getDebugState(reason)
                    + ", checkExisting=" + checkExisting);
        }
        if (!hasStarted()) {
            if (sVerbose) Log.v(TAG, "handleScheduleFlush(): session not started yet");
            return;
        }

        if (mDisabled.get()) {
            // Should not be called on this state, as handleSendEvent checks.
            // But we rather add one if check and log than re-schedule and keep the session alive...
            Log.e(TAG, "handleScheduleFlush(" + getDebugState(reason) + "): should not be called "
                    + "when disabled. events=" + (mEvents == null ? null : mEvents.size()));
            return;
        }
        if (checkExisting && mContentCaptureHandler.hasMessages(MSG_FLUSH)) {
            // "Renew" the flush message by removing the previous one
            mContentCaptureHandler.removeMessages(MSG_FLUSH);
        }

        final int flushFrequencyMs;
        if (reason == FLUSH_REASON_TEXT_CHANGE_TIMEOUT) {
            flushFrequencyMs = mManager.mOptions.textChangeFlushingFrequencyMs;
        } else {
            if (reason != FLUSH_REASON_IDLE_TIMEOUT) {
                if (sDebug) {
                    Log.d(TAG, "handleScheduleFlush(" + getDebugState(reason) + "): not a timeout "
                            + "reason because mDirectServiceInterface is not ready yet");
                }
            }
            flushFrequencyMs = mManager.mOptions.idleFlushingFrequencyMs;
        }

        mNextFlush = System.currentTimeMillis() + flushFrequencyMs;
        if (sVerbose) {
            Log.v(TAG, "handleScheduleFlush(): scheduled to flush in "
                    + flushFrequencyMs + "ms: " + TimeUtils.logTimeOfDay(mNextFlush));
        }
        // Post using a Runnable directly to trim a few μs from PooledLambda.obtainMessage()
        mContentCaptureHandler.postDelayed(() ->
                flushIfNeeded(reason), MSG_FLUSH, flushFrequencyMs);
    }

    private void flushIfNeeded(@FlushReason int reason) {
        checkOnContentCaptureThread();
        if (mEvents == null || mEvents.isEmpty()) {
            if (sVerbose) Log.v(TAG, "Nothing to flush");
            return;
        }
        flush(reason);
    }

    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
    @Override
    public void flush(@FlushReason int reason) {
        runOnContentCaptureThread(() -> flushImpl(reason));
    }

    private void flushImpl(@FlushReason int reason) {
        checkOnContentCaptureThread();
        if (mEvents == null || mEvents.size() == 0) {
            if (sVerbose) {
                Log.v(TAG, "Don't flush for empty event buffer.");
            }
            return;
        }

        if (mDisabled.get()) {
            Log.e(TAG, "handleForceFlush(" + getDebugState(reason) + "): should not be when "
                    + "disabled");
            return;
        }

        if (!isContentCaptureReceiverEnabled()) {
            return;
        }

        if (mDirectServiceInterface == null) {
            if (sVerbose) {
                Log.v(TAG, "handleForceFlush(" + getDebugState(reason) + "): hold your horses, "
                        + "client not ready: " + mEvents);
            }
            if (!mContentCaptureHandler.hasMessages(MSG_FLUSH)) {
                scheduleFlush(reason, /* checkExisting= */ false);
            }
            return;
        }

        mNextFlushForTextChanged = false;

        final int numberEvents = mEvents.size();
        final String reasonString = getFlushReasonAsString(reason);

        if (sVerbose) {
            ContentCaptureEvent event = mEvents.get(numberEvents - 1);
            String forceString = (reason == FLUSH_REASON_FORCE_FLUSH) ? ". The force flush event "
                    + ContentCaptureEvent.getTypeAsString(event.getType()) : "";
            Log.v(TAG, "Flushing " + numberEvents + " event(s) for " + getDebugState(reason)
                    + forceString);
        }
        if (mFlushHistory != null) {
            // Logs reason, size, max size, idle timeout
            final String logRecord = "r=" + reasonString + " s=" + numberEvents
                    + " m=" + mManager.mOptions.maxBufferSize
                    + " i=" + mManager.mOptions.idleFlushingFrequencyMs;
            mFlushHistory.log(logRecord);
        }
        try {
            mContentCaptureHandler.removeMessages(MSG_FLUSH);

            final ParceledListSlice<ContentCaptureEvent> events = clearEvents();
            mDirectServiceInterface.sendEvents(events, reason, mManager.mOptions);
        } catch (RemoteException e) {
            Log.w(TAG, "Error sending " + numberEvents + " for " + getDebugState()
                    + ": " + e);
        }
    }

    @Override
    public void updateContentCaptureContext(@Nullable ContentCaptureContext context) {
        internalNotifyContextUpdated(mId, context);
    }

    /**
     * Resets the buffer and return a {@link ParceledListSlice} with the previous events.
     */
    @NonNull
    private ParceledListSlice<ContentCaptureEvent> clearEvents() {
        checkOnContentCaptureThread();
        // NOTE: we must save a reference to the current mEvents and then set it to to null,
        // otherwise clearing it would clear it in the receiving side if the service is also local.
        if (mEvents == null) {
            return new ParceledListSlice<>(Collections.EMPTY_LIST);
        }

        final List<ContentCaptureEvent> events = new ArrayList<>(mEvents);
        mEvents.clear();
        return new ParceledListSlice<>(events);
    }

    /** hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void destroySession() {
        checkOnContentCaptureThread();
        if (sDebug) {
            Log.d(TAG, "Destroying session (ctx=" + mContext + ", id=" + mId + ") with "
                    + (mEvents == null ? 0 : mEvents.size()) + " event(s) for "
                    + getDebugState());
        }

        reportWrongThreadMetric();
        try {
            mSystemServerInterface.finishSession(mId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error destroying system-service session " + mId + " for "
                    + getDebugState() + ": " + e);
        }

        if (mDirectServiceInterface != null) {
            mDirectServiceInterface.asBinder().unlinkToDeath(mDirectServiceVulture, 0);
        }
        mDirectServiceInterface = null;
        mContentProtectionEventProcessor = null;
        mEventProcessQueue.clear();
    }

    // TODO(b/122454205): once we support multiple sessions, we might need to move some of these
    // clearings out.
    /** @hide */
    @VisibleForTesting(visibility = VisibleForTesting.Visibility.PRIVATE)
    public void resetSession(int newState) {
        checkOnContentCaptureThread();
        if (sVerbose) {
            Log.v(TAG, "handleResetSession(" + getActivityName() + "): from "
                    + getStateAsString(mState) + " to " + getStateAsString(newState));
        }
        mState = newState;
        mDisabled.set((newState & STATE_DISABLED) != 0);
        // TODO(b/122454205): must reset children (which currently is owned by superclass)
        mApplicationToken = null;
        mShareableActivityToken = null;
        mComponentName = null;
        mEvents = null;
        if (mDirectServiceInterface != null) {
            try {
                mDirectServiceInterface.asBinder().unlinkToDeath(mDirectServiceVulture, 0);
            } catch (NoSuchElementException e) {
                Log.w(TAG, "IContentCaptureDirectManager does not exist");
            }
        }
        mDirectServiceInterface = null;
        mContentProtectionEventProcessor = null;
        mContentCaptureHandler.removeMessages(MSG_FLUSH);
    }

    @Override
    void internalNotifyViewAppeared(int sessionId, @NonNull ViewStructureImpl node) {
        final ContentCaptureEvent event = new ContentCaptureEvent(sessionId, TYPE_VIEW_APPEARED)
                .setViewNode(node.mNode);
        enqueueEvent(event);
    }

    @Override
    void internalNotifyViewDisappeared(int sessionId, @NonNull AutofillId id) {
        final ContentCaptureEvent event = new ContentCaptureEvent(sessionId, TYPE_VIEW_DISAPPEARED)
                .setAutofillId(id);
        enqueueEvent(event);
    }

    @Override
    void internalNotifyViewTextChanged(
            int sessionId, @NonNull AutofillId id, @Nullable CharSequence text) {
        // Since the same CharSequence instance may be reused in the TextView, we need to make
        // a copy of its content so that its value will not be changed by subsequent updates
        // in the TextView.
        CharSequence trimmed = TextUtils.trimToParcelableSize(text);
        final CharSequence eventText = trimmed != null && trimmed == text
                ? trimmed.toString()
                : trimmed;

        final int composingStart;
        final int composingEnd;
        if (text instanceof Spannable) {
            composingStart = BaseInputConnection.getComposingSpanStart((Spannable) text);
            composingEnd = BaseInputConnection.getComposingSpanEnd((Spannable) text);
        } else {
            composingStart = ContentCaptureEvent.MAX_INVALID_VALUE;
            composingEnd = ContentCaptureEvent.MAX_INVALID_VALUE;
        }

        final int startIndex = Selection.getSelectionStart(text);
        final int endIndex = Selection.getSelectionEnd(text);

        final ContentCaptureEvent event = new ContentCaptureEvent(sessionId, TYPE_VIEW_TEXT_CHANGED)
                .setAutofillId(id).setText(eventText)
                .setComposingIndex(composingStart, composingEnd)
                .setSelectionIndex(startIndex, endIndex);
        enqueueEvent(event);
    }

    @Override
    void internalNotifyViewInsetsChanged(int sessionId, @NonNull Insets viewInsets) {
        final ContentCaptureEvent event =
                new ContentCaptureEvent(sessionId, TYPE_VIEW_INSETS_CHANGED)
                        .setInsets(viewInsets);
        enqueueEvent(event);
    }

    @Override
    public void internalNotifyViewTreeEvent(int sessionId, boolean started) {
        final int type = started ? TYPE_VIEW_TREE_APPEARING : TYPE_VIEW_TREE_APPEARED;
        final boolean disableFlush = mManager.getFlushViewTreeAppearingEventDisabled();
        final boolean forceFlush = disableFlush ? !started : FORCE_FLUSH;

        final ContentCaptureEvent event = new ContentCaptureEvent(sessionId, type);
        enqueueEvent(event, forceFlush);
    }

    @Override
    public void internalNotifySessionResumed() {
        final ContentCaptureEvent event = new ContentCaptureEvent(mId, TYPE_SESSION_RESUMED);
        enqueueEvent(event, FORCE_FLUSH);
    }

    @Override
    public void internalNotifySessionPaused() {
        final ContentCaptureEvent event = new ContentCaptureEvent(mId, TYPE_SESSION_PAUSED);
        enqueueEvent(event, FORCE_FLUSH);
    }

    @Override
    boolean isContentCaptureEnabled() {
        return super.isContentCaptureEnabled() && mManager.isContentCaptureEnabled();
    }

    // Called by ContentCaptureManager.isContentCaptureEnabled
    boolean isDisabled() {
        return mDisabled.get();
    }

    /**
     * Sets the disabled state of content capture.
     *
     * @return whether disabled state was changed.
     */
    boolean setDisabled(boolean disabled) {
        return mDisabled.compareAndSet(!disabled, disabled);
    }

    @Override
    void internalNotifyChildSessionStarted(int parentSessionId, int childSessionId,
            @NonNull ContentCaptureContext clientContext) {
        final ContentCaptureEvent event =
                new ContentCaptureEvent(childSessionId, TYPE_SESSION_STARTED)
                        .setParentSessionId(parentSessionId)
                        .setClientContext(clientContext);
        enqueueEvent(event, FORCE_FLUSH);
    }

    @Override
    void internalNotifyChildSessionFinished(int parentSessionId, int childSessionId) {
        final ContentCaptureEvent event =
                new ContentCaptureEvent(childSessionId, TYPE_SESSION_FINISHED)
                        .setParentSessionId(parentSessionId);
        enqueueEvent(event, FORCE_FLUSH);
    }

    @Override
    void internalNotifyContextUpdated(int sessionId, @Nullable ContentCaptureContext context) {
        final ContentCaptureEvent event = new ContentCaptureEvent(sessionId, TYPE_CONTEXT_UPDATED)
                .setClientContext(context);
        enqueueEvent(event, FORCE_FLUSH);
    }

    @Override
    public void notifyWindowBoundsChanged(int sessionId, @NonNull Rect bounds) {
        final ContentCaptureEvent event =
                new ContentCaptureEvent(sessionId, TYPE_WINDOW_BOUNDS_CHANGED)
                        .setBounds(bounds);
        enqueueEvent(event);
    }

    private List<ContentCaptureEvent> clearBufferEvents() {
        final ArrayList<ContentCaptureEvent> bufferEvents = new ArrayList<>();
        ContentCaptureEvent event;
        while ((event = mEventProcessQueue.poll()) != null) {
            bufferEvents.add(event);
        }
        return bufferEvents;
    }

    private void enqueueEvent(@NonNull final ContentCaptureEvent event) {
        enqueueEvent(event, /* forceFlush */ false);
    }

    /**
     * Enqueue the event into {@code mEventProcessBuffer} if it is not an urgent request. Otherwise,
     * clear the buffer events then starting sending out current event.
     */
    private void enqueueEvent(@NonNull final ContentCaptureEvent event, boolean forceFlush) {
        if (forceFlush || mEventProcessQueue.size() >= mManager.mOptions.maxBufferSize - 1) {
            // The buffer events are cleared in the same thread first to prevent new events
            // being added during the time of context switch. This would disrupt the sequence
            // of events.
            final List<ContentCaptureEvent> batchEvents = clearBufferEvents();
            runOnContentCaptureThread(() -> {
                for (int i = 0; i < batchEvents.size(); i++) {
                    sendEvent(batchEvents.get(i));
                }
                sendEvent(event, /* forceFlush= */ true);
            });
        } else {
            mEventProcessQueue.offer(event);
        }
    }

    @Override
    public void notifyContentCaptureEvents(
            @NonNull SparseArray<ArrayList<Object>> contentCaptureEvents) {
        runOnUiThread(() -> {
            prepareViewStructures(contentCaptureEvents);
            runOnContentCaptureThread(() ->
                    notifyContentCaptureEventsImpl(contentCaptureEvents));
        });
    }

    /**
     * Traverse events and pre-process {@link View} events to {@link ViewStructureSession} events.
     * If a {@link View} event is invalid, an empty {@link ViewStructureSession} will still be
     * provided.
     */
    private void prepareViewStructures(
            @NonNull SparseArray<ArrayList<Object>> contentCaptureEvents) {
        for (int i = 0; i < contentCaptureEvents.size(); i++) {
            int sessionId = contentCaptureEvents.keyAt(i);
            ArrayList<Object> events = contentCaptureEvents.valueAt(i);
            for_each_event: for (int j = 0; j < events.size(); j++) {
                Object event = events.get(j);
                if (event instanceof View) {
                    View view = (View) event;
                    ContentCaptureSession session = view.getContentCaptureSession();
                    ViewStructureSession structureSession = new ViewStructureSession();

                    // Replace the View event with ViewStructureSession no matter the data is
                    // available or not. This is to ensure the sequence of the events are still
                    // the same. Calls to notifyViewAppeared will check the availability later.
                    events.set(j, structureSession);
                    if (session == null) {
                        Log.w(TAG, "no content capture session on view: " + view);
                        continue for_each_event;
                    }
                    int actualId = session.getId();
                    if (actualId != sessionId) {
                        Log.w(TAG, "content capture session mismatch for view (" + view
                                + "): was " + sessionId + " before, it's " + actualId + " now");
                        continue for_each_event;
                    }
                    ViewStructure structure = session.newViewStructure(view);
                    view.onProvideContentCaptureStructure(structure, /* flags= */ 0);

                    structureSession.setSession(session);
                    structureSession.setStructure(structure);
                }
            }
        }
    }

    private void notifyContentCaptureEventsImpl(
            @NonNull SparseArray<ArrayList<Object>> contentCaptureEvents) {
        checkOnContentCaptureThread();
        try {
            if (Trace.isTagEnabled(Trace.TRACE_TAG_VIEW)) {
                Trace.traceBegin(Trace.TRACE_TAG_VIEW, "notifyContentCaptureEvents");
            }
            for (int i = 0; i < contentCaptureEvents.size(); i++) {
                int sessionId = contentCaptureEvents.keyAt(i);
                internalNotifyViewTreeEvent(sessionId, /* started= */ true);
                ArrayList<Object> events = contentCaptureEvents.valueAt(i);
                for_each_event: for (int j = 0; j < events.size(); j++) {
                    Object event = events.get(j);
                    if (event instanceof AutofillId) {
                        internalNotifyViewDisappeared(sessionId, (AutofillId) event);
                    } else if (event instanceof ViewStructureSession viewStructureSession) {
                        viewStructureSession.notifyViewAppeared();
                    } else if (event instanceof Insets) {
                        internalNotifyViewInsetsChanged(sessionId, (Insets) event);
                    } else {
                        Log.w(TAG, "invalid content capture event: " + event);
                    }
                }
                internalNotifyViewTreeEvent(sessionId, /* started= */ false);
            }
        } finally {
            Trace.traceEnd(Trace.TRACE_TAG_VIEW);
        }
    }

    @Override
    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        super.dump(prefix, pw);

        pw.print(prefix); pw.print("mContext: "); pw.println(mContext);
        pw.print(prefix); pw.print("user: "); pw.println(mContext.getUserId());
        if (mDirectServiceInterface != null) {
            pw.print(prefix); pw.print("mDirectServiceInterface: ");
            pw.println(mDirectServiceInterface);
        }
        pw.print(prefix); pw.print("mDisabled: "); pw.println(mDisabled.get());
        pw.print(prefix); pw.print("isEnabled(): "); pw.println(isContentCaptureEnabled());
        pw.print(prefix); pw.print("state: "); pw.println(getStateAsString(mState));
        if (mApplicationToken != null) {
            pw.print(prefix); pw.print("app token: "); pw.println(mApplicationToken);
        }
        if (mShareableActivityToken != null) {
            pw.print(prefix); pw.print("sharable activity token: ");
            pw.println(mShareableActivityToken);
        }
        if (mComponentName != null) {
            pw.print(prefix); pw.print("component name: ");
            pw.println(mComponentName.flattenToShortString());
        }
        if (mEvents != null && !mEvents.isEmpty()) {
            final int numberEvents = mEvents.size();
            pw.print(prefix); pw.print("buffered events: "); pw.print(numberEvents);
            pw.print('/'); pw.println(mManager.mOptions.maxBufferSize);
            if (sVerbose && numberEvents > 0) {
                final String prefix3 = prefix + "  ";
                for (int i = 0; i < numberEvents; i++) {
                    final ContentCaptureEvent event = mEvents.get(i);
                    pw.print(prefix3); pw.print(i); pw.print(": "); event.dump(pw);
                    pw.println();
                }
            }
            pw.print(prefix); pw.print("mNextFlushForTextChanged: ");
            pw.println(mNextFlushForTextChanged);
            pw.print(prefix); pw.print("flush frequency: ");
            if (mNextFlushForTextChanged) {
                pw.println(mManager.mOptions.textChangeFlushingFrequencyMs);
            } else {
                pw.println(mManager.mOptions.idleFlushingFrequencyMs);
            }
            pw.print(prefix); pw.print("next flush: ");
            TimeUtils.formatDuration(mNextFlush - System.currentTimeMillis(), pw);
            pw.print(" ("); pw.print(TimeUtils.logTimeOfDay(mNextFlush)); pw.println(")");
        }
        if (mFlushHistory != null) {
            pw.print(prefix); pw.println("flush history:");
            mFlushHistory.reverseDump(/* fd= */ null, pw, /* args= */ null); pw.println();
        } else {
            pw.print(prefix); pw.println("not logging flush history");
        }

        super.dump(prefix, pw);
    }

    /**
     * Gets a string that can be used to identify the activity on logging statements.
     */
    private String getActivityName() {
        return mComponentName == null
                ? "pkg:" + mContext.getPackageName()
                : "act:" + mComponentName.flattenToShortString();
    }

    @NonNull
    private String getDebugState() {
        return getActivityName() + " [state=" + getStateAsString(mState) + ", disabled="
                + mDisabled.get() + "]";
    }

    @NonNull
    private String getDebugState(@FlushReason int reason) {
        return getDebugState() + ", reason=" + getFlushReasonAsString(reason);
    }

    private boolean isContentProtectionReceiverEnabled() {
        return mManager.mOptions.contentProtectionOptions.enableReceiver;
    }

    private boolean isContentCaptureReceiverEnabled() {
        return mManager.mOptions.enableReceiver;
    }

    private boolean isContentProtectionEnabled() {
        // Should not be possible for mComponentName to be null here but check anyway
        // Should not be possible for groups to be empty if receiver is enabled but check anyway
        return mManager.mOptions.contentProtectionOptions.enableReceiver
                && mManager.getContentProtectionEventBuffer() != null
                && mComponentName != null
                && (!mManager.mOptions.contentProtectionOptions.requiredGroups.isEmpty()
                        || !mManager.mOptions.contentProtectionOptions.optionalGroups.isEmpty());
    }

    /**
     * Checks that the current work is running on the assigned thread from {@code mHandler} and
     * count the number of times running on the wrong thread.
     *
     * <p>It is not guaranteed that the callers always invoke function from a single thread.
     * Therefore, accessing internal properties in {@link MainContentCaptureSession} should
     * always delegate to the assigned thread from {@code mHandler} for synchronization.</p>
     */
    private void checkOnContentCaptureThread() {
        final boolean onContentCaptureThread = mContentCaptureHandler.getLooper().isCurrentThread();
        if (!onContentCaptureThread) {
            mWrongThreadCount.incrementAndGet();
            Log.e(TAG, "MainContentCaptureSession running on " + Thread.currentThread());
        }
    }

    /** Reports number of times running on the wrong thread. */
    private void reportWrongThreadMetric() {
        Counter.logIncrement(
                CONTENT_CAPTURE_WRONG_THREAD_METRIC_ID, mWrongThreadCount.getAndSet(0));
    }

    /**
     * Ensures that {@code r} will be running on the assigned thread.
     *
     * <p>This is to prevent unnecessary delegation to Handler that results in fragmented runnable.
     * </p>
     */
    private void runOnContentCaptureThread(@NonNull Runnable r) {
        if (!mContentCaptureHandler.getLooper().isCurrentThread()) {
            mContentCaptureHandler.post(r);
        } else {
            r.run();
        }
    }

    private void clearAndRunOnContentCaptureThread(@NonNull Runnable r, int what) {
        if (!mContentCaptureHandler.getLooper().isCurrentThread()) {
            mContentCaptureHandler.removeMessages(what);
            mContentCaptureHandler.post(r);
        } else {
            r.run();
        }
    }

    private void runOnUiThread(@NonNull Runnable r) {
        if (mUiHandler.getLooper().isCurrentThread()) {
            r.run();
        } else {
            mUiHandler.post(r);
        }
    }

    /**
     * Holds {@link ContentCaptureSession} and related {@link ViewStructure} for processing.
     */
    private static final class ViewStructureSession {
        @Nullable private ContentCaptureSession mSession;
        @Nullable private ViewStructure mStructure;

        ViewStructureSession() {}

        void setSession(@Nullable ContentCaptureSession session) {
            this.mSession = session;
        }

        void setStructure(@Nullable ViewStructure struct) {
            this.mStructure = struct;
        }

        /**
         * Calls {@link ContentCaptureSession#notifyViewAppeared(ViewStructure)} if the session and
         * the view structure are available.
         */
        void notifyViewAppeared() {
            if (mSession != null && mStructure != null) {
                mSession.notifyViewAppeared(mStructure);
            }
        }
    }
}
