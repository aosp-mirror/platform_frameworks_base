/*
 * Copyright 2019 The Android Open Source Project
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

package android.app.timezonedetector;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.ShellCommand;
import android.text.TextUtils;

import java.io.PrintWriter;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A time zone suggestion from an identified telephony source, e.g. from MCC and NITZ information
 * associated with a specific radio.
 *
 * <p>{@code slotIndex} identifies the suggestion source. This enables detection logic to identify
 * suggestions from the same source when there are several in use.
 *
 * <p>{@code zoneId}. When not {@code null}, {@code zoneId} contains the suggested time zone ID,
 * e.g. "America/Los_Angeles". Suggestion metadata like {@code matchType} and {@code quality} can be
 * used to judge quality / certainty of the suggestion. {@code zoneId} can be {@code null} to
 * indicate that the telephony source has entered an "un-opinionated" state and any previous
 * suggestion from the same source is being withdrawn.
 *
 * <p>{@code matchType} must be set to {@link #MATCH_TYPE_NA} when {@code zoneId} is {@code null},
 * and one of the other {@code MATCH_TYPE_} values when it is not {@code null}.
 *
 * <p>{@code quality} must be set to {@link #QUALITY_NA} when {@code zoneId} is {@code null},
 * and one of the other {@code QUALITY_} values when it is not {@code null}.
 *
 * <p>{@code debugInfo} contains debugging metadata associated with the suggestion. This is used to
 * record why the suggestion exists, e.g. what triggered it to be made and what heuristic was used
 * to determine the time zone or its absence. This information exists only to aid in debugging and
 * therefore is used by {@link #toString()}, but it is not for use in detection logic and is not
 * considered in {@link #hashCode()} or {@link #equals(Object)}.
 *
 * @hide
 */
public final class TelephonyTimeZoneSuggestion implements Parcelable {

    /** @hide */
    @NonNull
    public static final Creator<TelephonyTimeZoneSuggestion> CREATOR =
            new Creator<TelephonyTimeZoneSuggestion>() {
                public TelephonyTimeZoneSuggestion createFromParcel(Parcel in) {
                    return TelephonyTimeZoneSuggestion.createFromParcel(in);
                }

                public TelephonyTimeZoneSuggestion[] newArray(int size) {
                    return new TelephonyTimeZoneSuggestion[size];
                }
            };

    /**
     * Creates an empty time zone suggestion, i.e. one that will cancel previous suggestions with
     * the same {@code slotIndex}.
     */
    @NonNull
    public static TelephonyTimeZoneSuggestion createEmptySuggestion(
            int slotIndex, @NonNull String debugInfo) {
        return new Builder(slotIndex).addDebugInfo(debugInfo).build();
    }

    /** @hide */
    @IntDef({ MATCH_TYPE_NA, MATCH_TYPE_NETWORK_COUNTRY_ONLY, MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET,
            MATCH_TYPE_EMULATOR_ZONE_ID, MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MatchType {}

    /** Used when match type is not applicable. */
    public static final int MATCH_TYPE_NA = 0;

    /**
     * Only the network country is known.
     */
    public static final int MATCH_TYPE_NETWORK_COUNTRY_ONLY = 2;

    /**
     * Both the network county and offset were known.
     */
    public static final int MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET = 3;

    /**
     * The device is running in an emulator and an NITZ signal was simulated containing an
     * Android extension with an explicit Olson ID.
     */
    public static final int MATCH_TYPE_EMULATOR_ZONE_ID = 4;

    /**
     * The phone is most likely running in a test network not associated with a country (this is
     * distinct from the country just not being known yet).
     * Historically, Android has just picked an arbitrary time zone with the correct offset when
     * on a test network.
     */
    public static final int MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY = 5;

    /** @hide */
    @IntDef({ QUALITY_NA, QUALITY_SINGLE_ZONE, QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET,
            QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Quality {}

    /** Used when quality is not applicable. */
    public static final int QUALITY_NA = 0;

    /** There is only one answer */
    public static final int QUALITY_SINGLE_ZONE = 1;

    /**
     * There are multiple answers, but they all shared the same offset / DST state at the time
     * the suggestion was created. i.e. it might be the wrong zone but the user won't notice
     * immediately if it is wrong.
     */
    public static final int QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET = 2;

    /**
     * There are multiple answers with different offsets. The one given is just one possible.
     */
    public static final int QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS = 3;

    private final int mSlotIndex;
    @Nullable private final String mZoneId;
    @MatchType private final int mMatchType;
    @Quality private final int mQuality;
    @Nullable private List<String> mDebugInfo;

    private TelephonyTimeZoneSuggestion(Builder builder) {
        mSlotIndex = builder.mSlotIndex;
        mZoneId = builder.mZoneId;
        mMatchType = builder.mMatchType;
        mQuality = builder.mQuality;
        mDebugInfo = builder.mDebugInfo != null ? new ArrayList<>(builder.mDebugInfo) : null;
    }

    @SuppressWarnings("unchecked")
    private static TelephonyTimeZoneSuggestion createFromParcel(Parcel in) {
        // Use the Builder so we get validation during build().
        int slotIndex = in.readInt();
        TelephonyTimeZoneSuggestion suggestion = new Builder(slotIndex)
                .setZoneId(in.readString())
                .setMatchType(in.readInt())
                .setQuality(in.readInt())
                .build();
        List<String> debugInfo =
                in.readArrayList(TelephonyTimeZoneSuggestion.class.getClassLoader());
        if (debugInfo != null) {
            suggestion.addDebugInfo(debugInfo);
        }
        return suggestion;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mSlotIndex);
        dest.writeString(mZoneId);
        dest.writeInt(mMatchType);
        dest.writeInt(mQuality);
        dest.writeList(mDebugInfo);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /**
     * Returns an identifier for the source of this suggestion.
     *
     * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code slotIndex}.
     */
    public int getSlotIndex() {
        return mSlotIndex;
    }

    /**
     * Returns the suggested time zone Olson ID, e.g. "America/Los_Angeles". {@code null} means that
     * the caller is no longer sure what the current time zone is.
     *
     * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code zoneId}.
     */
    @Nullable
    public String getZoneId() {
        return mZoneId;
    }

    /**
     * Returns information about how the suggestion was determined which could be used to rank
     * suggestions when several are available from different sources.
     *
     * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code matchType}.
     */
    @MatchType
    public int getMatchType() {
        return mMatchType;
    }

    /**
     * Returns information about the likelihood of the suggested zone being correct.
     *
     * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code quality}.
     */
    @Quality
    public int getQuality() {
        return mQuality;
    }

    /**
     * Returns debug metadata for the suggestion.
     *
     * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code debugInfo}.
     */
    @NonNull
    public List<String> getDebugInfo() {
        return mDebugInfo == null
                ? Collections.emptyList() : Collections.unmodifiableList(mDebugInfo);
    }

    /**
     * Associates information with the instance that can be useful for debugging / logging.
     *
     * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code debugInfo}.
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
     * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code debugInfo}.
     */
    public void addDebugInfo(@NonNull List<String> debugInfo) {
        if (mDebugInfo == null) {
            mDebugInfo = new ArrayList<>(debugInfo.size());
        }
        mDebugInfo.addAll(debugInfo);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (o == null || getClass() != o.getClass()) {
            return false;
        }
        TelephonyTimeZoneSuggestion that = (TelephonyTimeZoneSuggestion) o;
        return mSlotIndex == that.mSlotIndex
                && mMatchType == that.mMatchType
                && mQuality == that.mQuality
                && Objects.equals(mZoneId, that.mZoneId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mSlotIndex, mZoneId, mMatchType, mQuality);
    }

    @Override
    public String toString() {
        return "TelephonyTimeZoneSuggestion{"
                + "mSlotIndex=" + mSlotIndex
                + ", mZoneId='" + mZoneId + '\''
                + ", mMatchType=" + mMatchType
                + ", mQuality=" + mQuality
                + ", mDebugInfo=" + mDebugInfo
                + '}';
    }

    /**
     * Builds {@link TelephonyTimeZoneSuggestion} instances.
     *
     * @hide
     */
    public static final class Builder {
        private final int mSlotIndex;
        @Nullable private String mZoneId;
        @MatchType private int mMatchType;
        @Quality private int mQuality;
        @Nullable private List<String> mDebugInfo;

        /**
         * Creates a builder with the specified {@code slotIndex}.
         *
         * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code slotIndex}.
         */
        public Builder(int slotIndex) {
            mSlotIndex = slotIndex;
        }

        /**
         * Returns the builder for call chaining.
         *
         * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code zoneId}.
         */
        @NonNull
        public Builder setZoneId(@Nullable String zoneId) {
            mZoneId = zoneId;
            return this;
        }

        /**
         * Returns the builder for call chaining.
         *
         * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code matchType}.
         */
        @NonNull
        public Builder setMatchType(@MatchType int matchType) {
            mMatchType = matchType;
            return this;
        }

        /**
         * Returns the builder for call chaining.
         *
         * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code quality}.
         */
        @NonNull
        public Builder setQuality(@Quality int quality) {
            mQuality = quality;
            return this;
        }

        /**
         * Returns the builder for call chaining.
         *
         * <p>See {@link TelephonyTimeZoneSuggestion} for more information about {@code debugInfo}.
         */
        @NonNull
        public Builder addDebugInfo(@NonNull String debugInfo) {
            if (mDebugInfo == null) {
                mDebugInfo = new ArrayList<>();
            }
            mDebugInfo.add(debugInfo);
            return this;
        }

        /**
         * Performs basic structural validation of this instance. e.g. Are all the fields populated
         * that must be? Are the enum ints set to valid values?
         */
        void validate() {
            int quality = mQuality;
            int matchType = mMatchType;
            if (mZoneId == null) {
                if (quality != QUALITY_NA || matchType != MATCH_TYPE_NA) {
                    throw new RuntimeException("Invalid quality or match type for null zone ID."
                            + " quality=" + quality + ", matchType=" + matchType);
                }
            } else {
                boolean qualityValid = (quality == QUALITY_SINGLE_ZONE
                        || quality == QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET
                        || quality == QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS);
                boolean matchTypeValid = (matchType == MATCH_TYPE_NETWORK_COUNTRY_ONLY
                        || matchType == MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET
                        || matchType == MATCH_TYPE_EMULATOR_ZONE_ID
                        || matchType == MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY);
                if (!qualityValid || !matchTypeValid) {
                    throw new RuntimeException("Invalid quality or match type with zone ID."
                            + " quality=" + quality + ", matchType=" + matchType);
                }
            }
        }

        /** Returns the {@link TelephonyTimeZoneSuggestion}. */
        @NonNull
        public TelephonyTimeZoneSuggestion build() {
            validate();
            return new TelephonyTimeZoneSuggestion(this);
        }
    }

    /** @hide */
    public static TelephonyTimeZoneSuggestion parseCommandLineArg(@NonNull ShellCommand cmd)
            throws IllegalArgumentException {
        Integer slotIndex = null;
        String zoneId = null;
        Integer quality = null;
        Integer matchType = null;
        String opt;
        while ((opt = cmd.getNextArg()) != null) {
            switch (opt) {
                case "--slot_index": {
                    slotIndex = Integer.parseInt(cmd.getNextArgRequired());
                    break;
                }
                case "--zone_id": {
                    zoneId = cmd.getNextArgRequired();
                    break;
                }
                case "--quality": {
                    quality = parseQualityCommandLineArg(cmd.getNextArgRequired());
                    break;
                }
                case "--match_type": {
                    matchType = parseMatchTypeCommandLineArg(cmd.getNextArgRequired());
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

        Builder builder = new Builder(slotIndex);
        if (!(TextUtils.isEmpty(zoneId) || "_".equals(zoneId))) {
            builder.setZoneId(zoneId);
        }
        if (quality != null) {
            builder.setQuality(quality);
        }
        if (matchType != null) {
            builder.setMatchType(matchType);
        }
        builder.addDebugInfo("Command line injection");
        return builder.build();
    }

    private static int parseQualityCommandLineArg(@NonNull String arg) {
        switch (arg) {
            case "single":
                return QUALITY_SINGLE_ZONE;
            case "multiple_same":
                return QUALITY_MULTIPLE_ZONES_WITH_SAME_OFFSET;
            case "multiple_different":
                return QUALITY_MULTIPLE_ZONES_WITH_DIFFERENT_OFFSETS;
            default:
                throw new IllegalArgumentException("Unrecognized quality: " + arg);
        }
    }

    private static int parseMatchTypeCommandLineArg(@NonNull String arg) {
        switch (arg) {
            case "emulator":
                return MATCH_TYPE_EMULATOR_ZONE_ID;
            case "country_with_offset":
                return MATCH_TYPE_NETWORK_COUNTRY_AND_OFFSET;
            case "country":
                return MATCH_TYPE_NETWORK_COUNTRY_ONLY;
            case "test_network":
                return MATCH_TYPE_TEST_NETWORK_OFFSET_ONLY;
            default:
                throw new IllegalArgumentException("Unrecognized match_type: " + arg);
        }
    }

    /** @hide */
    public static void printCommandLineOpts(@NonNull PrintWriter pw) {
        pw.println("Telephony suggestion options:");
        pw.println("  --slot_index <number>");
        pw.println("  To withdraw a previous suggestion:");
        pw.println("    [--zone_id \"_\"]");
        pw.println("  To make a new suggestion:");
        pw.println("    --zone_id <Olson ID>");
        pw.println("    --quality <single|multiple_same|multiple_different>");
        pw.println("    --match_type <emulator|country_with_offset|country|test_network>");
        pw.println();
        pw.println("See " + TelephonyTimeZoneSuggestion.class.getName() + " for more information");
    }
}
