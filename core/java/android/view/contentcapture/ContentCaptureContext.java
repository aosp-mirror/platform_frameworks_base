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

import static android.view.contentcapture.ContentCaptureManager.NO_SESSION_ID;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.LocusId;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.Display;
import android.view.View;

import com.android.internal.util.Preconditions;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
/**
 * Context associated with a {@link ContentCaptureSession} - see {@link ContentCaptureManager} for
 * more info.
 */
public final class ContentCaptureContext implements Parcelable {

    /*
     * IMPLEMENTATION NOTICE:
     *
     * This object contains both the info that's explicitly added by apps (hence it's public), but
     * it also contains info injected by the server (and are accessible through @SystemApi methods).
     */

    /**
     * Flag used to indicate that the app explicitly disabled content capture for the activity
     * (using {@link ContentCaptureManager#setContentCaptureEnabled(boolean)}),
     * in which case the service will just receive activity-level events.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int FLAG_DISABLED_BY_APP = 0x1;

    /**
     * Flag used to indicate that the activity's window is tagged with
     * {@link android.view.Display#FLAG_SECURE}, in which case the service will just receive
     * activity-level events.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int FLAG_DISABLED_BY_FLAG_SECURE = 0x2;

    /**
     * Flag used when the event is sent because the Android System reconnected to the service (for
     * example, after its process died).
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public static final int FLAG_RECONNECTED = 0x4;

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_DISABLED_BY_APP,
            FLAG_DISABLED_BY_FLAG_SECURE,
            FLAG_RECONNECTED
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface ContextCreationFlags{}

    /**
     * Flag indicating if this object has the app-provided context (which is set on
     * {@link ContentCaptureSession#createContentCaptureSession(ContentCaptureContext)}).
     */
    private final boolean mHasClientContext;

    // Fields below are set by app on Builder
    private final @Nullable Bundle mExtras;
    private final @Nullable LocusId mId;

    // Fields below are set by server when the session starts
    private final @Nullable ComponentName mComponentName;
    private final int mTaskId;
    private final int mFlags;
    private final int mDisplayId;

    // Fields below are set by the service upon "delivery" and are not marshalled in the parcel
    private int mParentSessionId = NO_SESSION_ID;

    /** @hide */
    public ContentCaptureContext(@Nullable ContentCaptureContext clientContext,
            @NonNull ComponentName componentName, int taskId, int displayId, int flags) {
        if (clientContext != null) {
            mHasClientContext = true;
            mExtras = clientContext.mExtras;
            mId = clientContext.mId;
        } else {
            mHasClientContext = false;
            mExtras = null;
            mId = null;
        }
        mComponentName = Preconditions.checkNotNull(componentName);
        mTaskId = taskId;
        mDisplayId = displayId;
        mFlags = flags;
    }

    private ContentCaptureContext(@NonNull Builder builder) {
        mHasClientContext = true;
        mExtras = builder.mExtras;
        mId = builder.mId;

        mComponentName  = null;
        mTaskId = mFlags = 0;
        mDisplayId = Display.INVALID_DISPLAY;
    }

    /** @hide */
    public ContentCaptureContext(@Nullable ContentCaptureContext original, int extraFlags) {
        mHasClientContext = original.mHasClientContext;
        mExtras = original.mExtras;
        mId = original.mId;
        mComponentName = original.mComponentName;
        mTaskId = original.mTaskId;
        mFlags = original.mFlags | extraFlags;
        mDisplayId = original.mDisplayId;
    }

    /**
     * Gets the (optional) extras set by the app (through {@link Builder#setExtras(Bundle)}).
     *
     * <p>It can be used to provide vendor-specific data that can be modified and examined.
     */
    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Gets the context id.
     */
    @Nullable
    public LocusId getLocusId() {
        return mId;
    }

    /**
     * Gets the id of the {@link TaskInfo task} associated with this context.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public int getTaskId() {
        return mTaskId;
    }

    /**
     * Gets the activity associated with this context, or {@code null} when it is a child session.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public @Nullable ComponentName getActivityComponent() {
        return mComponentName;
    }

    /**
     * Gets the id of the session that originated this session (through
     * {@link ContentCaptureSession#createContentCaptureSession(ContentCaptureContext)}),
     * or {@code null} if this is the main session associated with the Activity's {@link Context}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public @Nullable ContentCaptureSessionId getParentSessionId() {
        return mParentSessionId == NO_SESSION_ID ? null
                : new ContentCaptureSessionId(mParentSessionId);
    }

    /** @hide */
    public void setParentSessionId(int parentSessionId) {
        mParentSessionId = parentSessionId;
    }

    /**
     * Gets the ID of the display associated with this context, as defined by
     * {G android.hardware.display.DisplayManager#getDisplay(int) DisplayManager.getDisplay()}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public int getDisplayId() {
        return mDisplayId;
    }

    /**
     * Gets the flags associated with this context.
     *
     * @return any combination of {@link #FLAG_DISABLED_BY_FLAG_SECURE},
     * {@link #FLAG_DISABLED_BY_APP} and {@link #FLAG_RECONNECTED}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public @ContextCreationFlags int getFlags() {
        return mFlags;
    }

    /**
     * Helper that creates a {@link ContentCaptureContext} associated with the given {@code id}.
     */
    @NonNull
    public static ContentCaptureContext forLocusId(@NonNull String id) {
        return new Builder(new LocusId(id)).build();
    }

    /**
     * Builder for {@link ContentCaptureContext} objects.
     */
    public static final class Builder {
        private Bundle mExtras;
        private final LocusId mId;
        private boolean mDestroyed;

        /**
         * Creates a new builder.
         *
         * <p>The context must have an id, which is usually one of the following:
         *
         * <ul>
         *   <li>A URL representing a web page (or {@code IFRAME}) that's being rendered by the
         *   activity (See {@link View#setContentCaptureSession(ContentCaptureSession)} for an
         *   example).
         *   <li>A unique identifier of the application state (for example, a conversation between
         *   2 users in a chat app).
         * </ul>
         *
         * <p>See {@link ContentCaptureManager} for more info about the content capture context.
         *
         * @param id id associated with this context.
         */
        public Builder(@NonNull LocusId id) {
            mId = Preconditions.checkNotNull(id);
        }

        /**
         * Sets extra options associated with this context.
         *
         * <p>It can be used to provide vendor-specific data that can be modified and examined.
         *
         * @param extras extra options.
         * @return this builder.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         */
        @NonNull
        public Builder setExtras(@NonNull Bundle extras) {
            mExtras = Preconditions.checkNotNull(extras);
            throwIfDestroyed();
            return this;
        }

        /**
         * Builds the {@link ContentCaptureContext}.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         *
         * @return the built {@code ContentCaptureContext}
         */
        @NonNull
        public ContentCaptureContext build() {
            throwIfDestroyed();
            mDestroyed = true;
            return new ContentCaptureContext(this);
        }

        private void throwIfDestroyed() {
            Preconditions.checkState(!mDestroyed, "Already called #build()");
        }
    }

    /**
     * @hide
     */
    // TODO(b/111276913): dump to proto as well
    public void dump(PrintWriter pw) {
        if (mComponentName != null) {
            pw.print("activity="); pw.print(mComponentName.flattenToShortString());
        }
        if (mId != null) {
            pw.print(", id="); mId.dump(pw);
        }
        pw.print(", taskId="); pw.print(mTaskId);
        pw.print(", displayId="); pw.print(mDisplayId);
        if (mParentSessionId != NO_SESSION_ID) {
            pw.print(", parentId="); pw.print(mParentSessionId);
        }
        if (mFlags > 0) {
            pw.print(", flags="); pw.print(mFlags);
        }
        if (mExtras != null) {
            // NOTE: cannot dump because it could contain PII
            pw.print(", hasExtras");
        }
    }

    private boolean fromServer() {
        return mComponentName != null;
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("Context[");

        if (fromServer()) {
            builder.append("act=").append(ComponentName.flattenToShortString(mComponentName))
                .append(", taskId=").append(mTaskId)
                .append(", displayId=").append(mDisplayId)
                .append(", flags=").append(mFlags);
        } else {
            builder.append("id=").append(mId);
            if (mExtras != null) {
                // NOTE: cannot print because it could contain PII
                builder.append(", hasExtras");
            }
        }
        if (mParentSessionId != NO_SESSION_ID) {
            builder.append(", parentId=").append(mParentSessionId);
        }
        return builder.append(']').toString();
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int flags) {
        parcel.writeInt(mHasClientContext ? 1 : 0);
        if (mHasClientContext) {
            parcel.writeParcelable(mId, flags);
            parcel.writeBundle(mExtras);
        }
        parcel.writeParcelable(mComponentName, flags);
        if (fromServer()) {
            parcel.writeInt(mTaskId);
            parcel.writeInt(mDisplayId);
            parcel.writeInt(mFlags);
        }
    }

    public static final @android.annotation.NonNull Parcelable.Creator<ContentCaptureContext> CREATOR =
            new Parcelable.Creator<ContentCaptureContext>() {

        @Override
        @NonNull
        public ContentCaptureContext createFromParcel(Parcel parcel) {
            final boolean hasClientContext = parcel.readInt() == 1;

            final ContentCaptureContext clientContext;
            if (hasClientContext) {
                // Must reconstruct the client context using the Builder API
                final LocusId id = parcel.readParcelable(null);
                final Bundle extras = parcel.readBundle();
                final Builder builder = new Builder(id);
                if (extras != null) builder.setExtras(extras);
                clientContext = new ContentCaptureContext(builder);
            } else {
                clientContext = null;
            }
            final ComponentName componentName = parcel.readParcelable(null);
            if (componentName == null) {
                // Client-state only
                return clientContext;
            } else {
                final int taskId = parcel.readInt();
                final int displayId = parcel.readInt();
                final int flags = parcel.readInt();
                return new ContentCaptureContext(clientContext, componentName, taskId, displayId,
                        flags);
            }
        }

        @Override
        @NonNull
        public ContentCaptureContext[] newArray(int size) {
            return new ContentCaptureContext[size];
        }
    };
}
