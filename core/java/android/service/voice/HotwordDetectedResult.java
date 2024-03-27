/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.service.voice;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.annotation.SystemApi;
import android.content.res.Resources;
import android.media.AudioRecord;
import android.media.MediaSyncEvent;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.PersistableBundle;

import com.android.internal.R;
import com.android.internal.util.DataClass;
import com.android.internal.util.Preconditions;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Represents a result supporting the hotword detection.
 *
 * @hide
 */
@DataClass(
        genConstructor = false,
        genBuilder = true,
        genEqualsHashCode = true,
        genHiddenConstDefs = true,
        genParcelable = true,
        genToString = true
)
@SystemApi
public final class HotwordDetectedResult implements Parcelable {

    /** No confidence in hotword detector result. */
    public static final int CONFIDENCE_LEVEL_NONE = 0;

    /** Low confidence in hotword detector result. */
    public static final int CONFIDENCE_LEVEL_LOW = 1;

    /** Low-to-medium confidence in hotword detector result. */
    public static final int CONFIDENCE_LEVEL_LOW_MEDIUM = 2;

    /** Medium confidence in hotword detector result. */
    public static final int CONFIDENCE_LEVEL_MEDIUM = 3;

    /** Medium-to-high confidence in hotword detector result. */
    public static final int CONFIDENCE_LEVEL_MEDIUM_HIGH = 4;

    /** High confidence in hotword detector result. */
    public static final int CONFIDENCE_LEVEL_HIGH = 5;

    /** Very high confidence in hotword detector result. */
    public static final int CONFIDENCE_LEVEL_VERY_HIGH = 6;

    /** @hide */
    @IntDef(prefix = {"CONFIDENCE_LEVEL_"}, value = {
            CONFIDENCE_LEVEL_NONE,
            CONFIDENCE_LEVEL_LOW,
            CONFIDENCE_LEVEL_LOW_MEDIUM,
            CONFIDENCE_LEVEL_MEDIUM,
            CONFIDENCE_LEVEL_MEDIUM_HIGH,
            CONFIDENCE_LEVEL_HIGH,
            CONFIDENCE_LEVEL_VERY_HIGH
    })
    @Retention(RetentionPolicy.SOURCE)
    @interface HotwordConfidenceLevelValue {
    }

    /** Represents unset value for the hotword offset. */
    public static final int HOTWORD_OFFSET_UNSET = -1;

    /** Represents unset value for the triggered audio channel. */
    public static final int AUDIO_CHANNEL_UNSET = -1;

    /** Represents unset value for the background audio signal power. */
    public static final int BACKGROUND_AUDIO_POWER_UNSET = -1;

    /** Limits the max value for the hotword offset. */
    private static final int LIMIT_HOTWORD_OFFSET_MAX_VALUE = 60 * 60 * 1000; // 1 hour

    /** Limits the max value for the triggered audio channel. */
    private static final int LIMIT_AUDIO_CHANNEL_MAX_VALUE = 63;

    /**
     * The bundle key for proximity
     *
     * TODO(b/238896013): Move the proximity logic out of bundle to proper API.
     */
    private static final String EXTRA_PROXIMITY =
            "android.service.voice.extra.PROXIMITY";

    /** Users’ proximity is unknown (proximity sensing was inconclusive and is unsupported). */
    public static final int PROXIMITY_UNKNOWN = -1;

    /** Proximity value that represents that the object is near. */
    public static final int PROXIMITY_NEAR = 1;

    /** Proximity value that represents that the object is far. */
    public static final int PROXIMITY_FAR = 2;

    /** @hide */
    @IntDef(prefix = {"PROXIMITY"}, value = {
            PROXIMITY_UNKNOWN,
            PROXIMITY_NEAR,
            PROXIMITY_FAR
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface ProximityValue {}

    /** Id of the current speaker
     *
     * <p>Only values between 0 and {@link #getMaxSpeakerId} (inclusive) are accepted.
     */
    private final int mSpeakerId;
    private static int defaultSpeakerId() {
        return 0;
    }

    /** Maximum number of active speaker ids. **/
    @SuppressLint("UnflaggedApi") // b/325678077 flags not supported in isolated process
    public static int getMaxSpeakerId() {
        return 15;
    }

    /** Confidence level in the trigger outcome. */
    @HotwordConfidenceLevelValue
    private final int mConfidenceLevel;
    private static int defaultConfidenceLevel() {
        return CONFIDENCE_LEVEL_NONE;
    }

    /**
     * A {@code MediaSyncEvent} that allows the {@link HotwordDetector} to recapture the audio
     * that contains the hotword trigger. This must be obtained using
     * {@link android.media.AudioRecord#shareAudioHistory(String, long)}.
     */
    @Nullable
    private MediaSyncEvent mMediaSyncEvent = null;

    /**
     * Offset in milliseconds the audio stream when the trigger event happened (end of hotword
     * phrase).
     *
     * <p>Only value between 0 and 3600000 (inclusive) is accepted.
     */
    private int mHotwordOffsetMillis = HOTWORD_OFFSET_UNSET;

    /**
     * Duration in milliseconds of the hotword trigger phrase.
     *
     * <p>Only values between 0 and {@link android.media.AudioRecord#getMaxSharedAudioHistoryMillis}
     * (inclusive) are accepted.
     */
    private int mHotwordDurationMillis = 0;

    /**
     * Audio channel containing the highest-confidence hotword signal.
     *
     * <p>Only value between 0 and 63 (inclusive) is accepted.
     */
    private int mAudioChannel = AUDIO_CHANNEL_UNSET;

    /**
     * Returns whether the trigger has happened due to model having been personalized to fit user's
     * voice.
     */
    private boolean mHotwordDetectionPersonalized = false;

    /**
     * Score for the hotword trigger.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    private final int mScore;
    private static int defaultScore() {
        return 0;
    }

    /**
     * Score for the hotword trigger for device user.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    private final int mPersonalizedScore;
    private static int defaultPersonalizedScore() {
        return 0;
    }

    /**
     * Returns the maximum values of {@link #getScore} and {@link #getPersonalizedScore}.
     * <p>
     * The float value should be calculated as {@code getScore() / getMaxScore()}.
     */
    public static int getMaxScore() {
        return 255;
    }

    /**
     * An ID representing the keyphrase that triggered the successful detection.
     *
     * <p>Only values between 0 and {@link #getMaxHotwordPhraseId()} (inclusive) are accepted.
     */
    private final int mHotwordPhraseId;
    private static int defaultHotwordPhraseId() {
        return 0;
    }

    /**
     * Returns the maximum value of {@link #getHotwordPhraseId()}.
     */
    public static int getMaxHotwordPhraseId() {
        return 63;
    }

    /**
     * The list of the audio streams containing audio bytes that were used for hotword detection.
     */
    @NonNull
    private final List<HotwordAudioStream> mAudioStreams;
    private static List<HotwordAudioStream> defaultAudioStreams() {
        return Collections.emptyList();
    }

    /**
     * App-specific extras to support trigger.
     *
     * <p>The size of this bundle will be limited to {@link #getMaxBundleSize}. Results will larger
     * bundles will be rejected.
     *
     * <p>Only primitive types are supported in this bundle. Complex types will be removed from the
     * bundle.
     *
     * <p>The use of this method is discouraged, and support for it will be removed in future
     * versions of Android.
     *
     * <p>After the trigger happens, a special case of proximity-related extra, with the key of
     * 'android.service.voice.extra.PROXIMITY_VALUE' and the value of proximity value (integer)
     * will be stored to enable proximity logic. {@link HotwordDetectedResult#PROXIMITY_NEAR} will
     * indicate 'NEAR' proximity and {@link HotwordDetectedResult#PROXIMITY_FAR} will indicate 'FAR'
     * proximity. The proximity value is provided by the system, on devices that support detecting
     * proximity of nearby users, to help disambiguate which nearby device should respond. When the
     * proximity is unknown, the proximity value will not be stored. This mapping will be excluded
     * from the max bundle size calculation because this mapping is included after the result is
     * returned from the hotword detector service.
     *
     * <p>This is a PersistableBundle so it doesn't allow any remotable objects or other contents
     * that can be used to communicate with other processes.
     */
    @NonNull
    private final PersistableBundle mExtras;
    private static PersistableBundle defaultExtras() {
        return new PersistableBundle();
    }

    private static int sMaxBundleSize = -1;

    /**
     * Returns the maximum byte size of the information contained in the bundle.
     *
     * <p>The total size will be calculated by how much bundle data should be written into the
     * Parcel.
     */
    public static int getMaxBundleSize() {
        if (sMaxBundleSize < 0) {
            sMaxBundleSize = Resources.getSystem().getInteger(
                    R.integer.config_hotwordDetectedResultMaxBundleSize);
        }
        return sMaxBundleSize;
    }

    /**
     * A {@code MediaSyncEvent} that allows the {@link HotwordDetector} to recapture the audio
     * that contains the hotword trigger. This must be obtained using
     * {@link android.media.AudioRecord#shareAudioHistory(String, long)}.
     * <p>
     * This can be {@code null} if reprocessing the hotword trigger isn't required.
     */
    // Suppress codegen to make javadoc consistent. Getter returns @Nullable, setter accepts
    // @NonNull only, and by default codegen would use the same javadoc on both.
    public @Nullable MediaSyncEvent getMediaSyncEvent() {
        return mMediaSyncEvent;
    }

    /**
     * Power of the background audio signal in which the hotword phrase was detected.
     *
     * <p> Only values between 0 and {@link #getMaxBackgroundAudioPower} (inclusive)
     * and the special value {@link #BACKGROUND_AUDIO_POWER_UNSET} are valid.
     *
     * <p> This value is unitless. The relation between this value and the real audio signal
     * power measured in decibels depends on the hotword detection service implementation.
     */
    private final int mBackgroundAudioPower;
    private static int defaultBackgroundAudioPower() {
        return BACKGROUND_AUDIO_POWER_UNSET;
    }

    /**
     * Returns the maximum value of {@link #getBackgroundAudioPower()}.
     */
    public static int getMaxBackgroundAudioPower() {
        return 255;
    }

    /**
     * Returns how many bytes should be written into the Parcel
     *
     * @hide
     */
    public static int getParcelableSize(@NonNull Parcelable parcelable) {
        final Parcel p = Parcel.obtain();
        parcelable.writeToParcel(p, 0);
        p.setDataPosition(0);
        final int size = p.dataSize();
        p.recycle();
        return size;
    }

    /**
     * Returns how many bits have been written into the HotwordDetectedResult.
     *
     * @hide
     */
    public static int getUsageSize(@NonNull HotwordDetectedResult hotwordDetectedResult) {
        int totalBits = 0;

        if (hotwordDetectedResult.getConfidenceLevel() != defaultConfidenceLevel()) {
            totalBits += bitCount(CONFIDENCE_LEVEL_VERY_HIGH);
        }
        if (hotwordDetectedResult.getHotwordOffsetMillis() != HOTWORD_OFFSET_UNSET) {
            totalBits += bitCount(LIMIT_HOTWORD_OFFSET_MAX_VALUE);
        }
        if (hotwordDetectedResult.getHotwordDurationMillis() != 0) {
            totalBits += bitCount(AudioRecord.getMaxSharedAudioHistoryMillis());
        }
        if (hotwordDetectedResult.getAudioChannel() != AUDIO_CHANNEL_UNSET) {
            totalBits += bitCount(LIMIT_AUDIO_CHANNEL_MAX_VALUE);
        }

        // Add one bit for HotwordDetectionPersonalized
        totalBits += 1;

        if (hotwordDetectedResult.getScore() != defaultScore()) {
            totalBits += bitCount(HotwordDetectedResult.getMaxScore());
        }
        if (hotwordDetectedResult.getPersonalizedScore() != defaultPersonalizedScore()) {
            totalBits += bitCount(HotwordDetectedResult.getMaxScore());
        }
        if (hotwordDetectedResult.getHotwordPhraseId() != defaultHotwordPhraseId()) {
            totalBits += bitCount(HotwordDetectedResult.getMaxHotwordPhraseId());
        }
        PersistableBundle persistableBundle = hotwordDetectedResult.getExtras();
        if (!persistableBundle.isEmpty()) {
            totalBits += getParcelableSize(persistableBundle) * Byte.SIZE;
        }
        if (hotwordDetectedResult.getBackgroundAudioPower() != defaultBackgroundAudioPower()) {
            totalBits += bitCount(HotwordDetectedResult.getMaxBackgroundAudioPower());
        }

        return totalBits;
    }

    private static int bitCount(long value) {
        int bits = 0;
        while (value > 0) {
            bits++;
            value = value >> 1;
        }
        return bits;
    }

    private void onConstructed() {
        Preconditions.checkArgumentInRange(mSpeakerId, 0, getMaxSpeakerId(), "speakerId");
        Preconditions.checkArgumentInRange(mScore, 0, getMaxScore(), "score");
        Preconditions.checkArgumentInRange(mPersonalizedScore, 0, getMaxScore(),
                "personalizedScore");
        Preconditions.checkArgumentInRange(mHotwordPhraseId, 0, getMaxHotwordPhraseId(),
                "hotwordPhraseId");
        if (mBackgroundAudioPower != BACKGROUND_AUDIO_POWER_UNSET) {
            Preconditions.checkArgumentInRange(mBackgroundAudioPower,
                    0, getMaxBackgroundAudioPower(), "backgroundAudioPower");
        }
        Preconditions.checkArgumentInRange((long) mHotwordDurationMillis, 0,
                AudioRecord.getMaxSharedAudioHistoryMillis(), "hotwordDurationMillis");
        if (mHotwordOffsetMillis != HOTWORD_OFFSET_UNSET) {
            Preconditions.checkArgumentInRange(mHotwordOffsetMillis, 0,
                    LIMIT_HOTWORD_OFFSET_MAX_VALUE, "hotwordOffsetMillis");
        }
        if (mAudioChannel != AUDIO_CHANNEL_UNSET) {
            Preconditions.checkArgumentInRange(mAudioChannel, 0, LIMIT_AUDIO_CHANNEL_MAX_VALUE,
                    "audioChannel");
        }
        if (!mExtras.isEmpty()) {
            // Remove the proximity key from the bundle before checking the bundle size. The
            // proximity value is added after the privileged module and can avoid the
            // maxBundleSize limitation.
            if (mExtras.containsKey(EXTRA_PROXIMITY)) {
                int proximityValue = mExtras.getInt(EXTRA_PROXIMITY);
                mExtras.remove(EXTRA_PROXIMITY);
                // Skip checking parcelable size if the new bundle size is 0. Newly empty bundle
                // has parcelable size of 4, but the default bundle has parcelable size of 0.
                if (mExtras.size() > 0) {
                    Preconditions.checkArgumentInRange(getParcelableSize(mExtras), 0,
                            getMaxBundleSize(), "extras");
                }
                mExtras.putInt(EXTRA_PROXIMITY, proximityValue);
            } else {
                Preconditions.checkArgumentInRange(getParcelableSize(mExtras), 0,
                        getMaxBundleSize(), "extras");
            }
        }
    }

    /**
     * The list of the audio streams containing audio bytes that were used for hotword detection.
     */
    public @NonNull List<HotwordAudioStream> getAudioStreams() {
        return List.copyOf(mAudioStreams);
    }

    /**
     * Adds proximity level, either near or far, that is mapped for the given distance into
     * the bundle. The proximity value is provided by the system, on devices that support detecting
     * proximity of nearby users, to help disambiguate which nearby device should respond.
     * This mapping will be excluded from the max bundle size calculation because this mapping is
     * included after the result is returned from the hotword detector service. The value will not
     * be included if the proximity was unknown.
     *
     * @hide
     */
    public void setProximity(double distance) {
        int proximityLevel = convertToProximityLevel(distance);
        if (proximityLevel != PROXIMITY_UNKNOWN) {
            mExtras.putInt(EXTRA_PROXIMITY, proximityLevel);
        }
    }

    /**
     * Returns proximity level, which can be either of {@link HotwordDetectedResult#PROXIMITY_NEAR}
     * or {@link HotwordDetectedResult#PROXIMITY_FAR}. If the proximity is unknown, it will
     * return {@link HotwordDetectedResult#PROXIMITY_UNKNOWN}.
     */
    @ProximityValue
    public int getProximity() {
        return mExtras.getInt(EXTRA_PROXIMITY, PROXIMITY_UNKNOWN);
    }

    /**
     * Mapping of the proximity distance (meters) to proximity values, unknown, near, and far.
     * Currently, this mapping is handled by HotwordDetectedResult because it handles just
     * HotwordDetectionConnection which we know the mapping of. However, the mapping will need to
     * move to a more centralized place once there are more clients.
     *
     * TODO(b/258531144): Move the proximity mapping to a central location
     */
    @ProximityValue
    private int convertToProximityLevel(double distance) {
        if (distance < 0) {
            return PROXIMITY_UNKNOWN;
        } else if (distance <= 3) {
            return PROXIMITY_NEAR;
        } else {
            return PROXIMITY_FAR;
        }
    }

    @DataClass.Suppress("addAudioStreams")
    abstract static class BaseBuilder {
        /**
         * The list of the audio streams containing audio bytes that were used for hotword
         * detection.
         */
        public @NonNull Builder setAudioStreams(@NonNull List<HotwordAudioStream> value) {
            Objects.requireNonNull(value, "value should not be null");
            final Builder builder = (Builder) this;
            // If the code gen flag in build() is changed, we must update the flag e.g. 0x400 here.
            builder.mBuilderFieldsSet |= 0x400;
            builder.mAudioStreams = List.copyOf(value);
            return builder;
        }
    }

    /**
     * Provides an instance of {@link Builder} with state corresponding to this instance.
     * @hide
     */
    public Builder buildUpon() {
        return new Builder()
            .setConfidenceLevel(mConfidenceLevel)
            .setMediaSyncEvent(mMediaSyncEvent)
            .setHotwordOffsetMillis(mHotwordOffsetMillis)
            .setHotwordDurationMillis(mHotwordDurationMillis)
            .setAudioChannel(mAudioChannel)
            .setHotwordDetectionPersonalized(mHotwordDetectionPersonalized)
            .setScore(mScore)
            .setPersonalizedScore(mPersonalizedScore)
            .setHotwordPhraseId(mHotwordPhraseId)
            .setAudioStreams(mAudioStreams)
            .setExtras(mExtras)
            .setBackgroundAudioPower(mBackgroundAudioPower)
            .setSpeakerId(mSpeakerId);
    }



    // Code below generated by codegen v1.0.23.
    //
    // DO NOT MODIFY!
    // CHECKSTYLE:OFF Generated code
    //
    // To regenerate run:
    // $ codegen $ANDROID_BUILD_TOP/frameworks/base/core/java/android/service/voice/HotwordDetectedResult.java
    //
    // To exclude the generated code from IntelliJ auto-formatting enable (one-time):
    //   Settings > Editor > Code Style > Formatter Control
    //@formatter:off


    /** @hide */
    @IntDef(prefix = "CONFIDENCE_LEVEL_", value = {
        CONFIDENCE_LEVEL_NONE,
        CONFIDENCE_LEVEL_LOW,
        CONFIDENCE_LEVEL_LOW_MEDIUM,
        CONFIDENCE_LEVEL_MEDIUM,
        CONFIDENCE_LEVEL_MEDIUM_HIGH,
        CONFIDENCE_LEVEL_HIGH,
        CONFIDENCE_LEVEL_VERY_HIGH
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface ConfidenceLevel {}

    /** @hide */
    @DataClass.Generated.Member
    public static String confidenceLevelToString(@ConfidenceLevel int value) {
        switch (value) {
            case CONFIDENCE_LEVEL_NONE:
                    return "CONFIDENCE_LEVEL_NONE";
            case CONFIDENCE_LEVEL_LOW:
                    return "CONFIDENCE_LEVEL_LOW";
            case CONFIDENCE_LEVEL_LOW_MEDIUM:
                    return "CONFIDENCE_LEVEL_LOW_MEDIUM";
            case CONFIDENCE_LEVEL_MEDIUM:
                    return "CONFIDENCE_LEVEL_MEDIUM";
            case CONFIDENCE_LEVEL_MEDIUM_HIGH:
                    return "CONFIDENCE_LEVEL_MEDIUM_HIGH";
            case CONFIDENCE_LEVEL_HIGH:
                    return "CONFIDENCE_LEVEL_HIGH";
            case CONFIDENCE_LEVEL_VERY_HIGH:
                    return "CONFIDENCE_LEVEL_VERY_HIGH";
            default: return Integer.toHexString(value);
        }
    }

    /** @hide */
    @IntDef(prefix = "LIMIT_", value = {
        LIMIT_HOTWORD_OFFSET_MAX_VALUE,
        LIMIT_AUDIO_CHANNEL_MAX_VALUE
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    /* package-private */ @interface Limit {}

    /** @hide */
    @DataClass.Generated.Member
    /* package-private */ static String limitToString(@Limit int value) {
        switch (value) {
            case LIMIT_HOTWORD_OFFSET_MAX_VALUE:
                    return "LIMIT_HOTWORD_OFFSET_MAX_VALUE";
            case LIMIT_AUDIO_CHANNEL_MAX_VALUE:
                    return "LIMIT_AUDIO_CHANNEL_MAX_VALUE";
            default: return Integer.toHexString(value);
        }
    }

    /** @hide */
    @IntDef(prefix = "PROXIMITY_", value = {
        PROXIMITY_UNKNOWN,
        PROXIMITY_NEAR,
        PROXIMITY_FAR
    })
    @Retention(RetentionPolicy.SOURCE)
    @DataClass.Generated.Member
    public @interface Proximity {}

    /** @hide */
    @DataClass.Generated.Member
    public static String proximityToString(@Proximity int value) {
        switch (value) {
            case PROXIMITY_UNKNOWN:
                    return "PROXIMITY_UNKNOWN";
            case PROXIMITY_NEAR:
                    return "PROXIMITY_NEAR";
            case PROXIMITY_FAR:
                    return "PROXIMITY_FAR";
            default: return Integer.toHexString(value);
        }
    }

    @DataClass.Generated.Member
    /* package-private */ HotwordDetectedResult(
            int speakerId,
            @HotwordConfidenceLevelValue int confidenceLevel,
            @Nullable MediaSyncEvent mediaSyncEvent,
            int hotwordOffsetMillis,
            int hotwordDurationMillis,
            int audioChannel,
            boolean hotwordDetectionPersonalized,
            int score,
            int personalizedScore,
            int hotwordPhraseId,
            @NonNull List<HotwordAudioStream> audioStreams,
            @NonNull PersistableBundle extras,
            int backgroundAudioPower) {
        this.mSpeakerId = speakerId;
        this.mConfidenceLevel = confidenceLevel;
        com.android.internal.util.AnnotationValidations.validate(
                HotwordConfidenceLevelValue.class, null, mConfidenceLevel);
        this.mMediaSyncEvent = mediaSyncEvent;
        this.mHotwordOffsetMillis = hotwordOffsetMillis;
        this.mHotwordDurationMillis = hotwordDurationMillis;
        this.mAudioChannel = audioChannel;
        this.mHotwordDetectionPersonalized = hotwordDetectionPersonalized;
        this.mScore = score;
        this.mPersonalizedScore = personalizedScore;
        this.mHotwordPhraseId = hotwordPhraseId;
        this.mAudioStreams = audioStreams;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAudioStreams);
        this.mExtras = extras;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mExtras);
        this.mBackgroundAudioPower = backgroundAudioPower;

        onConstructed();
    }

    /**
     * Id of the current speaker
     *
     * <p>Only values between 0 and {@link #getMaxSpeakerId} (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    @SuppressLint("UnflaggedApi") // b/325678077 flags not supported in isolated process
    public int getSpeakerId() {
        return mSpeakerId;
    }

    /**
     * Confidence level in the trigger outcome.
     */
    @DataClass.Generated.Member
    public @HotwordConfidenceLevelValue int getConfidenceLevel() {
        return mConfidenceLevel;
    }

    /**
     * Offset in milliseconds the audio stream when the trigger event happened (end of hotword
     * phrase).
     *
     * <p>Only value between 0 and 3600000 (inclusive) is accepted.
     */
    @DataClass.Generated.Member
    public int getHotwordOffsetMillis() {
        return mHotwordOffsetMillis;
    }

    /**
     * Duration in milliseconds of the hotword trigger phrase.
     *
     * <p>Only values between 0 and {@link android.media.AudioRecord#getMaxSharedAudioHistoryMillis}
     * (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    public int getHotwordDurationMillis() {
        return mHotwordDurationMillis;
    }

    /**
     * Audio channel containing the highest-confidence hotword signal.
     *
     * <p>Only value between 0 and 63 (inclusive) is accepted.
     */
    @DataClass.Generated.Member
    public int getAudioChannel() {
        return mAudioChannel;
    }

    /**
     * Returns whether the trigger has happened due to model having been personalized to fit user's
     * voice.
     */
    @DataClass.Generated.Member
    public boolean isHotwordDetectionPersonalized() {
        return mHotwordDetectionPersonalized;
    }

    /**
     * Score for the hotword trigger.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    public int getScore() {
        return mScore;
    }

    /**
     * Score for the hotword trigger for device user.
     *
     * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    public int getPersonalizedScore() {
        return mPersonalizedScore;
    }

    /**
     * An ID representing the keyphrase that triggered the successful detection.
     *
     * <p>Only values between 0 and {@link #getMaxHotwordPhraseId()} (inclusive) are accepted.
     */
    @DataClass.Generated.Member
    public int getHotwordPhraseId() {
        return mHotwordPhraseId;
    }

    /**
     * App-specific extras to support trigger.
     *
     * <p>The size of this bundle will be limited to {@link #getMaxBundleSize}. Results will larger
     * bundles will be rejected.
     *
     * <p>Only primitive types are supported in this bundle. Complex types will be removed from the
     * bundle.
     *
     * <p>The use of this method is discouraged, and support for it will be removed in future
     * versions of Android.
     *
     * <p>After the trigger happens, a special case of proximity-related extra, with the key of
     * 'android.service.voice.extra.PROXIMITY_VALUE' and the value of proximity value (integer)
     * will be stored to enable proximity logic. {@link HotwordDetectedResult#PROXIMITY_NEAR} will
     * indicate 'NEAR' proximity and {@link HotwordDetectedResult#PROXIMITY_FAR} will indicate 'FAR'
     * proximity. The proximity value is provided by the system, on devices that support detecting
     * proximity of nearby users, to help disambiguate which nearby device should respond. When the
     * proximity is unknown, the proximity value will not be stored. This mapping will be excluded
     * from the max bundle size calculation because this mapping is included after the result is
     * returned from the hotword detector service.
     *
     * <p>This is a PersistableBundle so it doesn't allow any remotable objects or other contents
     * that can be used to communicate with other processes.
     */
    @DataClass.Generated.Member
    public @NonNull PersistableBundle getExtras() {
        return mExtras;
    }

    /**
     * Power of the background audio signal in which the hotword phrase was detected.
     *
     * <p> Only values between 0 and {@link #getMaxBackgroundAudioPower} (inclusive)
     * and the special value {@link #BACKGROUND_AUDIO_POWER_UNSET} are valid.
     *
     * <p> This value is unitless. The relation between this value and the real audio signal
     * power measured in decibels depends on the hotword detection service implementation.
     */
    @DataClass.Generated.Member
    public int getBackgroundAudioPower() {
        return mBackgroundAudioPower;
    }

    @Override
    @DataClass.Generated.Member
    public String toString() {
        // You can override field toString logic by defining methods like:
        // String fieldNameToString() { ... }

        return "HotwordDetectedResult { " +
                "speakerId = " + mSpeakerId + ", " +
                "confidenceLevel = " + mConfidenceLevel + ", " +
                "mediaSyncEvent = " + mMediaSyncEvent + ", " +
                "hotwordOffsetMillis = " + mHotwordOffsetMillis + ", " +
                "hotwordDurationMillis = " + mHotwordDurationMillis + ", " +
                "audioChannel = " + mAudioChannel + ", " +
                "hotwordDetectionPersonalized = " + mHotwordDetectionPersonalized + ", " +
                "score = " + mScore + ", " +
                "personalizedScore = " + mPersonalizedScore + ", " +
                "hotwordPhraseId = " + mHotwordPhraseId + ", " +
                "audioStreams = " + mAudioStreams + ", " +
                "extras = " + mExtras + ", " +
                "backgroundAudioPower = " + mBackgroundAudioPower +
        " }";
    }

    @Override
    @DataClass.Generated.Member
    public boolean equals(@Nullable Object o) {
        // You can override field equality logic by defining either of the methods like:
        // boolean fieldNameEquals(HotwordDetectedResult other) { ... }
        // boolean fieldNameEquals(FieldType otherValue) { ... }

        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        @SuppressWarnings("unchecked")
        HotwordDetectedResult that = (HotwordDetectedResult) o;
        //noinspection PointlessBooleanExpression
        return true
                && mSpeakerId == that.mSpeakerId
                && mConfidenceLevel == that.mConfidenceLevel
                && Objects.equals(mMediaSyncEvent, that.mMediaSyncEvent)
                && mHotwordOffsetMillis == that.mHotwordOffsetMillis
                && mHotwordDurationMillis == that.mHotwordDurationMillis
                && mAudioChannel == that.mAudioChannel
                && mHotwordDetectionPersonalized == that.mHotwordDetectionPersonalized
                && mScore == that.mScore
                && mPersonalizedScore == that.mPersonalizedScore
                && mHotwordPhraseId == that.mHotwordPhraseId
                && Objects.equals(mAudioStreams, that.mAudioStreams)
                && Objects.equals(mExtras, that.mExtras)
                && mBackgroundAudioPower == that.mBackgroundAudioPower;
    }

    @Override
    @DataClass.Generated.Member
    public int hashCode() {
        // You can override field hashCode logic by defining methods like:
        // int fieldNameHashCode() { ... }

        int _hash = 1;
        _hash = 31 * _hash + mSpeakerId;
        _hash = 31 * _hash + mConfidenceLevel;
        _hash = 31 * _hash + Objects.hashCode(mMediaSyncEvent);
        _hash = 31 * _hash + mHotwordOffsetMillis;
        _hash = 31 * _hash + mHotwordDurationMillis;
        _hash = 31 * _hash + mAudioChannel;
        _hash = 31 * _hash + Boolean.hashCode(mHotwordDetectionPersonalized);
        _hash = 31 * _hash + mScore;
        _hash = 31 * _hash + mPersonalizedScore;
        _hash = 31 * _hash + mHotwordPhraseId;
        _hash = 31 * _hash + Objects.hashCode(mAudioStreams);
        _hash = 31 * _hash + Objects.hashCode(mExtras);
        _hash = 31 * _hash + mBackgroundAudioPower;
        return _hash;
    }

    @Override
    @DataClass.Generated.Member
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // You can override field parcelling by defining methods like:
        // void parcelFieldName(Parcel dest, int flags) { ... }

        int flg = 0;
        if (mHotwordDetectionPersonalized) flg |= 0x40;
        if (mMediaSyncEvent != null) flg |= 0x4;
        dest.writeInt(flg);
        dest.writeInt(mSpeakerId);
        dest.writeInt(mConfidenceLevel);
        if (mMediaSyncEvent != null) dest.writeTypedObject(mMediaSyncEvent, flags);
        dest.writeInt(mHotwordOffsetMillis);
        dest.writeInt(mHotwordDurationMillis);
        dest.writeInt(mAudioChannel);
        dest.writeInt(mScore);
        dest.writeInt(mPersonalizedScore);
        dest.writeInt(mHotwordPhraseId);
        dest.writeParcelableList(mAudioStreams, flags);
        dest.writeTypedObject(mExtras, flags);
        dest.writeInt(mBackgroundAudioPower);
    }

    @Override
    @DataClass.Generated.Member
    public int describeContents() { return 0; }

    /** @hide */
    @SuppressWarnings({"unchecked", "RedundantCast"})
    @DataClass.Generated.Member
    /* package-private */ HotwordDetectedResult(@NonNull Parcel in) {
        // You can override field unparcelling by defining methods like:
        // static FieldType unparcelFieldName(Parcel in) { ... }

        int flg = in.readInt();
        boolean hotwordDetectionPersonalized = (flg & 0x40) != 0;
        int speakerId = in.readInt();
        int confidenceLevel = in.readInt();
        MediaSyncEvent mediaSyncEvent = (flg & 0x4) == 0 ? null : (MediaSyncEvent) in.readTypedObject(MediaSyncEvent.CREATOR);
        int hotwordOffsetMillis = in.readInt();
        int hotwordDurationMillis = in.readInt();
        int audioChannel = in.readInt();
        int score = in.readInt();
        int personalizedScore = in.readInt();
        int hotwordPhraseId = in.readInt();
        List<HotwordAudioStream> audioStreams = new ArrayList<>();
        in.readParcelableList(audioStreams, HotwordAudioStream.class.getClassLoader());
        PersistableBundle extras = (PersistableBundle) in.readTypedObject(PersistableBundle.CREATOR);
        int backgroundAudioPower = in.readInt();

        this.mSpeakerId = speakerId;
        this.mConfidenceLevel = confidenceLevel;
        com.android.internal.util.AnnotationValidations.validate(
                HotwordConfidenceLevelValue.class, null, mConfidenceLevel);
        this.mMediaSyncEvent = mediaSyncEvent;
        this.mHotwordOffsetMillis = hotwordOffsetMillis;
        this.mHotwordDurationMillis = hotwordDurationMillis;
        this.mAudioChannel = audioChannel;
        this.mHotwordDetectionPersonalized = hotwordDetectionPersonalized;
        this.mScore = score;
        this.mPersonalizedScore = personalizedScore;
        this.mHotwordPhraseId = hotwordPhraseId;
        this.mAudioStreams = audioStreams;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mAudioStreams);
        this.mExtras = extras;
        com.android.internal.util.AnnotationValidations.validate(
                NonNull.class, null, mExtras);
        this.mBackgroundAudioPower = backgroundAudioPower;

        onConstructed();
    }

    @DataClass.Generated.Member
    public static final @NonNull Parcelable.Creator<HotwordDetectedResult> CREATOR
            = new Parcelable.Creator<HotwordDetectedResult>() {
        @Override
        public HotwordDetectedResult[] newArray(int size) {
            return new HotwordDetectedResult[size];
        }

        @Override
        public HotwordDetectedResult createFromParcel(@NonNull Parcel in) {
            return new HotwordDetectedResult(in);
        }
    };

    /**
     * A builder for {@link HotwordDetectedResult}
     */
    @SuppressWarnings("WeakerAccess")
    @DataClass.Generated.Member
    public static final class Builder extends BaseBuilder {

        private int mSpeakerId;
        private @HotwordConfidenceLevelValue int mConfidenceLevel;
        private @Nullable MediaSyncEvent mMediaSyncEvent;
        private int mHotwordOffsetMillis;
        private int mHotwordDurationMillis;
        private int mAudioChannel;
        private boolean mHotwordDetectionPersonalized;
        private int mScore;
        private int mPersonalizedScore;
        private int mHotwordPhraseId;
        private @NonNull List<HotwordAudioStream> mAudioStreams;
        private @NonNull PersistableBundle mExtras;
        private int mBackgroundAudioPower;

        private long mBuilderFieldsSet = 0L;

        public Builder() {
        }

        /**
         * Id of the current speaker
         *
         * <p>Only values between 0 and {@link #getMaxSpeakerId} (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        @SuppressLint("UnflaggedApi") // b/325678077 flags not supported in isolated process
        public @NonNull Builder setSpeakerId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1;
            mSpeakerId = value;
            return this;
        }

        /**
         * Confidence level in the trigger outcome.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setConfidenceLevel(@HotwordConfidenceLevelValue int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2;
            mConfidenceLevel = value;
            return this;
        }

        /**
         * A {@code MediaSyncEvent} that allows the {@link HotwordDetector} to recapture the audio
         * that contains the hotword trigger. This must be obtained using
         * {@link android.media.AudioRecord#shareAudioHistory(String, long)}.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setMediaSyncEvent(@NonNull MediaSyncEvent value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x4;
            mMediaSyncEvent = value;
            return this;
        }

        /**
         * Offset in milliseconds the audio stream when the trigger event happened (end of hotword
         * phrase).
         *
         * <p>Only value between 0 and 3600000 (inclusive) is accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setHotwordOffsetMillis(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x8;
            mHotwordOffsetMillis = value;
            return this;
        }

        /**
         * Duration in milliseconds of the hotword trigger phrase.
         *
         * <p>Only values between 0 and {@link android.media.AudioRecord#getMaxSharedAudioHistoryMillis}
         * (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setHotwordDurationMillis(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x10;
            mHotwordDurationMillis = value;
            return this;
        }

        /**
         * Audio channel containing the highest-confidence hotword signal.
         *
         * <p>Only value between 0 and 63 (inclusive) is accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setAudioChannel(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x20;
            mAudioChannel = value;
            return this;
        }

        /**
         * Returns whether the trigger has happened due to model having been personalized to fit user's
         * voice.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setHotwordDetectionPersonalized(boolean value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x40;
            mHotwordDetectionPersonalized = value;
            return this;
        }

        /**
         * Score for the hotword trigger.
         *
         * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setScore(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x80;
            mScore = value;
            return this;
        }

        /**
         * Score for the hotword trigger for device user.
         *
         * <p>Only values between 0 and {@link #getMaxScore} (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setPersonalizedScore(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x100;
            mPersonalizedScore = value;
            return this;
        }

        /**
         * An ID representing the keyphrase that triggered the successful detection.
         *
         * <p>Only values between 0 and {@link #getMaxHotwordPhraseId()} (inclusive) are accepted.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setHotwordPhraseId(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x200;
            mHotwordPhraseId = value;
            return this;
        }

        /**
         * App-specific extras to support trigger.
         *
         * <p>The size of this bundle will be limited to {@link #getMaxBundleSize}. Results will larger
         * bundles will be rejected.
         *
         * <p>Only primitive types are supported in this bundle. Complex types will be removed from the
         * bundle.
         *
         * <p>The use of this method is discouraged, and support for it will be removed in future
         * versions of Android.
         *
         * <p>After the trigger happens, a special case of proximity-related extra, with the key of
         * 'android.service.voice.extra.PROXIMITY_VALUE' and the value of proximity value (integer)
         * will be stored to enable proximity logic. {@link HotwordDetectedResult#PROXIMITY_NEAR} will
         * indicate 'NEAR' proximity and {@link HotwordDetectedResult#PROXIMITY_FAR} will indicate 'FAR'
         * proximity. The proximity value is provided by the system, on devices that support detecting
         * proximity of nearby users, to help disambiguate which nearby device should respond. When the
         * proximity is unknown, the proximity value will not be stored. This mapping will be excluded
         * from the max bundle size calculation because this mapping is included after the result is
         * returned from the hotword detector service.
         *
         * <p>This is a PersistableBundle so it doesn't allow any remotable objects or other contents
         * that can be used to communicate with other processes.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setExtras(@NonNull PersistableBundle value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x800;
            mExtras = value;
            return this;
        }

        /**
         * Power of the background audio signal in which the hotword phrase was detected.
         *
         * <p> Only values between 0 and {@link #getMaxBackgroundAudioPower} (inclusive)
         * and the special value {@link #BACKGROUND_AUDIO_POWER_UNSET} are valid.
         *
         * <p> This value is unitless. The relation between this value and the real audio signal
         * power measured in decibels depends on the hotword detection service implementation.
         */
        @DataClass.Generated.Member
        public @NonNull Builder setBackgroundAudioPower(int value) {
            checkNotUsed();
            mBuilderFieldsSet |= 0x1000;
            mBackgroundAudioPower = value;
            return this;
        }

        /** Builds the instance. This builder should not be touched after calling this! */
        public @NonNull HotwordDetectedResult build() {
            checkNotUsed();
            mBuilderFieldsSet |= 0x2000; // Mark builder used

            if ((mBuilderFieldsSet & 0x1) == 0) {
                mSpeakerId = defaultSpeakerId();
            }
            if ((mBuilderFieldsSet & 0x2) == 0) {
                mConfidenceLevel = defaultConfidenceLevel();
            }
            if ((mBuilderFieldsSet & 0x4) == 0) {
                mMediaSyncEvent = null;
            }
            if ((mBuilderFieldsSet & 0x8) == 0) {
                mHotwordOffsetMillis = HOTWORD_OFFSET_UNSET;
            }
            if ((mBuilderFieldsSet & 0x10) == 0) {
                mHotwordDurationMillis = 0;
            }
            if ((mBuilderFieldsSet & 0x20) == 0) {
                mAudioChannel = AUDIO_CHANNEL_UNSET;
            }
            if ((mBuilderFieldsSet & 0x40) == 0) {
                mHotwordDetectionPersonalized = false;
            }
            if ((mBuilderFieldsSet & 0x80) == 0) {
                mScore = defaultScore();
            }
            if ((mBuilderFieldsSet & 0x100) == 0) {
                mPersonalizedScore = defaultPersonalizedScore();
            }
            if ((mBuilderFieldsSet & 0x200) == 0) {
                mHotwordPhraseId = defaultHotwordPhraseId();
            }
            if ((mBuilderFieldsSet & 0x400) == 0) {
                mAudioStreams = defaultAudioStreams();
            }
            if ((mBuilderFieldsSet & 0x800) == 0) {
                mExtras = defaultExtras();
            }
            if ((mBuilderFieldsSet & 0x1000) == 0) {
                mBackgroundAudioPower = defaultBackgroundAudioPower();
            }
            HotwordDetectedResult o = new HotwordDetectedResult(
                    mSpeakerId,
                    mConfidenceLevel,
                    mMediaSyncEvent,
                    mHotwordOffsetMillis,
                    mHotwordDurationMillis,
                    mAudioChannel,
                    mHotwordDetectionPersonalized,
                    mScore,
                    mPersonalizedScore,
                    mHotwordPhraseId,
                    mAudioStreams,
                    mExtras,
                    mBackgroundAudioPower);
            return o;
        }

        private void checkNotUsed() {
            if ((mBuilderFieldsSet & 0x2000) != 0) {
                throw new IllegalStateException(
                        "This Builder should not be reused. Use a new Builder instance instead");
            }
        }
    }

    @DataClass.Generated(
            time = 1710918729668L,
            codegenVersion = "1.0.23",
            sourceFile = "frameworks/base/core/java/android/service/voice/HotwordDetectedResult.java",
            inputSignatures = "public static final  int CONFIDENCE_LEVEL_NONE\npublic static final  int CONFIDENCE_LEVEL_LOW\npublic static final  int CONFIDENCE_LEVEL_LOW_MEDIUM\npublic static final  int CONFIDENCE_LEVEL_MEDIUM\npublic static final  int CONFIDENCE_LEVEL_MEDIUM_HIGH\npublic static final  int CONFIDENCE_LEVEL_HIGH\npublic static final  int CONFIDENCE_LEVEL_VERY_HIGH\npublic static final  int HOTWORD_OFFSET_UNSET\npublic static final  int AUDIO_CHANNEL_UNSET\npublic static final  int BACKGROUND_AUDIO_POWER_UNSET\nprivate static final  int LIMIT_HOTWORD_OFFSET_MAX_VALUE\nprivate static final  int LIMIT_AUDIO_CHANNEL_MAX_VALUE\nprivate static final  java.lang.String EXTRA_PROXIMITY\npublic static final  int PROXIMITY_UNKNOWN\npublic static final  int PROXIMITY_NEAR\npublic static final  int PROXIMITY_FAR\nprivate final  int mSpeakerId\nprivate final @android.service.voice.HotwordDetectedResult.HotwordConfidenceLevelValue int mConfidenceLevel\nprivate @android.annotation.Nullable android.media.MediaSyncEvent mMediaSyncEvent\nprivate  int mHotwordOffsetMillis\nprivate  int mHotwordDurationMillis\nprivate  int mAudioChannel\nprivate  boolean mHotwordDetectionPersonalized\nprivate final  int mScore\nprivate final  int mPersonalizedScore\nprivate final  int mHotwordPhraseId\nprivate final @android.annotation.NonNull java.util.List<android.service.voice.HotwordAudioStream> mAudioStreams\nprivate final @android.annotation.NonNull android.os.PersistableBundle mExtras\nprivate static  int sMaxBundleSize\nprivate final  int mBackgroundAudioPower\nprivate static  int defaultSpeakerId()\npublic static @android.annotation.SuppressLint int getMaxSpeakerId()\nprivate static  int defaultConfidenceLevel()\nprivate static  int defaultScore()\nprivate static  int defaultPersonalizedScore()\npublic static  int getMaxScore()\nprivate static  int defaultHotwordPhraseId()\npublic static  int getMaxHotwordPhraseId()\nprivate static  java.util.List<android.service.voice.HotwordAudioStream> defaultAudioStreams()\nprivate static  android.os.PersistableBundle defaultExtras()\npublic static  int getMaxBundleSize()\npublic @android.annotation.Nullable android.media.MediaSyncEvent getMediaSyncEvent()\nprivate static  int defaultBackgroundAudioPower()\npublic static  int getMaxBackgroundAudioPower()\npublic static  int getParcelableSize(android.os.Parcelable)\npublic static  int getUsageSize(android.service.voice.HotwordDetectedResult)\nprivate static  int bitCount(long)\nprivate  void onConstructed()\npublic @android.annotation.NonNull java.util.List<android.service.voice.HotwordAudioStream> getAudioStreams()\npublic  void setProximity(double)\npublic @android.service.voice.HotwordDetectedResult.ProximityValue int getProximity()\nprivate @android.service.voice.HotwordDetectedResult.ProximityValue int convertToProximityLevel(double)\npublic  android.service.voice.HotwordDetectedResult.Builder buildUpon()\nclass HotwordDetectedResult extends java.lang.Object implements [android.os.Parcelable]\npublic @android.annotation.NonNull android.service.voice.HotwordDetectedResult.Builder setAudioStreams(java.util.List<android.service.voice.HotwordAudioStream>)\nclass BaseBuilder extends java.lang.Object implements []\n@com.android.internal.util.DataClass(genConstructor=false, genBuilder=true, genEqualsHashCode=true, genHiddenConstDefs=true, genParcelable=true, genToString=true)\npublic @android.annotation.NonNull android.service.voice.HotwordDetectedResult.Builder setAudioStreams(java.util.List<android.service.voice.HotwordAudioStream>)\nclass BaseBuilder extends java.lang.Object implements []")
    @Deprecated
    private void __metadata() {}


    //@formatter:on
    // End of generated code

}
