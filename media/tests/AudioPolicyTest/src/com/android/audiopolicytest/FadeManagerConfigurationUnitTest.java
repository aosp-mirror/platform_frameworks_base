/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.media.flags.Flags.FLAG_ENABLE_FADE_MANAGER_CONFIGURATION;

import static org.junit.Assert.assertThrows;

import android.media.AudioAttributes;
import android.media.AudioPlaybackConfiguration;
import android.media.FadeManagerConfiguration;
import android.media.VolumeShaper;
import android.os.Parcel;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Presubmit
@RunWith(AndroidJUnit4.class)
@RequiresFlagsEnabled(FLAG_ENABLE_FADE_MANAGER_CONFIGURATION)
public final class FadeManagerConfigurationUnitTest {
    private static final long DEFAULT_FADE_OUT_DURATION_MS = 2_000;
    private static final long DEFAULT_FADE_IN_DURATION_MS = 1_000;
    private static final long TEST_FADE_OUT_DURATION_MS = 1_500;
    private static final long TEST_FADE_IN_DURATION_MS = 750;
    private static final int TEST_INVALID_USAGE = -10;
    private static final int TEST_INVALID_CONTENT_TYPE = 100;
    private static final int TEST_INVALID_FADE_STATE = 100;
    private static final long TEST_INVALID_DURATION = -10;
    private static final int TEST_UID_1 = 1010001;
    private static final int TEST_UID_2 = 1000;
    private static final int TEST_PARCEL_FLAGS = 0;
    private static final AudioAttributes TEST_MEDIA_AUDIO_ATTRIBUTE =
            createAudioAttributesForUsage(AudioAttributes.USAGE_MEDIA);
    private static final AudioAttributes TEST_GAME_AUDIO_ATTRIBUTE =
            createAudioAttributesForUsage(AudioAttributes.USAGE_GAME);
    private static final AudioAttributes TEST_NAVIGATION_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(
                    AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
    private static final AudioAttributes TEST_ASSISTANT_AUDIO_ATTRIBUTE =
            new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build();
    private static final List<Integer> TEST_FADEABLE_USAGES = Arrays.asList(
            AudioAttributes.USAGE_MEDIA,
            AudioAttributes.USAGE_GAME
    );
    private static final List<Integer> TEST_UNFADEABLE_CONTENT_TYPES = Arrays.asList(
            AudioAttributes.CONTENT_TYPE_SPEECH
    );

    private static final List<Integer> TEST_UNFADEABLE_PLAYER_TYPES = Arrays.asList(
            AudioPlaybackConfiguration.PLAYER_TYPE_AAUDIO,
            AudioPlaybackConfiguration.PLAYER_TYPE_JAM_SOUNDPOOL
    );
    private static final VolumeShaper.Configuration TEST_DEFAULT_FADE_OUT_VOLUME_SHAPER_CONFIG =
            new VolumeShaper.Configuration.Builder()
                    .setId(FadeManagerConfiguration.VOLUME_SHAPER_SYSTEM_FADE_ID)
                    .setCurve(/* times= */new float[]{0.f, 0.25f, 1.0f},
                            /* volumes= */new float[]{1.f, 0.65f, 0.0f})
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(DEFAULT_FADE_OUT_DURATION_MS)
                    .build();
    private static final VolumeShaper.Configuration TEST_DEFAULT_FADE_IN_VOLUME_SHAPER_CONFIG =
            new VolumeShaper.Configuration.Builder()
                    .setId(FadeManagerConfiguration.VOLUME_SHAPER_SYSTEM_FADE_ID)
                    .setCurve(/* times= */new float[]{0.f, 0.50f, 1.0f},
                            /* volumes= */new float[]{0.f, 0.30f, 1.0f})
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(DEFAULT_FADE_IN_DURATION_MS)
                    .build();
    private static final VolumeShaper.Configuration TEST_FADE_OUT_VOLUME_SHAPER_CONFIG =
            new VolumeShaper.Configuration.Builder()
                    .setId(FadeManagerConfiguration.VOLUME_SHAPER_SYSTEM_FADE_ID)
                    .setCurve(/* times= */new float[]{0.f, 0.25f, 1.0f},
                            /* volumes= */new float[]{1.f, 0.65f, 0.0f})
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(TEST_FADE_OUT_DURATION_MS)
                    .build();
    private static final VolumeShaper.Configuration TEST_FADE_IN_VOLUME_SHAPER_CONFIG =
            new VolumeShaper.Configuration.Builder()
                    .setId(FadeManagerConfiguration.VOLUME_SHAPER_SYSTEM_FADE_ID)
                    .setCurve(/* times= */new float[]{0.f, 0.50f, 1.0f},
                            /* volumes= */new float[]{0.f, 0.30f, 1.0f})
                    .setOptionFlags(VolumeShaper.Configuration.OPTION_FLAG_CLOCK_TIME)
                    .setDuration(TEST_FADE_IN_DURATION_MS)
                    .build();

    private FadeManagerConfiguration mFmc;

    @Rule
    public final Expect expect = Expect.create();

    @Before
    public void setUp() {
        mFmc = new FadeManagerConfiguration.Builder().build();
    }


    @Test
    public void build() {
        expect.withMessage("Fade state for default builder")
                .that(mFmc.getFadeState())
                .isEqualTo(FadeManagerConfiguration.FADE_STATE_ENABLED_DEFAULT);
        expect.withMessage("Fadeable usages for default builder")
                .that(mFmc.getFadeableUsages())
                .containsExactlyElementsIn(TEST_FADEABLE_USAGES);
        expect.withMessage("Unfadeable content types usages for default builder")
                .that(mFmc.getUnfadeableContentTypes())
                .containsExactlyElementsIn(TEST_UNFADEABLE_CONTENT_TYPES);
        expect.withMessage("Unfadeable player types for default builder")
                .that(mFmc.getUnfadeablePlayerTypes())
                .containsExactlyElementsIn(TEST_UNFADEABLE_PLAYER_TYPES);
        expect.withMessage("Unfadeable uids for default builder")
                .that(mFmc.getUnfadeableUids()).isEmpty();
        expect.withMessage("Unfadeable audio attributes for default builder")
                .that(mFmc.getUnfadeableAudioAttributes()).isEmpty();
        expect.withMessage("Fade out volume shaper config for media usage")
                .that(mFmc.getFadeOutVolumeShaperConfigForUsage(AudioAttributes.USAGE_MEDIA))
                .isEqualTo(TEST_DEFAULT_FADE_OUT_VOLUME_SHAPER_CONFIG);
        expect.withMessage("Fade out duration for game usage")
                .that(mFmc.getFadeOutDurationForUsage(AudioAttributes.USAGE_GAME))
                .isEqualTo(DEFAULT_FADE_OUT_DURATION_MS);
        expect.withMessage("Fade in volume shaper config for media uasge")
                .that(mFmc.getFadeInVolumeShaperConfigForUsage(AudioAttributes.USAGE_MEDIA))
                .isEqualTo(TEST_DEFAULT_FADE_IN_VOLUME_SHAPER_CONFIG);
        expect.withMessage("Fade in duration for game audio usage")
                .that(mFmc.getFadeInDurationForUsage(AudioAttributes.USAGE_GAME))
                .isEqualTo(DEFAULT_FADE_IN_DURATION_MS);
    }

    @Test
    public void build_withFadeDurations_succeeds() {
        FadeManagerConfiguration fmc = new FadeManagerConfiguration
                .Builder(TEST_FADE_OUT_DURATION_MS, TEST_FADE_IN_DURATION_MS).build();

        expect.withMessage("Fade state for builder with duration").that(fmc.getFadeState())
                .isEqualTo(FadeManagerConfiguration.FADE_STATE_ENABLED_DEFAULT);
        expect.withMessage("Fadeable usages for builder with duration")
                .that(fmc.getFadeableUsages())
                .containsExactlyElementsIn(TEST_FADEABLE_USAGES);
        expect.withMessage("Unfadeable content types usages for builder with duration")
                .that(fmc.getUnfadeableContentTypes())
                .containsExactlyElementsIn(TEST_UNFADEABLE_CONTENT_TYPES);
        expect.withMessage("Unfadeable player types for builder with duration")
                .that(fmc.getUnfadeablePlayerTypes())
                .containsExactlyElementsIn(TEST_UNFADEABLE_PLAYER_TYPES);
        expect.withMessage("Unfadeable uids for builder with duration")
                .that(fmc.getUnfadeableUids()).isEmpty();
        expect.withMessage("Unfadeable audio attributes for builder with duration")
                .that(fmc.getUnfadeableAudioAttributes()).isEmpty();
        expect.withMessage("Fade out volume shaper config for media usage")
                .that(fmc.getFadeOutVolumeShaperConfigForUsage(AudioAttributes.USAGE_MEDIA))
                .isEqualTo(TEST_FADE_OUT_VOLUME_SHAPER_CONFIG);
        expect.withMessage("Fade out duration for game usage")
                .that(fmc.getFadeOutDurationForUsage(AudioAttributes.USAGE_GAME))
                .isEqualTo(TEST_FADE_OUT_DURATION_MS);
        expect.withMessage("Fade in volume shaper config for media audio attributes")
                .that(fmc.getFadeInVolumeShaperConfigForUsage(AudioAttributes.USAGE_MEDIA))
                .isEqualTo(TEST_FADE_IN_VOLUME_SHAPER_CONFIG);
        expect.withMessage("Fade in duration for game audio attributes")
                .that(fmc.getFadeInDurationForUsage(AudioAttributes.USAGE_GAME))
                .isEqualTo(TEST_FADE_IN_DURATION_MS);

    }

    @Test
    public void build_withFadeManagerConfiguration_succeeds() {
        FadeManagerConfiguration fmcObj = new FadeManagerConfiguration
                .Builder(TEST_FADE_OUT_DURATION_MS, TEST_FADE_IN_DURATION_MS).build();

        FadeManagerConfiguration fmc = new FadeManagerConfiguration
                .Builder(fmcObj).build();

        expect.withMessage("Fade state for copy builder").that(fmc.getFadeState())
                .isEqualTo(fmcObj.getFadeState());
        expect.withMessage("Fadeable usages for copy builder")
                .that(fmc.getFadeableUsages())
                .containsExactlyElementsIn(fmcObj.getFadeableUsages());
        expect.withMessage("Unfadeable content types usages for copy builder")
                .that(fmc.getUnfadeableContentTypes())
                .containsExactlyElementsIn(fmcObj.getUnfadeableContentTypes());
        expect.withMessage("Unfadeable player types for copy builder")
                .that(fmc.getUnfadeablePlayerTypes())
                .containsExactlyElementsIn(fmcObj.getUnfadeablePlayerTypes());
        expect.withMessage("Unfadeable uids for copy builder")
                .that(fmc.getUnfadeableUids()).isEqualTo(fmcObj.getUnfadeableUids());
        expect.withMessage("Unfadeable audio attributes for copy builder")
                .that(fmc.getUnfadeableAudioAttributes())
                .isEqualTo(fmcObj.getUnfadeableAudioAttributes());
        expect.withMessage("Fade out volume shaper config for media usage")
                .that(fmc.getFadeOutVolumeShaperConfigForUsage(AudioAttributes.USAGE_MEDIA))
                .isEqualTo(fmcObj.getFadeOutVolumeShaperConfigForUsage(
                        AudioAttributes.USAGE_MEDIA));
        expect.withMessage("Fade out volume shaper config for game usage")
                .that(fmc.getFadeOutVolumeShaperConfigForUsage(AudioAttributes.USAGE_GAME))
                .isEqualTo(fmcObj.getFadeOutVolumeShaperConfigForUsage(
                        AudioAttributes.USAGE_GAME));
        expect.withMessage("Fade in volume shaper config for media usage")
                .that(fmc.getFadeInVolumeShaperConfigForUsage(AudioAttributes.USAGE_MEDIA))
                .isEqualTo(fmcObj.getFadeInVolumeShaperConfigForUsage(
                        AudioAttributes.USAGE_MEDIA));
        expect.withMessage("Fade in volume shaper config for game usage")
                .that(fmc.getFadeInVolumeShaperConfigForUsage(AudioAttributes.USAGE_GAME))
                .isEqualTo(fmcObj.getFadeInVolumeShaperConfigForUsage(
                        AudioAttributes.USAGE_GAME));
        expect.withMessage("Fade out volume shaper config for media audio attributes")
                .that(fmc.getFadeOutVolumeShaperConfigForAudioAttributes(
                        TEST_MEDIA_AUDIO_ATTRIBUTE))
                .isEqualTo(fmcObj.getFadeOutVolumeShaperConfigForAudioAttributes(
                        TEST_MEDIA_AUDIO_ATTRIBUTE));
        expect.withMessage("Fade out duration for game audio attributes")
                .that(fmc.getFadeOutDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE))
                .isEqualTo(fmcObj.getFadeOutDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE));
        expect.withMessage("Fade in volume shaper config for media audio attributes")
                .that(fmc.getFadeInVolumeShaperConfigForAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE))
                .isEqualTo(fmcObj.getFadeInVolumeShaperConfigForAudioAttributes(
                        TEST_MEDIA_AUDIO_ATTRIBUTE));
        expect.withMessage("Fade in duration for game audio attributes")
                .that(fmc.getFadeInDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE))
                .isEqualTo(fmcObj.getFadeInDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE));
    }

    @Test
    public void testSetFadeState_toDisable() {
        final int fadeState = FadeManagerConfiguration.FADE_STATE_DISABLED;
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setFadeState(fadeState).build();

        expect.withMessage("Fade state when disabled").that(fmc.getFadeState())
                .isEqualTo(fadeState);
    }

    @Test
    public void testSetFadeState_toEnableAuto() {
        final int fadeStateAuto = FadeManagerConfiguration.FADE_STATE_ENABLED_AUTO;
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setFadeState(fadeStateAuto).build();

        expect.withMessage("Fade state when enabled for audio").that(fmc.getFadeState())
                .isEqualTo(fadeStateAuto);
    }

    @Test
    public void testSetFadeState_toInvalid_fails() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new FadeManagerConfiguration.Builder()
                        .setFadeState(TEST_INVALID_FADE_STATE).build()
        );

        expect.withMessage("Invalid fade state exception").that(thrown)
                .hasMessageThat().contains("Unknown fade state");
    }

    @Test
    public void testSetFadeVolShaperConfig() {
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setFadeOutVolumeShaperConfigForAudioAttributes(TEST_ASSISTANT_AUDIO_ATTRIBUTE,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(TEST_ASSISTANT_AUDIO_ATTRIBUTE,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG).build();

        expect.withMessage("Fade out volume shaper config set for assistant audio attributes")
                .that(fmc.getFadeOutVolumeShaperConfigForAudioAttributes(
                        TEST_ASSISTANT_AUDIO_ATTRIBUTE))
                .isEqualTo(TEST_FADE_OUT_VOLUME_SHAPER_CONFIG);
        expect.withMessage("Fade in volume shaper config set for assistant audio attributes")
                .that(fmc.getFadeInVolumeShaperConfigForAudioAttributes(
                        TEST_ASSISTANT_AUDIO_ATTRIBUTE))
                .isEqualTo(TEST_FADE_IN_VOLUME_SHAPER_CONFIG);
    }

    @Test
    public void testSetFadeOutVolShaperConfig_withNullAudioAttributes_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder()
                        .setFadeOutVolumeShaperConfigForAudioAttributes(/* audioAttributes= */ null,
                                TEST_FADE_OUT_VOLUME_SHAPER_CONFIG).build()
        );

        expect.withMessage("Null audio attributes for fade out exception")
                .that(thrown).hasMessageThat().contains("cannot be null");
    }

    @Test
    public void testSetFadeVolShaperConfig_withNullVolumeShaper_getsNull() {
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder(mFmc)
                .setFadeOutVolumeShaperConfigForAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE,
                        /* VolumeShaper.Configuration= */ null)
                .setFadeInVolumeShaperConfigForAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE,
                        /* VolumeShaper.Configuration= */ null)
                .clearFadeableUsage(AudioAttributes.USAGE_MEDIA).build();

        expect.withMessage("Fade out volume shaper config set with null value")
                .that(fmc.getFadeOutVolumeShaperConfigForAudioAttributes(
                        TEST_MEDIA_AUDIO_ATTRIBUTE)).isNull();
    }

    @Test
    public void testSetFadeInVolShaperConfig_withNullAudioAttributes_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder()
                        .setFadeInVolumeShaperConfigForAudioAttributes(/* audioAttributes= */ null,
                                TEST_FADE_IN_VOLUME_SHAPER_CONFIG).build()
        );

        expect.withMessage("Null audio attributes for fade in exception")
                .that(thrown).hasMessageThat().contains("cannot be null");
    }

    @Test
    public void testSetFadeDuration() {
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setFadeOutDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE,
                        TEST_FADE_OUT_DURATION_MS)
                .setFadeInDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE,
                        TEST_FADE_IN_DURATION_MS).build();

        expect.withMessage("Fade out duration set for audio attributes")
                .that(fmc.getFadeOutDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE))
                .isEqualTo(TEST_FADE_OUT_DURATION_MS);
        expect.withMessage("Fade in duration set for audio attributes")
                .that(fmc.getFadeInDurationForAudioAttributes(TEST_GAME_AUDIO_ATTRIBUTE))
                .isEqualTo(TEST_FADE_IN_DURATION_MS);
    }

    @Test
    public void testSetFadeOutDuration_withNullAudioAttributes_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder().setFadeOutDurationForAudioAttributes(
                        /* audioAttributes= */ null, TEST_FADE_OUT_DURATION_MS).build()
        );

        expect.withMessage("Null audio attributes for fade out duration exception").that(thrown)
                .hasMessageThat().contains("cannot be null");
    }

    @Test
    public void testSetFadeOutDuration_withInvalidDuration_fails() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new FadeManagerConfiguration.Builder().setFadeOutDurationForAudioAttributes(
                        TEST_NAVIGATION_AUDIO_ATTRIBUTE, TEST_INVALID_DURATION).build()
        );

        expect.withMessage("Invalid duration for fade out exception").that(thrown)
                .hasMessageThat().contains("not positive");
    }

    @Test
    public void testSetFadeInDuration_withNullAudioAttributes_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder().setFadeInDurationForAudioAttributes(
                        /* audioAttributes= */ null, TEST_FADE_IN_DURATION_MS).build()
        );

        expect.withMessage("Null audio attributes for fade in duration exception").that(thrown)
                .hasMessageThat().contains("cannot be null");
    }

    @Test
    public void testSetFadeInDuration_withInvalidDuration_fails() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new FadeManagerConfiguration.Builder().setFadeInDurationForAudioAttributes(
                        TEST_NAVIGATION_AUDIO_ATTRIBUTE, TEST_INVALID_DURATION).build()
        );

        expect.withMessage("Invalid duration for fade in exception").that(thrown)
                .hasMessageThat().contains("not positive");
    }

    @Test
    public void testSetFadeableUsages() {
        final List<Integer> fadeableUsages = List.of(
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_ASSISTANT
                );
        AudioAttributes aaForVoiceComm = createAudioAttributesForUsage(
                AudioAttributes.USAGE_VOICE_COMMUNICATION);
        AudioAttributes aaForAlarm = createAudioAttributesForUsage(AudioAttributes.USAGE_ALARM);
        AudioAttributes aaForAssistant = createAudioAttributesForUsage(
                AudioAttributes.USAGE_ASSISTANT);


        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setFadeableUsages(fadeableUsages)
                .setFadeOutVolumeShaperConfigForAudioAttributes(aaForVoiceComm,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(aaForVoiceComm,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG)
                .setFadeOutVolumeShaperConfigForAudioAttributes(aaForAlarm,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(aaForAlarm,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG)
                .setFadeOutVolumeShaperConfigForAudioAttributes(aaForAssistant,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(aaForAssistant,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG).build();

        expect.withMessage("Fadeable usages")
                .that(fmc.getFadeableUsages()).isEqualTo(fadeableUsages);
    }

    @Test
    public void testSetFadeableUsages_withInvalidUsage_fails() {
        final List<Integer> fadeableUsages = List.of(
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                TEST_INVALID_USAGE,
                AudioAttributes.USAGE_ANNOUNCEMENT
        );

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new FadeManagerConfiguration.Builder().setFadeableUsages(fadeableUsages).build()
        );

        expect.withMessage("Fadeable usages set to invalid usage").that(thrown).hasMessageThat()
                .contains("Invalid usage");
    }

    @Test
    public void testSetFadeableUsages_withNullUsages_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder().setFadeableUsages(/* usages= */ null)
                        .build()
        );

        expect.withMessage("Fadeable usages set to null list").that(thrown).hasMessageThat()
                .contains("cannot be null");
    }

    @Test
    public void testSetFadeableUsages_withEmptyListClears_addsNewUsage() {
        final List<Integer> fadeableUsages = List.of(
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_ALARM,
                AudioAttributes.USAGE_ASSISTANT
        );
        FadeManagerConfiguration.Builder fmcBuilder = new FadeManagerConfiguration.Builder()
                .setFadeableUsages(fadeableUsages);

        fmcBuilder.setFadeableUsages(List.of());

        FadeManagerConfiguration fmc = fmcBuilder
                .addFadeableUsage(AudioAttributes.USAGE_MEDIA)
                .setFadeOutVolumeShaperConfigForAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(TEST_MEDIA_AUDIO_ATTRIBUTE,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG).build();
        expect.withMessage("Fadeable usages set to empty list")
                .that(fmc.getFadeableUsages()).isEqualTo(List.of(AudioAttributes.USAGE_MEDIA));
    }


    @Test
    public void testAddFadeableUsage() {
        final int usageToAdd = AudioAttributes.USAGE_ASSISTANT;
        AudioAttributes aaToAdd = createAudioAttributesForUsage(usageToAdd);
        List<Integer> updatedUsages = new ArrayList<>(mFmc.getFadeableUsages());
        updatedUsages.add(usageToAdd);

        FadeManagerConfiguration updatedFmc = new FadeManagerConfiguration
                .Builder(mFmc).addFadeableUsage(usageToAdd)
                .setFadeOutVolumeShaperConfigForAudioAttributes(aaToAdd,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(aaToAdd,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG)
                .build();

        expect.withMessage("Fadeable usages").that(updatedFmc.getFadeableUsages())
                .containsExactlyElementsIn(updatedUsages);
    }

    @Test
    public void testAddFadeableUsage_withoutSetFadeableUsages() {
        final int newUsage = AudioAttributes.USAGE_ASSISTANT;
        AudioAttributes aaToAdd = createAudioAttributesForUsage(newUsage);

        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .addFadeableUsage(newUsage)
                .setFadeOutVolumeShaperConfigForAudioAttributes(aaToAdd,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(aaToAdd,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG)
                .build();

        expect.withMessage("Fadeable usages").that(fmc.getFadeableUsages())
                .containsExactlyElementsIn(List.of(newUsage));
    }

    @Test
    public void testAddFadeableUsage_withInvalidUsage_fails() {
        List<Integer> setUsages = Arrays.asList(
                AudioAttributes.USAGE_VOICE_COMMUNICATION,
                AudioAttributes.USAGE_ASSISTANT
        );
        AudioAttributes aaForVoiceComm = createAudioAttributesForUsage(
                AudioAttributes.USAGE_VOICE_COMMUNICATION);
        AudioAttributes aaForAssistant = createAudioAttributesForUsage(
                AudioAttributes.USAGE_ASSISTANT);
        FadeManagerConfiguration.Builder fmcBuilder = new FadeManagerConfiguration.Builder()
                .setFadeableUsages(setUsages)
                .setFadeOutVolumeShaperConfigForAudioAttributes(aaForVoiceComm,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(aaForVoiceComm,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG)
                .setFadeOutVolumeShaperConfigForAudioAttributes(aaForAssistant,
                        TEST_FADE_OUT_VOLUME_SHAPER_CONFIG)
                .setFadeInVolumeShaperConfigForAudioAttributes(aaForAssistant,
                        TEST_FADE_IN_VOLUME_SHAPER_CONFIG);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                fmcBuilder.addFadeableUsage(TEST_INVALID_USAGE)
        );

        FadeManagerConfiguration fmc = fmcBuilder.build();
        expect.withMessage("Fadeable usages ").that(thrown).hasMessageThat()
                .contains("Invalid usage");
        expect.withMessage("Fadeable usages").that(fmc.getFadeableUsages())
                .containsExactlyElementsIn(setUsages);
    }

    @Test
    public void testClearFadeableUsage() {
        final int usageToClear = AudioAttributes.USAGE_MEDIA;
        List<Integer> updatedUsages = new ArrayList<>(mFmc.getFadeableUsages());
        updatedUsages.remove((Integer) usageToClear);

        FadeManagerConfiguration updatedFmc = new FadeManagerConfiguration
                .Builder(mFmc).clearFadeableUsage(usageToClear).build();

        expect.withMessage("Clear fadeable usage").that(updatedFmc.getFadeableUsages())
                .containsExactlyElementsIn(updatedUsages);
    }

    @Test
    public void testClearFadeableUsage_withInvalidUsage_fails() {
        FadeManagerConfiguration.Builder fmcBuilder = new FadeManagerConfiguration.Builder(mFmc);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                fmcBuilder.clearFadeableUsage(TEST_INVALID_USAGE)
        );

        FadeManagerConfiguration fmc = fmcBuilder.build();
        expect.withMessage("Clear invalid usage").that(thrown).hasMessageThat()
                .contains("Invalid usage");
        expect.withMessage("Fadeable usages").that(fmc.getFadeableUsages())
                .containsExactlyElementsIn(mFmc.getFadeableUsages());
    }

    @Test
    public void testSetUnfadeableContentTypes() {
        final List<Integer> unfadeableContentTypes = List.of(
                AudioAttributes.CONTENT_TYPE_MOVIE,
                AudioAttributes.CONTENT_TYPE_SONIFICATION
        );
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setUnfadeableContentTypes(unfadeableContentTypes).build();

        expect.withMessage("Unfadeable content types set")
                .that(fmc.getUnfadeableContentTypes()).isEqualTo(unfadeableContentTypes);
    }

    @Test
    public void testSetUnfadeableContentTypes_withInvalidContentType_fails() {
        final List<Integer> invalidUnfadeableContentTypes = List.of(
                AudioAttributes.CONTENT_TYPE_MOVIE,
                TEST_INVALID_CONTENT_TYPE,
                AudioAttributes.CONTENT_TYPE_SONIFICATION
        );

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                new FadeManagerConfiguration.Builder()
                        .setUnfadeableContentTypes(invalidUnfadeableContentTypes).build()
        );

        expect.withMessage("Invalid content type set exception").that(thrown).hasMessageThat()
                .contains("Invalid content type");
    }

    @Test
    public void testSetUnfadeableContentTypes_withNullContentType_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder()
                        .setUnfadeableContentTypes(/* contentType= */ null).build()
        );

        expect.withMessage("Null content type set exception").that(thrown).hasMessageThat()
                .contains("cannot be null");
    }

    @Test
    public void testSetUnfadeableContentTypes_withEmptyList_clearsExistingList() {
        final List<Integer> unfadeableContentTypes = List.of(
                AudioAttributes.CONTENT_TYPE_MOVIE,
                AudioAttributes.CONTENT_TYPE_SONIFICATION
        );
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setUnfadeableContentTypes(unfadeableContentTypes).build();

        FadeManagerConfiguration fmcWithEmptyLsit = new FadeManagerConfiguration.Builder(fmc)
                .setUnfadeableContentTypes(List.of()).build();

        expect.withMessage("Unfadeable content types for empty list")
                .that(fmcWithEmptyLsit.getUnfadeableContentTypes()).isEmpty();
    }

    @Test
    public void testAddUnfadeableContentType() {
        final int contentTypeToAdd = AudioAttributes.CONTENT_TYPE_MOVIE;
        List<Integer> upatdedContentTypes = new ArrayList<>(mFmc.getUnfadeableContentTypes());
        upatdedContentTypes.add(contentTypeToAdd);

        FadeManagerConfiguration updatedFmc = new FadeManagerConfiguration
                .Builder(mFmc).addUnfadeableContentType(contentTypeToAdd).build();

        expect.withMessage("Unfadeable content types").that(updatedFmc.getUnfadeableContentTypes())
                .containsExactlyElementsIn(upatdedContentTypes);
    }

    @Test
    public void testAddUnfadeableContentTypes_withoutSetUnfadeableContentTypes() {
        final int newContentType = AudioAttributes.CONTENT_TYPE_MOVIE;

        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .addUnfadeableContentType(newContentType).build();

        expect.withMessage("Unfadeable content types").that(fmc.getUnfadeableContentTypes())
                .containsExactlyElementsIn(List.of(newContentType));
    }

    @Test
    public void testAddunfadeableContentTypes_withInvalidContentType_fails() {
        final List<Integer> unfadeableContentTypes = List.of(
                AudioAttributes.CONTENT_TYPE_MOVIE,
                AudioAttributes.CONTENT_TYPE_SONIFICATION
        );
        FadeManagerConfiguration.Builder fmcBuilder = new FadeManagerConfiguration.Builder()
                .setUnfadeableContentTypes(unfadeableContentTypes);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                fmcBuilder.addUnfadeableContentType(TEST_INVALID_CONTENT_TYPE).build()
        );

        expect.withMessage("Invalid content types exception").that(thrown).hasMessageThat()
                .contains("Invalid content type");
    }

    @Test
    public void testClearUnfadeableContentType() {
        List<Integer> unfadeableContentTypes = new ArrayList<>(Arrays.asList(
                AudioAttributes.CONTENT_TYPE_MOVIE,
                AudioAttributes.CONTENT_TYPE_SONIFICATION
        ));
        final int contentTypeToClear = AudioAttributes.CONTENT_TYPE_MOVIE;

        FadeManagerConfiguration updatedFmc = new FadeManagerConfiguration.Builder()
                .setUnfadeableContentTypes(unfadeableContentTypes)
                .clearUnfadeableContentType(contentTypeToClear).build();

        unfadeableContentTypes.remove((Integer) contentTypeToClear);
        expect.withMessage("Unfadeable content types").that(updatedFmc.getUnfadeableContentTypes())
                .containsExactlyElementsIn(unfadeableContentTypes);
    }

    @Test
    public void testClearUnfadeableContentType_withInvalidContentType_fails() {
        FadeManagerConfiguration.Builder fmcBuilder = new FadeManagerConfiguration.Builder(mFmc);

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, () ->
                fmcBuilder.clearUnfadeableContentType(TEST_INVALID_CONTENT_TYPE).build()
        );

        expect.withMessage("Invalid content type exception").that(thrown).hasMessageThat()
                .contains("Invalid content type");
    }

    @Test
    public void testSetUnfadeableUids() {
        final List<Integer> unfadeableUids = List.of(
                TEST_UID_1,
                TEST_UID_2
        );
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setUnfadeableUids(unfadeableUids).build();

        expect.withMessage("Unfadeable uids set")
                .that(fmc.getUnfadeableUids()).isEqualTo(unfadeableUids);
    }

    @Test
    public void testSetUnfadeableUids_withNullUids_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder()
                        .setUnfadeableUids(/* uids= */ null).build()
        );

        expect.withMessage("Null unfadeable uids").that(thrown).hasMessageThat()
                .contains("cannot be null");
    }

    @Test
    public void testAddUnfadeableUid() {
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .addUnfadeableUid(TEST_UID_1).build();

        expect.withMessage("Unfadeable uids")
                .that(fmc.getUnfadeableUids()).isEqualTo(List.of(TEST_UID_1));
    }

    @Test
    public void testClearUnfadebaleUid() {
        final List<Integer> unfadeableUids = List.of(
                TEST_UID_1,
                TEST_UID_2
        );
        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setUnfadeableUids(unfadeableUids).build();

        FadeManagerConfiguration updatedFmc = new FadeManagerConfiguration.Builder(fmc)
                .clearUnfadeableUid(TEST_UID_1).build();

        expect.withMessage("Unfadeable uids").that(updatedFmc.getUnfadeableUids())
                .isEqualTo(List.of(TEST_UID_2));
    }

    @Test
    public void testSetUnfadeableAudioAttributes() {
        final List<AudioAttributes> unfadeableAttrs = List.of(
                TEST_ASSISTANT_AUDIO_ATTRIBUTE,
                TEST_NAVIGATION_AUDIO_ATTRIBUTE
        );

        FadeManagerConfiguration fmc = new FadeManagerConfiguration.Builder()
                .setUnfadeableAudioAttributes(unfadeableAttrs).build();

        expect.withMessage("Unfadeable audio attributes")
                .that(fmc.getUnfadeableAudioAttributes()).isEqualTo(unfadeableAttrs);
    }

    @Test
    public void testSetUnfadeableAudioAttributes_withNullAttributes_fails() {
        NullPointerException thrown = assertThrows(NullPointerException.class, () ->
                new FadeManagerConfiguration.Builder()
                        .setUnfadeableAudioAttributes(/* attrs= */ null).build()
        );

        expect.withMessage("Null audio attributes exception").that(thrown).hasMessageThat()
                .contains("cannot be null");
    }

    @Test
    public void testWriteToParcel_andCreateFromParcel() {
        Parcel parcel = Parcel.obtain();

        mFmc.writeToParcel(parcel, TEST_PARCEL_FLAGS);
        parcel.setDataPosition(/* position= */ 0);
        expect.withMessage("Fade manager configuration write to and create from parcel")
                .that(mFmc)
                .isEqualTo(FadeManagerConfiguration.CREATOR.createFromParcel(parcel));
    }

    private static AudioAttributes createAudioAttributesForUsage(int usage) {
        if (AudioAttributes.isSystemUsage(usage)) {
            return new AudioAttributes.Builder().setSystemUsage(usage).build();
        }
        return new AudioAttributes.Builder().setUsage(usage).build();
    }
}
