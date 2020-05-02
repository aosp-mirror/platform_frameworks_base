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

package android.media;

import android.annotation.NonNull;
import android.media.AudioAttributes.AttributeUsage;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioMixingRule.AudioMixMatchCriterion;
import android.media.projection.MediaProjection;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

import java.util.function.ToIntFunction;

/**
 * Configuration for capturing audio played by other apps.
 *
 *  When capturing audio signals played by other apps (and yours),
 *  you will only capture a mix of the audio signals played by players
 *  (such as AudioTrack or MediaPlayer) which present the following characteristics:
 *  <ul>
 *  <li> the usage value MUST be {@link AudioAttributes#USAGE_UNKNOWN} or
 *       {@link AudioAttributes#USAGE_GAME}
 *       or {@link AudioAttributes#USAGE_MEDIA}. All other usages CAN NOT be captured. </li>
 *  <li> AND the capture policy set by their app (with {@link AudioManager#setAllowedCapturePolicy})
 *       or on each player (with {@link AudioAttributes.Builder#setAllowedCapturePolicy}) is
 *       {@link AudioAttributes#ALLOW_CAPTURE_BY_ALL}, whichever is the most strict. </li>
 *  <li> AND their app attribute allowAudioPlaybackCapture in their manifest
 *       MUST either be: <ul>
 *       <li> set to "true" </li>
 *       <li> not set, and their {@code targetSdkVersion} MUST be equal to or greater than
 *            {@link android.os.Build.VERSION_CODES#Q}.
 *            Ie. Apps that do not target at least Android Q must explicitly opt-in to be captured
 *            by a MediaProjection. </li></ul>
 *  <li> AND their apps MUST be in the same user profile as your app
 *       (eg work profile cannot capture user profile apps and vice-versa). </li>
 *  </ul>
 *
 * <p>An example for creating a capture configuration for capturing all media playback:
 *
 * <pre>
 *     MediaProjection mediaProjection;
 *     // Retrieve a audio capable projection from the MediaProjectionManager
 *     AudioPlaybackCaptureConfiguration config =
 *         new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
 *         .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
 *         .build();
 *     AudioRecord record = new AudioRecord.Builder()
 *         .setAudioPlaybackCaptureConfig(config)
 *         .build();
 * </pre>
 *
 * @see Builder
 * @see android.media.projection.MediaProjectionManager#getMediaProjection(int, Intent)
 * @see AudioRecord.Builder#setAudioPlaybackCaptureConfig(AudioPlaybackCaptureConfiguration)
 */
public final class AudioPlaybackCaptureConfiguration {

    private final AudioMixingRule mAudioMixingRule;
    private final MediaProjection mProjection;

    private AudioPlaybackCaptureConfiguration(AudioMixingRule audioMixingRule,
                                              MediaProjection projection) {
        mAudioMixingRule = audioMixingRule;
        mProjection = projection;
    }

    /**
     * @return the {@code MediaProjection} used to build this object.
     * @see Builder#Builder(MediaProjection)
     */
    public @NonNull MediaProjection getMediaProjection() {
        return mProjection;
    }

    /** @return the usages passed to {@link Builder#addMatchingUsage(int)}. */
    @AttributeUsage
    public @NonNull int[] getMatchingUsages() {
        return getIntPredicates(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE,
                                criterion -> criterion.getAudioAttributes().getUsage());
    }

    /** @return the UIDs passed to {@link Builder#addMatchingUid(int)}. */
    public @NonNull int[] getMatchingUids() {
        return getIntPredicates(AudioMixingRule.RULE_MATCH_UID,
                                criterion -> criterion.getIntProp());
    }

    /** @return the usages passed to {@link Builder#excludeUsage(int)}. */
    @AttributeUsage
    public @NonNull int[] getExcludeUsages() {
        return getIntPredicates(AudioMixingRule.RULE_EXCLUDE_ATTRIBUTE_USAGE,
                                criterion -> criterion.getAudioAttributes().getUsage());
    }

    /** @return the UIDs passed to {@link Builder#excludeUid(int)}.  */
    public @NonNull int[] getExcludeUids() {
        return getIntPredicates(AudioMixingRule.RULE_EXCLUDE_UID,
                                criterion -> criterion.getIntProp());
    }

    private int[] getIntPredicates(int rule,
                                   ToIntFunction<AudioMixMatchCriterion> getPredicate) {
        return mAudioMixingRule.getCriteria().stream()
            .filter(criterion -> criterion.getRule() == rule)
            .mapToInt(getPredicate)
            .toArray();
    }

    /**
     * Returns a mix that routes audio back into the app while still playing it from the speakers.
     *
     * @param audioFormat The format in which to capture the audio.
     */
    @NonNull AudioMix createAudioMix(@NonNull AudioFormat audioFormat) {
        return new AudioMix.Builder(mAudioMixingRule)
                .setFormat(audioFormat)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK | AudioMix.ROUTE_FLAG_RENDER)
                .build();
    }

    /** Builder for creating {@link AudioPlaybackCaptureConfiguration} instances. */
    public static final class Builder {

        private static final int MATCH_TYPE_UNSPECIFIED = 0;
        private static final int MATCH_TYPE_INCLUSIVE = 1;
        private static final int MATCH_TYPE_EXCLUSIVE = 2;

        private static final String ERROR_MESSAGE_MISMATCHED_RULES =
                "Inclusive and exclusive usage rules cannot be combined";
        private static final String ERROR_MESSAGE_START_ACTIVITY_FAILED =
                "startActivityForResult failed";
        private static final String ERROR_MESSAGE_NON_AUDIO_PROJECTION =
                "MediaProjection can not project audio";

        private final AudioMixingRule.Builder mAudioMixingRuleBuilder;
        private final MediaProjection mProjection;
        private int mUsageMatchType = MATCH_TYPE_UNSPECIFIED;
        private int mUidMatchType = MATCH_TYPE_UNSPECIFIED;

        /** @param projection A MediaProjection that supports audio projection. */
        public Builder(@NonNull MediaProjection projection) {
            Preconditions.checkNotNull(projection);
            try {
                Preconditions.checkArgument(projection.getProjection().canProjectAudio(),
                                            ERROR_MESSAGE_NON_AUDIO_PROJECTION);
            } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
            }
            mProjection = projection;
            mAudioMixingRuleBuilder = new AudioMixingRule.Builder();
        }

        /**
         * Only capture audio output with the given {@link AudioAttributes}.
         *
         * <p>If called multiple times, will capture audio output that matches any of the given
         * attributes.
         *
         * @throws IllegalStateException if called in conjunction with
         *     {@link #excludeUsage(int)}.
         */
        public @NonNull Builder addMatchingUsage(@AttributeUsage int usage) {
            Preconditions.checkState(
                    mUsageMatchType != MATCH_TYPE_EXCLUSIVE, ERROR_MESSAGE_MISMATCHED_RULES);
            mAudioMixingRuleBuilder.addRule(new AudioAttributes.Builder().setUsage(usage).build(),
                                            AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
            mUsageMatchType = MATCH_TYPE_INCLUSIVE;
            return this;
        }

        /**
         * Only capture audio output by app with the matching {@code uid}.
         *
         * <p>If called multiple times, will capture audio output by apps whose uid is any of the
         * given uids.
         *
         * @throws IllegalStateException if called in conjunction with {@link #excludeUid(int)}.
         */
        public @NonNull Builder addMatchingUid(int uid) {
            Preconditions.checkState(
                    mUidMatchType != MATCH_TYPE_EXCLUSIVE, ERROR_MESSAGE_MISMATCHED_RULES);
            mAudioMixingRuleBuilder.addMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
            mUidMatchType = MATCH_TYPE_INCLUSIVE;
            return this;
        }

        /**
         * Only capture audio output that does not match the given {@link AudioAttributes}.
         *
         * <p>If called multiple times, will capture audio output that does not match any of the
         * given attributes.
         *
         * @throws IllegalStateException if called in conjunction with
         *     {@link #addMatchingUsage(int)}.
         */
        public @NonNull Builder excludeUsage(@AttributeUsage int usage) {
            Preconditions.checkState(
                    mUsageMatchType != MATCH_TYPE_INCLUSIVE, ERROR_MESSAGE_MISMATCHED_RULES);
            mAudioMixingRuleBuilder.excludeRule(new AudioAttributes.Builder()
                                                    .setUsage(usage)
                                                    .build(),
                                                AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
            mUsageMatchType = MATCH_TYPE_EXCLUSIVE;
            return this;
        }

        /**
         * Only capture audio output by apps that do not have the matching {@code uid}.
         *
         * <p>If called multiple times, will capture audio output by apps whose uid is not any of
         * the given uids.
         *
         * @throws IllegalStateException if called in conjunction with {@link #addMatchingUid(int)}.
         */
        public @NonNull Builder excludeUid(int uid) {
            Preconditions.checkState(
                    mUidMatchType != MATCH_TYPE_INCLUSIVE, ERROR_MESSAGE_MISMATCHED_RULES);
            mAudioMixingRuleBuilder.excludeMixRule(AudioMixingRule.RULE_MATCH_UID, uid);
            mUidMatchType = MATCH_TYPE_EXCLUSIVE;
            return this;
        }

        /**
         * Builds the configuration instance.
         *
         * @throws UnsupportedOperationException if the parameters set are incompatible.
         */
        public @NonNull AudioPlaybackCaptureConfiguration build() {
            return new AudioPlaybackCaptureConfiguration(mAudioMixingRuleBuilder.build(),
                                                         mProjection);
        }
    }
}
