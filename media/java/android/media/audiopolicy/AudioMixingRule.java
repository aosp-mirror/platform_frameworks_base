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

import android.media.AudioAttributes;

import java.util.ArrayList;
import java.util.Iterator;


/**
 * @hide CANDIDATE FOR PUBLIC API
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
public class AudioMixingRule {

    private AudioMixingRule(ArrayList<AttributeMatchCriterion> criteria) {
        mCriteria = criteria;
    }

    /**
     * A rule requiring the usage information of the {@link AudioAttributes} to match
     */
    public static final int RULE_MATCH_ATTRIBUTE_USAGE = 0x1;
    /**
     * A rule requiring the usage information of the {@link AudioAttributes} to differ
     */
    public static final int RULE_EXCLUDE_ATTRIBUTE_USAGE = 0x1 << 1;

    static final class AttributeMatchCriterion {
        AudioAttributes mAttr;
        int mRule;

        AttributeMatchCriterion(AudioAttributes attributes, int rule) {
            mAttr = attributes;
            mRule = rule;
        }
    }

    private ArrayList<AttributeMatchCriterion> mCriteria;
    ArrayList<AttributeMatchCriterion> getCriteria() { return mCriteria; }

    /**
     * Builder class for {@link AudioMixingRule} objects
     *
     */
    public static class Builder {
        private ArrayList<AttributeMatchCriterion> mCriteria;

        /**
         * Constructs a new Builder with no rules.
         */
        public Builder() {
            mCriteria = new ArrayList<AttributeMatchCriterion>();
        }

        /**
         * Add a rule for the selection of which streams are mixed together.
         * @param attrToMatch a non-null AudioAttributes instance for which a contradictory
         *     rule hasn't been set yet.
         * @param rule one of {@link AudioMixingRule#RULE_EXCLUDE_ATTRIBUTE_USAGE},
         *     {@link AudioMixingRule#RULE_MATCH_ATTRIBUTE_USAGE}.
         * @return the same Builder instance.
         * @throws IllegalArgumentException
         */
        public Builder addRule(AudioAttributes attrToMatch, int rule)
                throws IllegalArgumentException {
            if (attrToMatch == null) {
                throw new IllegalArgumentException("Illegal null AudioAttributes argument");
            }
            if ((rule != RULE_MATCH_ATTRIBUTE_USAGE) && (rule != RULE_EXCLUDE_ATTRIBUTE_USAGE)) {
                throw new IllegalArgumentException("Illegal rule value " + rule);
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
                    }
                }
                // rule didn't exist, add it
                mCriteria.add(new AttributeMatchCriterion(attrToMatch, rule));
            }
            return this;
        }

        /**
         * Combines all of the matching and exclusion rules that have been set and return a new
         * {@link AudioMixingRule} object.
         * @return a new {@link AudioMixingRule} object
         */
        public AudioMixingRule build() {
            return new AudioMixingRule(mCriteria);
        }
    }
}
