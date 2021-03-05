/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.os;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.util.SparseBooleanArray;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A VibratorInfo describes the capabilities of a {@link Vibrator}.
 *
 * This description includes its capabilities, list of supported effects and composition primitives.
 *
 * @hide
 */
public final class VibratorInfo implements Parcelable {
    private static final String TAG = "VibratorInfo";

    private final int mId;
    private final long mCapabilities;
    @Nullable
    private final SparseBooleanArray mSupportedEffects;
    @Nullable
    private final SparseBooleanArray mSupportedPrimitives;

    VibratorInfo(Parcel in) {
        mId = in.readInt();
        mCapabilities = in.readLong();
        mSupportedEffects = in.readSparseBooleanArray();
        mSupportedPrimitives = in.readSparseBooleanArray();
    }

    /** @hide */
    public VibratorInfo(int id, long capabilities, int[] supportedEffects,
            int[] supportedPrimitives) {
        mId = id;
        mCapabilities = capabilities;
        mSupportedEffects = toSparseBooleanArray(supportedEffects);
        mSupportedPrimitives = toSparseBooleanArray(supportedPrimitives);
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeLong(mCapabilities);
        dest.writeSparseBooleanArray(mSupportedEffects);
        dest.writeSparseBooleanArray(mSupportedPrimitives);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof VibratorInfo)) {
            return false;
        }
        VibratorInfo that = (VibratorInfo) o;
        return mId == that.mId && mCapabilities == that.mCapabilities
                && Objects.equals(mSupportedEffects, that.mSupportedEffects)
                && Objects.equals(mSupportedPrimitives, that.mSupportedPrimitives);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mCapabilities, mSupportedEffects, mSupportedPrimitives);
    }

    @Override
    public String toString() {
        return "VibratorInfo{"
                + "mId=" + mId
                + ", mCapabilities=" + Arrays.toString(getCapabilitiesNames())
                + ", mCapabilities flags=" + Long.toBinaryString(mCapabilities)
                + ", mSupportedEffects=" + Arrays.toString(getSupportedEffectsNames())
                + ", mSupportedPrimitives=" + Arrays.toString(getSupportedPrimitivesNames())
                + '}';
    }

    /** Return the id of this vibrator. */
    public int getId() {
        return mId;
    }

    /**
     * Check whether the vibrator has amplitude control.
     *
     * @return True if the hardware can control the amplitude of the vibrations, otherwise false.
     */
    public boolean hasAmplitudeControl() {
        return hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL);
    }

    /**
     * Query whether the vibrator supports the given effect.
     *
     * @param effectId Which effects to query for.
     * @return {@link Vibrator#VIBRATION_EFFECT_SUPPORT_YES} if the effect is supported,
     * {@link Vibrator#VIBRATION_EFFECT_SUPPORT_NO} if it isn't supported, or
     * {@link Vibrator#VIBRATION_EFFECT_SUPPORT_UNKNOWN} if the system can't determine whether it's
     * supported or not.
     */
    @Vibrator.VibrationEffectSupport
    public int isEffectSupported(@VibrationEffect.EffectType int effectId) {
        if (mSupportedEffects == null) {
            return Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN;
        }
        return mSupportedEffects.get(effectId, false) ? Vibrator.VIBRATION_EFFECT_SUPPORT_YES
                : Vibrator.VIBRATION_EFFECT_SUPPORT_NO;
    }

    /**
     * Query whether the vibrator supports the given primitive.
     *
     * @param primitiveId Which primitives to query for.
     * @return Whether the primitive is supported.
     */
    public boolean isPrimitiveSupported(@VibrationEffect.Composition.Primitive int primitiveId) {
        return hasCapability(IVibrator.CAP_COMPOSE_EFFECTS) && mSupportedPrimitives != null
                && mSupportedPrimitives.get(primitiveId, false);
    }

    /**
     * Check against this vibrator capabilities.
     *
     * @param capability one of IVibrator.CAP_*
     * @return true if this vibrator has this capability, false otherwise
     * @hide
     */
    public boolean hasCapability(long capability) {
        return (mCapabilities & capability) == capability;
    }

    private String[] getCapabilitiesNames() {
        List<String> names = new ArrayList<>();
        if (hasCapability(IVibrator.CAP_ON_CALLBACK)) {
            names.add("ON_CALLBACK");
        }
        if (hasCapability(IVibrator.CAP_PERFORM_CALLBACK)) {
            names.add("PERFORM_CALLBACK");
        }
        if (hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)) {
            names.add("COMPOSE_EFFECTS");
        }
        if (hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
            names.add("ALWAYS_ON_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
            names.add("AMPLITUDE_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
            names.add("EXTERNAL_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_EXTERNAL_AMPLITUDE_CONTROL)) {
            names.add("EXTERNAL_AMPLITUDE_CONTROL");
        }
        return names.toArray(new String[names.size()]);
    }

    private String[] getSupportedEffectsNames() {
        if (mSupportedEffects == null) {
            return new String[0];
        }
        String[] names = new String[mSupportedEffects.size()];
        for (int i = 0; i < mSupportedEffects.size(); i++) {
            names[i] = VibrationEffect.effectIdToString(mSupportedEffects.keyAt(i));
        }
        return names;
    }

    private String[] getSupportedPrimitivesNames() {
        if (mSupportedPrimitives == null) {
            return new String[0];
        }
        String[] names = new String[mSupportedPrimitives.size()];
        for (int i = 0; i < mSupportedPrimitives.size(); i++) {
            names[i] = VibrationEffect.Composition.primitiveToString(mSupportedPrimitives.keyAt(i));
        }
        return names;
    }

    @Nullable
    private static SparseBooleanArray toSparseBooleanArray(int[] values) {
        if (values == null) {
            return null;
        }
        SparseBooleanArray array = new SparseBooleanArray();
        for (int value : values) {
            array.put(value, true);
        }
        return array;
    }

    @NonNull
    public static final Creator<VibratorInfo> CREATOR =
            new Creator<VibratorInfo>() {
                @Override
                public VibratorInfo createFromParcel(Parcel in) {
                    return new VibratorInfo(in);
                }

                @Override
                public VibratorInfo[] newArray(int size) {
                    return new VibratorInfo[size];
                }
            };
}
