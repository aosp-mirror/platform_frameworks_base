/*
 * Copyright (C) 2022 The Android Open Source Project
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

package android.app.ambientcontext;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.app.PendingIntent;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.util.ArrayList;
import java.util.List;

/**
 * Represents a response from the {@code AmbientContextEvent} service.
 *
 * @hide
 */
@SystemApi
public final class AmbientContextEventResponse implements Parcelable {
    /**
     * An unknown status.
     */
    public static final int STATUS_UNKNOWN = 0;
    /**
     * The value of the status code that indicates success.
     */
    public static final int STATUS_SUCCESS = 1;
    /**
     * The value of the status code that indicates one or more of the
     * requested events are not supported.
     */
    public static final int STATUS_NOT_SUPPORTED = 2;
    /**
     * The value of the status code that indicates service not available.
     */
    public static final int STATUS_SERVICE_UNAVAILABLE = 3;
    /**
     * The value of the status code that microphone is disabled.
     */
    public static final int STATUS_MICROPHONE_DISABLED = 4;
    /**
     * The value of the status code that the app is not granted access.
     */
    public static final int STATUS_ACCESS_DENIED = 5;

    /** @hide */
    @IntDef(prefix = { "STATUS_" }, value = {
            STATUS_UNKNOWN,
            STATUS_SUCCESS,
            STATUS_NOT_SUPPORTED,
            STATUS_SERVICE_UNAVAILABLE,
            STATUS_MICROPHONE_DISABLED,
            STATUS_ACCESS_DENIED
    }) public @interface StatusCode {}

    @StatusCode private final int mStatusCode;
    @NonNull private final List<AmbientContextEvent> mEvents;
    @NonNull private final String mPackageName;
    @Nullable private final PendingIntent mActionPendingIntent;

    /** @hide */
    public static String statusToString(@StatusCode int value) {
        switch (value) {
            case STATUS_UNKNOWN:
                return "STATUS_UNKNOWN";
            case STATUS_SUCCESS:
                return "STATUS_SUCCESS";
            case STATUS_NOT_SUPPORTED:
                return "STATUS_NOT_SUPPORTED";
            case STATUS_SERVICE_UNAVAILABLE:
                return "STATUS_SERVICE_UNAVAILABLE";
            case STATUS_MICROPHONE_DISABLED:
                return "STATUS_MICROPHONE_DISABLED";
            case STATUS_ACCESS_DENIED:
                return "STATUS_ACCESS_DENIED";
            default: return Integer.toHexString(value);
        }
    }

    AmbientContextEventResponse(
            @StatusCode int statusCode,
            @NonNull List<AmbientContextEvent> events,
            @NonNull String packageName,
            @Nullable PendingIntent actionPendingIntent) {
        this.mStatusCode = statusCode;
        AnnotationValidations.validate(StatusCode.class, null, mStatusCode);
        this.mEvents = events;
        AnnotationValidations.validate(NonNull.class, null, mEvents);
        this.mPackageName = packageName;
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
        this.mActionPendingIntent = actionPendingIntent;
    }

    /**
     * The status of the response.
     */
    public @StatusCode int getStatusCode() {
        return mStatusCode;
    }

    /**
     * The detected event.
     */
    public @NonNull List<AmbientContextEvent> getEvents() {
        return mEvents;
    }

    /**
     * The package to deliver the response to.
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    /**
     * A {@link PendingIntent} that the client should call to allow further actions by user.
     * For example, with {@link STATUS_ACCESS_DENIED}, the PendingIntent can redirect users to the
     * grant access activity.
     */
    public @Nullable PendingIntent getActionPendingIntent() {
        return mActionPendingIntent;
    }

    @Override
    public String toString() {
        return "AmbientContextEventResponse { " + "statusCode = " + mStatusCode + ", "
                + "events = " + mEvents + ", " + "packageName = " + mPackageName + ", "
                + "callbackPendingIntent = " + mActionPendingIntent + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        byte flg = 0;
        if (mActionPendingIntent != null) flg |= 0x8;
        dest.writeByte(flg);
        dest.writeInt(mStatusCode);
        dest.writeParcelableList(mEvents, flags);
        dest.writeString(mPackageName);
        if (mActionPendingIntent != null) dest.writeTypedObject(mActionPendingIntent, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    AmbientContextEventResponse(@NonNull android.os.Parcel in) {
        byte flg = in.readByte();
        int statusCode = in.readInt();
        List<AmbientContextEvent> events = new ArrayList<>();
        in.readParcelableList(events, AmbientContextEvent.class.getClassLoader(),
                AmbientContextEvent.class);
        String packageName = in.readString();
        PendingIntent callbackPendingIntent = (flg & 0x8) == 0 ? null
                : (PendingIntent) in.readTypedObject(PendingIntent.CREATOR);

        this.mStatusCode = statusCode;
        AnnotationValidations.validate(
                StatusCode.class, null, mStatusCode);
        this.mEvents = events;
        AnnotationValidations.validate(
                NonNull.class, null, mEvents);
        this.mPackageName = packageName;
        AnnotationValidations.validate(
                NonNull.class, null, mPackageName);
        this.mActionPendingIntent = callbackPendingIntent;
    }

    public static final @NonNull Parcelable.Creator<AmbientContextEventResponse> CREATOR =
            new Parcelable.Creator<AmbientContextEventResponse>() {
        @Override
        public AmbientContextEventResponse[] newArray(int size) {
            return new AmbientContextEventResponse[size];
        }

        @Override
        public AmbientContextEventResponse createFromParcel(@NonNull android.os.Parcel in) {
            return new AmbientContextEventResponse(in);
        }
    };

    /**
     * A builder for {@link AmbientContextEventResponse}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {
        private @StatusCode int mStatusCode;
        private @NonNull List<AmbientContextEvent> mEvents;
        private @NonNull String mPackageName;
        private @Nullable PendingIntent mCallbackPendingIntent;
        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * The status of the response.
         */
        public @NonNull Builder setStatusCode(@StatusCode int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mStatusCode = value;
            return this;
        }

        /**
         * Adds an event to the builder.
         */
        public @NonNull Builder addEvent(@NonNull AmbientContextEvent value) {
            checkNotUsed();
            if (mEvents == null) {
                mBuilderFieldsSet |= 0x2;
                mEvents = new ArrayList<>();
            }
            mEvents.add(value);
            return this;
        }

        /**
         * The package to deliver the response to.
         */
        public @NonNull Builder setPackageName(@NonNull String value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mPackageName = value;
            return this;
        }

        /**
         * A {@link PendingIntent} that the client should call to allow further actions by user.
         * For example, with {@link STATUS_ACCESS_DENIED}, the PendingIntent can redirect users to
         * the grant access activity.
         */
        public @NonNull Builder setActionPendingIntent(@NonNull PendingIntent value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mCallbackPendingIntent = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull AmbientContextEventResponse build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mStatusCode = STATUS_UNKNOWN;
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                mEvents = new ArrayList<>();
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mPackageName = "";
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mCallbackPendingIntent = null;
            }
            AmbientContextEventResponse o = new AmbientContextEventResponse(
                    mStatusCode,
                    mEvents,
                    mPackageName,
                    mCallbackPendingIntent);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x10) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
