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
package android.view.intelligence;

import static android.view.intelligence.ContentCaptureEvent.TYPE_VIEW_APPEARED;
import static android.view.intelligence.ContentCaptureEvent.TYPE_VIEW_DISAPPEARED;
import static android.view.intelligence.ContentCaptureEvent.TYPE_VIEW_TEXT_CHANGED;

import static com.android.internal.util.function.pooled.PooledLambda.obtainMessage;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemService;
import android.content.ComponentName;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.RemoteException;
import android.service.intelligence.InteractionSessionId;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.intelligence.ContentCaptureEvent.EventType;

import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/*
 * NOTE: all methods in this class should return right away, or do the real work in a handler
 * thread.
 *
 * Hence, the only field that must be thread-safe is mEnabled, which is called at the beginning
 * of every method.
 */
/**
 * TODO(b/111276913): add javadocs / implement
 */
@SystemService(Context.CONTENT_CAPTURE_MANAGER_SERVICE)
public final class ContentCaptureManager {

    private static final String TAG = "ContentCaptureManager";

    // TODO(b/111276913): define a way to dynamically set them(for example, using settings?)
    private static final boolean VERBOSE = false;
    private static final boolean DEBUG = true; // STOPSHIP if not set to false

    /**
     * Used to indicate that a text change was caused by user input (for example, through IME).
     */
    //TODO(b/111276913): link to notifyTextChanged() method once available
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

    private static final String BG_THREAD_NAME = "intel_svc_streamer_thread";

    /**
     * Maximum number of events that are buffered before sent to the app.
     */
    // TODO(b/111276913): use settings
    private static final int MAX_BUFFER_SIZE = 100;

    @NonNull
    private final AtomicBoolean mDisabled = new AtomicBoolean();

    @NonNull
    private final Context mContext;

    @Nullable
    private final IIntelligenceManager mService;

    @Nullable
    private InteractionSessionId mId;

    private int mState = STATE_UNKNOWN;

    @Nullable
    private IBinder mApplicationToken;

    @Nullable
    private ComponentName mComponentName;

    /**
     * List of events held to be sent as a batch.
     */
    @Nullable
    private ArrayList<ContentCaptureEvent> mEvents;

    // TODO(b/111276913): use UI Thread directly (as calls are one-way) or a shared thread / handler
    // held at the Application level
    private final Handler mHandler;

    /** @hide */
    public ContentCaptureManager(@NonNull Context context, @Nullable IIntelligenceManager service) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
        if (VERBOSE) {
            Log.v(TAG, "Constructor for " + context.getPackageName());
        }
        mService = service;
        // TODO(b/111276913): use an existing bg thread instead...
        final HandlerThread bgThread = new HandlerThread(BG_THREAD_NAME);
        bgThread.start();
        mHandler = Handler.createAsync(bgThread.getLooper());
    }

    /** @hide */
    public void onActivityCreated(@NonNull IBinder token, @NonNull ComponentName componentName) {
        if (!isContentCaptureEnabled()) return;

        mHandler.sendMessage(obtainMessage(ContentCaptureManager::handleStartSession, this,
                token, componentName));
    }

    private void handleStartSession(@NonNull IBinder token, @NonNull ComponentName componentName) {
        if (mState != STATE_UNKNOWN) {
            // TODO(b/111276913): revisit this scenario
            Log.w(TAG, "ignoring handleStartSession(" + token + ") while on state "
                    + getStateAsString(mState));
            return;
        }
        mState = STATE_WAITING_FOR_SERVER;
        mId = new InteractionSessionId();
        mApplicationToken = token;
        mComponentName = componentName;

        if (VERBOSE) {
            Log.v(TAG, "handleStartSession(): token=" + token + ", act="
                    + getActivityDebugName() + ", id=" + mId);
        }
        final int flags = 0; // TODO(b/111276913): get proper flags

        try {
            mService.startSession(mContext.getUserId(), mApplicationToken, componentName,
                    mId, flags, new IResultReceiver.Stub() {
                        @Override
                        public void send(int resultCode, Bundle resultData) {
                            handleSessionStarted(resultCode);
                        }
                    });
        } catch (RemoteException e) {
            Log.w(TAG, "Error starting session for " + componentName.flattenToShortString() + ": "
                    + e);
        }
    }

    private void handleSessionStarted(int resultCode) {
        mState = resultCode;
        mDisabled.set(mState == STATE_DISABLED);
        if (VERBOSE) {
            Log.v(TAG, "onActivityStarted() result: code=" + resultCode + ", id=" + mId
                    + ", state=" + getStateAsString(mState) + ", disabled=" + mDisabled.get());
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
        if (numberEvents < MAX_BUFFER_SIZE && !forceFlush) {
            // Buffering events, return right away...
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

        if (mId == null) {
            // Sanity check - should not happen
            Log.wtf(TAG, "null session id for " + getActivityDebugName());
            return;
        }

        try {
            if (DEBUG) {
                Log.d(TAG, "Flushing " + numberEvents + " event(s) for " + getActivityDebugName());
            }
            mService.sendEvents(mContext.getUserId(), mId, mEvents);
            // TODO(b/111276913): decide whether we should clear or set it to null, as each has
            // its own advantages: clearing will save extra allocations while the session is
            // active, while setting to null would save memory if there's no more event coming.
            mEvents.clear();
        } catch (RemoteException e) {
            Log.w(TAG, "Error sending " + numberEvents + " for " + getActivityDebugName()
                    + ": " + e);
        }
    }

    /**
     * Used for intermediate events (i.e, other than created and destroyed).
     *
     * @hide
     */
    public void onActivityLifecycleEvent(@EventType int type) {
        if (!isContentCaptureEnabled()) return;
        if (VERBOSE) {
            Log.v(TAG, "onActivityLifecycleEvent() for " + getActivityDebugName()
                    + ": " + ContentCaptureEvent.getTypeAsString(type));
        }
        mHandler.sendMessage(obtainMessage(ContentCaptureManager::handleSendEvent, this,
                new ContentCaptureEvent(type), /* forceFlush= */ true));
    }

    /** @hide */
    public void onActivityDestroyed() {
        if (!isContentCaptureEnabled()) return;

        //TODO(b/111276913): check state (for example, how to handle if it's waiting for remote
        // id) and send it to the cache of batched commands
        if (VERBOSE) {
            Log.v(TAG, "onActivityDestroyed(): state=" + getStateAsString(mState)
                    + ", mId=" + mId);
        }

        mHandler.sendMessage(obtainMessage(ContentCaptureManager::handleFinishSession, this));
    }

    private void handleFinishSession() {
        //TODO(b/111276913): right now both the ContentEvents and lifecycle sessions are sent
        // to system_server, so it's ok to call both in sequence here. But once we split
        // them so the events are sent directly to the service, we need to make sure they're
        // sent in order.
        try {
            if (DEBUG) {
                Log.d(TAG, "Finishing session " + mId + " with "
                        + (mEvents == null ? 0 : mEvents.size()) + " event(s) for "
                        + getActivityDebugName());
            }

            mService.finishSession(mContext.getUserId(), mId, mEvents);
        } catch (RemoteException e) {
            Log.e(TAG, "Error finishing session " + mId + " for " + getActivityDebugName()
                    + ": " + e);
        } finally {
            handleResetState();
        }
    }

    private void handleResetState() {
        mState = STATE_UNKNOWN;
        mId = null;
        mApplicationToken = null;
        mComponentName = null;
        mEvents = null;
    }

    /**
     * Notifies the Intelligence Service that a node has been added to the view structure.
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

        mHandler.sendMessage(obtainMessage(ContentCaptureManager::handleSendEvent, this,
                new ContentCaptureEvent(TYPE_VIEW_APPEARED)
                        .setViewNode(((ViewNode.ViewStructureImpl) node).mNode),
                        /* forceFlush= */ false));
    }

    /**
     * Notifies the Intelligence Service that a node has been removed from the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for standard views.
     *
     * @param id id of the node that has been removed.
     */
    public void notifyViewDisappeared(@NonNull AutofillId id) {
        Preconditions.checkNotNull(id);
        if (!isContentCaptureEnabled()) return;

        mHandler.sendMessage(obtainMessage(ContentCaptureManager::handleSendEvent, this,
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

        mHandler.sendMessage(obtainMessage(ContentCaptureManager::handleSendEvent, this,
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
     */
    @NonNull
    public ViewStructure newVirtualViewStructure(@NonNull AutofillId parentId, int virtualId) {
        return new ViewNode.ViewStructureImpl(parentId, virtualId);
    }

    /**
     * Returns the component name of the system service that is consuming the captured events for
     * the current user.
     */
    @Nullable
    public ComponentName getServiceComponentName() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Checks whether content capture is enabled for this activity.
     */
    public boolean isContentCaptureEnabled() {
        return mService != null && !mDisabled.get();
    }

    /**
     * Called by apps to explicitly enable or disable content capture.
     *
     * <p><b>Note: </b> this call is not persisted accross reboots, so apps should typically call
     * it on {@link android.app.Activity#onCreate(android.os.Bundle, android.os.PersistableBundle)}.
     */
    public void setContentCaptureEnabled(boolean enabled) {
        //TODO(b/111276913): implement
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("IntelligenceManager");
        final String prefix2 = prefix + "  ";
        pw.print(prefix2); pw.print("mContext: "); pw.println(mContext);
        pw.print(prefix2); pw.print("user: "); pw.println(mContext.getUserId());
        if (mService != null) {
            pw.print(prefix2); pw.print("mService: "); pw.println(mService);
        }
        pw.print(prefix2); pw.print("mDisabled: "); pw.println(mDisabled.get());
        pw.print(prefix2); pw.print("isEnabled(): "); pw.println(isContentCaptureEnabled());
        if (mId != null) {
            pw.print(prefix2); pw.print("id: "); pw.println(mId);
        }
        pw.print(prefix2); pw.print("state: "); pw.print(mState); pw.print(" (");
        pw.print(getStateAsString(mState)); pw.println(")");
        if (mApplicationToken != null) {
            pw.print(prefix2); pw.print("app token: "); pw.println(mApplicationToken);
        }
        if (mComponentName != null) {
            pw.print(prefix2); pw.print("component name: ");
            pw.println(mComponentName.flattenToShortString());
        }
        if (mEvents != null) {
            final int numberEvents = mEvents.size();
            pw.print(prefix2); pw.print("buffered events: "); pw.print(numberEvents);
            pw.print('/'); pw.println(MAX_BUFFER_SIZE);
            if (VERBOSE && numberEvents > 0) {
                final String prefix3 = prefix2 + "  ";
                for (int i = 0; i < numberEvents; i++) {
                    final ContentCaptureEvent event = mEvents.get(i);
                    pw.print(prefix3); pw.print(i); pw.print(": "); event.dump(pw);
                    pw.println();
                }
            }
        }
    }

    /**
     * Gets a string that can be used to identify the activity on logging statements.
     */
    private String getActivityDebugName() {
        return mComponentName == null ? mContext.getPackageName()
                : mComponentName.flattenToShortString();
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
            default:
                return "INVALID:" + state;
        }
    }
}
