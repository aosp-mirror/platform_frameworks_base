/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.timedetector;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.TimestampedValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time suggestion from an identified telephony source. e.g. from NITZ information from a specific
 * radio.
 *
 * <p>{@code slotIndex} identifies the suggestion source. This enables detection logic to identify
 * suggestions from the same source when there are several in use.
 *
 * <p>{@code utcTime}. When not {@code null}, the {@code utcTime.value} is the number of
 * milliseconds elapsed since 1/1/1970 00:00:00 UTC. The {@code utcTime.referenceTimeMillis} is the
 * value of the elapsed realtime clock when the {@code utcTime.value} was established.
 * Note that the elapsed realtime clock is considered accurate but it is volatile, so time
 * suggestions cannot be persisted across device resets. {@code utcTime} can be {@code null} to
 * indicate that the telephony source has entered an "un-opinionated" state and any previous
 * suggestion from the source is being withdrawn.
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists, e.g. what triggered it to be made and what heuristic was used
 * to determine the time or its absence. This information exists only to aid in debugging and
 * therefore is used by {@link #toString()}, but it is not for use in detection logic and is not
 * considered in {@link #hashCode()} or {@link #equals(Object)}.
 *
 * @hide
 */
public final class TelephonyTimeSuggestion implements Parcelable {

    /** @hide */
    public static final @NonNull Parcelable.Creator<TelephonyTimeSuggestion> CREATOR =
            new Parcelable.Creator<TelephonyTimeSuggestion>() {
                public TelephonyTimeSuggestion createFromParcel(Parcel in) {
                    return TelephonyTimeSuggestion.createFromParcel(in);
                }

                public TelephonyTimeSuggestion[] newArray(int size) {
                    return new TelephonyTimeSuggestion[size];
                }
            };

    private final int mSlotIndex;
    @Nullable private final TimestampedValue<Long> mUtcTime;
    @Nullable private ArrayList<String> mDebugInfo;

    private TelephonyTimeSuggestion(Builder builder) {
        mSlotIndex = builder.mSlotIndex;
        mUtcTime = builder.mUtcTime;
        mDebugInfo = builder.mDebugInfo != null ? new ArrayList<>(builder.mDebugInfo) : null;
    }

    private static TelephonyTimeSuggestion createFromParcel(Parcel in) {
        int slotIndex = in.readInt();
        TelephonyTimeSuggestion suggestion = new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUtcTime(in.readParcelable(null /* classLoader */))
                .build();
        @SuppressWarnings("unchecked")
        ArrayList<String> debugInfo = (ArrayList<String>) in.readArrayList(null /* classLoader */);
        if (debugInfo != null) {
            suggestion.addDebugInfo(debugInfo);
        }
        return suggestion;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSlotIndex);
        dest.writeParcelable(mUtcTime, 0);
        dest.writeList(mDebugInfo);
    }

    /**
     * Returns an identifier for the source of this suggestion.
     *
     * <p>See {@link TelephonyTimeSuggestion} for more information about {@code slotIndex}.
     */
    public int getSlotIndex() {
        return mSlotIndex;
    }

    /**
     * Returns the suggested time or {@code null} if there isn't one.
     *
     * <p>See {@link TelephonyTimeSuggestion} for more information about {@code utcTime}.
     */
    @Nullable
    public TimestampedValue<Long> getUtcTime() {
        return mUtcTime;
    }

    /**
     * Returns debug metadata for the suggestion.
     *
     * <p>See {@link TelephonyTimeSuggestion} for more information about {@code debugInfo}.
     */
    @NonNull
    public List<String> getDebugInfo() {
        return mDebugInfo == null
                ? Collections.emptyList() : Collections.unmodifiableList(mDebugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging.
     *
     * <p>See {@link TelephonyTimeSuggestion} for more information about {@code debugInfo}.
     */
    public void addDebugInfo(@NonNull String debugInfo) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>();
        }
        mDebugInfo.add(debugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging.
     *
     * <p>See {@link TelephonyTimeSuggestion} for more information about {@code debugInfo}.
     */
    public void addDebugInfo(@NonNull List<String> debugInfo) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>(debugInfo.size());
        }
        mDebugInfo.addAll(debugInfo);
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TelephonyTimeSuggestion that = (TelephonyTimeSuggestion) o;
        return mSlotIndex == that.mSlotIndex
                && Objects.equals(mUtcTime, that.mUtcTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSlotIndex, mUtcTime);
    }

    @Override
    public String toString() {
        return "TelephonyTimeSuggestion{"
                + "mSlotIndex='" + mSlotIndex + '\''
                + ", mUtcTime=" + mUtcTime
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }

    /**
     * Builds {@link TelephonyTimeSuggestion} instances.
     *
     * @hide
     */
    public static final class Builder {
        private final int mSlotIndex;
        @Nullable private TimestampedValue<Long> mUtcTime;
        @Nullable private List<String> mDebugInfo;

        /**
         * Creates a builder with the specified {@code slotIndex}.
         *
         * <p>See {@link TelephonyTimeSuggestion} for more information about {@code slotIndex}.
         */
        public Builder(int slotIndex) {
            mSlotIndex = slotIndex;
        }

        /**
         * Returns the builder for call chaining.
         *
         * <p>See {@link TelephonyTimeSuggestion} for more information about {@code utcTime}.
         */
        @NonNull
        public Builder setUtcTime(@Nullable TimestampedValue<Long> utcTime) {
            if (utcTime != null) {
                // utcTime can be null, but the value it holds cannot.
                Objects.requireNonNull(utcTime.getValue());
            }

            mUtcTime = utcTime;
            return this;
        }

        /**
         * Returns the builder for call chaining.
         *
         * <p>See {@link TelephonyTimeSuggestion} for more information about {@code debugInfo}.
         */
        @NonNull
        public Builder addDebugInfo(@NonNull String debugInfo) {
            if (mDebugInfo == null) {
                mDebugInfo = new ArrayList<>();
            }
            mDebugInfo.add(debugInfo);
            return this;
        }

        /** Returns the {@link TelephonyTimeSuggestion}. */
        @NonNull
        public TelephonyTimeSuggestion build() {
            return new TelephonyTimeSuggestion(this);
        }
    }
}
