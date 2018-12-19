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

import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_APPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_DISAPPEARED;
import static android.view.contentcapture.ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED;
import static android.view.contentcapture.ContentCaptureManager.DEBUG;
import static android.view.contentcapture.ContentCaptureManager.VERBOSE;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ParceledListSlice;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;
import android.os.SystemClock;
import android.util.Log;
import android.util.TimeUtils;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;

import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Session used to notify a system-provided Content Capture service about events associated with
 * views.
 */
public final class ContentCaptureSession implements AutoCloseable {

    /*
     * IMPLEMENTATION NOTICE:
     *
     * All methods in this class should return right away, or do the real work in a handler thread.
     *
     * Hence, the only field that must be thread-safe is mEnabled, which is called at the
     * beginning of every method.
     */

    private static final String TAG = ContentCaptureSession.class.getSimpleName();

    /**
     * Used on {@link #notifyViewTextChanged(AutofillId, CharSequence, int)} to indicate that the
     * thext change was caused by user input (for example, through IME).
     */
    public static final int FLAG_USER_INPUT = 0x1;

    /**
     * Initial state, when there is no session.
     *
     * @hide
     */
    public static final int STATE_UNKNOWN = 0;

    /**
     * Service's startSession() was called, but server didn't confirm it was created yet.
     *
     * @hide
     */
    public static final int STATE_WAITING_FOR_SERVER = 1;

    /**
     * Session is active.
     *
     * @hide
     */
    public static final int STATE_ACTIVE = 2;

    /**
     * Session is disabled.
     *
     * @hide
     */
    public static final int STATE_DISABLED = 3;

    /**
     * Session is disabled because its id already existed on server.
     *
     * @hide
     */
    public static final int STATE_DISABLED_DUPLICATED_ID = 4;

    /**
     * Handler message used to flush the buffer.
     */
    private static final int MSG_FLUSH = 1;

    /**
     * Maximum number of events that are buffered before sent to the app.
     */
    // TODO(b/121044064): use settings
    private static final int MAX_BUFFER_SIZE = 100;

    /**
     * Frequency the buffer is flushed if stale.
     */
    // TODO(b/121044064): use settings
    private static final int FLUSHING_FREQUENCY_MS = 5_000;


    /**
     * Name of the {@link IResultReceiver} extra used to pass the binder interface to the service.
     * @hide
     */
    public static final String EXTRA_BINDER = "binder";

    private final CloseGuard mCloseGuard = CloseGuard.get();

    @NonNull
    private final AtomicBoolean mDisabled;

    @NonNull
    private final Context mContext;

    @NonNull
    private final Handler mHandler;

    /**
     * Interface to the system_server binder object - it's only used to start the session (and
     * notify when the session is finished).
     */
    @Nullable
    private final IContentCaptureManager mSystemServerInterface;

    /**
     * Direct interface to the service binder object - it's used to send the events, including the
     * last ones (when the session is finished)
     */
    @Nullable
    private IContentCaptureDirectManager mDirectServiceInterface;
    @Nullable
    private DeathRecipient mDirectServiceVulture;

    @Nullable
    private final String mId = UUID.randomUUID().toString();

    private int mState = STATE_UNKNOWN;

    @Nullable
    private IBinder mApplicationToken;

    @Nullable
    private ComponentName mComponentName;

    /**
     * List of events held to be sent as a batch.
     */
    // TODO(b/111276913): once we support multiple sessions, we need to move the buffer of events
    // to its own class so it's shared by all sessions
    @Nullable
    private ArrayList<ContentCaptureEvent> mEvents;

    // Used just for debugging purposes (on dump)
    private long mNextFlush;

    // Lazily created on demand.
    private ContentCaptureSessionId mContentCaptureSessionId;

    /**
     * {@link ContentCaptureContext} set by client, or {@code null} when it's the
     * {@link ContentCaptureManager#getMainContentCaptureSession() default session} for the
     * context.
     */
    @Nullable
    private final ContentCaptureContext mClientContext;

    /** @hide */
    protected ContentCaptureSession(@NonNull Context context, @NonNull Handler handler,
            @Nullable IContentCaptureManager systemServerInterface, @NonNull AtomicBoolean disabled,
            @Nullable ContentCaptureContext clientContext) {
        mContext = context;
        mHandler = handler;
        mSystemServerInterface = systemServerInterface;
        mDisabled = disabled;
        mClientContext = clientContext;
        mCloseGuard.open("destroy");
    }

    /**
     * Gets the id used to identify this session.
     */
    public ContentCaptureSessionId getContentCaptureSessionId() {
        if (mContentCaptureSessionId == null) {
            mContentCaptureSessionId = new ContentCaptureSessionId(mId);
        }
        return mContentCaptureSessionId;
    }

    /**
     * Starts this session.
     *
     * @hide
     */
    void start(@NonNull IBinder applicationToken, @NonNull ComponentName activityComponent) {
        if (!isContentCaptureEnabled()) return;

        if (VERBOSE) {
            Log.v(TAG, "start(): token=" + applicationToken + ", comp="
                    + ComponentName.flattenToShortString(activityComponent));
        }

        mHandler.sendMessage(obtainMessage(ContentCaptureSession::handleStartSession, this,
                applicationToken, activityComponent));
    }

    /**
     * Flushes the buffered events to the service.
     *
     * @hide
     */
    void flush() {
        mHandler.sendMessage(obtainMessage(ContentCaptureSession::handleForceFlush, this));
    }

    /**
     * Destroys this session, flushing out all pending notifications to the service.
     *
     * <p>Once destroyed, any new notification will be dropped.
     */
    public void destroy() {
        //TODO(b/111276913): mark it as destroyed so other methods are ignored (and test on CTS)

        if (!isContentCaptureEnabled()) return;

        //TODO(b/111276913): check state (for example, how to handle if it's waiting for remote
        // id) and send it to the cache of batched commands
        if (VERBOSE) {
            Log.v(TAG, "destroy(): state=" + getStateAsString(mState) + ", mId=" + mId);
        }

        flush();

        mHandler.sendMessage(obtainMessage(ContentCaptureSession::handleDestroySession, this));
        mCloseGuard.close();
    }

    /** @hide */
    @Override
    public void close() {
        destroy();
    }

    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            destroy();
        } finally {
            super.finalize();
        }
    }

    private void handleStartSession(@NonNull IBinder token, @NonNull ComponentName componentName) {
        if (mState != STATE_UNKNOWN) {
            // TODO(b/111276913): revisit this scenario
            Log.w(TAG, "ignoring handleStartSession(" + token + ") while on state "
                    + getStateAsString(mState));
            return;
        }
        mState = STATE_WAITING_FOR_SERVER;
        mApplicationToken = token;
        mComponentName = componentName;

        if (VERBOSE) {
            Log.v(TAG, "handleStartSession(): token=" + token + ", act="
                    + getActivityDebugName() + ", id=" + mId);
        }
        final int flags = 0; // TODO(b/111276913): get proper flags

        try {
            mSystemServerInterface.startSession(mContext.getUserId(), mApplicationToken,
                    componentName, mId, mClientContext, flags, new IResultReceiver.Stub() {
                        @Override
                        public void send(int resultCode, Bundle resultData) {
                            IBinder binder = null;
                            if (resultData != null) {
                                binder = resultData.getBinder(EXTRA_BINDER);
                                if (binder == null) {
                                    Log.wtf(TAG, "No " + EXTRA_BINDER + " extra result");
                                    handleResetState();
                                    return;
                                }
                            }
                            handleSessionStarted(resultCode, binder);
                        }
                    });
        } catch (RemoteException e) {
            Log.w(TAG, "Error starting session for " + componentName.flattenToShortString() + ": "
                    + e);
        }
    }

    /**
     * Callback from {@code system_server} after call to
     * {@link IContentCaptureManager#startSession(int, IBinder, ComponentName, String,
     * ContentCaptureContext, int, IResultReceiver)}.
     *
     * @param resultCode session state
     * @param binder handle to {@link IContentCaptureDirectManager}
     */
    private void handleSessionStarted(int resultCode, @Nullable IBinder binder) {
        mState = resultCode;
        if (binder != null) {
            mDirectServiceInterface = IContentCaptureDirectManager.Stub.asInterface(binder);
            mDirectServiceVulture = () -> {
                Log.w(TAG, "Destroying session " + mId + " because service died");
                destroy();
            };
            try {
                binder.linkToDeath(mDirectServiceVulture, 0);
            } catch (RemoteException e) {
                Log.w(TAG, "Failed to link to death on " + binder + ": " + e);
            }
        }
        if (resultCode == STATE_DISABLED || resultCode == STATE_DISABLED_DUPLICATED_ID) {
            mDisabled.set(true);
            handleResetSession(/* resetState= */ false);
        } else {
            mDisabled.set(false);
        }
        if (VERBOSE) {
            Log.v(TAG, "handleSessionStarted() result: code=" + resultCode + ", id=" + mId
                    + ", state=" + getStateAsString(mState) + ", disabled=" + mDisabled.get()
                    + ", binder=" + binder);
        }
    }

    private void handleSendEvent(@NonNull ContentCaptureEvent event, boolean forceFlush) {
        if (mEvents == null) {
            if (VERBOSE) {
                Log.v(TAG, "Creating buffer for " + MAX_BUFFER_SIZE + " events");
            }
            mEvents = new ArrayList<>(MAX_BUFFER_SIZE);
        }
        mEvents.add(event);

        final int numberEvents = mEvents.size();

        // TODO(b/120784831): need to optimize it so we buffer changes until a number of X are
        // buffered (either total or per autofillid). For
        // example, if the user typed "a", "b", "c" and the threshold is 3, we should buffer
        // "a" and "b" then send "abc".
        final boolean bufferEvent = numberEvents < MAX_BUFFER_SIZE;

        if (bufferEvent && !forceFlush) {
            handleScheduleFlush(/* checkExisting= */ true);
            return;
        }

        if (mState != STATE_ACTIVE) {
            // Callback from startSession hasn't been called yet - typically happens on system
            // apps that are started before the system service
            // TODO(b/111276913): try to ignore session while system is not ready / boot
            // not complete instead. Similarly, the manager service should return right away
            // when the user does not have a service set
            if (VERBOSE) {
                Log.v(TAG, "Closing session for " + getActivityDebugName()
                        + " after " + numberEvents + " delayed events and state "
                        + getStateAsString(mState));
            }
            handleResetState();
            // TODO(b/111276913): blacklist activity / use special flag to indicate that
            // when it's launched again
            return;
        }

        handleForceFlush();
    }

    private void handleScheduleFlush(boolean checkExisting) {
        if (checkExisting && mHandler.hasMessages(MSG_FLUSH)) {
            // "Renew" the flush message by removing the previous one
            mHandler.removeMessages(MSG_FLUSH);
        }
        mNextFlush = SystemClock.elapsedRealtime() + FLUSHING_FREQUENCY_MS;
        if (VERBOSE) {
            Log.v(TAG, "Scheduled to flush in " + FLUSHING_FREQUENCY_MS + "ms: " + mNextFlush);
        }
        mHandler.sendMessageDelayed(
                obtainMessage(ContentCaptureSession::handleFlushIfNeeded, this).setWhat(MSG_FLUSH),
                FLUSHING_FREQUENCY_MS);
    }

    private void handleFlushIfNeeded() {
        if (mEvents.isEmpty()) {
            if (VERBOSE) Log.v(TAG, "Nothing to flush");
            return;
        }
        handleForceFlush();
    }

    private void handleForceFlush() {
        if (mEvents == null) return;

        if (mDirectServiceInterface == null) {
            Log.w(TAG, "handleForceFlush(): client not available yet");
            if (!mHandler.hasMessages(MSG_FLUSH)) {
                handleScheduleFlush(/* checkExisting= */ false);
            }
            return;
        }

        final int numberEvents = mEvents.size();
        try {
            if (DEBUG) {
                Log.d(TAG, "Flushing " + numberEvents + " event(s) for " + getActivityDebugName());
            }
            mHandler.removeMessages(MSG_FLUSH);

            final ParceledListSlice<ContentCaptureEvent> events = handleClearEvents();
            mDirectServiceInterface.sendEvents(mId, events);
        } catch (RemoteException e) {
            Log.w(TAG, "Error sending " + numberEvents + " for " + getActivityDebugName()
                    + ": " + e);
        }
    }

    /**
     * Resets the buffer and return a {@link ParceledListSlice} with the previous events.
     */
    @NonNull
    private ParceledListSlice<ContentCaptureEvent> handleClearEvents() {
        // NOTE: we must save a reference to the current mEvents and then set it to to null,
        // otherwise clearing it would clear it in the receiving side if the service is also local.
        final List<ContentCaptureEvent> events = mEvents == null
                ? Collections.emptyList()
                : mEvents;
        mEvents = null;
        return new ParceledListSlice<>(events);
    }

    private void handleDestroySession() {
        //TODO(b/111276913): right now both the ContentEvents and lifecycle sessions are sent
        // to system_server, so it's ok to call both in sequence here. But once we split
        // them so the events are sent directly to the service, we need to make sure they're
        // sent in order.
        if (DEBUG) {
            Log.d(TAG, "Destroying session (ctx=" + mContext + ", id=" + mId + ") with "
                    + (mEvents == null ? 0 : mEvents.size()) + " event(s) for "
                    + getActivityDebugName());
        }

        try {
            mSystemServerInterface.finishSession(mContext.getUserId(), mId);
        } catch (RemoteException e) {
            Log.e(TAG, "Error destroying system-service session " + mId + " for "
                    + getActivityDebugName() + ": " + e);
        }
    }

    private void handleResetState() {
        handleResetSession(/* resetState= */ true);
    }

    // TODO(b/111276913): once we support multiple sessions, we might need to move some of these
    // clearings out.
    private void handleResetSession(boolean resetState) {
        if (resetState) {
            mState = STATE_UNKNOWN;
        }
        mContentCaptureSessionId = null;
        mApplicationToken = null;
        mComponentName = null;
        mEvents = null;
        if (mDirectServiceInterface != null) {
            mDirectServiceInterface.asBinder().unlinkToDeath(mDirectServiceVulture, 0);
        }
        mDirectServiceInterface = null;
        mHandler.removeMessages(MSG_FLUSH);
    }

    /**
     * Notifies the Content Capture Service that a node has been added to the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for views that return {@code true} on
     * {@link View#onProvideContentCaptureStructure(ViewStructure, int)}.
     *
     * @param node node that has been added.
     */
    public void notifyViewAppeared(@NonNull ViewStructure node) {
        Preconditions.checkNotNull(node);
        if (!isContentCaptureEnabled()) return;

        if (!(node instanceof ViewNode.ViewStructureImpl)) {
            throw new IllegalArgumentException("Invalid node class: " + node.getClass());
        }

        mHandler.sendMessage(obtainMessage(ContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(TYPE_VIEW_APPEARED)
                        .setViewNode(((ViewNode.ViewStructureImpl) node).mNode),
                        /* forceFlush= */ false));
    }

    /**
     * Notifies the Content Capture Service that a node has been removed from the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for standard views.
     *
     * @param id id of the node that has been removed.
     */
    public void notifyViewDisappeared(@NonNull AutofillId id) {
        Preconditions.checkNotNull(id);
        if (!isContentCaptureEnabled()) return;

        mHandler.sendMessage(obtainMessage(ContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(TYPE_VIEW_DISAPPEARED).setAutofillId(id),
                        /* forceFlush= */ false));
    }

    /**
     * Notifies the Intelligence Service that the value of a text node has been changed.
     *
     * @param id of the node.
     * @param text new text.
     * @param flags either {@code 0} or {@link #FLAG_USER_INPUT} when the value was explicitly
     * changed by the user (for example, through the keyboard).
     */
    public void notifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text,
            int flags) {
        Preconditions.checkNotNull(id);

        if (!isContentCaptureEnabled()) return;

        mHandler.sendMessage(obtainMessage(ContentCaptureSession::handleSendEvent, this,
                new ContentCaptureEvent(TYPE_VIEW_TEXT_CHANGED, flags).setAutofillId(id)
                        .setText(text), /* forceFlush= */ false));
    }

    /**
     * Creates a {@link ViewStructure} for a "standard" view.
     *
     * @hide
     */
    @NonNull
    public ViewStructure newViewStructure(@NonNull View view) {
        return new ViewNode.ViewStructureImpl(view);
    }

    /**
     * Creates a {@link ViewStructure} for a "virtual" view, so it can be passed to
     * {@link #notifyViewAppeared(ViewStructure)} by the view managing the virtual view hierarchy.
     *
     * @param parentId id of the virtual view parent (it can be obtained by calling
     * {@link ViewStructure#getAutofillId()} on the parent).
     * @param virtualId id of the virtual child, relative to the parent.
     *
     * @return a new {@link ViewStructure} that can be used for Content Capture purposes.
     *
     * @hide
     */
    @NonNull
    public ViewStructure newVirtualViewStructure(@NonNull AutofillId parentId, int virtualId) {
        return new ViewNode.ViewStructureImpl(parentId, virtualId);
    }

    private boolean isContentCaptureEnabled() {
        return mSystemServerInterface != null && !mDisabled.get();
    }

    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("id: "); pw.println(mId);
        pw.print(prefix); pw.print("mContext: "); pw.println(mContext);
        pw.print(prefix); pw.print("user: "); pw.println(mContext.getUserId());
        if (mSystemServerInterface != null) {
            pw.print(prefix); pw.print("mSystemServerInterface: ");
            pw.println(mSystemServerInterface);
        }
        if (mDirectServiceInterface != null) {
            pw.print(prefix); pw.print("mDirectServiceInterface: ");
            pw.println(mDirectServiceInterface);
        }
        if (mClientContext != null) {
            // NOTE: we don't dump clientContent because it could have PII
            pw.print(prefix); pw.println("hasClientContext");

        }
        pw.print(prefix); pw.print("mDisabled: "); pw.println(mDisabled.get());
        pw.print(prefix); pw.print("isEnabled(): "); pw.println(isContentCaptureEnabled());
        if (mContentCaptureSessionId != null) {
            pw.print(prefix); pw.print("public id: "); pw.println(mContentCaptureSessionId);
        }
        pw.print(prefix); pw.print("state: "); pw.print(mState); pw.print(" (");
        pw.print(getStateAsString(mState)); pw.println(")");
        if (mApplicationToken != null) {
            pw.print(prefix); pw.print("app token: "); pw.println(mApplicationToken);
        }
        if (mComponentName != null) {
            pw.print(prefix); pw.print("component name: ");
            pw.println(mComponentName.flattenToShortString());
        }
        if (mEvents != null && !mEvents.isEmpty()) {
            final int numberEvents = mEvents.size();
            pw.print(prefix); pw.print("buffered events: "); pw.print(numberEvents);
            pw.print('/'); pw.println(MAX_BUFFER_SIZE);
            if (VERBOSE && numberEvents > 0) {
                final String prefix3 = prefix + "  ";
                for (int i = 0; i < numberEvents; i++) {
                    final ContentCaptureEvent event = mEvents.get(i);
                    pw.print(prefix3); pw.print(i); pw.print(": "); event.dump(pw);
                    pw.println();
                }
            }
            pw.print(prefix); pw.print("flush frequency: "); pw.println(FLUSHING_FREQUENCY_MS);
            pw.print(prefix); pw.print("next flush: ");
            TimeUtils.formatDuration(mNextFlush - SystemClock.elapsedRealtime(), pw); pw.println();
        }
    }

    /**
     * Gets a string that can be used to identify the activity on logging statements.
     */
    private String getActivityDebugName() {
        return mComponentName == null ? mContext.getPackageName()
                : mComponentName.flattenToShortString();
    }

    @Override
    public String toString() {
        return mId;
    }

    @NonNull
    private static String getStateAsString(int state) {
        switch (state) {
            case STATE_UNKNOWN:
                return "UNKNOWN";
            case STATE_WAITING_FOR_SERVER:
                return "WAITING_FOR_SERVER";
            case STATE_ACTIVE:
                return "ACTIVE";
            case STATE_DISABLED:
                return "DISABLED";
            case STATE_DISABLED_DUPLICATED_ID:
                return "DISABLED_DUPLICATED_ID";
            default:
                return "INVALID:" + state;
        }
    }
}
