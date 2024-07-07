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

import static android.os.vibrator.Flags.FLAG_VIBRATION_XML_APIS;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.annotation.TestApi;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The result of parsing a serialized vibration.
 *
 * @see VibrationXmlParser
 *
 * @hide
 */
@SystemApi
@FlaggedApi(FLAG_VIBRATION_XML_APIS)
public final class ParsedVibration {
    private final ArrayList<VibrationEffect> mEffects;

    /** @hide */
    @TestApi
    public ParsedVibration(@NonNull List<VibrationEffect> effects) {
        mEffects = new ArrayList<>(effects);
    }

    /** @hide */
    public ParsedVibration(@NonNull VibrationEffect effect) {
        mEffects = new ArrayList<>(1);
        mEffects.add(effect);
    }

    /**
     * Returns the first parsed vibration supported by {@code vibrator}, or {@code null} if none of
     * the parsed vibrations are supported.
     *
     * @hide
     */
    @SystemApi
    @FlaggedApi(FLAG_VIBRATION_XML_APIS)
    @Nullable
    public VibrationEffect resolve(@NonNull Vibrator vibrator) {
        return resolve(vibrator.getInfo());
    }

    /**
     * Same as {@link #resolve(Vibrator)}, but uses {@link VibratorInfo} instead for resolving.
     *
     * @hide
     */
    @Nullable
    public VibrationEffect resolve(@NonNull VibratorInfo info) {
        for (int i = 0; i < mEffects.size(); i++) {
            VibrationEffect effect = mEffects.get(i);
            if (info.areVibrationFeaturesSupported(effect)) {
                return effect;
            }
        }
        return null;
    }

    @Override
    public boolean equals(@Nullable Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof ParsedVibration)) {
            return false;
        }
        ParsedVibration other = (ParsedVibration) o;
        return mEffects.equals(other.mEffects);
    }

    @Override
    public int hashCode() {
        return Objects.hashCode(mEffects);
    }

    @Override
    public String toString() {
        return "ParsedVibration{"
                + "effects=" + mEffects
                + '}';
    }
}
