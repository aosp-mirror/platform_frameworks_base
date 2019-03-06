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

import static android.view.contentcapture.ContentCaptureHelper.sDebug;
import static android.view.contentcapture.ContentCaptureHelper.sVerbose;

import android.annotation.CallSuper;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.DebugUtils;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.ArrayUtils;
import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.UUID;

/**
 * Session used to notify a system-provided Content Capture service about events associated with
 * views.
 */
public abstract class ContentCaptureSession implements AutoCloseable {

    private static final String TAG = ContentCaptureSession.class.getSimpleName();

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
     * Session is disabled because service didn't whitelist package or activity.
     *
     * @hide
     */
    public static final int STATE_NOT_WHITELISTED = 0x200;

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
    @IntDef(prefix = { "FLUSH_REASON_" }, value = {
            FLUSH_REASON_FULL,
            FLUSH_REASON_VIEW_ROOT_ENTERED,
            FLUSH_REASON_SESSION_STARTED,
            FLUSH_REASON_SESSION_FINISHED,
            FLUSH_REASON_IDLE_TIMEOUT
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface FlushReason{}

    private final Object mLock = new Object();

    /**
     * Guard use to ignore events after it's destroyed.
     */
    @NonNull
    @GuardedBy("mLock")
    private boolean mDestroyed;

    /** @hide */
    @Nullable
    protected final String mId;

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
        this(UUID.randomUUID().toString());
    }

    /** @hide */
    @VisibleForTesting
    public ContentCaptureSession(@NonNull String id) {
        mId = Preconditions.checkNotNull(id);
    }

    // Used by ChildCOntentCaptureSession
    ContentCaptureSession(@NonNull ContentCaptureContext initialContext) {
        this();
        mClientContext = Preconditions.checkNotNull(initialContext);
    }

    /** @hide */
    @NonNull
    abstract MainContentCaptureSession getMainCaptureSession();

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
    @VisibleForTesting
    public int getIdAsInt() {
        // TODO(b/121197119): use sessionId instead of hashcode once it's changed to int
        return mId.hashCode();
    }

    /** @hide */
    @NonNull
    public String getId() {
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

        try {
            flush(FLUSH_REASON_SESSION_FINISHED);
        } finally {
            onDestroy();
        }
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
     * @param node node that has been added.
     */
    public final void notifyViewAppeared(@NonNull ViewStructure node) {
        Preconditions.checkNotNull(node);
        if (!isContentCaptureEnabled()) return;

        if (!(node instanceof ViewNode.ViewStructureImpl)) {
            throw new IllegalArgumentException("Invalid node class: " + node.getClass());
        }

        internalNotifyViewAppeared((ViewStructureImpl) node);
    }

    abstract void internalNotifyViewAppeared(@NonNull ViewNode.ViewStructureImpl node);

    /**
     * Notifies the Content Capture Service that a node has been removed from the view structure.
     *
     * <p>Typically called "manually" by views that handle their own virtual view hierarchy, or
     * automatically by the Android System for standard views.
     *
     * @param id id of the node that has been removed.
     */
    public final void notifyViewDisappeared(@NonNull AutofillId id) {
        Preconditions.checkNotNull(id);
        if (!isContentCaptureEnabled()) return;

        internalNotifyViewDisappeared(id);
    }

    abstract void internalNotifyViewDisappeared(@NonNull AutofillId id);

    /**
     * Notifies the Content Capture Service that many nodes has been removed from a virtual view
     * structure.
     *
     * <p>Should only be called by views that handle their own virtual view hierarchy.
     *
     * @param hostId id of the view hosting the virtual hierarchy.
     * @param virtualIds ids of the virtual children.
     *
     * @throws IllegalArgumentException if the {@code hostId} is an autofill id for a virtual view.
     * @throws IllegalArgumentException if {@code virtualIds} is empty
     */
    public final void notifyViewsDisappeared(@NonNull AutofillId hostId,
            @NonNull long[] virtualIds) {
        Preconditions.checkArgument(hostId.isNonVirtual(), "parent cannot be virtual");
        Preconditions.checkArgument(!ArrayUtils.isEmpty(virtualIds), "virtual ids cannot be empty");
        if (!isContentCaptureEnabled()) return;

        // TODO(b/123036895): use a internalNotifyViewsDisappeared that optimizes how the event is
        // parcelized
        for (long id : virtualIds) {
            internalNotifyViewDisappeared(new AutofillId(hostId, id, getIdAsInt()));
        }
    }

    /**
     * Notifies the Intelligence Service that the value of a text node has been changed.
     *
     * @param id of the node.
     * @param text new text.
     */
    public final void notifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text) {
        Preconditions.checkNotNull(id);

        if (!isContentCaptureEnabled()) return;

        internalNotifyViewTextChanged(id, text);
    }

    abstract void internalNotifyViewTextChanged(@NonNull AutofillId id,
            @Nullable CharSequence text);

    /** @hide */
    public abstract void internalNotifyViewTreeEvent(boolean started);

    /**
     * Creates a {@link ViewStructure} for a "standard" view.
     *
     * @hide
     */
    @NonNull
    public final ViewStructure newViewStructure(@NonNull View view) {
        return new ViewNode.ViewStructureImpl(view);
    }

    /**
     * Creates a new {@link AutofillId} for a virtual child, so it can be used to uniquely identify
     * the children in the session.
     *
     * @param parentId id of the virtual view parent (it can be obtained by calling
     * {@link ViewStructure#getAutofillId()} on the parent).
     * @param virtualChildId id of the virtual child, relative to the parent.
     *
     * @return if for the virtual child
     *
     * @throws IllegalArgumentException if the {@code parentId} is a virtual child id.
     */
    public @NonNull AutofillId newAutofillId(@NonNull AutofillId parentId, long virtualChildId) {
        Preconditions.checkNotNull(parentId);
        Preconditions.checkArgument(parentId.isNonVirtual(), "virtual ids cannot have children");
        return new AutofillId(parentId, virtualChildId, getIdAsInt());
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
        return new ViewNode.ViewStructureImpl(parentId, virtualId, getIdAsInt());
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
        return mId;
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
            default:
                return "UNKOWN-" + reason;
        }
    }
}
