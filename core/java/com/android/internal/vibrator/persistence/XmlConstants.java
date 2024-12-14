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

package com.android.internal.vibrator.persistence;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.os.VibrationEffect;
import android.os.VibrationEffect.Composition.PrimitiveType;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Locale;

/**
 * Constants used for vibration XML serialization and parsing.
 *
 * @hide
 */
public final class XmlConstants {

    public static final String NAMESPACE = null;

    public static final String TAG_VIBRATION_EFFECT = "vibration-effect";
    public static final String TAG_VIBRATION_SELECT = "vibration-select";

    public static final String TAG_PREDEFINED_EFFECT = "predefined-effect";
    public static final String TAG_PRIMITIVE_EFFECT = "primitive-effect";
    public static final String TAG_VENDOR_EFFECT = "vendor-effect";
    public static final String TAG_WAVEFORM_EFFECT = "waveform-effect";
    public static final String TAG_WAVEFORM_ENTRY = "waveform-entry";
    public static final String TAG_REPEATING = "repeating";

    public static final String ATTRIBUTE_NAME = "name";
    public static final String ATTRIBUTE_FALLBACK = "fallback";
    public static final String ATTRIBUTE_DURATION_MS = "durationMs";
    public static final String ATTRIBUTE_AMPLITUDE = "amplitude";
    public static final String ATTRIBUTE_SCALE = "scale";
    public static final String ATTRIBUTE_DELAY_MS = "delayMs";

    public static final String VALUE_AMPLITUDE_DEFAULT = "default";

    /**
     * Allow {@link VibrationEffect} hidden APIs to be used during parsing/serializing.
     *
     * <p>Use the schema at services/core/xsd/vibrator/vibration/vibration-plus-hidden-apis.xsd.
     */
    public static final int FLAG_ALLOW_HIDDEN_APIS = 1 << 0;

    /** @hide */
    @IntDef(prefix = { "FLAG_" }, flag = true, value = {
            FLAG_ALLOW_HIDDEN_APIS
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface Flags {}

    /** Represent supported values for attribute name in {@link #TAG_PRIMITIVE_EFFECT}  */
    public enum PrimitiveEffectName {
        LOW_TICK(VibrationEffect.Composition.PRIMITIVE_LOW_TICK),
        TICK(VibrationEffect.Composition.PRIMITIVE_TICK),
        CLICK(VibrationEffect.Composition.PRIMITIVE_CLICK),
        SLOW_RISE(VibrationEffect.Composition.PRIMITIVE_SLOW_RISE),
        QUICK_RISE(VibrationEffect.Composition.PRIMITIVE_QUICK_RISE),
        QUICK_FALL(VibrationEffect.Composition.PRIMITIVE_QUICK_FALL),
        SPIN(VibrationEffect.Composition.PRIMITIVE_SPIN),
        THUD(VibrationEffect.Composition.PRIMITIVE_THUD);

        @PrimitiveType private final int mPrimitiveId;

        PrimitiveEffectName(@PrimitiveType int id) {
            mPrimitiveId = id;
        }

        /**
         * Return the {@link PrimitiveEffectName} that represents given primitive id, or null if
         * none of the available names maps to the given id.
         */
        @Nullable
        public static PrimitiveEffectName findById(int primitiveId) {
            for (PrimitiveEffectName name : PrimitiveEffectName.values()) {
                if (name.mPrimitiveId == primitiveId) {
                    return name;
                }
            }
            return null;
        }

        /**
         * Return the {@link PrimitiveEffectName} that represents given primitive name, or null if
         * none of the available names maps to the given name.
         */
        @Nullable
        public static PrimitiveEffectName findByName(@NonNull String primitiveName) {
            try {
                return PrimitiveEffectName.valueOf(primitiveName.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        @PrimitiveType
        public int getPrimitiveId() {
            return mPrimitiveId;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Represent supported values for attribute name in {@link #TAG_PREDEFINED_EFFECT}  */
    public enum PredefinedEffectName {
        // Public effects
        TICK(VibrationEffect.EFFECT_TICK, true),
        CLICK(VibrationEffect.EFFECT_CLICK, true),
        HEAVY_CLICK(VibrationEffect.EFFECT_HEAVY_CLICK, true),
        DOUBLE_CLICK(VibrationEffect.EFFECT_DOUBLE_CLICK, true),

        // Hidden effects
        TEXTURE_TICK(VibrationEffect.EFFECT_TEXTURE_TICK, false),
        THUD(VibrationEffect.EFFECT_THUD, false),
        POP(VibrationEffect.EFFECT_POP, false),
        RINGTONE_1(VibrationEffect.RINGTONES[0], false),
        RINGTONE_2(VibrationEffect.RINGTONES[1], false),
        RINGTONE_3(VibrationEffect.RINGTONES[2], false),
        RINGTONE_4(VibrationEffect.RINGTONES[3], false),
        RINGTONE_5(VibrationEffect.RINGTONES[4], false),
        RINGTONE_6(VibrationEffect.RINGTONES[5], false),
        RINGTONE_7(VibrationEffect.RINGTONES[6], false),
        RINGTONE_8(VibrationEffect.RINGTONES[7], false),
        RINGTONE_9(VibrationEffect.RINGTONES[8], false),
        RINGTONE_10(VibrationEffect.RINGTONES[9], false),
        RINGTONE_11(VibrationEffect.RINGTONES[10], false),
        RINGTONE_12(VibrationEffect.RINGTONES[11], false),
        RINGTONE_13(VibrationEffect.RINGTONES[12], false),
        RINGTONE_14(VibrationEffect.RINGTONES[13], false),
        RINGTONE_15(VibrationEffect.RINGTONES[14], false);

        private final int mEffectId;
        private final boolean mIsPublic;

        PredefinedEffectName(int id, boolean isPublic) {
            mEffectId = id;
            mIsPublic = isPublic;
        }

        /**
         * Return the {@link PredefinedEffectName} that represents given effect id, or null if
         * none of the available names maps to the given id.
         */
        @Nullable
        public static PredefinedEffectName findById(int effectId, @XmlConstants.Flags int flags) {
            boolean allowHidden = (flags & XmlConstants.FLAG_ALLOW_HIDDEN_APIS) != 0;
            for (PredefinedEffectName name : PredefinedEffectName.values()) {
                if (name.mEffectId == effectId) {
                    return (name.mIsPublic || allowHidden) ? name : null;
                }
            }
            return null;
        }

        /**
         * Return the {@link PredefinedEffectName} that represents given effect name, or null if
         * none of the available names maps to the given name.
         */
        @Nullable
        public static PredefinedEffectName findByName(@NonNull String effectName,
                @XmlConstants.Flags int flags) {
            boolean allowHidden = (flags & XmlConstants.FLAG_ALLOW_HIDDEN_APIS) != 0;
            try {
                PredefinedEffectName name = PredefinedEffectName.valueOf(
                        effectName.toUpperCase(Locale.ROOT));
                return (name.mIsPublic || allowHidden) ? name : null;
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        public int getEffectId() {
            return mEffectId;
        }

        @Override
        public String toString() {
            return name().toLowerCase(Locale.ROOT);
        }
    }
}
