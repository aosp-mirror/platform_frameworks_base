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

import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;
import android.util.ArraySet;

import com.android.internal.util.AnnotationValidations;
import com.android.internal.util.Preconditions;

import java.util.HashSet;
import java.util.Set;

/**
 * Represents the request for ambient event detection.
 *
 * @hide
 */
@SystemApi
public final class AmbientContextEventRequest implements Parcelable {
    @NonNull private final Set<Integer> mEventTypes;
    @NonNull private final PersistableBundle mOptions;

    private AmbientContextEventRequest(
            @NonNull Set<Integer> eventTypes,
            @NonNull PersistableBundle options) {
        this.mEventTypes = eventTypes;
        AnnotationValidations.validate(NonNull.class, null, mEventTypes);
        Preconditions.checkArgument(!eventTypes.isEmpty(), "eventTypes cannot be empty");
        for (int eventType : eventTypes) {
            AnnotationValidations.validate(AmbientContextEvent.EventCode.class, null, eventType);
        }
        this.mOptions = options;
        AnnotationValidations.validate(NonNull.class, null, mOptions);
    }

    /**
     * The event types to detect.
     */
    public @NonNull Set<Integer> getEventTypes() {
        return mEventTypes;
    }

    /**
     * Optional detection options.
     */
    public @NonNull PersistableBundle getOptions() {
        return mOptions;
    }

    @Override
    public String toString() {
        return "AmbientContextEventRequest { " + "eventTypes = " + mEventTypes + ", "
                + "options = " + mOptions + " }";
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeArraySet(new ArraySet<>(mEventTypes));
        dest.writeTypedObject(mOptions, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    private AmbientContextEventRequest(@NonNull Parcel in) {
        Set<Integer> eventTypes = (Set<Integer>) in.readArraySet(Integer.class.getClassLoader());
        PersistableBundle options = (PersistableBundle) in.readTypedObject(
                PersistableBundle.CREATOR);

        this.mEventTypes = eventTypes;
        AnnotationValidations.validate(
                NonNull.class, null, mEventTypes);
        Preconditions.checkArgument(!eventTypes.isEmpty(), "eventTypes cannot be empty");
        for (int eventType : eventTypes) {
            AnnotationValidations.validate(AmbientContextEvent.EventCode.class, null, eventType);
        }
        this.mOptions = options;
        AnnotationValidations.validate(
                NonNull.class, null, mOptions);
    }

    public static final @NonNull Parcelable.Creator<AmbientContextEventRequest> CREATOR =
            new Parcelable.Creator<AmbientContextEventRequest>() {
        @Override
        public AmbientContextEventRequest[] newArray(int size) {
            return new AmbientContextEventRequest[size];
        }

        @Override
        public AmbientContextEventRequest createFromParcel(@NonNull Parcel in) {
            return new AmbientContextEventRequest(in);
        }
    };

    /**
     * A builder for {@link AmbientContextEventRequest}
     */
    @SuppressWarnings("WeakerAccess")
    public static final class Builder {
        private @NonNull Set<Integer> mEventTypes;
        private @NonNull PersistableBundle mOptions;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * Add an event type to detect.
         */
        public @NonNull Builder addEventType(@AmbientContextEvent.EventCode int value) {
            checkNotUsed();
            if (mEventTypes == null) {
                mBuilderFieldsSet |= 0x1;
                mEventTypes = new HashSet<>();
            }
            mEventTypes.add(value);
            return this;
        }

        /**
         * Optional detection options.
         */
        public @NonNull Builder setOptions(@NonNull PersistableBundle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mOptions = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull AmbientContextEventRequest build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mEventTypes = new HashSet<>();
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                mOptions = new PersistableBundle();
            }
            AmbientContextEventRequest o = new AmbientContextEventRequest(
                    mEventTypes,
                    mOptions);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x4) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }
}
