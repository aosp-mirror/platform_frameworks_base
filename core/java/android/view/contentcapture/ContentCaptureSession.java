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

import static android.view.contentcapture.ContentCaptureManager.VERBOSE;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.util.Log;
import android.view.View;
import android.view.ViewStructure;
import android.view.autofill.AutofillId;
import android.view.contentcapture.ViewNode.ViewStructureImpl;

import com.android.internal.util.Preconditions;

import dalvik.system.CloseGuard;

import java.io.PrintWriter;
import java.util.UUID;

/**
 * Session used to notify a system-provided Content Capture service about events associated with
 * views.
 */
public abstract class ContentCaptureSession implements AutoCloseable {

    /**
     * Used on {@link #notifyViewTextChanged(AutofillId, CharSequence, int)} to indicate that the
     *
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

    /** @hide */
    protected final String mTag = getClass().getSimpleName();

    private final CloseGuard mCloseGuard = CloseGuard.get();

    /** @hide */
    @Nullable
    protected final String mId = UUID.randomUUID().toString();

    private int mState = STATE_UNKNOWN;

    // Lazily created on demand.
    private ContentCaptureSessionId mContentCaptureSessionId;

    /**
     * {@link ContentCaptureContext} set by client, or {@code null} when it's the
     * {@link ContentCaptureManager#getMainContentCaptureSession() default session} for the
     * context.
     *
     * @hide
     */
    @Nullable
    // TODO(b/121042846): move to ChildContentCaptureSession.java
    protected final ContentCaptureContext mClientContext;

    /** @hide */
    protected ContentCaptureSession(@Nullable ContentCaptureContext clientContext) {
        mClientContext = clientContext;
        mCloseGuard.open("destroy");
    }

    /**
     * Gets the id used to identify this session.
     */
    public final ContentCaptureSessionId getContentCaptureSessionId() {
        if (mContentCaptureSessionId == null) {
            mContentCaptureSessionId = new ContentCaptureSessionId(mId);
        }
        return mContentCaptureSessionId;
    }

    /**
     * Flushes the buffered events to the service.
     */
    abstract void flush();

    /**
     * Destroys this session, flushing out all pending notifications to the service.
     *
     * <p>Once destroyed, any new notification will be dropped.
     */
    public final void destroy() {
        //TODO(b/111276913): mark it as destroyed so other methods are ignored (and test on CTS)

        if (!isContentCaptureEnabled()) return;

        //TODO(b/111276913): check state (for example, how to handle if it's waiting for remote
        // id) and send it to the cache of batched commands
        if (VERBOSE) {
            Log.v(mTag, "destroy(): state=" + getStateAsString(mState) + ", mId=" + mId);
        }

        flush();

        onDestroy();

        mCloseGuard.close();
    }

    abstract void onDestroy();


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
     * Notifies the Intelligence Service that the value of a text node has been changed.
     *
     * @param id of the node.
     * @param text new text.
     * @param flags either {@code 0} or {@link #FLAG_USER_INPUT} when the value was explicitly
     * changed by the user (for example, through the keyboard).
     */
    public final void notifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text,
            int flags) {
        Preconditions.checkNotNull(id);

        if (!isContentCaptureEnabled()) return;

        internalNotifyViewTextChanged(id, text, flags);
    }

    abstract void internalNotifyViewTextChanged(@NonNull AutofillId id, @Nullable CharSequence text,
            int flags);

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
    public final ViewStructure newVirtualViewStructure(@NonNull AutofillId parentId,
            int virtualId) {
        return new ViewNode.ViewStructureImpl(parentId, virtualId);
    }

    abstract boolean isContentCaptureEnabled();

    abstract void dump(@NonNull String prefix, @NonNull PrintWriter pw);

    @Override
    public String toString() {
        return mId;
    }

    /**
     * @hide
     */
    @NonNull
    protected static String getStateAsString(int state) {
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
