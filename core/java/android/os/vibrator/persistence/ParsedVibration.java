/*
 * Copyright 2023 The Android Open Source Project
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

package android.os.vibrator.persistence;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;

import com.android.internal.annotations.VisibleForTesting;

import java.util.Collections;
import java.util.List;

/**
 * The result of parsing a serialized vibration, which can be define by one or more
 * {@link VibrationEffect} and a resolution method.
 *
 * @hide
 */
@FlaggedApi(android.os.vibrator.Flags.FLAG_ENABLE_VIBRATION_SERIALIZATION_APIS)
@TestApi
public class ParsedVibration {
    private final List<VibrationEffect> mEffects;

    /** @hide */
    public ParsedVibration(@NonNull List<VibrationEffect> effects) {
        mEffects = effects;
    }

    /** @hide */
    public ParsedVibration(@NonNull VibrationEffect effect) {
        mEffects = List.of(effect);
    }
    /**
     * Returns the first parsed vibration supported by {@code vibrator}, or {@code null} if none of
     * the parsed vibrations are supported.
     *
     * @hide
     */
    @TestApi
    @Nullable
    public VibrationEffect resolve(@NonNull Vibrator vibrator) {
        return resolve(vibrator.getInfo());
    }

    /**
     * Returns the parsed vibrations for testing purposes.
     *
     * <p>Real callers should not use this method. Instead, they should resolve to a
     * {@link VibrationEffect} via {@link #resolve(Vibrator)}.
     *
     * @hide
     */
    @TestApi
    @VisibleForTesting
    @NonNull
    public List<VibrationEffect> getVibrationEffects() {
        return Collections.unmodifiableList(mEffects);
    }

    /**
     * Same as {@link #resolve(Vibrator)}, but uses {@link VibratorInfo} instead for resolving.
     *
     * @hide
     */
    @Nullable
    public final VibrationEffect resolve(@NonNull VibratorInfo info) {
        for (int i = 0; i < mEffects.size(); i++) {
            VibrationEffect effect = mEffects.get(i);
            if (info.areVibrationFeaturesSupported(effect)) {
                return effect;
            }
        }
        return null;
    }
}
