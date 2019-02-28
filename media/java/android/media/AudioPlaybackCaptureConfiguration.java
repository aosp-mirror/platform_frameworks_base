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
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.projection.MediaProjection;
import android.os.RemoteException;

import com.android.internal.util.Preconditions;

/**
 * Configuration for capturing audio played by other apps.
 *
 * For privacy and copyright reason, only the following audio can be captured:
 *  - usage MUST be UNKNOWN or GAME or MEDIA. All other usages CAN NOT be capturable.
 *  - audio attributes MUST NOT have the FLAG_NO_CAPTURE
 *  - played by apps that MUST be in the same user profile as the capturing app
 *    (eg work profile can not capture user profile apps and vice-versa).
 *  - played by apps that MUST NOT have in their manifest.xml the application
 *    attribute android:allowAudioPlaybackCapture="false"
 *  - played by apps that MUST have a targetSdkVersion higher or equal to 29 (Q).
 *
 * <p>An example for creating a capture configuration for capturing all media playback:
 *
 * <pre>
 *     MediaProjection mediaProjection;
 *     // Retrieve a audio capable projection from the MediaProjectionManager
 *     AudioAttributes mediaAttr = new AudioAttributes.Builder()
 *         .setUsage(AudioAttributes.USAGE_MEDIA)
 *         .build();
 *     AudioPlaybackCaptureConfiguration config =
 *              new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
 *         .addMatchingUsage(mediaAttr)
 *         .build();
 *     AudioRecord record = new AudioRecord.Builder()
 *         .setAudioPlaybackCaptureConfig(config)
 *         .build();
 * </pre>
 *
 * @see MediaProjectionManager#getMediaProjection(int, Intent)
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
     * Returns a mix that routes audio back into the app while still playing it from the speakers.
     *
     * @param audioFormat The format in which to capture the audio.
     */
    AudioMix createAudioMix(AudioFormat audioFormat) {
        return new AudioMix.Builder(mAudioMixingRule)
                .setFormat(audioFormat)
                .setRouteFlags(AudioMix.ROUTE_FLAG_LOOP_BACK | AudioMix.ROUTE_FLAG_RENDER)
                .build();
    }
    MediaProjection getMediaProjection() {
        return mProjection;
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
         *     {@link #excludeUsage(AudioAttributes)}.
         */
        public Builder addMatchingUsage(@NonNull AudioAttributes audioAttributes) {
            Preconditions.checkNotNull(audioAttributes);
            Preconditions.checkState(
                    mUsageMatchType != MATCH_TYPE_EXCLUSIVE, ERROR_MESSAGE_MISMATCHED_RULES);
            mAudioMixingRuleBuilder
                    .addRule(audioAttributes, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
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
        public Builder addMatchingUid(int uid) {
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
         *     {@link #addMatchingUsage(AudioAttributes)}.
         */
        public Builder excludeUsage(@NonNull AudioAttributes audioAttributes) {
            Preconditions.checkNotNull(audioAttributes);
            Preconditions.checkState(
                    mUsageMatchType != MATCH_TYPE_INCLUSIVE, ERROR_MESSAGE_MISMATCHED_RULES);
            mAudioMixingRuleBuilder.excludeRule(audioAttributes,
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
        public Builder excludeUid(int uid) {
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
        public AudioPlaybackCaptureConfiguration build() {
            return new AudioPlaybackCaptureConfiguration(mAudioMixingRuleBuilder.build(),
                                                         mProjection);
        }
    }
}
