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
import static android.media.MediaRecorder.AudioSource.VOICE_RECOGNITION;
import static android.media.audiopolicy.AudioMixingRule.MIX_ROLE_INJECTOR;
import static android.media.audiopolicy.AudioMixingRule.MIX_ROLE_PLAYERS;
import static android.media.audiopolicy.AudioMixingRule.RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET;
import static android.media.audiopolicy.AudioMixingRule.RULE_EXCLUDE_ATTRIBUTE_USAGE;
import static android.media.audiopolicy.AudioMixingRule.RULE_EXCLUDE_AUDIO_SESSION_ID;
import static android.media.audiopolicy.AudioMixingRule.RULE_EXCLUDE_UID;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_AUDIO_SESSION_ID;
import static android.media.audiopolicy.AudioMixingRule.RULE_MATCH_UID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsIterableContainingInAnyOrder.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;


import android.media.AudioAttributes;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioMixingRule.AudioMixMatchCriterion;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.testing.EqualsTester;

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
    private static final AudioAttributes CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES =
            new AudioAttributes.Builder().setCapturePreset(VOICE_RECOGNITION).build();
    private static final int TEST_UID = 42;
    private static final int OTHER_UID = 77;
    private static final int TEST_SESSION_ID = 1234;

    @Test
    public void testConstructValidRule() {
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                .addMixRule(RULE_MATCH_UID, TEST_UID)
                .excludeMixRule(RULE_MATCH_AUDIO_SESSION_ID, TEST_SESSION_ID)
                .build();

        // Based on the rules, the mix type should fall back to MIX_ROLE_PLAYERS,
        // since the rules are valid for both MIX_ROLE_PLAYERS & MIX_ROLE_INJECTOR.
        assertEquals(rule.getTargetMixRole(), MIX_ROLE_PLAYERS);
        assertThat(rule.getCriteria(), containsInAnyOrder(
                isAudioMixMatchUsageCriterion(USAGE_MEDIA),
                isAudioMixMatchUidCriterion(TEST_UID),
                isAudioMixExcludeSessionCriterion(TEST_SESSION_ID)));
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

    @Test
    public void injectorMixTypeDeductionWithGenericRuleSucceeds() {
        AudioMixingRule rule = new AudioMixingRule.Builder()
                // UID rule can be used both with MIX_ROLE_PLAYERS and MIX_ROLE_INJECTOR.
                .addMixRule(RULE_MATCH_UID, TEST_UID)
                // Capture preset rule is only valid for injector, MIX_ROLE_INJECTOR should
                // be deduced.
                .addMixRule(RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                        CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES)
                .build();

        assertEquals(rule.getTargetMixRole(), MIX_ROLE_INJECTOR);
        assertThat(rule.getCriteria(), containsInAnyOrder(
                isAudioMixMatchUidCriterion(TEST_UID),
                isAudioMixMatchCapturePresetCriterion(VOICE_RECOGNITION)));
    }

    @Test
    public void settingTheMixTypeToIncompatibleInjectorMixFails() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder()
                        .addMixRule(RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                                CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES)
                        // Capture preset cannot be defined for MIX_ROLE_PLAYERS.
                        .setTargetMixRole(MIX_ROLE_PLAYERS)
                        .build());
    }

    @Test
    public void addingPlayersOnlyRuleWithInjectorsOnlyRuleFails() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder()
                        // MIX_ROLE_PLAYERS only rule.
                        .addMixRule(RULE_MATCH_ATTRIBUTE_USAGE, USAGE_MEDIA_AUDIO_ATTRIBUTES)
                        // MIX ROLE_INJECTOR only rule.
                        .addMixRule(RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                                CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES)
                        .build());
    }

    @Test
    public void sessionIdRuleCompatibleWithPlayersMix() {
        int sessionId = 42;
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addMixRule(RULE_MATCH_AUDIO_SESSION_ID, sessionId)
                .setTargetMixRole(MIX_ROLE_PLAYERS)
                .build();

        assertEquals(rule.getTargetMixRole(), MIX_ROLE_PLAYERS);
        assertThat(rule.getCriteria(), containsInAnyOrder(isAudioMixSessionCriterion(sessionId)));
    }

    @Test
    public void sessionIdRuleCompatibleWithInjectorMix() {
        AudioMixingRule rule = new AudioMixingRule.Builder()
                .addMixRule(RULE_MATCH_AUDIO_SESSION_ID, TEST_SESSION_ID)
                .setTargetMixRole(MIX_ROLE_INJECTOR)
                .build();

        assertEquals(rule.getTargetMixRole(), MIX_ROLE_INJECTOR);
        assertThat(rule.getCriteria(),
                containsInAnyOrder(isAudioMixSessionCriterion(TEST_SESSION_ID)));
    }

    @Test
    public void audioMixingRuleWithNoRulesFails() {
        assertThrows(IllegalArgumentException.class,
                () -> new AudioMixingRule.Builder().build());
    }

    @Test
    public void audioMixMatchCriterion_equals_isCorrect() {
        AudioMixMatchCriterion criterionUsage = new AudioMixMatchCriterion(
                USAGE_MEDIA_AUDIO_ATTRIBUTES, RULE_MATCH_ATTRIBUTE_USAGE);
        AudioMixMatchCriterion criterionExcludeUsage = new AudioMixMatchCriterion(
                USAGE_MEDIA_AUDIO_ATTRIBUTES, RULE_EXCLUDE_ATTRIBUTE_USAGE);
        AudioMixMatchCriterion criterionCapturePreset = new AudioMixMatchCriterion(
                CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES,
                RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET);
        AudioMixMatchCriterion criterionExcludeCapturePreset = new AudioMixMatchCriterion(
                CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES,
                RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET);
        AudioMixMatchCriterion criterionUid = new AudioMixMatchCriterion(TEST_UID, RULE_MATCH_UID);
        AudioMixMatchCriterion criterionExcludeUid = new AudioMixMatchCriterion(TEST_UID,
                RULE_EXCLUDE_UID);
        AudioMixMatchCriterion criterionSessionId = new AudioMixMatchCriterion(TEST_SESSION_ID,
                RULE_MATCH_UID);
        AudioMixMatchCriterion criterionExcludeSessionId = new AudioMixMatchCriterion(
                TEST_SESSION_ID, RULE_EXCLUDE_UID);

        final EqualsTester equalsTester = new EqualsTester();
        equalsTester.addEqualityGroup(criterionUsage, writeToAndFromParcel(criterionUsage));
        equalsTester.addEqualityGroup(criterionExcludeUsage,
                writeToAndFromParcel(criterionExcludeUsage));
        equalsTester.addEqualityGroup(criterionCapturePreset,
                writeToAndFromParcel(criterionCapturePreset));
        equalsTester.addEqualityGroup(criterionExcludeCapturePreset,
                writeToAndFromParcel(criterionExcludeCapturePreset));
        equalsTester.addEqualityGroup(criterionUid, writeToAndFromParcel(criterionUid));
        equalsTester.addEqualityGroup(criterionExcludeUid,
                writeToAndFromParcel(criterionExcludeUid));
        equalsTester.addEqualityGroup(criterionSessionId, writeToAndFromParcel(criterionSessionId));
        equalsTester.addEqualityGroup(criterionExcludeSessionId,
                writeToAndFromParcel(criterionExcludeSessionId));

        equalsTester.testEquals();
    }

    @Test
    public void audioMixingRule_equals_isCorrect() {
        final EqualsTester equalsTester = new EqualsTester();

        AudioMixingRule mixRule1 = new AudioMixingRule.Builder().addMixRule(
                RULE_MATCH_AUDIO_SESSION_ID, TEST_SESSION_ID).excludeMixRule(RULE_MATCH_UID,
                TEST_UID).build();
        AudioMixingRule mixRule2 = new AudioMixingRule.Builder().addMixRule(
                RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES).setTargetMixRole(
                MIX_ROLE_INJECTOR).allowPrivilegedPlaybackCapture(true).build();
        AudioMixingRule mixRule3 = new AudioMixingRule.Builder().addMixRule(
                RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES).setTargetMixRole(
                MIX_ROLE_INJECTOR).allowPrivilegedPlaybackCapture(false).build();
        AudioMixingRule mixRule4 = new AudioMixingRule.Builder().addMixRule(
                RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET,
                CAPTURE_PRESET_VOICE_RECOGNITION_AUDIO_ATTRIBUTES).setTargetMixRole(
                MIX_ROLE_INJECTOR).voiceCommunicationCaptureAllowed(true).build();

        equalsTester.addEqualityGroup(mixRule1, writeToAndFromParcel(mixRule1));
        equalsTester.addEqualityGroup(mixRule2, writeToAndFromParcel(mixRule2));
        equalsTester.addEqualityGroup(mixRule3, writeToAndFromParcel(mixRule3));
        equalsTester.addEqualityGroup(mixRule4, writeToAndFromParcel(mixRule4));

        equalsTester.testEquals();
    }

    private static AudioMixMatchCriterion writeToAndFromParcel(AudioMixMatchCriterion criterion) {
        Parcel parcel = Parcel.obtain();
        try {
            criterion.writeToParcel(parcel, /*parcelableFlags=*/0);
            parcel.setDataPosition(0);
            return AudioMixMatchCriterion.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
    }

    private static AudioMixingRule writeToAndFromParcel(AudioMixingRule audioMixingRule) {
        Parcel parcel = Parcel.obtain();
        try {
            audioMixingRule.writeToParcel(parcel, /*parcelableFlags=*/0);
            parcel.setDataPosition(0);
            return AudioMixingRule.CREATOR.createFromParcel(parcel);
        } finally {
            parcel.recycle();
        }
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

    private static Matcher isAudioMixCapturePresetCriterion(int audioSource, boolean exclude) {
        return new CustomTypeSafeMatcher<AudioMixMatchCriterion>("uid mix criterion") {
            @Override
            public boolean matchesSafely(AudioMixMatchCriterion item) {
                int expectedRule = exclude
                        ? RULE_EXCLUDE_ATTRIBUTE_CAPTURE_PRESET
                        : RULE_MATCH_ATTRIBUTE_CAPTURE_PRESET;
                AudioAttributes attributes = item.getAudioAttributes();
                return item.getRule() == expectedRule
                        && attributes != null && attributes.getCapturePreset() == audioSource;
            }

            @Override
            public void describeMismatchSafely(
                    AudioMixMatchCriterion item, Description mismatchDescription) {
                mismatchDescription.appendText(
                        String.format("is not %s criterion with capture preset %d",
                                exclude ? "exclude" : "match", audioSource));
            }
        };
    }

    private static Matcher isAudioMixMatchCapturePresetCriterion(int audioSource) {
        return isAudioMixCapturePresetCriterion(audioSource, /*exclude=*/ false);
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

    private static Matcher isAudioMixSessionCriterion(int sessionId, boolean exclude) {
        return new CustomTypeSafeMatcher<AudioMixMatchCriterion>("sessionId mix criterion") {
            @Override
            public boolean matchesSafely(AudioMixMatchCriterion item) {
                int excludeRule =
                        exclude ? RULE_EXCLUDE_AUDIO_SESSION_ID : RULE_MATCH_AUDIO_SESSION_ID;
                return item.getRule() == excludeRule && item.getIntProp() == sessionId;
            }

            @Override
            public void describeMismatchSafely(
                    AudioMixMatchCriterion item, Description mismatchDescription) {
                mismatchDescription.appendText(
                        String.format("is not %s criterion with session id %d",
                        exclude ? "exclude" : "match", sessionId));
            }
        };
    }

    private static Matcher isAudioMixSessionCriterion(int sessionId) {
        return isAudioMixSessionCriterion(sessionId, /*exclude=*/ false);
    }

    private static Matcher isAudioMixExcludeSessionCriterion(int sessionId) {
        return isAudioMixSessionCriterion(sessionId, /*exclude=*/ true);
    }

}
