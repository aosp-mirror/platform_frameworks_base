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
import static android.view.contentcapture.ContentCaptureHelper.getSanitizedString;
import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;
import static android.view.contentcapture.ContentCaptureManager.RESULT_CODE_FALSE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.UiThread;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.graphics.Insets;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.text.Spannable;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.LocalLog;
import android.util.Log;
import android.util.TimeUtils;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;
import android.view.inputmethod.BaseInputConnection;

import com.android.internal.os.IResultReceiver;

import java.io.PrintWriter;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Main session associated with a context.
 *
 * <p>This session is created when the activity starts and finished when it stops; clients can use
 * it to create children activities.
 *
 * @hide
 */
public final class MainContentCaptureSession extends ContentCaptureSession {

    private static final String TAG = MainContentCaptureSession.class.getSimpleName();

    // For readability purposes...
    private static final boolean FORCE_FLUSH = true;

    /**
     * Handler message used to flush the buffer.
     */
    private static final int MSG_FLUSH = 1;

    /**
     * Name of the {@link IResultReceiver} extra used to pass the binder interface to the service.
     * @hide
     */
    public static final String EXTRA_BINDER = "binder";

    /**
     * Name of the {@link IResultReceiver} extra used to pass the content capture enabled state.
     * @hide
     */
    public static final String EXTRA_ENABLED_STATE = "enabled";

    @NonNull
    private final AtomicBoolean mDisabled = new AtomicBoolean(false);

    @NonNull
    private final Context mContext;

    @NonNull
    private final ContentCaptureManager mManager;

    @NonNull
    private final Handler mHandler;

    /**
     * Interface to the system_server binder object - it's only used to start the session (and
     * notify when the session is finished).
     */
    @NonNull
    private final IContentCaptureManager mSystemServerInterface;

    /**
     * Direct interface to the service binder object - it's used to send the events, including the
     * last ones (when the session is finished)
     */
    @NonNull
    private IContentCaptureDirectManager mDirectServiceInterface;
    @Nullable
    private DeathRecipient mDirectServiceVulture;

    private int mState = UNKNOWN_STATE;

    @Nullable
    private IBinder mApplicationToken;
    @Nullable
    private IBinder mShareableActivityToken;

    @Nullable
    private ComponentName mComponentName;

    /**
     * List of events held to be sent as a batch.
     */
    @Nullable
    private ArrayList<ContentCaptureEvent> mEvents;

    // Used just for debugging purposes (on dump)
    private long mNextFlush;

    /**
     * Whether the next buffer flush is queued by a text changed event.
     */
    private boolean mNextFlushForTextChanged = false;

    @Nullable
    private final LocalLog mFlushHistory;

    /**
     * If the event in the buffer is of type {@link TYPE_VIEW_TEXT_CHANGED}, this value
     * indicates whether the event has composing span or not.
     */
    private final Map<AutofillId, Boolean> mLastComposingSpan = new ArrayMap<>();

    /**
     * Binder object used to update the session state.
     */
    @NonNull
    private final SessionStateReceiver mSessionStateReceiver;

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
                    mainSession.mHandler.post(() -> mainSession.resetSession(
                            STATE_DISABLED | STATE_INTERNAL_ERROR));
                    return;
                }
            } else {
                binder = null;
            }
            mainSession.mHandler.post(() -> mainSession.onSessionStarted(resultCode, binder));
        }
    }

    protected MainContentCaptureSession(@NonNull Context context,
            @NonNull ContentCaptureManager manager, @NonNull Handler handler,
            @NonNull IContentCaptureManager systemServerInterface) {
        mContext = context;
        mManager = manager;
        mHandler = handler;
        mSystemServerInterface = systemServerInterface;

        final int logHistorySize = mManager.mOptions.logHistorySize;
        mFlushHistory = logHistorySize > 0 ? new LocalLog(logHistorySize) : null;

        mSessionStateReceiver = new SessionStateReceiver(this);
    }

    @Override
    MainContentCaptureSession getMainCaptureSession() {
        return this;
    }

    @Override
    ContentCaptureSession newChild(@NonNull ContentCaptureContext clientContext) {
        final ContentCaptureSession child = new ChildContentCaptureSession(this, clientContext);
        notifyChildSessionStarted(mId, child.mId, clientContext);
        return child;
    }

    /**
     * Starts this session.
     */
    @UiThread
    void start(@NonNull IBinder token, @NonNull IBinder shareableActivityToken,
            @NonNull ComponentName component, int flags) {
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
        mHandler.removeMessages(MSG_FLUSH);
        mHandler.post(() -> destroySession());
    }

    /**
     * Callback from {@code system_server} after call to
     * {@link IContentCaptureManager#startSession(IBinder, ComponentName, String, int,
     * IResultReceiver)}.
     *
     * @param resultCode session state
     * @param binder handle to {@code IContentCaptureDirectManager}
     */
    @UiThread
    private void onSessionStarted(int resultCode, @Nullable IBinder binder) {
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

    @UiThread
    private void sendEvent(@NonNull ContentCaptureEvent event) {
        sendEvent(event, /* forceFlush= */ false);
    }

    @UiThread
    private void sendEvent(@NonNull ContentCaptureEvent event, boolean forceFlush) {
        final int eventType = event.getType();
        if (sVerbose) Log.v(TAG, "handleSendEvent(" + getDebugState() + "): " + event);
        if (!hasStarted() && eventType != ContentCaptureEvent.TYPE_SESSION_STARTED
                && eventType != ContentCaptureEvent.TYPE_CONTEXT_UPDATED) {
            // TODO(b/120494182): comment when this could happen (dialogs?)
            Log.v(TAG, "handleSendEvent(" + getDebugState() + ", "
                    + ContentCaptureEvent.getTypeAsString(eventType)
                    + "): dropping because session not started yet");
            return;
        }
        if (mDisabled.get()) {
            // This happens when the event was queued in the handler before the sesison was ready,
            // then handleSessionStarted() returned and set it as disabled - we need to drop it,
            // otherwise it will keep triggering handleScheduleFlush()
            if (sVerbose) Log.v(TAG, "handleSendEvent(): ignoring when disabled");
            return;
        }
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
            final boolean textHasComposingSpan = event.getTextHasComposingSpan();

            if (textHasComposingSpan && !mLastComposingSpan.isEmpty()) {
                final Boolean lastEventHasComposingSpan = mLastComposingSpan.get(event.getId());
                if (lastEventHasComposingSpan != null && lastEventHasComposingSpan.booleanValue()) {
                    ContentCaptureEvent lastEvent = null;
                    for (int index = mEvents.size() - 1; index >= 0; index--) {
                        final ContentCaptureEvent tmpEvent = mEvents.get(index);
                        if (event.getId().equals(tmpEvent.getId())) {
                            lastEvent = tmpEvent;
                            break;
                        }
                    }
                    if (lastEvent != null) {
                        final CharSequence lastText = lastEvent.getText();
                        final boolean bothNonEmpty = !TextUtils.isEmpty(lastText)
                                && !TextUtils.isEmpty(text);
                        boolean equalContent = TextUtils.equals(lastText, text);
                        if (equalContent) {
                            addEvent = false;
                        } else if (bothNonEmpty && lastEventHasComposingSpan) {
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
            mLastComposingSpan.put(event.getId(), textHasComposingSpan);
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
            default:
                flushReason = FLUSH_REASON_FULL;
        }

        flush(flushReason);
    }

    @UiThread
    private boolean hasStarted() {
        return mState != UNKNOWN_STATE;
    }

    @UiThread
    private void scheduleFlush(@FlushReason int reason, boolean checkExisting) {
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
        if (checkExisting && mHandler.hasMessages(MSG_FLUSH)) {
            // "Renew" the flush message by removing the previous one
            mHandler.removeMessages(MSG_FLUSH);
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
        mHandler.postDelayed(() -> flushIfNeeded(reason), MSG_FLUSH, flushFrequencyMs);
    }

    @UiThread
    private void flushIfNeeded(@FlushReason int reason) {
        if (mEvents == null || mEvents.isEmpty()) {
            if (sVerbose) Log.v(TAG, "Nothing to flush");
            return;
        }
        flush(reason);
    }

    @Override
    @UiThread
    void flush(@FlushReason int reason) {
        if (mEvents == null) return;

        if (mDisabled.get()) {
            Log.e(TAG, "handleForceFlush(" + getDebugState(reason) + "): should not be when "
                    + "disabled");
            return;
        }

        if (mDirectServiceInterface == null) {
            if (sVerbose) {
                Log.v(TAG, "handleForceFlush(" + getDebugState(reason) + "): hold your horses, "
                        + "client not ready: " + mEvents);
            }
            if (!mHandler.hasMessages(MSG_FLUSH)) {
                scheduleFlush(reason, /* checkExisting= */ false);
            }
            return;
        }

        mNextFlushForTextChanged = false;

        final int numberEvents = mEvents.size();
        final String reasonString = getFlushReasonAsString(reason);
        if (sDebug) {
            Log.d(TAG, "Flushing " + numberEvents + " event(s) for " + getDebugState(reason));
        }
        if (mFlushHistory != null) {
            // Logs reason, size, max size, idle timeout
            final String logRecord = "r=" + reasonString + " s=" + numberEvents
                    + " m=" + mManager.mOptions.maxBufferSize
                    + " i=" + mManager.mOptions.idleFlushingFrequencyMs;
            mFlushHistory.log(logRecord);
        }
        try {
            mHandler.removeMessages(MSG_FLUSH);

            final ParceledListSlice<ContentCaptureEvent> events = clearEvents();
            mDirectServiceInterface.sendEvents(events, reason, mManager.mOptions);
        } catch (RemoteException e) {
            Log.w(TAG, "Error sending " + numberEvents + " for " + getDebugState()
                    + ": " + e);
        }
    }

    @Override
    public void updateContentCaptureContext(@Nullable ContentCaptureContext context) {
        notifyContextUpdated(mId, context);
    }

    /**
     * Resets the buffer and return a {@link ParceledListSlice} with the previous events.
     */
    @NonNull
    @UiThread
    private ParceledListSlice<ContentCaptureEvent> clearEvents() {
        // NOTE: we must save a reference to the current mEvents and then set it to to null,
        // otherwise clearing it would clear it in the receiving side if the service is also local.
        final List<ContentCaptureEvent> events = mEvents == null
                ? Collections.emptyList()
                : mEvents;
        mEvents = null;
        mLastComposingSpan.clear();
        return new ParceledListSlice<>(events);
    }

    @UiThread
    private void destroySession() {
        if (sDebug) {
            Log.d(TAG, "Destroying session (ctx=" + mContext + ", id=" + mId + ") with "
                    + (mEvents == null ? 0 : mEvents.size()) + " event(s) for "
                    + getDebugState());
        }

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
    }

    // TODO(b/122454205): once we support multiple sessions, we might need to move some of these
    // clearings out.
    @UiThread
    private void resetSession(int newState) {
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
            mDirectServiceInterface.asBinder().unlinkToDeath(mDirectServiceVulture, 0);
        }
        mDirectServiceInterface = null;
        mHandler.removeMessages(MSG_FLUSH);
    }

    @Override
    void internalNotifyViewAppeared(@NonNull ViewStructureImpl node) {
        notifyViewAppeared(mId, node);
    }

    @Override
    void internalNotifyViewDisappeared(@NonNull AutofillId id) {
        notifyViewDisappeared(mId, id);
    }

    @Override
    void internalNotifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text) {
        notifyViewTextChanged(mId, id, text);
    }

    @Override
    void internalNotifyViewInsetsChanged(@NonNull Insets viewInsets) {
        notifyViewInsetsChanged(mId, viewInsets);
    }

    @Override
    public void internalNotifyViewTreeEvent(boolean started) {
        notifyViewTreeEvent(mId, started);
    }

    @Override
    public void internalNotifySessionResumed() {
        notifySessionResumed(mId);
    }

    @Override
    public void internalNotifySessionPaused() {
        notifySessionPaused(mId);
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

    // TODO(b/122454205): refactor "notifyXXXX" methods below to a common "Buffer" object that is
    // shared between ActivityContentCaptureSession and ChildContentCaptureSession objects. Such
    // change should also get get rid of the "internalNotifyXXXX" methods above
    void notifyChildSessionStarted(int parentSessionId, int childSessionId,
            @NonNull ContentCaptureContext clientContext) {
        mHandler.post(() -> sendEvent(new ContentCaptureEvent(childSessionId, TYPE_SESSION_STARTED)
                .setParentSessionId(parentSessionId).setClientContext(clientContext),
                FORCE_FLUSH));
    }

    void notifyChildSessionFinished(int parentSessionId, int childSessionId) {
        mHandler.post(() -> sendEvent(new ContentCaptureEvent(childSessionId, TYPE_SESSION_FINISHED)
                .setParentSessionId(parentSessionId), FORCE_FLUSH));
    }

    void notifyViewAppeared(int sessionId, @NonNull ViewStructureImpl node) {
        mHandler.post(() -> sendEvent(new ContentCaptureEvent(sessionId, TYPE_VIEW_APPEARED)
                .setViewNode(node.mNode)));
    }

    /** Public because is also used by ViewRootImpl */
    public void notifyViewDisappeared(int sessionId, @NonNull AutofillId id) {
        mHandler.post(() -> sendEvent(
                new ContentCaptureEvent(sessionId, TYPE_VIEW_DISAPPEARED).setAutofillId(id)));
    }

    void notifyViewTextChanged(int sessionId, @NonNull AutofillId id, @Nullable CharSequence text) {
        // Since the same CharSequence instance may be reused in the TextView, we need to make
        // a copy of its content so that its value will not be changed by subsequent updates
        // in the TextView.
        final String eventText = text == null ? null : text.toString();
        final boolean textHasComposingSpan =
                text instanceof Spannable && BaseInputConnection.getComposingSpanStart(
                        (Spannable) text) >= 0;
        mHandler.post(() -> sendEvent(
                new ContentCaptureEvent(sessionId, TYPE_VIEW_TEXT_CHANGED)
                        .setAutofillId(id).setText(eventText, textHasComposingSpan)));
    }

    /** Public because is also used by ViewRootImpl */
    public void notifyViewInsetsChanged(int sessionId, @NonNull Insets viewInsets) {
        mHandler.post(() -> sendEvent(new ContentCaptureEvent(sessionId, TYPE_VIEW_INSETS_CHANGED)
                .setInsets(viewInsets)));
    }

    /** Public because is also used by ViewRootImpl */
    public void notifyViewTreeEvent(int sessionId, boolean started) {
        final int type = started ? TYPE_VIEW_TREE_APPEARING : TYPE_VIEW_TREE_APPEARED;
        mHandler.post(() -> sendEvent(new ContentCaptureEvent(sessionId, type), FORCE_FLUSH));
    }

    void notifySessionResumed(int sessionId) {
        mHandler.post(() -> sendEvent(
                new ContentCaptureEvent(sessionId, TYPE_SESSION_RESUMED), FORCE_FLUSH));
    }

    void notifySessionPaused(int sessionId) {
        mHandler.post(() -> sendEvent(
                new ContentCaptureEvent(sessionId, TYPE_SESSION_PAUSED), FORCE_FLUSH));
    }

    void notifyContextUpdated(int sessionId, @Nullable ContentCaptureContext context) {
        mHandler.post(() -> sendEvent(new ContentCaptureEvent(sessionId, TYPE_CONTEXT_UPDATED)
                .setClientContext(context)));
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
}
