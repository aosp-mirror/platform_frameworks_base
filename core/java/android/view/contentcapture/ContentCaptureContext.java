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

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.app.TaskInfo;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
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
 * Context associated with a {@link ContentCaptureSession}.
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
     * (using
     * {@link android.view.contentcapture.ContentCaptureManager#setContentCaptureEnabled(boolean)}),
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

    /** @hide */
    @IntDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_DISABLED_BY_APP,
            FLAG_DISABLED_BY_FLAG_SECURE
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
    private final @Nullable Uri mUri;
    private final @Nullable String mAction;

    // Fields below are set by server when the session starts
    private final @Nullable ComponentName mComponentName;
    private final int mTaskId;
    private final int mFlags;
    private final int mDisplayId;

    // Fields below are set by the service upon "delivery" and are not marshalled in the parcel
    private @Nullable String mParentSessionId;

    /** @hide */
    public ContentCaptureContext(@Nullable ContentCaptureContext clientContext,
            @NonNull ComponentName componentName, int taskId, int displayId, int flags) {
        if (clientContext != null) {
            mHasClientContext = true;
            mExtras = clientContext.mExtras;
            mUri = clientContext.mUri;
            mAction = clientContext.mAction;
        } else {
            mHasClientContext = false;
            mExtras = null;
            mUri = null;
            mAction = null;
        }
        mComponentName = Preconditions.checkNotNull(componentName);
        mTaskId = taskId;
        mDisplayId = displayId;
        mFlags = flags;
    }

    private ContentCaptureContext(@NonNull Builder builder) {
        mHasClientContext = true;
        mExtras = builder.mExtras;
        mUri = builder.mUri;
        mAction = builder.mAction;

        mComponentName  = null;
        mTaskId = mFlags = 0;
        mDisplayId = Display.INVALID_DISPLAY;
    }

    /**
     * Gets the (optional) extras set by the app (through {@link Builder#setExtras(Bundle)}).
     *
     * <p>It can be used to provide vendor-specific data that can be modified and examined.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @Nullable
    public Bundle getExtras() {
        return mExtras;
    }

    /**
     * Gets the (optional) URI set by the app (through {@link Builder#setUri(Uri)}).
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @Nullable
    public Uri getUri() {
        return mUri;
    }

    /**
     * Gets the (optional) action set by the app (through {@link Builder#setAction(String)}).
     *
     * @hide
     */
    @SystemApi
    @TestApi
    @Nullable
    public String getAction() {
        return mAction;
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
        return mParentSessionId == null ?  null : new ContentCaptureSessionId(mParentSessionId);
    }

    /** @hide */
    public void setParentSessionId(@NonNull String parentSessionId) {
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
     * @return any combination of {@link #FLAG_DISABLED_BY_FLAG_SECURE} and
     * {@link #FLAG_DISABLED_BY_APP}.
     *
     * @hide
     */
    @SystemApi
    @TestApi
    public @ContextCreationFlags int getFlags() {
        return mFlags;
    }

    /**
     * Builder for {@link ContentCaptureContext} objects.
     */
    public static final class Builder {
        private Bundle mExtras;
        private Uri mUri;
        private boolean mDestroyed;
        private String mAction;

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
         * Sets the {@link Uri} associated with this context.
         *
         * <p>See {@link View#setContentCaptureSession(ContentCaptureSession)} for an example.
         *
         * @param uri URI associated with this context.
         * @return this builder.
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         */
        @NonNull
        public Builder setUri(@NonNull Uri uri) {
            mUri = Preconditions.checkNotNull(uri);
            throwIfDestroyed();
            return this;
        }

        /**
         * Sets an {@link Intent#getAction() intent action} associated with this context.
         *
         * @param action intent action
         *
         * @return this builder
         *
         * @throws IllegalStateException if {@link #build()} was already called.
         */
        @NonNull
        public Builder setAction(@NonNull String action) {
            mAction = Preconditions.checkNotNull(action);
            throwIfDestroyed();
            return this;
        }

        /**
         * Builds the {@link ContentCaptureContext}.
         *
         * @throws IllegalStateException if {@link #build()} was already called or no call to either
         * {@link #setExtras(Bundle)}, {@link #setAction(String)}, or {@link #setUri(Uri)} was made.
         *
         * @return the built {@code ContentCaptureContext}
         */
        public ContentCaptureContext build() {
            throwIfDestroyed();
            Preconditions.checkState(mExtras != null || mUri != null || mAction != null,
                    "Must call setUri() or setExtras() or setUri() before calling build()");
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
        pw.print("comp="); pw.print(ComponentName.flattenToShortString(mComponentName));
        pw.print(", taskId="); pw.print(mTaskId);
        pw.print(", displayId="); pw.print(mDisplayId);
        if (mParentSessionId != null) {
            pw.print(", parentId="); pw.print(mParentSessionId);
        }
        if (mFlags > 0) {
            pw.print(", flags="); pw.print(mFlags);
        }
        if (mExtras != null) {
            // NOTE: cannot dump because it could contain PII
            pw.print(", hasExtras");
        }
        if (mUri != null) {
            // NOTE: cannot dump because it could contain PII
            pw.print(", hasUri");
        }
        if (mAction != null) {
            // NOTE: cannot dump because it could contain PII
            pw.print(", hasAction");
        }
    }

    @Override
    public String toString() {
        final StringBuilder builder = new StringBuilder("Context[act=")
                .append(ComponentName.flattenToShortString(mComponentName))
                .append(", taskId=").append(mTaskId)
                .append(", displayId=").append(mDisplayId)
                .append(", flags=").append(mFlags);
        if (mParentSessionId != null) {
            builder.append(", parentId=").append(mParentSessionId);
        }
        if (mExtras != null) {
            // NOTE: cannot print because it could contain PII
            builder.append(", hasExtras");
        }
        if (mUri != null) {
            // NOTE: cannot print because it could contain PII
            builder.append(", hasUri");
        }
        if (mAction != null) {
            // NOTE: cannot print because it could contain PII
            builder.append(", hasAction");
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
            parcel.writeParcelable(mUri, flags);
            parcel.writeString(mAction);
            parcel.writeBundle(mExtras);
        }
        parcel.writeParcelable(mComponentName, flags);
        if (mComponentName != null) {
            parcel.writeInt(mTaskId);
            parcel.writeInt(mDisplayId);
            parcel.writeInt(mFlags);
        }
    }

    public static final Parcelable.Creator<ContentCaptureContext> CREATOR =
            new Parcelable.Creator<ContentCaptureContext>() {

        @Override
        public ContentCaptureContext createFromParcel(Parcel parcel) {
            final boolean hasClientContext = parcel.readInt() == 1;

            final ContentCaptureContext clientContext;
            if (hasClientContext) {
                // Must reconstruct the client context using the Builder API
                final Builder builder = new Builder();
                final Uri uri = parcel.readParcelable(null);
                final String action = parcel.readString();
                final Bundle extras = parcel.readBundle();
                if (uri != null) builder.setUri(uri);
                if (action != null) builder.setAction(action);
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
        public ContentCaptureContext[] newArray(int size) {
            return new ContentCaptureContext[size];
        }
    };
}
