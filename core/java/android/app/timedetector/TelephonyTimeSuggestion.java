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
import android.app.time.UnixEpochTime;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ShellCommand;

import java.io.PrintWriter;
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
 * <p>{@code unixEpochTime}. When not {@code null}, the {@code unixEpochTime.value} is the number of
 * milliseconds elapsed since 1/1/1970 00:00:00 UTC. The {@code unixEpochTime.referenceTimeMillis}
 * is the value of the elapsed realtime clock when the {@code unixEpochTime.value} was established.
 * Note that the elapsed realtime clock is considered accurate but it is volatile, so time
 * suggestions cannot be persisted across device resets. {@code unixEpochTime} can be {@code null}
 * to indicate that the telephony source has entered an "un-opinionated" state and any previous
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
    @Nullable private final UnixEpochTime mUnixEpochTime;
    @Nullable private ArrayList<String> mDebugInfo;

    private TelephonyTimeSuggestion(Builder builder) {
        mSlotIndex = builder.mSlotIndex;
        mUnixEpochTime = builder.mUnixEpochTime;
        mDebugInfo = builder.mDebugInfo != null ? new ArrayList<>(builder.mDebugInfo) : null;
    }

    private static TelephonyTimeSuggestion createFromParcel(Parcel in) {
        int slotIndex = in.readInt();
        UnixEpochTime unixEpochTime =
                in.readParcelable(null /* classLoader */, UnixEpochTime.class);
        TelephonyTimeSuggestion suggestion = new TelephonyTimeSuggestion.Builder(slotIndex)
                .setUnixEpochTime(unixEpochTime)
                .build();
        @SuppressWarnings("unchecked")
        ArrayList<String> debugInfo = in.readArrayList(
                null /* classLoader */, java.lang.String.class);
        if (debugInfo != null) {
            suggestion.addDebugInfo(debugInfo);
        }
        return suggestion;
    }

    /** @hide */
    public static TelephonyTimeSuggestion parseCommandLineArg(@NonNull ShellCommand cmd)
            throws IllegalArgumentException {
        Integer slotIndex = null;
        Long elapsedRealtimeMillis = null;
        Long unixEpochTimeMillis = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--slot_index": {
                    slotIndex = Integer.parseInt(cmd.getNextArgRequired());
                    break;
                }
                case "--reference_time":
                case "--elapsed_realtime": {
                    elapsedRealtimeMillis = Long.parseLong(cmd.getNextArgRequired());
                    break;
                }
                case "--unix_epoch_time": {
                    unixEpochTimeMillis = Long.parseLong(cmd.getNextArgRequired());
                    break;
                }
                default: {
                    throw new IllegalArgumentException("Unknown option: " + opt);
                }
            }
        }

        if (slotIndex == null) {
            throw new IllegalArgumentException("No slotIndex specified.");
        }
        if (elapsedRealtimeMillis == null) {
            throw new IllegalArgumentException("No elapsedRealtimeMillis specified.");
        }
        if (unixEpochTimeMillis == null) {
            throw new IllegalArgumentException("No unixEpochTimeMillis specified.");
        }

        UnixEpochTime timeSignal = new UnixEpochTime(elapsedRealtimeMillis, unixEpochTimeMillis);
        Builder builder = new Builder(slotIndex)
                .setUnixEpochTime(timeSignal)
                .addDebugInfo("Command line injection");
        return builder.build();
    }

    /** @hide */
    public static void printCommandLineOpts(PrintWriter pw) {
        pw.println("Telephony suggestion options:");
        pw.println("  --slot_index <number>");
        pw.println("  --elapsed_realtime <elapsed realtime millis>");
        pw.println("  --unix_epoch_time <Unix epoch time millis>");
        pw.println();
        pw.println("See " + TelephonyTimeSuggestion.class.getName() + " for more information");
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSlotIndex);
        dest.writeParcelable(mUnixEpochTime, 0);
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
     * <p>See {@link TelephonyTimeSuggestion} for more information about {@code unixEpochTime}.
     */
    @Nullable
    public UnixEpochTime getUnixEpochTime() {
        return mUnixEpochTime;
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
                && Objects.equals(mUnixEpochTime, that.mUnixEpochTime);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSlotIndex, mUnixEpochTime);
    }

    @Override
    public String toString() {
        return "TelephonyTimeSuggestion{"
                + "mSlotIndex='" + mSlotIndex + '\''
                + ", mUnixEpochTime=" + mUnixEpochTime
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
        @Nullable private UnixEpochTime mUnixEpochTime;
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
         * <p>See {@link TelephonyTimeSuggestion} for more information about {@code unixEpochTime}.
         */
        @NonNull
        public Builder setUnixEpochTime(@Nullable UnixEpochTime unixEpochTime) {
            mUnixEpochTime = unixEpochTime;
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
