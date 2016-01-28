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

import android.annotation.SystemApi;
import android.media.AudioAttributes;
import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.Objects;


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
public class AudioMixingRule {

    private AudioMixingRule(int mixType, ArrayList<AttributeMatchCriterion> criteria) {
        mCriteria = criteria;
        mTargetMixType = mixType;
    }

    /**
     * A rule requiring the usage information of the {@link AudioAttributes} to match.
     * This mixing rule can be added with {@link Builder#addRule(AudioAttributes, int)} or
     * {@link Builder#addMixRule(int, Object)} where the Object parameter is an instance of
     * {@link AudioAttributes}.
     */
    @SystemApi
    public static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    /**
     * A rule requiring the capture preset information of the {@link AudioAttributes} to match.
     * This mixing rule can be added with {@link Builder#addRule(AudioAttributes, int)} or
     * {@link Builder#addMixRule(int, Object)} where the Object parameter is an instance of
     * {@link AudioAttributes}.
     */
    @SystemApi
    public static final int RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x1 << 1;
    /**
     * A rule requiring the UID of the audio stream to match that specified.
     * This mixing rule can be added with {@link Builder#addMixRule(int, Object)} where the Object
     * parameter is an instance of {@link java.lang.Integer}.
     */
    @SystemApi
    public static final int RULE_MATCH_UID = 0x1 << 2;

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

    static final class AttributeMatchCriterion {
        final AudioAttributes mAttr;
        final Integer mIntProp;
        final int mRule;

        /** input parameters must be valid */
        AttributeMatchCriterion(AudioAttributes attributes, int rule) {
            mAttr = attributes;
            mIntProp = null;
            mRule = rule;
        }
        /** input parameters must be valid */
        AttributeMatchCriterion(Integer intProp, int rule) {
            mAttr = null;
            mIntProp = intProp;
            mRule = rule;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAttr, mIntProp, mRule);
        }

        void writeToParcel(Parcel dest) {
            dest.writeInt(mRule);
            final int match_rule = mRule & ~RULE_EXCLUSION_MASK;
            switch (match_rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
                dest.writeInt(mAttr.getUsage());
                break;
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                dest.writeInt(mAttr.getCapturePreset());
                break;
            case RULE_MATCH_UID:
                dest.writeInt(mIntProp.intValue());
                break;
            default:
                Log.e("AttributeMatchCriterion", "Unknown match rule" + match_rule
                        + " when writing to Parcel");
                dest.writeInt(-1);
            }
        }
    }

    private final int mTargetMixType;
    int getTargetMixType() { return mTargetMixType; }
    private final ArrayList<AttributeMatchCriterion> mCriteria;
    ArrayList<AttributeMatchCriterion> getCriteria() { return mCriteria; }

    @Override
    public int hashCode() {
        return Objects.hash(mTargetMixType, mCriteria);
    }

    private static boolean isValidSystemApiRule(int rule) {
        switch(rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_MATCH_UID:
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidAttributesSystemApiRule(int rule) {
        switch(rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidRule(int rule) {
        switch(rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_EXCLUDE_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_MATCH_UID:
            case RULE_EXCLUDE_UID:
                return true;
            default:
                return false;
        }
    }

    private static boolean isPlayerRule(int rule) {
        final int match_rule = rule & ~RULE_EXCLUSION_MASK;
        switch (match_rule) {
        case RULE_MATCH_ATTRIBUTE_USAGE:
        case RULE_MATCH_UID:
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
    @SystemApi
    public static class Builder {
        private ArrayList<AttributeMatchCriterion> mCriteria;
        private int mTargetMixType = AudioMix.MIX_TYPE_INVALID;

        /**
         * Constructs a new Builder with no rules.
         */
        @SystemApi
        public Builder() {
            mCriteria = new ArrayList<AttributeMatchCriterion>();
        }

        /**
         * Add a rule for the selection of which streams are mixed together.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet.
         * @param rule {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE} or
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see {@link #excludeRule(AudioAttributes, int)}
         */
        @SystemApi
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
         * @see {@link #addRule(AudioAttributes, int)}
         */
        @SystemApi
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
         *     {@link AudioMixingRule#RULE_MATCH_UID}.
         * @param property see the definition of each rule for the type to use (either an
         *     {@link AudioAttributes} or an {@link java.lang.Integer}).
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         * @see {@link #excludeMixRule(int, Object)}
         */
        @SystemApi
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
         *     {@link AudioMixingRule#RULE_MATCH_UID}.
         * @param property see the definition of each rule for the type to use (either an
         *     {@link AudioAttributes} or an {@link java.lang.Integer}).
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        @SystemApi
        public Builder excludeMixRule(int rule, Object property) throws IllegalArgumentException {
            if (!isValidSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return checkAddRuleObjInternal(rule | RULE_EXCLUSION_MASK, property);
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
                throw new IllegalArgumentException("Illegal null Object argument");
            }
            if (!isValidRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            } else {
                // as rules are added to the Builder, we verify they are consistent with the type
                // of mix being built. When adding the first rule, the mix type is MIX_TYPE_INVALID.
                if (mTargetMixType == AudioMix.MIX_TYPE_INVALID) {
                    if (isPlayerRule(rule)) {
                        mTargetMixType = AudioMix.MIX_TYPE_PLAYERS;
                    } else {
                        mTargetMixType = AudioMix.MIX_TYPE_RECORDERS;
                    }
                } else if (((mTargetMixType == AudioMix.MIX_TYPE_PLAYERS) && !isPlayerRule(rule))
                        || ((mTargetMixType == AudioMix.MIX_TYPE_RECORDERS) && isPlayerRule(rule)))
                {
                    throw new IllegalArgumentException("Incompatible rule for mix");
                }
            }
            final int match_rule = rule & ~RULE_EXCLUSION_MASK;
            if (isAudioAttributeRule(match_rule)) {
                if (!(property instanceof AudioAttributes)) {
                    throw new IllegalArgumentException("Invalid AudioAttributes argument");
                }
                return addRuleInternal((AudioAttributes) property, null, rule);
            } else {
                // implies integer match rule
                if (!(property instanceof Integer)) {
                    throw new IllegalArgumentException("Invalid Integer argument");
                }
                return addRuleInternal(null, (Integer) property, rule);
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
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET} or
         *     {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET},
         *     {@link AudioMixingRule#RULE_MATCH_UID}, {@link AudioMixingRule#RULE_EXCLUDE_UID}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        private Builder addRuleInternal(AudioAttributes attrToMatch, Integer intProp, int rule)
                throws IllegalArgumentException {
            synchronized (mCriteria) {
                Iterator<AttributeMatchCriterion> crIterator = mCriteria.iterator();
                final int match_rule = rule & ~RULE_EXCLUSION_MASK;
                while (crIterator.hasNext()) {
                    final AttributeMatchCriterion criterion = crIterator.next();
                    switch (match_rule) {
                        case RULE_MATCH_ATTRIBUTE_USAGE:
                            // "usage"-based rule
                            if (criterion.mAttr.getUsage() == attrToMatch.getUsage()) {
                                if (criterion.mRule == rule) {
                                    // rule already exists, we're done
                                    return this;
                                } else {
                                    // criterion already exists with a another rule,
                                    // it is incompatible
                                    throw new IllegalArgumentException("Contradictory rule exists"
                                            + " for " + attrToMatch);
                                }
                            }
                            break;
                        case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                            // "capture preset"-base rule
                            if (criterion.mAttr.getCapturePreset() == attrToMatch.getCapturePreset()) {
                                if (criterion.mRule == rule) {
                                    // rule already exists, we're done
                                    return this;
                                } else {
                                    // criterion already exists with a another rule,
                                    // it is incompatible
                                    throw new IllegalArgumentException("Contradictory rule exists"
                                            + " for " + attrToMatch);
                                }
                            }
                            break;
                        case RULE_MATCH_UID:
                            // "usage"-based rule
                            if (criterion.mIntProp.intValue() == intProp.intValue()) {
                                if (criterion.mRule == rule) {
                                    // rule already exists, we're done
                                    return this;
                                } else {
                                    // criterion already exists with a another rule,
                                    // it is incompatible
                                    throw new IllegalArgumentException("Contradictory rule exists"
                                            + " for UID " + intProp);
                                }
                            }
                            break;
                    }
                }
                // rule didn't exist, add it
                mCriteria.add(new AttributeMatchCriterion(attrToMatch, rule));
            }
            return this;
        }

        Builder addRuleFromParcel(Parcel in) throws IllegalArgumentException {
            final int rule = in.readInt();
            final int match_rule = rule & ~RULE_EXCLUSION_MASK;
            AudioAttributes attr = null;
            Integer intProp = null;
            switch (match_rule) {
                case RULE_MATCH_ATTRIBUTE_USAGE:
                    int usage = in.readInt();
                    attr = new AudioAttributes.Builder()
                            .setUsage(usage).build();
                    break;
                case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
                    int preset = in.readInt();
                    attr = new AudioAttributes.Builder()
                            .setInternalCapturePreset(preset).build();
                    break;
                case RULE_MATCH_UID:
                    intProp = new Integer(in.readInt());
                    break;
                default:
                    // assume there was in int value to read as for now they come in pair
                    in.readInt();
                    throw new IllegalArgumentException("Illegal rule value " + rule + " in parcel");
            }
            return addRuleInternal(attr, intProp, rule);
        }

        /**
         * Combines all of the matching and exclusion rules that have been set and return a new
         * {@link AudioMixingRule} object.
         * @return a new {@link AudioMixingRule} object
         */
        public AudioMixingRule build() {
            return new AudioMixingRule(mTargetMixType, mCriteria);
        }
    }
}
