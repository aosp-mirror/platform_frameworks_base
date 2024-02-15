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

import static android.os.Build.VERSION_CODES.UPSIDE_DOWN_CAKE;
import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;
import static android.view.contentcapture.ContentCaptureManager.NO_SESSION_ID;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.compat.CompatChanges;
import android.compat.annotation.ChangeId;
import android.compat.annotation.EnabledSince;
import android.content.ComponentName;
import android.graphics.Insets;
import android.graphics.Rect;
import android.os.IBinder;
import android.util.DebugUtils;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.os.IResultReceiver;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Session used when notifying the Android system about events associated with views.
 */
public abstract class ContentCaptureSession implements AutoCloseable {

    private static final String TAG = ContentCaptureSession.class.getSimpleName();

    // TODO(b/158778794): to make the session ids truly globally unique across
    //  processes, we may need to explore other options.
    private static final SecureRandom ID_GENERATOR = new SecureRandom();

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

    /**
     * Initial state, when there is no session.
     *
     * @hide
     */
    // NOTE: not prefixed by STATE_ so it's not printed on getStateAsString()
    public static final int UNKNOWN_STATE = 0x0;

    /**
     * Service's startSession() was called, but server didn't confirm it was created yet.
     *
     * @hide
     */
    public static final int STATE_WAITING_FOR_SERVER = 0x1;

    /**
     * Session is active.
     *
     * @hide
     */
    public static final int STATE_ACTIVE = 0x2;

    /**
     * Session is disabled because there is no service for this user.
     *
     * @hide
     */
    public static final int STATE_DISABLED = 0x4;

    /**
     * Session is disabled because its id already existed on server.
     *
     * @hide
     */
    public static final int STATE_DUPLICATED_ID = 0x8;

    /**
     * Session is disabled because service is not set for user.
     *
     * @hide
     */
    public static final int STATE_NO_SERVICE = 0x10;

    /**
     * Session is disabled by FLAG_SECURE
     *
     * @hide
     */
    public static final int STATE_FLAG_SECURE = 0x20;

    /**
     * Session is disabled manually by the specific app
     * (through {@link ContentCaptureManager#setContentCaptureEnabled(boolean)}).
     *
     * @hide
     */
    public static final int STATE_BY_APP = 0x40;

    /**
     * Session is disabled because session start was never replied.
     *
     * @hide
     */
    public static final int STATE_NO_RESPONSE = 0x80;

    /**
     * Session is disabled because an internal error.
     *
     * @hide
     */
    public static final int STATE_INTERNAL_ERROR = 0x100;

    /**
     * Session is disabled because service didn't allowlist package or activity.
     *
     * @hide
     */
    public static final int STATE_NOT_WHITELISTED = 0x200;

    /**
     * Session is disabled because the service died.
     *
     * @hide
     */
    public static final int STATE_SERVICE_DIED = 0x400;

    /**
     * Session is disabled because the service package is being udpated.
     *
     * @hide
     */
    public static final int STATE_SERVICE_UPDATING = 0x800;

    /**
     * Session is enabled, after the service died and came back to live.
     *
     * @hide
     */
    public static final int STATE_SERVICE_RESURRECTED = 0x1000;

    private static final int INITIAL_CHILDREN_CAPACITY = 5;

    /** @hide */
    public static final int FLUSH_REASON_FULL = 1;
    /** @hide */
    public static final int FLUSH_REASON_VIEW_ROOT_ENTERED = 2;
    /** @hide */
    public static final int FLUSH_REASON_SESSION_STARTED = 3;
    /** @hide */
    public static final int FLUSH_REASON_SESSION_FINISHED = 4;
    /** @hide */
    public static final int FLUSH_REASON_IDLE_TIMEOUT = 5;
    /** @hide */
    public static final int FLUSH_REASON_TEXT_CHANGE_TIMEOUT = 6;
    /** @hide */
    public static final int FLUSH_REASON_SESSION_CONNECTED = 7;
    /** @hide */
    public static final int FLUSH_REASON_FORCE_FLUSH = 8;
    /** @hide */
    public static final int FLUSH_REASON_VIEW_TREE_APPEARING = 9;
    /** @hide */
    public static final int FLUSH_REASON_VIEW_TREE_APPEARED = 10;

    /**
     * After {@link android.os.Build.VERSION_CODES#UPSIDE_DOWN_CAKE},
     * {@link #notifyViewsDisappeared(AutofillId, long[])} wraps
     * the virtual children with a pair of view tree appearing and view tree appeared events.
     */
    @ChangeId
    @EnabledSince(targetSdkVersion = UPSIDE_DOWN_CAKE)
    static final long NOTIFY_NODES_DISAPPEAR_NOW_SENDS_TREE_EVENTS = 258825825L;

    /** @hide */
    @IntDef(
            prefix = {"FLUSH_REASON_"},
            value = {
                FLUSH_REASON_FULL,
                FLUSH_REASON_VIEW_ROOT_ENTERED,
                FLUSH_REASON_SESSION_STARTED,
                FLUSH_REASON_SESSION_FINISHED,
                FLUSH_REASON_IDLE_TIMEOUT,
                FLUSH_REASON_TEXT_CHANGE_TIMEOUT,
                FLUSH_REASON_SESSION_CONNECTED,
                FLUSH_REASON_FORCE_FLUSH,
                FLUSH_REASON_VIEW_TREE_APPEARING,
                FLUSH_REASON_VIEW_TREE_APPEARED
            })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlushReason {}

    private final Object mLock = new Object();

    /**
     * Guard use to ignore events after it's destroyed.
     */
    @NonNull
    @GuardedBy("mLock")
    private boolean mDestroyed;

    /** @hide */
    @Nullable
    protected final int mId;

    private int mState = UNKNOWN_STATE;

    // Lazily created on demand.
    private ContentCaptureSessionId mContentCaptureSessionId;

    /**
     * {@link ContentCaptureContext} set by client, or {@code null} when it's the
     * {@link ContentCaptureManager#getMainContentCaptureSession() default session} for the
     * context.
     */
    @Nullable
    private ContentCaptureContext mClientContext;

    /**
     * List of children session.
     */
    @Nullable
    @GuardedBy("mLock")
    private ArrayList<ContentCaptureSession> mChildren;

    /** @hide */
    protected ContentCaptureSession() {
        this(getRandomSessionId());
    }

    /** @hide */
    @VisibleForTesting
    public ContentCaptureSession(int id) {
        Preconditions.checkArgument(id != NO_SESSION_ID);
        mId = id;
    }

    // Used by ChildContentCaptureSession
    ContentCaptureSession(@NonNull ContentCaptureContext initialContext) {
        this();
        mClientContext = Objects.requireNonNull(initialContext);
    }

    /** @hide */
    @NonNull
    abstract ContentCaptureSession getMainCaptureSession();

    abstract void start(@NonNull IBinder token, @NonNull IBinder shareableActivityToken,
            @NonNull ComponentName component, int flags);

    abstract boolean isDisabled();

    /**
     * Sets the disabled state of content capture.
     *
     * @return whether disabled state was changed.
     */
    abstract boolean setDisabled(boolean disabled);

    /**
     * Gets the id used to identify this session.
     */
    @NonNull
    public final ContentCaptureSessionId getContentCaptureSessionId() {
        if (mContentCaptureSessionId == null) {
            mContentCaptureSessionId = new ContentCaptureSessionId(mId);
        }
        return mContentCaptureSessionId;
    }

    /** @hide */
    @NonNull
    public int getId() {
        return mId;
    }

    /**
     * Creates a new {@link ContentCaptureSession}.
     *
     * <p>See {@link View#setContentCaptureSession(ContentCaptureSession)} for more info.
     */
    @NonNull
    public final ContentCaptureSession createContentCaptureSession(
            @NonNull ContentCaptureContext context) {
        final ContentCaptureSession child = newChild(context);
        if (sDebug) {
            Log.d(TAG, "createContentCaptureSession(" + context + ": parent=" + mId + ", child="
                    + child.mId);
        }
        synchronized (mLock) {
            if (mChildren == null) {
                mChildren = new ArrayList<>(INITIAL_CHILDREN_CAPACITY);
            }
            mChildren.add(child);
        }
        return child;
    }

    abstract ContentCaptureSession newChild(@NonNull ContentCaptureContext context);

    /**
     * Flushes the buffered events to the service.
     */
    abstract void flush(@FlushReason int reason);

    /**
     * Sets the {@link ContentCaptureContext} associated with the session.
     *
     * <p>Typically used to change the context associated with the default session from an activity.
     */
    public final void setContentCaptureContext(@Nullable ContentCaptureContext context) {
        if (!isContentCaptureEnabled()) return;

        mClientContext = context;
        updateContentCaptureContext(context);
    }

    abstract void updateContentCaptureContext(@Nullable ContentCaptureContext context);

    /**
     * Gets the {@link ContentCaptureContext} associated with the session.
     *
     * @return context set on constructor or by
     *         {@link #setContentCaptureContext(ContentCaptureContext)}, or {@code null} if never
     *         explicitly set.
     */
    @Nullable
    public final ContentCaptureContext getContentCaptureContext() {
        return mClientContext;
    }

    /**
     * Destroys this session, flushing out all pending notifications to the service.
     *
     * <p>Once destroyed, any new notification will be dropped.
     */
    public final void destroy() {
        synchronized (mLock) {
            if (mDestroyed) {
                if (sDebug) Log.d(TAG, "destroy(" + mId + "): already destroyed");
                return;
            }
            mDestroyed = true;

            // TODO(b/111276913): check state (for example, how to handle if it's waiting for remote
            // id) and send it to the cache of batched commands
            if (sVerbose) {
                Log.v(TAG, "destroy(): state=" + getStateAsString(mState) + ", mId=" + mId);
            }
            // Finish children first
            if (mChildren != null) {
                final int numberChildren = mChildren.size();
                if (sVerbose) Log.v(TAG, "Destroying " + numberChildren + " children first");
                for (int i = 0; i < numberChildren; i++) {
                    final ContentCaptureSession child = mChildren.get(i);
                    try {
                        child.destroy();
                    } catch (Exception e) {
                        Log.w(TAG, "exception destroying child session #" + i + ": " + e);
                    }
                }
            }
        }

        onDestroy();
    }

    abstract void onDestroy();

    /** @hide */
    @Override
    public void close() {
        destroy();
    }

    /**
     * Notifies the Content Capture Service that a node has been added to the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for views that return {@code true} on
     * {@link View#onProvideContentCaptureStructure(ViewStructure, int)}.
     *
     * <p>Consider use {@link #notifyViewsAppeared} which has a better performance when notifying
     * a list of nodes has appeared.
     *
     * @param node node that has been added.
     */
    public final void notifyViewAppeared(@NonNull ViewStructure node) {
        Objects.requireNonNull(node);
        if (!isContentCaptureEnabled()) return;

        if (!(node instanceof ViewNode.ViewStructureImpl)) {
            throw new IllegalArgumentException("Invalid node class: " + node.getClass());
        }

        internalNotifyViewAppeared(mId, (ViewStructureImpl) node);
    }

    abstract void internalNotifyViewAppeared(
            int sessionId, @NonNull ViewNode.ViewStructureImpl node);

    /**
     * Notifies the Content Capture Service that a node has been removed from the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for standard views.
     *
     * <p>Consider use {@link #notifyViewsDisappeared} which has a better performance when notifying
     * a list of nodes has disappeared.
     *
     * @param id id of the node that has been removed.
     */
    public final void notifyViewDisappeared(@NonNull AutofillId id) {
        Objects.requireNonNull(id);
        if (!isContentCaptureEnabled()) return;

        internalNotifyViewDisappeared(mId, id);
    }

    abstract void internalNotifyViewDisappeared(int sessionId, @NonNull AutofillId id);

    /**
     * Notifies the Content Capture Service that a list of nodes has appeared in the view structure.
     *
     * <p>Typically called manually by views that handle their own virtual view hierarchy.
     *
     * @param appearedNodes nodes that have appeared. Each element represents a view node that has
     * been added to the view structure. The order of the elements is important, which should be
     * preserved as the attached order of when the node is attached to the virtual view hierarchy.
     */
    public final void notifyViewsAppeared(@NonNull List<ViewStructure> appearedNodes) {
        Preconditions.checkCollectionElementsNotNull(appearedNodes, "appearedNodes");
        if (!isContentCaptureEnabled()) return;

        for (int i = 0; i < appearedNodes.size(); i++) {
            ViewStructure v = appearedNodes.get(i);
            if (!(v instanceof ViewNode.ViewStructureImpl)) {
                throw new IllegalArgumentException("Invalid class: " + v.getClass());
            }
        }

        internalNotifyViewTreeEvent(mId, /* started= */ true);
        for (int i = 0; i < appearedNodes.size(); i++) {
            ViewStructure v = appearedNodes.get(i);
            internalNotifyViewAppeared(mId, (ViewStructureImpl) v);
        }
        internalNotifyViewTreeEvent(mId, /* started= */ false);
    }

    /**
     * Notifies the Content Capture Service that many nodes has been removed from a virtual view
     * structure.
     *
     * <p>Should only be called by views that handle their own virtual view hierarchy.
     *
     * <p>After UPSIDE_DOWN_CAKE, this method wraps the virtual children with a pair of view tree
     * appearing and view tree appeared events.
     *
     * @param hostId id of the non-virtual view hosting the virtual view hierarchy (it can be
     * obtained by calling {@link ViewStructure#getAutofillId()}).
     * @param virtualIds ids of the virtual children.
     *
     * @throws IllegalArgumentException if the {@code hostId} is an autofill id for a virtual view.
     * @throws IllegalArgumentException if {@code virtualIds} is empty
     */
    public final void notifyViewsDisappeared(@NonNull AutofillId hostId,
            @NonNull long[] virtualIds) {
        Preconditions.checkArgument(hostId.isNonVirtual(), "hostId cannot be virtual: %s", hostId);
        Preconditions.checkArgument(!ArrayUtils.isEmpty(virtualIds), "virtual ids cannot be empty");
        if (!isContentCaptureEnabled()) return;

        if (CompatChanges.isChangeEnabled(NOTIFY_NODES_DISAPPEAR_NOW_SENDS_TREE_EVENTS)) {
            internalNotifyViewTreeEvent(mId, /* started= */ true);
        }
        // TODO(b/123036895): use a internalNotifyViewsDisappeared that optimizes how the event is
        // parcelized
        for (long id : virtualIds) {
            internalNotifyViewDisappeared(mId, new AutofillId(hostId, id, mId));
        }
        if (CompatChanges.isChangeEnabled(NOTIFY_NODES_DISAPPEAR_NOW_SENDS_TREE_EVENTS)) {
            internalNotifyViewTreeEvent(mId, /* started= */ false);
        }
    }

    /**
     * Notifies the Intelligence Service that the value of a text node has been changed.
     *
     * @param id of the node.
     * @param text new text.
     */
    public final void notifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text) {
        Objects.requireNonNull(id);

        if (!isContentCaptureEnabled()) return;

        internalNotifyViewTextChanged(mId, id, text);
    }

    abstract void internalNotifyViewTextChanged(int sessionId, @NonNull AutofillId id,
            @Nullable CharSequence text);

    /**
     * Notifies the Intelligence Service that the insets of a view have changed.
     */
    public final void notifyViewInsetsChanged(@NonNull Insets viewInsets) {
        Objects.requireNonNull(viewInsets);

        if (!isContentCaptureEnabled()) return;

        internalNotifyViewInsetsChanged(mId, viewInsets);
    }

    abstract void internalNotifyViewInsetsChanged(int sessionId, @NonNull Insets viewInsets);

    /** @hide */
    public void notifyViewTreeEvent(boolean started) {
        internalNotifyViewTreeEvent(mId, started);
    }

    /** @hide */
    abstract void internalNotifyViewTreeEvent(int sessionId, boolean started);

    /**
     * Notifies the Content Capture Service that a session has resumed.
     */
    public final void notifySessionResumed() {
        if (!isContentCaptureEnabled()) return;

        internalNotifySessionResumed();
    }

    abstract void internalNotifySessionResumed();

    /**
     * Notifies the Content Capture Service that a session has paused.
     */
    public final void notifySessionPaused() {
        if (!isContentCaptureEnabled()) return;

        internalNotifySessionPaused();
    }

    abstract void internalNotifySessionPaused();

    abstract void internalNotifyChildSessionStarted(int parentSessionId, int childSessionId,
            @NonNull ContentCaptureContext clientContext);

    abstract void internalNotifyChildSessionFinished(int parentSessionId, int childSessionId);

    abstract void internalNotifyContextUpdated(
            int sessionId, @Nullable ContentCaptureContext context);

    /** @hide */
    public abstract void notifyWindowBoundsChanged(int sessionId, @NonNull Rect bounds);

    /** @hide */
    public abstract void notifyContentCaptureEvents(
            @NonNull SparseArray<ArrayList<Object>> contentCaptureEvents);

    /**
     * Creates a {@link ViewStructure} for a "standard" view.
     *
     * <p>This method should be called after a visible view is laid out; the view then must populate
     * the structure and pass it to {@link #notifyViewAppeared(ViewStructure)}.
     *
     * <b>Note: </b>views that manage a virtual structure under this view must populate just the
     * node representing this view and return right away, then asynchronously report (not
     * necessarily in the UI thread) when the children nodes appear, disappear or have their text
     * changed by calling {@link ContentCaptureSession#notifyViewAppeared(ViewStructure)},
     * {@link ContentCaptureSession#notifyViewDisappeared(AutofillId)}, and
     * {@link ContentCaptureSession#notifyViewTextChanged(AutofillId, CharSequence)} respectively.
     * The structure for the a child must be created using
     * {@link ContentCaptureSession#newVirtualViewStructure(AutofillId, long)}, and the
     * {@code autofillId} for a child can be obtained either through
     * {@code childStructure.getAutofillId()} or
     * {@link ContentCaptureSession#newAutofillId(AutofillId, long)}.
     *
     * <p>When the virtual view hierarchy represents a web page, you should also:
     *
     * <ul>
     * <li>Call {@link ContentCaptureManager#getContentCaptureConditions()} to infer content capture
     * events should be generate for that URL.
     * <li>Create a new {@link ContentCaptureSession} child for every HTML element that renders a
     * new URL (like an {@code IFRAME}) and use that session to notify events from that subtree.
     * </ul>
     *
     * <p><b>Note: </b>the following methods of the {@code structure} will be ignored:
     * <ul>
     * <li>{@link ViewStructure#setChildCount(int)}
     * <li>{@link ViewStructure#addChildCount(int)}
     * <li>{@link ViewStructure#getChildCount()}
     * <li>{@link ViewStructure#newChild(int)}
     * <li>{@link ViewStructure#asyncNewChild(int)}
     * <li>{@link ViewStructure#asyncCommit()}
     * <li>{@link ViewStructure#setWebDomain(String)}
     * <li>{@link ViewStructure#newHtmlInfoBuilder(String)}
     * <li>{@link ViewStructure#setHtmlInfo(android.view.ViewStructure.HtmlInfo)}
     * <li>{@link ViewStructure#setDataIsSensitive(boolean)}
     * <li>{@link ViewStructure#setAlpha(float)}
     * <li>{@link ViewStructure#setElevation(float)}
     * <li>{@link ViewStructure#setTransformation(android.graphics.Matrix)}
     * </ul>
     */
    @NonNull
    public final ViewStructure newViewStructure(@NonNull View view) {
        return new ViewNode.ViewStructureImpl(view);
    }

    /**
     * Creates a new {@link AutofillId} for a virtual child, so it can be used to uniquely identify
     * the children in the session.
     *
     * @param hostId id of the non-virtual view hosting the virtual view hierarchy (it can be
     * obtained by calling {@link ViewStructure#getAutofillId()}).
     * @param virtualChildId id of the virtual child, relative to the parent.
     *
     * @return if for the virtual child
     *
     * @throws IllegalArgumentException if the {@code parentId} is a virtual child id.
     */
    public @NonNull AutofillId newAutofillId(@NonNull AutofillId hostId, long virtualChildId) {
        Objects.requireNonNull(hostId);
        Preconditions.checkArgument(hostId.isNonVirtual(), "hostId cannot be virtual: %s", hostId);
        return new AutofillId(hostId, virtualChildId, mId);
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
    public final ViewStructure newVirtualViewStructure(@NonNull AutofillId parentId,
            long virtualId) {
        return new ViewNode.ViewStructureImpl(parentId, virtualId, mId);
    }

    boolean isContentCaptureEnabled() {
        synchronized (mLock) {
            return !mDestroyed;
        }
    }

    @CallSuper
    void dump(@NonNull String prefix, @NonNull PrintWriter pw) {
        pw.print(prefix); pw.print("id: "); pw.println(mId);
        if (mClientContext != null) {
            pw.print(prefix); mClientContext.dump(pw); pw.println();
        }
        synchronized (mLock) {
            pw.print(prefix); pw.print("destroyed: "); pw.println(mDestroyed);
            if (mChildren != null && !mChildren.isEmpty()) {
                final String prefix2 = prefix + "  ";
                final int numberChildren = mChildren.size();
                pw.print(prefix); pw.print("number children: "); pw.println(numberChildren);
                for (int i = 0; i < numberChildren; i++) {
                    final ContentCaptureSession child = mChildren.get(i);
                    pw.print(prefix); pw.print(i); pw.println(": "); child.dump(prefix2, pw);
                }
            }
        }
    }

    @Override
    public String toString() {
        return Integer.toString(mId);
    }

    /** @hide */
    @NonNull
    protected static String getStateAsString(int state) {
        return state + " (" + (state == UNKNOWN_STATE ? "UNKNOWN"
                : DebugUtils.flagsToString(ContentCaptureSession.class, "STATE_", state)) + ")";
    }

    /** @hide */
    @NonNull
    public static String getFlushReasonAsString(@FlushReason int reason) {
        switch (reason) {
            case FLUSH_REASON_FULL:
                return "FULL";
            case FLUSH_REASON_VIEW_ROOT_ENTERED:
                return "VIEW_ROOT";
            case FLUSH_REASON_SESSION_STARTED:
                return "STARTED";
            case FLUSH_REASON_SESSION_FINISHED:
                return "FINISHED";
            case FLUSH_REASON_IDLE_TIMEOUT:
                return "IDLE";
            case FLUSH_REASON_TEXT_CHANGE_TIMEOUT:
                return "TEXT_CHANGE";
            case FLUSH_REASON_SESSION_CONNECTED:
                return "CONNECTED";
            case FLUSH_REASON_FORCE_FLUSH:
                return "FORCE_FLUSH";
            case FLUSH_REASON_VIEW_TREE_APPEARING:
                return "VIEW_TREE_APPEARING";
            case FLUSH_REASON_VIEW_TREE_APPEARED:
                return "VIEW_TREE_APPEARED";
            default:
                return "UNKNOWN-" + reason;
        }
    }

    private static int getRandomSessionId() {
        int id;
        do {
            id = ID_GENERATOR.nextInt();
        } while (id == NO_SESSION_ID);
        return id;
    }
}
