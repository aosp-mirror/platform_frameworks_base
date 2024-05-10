/*
 * Copyright (C) 2014 The Android Open Source Project
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

package android.media.audiopolicy;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.SystemApi;
import android.compat.annotation.UnsupportedAppUsage;
import android.media.AudioAttributes;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

import java.lang.annotation.Retention;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;


/**
 * @hide
 *
 * Here's an example of creating a mixing rule for all media playback:
 * <pre>
 * AudioAttributes mediaAttr = new AudioAttributes.Builder()
 *         .setUsage(AudioAttributes.USAGE_MEDIA)
 *         .build();
 * AudioMixingRule mediaRule = new AudioMixingRule.Builder()
 *         .addRule(mediaAttr, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
 *         .build();
 * </pre>
 */
@SystemApi
public class AudioMixingRule implements Parcelable {

    private AudioMixingRule(int mixType, Collection<AudioMixMatchCriterion> criteria,
                            boolean allowPrivilegedMediaPlaybackCapture,
                            boolean voiceCommunicationCaptureAllowed) {
        mCriteria = new ArrayList<>(criteria);
        mTargetMixType = mixType;
        mAllowPrivilegedPlaybackCapture = allowPrivilegedMediaPlaybackCapture;
        mVoiceCommunicationCaptureAllowed = voiceCommunicationCaptureAllowed;
    }

    /**
     * A rule requiring the usage information of the {@link AudioAttributes} to match.
     * This mixing rule can be added with {@link Builder#addRule(AudioAttributes, int)} or
     * {@link Builder#addMixRule(int, Object)} where the Object parameter is an instance of
     * {@link AudioAttributes}.
     */
    public static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    /**
     * A rule requiring the capture preset information of the {@link AudioAttributes} to match.
     * This mixing rule can be added with {@link Builder#addRule(AudioAttributes, int)} or
     * {@link Builder#addMixRule(int, Object)} where the Object parameter is an instance of
     * {@link AudioAttributes}.
     */
    public static final int RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x1 << 1;
    /**
     * A rule requiring the UID of the audio stream to match that specified.
     * This mixing rule can be added with {@link Builder#addMixRule(int, Object)} where the Object
     * parameter is an instance of {@link java.lang.Integer}.
     */
    public static final int RULE_MATCH_UID = 0x1 << 2;
    /**
     * A rule requiring the userId of the audio stream to match that specified.
     * This mixing rule can be added with {@link Builder#addMixRule(int, Object)} where the Object
     * parameter is an instance of {@link java.lang.Integer}.
     */
    public static final int RULE_MATCH_USERID = 0x1 << 3;
    /**
     * A rule requiring the audio session id of the audio stream to match that specified.
     * This mixing rule can be added with {@link Builder#addMixRule(int, Object)} where Object
     * parameter is an instance of {@link java.lang.Integer}.
     * @see android.media.AudioTrack.Builder#setSessionId
     */
    public static final int RULE_MATCH_AUDIO_SESSION_ID = 0x1 << 4;

    private final static int RULE_EXCLUSION_MASK = 0x8000;
    /**
     * @hide
     * A rule requiring the usage information of the {@link AudioAttributes} to differ.
     */
    public static final int RULE_EXCLUDE_ATTRIBUTE_USAGE =
            RULE_EXCLUSION_MASK | RULE_MATCH_ATTRIBUTE_USAGE;
    /**
     * @hide
     * A rule requiring the capture preset information of the {@link AudioAttributes} to differ.
     */
    public static final int RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET =
            RULE_EXCLUSION_MASK | RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET;
    /**
     * @hide
     * A rule requiring the UID information to differ.
     */
    public static final int RULE_EXCLUDE_UID =
            RULE_EXCLUSION_MASK | RULE_MATCH_UID;

    /**
     * @hide
     * A rule requiring the userId information to differ.
     */
    public static final int RULE_EXCLUDE_USERID =
            RULE_EXCLUSION_MASK | RULE_MATCH_USERID;

    /**
     * @hide
     * A rule requiring the audio session id information to differ.
     */
    public static final int RULE_EXCLUDE_AUDIO_SESSION_ID =
            RULE_EXCLUSION_MASK | RULE_MATCH_AUDIO_SESSION_ID;

    /** @hide */
    public static final class AudioMixMatchCriterion implements Parcelable {
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        final AudioAttributes mAttr;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        final int mIntProp;
        @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
        final int mRule;

        /** input parameters must be valid */
        @VisibleForTesting
        public AudioMixMatchCriterion(AudioAttributes attributes, int rule) {
            mAttr = attributes;
            mIntProp = Integer.MIN_VALUE;
            mRule = rule;
        }
        /** input parameters must be valid */
        @VisibleForTesting
        public AudioMixMatchCriterion(Integer intProp, int rule) {
            mAttr = null;
            mIntProp = intProp.intValue();
            mRule = rule;
        }

        private AudioMixMatchCriterion(@NonNull Parcel in) {
            Objects.requireNonNull(in);
            mRule = in.readInt();
            final int match_rule = mRule & ~RULE_EXCLUSION_MASK;
            switch (match_rule) {
                case RULE_MATCH_ATTRIBUTE_USAGE:
                case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                    mAttr = AudioAttributes.CREATOR.createFromParcel(in);
                    mIntProp = Integer.MIN_VALUE;
                    break;
                case RULE_MATCH_UID:
                case RULE_MATCH_USERID:
                case RULE_MATCH_AUDIO_SESSION_ID:
                    mIntProp = in.readInt();
                    mAttr = null;
                    break;
                default:
                    // assume there was in int value to read as for now they come in pair
                    in.readInt();
                    throw new IllegalArgumentException(
                            "Illegal rule value " + mRule + " in parcel");
            }
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAttr, mIntProp, mRule);
        }

        @Override
        public boolean equals(Object object) {
            if (object == null || this.getClass() != object.getClass()) {
                return false;
            }
            if (object == this) {
                return true;
            }
            AudioMixMatchCriterion other = (AudioMixMatchCriterion) object;
            return mRule == other.mRule
                    && mIntProp == other.mIntProp
                    && Objects.equals(mAttr, other.mAttr);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(@NonNull Parcel dest, int flags) {
            dest.writeInt(mRule);
            final int match_rule = mRule & ~RULE_EXCLUSION_MASK;
            switch (match_rule) {
                case RULE_MATCH_ATTRIBUTE_USAGE:
                case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                    mAttr.writeToParcel(dest, AudioAttributes.FLATTEN_TAGS/*flags*/);
                    break;
                case RULE_MATCH_UID:
                case RULE_MATCH_USERID:
                case RULE_MATCH_AUDIO_SESSION_ID:
                    dest.writeInt(mIntProp);
                    break;
                default:
                    Log.e("AudioMixMatchCriterion", "Unknown match rule" + match_rule
                            + " when writing to Parcel");
                    dest.writeInt(-1);
            }
        }

        public static final @NonNull Parcelable.Creator<AudioMixMatchCriterion> CREATOR =
                new Parcelable.Creator<>() {
            /**
             * Rebuilds an AudioMixMatchCriterion previously stored with writeToParcel().
             *
             * @param p Parcel object to read the AudioMix from
             * @return a new AudioMixMatchCriterion created from the data in the parcel
             */
            public AudioMixMatchCriterion createFromParcel(Parcel p) {
                return new AudioMixMatchCriterion(p);
            }
            public AudioMixMatchCriterion[] newArray(int size) {
                return new AudioMixMatchCriterion[size];
            }
        };

        public AudioAttributes getAudioAttributes() { return mAttr; }
        public int getIntProp() { return mIntProp; }
        public int getRule() { return mRule; }
    }

    boolean isAffectingUsage(int usage) {
        for (AudioMixMatchCriterion criterion : mCriteria) {
            if ((criterion.mRule & RULE_MATCH_ATTRIBUTE_USAGE) != 0
                    && criterion.mAttr != null
                    && criterion.mAttr.getSystemUsage() == usage) {
                return true;
            }
        }
        return false;
    }

    /**
      * Returns {@code true} if this rule contains a RULE_MATCH_ATTRIBUTE_USAGE criterion for
      * the given usage
      *
      * @hide
      */
    boolean containsMatchAttributeRuleForUsage(int usage) {
        for (AudioMixMatchCriterion criterion : mCriteria) {
            if (criterion.mRule == RULE_MATCH_ATTRIBUTE_USAGE
                    && criterion.mAttr != null
                    && criterion.mAttr.getSystemUsage() == usage) {
                return true;
            }
        }
        return false;
    }

    private final int mTargetMixType;
    int getTargetMixType() {
        return mTargetMixType;
    }

    /**
     * Captures an audio signal from one or more playback streams.
     */
    public static final int MIX_ROLE_PLAYERS = AudioMix.MIX_TYPE_PLAYERS;
    /**
     * Injects an audio signal into the framework to replace a recording source.
     */
    public static final int MIX_ROLE_INJECTOR = AudioMix.MIX_TYPE_RECORDERS;

    /** @hide */
    @IntDef({MIX_ROLE_PLAYERS, MIX_ROLE_INJECTOR})
    @Retention(SOURCE)
    public @interface MixRole {}

    /**
     * Gets target mix role of this mixing rule.
     *
     * <p>The mix role indicates playback streams will be captured or recording source will be
     * injected.
     *
     * @return integer value of {@link #MIX_ROLE_PLAYERS} or {@link #MIX_ROLE_INJECTOR}
     */
    public @MixRole int getTargetMixRole() {
        return mTargetMixType == AudioMix.MIX_TYPE_RECORDERS ? MIX_ROLE_INJECTOR : MIX_ROLE_PLAYERS;
    }

    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private final ArrayList<AudioMixMatchCriterion> mCriteria;
    /** @hide */
    public ArrayList<AudioMixMatchCriterion> getCriteria() { return mCriteria; }
    /** Indicates that this rule is intended to capture media or game playback by a system component
      * with permission CAPTURE_MEDIA_OUTPUT or CAPTURE_AUDIO_OUTPUT.
      */
    //TODO b/177061175: rename to mAllowPrivilegedMediaPlaybackCapture
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean mAllowPrivilegedPlaybackCapture = false;
    @UnsupportedAppUsage(maxTargetSdk = Build.VERSION_CODES.R, trackingBug = 170729553)
    private boolean mVoiceCommunicationCaptureAllowed = false;

    /** @hide */
    public boolean allowPrivilegedMediaPlaybackCapture() {
        return mAllowPrivilegedPlaybackCapture;
    }

    /** @hide */
    public boolean voiceCommunicationCaptureAllowed() {
        return mVoiceCommunicationCaptureAllowed;
    }

    /** @hide */
    public void setVoiceCommunicationCaptureAllowed(boolean allowed) {
        mVoiceCommunicationCaptureAllowed = allowed;
    }

    /** @hide */
    public boolean isForCallRedirection() {
        for (AudioMixMatchCriterion criterion : mCriteria) {
            if (criterion.mAttr != null
                    && criterion.mAttr.isForCallRedirection()
                    && ((criterion.mRule == RULE_MATCH_ATTRIBUTE_USAGE
                        && (criterion.mAttr.getUsage() == AudioAttributes.USAGE_VOICE_COMMUNICATION
                            || criterion.mAttr.getUsage()
                                == AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING))
                    || (criterion.mRule == RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET
                        && (criterion.mAttr.getCapturePreset()
                            == MediaRecorder.AudioSource.VOICE_COMMUNICATION)))) {
                return true;
            }
        }
        return false;
    }

    /** @hide */
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;

        final AudioMixingRule that = (AudioMixingRule) o;
        return (this.mTargetMixType == that.mTargetMixType)
                && Objects.equals(mCriteria, that.mCriteria)
                && (this.mAllowPrivilegedPlaybackCapture == that.mAllowPrivilegedPlaybackCapture)
                && (this.mVoiceCommunicationCaptureAllowed
                    == that.mVoiceCommunicationCaptureAllowed);
    }

    @Override
    public int hashCode() {
        return Objects.hash(
            mTargetMixType,
            mCriteria,
            mAllowPrivilegedPlaybackCapture,
            mVoiceCommunicationCaptureAllowed);
    }

    private static boolean isValidSystemApiRule(int rule) {
        // API rules only expose the RULE_MATCH_* rules
        switch (rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_MATCH_UID:
            case RULE_MATCH_USERID:
            case RULE_MATCH_AUDIO_SESSION_ID:
                return true;
            default:
                return false;
        }
    }
    private static boolean isValidAttributesSystemApiRule(int rule) {
        // API rules only expose the RULE_MATCH_* rules
        switch (rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidRule(int rule) {
        final int match_rule = rule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_MATCH_UID:
            case RULE_MATCH_USERID:
            case RULE_MATCH_AUDIO_SESSION_ID:
                return true;
            default:
                return false;
        }
    }

    private static boolean isPlayerRule(int rule) {
        final int match_rule = rule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_USERID:
                return true;
            default:
                return false;
        }
    }

    private static boolean isRecorderRule(int rule) {
        final int match_rule = rule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    private static boolean isAudioAttributeRule(int match_rule) {
        switch(match_rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    /**
     * Builder class for {@link AudioMixingRule} objects
     */
    public static class Builder {
        private final Set<AudioMixMatchCriterion> mCriteria;
        private int mTargetMixType = AudioMix.MIX_TYPE_INVALID;
        private boolean mAllowPrivilegedMediaPlaybackCapture = false;
        // This value should be set internally according to a permission check
        private boolean mVoiceCommunicationCaptureAllowed = false;

        /**
         * Constructs a new Builder with no rules.
         */
        public Builder() {
            mCriteria = new HashSet<>();
        }

        /**
         * Add a rule for the selection of which streams are mixed together.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet.
         * @param rule {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE} or
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see #excludeRule(AudioAttributes, int)
         */
        public Builder addRule(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (!isValidAttributesSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule, attrToMatch);
        }

        /**
         * Add a rule by exclusion for the selection of which streams are mixed together.
         * <br>For instance the following code
         * <br><pre>
         * AudioAttributes mediaAttr = new AudioAttributes.Builder()
         *         .setUsage(AudioAttributes.USAGE_MEDIA)
         *         .build();
         * AudioMixingRule noMediaRule = new AudioMixingRule.Builder()
         *         .excludeRule(mediaAttr, AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE)
         *         .build();
         * </pre>
         * <br>will create a rule which maps to any usage value, except USAGE_MEDIA.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet.
         * @param rule {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE} or
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see #addRule(AudioAttributes, int)
         */
        public Builder excludeRule(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (!isValidAttributesSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule | RULE_EXCLUSION_MASK, attrToMatch);
        }

        /**
         * Add a rule for the selection of which streams are mixed together.
         * The rule defines what the matching will be made on. It also determines the type of the
         * property to match against.
         * @param rule one of {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET} or
         *     {@link AudioMixingRule#RULE_MATCH_UID} or
         *     {@link AudioMixingRule#RULE_MATCH_USERID} or
         *     {@link AudioMixingRule#RULE_MATCH_AUDIO_SESSION_ID}.
         * @param property see the definition of each rule for the type to use (either an
         *     {@link AudioAttributes} or an {@link java.lang.Integer}).
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see #excludeMixRule(int, Object)
         */
        public Builder addMixRule(int rule, Object property) throws IllegalArgumentException {
            if (!isValidSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule, property);
        }

        /**
         * Add a rule by exclusion for the selection of which streams are mixed together.
         * <br>For instance the following code
         * <br><pre>
         * AudioAttributes mediaAttr = new AudioAttributes.Builder()
         *         .setUsage(AudioAttributes.USAGE_MEDIA)
         *         .build();
         * AudioMixingRule noMediaRule = new AudioMixingRule.Builder()
         *         .addMixRule(AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE, mediaAttr)
         *         .excludeMixRule(AudioMixingRule.RULE_MATCH_UID, new Integer(uidToExclude)
         *         .build();
         * </pre>
         * <br>will create a rule which maps to usage USAGE_MEDIA, but excludes any stream
         * coming from the specified UID.
         * @param rule one of {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET} or
         *     {@link AudioMixingRule#RULE_MATCH_UID} or
         *     {@link AudioMixingRule#RULE_MATCH_USERID} or
         *     {@link AudioMixingRule#RULE_MATCH_AUDIO_SESSION_ID}.
         * @param property see the definition of each rule for the type to use (either an
         *     {@link AudioAttributes} or an {@link java.lang.Integer}).
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder excludeMixRule(int rule, Object property) throws IllegalArgumentException {
            if (!isValidSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule | RULE_EXCLUSION_MASK, property);
        }

        /**
         * Set if the audio of app that opted out of audio playback capture should be captured.
         *
         * Caller of this method with <code>true</code>, MUST abide to the restriction listed in
         * {@link ALLOW_CAPTURE_BY_SYSTEM}, including but not limited to the captured audio
         * can not leave the capturing app, and the quality is limited to 16k mono.
         *
         * The permission {@link CAPTURE_AUDIO_OUTPUT} or {@link CAPTURE_MEDIA_OUTPUT} is needed
         * to ignore the opt-out.
         *
         * Only affects LOOPBACK|RENDER mix.
         *
         * @return the same Builder instance.
         */
        public @NonNull Builder allowPrivilegedPlaybackCapture(boolean allow) {
            mAllowPrivilegedMediaPlaybackCapture = allow;
            return this;
        }

        /**
         * Set if the caller of the rule is able to capture voice communication output.
         * A system app can capture voice communication output only if it is granted with the.
         * CAPTURE_VOICE_COMMUNICATION_OUTPUT permission.
         *
         * Note that this method is for internal use only and should not be called by the app that
         * creates the rule.
         *
         * @return the same Builder instance.
         *
         * @hide
         */
        public @NonNull Builder voiceCommunicationCaptureAllowed(boolean allowed) {
            mVoiceCommunicationCaptureAllowed = allowed;
            return this;
        }

        /**
         * Sets target mix role of the mixing rule.
         *
         * As each mixing rule is intended to be associated with an {@link AudioMix},
         * explicitly setting the role of a mixing rule allows this {@link Builder} to
         * verify validity of the mixing rules to be validated.<br>
         * The mix role allows distinguishing between:
         * <ul>
         * <li>audio framework mixers that will mix / sample-rate convert / reformat the audio
         *     signal of any audio player (e.g. a {@link android.media.MediaPlayer}) that matches
         *     the selection rules defined in the object being built. Use
         *     {@link AudioMixingRule#MIX_ROLE_PLAYERS} for such an {@code AudioMixingRule}</li>
         * <li>audio framework mixers that will be used as the injection point (after sample-rate
         *     conversion and reformatting of the audio signal) into any audio recorder (e.g. a
         *     {@link android.media.AudioRecord}) that matches the selection rule defined in the
         *     object being built. Use {@link AudioMixingRule#MIX_ROLE_INJECTOR} for such an
         *     {@code AudioMixingRule}.</li>
         * </ul>
         * <p>If not specified, the mix role will be decided automatically when
         * {@link #addRule(AudioAttributes, int)} or {@link #addMixRule(int, Object)} be called.
         *
         * @param mixRole integer value of {@link #MIX_ROLE_PLAYERS} or {@link #MIX_ROLE_INJECTOR}
         * @return the same Builder instance.
         */
        public @NonNull Builder setTargetMixRole(@MixRole int mixRole) {
            if (mixRole != MIX_ROLE_PLAYERS && mixRole != MIX_ROLE_INJECTOR) {
                throw new IllegalArgumentException("Illegal argument for mix role");
            }

            if (mCriteria.stream().map(AudioMixMatchCriterion::getRule)
                    .anyMatch(mixRole == MIX_ROLE_PLAYERS
                            ? AudioMixingRule::isRecorderRule : AudioMixingRule::isPlayerRule)) {
                throw new IllegalArgumentException(
                        "Target mix role is not compatible with mix rules.");
            }
            mTargetMixType = mixRole == MIX_ROLE_INJECTOR
                    ? AudioMix.MIX_TYPE_RECORDERS : AudioMix.MIX_TYPE_PLAYERS;
            return this;
        }

        /**
         * Add or exclude a rule for the selection of which streams are mixed together.
         * Does error checking on the parameters.
         * @param rule
         * @param property
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        private Builder checkAddRuleObjInternal(int rule, Object property)
                throws IllegalArgumentException {
            if (property == null) {
                throw new IllegalArgumentException("Illegal null argument for mixing rule");
            }
            if (!isValidRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            final int match_rule = rule & ~RULE_EXCLUSION_MASK;
            if (isAudioAttributeRule(match_rule)) {
                if (!(property instanceof AudioAttributes)) {
                    throw new IllegalArgumentException("Invalid AudioAttributes argument");
                }
                return addRuleInternal(
                        new AudioMixMatchCriterion((AudioAttributes) property, rule));
            } else {
                // implies integer match rule
                if (!(property instanceof Integer)) {
                    throw new IllegalArgumentException("Invalid Integer argument");
                }
                return addRuleInternal(new AudioMixMatchCriterion((Integer) property, rule));
            }
        }

        /**
         * Add or exclude a rule on AudioAttributes or integer property for the selection of which
         * streams are mixed together.
         * No rule-to-parameter type check, all done in {@link #checkAddRuleObjInternal(int, Object)}.
         * Exceptions are thrown only when incompatible rules are added.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet, null if not used.
         * @param intProp an integer property to match or exclude, null if not used.
         * @param rule one of {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET},
         *     {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET},
         *     {@link AudioMixingRule#RULE_MATCH_UID},
         *     {@link AudioMixingRule#RULE_EXCLUDE_UID},
         *     {@link AudioMixingRule#RULE_MATCH_AUDIO_SESSION_ID},
         *     {@link AudioMixingRule#RULE_EXCLUDE_AUDIO_SESSION_ID}
         *     {@link AudioMixingRule#RULE_MATCH_USERID},
         *     {@link AudioMixingRule#RULE_EXCLUDE_USERID}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        private Builder addRuleInternal(AudioMixMatchCriterion criterion)
                throws IllegalArgumentException {
            // If mix type is invalid and added rule is valid only for the players / recorders,
            // adjust the mix type accordingly.
            // Otherwise, if the mix type was already deduced or set explicitly, verify the rule
            // is valid for the mix type.
            final int rule = criterion.mRule;
            if (mTargetMixType == AudioMix.MIX_TYPE_INVALID) {
                if (isPlayerRule(rule)) {
                    mTargetMixType = AudioMix.MIX_TYPE_PLAYERS;
                } else if (isRecorderRule(rule)) {
                    mTargetMixType = AudioMix.MIX_TYPE_RECORDERS;
                }
            } else if ((isPlayerRule(rule) && (mTargetMixType != AudioMix.MIX_TYPE_PLAYERS))
                    || (isRecorderRule(rule)) && (mTargetMixType != AudioMix.MIX_TYPE_RECORDERS))
            {
                throw new IllegalArgumentException("Incompatible rule for mix");
            }
            synchronized (mCriteria) {
                int oppositeRule = rule ^ RULE_EXCLUSION_MASK;
                if (mCriteria.stream().anyMatch(
                        otherCriterion -> otherCriterion.mRule == oppositeRule)) {
                    throw new IllegalArgumentException("AudioMixingRule cannot contain RULE_MATCH_*"
                            + " and RULE_EXCLUDE_* for the same dimension.");
                }
                mCriteria.add(criterion);
            }
            return this;
        }

        /**
         * Combines all of the matching and exclusion rules that have been set and return a new
         * {@link AudioMixingRule} object.
         * @return a new {@link AudioMixingRule} object
         * @throws IllegalArgumentException if the rule is empty.
         */
        public AudioMixingRule build() {
            if (mCriteria.isEmpty()) {
                throw new IllegalArgumentException("Cannot build AudioMixingRule with no rules.");
            }
            return new AudioMixingRule(
                    mTargetMixType == AudioMix.MIX_TYPE_INVALID
                            ? AudioMix.MIX_TYPE_PLAYERS : mTargetMixType,
                    mCriteria, mAllowPrivilegedMediaPlaybackCapture,
                    mVoiceCommunicationCaptureAllowed);
        }
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        // write opt-out respect
        dest.writeBoolean(mAllowPrivilegedPlaybackCapture);
        // write voice communication capture allowed flag
        dest.writeBoolean(mVoiceCommunicationCaptureAllowed);
        // write specified mix type
        dest.writeInt(mTargetMixType);
        // write mix rules
        dest.writeInt(mCriteria.size());
        for (AudioMixingRule.AudioMixMatchCriterion criterion : mCriteria) {
            criterion.writeToParcel(dest, flags);
        }
    }

    public static final @NonNull Parcelable.Creator<AudioMixingRule> CREATOR =
            new Parcelable.Creator<>() {

        @Override
        public AudioMixingRule createFromParcel(Parcel source) {
            AudioMixingRule.Builder ruleBuilder = new AudioMixingRule.Builder();
            // read opt-out respect
            ruleBuilder.allowPrivilegedPlaybackCapture(source.readBoolean());
            // read voice capture allowed flag
            ruleBuilder.voiceCommunicationCaptureAllowed(source.readBoolean());
            // read specified mix type
            ruleBuilder.setTargetMixRole(source.readInt());
            // read mix rules
            int nbRules = source.readInt();
            for (int j = 0; j < nbRules; j++) {
                // read the matching rules
                ruleBuilder.addRuleInternal(
                        AudioMixMatchCriterion.CREATOR.createFromParcel(source));
            }
            return ruleBuilder.build();
        }

        @Override
        public AudioMixingRule[] newArray(int size) {
            return new AudioMixingRule[size];
        }
    };
}
