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
     */
    @SystemApi
    public static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    /**
     * A rule requiring the capture preset information of the {@link AudioAttributes} to match.
     */
    @SystemApi
    public static final int RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET = 0x1 << 1;

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

    static final class AttributeMatchCriterion {
        AudioAttributes mAttr;
        int mRule;

        /** input parameters must be valid */
        AttributeMatchCriterion(AudioAttributes attributes, int rule) {
            mAttr = attributes;
            mRule = rule;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mAttr, mRule);
        }

        void writeToParcel(Parcel dest) {
            dest.writeInt(mRule);
            if ((mRule == RULE_MATCH_ATTRIBUTE_USAGE) || (mRule == RULE_EXCLUDE_ATTRIBUTE_USAGE)) {
                dest.writeInt(mAttr.getUsage());
            } else {
                // capture preset rule
                dest.writeInt(mAttr.getCapturePreset());
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
                return true;
            default:
                return false;
        }
    }

    private static boolean isValidIntRule(int rule) {
        switch(rule) {
            case RULE_MATCH_ATTRIBUTE_USAGE:
            case RULE_EXCLUDE_ATTRIBUTE_USAGE:
            case RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET:
            case RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET:
                return true;
            default:
                return false;
        }
    }

    private static boolean isPlayerRule(int rule) {
        return ((rule == RULE_MATCH_ATTRIBUTE_USAGE)
                || (rule == RULE_EXCLUDE_ATTRIBUTE_USAGE));
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
         */
        @SystemApi
        public Builder addRule(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (!isValidSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return addRuleInt(attrToMatch, rule);
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
         */
        @SystemApi
        public Builder excludeRule(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (!isValidSystemApiRule(rule)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
            }
            return addRuleInt(attrToMatch, rule | RULE_EXCLUSION_MASK);
        }

        /**
         * Add or exclude a rule for the selection of which streams are mixed together.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet.
         * @param rule one of {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET} or
         *     {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        Builder addRuleInt(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (attrToMatch == null) {
                throw new IllegalArgumentException("Illegal null AudioAttributes argument");
            }
            if (!isValidIntRule(rule)) {
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
            synchronized (mCriteria) {
                Iterator<AttributeMatchCriterion> crIterator = mCriteria.iterator();
                while (crIterator.hasNext()) {
                    final AttributeMatchCriterion criterion = crIterator.next();
                    if ((rule == RULE_MATCH_ATTRIBUTE_USAGE)
                            || (rule == RULE_EXCLUDE_ATTRIBUTE_USAGE)) {
                        // "usage"-based rule
                        if (criterion.mAttr.getUsage() == attrToMatch.getUsage()) {
                            if (criterion.mRule == rule) {
                                // rule already exists, we're done
                                return this;
                            } else {
                                // criterion already exists with a another rule, it is incompatible
                                throw new IllegalArgumentException("Contradictory rule exists for "
                                        + attrToMatch);
                            }
                        }
                    } else if ((rule == RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET)
                            || (rule == RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET)) {
                        // "capture preset"-base rule
                        if (criterion.mAttr.getCapturePreset() == attrToMatch.getCapturePreset()) {
                            if (criterion.mRule == rule) {
                             // rule already exists, we're done
                                return this;
                            } else {
                                // criterion already exists with a another rule, it is incompatible
                                throw new IllegalArgumentException("Contradictory rule exists for "
                                        + attrToMatch);
                            }
                        }
                    }
                }
                // rule didn't exist, add it
                mCriteria.add(new AttributeMatchCriterion(attrToMatch, rule));
            }
            return this;
        }

        Builder addRuleFromParcel(Parcel in) throws IllegalArgumentException {
            int rule = in.readInt();
            AudioAttributes attr;
            if ((rule == RULE_MATCH_ATTRIBUTE_USAGE) || (rule == RULE_EXCLUDE_ATTRIBUTE_USAGE)) {
                int usage = in.readInt();
                attr = new AudioAttributes.Builder()
                        .setUsage(usage).build();
            } else if ((rule == RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET)
                    || (rule == RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET)) {
                int preset = in.readInt();
                attr = new AudioAttributes.Builder()
                        .setInternalCapturePreset(preset).build();
            } else {
                in.readInt(); // assume there was in int value to read as for now they come in pair
                throw new IllegalArgumentException("Illegal rule value " + rule + " in parcel");
            }
            return addRuleInt(attr, rule);
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
