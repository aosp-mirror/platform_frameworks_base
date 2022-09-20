/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.audiopolicytest;

import static android.media.AudioAttributes.USAGE_MEDIA;
import static android.media.audiopolicy.AudioMixingRule.MIX_ROLE_PLAYERS;
import static android.media.audiopolicy.AudioMixingRule.RULE_EXCLUDE_ATTRIBUTE_USAGE;
import static android.media.audiopolicy.AudioMixingRule.RULE_EXCLUDE_UID;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_UID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;


import android.media.AudioAttributes;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioMixingRule.AudioMixMatchCriterion;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.hamcrest.CustomTypeSafeMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for AudioPolicy.
 *
 * Run with "atest AudioMixingRuleUnitTests".
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
public class AudioMixingRuleUnitTests {
    private static final AudioAttributes USAGE_MEDIA_AUDIO_ATTRIBUTES =
            new AudioAttributes.Builder().setUsage(USAGE_MEDIA).build();
    private static final int TEST_UID = 42;
    private static final int OTHER_UID = 77;

    @Test
    public void testConstructValidRule() {
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                .addMixRule(RULE_MATCH_UID, TEST_UID)
                .build();

        // Based on the rules, the mix type should fall back to MIX_ROLE_PLAYERS,
        // since the rules are valid for both MIX_ROLE_PLAYERS & MIX_ROLE_INJECTOR.
        assertEquals(rule.getTargetMixRole(), MIX_ROLE_PLAYERS);
        assertThat(rule.getCriteria(), containsInAnyOrder(
                isAudioMixMatchUsageCriterion(USAGE_MEDIA),
                isAudioMixMatchUidCriterion(TEST_UID)));
    }

    @Test
    public void testConstructRuleWithConflictingCriteriaFails() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder()
                        .addMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                        .addMixRule(RULE_MATCH_UID, TEST_UID)
                        // Conflicts with previous criterion.
                        .addMixRule(RULE_EXCLUDE_UID, OTHER_UID)
                        .build());
    }

    @Test
    public void testRuleBuilderDedupsCriteria() {
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                .addMixRule(RULE_MATCH_UID, TEST_UID)
                // Identical to previous criterion.
                .addMixRule(RULE_MATCH_UID, TEST_UID)
                // Identical to first criterion.
                .addMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                .build();

        assertThat(rule.getCriteria(), hasSize(2));
        assertThat(rule.getCriteria(), containsInAnyOrder(
                isAudioMixMatchUsageCriterion(USAGE_MEDIA),
                isAudioMixMatchUidCriterion(TEST_UID)));
    }

    @Test
    public void failsWhenAddAttributeRuleCalledWithInvalidType() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder()
                        // Rule match attribute usage requires AudioAttributes, not
                        // just the int enum value of the usage.
                        .addMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA)
                        .build());
    }

    @Test
    public void failsWhenExcludeAttributeRuleCalledWithInvalidType() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder()
                        // Rule match attribute usage requires AudioAttributes, not
                        // just the int enum value of the usage.
                        .excludeMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA)
                        .build());
    }

    @Test
    public void failsWhenAddIntRuleCalledWithInvalidType() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder()
                        // Rule match uid requires Integer not AudioAttributes.
                        .addMixRule(RULE_MATCH_UID, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                        .build());
    }

    @Test
    public void failsWhenExcludeIntRuleCalledWithInvalidType() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder()
                        // Rule match uid requires Integer not AudioAttributes.
                        .excludeMixRule(RULE_MATCH_UID, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                        .build());
    }


    private static Matcher isAudioMixUidCriterion(int uid, boolean exclude) {
        return new CustomTypeSafeMatcher<AudioMixMatchCriterion>("uid mix criterion") {
            @Override
            public boolean matchesSafely(AudioMixMatchCriterion item) {
                int expectedRule = exclude ? RULE_EXCLUDE_UID : RULE_MATCH_UID;
                return item.getRule() == expectedRule && item.getIntProp() == uid;
            }

            @Override
            public void describeMismatchSafely(
                    AudioMixMatchCriterion item, Description mismatchDescription) {
                mismatchDescription.appendText(
                        String.format("is not %s criterion with uid %d",
                                exclude ? "exclude" : "match", uid));
            }
        };
    }

    private static Matcher isAudioMixMatchUidCriterion(int uid) {
        return isAudioMixUidCriterion(uid, /*exclude=*/ false);
    }

    private static Matcher isAudioMixUsageCriterion(int usage, boolean exclude) {
        return new CustomTypeSafeMatcher<AudioMixMatchCriterion>("usage mix criterion") {
            @Override
            public boolean matchesSafely(AudioMixMatchCriterion item) {
                int expectedRule =
                        exclude ? RULE_EXCLUDE_ATTRIBUTE_USAGE : RULE_MATCH_ATTRIBUTE_USAGE;
                AudioAttributes attributes = item.getAudioAttributes();
                return item.getRule() == expectedRule
                        && attributes != null && attributes.getUsage() == usage;
            }

            @Override
            public void describeMismatchSafely(
                    AudioMixMatchCriterion item, Description mismatchDescription) {
                mismatchDescription.appendText(
                        String.format("is not %s criterion with usage %d",
                                exclude ? "exclude" : "match", usage));
            }
        };
    }

    private static Matcher isAudioMixMatchUsageCriterion(int usage) {
        return isAudioMixUsageCriterion(usage, /*exclude=*/ false);
    }


}
