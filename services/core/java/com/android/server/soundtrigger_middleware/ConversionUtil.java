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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.audio.common.V2_0.Uuid;
import android.hardware.soundtrigger.V2_1.ISoundTriggerHwCallback;
import android.hardware.soundtrigger.V2_3.ISoundTriggerHw;
import android.hardware.soundtrigger.V2_3.Properties;
import android.media.audio.common.AudioConfig;
import android.media.audio.common.AudioOffloadInfo;
import android.media.soundtrigger_middleware.AudioCapabilities;
import android.media.soundtrigger_middleware.ConfidenceLevel;
import android.media.soundtrigger_middleware.ModelParameter;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.Phrase;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseRecognitionExtra;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionMode;
import android.media.soundtrigger_middleware.RecognitionStatus;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundModelType;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.os.HidlMemoryUtil;

import java.util.regex.Matcher;

/**
 * Utilities for type conversion between SoundTrigger HAL types and SoundTriggerMiddleware service
 * types.
 *
 * @hide
 */
class ConversionUtil {
    static @NonNull
    SoundTriggerModuleProperties hidl2aidlProperties(
            @NonNull ISoundTriggerHw.Properties hidlProperties) {
        SoundTriggerModuleProperties aidlProperties = new SoundTriggerModuleProperties();
        aidlProperties.implementor = hidlProperties.implementor;
        aidlProperties.description = hidlProperties.description;
        aidlProperties.version = hidlProperties.version;
        aidlProperties.uuid = hidl2aidlUuid(hidlProperties.uuid);
        aidlProperties.maxSoundModels = hidlProperties.maxSoundModels;
        aidlProperties.maxKeyPhrases = hidlProperties.maxKeyPhrases;
        aidlProperties.maxUsers = hidlProperties.maxUsers;
        aidlProperties.recognitionModes =
                hidl2aidlRecognitionModes(hidlProperties.recognitionModes);
        aidlProperties.captureTransition = hidlProperties.captureTransition;
        aidlProperties.maxBufferMs = hidlProperties.maxBufferMs;
        aidlProperties.concurrentCapture = hidlProperties.concurrentCapture;
        aidlProperties.triggerInEvent = hidlProperties.triggerInEvent;
        aidlProperties.powerConsumptionMw = hidlProperties.powerConsumptionMw;
        return aidlProperties;
    }

    static @NonNull SoundTriggerModuleProperties hidl2aidlProperties(
            @NonNull Properties hidlProperties) {
        SoundTriggerModuleProperties aidlProperties = hidl2aidlProperties(hidlProperties.base);
        aidlProperties.supportedModelArch = hidlProperties.supportedModelArch;
        aidlProperties.audioCapabilities =
                hidl2aidlAudioCapabilities(hidlProperties.audioCapabilities);
        return aidlProperties;
    }

    static @NonNull
    String hidl2aidlUuid(@NonNull Uuid hidlUuid) {
        if (hidlUuid.node == null || hidlUuid.node.length != 6) {
            throw new IllegalArgumentException("UUID.node must be of length 6.");
        }
        return String.format(UuidUtil.FORMAT,
                hidlUuid.timeLow,
                hidlUuid.timeMid,
                hidlUuid.versionAndTimeHigh,
                hidlUuid.variantAndClockSeqHigh,
                hidlUuid.node[0],
                hidlUuid.node[1],
                hidlUuid.node[2],
                hidlUuid.node[3],
                hidlUuid.node[4],
                hidlUuid.node[5]);
    }

    static @NonNull
    Uuid aidl2hidlUuid(@NonNull String aidlUuid) {
        Matcher matcher = UuidUtil.PATTERN.matcher(aidlUuid);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Illegal format for UUID: " + aidlUuid);
        }
        Uuid hidlUuid = new Uuid();
        hidlUuid.timeLow = Integer.parseUnsignedInt(matcher.group(1), 16);
        hidlUuid.timeMid = (short) Integer.parseUnsignedInt(matcher.group(2), 16);
        hidlUuid.versionAndTimeHigh = (short) Integer.parseUnsignedInt(matcher.group(3), 16);
        hidlUuid.variantAndClockSeqHigh = (short) Integer.parseUnsignedInt(matcher.group(4), 16);
        hidlUuid.node = new byte[]{(byte) Integer.parseUnsignedInt(matcher.group(5), 16),
                (byte) Integer.parseUnsignedInt(matcher.group(6), 16),
                (byte) Integer.parseUnsignedInt(matcher.group(7), 16),
                (byte) Integer.parseUnsignedInt(matcher.group(8), 16),
                (byte) Integer.parseUnsignedInt(matcher.group(9), 16),
                (byte) Integer.parseUnsignedInt(matcher.group(10), 16)};
        return hidlUuid;
    }

    static int aidl2hidlSoundModelType(int aidlType) {
        switch (aidlType) {
            case SoundModelType.GENERIC:
                return android.hardware.soundtrigger.V2_0.SoundModelType.GENERIC;
            case SoundModelType.KEYPHRASE:
                return android.hardware.soundtrigger.V2_0.SoundModelType.KEYPHRASE;
            default:
                throw new IllegalArgumentException("Unknown sound model type: " + aidlType);
        }
    }

    static int hidl2aidlSoundModelType(int hidlType) {
        switch (hidlType) {
            case android.hardware.soundtrigger.V2_0.SoundModelType.GENERIC:
                return SoundModelType.GENERIC;
            case android.hardware.soundtrigger.V2_0.SoundModelType.KEYPHRASE:
                return SoundModelType.KEYPHRASE;
            default:
                throw new IllegalArgumentException("Unknown sound model type: " + hidlType);
        }
    }

    static @NonNull
    ISoundTriggerHw.Phrase aidl2hidlPhrase(@NonNull Phrase aidlPhrase) {
        ISoundTriggerHw.Phrase hidlPhrase = new ISoundTriggerHw.Phrase();
        hidlPhrase.id = aidlPhrase.id;
        hidlPhrase.recognitionModes = aidl2hidlRecognitionModes(aidlPhrase.recognitionModes);
        for (int aidlUser : aidlPhrase.users) {
            hidlPhrase.users.add(aidlUser);
        }
        hidlPhrase.locale = aidlPhrase.locale;
        hidlPhrase.text = aidlPhrase.text;
        return hidlPhrase;
    }

    static int aidl2hidlRecognitionModes(int aidlModes) {
        int hidlModes = 0;

        if ((aidlModes & RecognitionMode.VOICE_TRIGGER) != 0) {
            hidlModes |= android.hardware.soundtrigger.V2_0.RecognitionMode.VOICE_TRIGGER;
        }
        if ((aidlModes & RecognitionMode.USER_IDENTIFICATION) != 0) {
            hidlModes |= android.hardware.soundtrigger.V2_0.RecognitionMode.USER_IDENTIFICATION;
        }
        if ((aidlModes & RecognitionMode.USER_AUTHENTICATION) != 0) {
            hidlModes |= android.hardware.soundtrigger.V2_0.RecognitionMode.USER_AUTHENTICATION;
        }
        if ((aidlModes & RecognitionMode.GENERIC_TRIGGER) != 0) {
            hidlModes |= android.hardware.soundtrigger.V2_0.RecognitionMode.GENERIC_TRIGGER;
        }
        return hidlModes;
    }

    static int hidl2aidlRecognitionModes(int hidlModes) {
        int aidlModes = 0;
        if ((hidlModes & android.hardware.soundtrigger.V2_0.RecognitionMode.VOICE_TRIGGER) != 0) {
            aidlModes |= RecognitionMode.VOICE_TRIGGER;
        }
        if ((hidlModes & android.hardware.soundtrigger.V2_0.RecognitionMode.USER_IDENTIFICATION)
                != 0) {
            aidlModes |= RecognitionMode.USER_IDENTIFICATION;
        }
        if ((hidlModes & android.hardware.soundtrigger.V2_0.RecognitionMode.USER_AUTHENTICATION)
                != 0) {
            aidlModes |= RecognitionMode.USER_AUTHENTICATION;
        }
        if ((hidlModes & android.hardware.soundtrigger.V2_0.RecognitionMode.GENERIC_TRIGGER) != 0) {
            aidlModes |= RecognitionMode.GENERIC_TRIGGER;
        }
        return aidlModes;
    }

    static @NonNull
    ISoundTriggerHw.SoundModel aidl2hidlSoundModel(@NonNull SoundModel aidlModel) {
        ISoundTriggerHw.SoundModel hidlModel = new ISoundTriggerHw.SoundModel();
        hidlModel.header.type = aidl2hidlSoundModelType(aidlModel.type);
        hidlModel.header.uuid = aidl2hidlUuid(aidlModel.uuid);
        hidlModel.header.vendorUuid = aidl2hidlUuid(aidlModel.vendorUuid);
        hidlModel.data = HidlMemoryUtil.fileDescriptorToHidlMemory(aidlModel.data,
                aidlModel.dataSize);
        return hidlModel;
    }

    static @NonNull
    ISoundTriggerHw.PhraseSoundModel aidl2hidlPhraseSoundModel(
            @NonNull PhraseSoundModel aidlModel) {
        ISoundTriggerHw.PhraseSoundModel hidlModel = new ISoundTriggerHw.PhraseSoundModel();
        hidlModel.common = aidl2hidlSoundModel(aidlModel.common);
        for (Phrase aidlPhrase : aidlModel.phrases) {
            hidlModel.phrases.add(aidl2hidlPhrase(aidlPhrase));
        }
        return hidlModel;
    }

    static @NonNull android.hardware.soundtrigger.V2_3.RecognitionConfig aidl2hidlRecognitionConfig(
            @NonNull RecognitionConfig aidlConfig) {
        android.hardware.soundtrigger.V2_3.RecognitionConfig hidlConfig =
                new android.hardware.soundtrigger.V2_3.RecognitionConfig();
        hidlConfig.base.header.captureRequested = aidlConfig.captureRequested;
        for (PhraseRecognitionExtra aidlPhraseExtra : aidlConfig.phraseRecognitionExtras) {
            hidlConfig.base.header.phrases.add(aidl2hidlPhraseRecognitionExtra(aidlPhraseExtra));
        }
        hidlConfig.base.data = HidlMemoryUtil.byteArrayToHidlMemory(aidlConfig.data,
                "SoundTrigger RecognitionConfig");
        hidlConfig.audioCapabilities = aidlConfig.audioCapabilities;
        return hidlConfig;
    }

    static @NonNull
    android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra aidl2hidlPhraseRecognitionExtra(
            @NonNull PhraseRecognitionExtra aidlExtra) {
        android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra hidlExtra =
                new android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra();
        hidlExtra.id = aidlExtra.id;
        hidlExtra.recognitionModes = aidl2hidlRecognitionModes(aidlExtra.recognitionModes);
        hidlExtra.confidenceLevel = aidlExtra.confidenceLevel;
        hidlExtra.levels.ensureCapacity(aidlExtra.levels.length);
        for (ConfidenceLevel aidlLevel : aidlExtra.levels) {
            hidlExtra.levels.add(aidl2hidlConfidenceLevel(aidlLevel));
        }
        return hidlExtra;
    }

    static @NonNull
    PhraseRecognitionExtra hidl2aidlPhraseRecognitionExtra(
            @NonNull android.hardware.soundtrigger.V2_0.PhraseRecognitionExtra hidlExtra) {
        PhraseRecognitionExtra aidlExtra = new PhraseRecognitionExtra();
        aidlExtra.id = hidlExtra.id;
        aidlExtra.recognitionModes = hidl2aidlRecognitionModes(hidlExtra.recognitionModes);
        aidlExtra.confidenceLevel = hidlExtra.confidenceLevel;
        aidlExtra.levels = new ConfidenceLevel[hidlExtra.levels.size()];
        for (int i = 0; i < hidlExtra.levels.size(); ++i) {
            aidlExtra.levels[i] = hidl2aidlConfidenceLevel(hidlExtra.levels.get(i));
        }
        return aidlExtra;
    }

    static @NonNull
    android.hardware.soundtrigger.V2_0.ConfidenceLevel aidl2hidlConfidenceLevel(
            @NonNull ConfidenceLevel aidlLevel) {
        android.hardware.soundtrigger.V2_0.ConfidenceLevel hidlLevel =
                new android.hardware.soundtrigger.V2_0.ConfidenceLevel();
        hidlLevel.userId = aidlLevel.userId;
        hidlLevel.levelPercent = aidlLevel.levelPercent;
        return hidlLevel;
    }

    static @NonNull
    ConfidenceLevel hidl2aidlConfidenceLevel(
            @NonNull android.hardware.soundtrigger.V2_0.ConfidenceLevel hidlLevel) {
        ConfidenceLevel aidlLevel = new ConfidenceLevel();
        aidlLevel.userId = hidlLevel.userId;
        aidlLevel.levelPercent = hidlLevel.levelPercent;
        return aidlLevel;
    }

    static int hidl2aidlRecognitionStatus(int hidlStatus) {
        switch (hidlStatus) {
            case ISoundTriggerHwCallback.RecognitionStatus.SUCCESS:
                return RecognitionStatus.SUCCESS;
            case ISoundTriggerHwCallback.RecognitionStatus.ABORT:
                return RecognitionStatus.ABORTED;
            case ISoundTriggerHwCallback.RecognitionStatus.FAILURE:
                return RecognitionStatus.FAILURE;
            case 3: // This doesn't have a constant in HIDL.
                return RecognitionStatus.FORCED;
            default:
                throw new IllegalArgumentException("Unknown recognition status: " + hidlStatus);
        }
    }

    static @NonNull
    RecognitionEvent hidl2aidlRecognitionEvent(@NonNull
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.RecognitionEvent hidlEvent) {
        RecognitionEvent aidlEvent = new RecognitionEvent();
        aidlEvent.status = hidl2aidlRecognitionStatus(hidlEvent.status);
        aidlEvent.type = hidl2aidlSoundModelType(hidlEvent.type);
        aidlEvent.captureAvailable = hidlEvent.captureAvailable;
        // hidlEvent.captureSession is never a valid field.
        aidlEvent.captureSession = -1;
        aidlEvent.captureDelayMs = hidlEvent.captureDelayMs;
        aidlEvent.capturePreambleMs = hidlEvent.capturePreambleMs;
        aidlEvent.triggerInData = hidlEvent.triggerInData;
        aidlEvent.audioConfig = hidl2aidlAudioConfig(hidlEvent.audioConfig);
        aidlEvent.data = new byte[hidlEvent.data.size()];
        for (int i = 0; i < aidlEvent.data.length; ++i) {
            aidlEvent.data[i] = hidlEvent.data.get(i);
        }
        return aidlEvent;
    }

    static @NonNull
    RecognitionEvent hidl2aidlRecognitionEvent(
            @NonNull ISoundTriggerHwCallback.RecognitionEvent hidlEvent) {
        RecognitionEvent aidlEvent = hidl2aidlRecognitionEvent(hidlEvent.header);
        // Data needs to get overridden with 2.1 data.
        aidlEvent.data = HidlMemoryUtil.hidlMemoryToByteArray(hidlEvent.data);
        return aidlEvent;
    }

    static @NonNull
    PhraseRecognitionEvent hidl2aidlPhraseRecognitionEvent(@NonNull
            android.hardware.soundtrigger.V2_0.ISoundTriggerHwCallback.PhraseRecognitionEvent hidlEvent) {
        PhraseRecognitionEvent aidlEvent = new PhraseRecognitionEvent();
        aidlEvent.common = hidl2aidlRecognitionEvent(hidlEvent.common);
        aidlEvent.phraseExtras = new PhraseRecognitionExtra[hidlEvent.phraseExtras.size()];
        for (int i = 0; i < hidlEvent.phraseExtras.size(); ++i) {
            aidlEvent.phraseExtras[i] = hidl2aidlPhraseRecognitionExtra(
                    hidlEvent.phraseExtras.get(i));
        }
        return aidlEvent;
    }

    static @NonNull
    PhraseRecognitionEvent hidl2aidlPhraseRecognitionEvent(
            @NonNull ISoundTriggerHwCallback.PhraseRecognitionEvent hidlEvent) {
        PhraseRecognitionEvent aidlEvent = new PhraseRecognitionEvent();
        aidlEvent.common = hidl2aidlRecognitionEvent(hidlEvent.common);
        aidlEvent.phraseExtras = new PhraseRecognitionExtra[hidlEvent.phraseExtras.size()];
        for (int i = 0; i < hidlEvent.phraseExtras.size(); ++i) {
            aidlEvent.phraseExtras[i] = hidl2aidlPhraseRecognitionExtra(
                    hidlEvent.phraseExtras.get(i));
        }
        return aidlEvent;
    }

    static @NonNull
    AudioConfig hidl2aidlAudioConfig(
            @NonNull android.hardware.audio.common.V2_0.AudioConfig hidlConfig) {
        AudioConfig aidlConfig = new AudioConfig();
        // TODO(ytai): channelMask and format might need a more careful conversion to make sure the
        //  constants match.
        aidlConfig.sampleRateHz = hidlConfig.sampleRateHz;
        aidlConfig.channelMask = hidlConfig.channelMask;
        aidlConfig.format = hidlConfig.format;
        aidlConfig.offloadInfo = hidl2aidlOffloadInfo(hidlConfig.offloadInfo);
        aidlConfig.frameCount = hidlConfig.frameCount;
        return aidlConfig;
    }

    static @NonNull
    AudioOffloadInfo hidl2aidlOffloadInfo(
            @NonNull android.hardware.audio.common.V2_0.AudioOffloadInfo hidlInfo) {
        AudioOffloadInfo aidlInfo = new AudioOffloadInfo();
        // TODO(ytai): channelMask, format, streamType and usage might need a more careful
        //  conversion to make sure the constants match.
        aidlInfo.sampleRateHz = hidlInfo.sampleRateHz;
        aidlInfo.channelMask = hidlInfo.channelMask;
        aidlInfo.format = hidlInfo.format;
        aidlInfo.streamType = hidlInfo.streamType;
        aidlInfo.bitRatePerSecond = hidlInfo.bitRatePerSecond;
        aidlInfo.durationMicroseconds = hidlInfo.durationMicroseconds;
        aidlInfo.hasVideo = hidlInfo.hasVideo;
        aidlInfo.isStreaming = hidlInfo.isStreaming;
        aidlInfo.bitWidth = hidlInfo.bitWidth;
        aidlInfo.bufferSize = hidlInfo.bufferSize;
        aidlInfo.usage = hidlInfo.usage;
        return aidlInfo;
    }

    @Nullable
    static ModelParameterRange hidl2aidlModelParameterRange(
            android.hardware.soundtrigger.V2_3.ModelParameterRange hidlRange) {
        if (hidlRange == null) {
            return null;
        }
        ModelParameterRange aidlRange = new ModelParameterRange();
        aidlRange.minInclusive = hidlRange.start;
        aidlRange.maxInclusive = hidlRange.end;
        return aidlRange;
    }

    static int aidl2hidlModelParameter(int aidlParam) {
        switch (aidlParam) {
            case ModelParameter.THRESHOLD_FACTOR:
                return android.hardware.soundtrigger.V2_3.ModelParameter.THRESHOLD_FACTOR;

            default:
                return android.hardware.soundtrigger.V2_3.ModelParameter.INVALID;
        }
    }

    static int hidl2aidlAudioCapabilities(int hidlCapabilities) {
        int aidlCapabilities = 0;
        if ((hidlCapabilities
                & android.hardware.soundtrigger.V2_3.AudioCapabilities.ECHO_CANCELLATION) != 0) {
            aidlCapabilities |= AudioCapabilities.ECHO_CANCELLATION;
        }
        if ((hidlCapabilities
                & android.hardware.soundtrigger.V2_3.AudioCapabilities.NOISE_SUPPRESSION) != 0) {
            aidlCapabilities |= AudioCapabilities.NOISE_SUPPRESSION;
        }
        return aidlCapabilities;
    }
}
