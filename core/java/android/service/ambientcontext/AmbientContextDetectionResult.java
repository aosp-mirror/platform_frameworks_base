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

package android.service.ambientcontext;

import android.annotation.NonNull;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.app.ambientcontext.AmbientContextEvent;
import android.os.Parcelable;

import com.android.internal.util.AnnotationValidations;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Represents a {@code AmbientContextEvent} detection result reported by the detection service.
 *
 * @hide
 */
@SystemApi
public final class AmbientContextDetectionResult implements Parcelable {

    /**
     * The bundle key for this class of object, used in {@code RemoteCallback#sendResult}.
     *
     * @hide
     */
    public static final String RESULT_RESPONSE_BUNDLE_KEY =
            "android.app.ambientcontext.AmbientContextDetectionResultBundleKey";
    @NonNull private final List<AmbientContextEvent> mEvents;
    @NonNull private final String mPackageName;

    AmbientContextDetectionResult(
            @NonNull List<AmbientContextEvent> events,
            @NonNull String packageName) {
        this.mEvents = events;
        AnnotationValidations.validate(NonNull.class, null, mEvents);
        this.mPackageName = packageName;
        AnnotationValidations.validate(NonNull.class, null, mPackageName);
    }

    /**
     * A list of detected event.
     */
    @SuppressLint("ConcreteCollection")
    public @NonNull List<AmbientContextEvent> getEvents() {
        return mEvents;
    }

    /**
     * The package to deliver the response to.
     */
    public @NonNull String getPackageName() {
        return mPackageName;
    }

    @Override
    public String toString() {
        return "AmbientContextEventResponse { "
                + "events = " + mEvents + ", " + "packageName = " + mPackageName + " }";
    }

    @Override
    public void writeToParcel(@NonNull android.os.Parcel dest, int flags) {
        byte flg = 0;
        dest.writeByte(flg);
        dest.writeParcelableList(mEvents, flags);
        dest.writeString(mPackageName);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    AmbientContextDetectionResult(@NonNull android.os.Parcel in) {
        byte flg = in.readByte();
        ArrayList<AmbientContextEvent> events = new ArrayList<>();
        in.readParcelableList(events, AmbientContextEvent.class.getClassLoader(),
                AmbientContextEvent.class);
        String packageName = in.readString();

        this.mEvents = events;
        AnnotationValidations.validate(
                NonNull.class, null, mEvents);
        this.mPackageName = packageName;
        AnnotationValidations.validate(
                NonNull.class, null, mPackageName);
    }

    public static final @NonNull Creator<AmbientContextDetectionResult> CREATOR =
            new Creator<AmbientContextDetectionResult>() {
        @Override
        public AmbientContextDetectionResult[] newArray(int size) {
            return new AmbientContextDetectionResult[size];
        }

        @Override
        public AmbientContextDetectionResult createFromParcel(@NonNull android.os.Parcel in) {
            return new AmbientContextDetectionResult(in);
        }
    };

    /**
     * A builder for {@link AmbientContextDetectionResult}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {
        private @NonNull ArrayList<AmbientContextEvent> mEvents;
        private @NonNull String mPackageName;
        private long mBuilderFieldsSet = 0L;

        public Builder(@NonNull String packageName) {
            Objects.requireNonNull(packageName);
            mPackageName = packageName;
        }

        /**
         * Adds an event to the builder.
         */
        public @NonNull Builder addEvent(@NonNull AmbientContextEvent value) {
            checkNotUsed();
            if (mEvents == null) {
                mBuilderFieldsSet |= 0x1;
                mEvents = new ArrayList<>();
            }
            mEvents.add(value);
            return this;
        }

        /**
         * Adds a list of events to the builder.
         */
        public @NonNull Builder addEvents(@NonNull List<AmbientContextEvent> values) {
            checkNotUsed();
            if (mEvents == null) {
                mBuilderFieldsSet |= 0x1;
                mEvents = new ArrayList<>();
            }
            mEvents.addAll(values);
            return this;
        }

        /**
         * Clears all events from the builder.
         */
        public @NonNull Builder clearEvents() {
            checkNotUsed();
            if (mEvents != null) {
                mEvents.clear();
            }
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull AmbientContextDetectionResult build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mEvents = new ArrayList<>();
            }
            AmbientContextDetectionResult o = new AmbientContextDetectionResult(
                    mEvents,
                    mPackageName);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x2) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
