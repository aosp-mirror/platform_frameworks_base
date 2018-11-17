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
import android.annotation.SystemApi;
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

import com.android.internal.annotations.GuardedBy;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Set;

/**
 * TODO(b/111276913): add javadocs / implement
 */
@SystemService(Context.INTELLIGENCE_MANAGER_SERVICE)
public final class IntelligenceManager {

    private static final String TAG = "IntelligenceManager";

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
     * Maximum number of events that are delayed for an app.
     *
     * <p>If the session is not started after the limit is reached, it's discarded.
     */
    private static final int MAX_DELAYED_SIZE = 20;

    private final Context mContext;

    @Nullable
    private final IIntelligenceManager mService;

    private final Object mLock = new Object();

    @Nullable
    @GuardedBy("mLock")
    private InteractionSessionId mId;

    @GuardedBy("mLock")
    private int mState = STATE_UNKNOWN;

    @GuardedBy("mLock")
    private IBinder mApplicationToken;

    // TODO(b/111276913): replace by an interface name implemented by Activity, similar to
    // AutofillClient
    @GuardedBy("mLock")
    private ComponentName mComponentName;

    // TODO(b/111276913): create using maximum batch size as capacity
    /**
     * List of events held to be sent as a batch.
     */
    @GuardedBy("mLock")
    private final ArrayList<ContentCaptureEvent> mEvents = new ArrayList<>();

    private final Handler mHandler;

    /** @hide */
    public IntelligenceManager(@NonNull Context context, @Nullable IIntelligenceManager service) {
        mContext = Preconditions.checkNotNull(context, "context cannot be null");
        mService = service;

        // TODO(b/111276913): use an existing bg thread instead...
        final HandlerThread bgThread = new HandlerThread(BG_THREAD_NAME);
        bgThread.start();
        mHandler = Handler.createAsync(bgThread.getLooper());
    }

    /** @hide */
    public void onActivityCreated(@NonNull IBinder token, @NonNull ComponentName componentName) {
        if (!isContentCaptureEnabled()) return;

        synchronized (mLock) {
            if (mState != STATE_UNKNOWN) {
                // TODO(b/111276913): revisit this scenario
                Log.w(TAG, "ignoring onActivityStarted(" + token + ") while on state "
                        + getStateAsString(mState));
                return;
            }
            mState = STATE_WAITING_FOR_SERVER;
            mId = new InteractionSessionId();
            mApplicationToken = token;
            mComponentName = componentName;

            if (VERBOSE) {
                Log.v(TAG, "onActivityCreated(): token=" + token + ", act="
                        + getActivityDebugNameLocked() + ", id=" + mId);
            }
            final int flags = 0; // TODO(b/111276913): get proper flags

            try {
                mService.startSession(mContext.getUserId(), mApplicationToken, componentName,
                        mId, flags, new IResultReceiver.Stub() {
                            @Override
                            public void send(int resultCode, Bundle resultData)
                                    throws RemoteException {
                                synchronized (mLock) {
                                    mState = resultCode;
                                    if (VERBOSE) {
                                        Log.v(TAG, "onActivityStarted() result: code=" + resultCode
                                                + ", id=" + mId
                                                + ", state=" + getStateAsString(mState));
                                    }
                                }
                            }
                        });
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    //TODO(b/111276913): should buffer event (and call service on handler thread), instead of
    // calling right away
    private void sendEvent(@NonNull ContentCaptureEvent event) {
        mHandler.sendMessage(obtainMessage(IntelligenceManager::handleSendEvent, this, event));
    }

    private void handleSendEvent(@NonNull ContentCaptureEvent event) {

        //TODO(b/111276913): make a copy and don't use lock
        synchronized (mLock) {
            mEvents.add(event);
            final int numberEvents = mEvents.size();
            if (mState != STATE_ACTIVE) {
                if (numberEvents >= MAX_DELAYED_SIZE) {
                    // Typically happens on system apps that are started before the system service
                    // is ready (like com.android.settings/.FallbackHome)
                    //TODO(b/111276913): try to ignore session while system is not ready / boot
                    // not complete instead. Similarly, the manager service should return right away
                    // when the user does not have a service set
                    if (VERBOSE) {
                        Log.v(TAG, "Closing session for " + getActivityDebugNameLocked()
                                + " after " + numberEvents + " delayed events and state "
                                + getStateAsString(mState));
                    }
                    // TODO(b/111276913): blacklist activity / use special flag to indicate that
                    // when it's launched again
                    resetStateLocked();
                    return;
                }

                if (VERBOSE) {
                    Log.v(TAG, "Delaying " + numberEvents + " events for "
                            + getActivityDebugNameLocked() + " while on state "
                            + getStateAsString(mState));
                }
                return;
            }

            if (mId == null) {
                // Sanity check - should not happen
                Log.wtf(TAG, "null session id for " + mComponentName);
                return;
            }

            //TODO(b/111276913): right now we're sending sending right away (unless not ready), but
            // we should hold the events and flush later.
            try {
                if (DEBUG) {
                    Log.d(TAG, "Sending " + numberEvents + " event(s) for "
                            + getActivityDebugNameLocked());
                }
                mService.sendEvents(mContext.getUserId(), mId, mEvents);
                mEvents.clear();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
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
            Log.v(TAG, "onActivityLifecycleEvent() for " + getActivityDebugNameLocked()
                    + ": " + ContentCaptureEvent.getTypeAsString(type));
        }
        sendEvent(new ContentCaptureEvent(type));
    }

    /** @hide */
    public void onActivityDestroyed() {
        if (!isContentCaptureEnabled()) return;

        synchronized (mLock) {
            //TODO(b/111276913): check state (for example, how to handle if it's waiting for remote
            // id) and send it to the cache of batched commands

            if (VERBOSE) {
                Log.v(TAG, "onActivityDestroyed(): state=" + getStateAsString(mState)
                        + ", mId=" + mId);
            }

            try {
                mService.finishSession(mContext.getUserId(), mId);
                resetStateLocked();
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
        }
    }

    @GuardedBy("mLock")
    private void resetStateLocked() {
        mState = STATE_UNKNOWN;
        mId = null;
        mApplicationToken = null;
        mComponentName = null;
        mEvents.clear();
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
        sendEvent(new ContentCaptureEvent(TYPE_VIEW_APPEARED)
                .setViewNode(((ViewNode.ViewStructureImpl) node).mNode));
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

        sendEvent(new ContentCaptureEvent(TYPE_VIEW_DISAPPEARED).setAutofillId(id));
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

        sendEvent(new ContentCaptureEvent(TYPE_VIEW_TEXT_CHANGED, flags).setAutofillId(id)
                .setText(text));
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
     * Returns the component name of the {@code android.service.intelligence.IntelligenceService}
     * that is enabled for the current user.
     */
    @Nullable
    public ComponentName getIntelligenceServiceComponentName() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Checks whether content capture is enabled for this activity.
     */
    public boolean isContentCaptureEnabled() {
        //TODO(b/111276913): properly implement by checking if it was explicitly disabled by
        // service, or if service is not set
        // (and probably renamign to isEnabledLocked()
        return mService != null && mState != STATE_DISABLED;
    }

    /**
     * Called by apps to disable content capture.
     *
     * <p><b>Note: </b> this call is not persisted accross reboots, so apps should typically call
     * it on {@link android.app.Activity#onCreate(android.os.Bundle, android.os.PersistableBundle)}.
     */
    public void disableContentCapture() {
        //TODO(b/111276913): implement
    }

    /**
     * Called by the the service {@link android.service.intelligence.IntelligenceService}
     * to define whether content capture should be enabled for activities with such
     * {@link android.content.ComponentName}.
     *
     * <p>Useful to blacklist a particular activity.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    public void setActivityContentCaptureEnabled(@NonNull ComponentName activity,
            boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Called by the the service {@link android.service.intelligence.IntelligenceService}
     * to define whether content capture should be enabled for activities of the app with such
     * {@code packageName}.
     *
     * <p>Useful to blacklist any activity from a particular app.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    public void setPackageContentCaptureEnabled(@NonNull String packageName, boolean enabled) {
        //TODO(b/111276913): implement
    }

    /**
     * Gets the activities where content capture was disabled by
     * {@link #setActivityContentCaptureEnabled(ComponentName, boolean)}.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public Set<ComponentName> getContentCaptureDisabledActivities() {
        //TODO(b/111276913): implement
        return null;
    }

    /**
     * Gets the apps where content capture was disabled by
     * {@link #setPackageContentCaptureEnabled(String, boolean)}.
     *
     * @throws UnsupportedOperationException if not called by the UID that owns the
     * {@link android.service.intelligence.IntelligenceService} associated with the
     * current user.
     *
     * @hide
     */
    @SystemApi
    @NonNull
    public Set<String> getContentCaptureDisabledPackages() {
        //TODO(b/111276913): implement
        return null;
    }

    /** @hide */
    public void dump(String prefix, PrintWriter pw) {
        pw.print(prefix); pw.println("IntelligenceManager");
        final String prefix2 = prefix + "  ";
        synchronized (mLock) {
            pw.print(prefix2); pw.print("mContext: "); pw.println(mContext);
            pw.print(prefix2); pw.print("mService: "); pw.println(mService);
            pw.print(prefix2); pw.print("user: "); pw.println(mContext.getUserId());
            pw.print(prefix2); pw.print("enabled: "); pw.println(isContentCaptureEnabled());
            pw.print(prefix2); pw.print("id: "); pw.println(mId);
            pw.print(prefix2); pw.print("state: "); pw.print(mState); pw.print(" (");
            pw.print(getStateAsString(mState)); pw.println(")");
            pw.print(prefix2); pw.print("app token: "); pw.println(mApplicationToken);
            pw.print(prefix2); pw.print("component name: ");
            pw.println(mComponentName == null ? "null" : mComponentName.flattenToShortString());
            final int numberEvents = mEvents.size();
            pw.print(prefix2); pw.print("batched events: "); pw.println(numberEvents);
            if (numberEvents > 0) {
                for (int i = 0; i < numberEvents; i++) {
                    final ContentCaptureEvent event = mEvents.get(i);
                    pw.println(i); pw.print(": "); event.dump(pw); pw.println();
                }

            }
        }
    }

    /**
     * Gets a string that can be used to identify the activity on logging statements.
     */
    @GuardedBy("mLock")
    private String getActivityDebugNameLocked() {
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
