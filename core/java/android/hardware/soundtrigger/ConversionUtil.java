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
import android.media.audio.common.AidlConversion;
import android.media.audio.common.AudioConfig;
import android.media.soundtrigger.AudioCapabilities;
import android.media.soundtrigger.ConfidenceLevel;
import android.media.soundtrigger.ModelParameterRange;
import android.media.soundtrigger.Phrase;
import android.media.soundtrigger.PhraseRecognitionEvent;
import android.media.soundtrigger.PhraseRecognitionExtra;
import android.media.soundtrigger.PhraseSoundModel;
import android.media.soundtrigger.Properties;
import android.media.soundtrigger.RecognitionConfig;
import android.media.soundtrigger.RecognitionEvent;
import android.media.soundtrigger.RecognitionMode;
import android.media.soundtrigger.SoundModel;
import android.media.soundtrigger_middleware.PhraseRecognitionEventSys;
import android.media.soundtrigger_middleware.RecognitionEventSys;
import android.media.soundtrigger_middleware.SoundTriggerModuleDescriptor;
import android.os.ParcelFileDescriptor;
import android.os.SharedMemory;
import android.system.ErrnoException;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** @hide */
public class ConversionUtil {
    public static SoundTrigger.ModuleProperties aidl2apiModuleDescriptor(
            SoundTriggerModuleDescriptor aidlDesc) {
        Properties properties = aidlDesc.properties;
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

    public static SoundTrigger.Keyphrase aidl2apiPhrase(Phrase aidlPhrase) {
        return new SoundTrigger.Keyphrase(aidlPhrase.id,
                aidl2apiRecognitionModes(aidlPhrase.recognitionModes),
                new Locale.Builder().setLanguageTag(aidlPhrase.locale).build(),
                aidlPhrase.text,
                Arrays.copyOf(aidlPhrase.users, aidlPhrase.users.length));
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

    public static SoundTrigger.RecognitionConfig aidl2apiRecognitionConfig(
            RecognitionConfig aidlConfig) {
        var keyphrases =
            new SoundTrigger.KeyphraseRecognitionExtra[aidlConfig.phraseRecognitionExtras.length];
        int i = 0;
        for (var extras : aidlConfig.phraseRecognitionExtras) {
            keyphrases[i++] = aidl2apiPhraseRecognitionExtra(extras);
        }
        return new SoundTrigger.RecognitionConfig(aidlConfig.captureRequested,
                false /** allowMultipleTriggers **/,
                keyphrases,
                Arrays.copyOf(aidlConfig.data, aidlConfig.data.length),
                aidl2apiAudioCapabilities(aidlConfig.audioCapabilities));
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

    public static SoundTrigger.RecognitionEvent aidl2apiRecognitionEvent(int modelHandle,
            int captureSession, RecognitionEventSys aidlEvent) {
        RecognitionEvent recognitionEvent = aidlEvent.recognitionEvent;
        // The API recognition event doesn't allow for a null audio format, even though it doesn't
        // always make sense. We thus replace it with a default.
        AudioFormat audioFormat = aidl2apiAudioFormatWithDefault(recognitionEvent.audioConfig,
                true /*isInput*/);
        return new SoundTrigger.GenericRecognitionEvent(recognitionEvent.status, modelHandle,
                recognitionEvent.captureAvailable, captureSession, recognitionEvent.captureDelayMs,
                recognitionEvent.capturePreambleMs, recognitionEvent.triggerInData, audioFormat,
                recognitionEvent.data,
                recognitionEvent.recognitionStillActive, aidlEvent.halEventReceivedMillis,
                aidlEvent.token);
    }

    public static SoundTrigger.RecognitionEvent aidl2apiPhraseRecognitionEvent(
            int modelHandle, int captureSession, PhraseRecognitionEventSys aidlEvent) {
        PhraseRecognitionEvent recognitionEvent = aidlEvent.phraseRecognitionEvent;
        SoundTrigger.KeyphraseRecognitionExtra[] apiExtras =
                new SoundTrigger.KeyphraseRecognitionExtra[recognitionEvent.phraseExtras.length];
        for (int i = 0; i < recognitionEvent.phraseExtras.length; ++i) {
            apiExtras[i] = aidl2apiPhraseRecognitionExtra(recognitionEvent.phraseExtras[i]);
        }
        // The API recognition event doesn't allow for a null audio format, even though it doesn't
        // always make sense. We thus replace it with a default.
        AudioFormat audioFormat = aidl2apiAudioFormatWithDefault(
                recognitionEvent.common.audioConfig,
                true /*isInput*/);
        return new SoundTrigger.KeyphraseRecognitionEvent(recognitionEvent.common.status,
                modelHandle,
                recognitionEvent.common.captureAvailable, captureSession,
                recognitionEvent.common.captureDelayMs,
                recognitionEvent.common.capturePreambleMs, recognitionEvent.common.triggerInData,
                audioFormat,
                recognitionEvent.common.data, apiExtras, aidlEvent.halEventReceivedMillis,
                aidlEvent.token);
    }

    // In case of a null input returns a non-null valid output.
    public static AudioFormat aidl2apiAudioFormatWithDefault(
            @Nullable AudioConfig audioConfig, boolean isInput) {
        if (audioConfig != null) {
            return AidlConversion.aidl2api_AudioConfig_AudioFormat(audioConfig, isInput);
        }
        return new AudioFormat.Builder()
            .setSampleRate(48000)
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build();
    }

    public static int api2aidlModelParameter(int apiParam) {
        switch (apiParam) {
            case ModelParams.THRESHOLD_FACTOR:
                return android.media.soundtrigger.ModelParameter.THRESHOLD_FACTOR;
            default:
                return android.media.soundtrigger.ModelParameter.INVALID;
        }
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

    public static @Nullable ParcelFileDescriptor byteArrayToSharedMemory(byte[] data, String name) {
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

    public static byte[] sharedMemoryToByteArray(@Nullable ParcelFileDescriptor pfd, int size) {
        if (pfd == null || size == 0) {
            return new byte[0];
        }
        try (SharedMemory mem = SharedMemory.fromFileDescriptor(pfd)) {
            ByteBuffer buffer = mem.mapReadOnly();
            byte[] data = new byte[(size > mem.getSize()) ? mem.getSize() : size];
            buffer.get(data);
            mem.unmap(buffer);
            return data;
        } catch (ErrnoException e) {
            throw new RuntimeException(e);
        }
    }
}
