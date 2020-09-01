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

import android.annotation.Nullable;
import android.media.soundtrigger_middleware.ConfidenceLevel;
import android.media.soundtrigger_middleware.ModelParameter;
import android.media.soundtrigger_middleware.Phrase;
import android.media.soundtrigger_middleware.PhraseRecognitionExtra;
import android.media.soundtrigger_middleware.PhraseSoundModel;
import android.media.soundtrigger_middleware.RecognitionConfig;
import android.media.soundtrigger_middleware.RecognitionMode;
import android.media.soundtrigger_middleware.SoundModel;
import android.media.soundtrigger_middleware.SoundModelType;

import com.android.internal.util.Preconditions;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Utilities for asserting the validity of various data types used by this module.
 * Each of the methods below would throw an {@link IllegalArgumentException} if its input is
 * invalid. The input's validity is determined irrespective of any context. In cases where the valid
 * value space is further limited by state, it is the caller's responsibility to assert.
 *
 * @hide
 */
public class ValidationUtil {
    static void validateUuid(@Nullable String uuid) {
        Objects.requireNonNull(uuid);
        Matcher matcher = UuidUtil.PATTERN.matcher(uuid);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(
                    "Illegal format for UUID: " + uuid);
        }
    }

    static void validateGenericModel(@Nullable SoundModel model) {
        validateModel(model, SoundModelType.GENERIC);
    }

    static void validateModel(@Nullable SoundModel model, int expectedType) {
        Objects.requireNonNull(model);
        if (model.type != expectedType) {
            throw new IllegalArgumentException("Invalid type");
        }
        validateUuid(model.uuid);
        validateUuid(model.vendorUuid);
        Objects.requireNonNull(model.data);
    }

    static void validatePhraseModel(@Nullable PhraseSoundModel model) {
        Objects.requireNonNull(model);
        validateModel(model.common, SoundModelType.KEYPHRASE);
        Objects.requireNonNull(model.phrases);
        for (Phrase phrase : model.phrases) {
            Objects.requireNonNull(phrase);
            if ((phrase.recognitionModes & ~(RecognitionMode.VOICE_TRIGGER
                    | RecognitionMode.USER_IDENTIFICATION | RecognitionMode.USER_AUTHENTICATION
                    | RecognitionMode.GENERIC_TRIGGER)) != 0) {
                throw new IllegalArgumentException("Invalid recognitionModes");
            }
            Objects.requireNonNull(phrase.users);
            Objects.requireNonNull(phrase.locale);
            Objects.requireNonNull(phrase.text);
        }
    }

    static void validateRecognitionConfig(@Nullable RecognitionConfig config) {
        Objects.requireNonNull(config);
        Objects.requireNonNull(config.phraseRecognitionExtras);
        for (PhraseRecognitionExtra extra : config.phraseRecognitionExtras) {
            Objects.requireNonNull(extra);
            if ((extra.recognitionModes & ~(RecognitionMode.VOICE_TRIGGER
                    | RecognitionMode.USER_IDENTIFICATION | RecognitionMode.USER_AUTHENTICATION
                    | RecognitionMode.GENERIC_TRIGGER)) != 0) {
                throw new IllegalArgumentException("Invalid recognitionModes");
            }
            if (extra.confidenceLevel < 0 || extra.confidenceLevel > 100) {
                throw new IllegalArgumentException("Invalid confidenceLevel");
            }
            Objects.requireNonNull(extra.levels);
            for (ConfidenceLevel level : extra.levels) {
                Objects.requireNonNull(level);
                if (level.levelPercent < 0 || level.levelPercent > 100) {
                    throw new IllegalArgumentException("Invalid confidenceLevel");
                }
            }
        }
        Objects.requireNonNull(config.data);
    }

    static void validateModelParameter(int modelParam) {
        switch (modelParam) {
            case ModelParameter.THRESHOLD_FACTOR:
                return;

            default:
                throw new IllegalArgumentException("Invalid model parameter");
        }
    }
}
