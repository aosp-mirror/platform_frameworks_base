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

package com.android.server.soundtrigger_middleware;

import android.os.HidlMemoryUtil;

import java.util.ArrayList;

/**
 * Utilities for maintaining data compatibility between different minor versions of soundtrigger@2.x
 * HAL.
 * Note that some of these conversion utilities are destructive, i.e. mutate their input (for the
 * sake of simplifying code and reducing copies).
 */
class Hw2CompatUtil {
    static android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel convertSoundModel_2_1_to_2_0(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.SoundModel soundModel) {
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.SoundModel model_2_0 = soundModel.header;
        // Note: this mutates the input!
        model_2_0.data = HidlMemoryUtil.hidlMemoryToByteList(soundModel.data);
        return model_2_0;
    }

    static android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent convertRecognitionEvent_2_0_to_2_1(
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionEvent event) {
        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent event_2_1 =
                new android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.RecognitionEvent();
        event_2_1.header = event;
        event_2_1.data = HidlMemoryUtil.byteListToHidlMemory(event_2_1.header.data,
                "SoundTrigger RecognitionEvent");
        // Note: this mutates the input!
        event_2_1.header.data = new ArrayList<>();
        return event_2_1;
    }

    static android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent convertPhraseRecognitionEvent_2_0_to_2_1(
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.PhraseRecognitionEvent event) {
        android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent
                event_2_1 =
                new android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback.PhraseRecognitionEvent();
        event_2_1.common = convertRecognitionEvent_2_0_to_2_1(event.common);
        event_2_1.phraseExtras = event.phraseExtras;
        return event_2_1;
    }

    static android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel convertPhraseSoundModel_2_1_to_2_0(
            android.hardware.soundtrigger.V2_1.ISoundTriggerHw.PhraseSoundModel soundModel) {
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel model_2_0 =
                new android.hardware.soundtrigger.V2_0.ISoundTriggerHw.PhraseSoundModel();
        model_2_0.common = convertSoundModel_2_1_to_2_0(soundModel.common);
        model_2_0.phrases = soundModel.phrases;
        return model_2_0;
    }

    static android.hardware.soundtrigger.V2_1.ISoundTriggerHw.RecognitionConfig convertRecognitionConfig_2_3_to_2_1(
            android.hardware.soundtrigger.V2_3.RecognitionConfig config) {
        return config.base;
    }

    static android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig convertRecognitionConfig_2_3_to_2_0(
            android.hardware.soundtrigger.V2_3.RecognitionConfig config) {
        android.hardware.soundtrigger.V2_0.ISoundTriggerHw.RecognitionConfig config_2_0 =
                config.base.header;
        // Note: this mutates the input!
        config_2_0.data = HidlMemoryUtil.hidlMemoryToByteList(config.base.data);
        return config_2_0;
    }

    static android.hardware.soundtrigger.V2_3.Properties convertProperties_2_0_to_2_3(
            android.hardware.soundtrigger.V2_0.ISoundTriggerHw.Properties properties) {
        android.hardware.soundtrigger.V2_3.Properties properties_2_3 =
                new android.hardware.soundtrigger.V2_3.Properties();
        properties_2_3.base = properties;
        return properties_2_3;
    }
}
