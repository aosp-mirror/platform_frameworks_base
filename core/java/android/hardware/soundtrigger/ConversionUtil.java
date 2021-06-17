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

package android.hardware.soundtrigger;

import android.annotation.Nullable;
import android.media.AudioFormat;
import android.media.audio.common.AudioConfig;
import android.media.soundtrigger_middleware.AudioCapabilities;
import android.media.soundtrigger_middleware.ConfidenceLevel;
import android.media.soundtrigger_middleware.ModelParameterRange;
import android.media.soundtrigger_middleware.Phrase;
import android.media.soundtrigger_middleware.PhraseRecognitionEvent;
import android.media.soundtrigger_middleware.PhraseRecognitionExtra;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionEvent;
import android.media.soundtrigger_middleware.RecognitionMode;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.media.soundtrigger_middleware.SoundTriggerModuleProperties;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.UUID;

/** @hide */
class ConversionUtil {
    public static SoundTrigger.ModuleProperties aidl2apiModuleDescriptor(
            SoundTriggerModuleDescriptor aidlDesc) {
        SoundTriggerModuleProperties properties = aidlDesc.properties;
        return new SoundTrigger.ModuleProperties(
                aidlDesc.handle,
                properties.implementor,
                properties.description,
                properties.uuid,
                properties.version,
                properties.supportedModelArch,
                properties.maxSoundModels,
                properties.maxKeyPhrases,
                properties.maxUsers,
                aidl2apiRecognitionModes(properties.recognitionModes),
                properties.captureTransition,
                properties.maxBufferMs,
                properties.concurrentCapture,
                properties.powerConsumptionMw,
                properties.triggerInEvent,
                aidl2apiAudioCapabilities(properties.audioCapabilities)
        );
    }

    public static int aidl2apiRecognitionModes(int aidlModes) {
        int result = 0;
        if ((aidlModes & RecognitionMode.VOICE_TRIGGER) != 0) {
            result |= SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER;
        }
        if ((aidlModes & RecognitionMode.USER_IDENTIFICATION) != 0) {
            result |= SoundTrigger.RECOGNITION_MODE_USER_IDENTIFICATION;
        }
        if ((aidlModes & RecognitionMode.USER_AUTHENTICATION) != 0) {
            result |= SoundTrigger.RECOGNITION_MODE_USER_AUTHENTICATION;
        }
        if ((aidlModes & RecognitionMode.GENERIC_TRIGGER) != 0) {
            result |= SoundTrigger.RECOGNITION_MODE_GENERIC;
        }
        return result;
    }

    public static int api2aidlRecognitionModes(int apiModes) {
        int result = 0;
        if ((apiModes & SoundTrigger.RECOGNITION_MODE_VOICE_TRIGGER) != 0) {
            result |= RecognitionMode.VOICE_TRIGGER;
        }
        if ((apiModes & SoundTrigger.RECOGNITION_MODE_USER_IDENTIFICATION) != 0) {
            result |= RecognitionMode.USER_IDENTIFICATION;
        }
        if ((apiModes & SoundTrigger.RECOGNITION_MODE_USER_AUTHENTICATION) != 0) {
            result |= RecognitionMode.USER_AUTHENTICATION;
        }
        if ((apiModes & SoundTrigger.RECOGNITION_MODE_GENERIC) != 0) {
            result |= RecognitionMode.GENERIC_TRIGGER;
        }
        return result;
    }


    public static SoundModel api2aidlGenericSoundModel(SoundTrigger.GenericSoundModel apiModel) {
        return api2aidlSoundModel(apiModel);
    }

    public static SoundModel api2aidlSoundModel(SoundTrigger.SoundModel apiModel) {
        SoundModel aidlModel = new SoundModel();
        aidlModel.type = apiModel.getType();
        aidlModel.uuid = api2aidlUuid(apiModel.getUuid());
        aidlModel.vendorUuid = api2aidlUuid(apiModel.getVendorUuid());
        byte[] data = apiModel.getData();
        aidlModel.data = byteArrayToSharedMemory(data, "SoundTrigger SoundModel");
        aidlModel.dataSize = data.length;
        return aidlModel;
    }

    public static String api2aidlUuid(UUID apiUuid) {
        return apiUuid.toString();
    }

    public static PhraseSoundModel api2aidlPhraseSoundModel(
            SoundTrigger.KeyphraseSoundModel apiModel) {
        PhraseSoundModel aidlModel = new PhraseSoundModel();
        aidlModel.common = api2aidlSoundModel(apiModel);
        aidlModel.phrases = new Phrase[apiModel.getKeyphrases().length];
        for (int i = 0; i < apiModel.getKeyphrases().length; ++i) {
            aidlModel.phrases[i] = api2aidlPhrase(apiModel.getKeyphrases()[i]);
        }
        return aidlModel;
    }

    public static Phrase api2aidlPhrase(SoundTrigger.Keyphrase apiPhrase) {
        Phrase aidlPhrase = new Phrase();
        aidlPhrase.id = apiPhrase.getId();
        aidlPhrase.recognitionModes = api2aidlRecognitionModes(apiPhrase.getRecognitionModes());
        aidlPhrase.users = Arrays.copyOf(apiPhrase.getUsers(), apiPhrase.getUsers().length);
        aidlPhrase.locale = apiPhrase.getLocale().toLanguageTag();
        aidlPhrase.text = apiPhrase.getText();
        return aidlPhrase;
    }

    public static RecognitionConfig api2aidlRecognitionConfig(
            SoundTrigger.RecognitionConfig apiConfig) {
        RecognitionConfig aidlConfig = new RecognitionConfig();
        aidlConfig.captureRequested = apiConfig.captureRequested;
        // apiConfig.allowMultipleTriggers is ignored by the lower layers.
        aidlConfig.phraseRecognitionExtras =
                new PhraseRecognitionExtra[apiConfig.keyphrases.length];
        for (int i = 0; i < apiConfig.keyphrases.length; ++i) {
            aidlConfig.phraseRecognitionExtras[i] = api2aidlPhraseRecognitionExtra(
                    apiConfig.keyphrases[i]);
        }
        aidlConfig.data = Arrays.copyOf(apiConfig.data, apiConfig.data.length);
        aidlConfig.audioCapabilities = api2aidlAudioCapabilities(apiConfig.audioCapabilities);
        return aidlConfig;
    }

    public static PhraseRecognitionExtra api2aidlPhraseRecognitionExtra(
            SoundTrigger.KeyphraseRecognitionExtra apiExtra) {
        PhraseRecognitionExtra aidlExtra = new PhraseRecognitionExtra();
        aidlExtra.id = apiExtra.id;
        aidlExtra.recognitionModes = api2aidlRecognitionModes(apiExtra.recognitionModes);
        aidlExtra.confidenceLevel = apiExtra.coarseConfidenceLevel;
        aidlExtra.levels = new ConfidenceLevel[apiExtra.confidenceLevels.length];
        for (int i = 0; i < apiExtra.confidenceLevels.length; ++i) {
            aidlExtra.levels[i] = api2aidlConfidenceLevel(apiExtra.confidenceLevels[i]);
        }
        return aidlExtra;
    }

    public static SoundTrigger.KeyphraseRecognitionExtra aidl2apiPhraseRecognitionExtra(
            PhraseRecognitionExtra aidlExtra) {
        SoundTrigger.ConfidenceLevel[] apiLevels =
                new SoundTrigger.ConfidenceLevel[aidlExtra.levels.length];
        for (int i = 0; i < aidlExtra.levels.length; ++i) {
            apiLevels[i] = aidl2apiConfidenceLevel(aidlExtra.levels[i]);
        }
        return new SoundTrigger.KeyphraseRecognitionExtra(aidlExtra.id,
                aidl2apiRecognitionModes(aidlExtra.recognitionModes),
                aidlExtra.confidenceLevel, apiLevels);
    }

    public static ConfidenceLevel api2aidlConfidenceLevel(
            SoundTrigger.ConfidenceLevel apiLevel) {
        ConfidenceLevel aidlLevel = new ConfidenceLevel();
        aidlLevel.levelPercent = apiLevel.confidenceLevel;
        aidlLevel.userId = apiLevel.userId;
        return aidlLevel;
    }

    public static SoundTrigger.ConfidenceLevel aidl2apiConfidenceLevel(
            ConfidenceLevel apiLevel) {
        return new SoundTrigger.ConfidenceLevel(apiLevel.userId, apiLevel.levelPercent);
    }

    public static SoundTrigger.RecognitionEvent aidl2apiRecognitionEvent(
            int modelHandle, RecognitionEvent aidlEvent) {
        // The API recognition event doesn't allow for a null audio format, even though it doesn't
        // always make sense. We thus replace it with a default.
        AudioFormat audioFormat = aidl2apiAudioFormatWithDefault(aidlEvent.audioConfig);
        return new SoundTrigger.GenericRecognitionEvent(
                aidlEvent.status,
                modelHandle, aidlEvent.captureAvailable, aidlEvent.captureSession,
                aidlEvent.captureDelayMs, aidlEvent.capturePreambleMs, aidlEvent.triggerInData,
                audioFormat, aidlEvent.data);
    }

    public static SoundTrigger.RecognitionEvent aidl2apiPhraseRecognitionEvent(
            int modelHandle,
            PhraseRecognitionEvent aidlEvent) {
        SoundTrigger.KeyphraseRecognitionExtra[] apiExtras =
                new SoundTrigger.KeyphraseRecognitionExtra[aidlEvent.phraseExtras.length];
        for (int i = 0; i < aidlEvent.phraseExtras.length; ++i) {
            apiExtras[i] = aidl2apiPhraseRecognitionExtra(aidlEvent.phraseExtras[i]);
        }
        // The API recognition event doesn't allow for a null audio format, even though it doesn't
        // always make sense. We thus replace it with a default.
        AudioFormat audioFormat = aidl2apiAudioFormatWithDefault(aidlEvent.common.audioConfig);
        return new SoundTrigger.KeyphraseRecognitionEvent(aidlEvent.common.status, modelHandle,
                aidlEvent.common.captureAvailable,
                aidlEvent.common.captureSession, aidlEvent.common.captureDelayMs,
                aidlEvent.common.capturePreambleMs, aidlEvent.common.triggerInData,
                audioFormat, aidlEvent.common.data,
                apiExtras);
    }

    public static AudioFormat aidl2apiAudioFormat(AudioConfig audioConfig) {
        AudioFormat.Builder apiBuilder = new AudioFormat.Builder();
        apiBuilder.setSampleRate(audioConfig.sampleRateHz);
        apiBuilder.setChannelMask(aidl2apiChannelInMask(audioConfig.channelMask));
        apiBuilder.setEncoding(aidl2apiEncoding(audioConfig.format));
        return apiBuilder.build();
    }

    // Same as above, but in case of a null input returns a non-null valid output.
    public static AudioFormat aidl2apiAudioFormatWithDefault(@Nullable AudioConfig audioConfig) {
        if (audioConfig != null) {
            return aidl2apiAudioFormat(audioConfig);
        }
        return new AudioFormat.Builder().build();
    }

    public static int aidl2apiEncoding(int aidlFormat) {
        switch (aidlFormat) {
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_16_BIT:
                return AudioFormat.ENCODING_PCM_16BIT;

            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_8_BIT:
                return AudioFormat.ENCODING_PCM_8BIT;

            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_FLOAT:
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_8_24_BIT:
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_24_BIT_PACKED:
            case android.media.audio.common.AudioFormat.PCM
                    | android.media.audio.common.AudioFormat.PCM_SUB_32_BIT:
                return AudioFormat.ENCODING_PCM_FLOAT;

            case android.media.audio.common.AudioFormat.AC3:
                return AudioFormat.ENCODING_AC3;

            case android.media.audio.common.AudioFormat.E_AC3:
                return AudioFormat.ENCODING_E_AC3;

            case android.media.audio.common.AudioFormat.DTS:
                return AudioFormat.ENCODING_DTS;

            case android.media.audio.common.AudioFormat.DTS_HD:
                return AudioFormat.ENCODING_DTS_HD;

            case android.media.audio.common.AudioFormat.MP3:
                return AudioFormat.ENCODING_MP3;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_LC:
                return AudioFormat.ENCODING_AAC_LC;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_HE_V1:
                return AudioFormat.ENCODING_AAC_HE_V1;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_HE_V2:
                return AudioFormat.ENCODING_AAC_HE_V2;

            case android.media.audio.common.AudioFormat.IEC61937:
                return AudioFormat.ENCODING_IEC61937;

            case android.media.audio.common.AudioFormat.DOLBY_TRUEHD:
                return AudioFormat.ENCODING_DOLBY_TRUEHD;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_ELD:
                return AudioFormat.ENCODING_AAC_ELD;

            case android.media.audio.common.AudioFormat.AAC
                    | android.media.audio.common.AudioFormat.AAC_SUB_XHE:
                return AudioFormat.ENCODING_AAC_XHE;

            case android.media.audio.common.AudioFormat.AC4:
                return AudioFormat.ENCODING_AC4;

            case android.media.audio.common.AudioFormat.E_AC3
                    | android.media.audio.common.AudioFormat.E_AC3_SUB_JOC:
                return AudioFormat.ENCODING_E_AC3_JOC;

            case android.media.audio.common.AudioFormat.MAT:
            case android.media.audio.common.AudioFormat.MAT
                    | android.media.audio.common.AudioFormat.MAT_SUB_1_0:
            case android.media.audio.common.AudioFormat.MAT
                    | android.media.audio.common.AudioFormat.MAT_SUB_2_0:
            case android.media.audio.common.AudioFormat.MAT
                    | android.media.audio.common.AudioFormat.MAT_SUB_2_1:
                return AudioFormat.ENCODING_DOLBY_MAT;

            case android.media.audio.common.AudioFormat.DEFAULT:
                return AudioFormat.ENCODING_DEFAULT;

            default:
                return AudioFormat.ENCODING_INVALID;
        }
    }

    public static int api2aidlModelParameter(int apiParam) {
        switch (apiParam) {
            case ModelParams.THRESHOLD_FACTOR:
                return android.media.soundtrigger_middleware.ModelParameter.THRESHOLD_FACTOR;
            default:
                return android.media.soundtrigger_middleware.ModelParameter.INVALID;
        }
    }

    public static int aidl2apiChannelInMask(int aidlMask) {
        // We're assuming AudioFormat.CHANNEL_IN_* constants are kept in sync with
        // android.media.audio.common.AudioChannelMask.
        return aidlMask;
    }

    public static SoundTrigger.ModelParamRange aidl2apiModelParameterRange(
            @Nullable ModelParameterRange aidlRange) {
        if (aidlRange == null) {
            return null;
        }
        return new SoundTrigger.ModelParamRange(aidlRange.minInclusive, aidlRange.maxInclusive);
    }

    public static int aidl2apiAudioCapabilities(int aidlCapabilities) {
        int result = 0;
        if ((aidlCapabilities & AudioCapabilities.ECHO_CANCELLATION) != 0) {
            result |= SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_ECHO_CANCELLATION;
        }
        if ((aidlCapabilities & AudioCapabilities.NOISE_SUPPRESSION) != 0) {
            result |= SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_NOISE_SUPPRESSION;
        }
        return result;
    }

    public static int api2aidlAudioCapabilities(int apiCapabilities) {
        int result = 0;
        if ((apiCapabilities & SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_ECHO_CANCELLATION)
                != 0) {
            result |= AudioCapabilities.ECHO_CANCELLATION;
        }
        if ((apiCapabilities & SoundTrigger.ModuleProperties.AUDIO_CAPABILITY_NOISE_SUPPRESSION)
                != 0) {
            result |= AudioCapabilities.NOISE_SUPPRESSION;
        }
        return result;
    }

    private static @Nullable ParcelFileDescriptor byteArrayToSharedMemory(byte[] data, String name) {
        if (data.length == 0) {
            return null;
        }

        try {
            SharedMemory shmem = SharedMemory.create(name != null ? name : "", data.length);
            ByteBuffer buffer = shmem.mapReadWrite();
            buffer.put(data);
            shmem.unmap(buffer);
            ParcelFileDescriptor fd = shmem.getFdDup();
            shmem.close();
            return fd;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
