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
import android.hardware.vibrator.Braking;
import android.hardware.vibrator.IVibrator;
import android.util.IndentingPrintWriter;
import android.util.MathUtils;
import android.util.Range;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A VibratorInfo describes the capabilities of a {@link Vibrator}.
 *
 * <p>This description includes its capabilities, list of supported effects and composition
 * primitives.
 *
 * @hide
 */
public class VibratorInfo implements Parcelable {
    private static final String TAG = "VibratorInfo";

    /** @hide */
    public static final VibratorInfo EMPTY_VIBRATOR_INFO = new VibratorInfo.Builder(-1).build();

    private final int mId;
    private final long mCapabilities;
    @Nullable
    private final SparseBooleanArray mSupportedEffects;
    @Nullable
    private final SparseBooleanArray mSupportedBraking;
    private final SparseIntArray mSupportedPrimitives;
    private final int mPrimitiveDelayMax;
    private final int mCompositionSizeMax;
    private final int mPwlePrimitiveDurationMax;
    private final int mPwleSizeMax;
    private final float mQFactor;
    private final FrequencyProfile mFrequencyProfile;
    private final int mMaxEnvelopeEffectSize;
    private final int mMinEnvelopeEffectControlPointDurationMillis;
    private final int mMaxEnvelopeEffectControlPointDurationMillis;

    VibratorInfo(Parcel in) {
        mId = in.readInt();
        mCapabilities = in.readLong();
        mSupportedEffects = in.readSparseBooleanArray();
        mSupportedBraking = in.readSparseBooleanArray();
        mSupportedPrimitives = in.readSparseIntArray();
        mPrimitiveDelayMax = in.readInt();
        mCompositionSizeMax = in.readInt();
        mPwlePrimitiveDurationMax = in.readInt();
        mPwleSizeMax = in.readInt();
        mQFactor = in.readFloat();
        mFrequencyProfile = FrequencyProfile.CREATOR.createFromParcel(in);
        mMaxEnvelopeEffectSize = in.readInt();
        mMinEnvelopeEffectControlPointDurationMillis = in.readInt();
        mMaxEnvelopeEffectControlPointDurationMillis = in.readInt();
    }

    public VibratorInfo(int id, @NonNull VibratorInfo baseVibratorInfo) {
        this(id, baseVibratorInfo.mCapabilities, baseVibratorInfo.mSupportedEffects,
                baseVibratorInfo.mSupportedBraking, baseVibratorInfo.mSupportedPrimitives,
                baseVibratorInfo.mPrimitiveDelayMax, baseVibratorInfo.mCompositionSizeMax,
                baseVibratorInfo.mPwlePrimitiveDurationMax, baseVibratorInfo.mPwleSizeMax,
                baseVibratorInfo.mQFactor, baseVibratorInfo.mFrequencyProfile,
                baseVibratorInfo.mMaxEnvelopeEffectSize,
                baseVibratorInfo.mMinEnvelopeEffectControlPointDurationMillis,
                baseVibratorInfo.mMaxEnvelopeEffectControlPointDurationMillis);
    }

    /**
     * Default constructor.
     *
     * @param id                       The vibrator id.
     * @param capabilities             All capability flags of the vibrator, defined in
     *                                 IVibrator.CAP_*.
     * @param supportedEffects         All supported predefined effects, enum values from
     *                                 {@link android.hardware.vibrator.Effect}.
     * @param supportedBraking         All supported braking types, enum values from {@link
     *                                 Braking}.
     * @param supportedPrimitives      All supported primitive effects, key are enum values from
     *                                 {@link android.hardware.vibrator.CompositePrimitive} and
     *                                 values are estimated durations in milliseconds.
     * @param primitiveDelayMax        The maximum delay that can be set to a composition primitive
     *                                 in milliseconds.
     * @param compositionSizeMax       The maximum number of primitives supported by a composition.
     * @param pwlePrimitiveDurationMax The maximum duration of a PWLE primitive in milliseconds.
     * @param pwleSizeMax              The maximum number of primitives supported by a PWLE
     *                                 composition.
     * @param qFactor                  The vibrator quality factor.
     * @param frequencyProfile         The description of the vibrator supported frequencies and max
     *                                 amplitude mappings.
     * @hide
     */
    public VibratorInfo(int id, long capabilities, @Nullable SparseBooleanArray supportedEffects,
            @Nullable SparseBooleanArray supportedBraking,
            @NonNull SparseIntArray supportedPrimitives, int primitiveDelayMax,
            int compositionSizeMax, int pwlePrimitiveDurationMax, int pwleSizeMax,
            float qFactor, @NonNull FrequencyProfile frequencyProfile,
            int maxEnvelopeEffectSize, int minEnvelopeEffectControlPointDurationMillis,
            int maxEnvelopeEffectControlPointDurationMillis) {
        Preconditions.checkNotNull(supportedPrimitives);
        Preconditions.checkNotNull(frequencyProfile);
        mId = id;
        mCapabilities = capabilities;
        mSupportedEffects = supportedEffects == null ? null : supportedEffects.clone();
        mSupportedBraking = supportedBraking == null ? null : supportedBraking.clone();
        mSupportedPrimitives = supportedPrimitives.clone();
        mPrimitiveDelayMax = primitiveDelayMax;
        mCompositionSizeMax = compositionSizeMax;
        mPwlePrimitiveDurationMax = pwlePrimitiveDurationMax;
        mPwleSizeMax = pwleSizeMax;
        mQFactor = qFactor;
        mFrequencyProfile = frequencyProfile;
        mMaxEnvelopeEffectSize = maxEnvelopeEffectSize;
        mMinEnvelopeEffectControlPointDurationMillis =
                minEnvelopeEffectControlPointDurationMillis;
        mMaxEnvelopeEffectControlPointDurationMillis =
                maxEnvelopeEffectControlPointDurationMillis;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeLong(mCapabilities);
        dest.writeSparseBooleanArray(mSupportedEffects);
        dest.writeSparseBooleanArray(mSupportedBraking);
        dest.writeSparseIntArray(mSupportedPrimitives);
        dest.writeInt(mPrimitiveDelayMax);
        dest.writeInt(mCompositionSizeMax);
        dest.writeInt(mPwlePrimitiveDurationMax);
        dest.writeInt(mPwleSizeMax);
        dest.writeFloat(mQFactor);
        mFrequencyProfile.writeToParcel(dest, flags);
        dest.writeInt(mMaxEnvelopeEffectSize);
        dest.writeInt(mMinEnvelopeEffectControlPointDurationMillis);
        dest.writeInt(mMaxEnvelopeEffectControlPointDurationMillis);
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
        return mId == that.mId && equalContent(that);
    }

    /**
     * Returns {@code true} only if the properties and capabilities of the provided info, except for
     * the ID, equals to this info. Returns {@code false} otherwise.
     *
     * @hide
     */
    public boolean equalContent(VibratorInfo that) {
        int supportedPrimitivesCount = mSupportedPrimitives.size();
        if (supportedPrimitivesCount != that.mSupportedPrimitives.size()) {
            return false;
        }
        for (int i = 0; i < supportedPrimitivesCount; i++) {
            if (mSupportedPrimitives.keyAt(i) != that.mSupportedPrimitives.keyAt(i)) {
                return false;
            }
            if (mSupportedPrimitives.valueAt(i) != that.mSupportedPrimitives.valueAt(i)) {
                return false;
            }
        }
        return mCapabilities == that.mCapabilities
                && mPrimitiveDelayMax == that.mPrimitiveDelayMax
                && mCompositionSizeMax == that.mCompositionSizeMax
                && mPwlePrimitiveDurationMax == that.mPwlePrimitiveDurationMax
                && mPwleSizeMax == that.mPwleSizeMax
                && Objects.equals(mSupportedEffects, that.mSupportedEffects)
                && Objects.equals(mSupportedBraking, that.mSupportedBraking)
                && Objects.equals(mQFactor, that.mQFactor)
                && Objects.equals(mFrequencyProfile, that.mFrequencyProfile)
                && mMaxEnvelopeEffectSize == that.mMaxEnvelopeEffectSize
                && mMinEnvelopeEffectControlPointDurationMillis
                == that.mMinEnvelopeEffectControlPointDurationMillis
                && mMaxEnvelopeEffectControlPointDurationMillis
                == that.mMaxEnvelopeEffectControlPointDurationMillis;
    }

    @Override
    public int hashCode() {
        int hashCode = Objects.hash(mId, mCapabilities, mSupportedEffects, mSupportedBraking,
                mQFactor, mFrequencyProfile);
        for (int i = 0; i < mSupportedPrimitives.size(); i++) {
            hashCode = 31 * hashCode + mSupportedPrimitives.keyAt(i);
            hashCode = 31 * hashCode + mSupportedPrimitives.valueAt(i);
        }
        return hashCode;
    }

    @Override
    public String toString() {
        return "VibratorInfo{"
                + "mId=" + mId
                + ", mCapabilities=" + Arrays.toString(getCapabilitiesNames())
                + ", mCapabilities flags=" + Long.toBinaryString(mCapabilities)
                + ", mSupportedEffects=" + Arrays.toString(getSupportedEffectsNames())
                + ", mSupportedBraking=" + Arrays.toString(getSupportedBrakingNames())
                + ", mSupportedPrimitives=" + Arrays.toString(getSupportedPrimitivesNames())
                + ", mPrimitiveDelayMax=" + mPrimitiveDelayMax
                + ", mCompositionSizeMax=" + mCompositionSizeMax
                + ", mPwlePrimitiveDurationMax=" + mPwlePrimitiveDurationMax
                + ", mPwleSizeMax=" + mPwleSizeMax
                + ", mQFactor=" + mQFactor
                + ", mFrequencyProfile=" + mFrequencyProfile
                + ", mMaxEnvelopeEffectSize=" + mMaxEnvelopeEffectSize
                + ", mMinEnvelopeEffectControlPointDurationMillis="
                + mMinEnvelopeEffectControlPointDurationMillis
                + ", mMaxEnvelopeEffectControlPointDurationMillis="
                + mMaxEnvelopeEffectControlPointDurationMillis
                + '}';
    }

    /** @hide */
    public void dump(IndentingPrintWriter pw) {
        pw.println("VibratorInfo:");
        pw.increaseIndent();
        pw.println("id = " + mId);
        pw.println("capabilities = " + Arrays.toString(getCapabilitiesNames()));
        pw.println("capabilitiesFlags = " + Long.toBinaryString(mCapabilities));
        pw.println("supportedEffects = " + Arrays.toString(getSupportedEffectsNames()));
        pw.println("supportedPrimitives = " + Arrays.toString(getSupportedPrimitivesNames()));
        pw.println("supportedBraking = " + Arrays.toString(getSupportedBrakingNames()));
        pw.println("primitiveDelayMax = " + mPrimitiveDelayMax);
        pw.println("compositionSizeMax = " + mCompositionSizeMax);
        pw.println("pwlePrimitiveDurationMax = " + mPwlePrimitiveDurationMax);
        pw.println("pwleSizeMax = " + mPwleSizeMax);
        pw.println("q-factor = " + mQFactor);
        pw.println("frequencyProfile = " + mFrequencyProfile);
        pw.println("mMaxEnvelopeEffectSize = " + mMaxEnvelopeEffectSize);
        pw.println("mMinEnvelopeEffectControlPointDurationMillis = "
                + mMinEnvelopeEffectControlPointDurationMillis);
        pw.println("mMaxEnvelopeEffectControlPointDurationMillis = "
                + mMaxEnvelopeEffectControlPointDurationMillis);
        pw.decreaseIndent();
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
     * Check whether the vibrator has frequency control.
     *
     * @return True if the hardware can control the frequency of the vibrations, otherwise false.
     */
    public boolean hasFrequencyControl() {
        // We currently can only control frequency of the vibration using the compose PWLE method.
        return hasCapability(
                IVibrator.CAP_FREQUENCY_CONTROL | IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
    }

    /**
     * Returns a default value to be applied to composed PWLE effects for braking.
     *
     * @return a supported braking value, one of android.hardware.vibrator.Braking.*
     * @hide
     */
    public int getDefaultBraking() {
        if (mSupportedBraking != null) {
            int size = mSupportedBraking.size();
            for (int i = 0; i < size; i++) {
                if (mSupportedBraking.keyAt(i) != Braking.NONE) {
                    return mSupportedBraking.keyAt(i);
                }
            }
        }
        return Braking.NONE;
    }

    /** @hide */
    @Nullable
    public SparseBooleanArray getSupportedBraking() {
        if (mSupportedBraking == null) {
            return null;
        }
        return mSupportedBraking.clone();
    }

    /** @hide */
    public boolean isBrakingSupportKnown() {
        return mSupportedBraking != null;
    }

    /** @hide */
    public boolean hasBrakingSupport(@Braking int braking) {
        return (mSupportedBraking != null) && mSupportedBraking.get(braking);
    }

    /** @hide */
    public boolean isEffectSupportKnown() {
        return mSupportedEffects != null;
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
        return mSupportedEffects.get(effectId) ? Vibrator.VIBRATION_EFFECT_SUPPORT_YES
                : Vibrator.VIBRATION_EFFECT_SUPPORT_NO;
    }

    /** @hide */
    @Nullable
    public SparseBooleanArray getSupportedEffects() {
        if (mSupportedEffects == null) {
            return null;
        }
        return mSupportedEffects.clone();
    }

    /**
     * Query whether the vibrator supports the given primitive.
     *
     * @param primitiveId Which primitives to query for.
     * @return Whether the primitive is supported.
     */
    public boolean isPrimitiveSupported(
            @VibrationEffect.Composition.PrimitiveType int primitiveId) {
        return hasCapability(IVibrator.CAP_COMPOSE_EFFECTS)
                && (mSupportedPrimitives.indexOfKey(primitiveId) >= 0);
    }

    /**
     * Query whether or not the vibrator supports all components of a given {@link VibrationEffect}
     * (i.e. the vibrator can play the given effect as intended).
     *
     * <p>See {@link Vibrator#areVibrationFeaturesSupported(VibrationEffect)} for more
     * information on how the vibrator support is determined.
     *
     * @param effect the {@link VibrationEffect} to check if it is supported
     * @return {@code true} if the vibrator can play the given {@code effect} as intended,
     *         {@code false} otherwise.
     *
     * @hide
     */
    public boolean areVibrationFeaturesSupported(@NonNull VibrationEffect effect) {
        return effect.areVibrationFeaturesSupported(this);
    }

    /**
     * Query the estimated duration of given primitive.
     *
     * @param primitiveId Which primitives to query for.
     * @return The duration in milliseconds estimated for the primitive, or zero if primitive not
     * supported.
     */
    public int getPrimitiveDuration(
            @VibrationEffect.Composition.PrimitiveType int primitiveId) {
        return mSupportedPrimitives.get(primitiveId);
    }

    /** @hide */
    public SparseIntArray getSupportedPrimitives() {
        return mSupportedPrimitives.clone();
    }

    /**
     * Query the maximum delay supported for a primitive in a composed effect.
     *
     * @return The max delay in milliseconds, or zero if unlimited.
     */
    public int getPrimitiveDelayMax() {
        return mPrimitiveDelayMax;
    }

    /**
     * Query the maximum number of primitives supported in a composed effect.
     *
     * @return The max number of primitives supported, or zero if unlimited.
     */
    public int getCompositionSizeMax() {
        return mCompositionSizeMax;
    }

    /**
     * Query the maximum duration supported for a primitive in a PWLE composition.
     *
     * @return The max duration in milliseconds, or zero if unlimited.
     */
    public int getPwlePrimitiveDurationMax() {
        return mPwlePrimitiveDurationMax;
    }

    /**
     * Query the maximum number of primitives supported in a PWLE composition.
     *
     * @return The max number of primitives supported, or zero if unlimited.
     */
    public int getPwleSizeMax() {
        return mPwleSizeMax;
    }

    /**
     * Check whether the vibrator supports the creation of envelope effects.
     *
     * <p>See {@link Vibrator#areEnvelopeEffectsSupported()} for more information on envelope
     * effects.
     *
     * @return True if the hardware supports creating envelope effects, false otherwise.
     */
    public boolean areEnvelopeEffectsSupported() {
        return hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2);
    }

    /**
     * Calculates the maximum allowed duration for an envelope effect, measured in milliseconds.
     *
     * @return The maximum duration (in milliseconds) that an envelope effect can have.
     */
    public int getMaxEnvelopeEffectDurationMillis() {
        return mMaxEnvelopeEffectSize * mMaxEnvelopeEffectControlPointDurationMillis;
    }

    /**
     * Gets the maximum number of control points supported for envelope effects on this device.
     *
     * @return The maximum number of control points that can be used to define an envelope effect.
     */
    public int getMaxEnvelopeEffectSize() {
        return mMaxEnvelopeEffectSize;
    }

    /**
     * Gets the minimum allowed duration for any individual segment within an envelope effect,
     * measured in milliseconds.
     *
     * @return The minimum duration (in milliseconds) that a segment within an envelope effect
     * can have.
     */
    public int getMinEnvelopeEffectControlPointDurationMillis() {
        return mMinEnvelopeEffectControlPointDurationMillis;
    }

    /**
     * Gets the maximum allowed duration for any individual segment within an envelope effect,
     * measured in milliseconds.
     *
     * @return The maximum duration (in milliseconds) that a segment within an envelope effect
     * can have.
     */
    public int getMaxEnvelopeEffectControlPointDurationMillis() {
        return mMaxEnvelopeEffectControlPointDurationMillis;
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

    /**
     * Gets the resonant frequency of the vibrator.
     *
     * @return the resonant frequency of the vibrator, or {@link Float#NaN NaN} if it's unknown or
     * this vibrator is a composite of multiple physical devices.
     */
    public float getResonantFrequencyHz() {
        return mFrequencyProfile.mResonantFrequencyHz;
    }

    /**
     * Gets the <a href="https://en.wikipedia.org/wiki/Q_factor">Q factor</a> of the vibrator.
     *
     * @return the Q factor of the vibrator, or {@link Float#NaN NaN} if it's unknown or
     * this vibrator is a composite of multiple physical devices.
     */
    public float getQFactor() {
        return mQFactor;
    }

    /**
     * Gets the profile of supported frequencies, including the measurements of maximum relative
     * output acceleration for supported vibration frequencies.
     *
     * <p>If the devices does not have frequency control then the profile should be empty.
     */
    @NonNull
    public FrequencyProfile getFrequencyProfile() {
        return mFrequencyProfile;
    }

    /** Returns a single int representing all the capabilities of the vibrator. */
    public long getCapabilities() {
        return mCapabilities;
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
        if (hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS)) {
            names.add("COMPOSE_PWLE_EFFECTS");
        }
        if (hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL)) {
            names.add("ALWAYS_ON_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL)) {
            names.add("AMPLITUDE_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_FREQUENCY_CONTROL)) {
            names.add("FREQUENCY_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_EXTERNAL_CONTROL)) {
            names.add("EXTERNAL_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_EXTERNAL_AMPLITUDE_CONTROL)) {
            names.add("EXTERNAL_AMPLITUDE_CONTROL");
        }
        if (hasCapability(IVibrator.CAP_COMPOSE_PWLE_EFFECTS_V2)) {
            names.add("CAP_COMPOSE_PWLE_EFFECTS_V2");
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

    private String[] getSupportedBrakingNames() {
        if (mSupportedBraking == null) {
            return new String[0];
        }
        String[] names = new String[mSupportedBraking.size()];
        for (int i = 0; i < mSupportedBraking.size(); i++) {
            switch (mSupportedBraking.keyAt(i)) {
                case Braking.NONE:
                    names[i] = "NONE";
                    break;
                case Braking.CLAB:
                    names[i] = "CLAB";
                    break;
                default:
                    names[i] = Integer.toString(mSupportedBraking.keyAt(i));
            }
        }
        return names;
    }

    private String[] getSupportedPrimitivesNames() {
        int supportedPrimitivesCount = mSupportedPrimitives.size();
        String[] names = new String[supportedPrimitivesCount];
        for (int i = 0; i < supportedPrimitivesCount; i++) {
            names[i] = VibrationEffect.Composition.primitiveToString(mSupportedPrimitives.keyAt(i))
                    + "(" + mSupportedPrimitives.valueAt(i) + "ms)";
        }
        return names;
    }

    /**
     * Describes the maximum relative output acceleration that can be achieved for each supported
     * frequency in a specific vibrator.
     *
     * <p>This profile is defined by the following parameters:
     *
     * <ol>
     *     <li>{@code minFrequencyHz}, {@code resonantFrequencyHz} and {@code frequencyResolutionHz}
     *         provided by the vibrator in hertz.
     *     <li>{@code maxAmplitudes} a list of values in [0,1] provided by the vibrator, where
     *         {@code maxAmplitudes[i]} represents max supported amplitude at frequency
     *         {@code minFrequencyHz + frequencyResolutionHz * i}.
     *     <li>{@code maxFrequencyHz = minFrequencyHz
     *                                     + frequencyResolutionHz * (maxAmplitudes.length-1)}
     * </ol>
     *
     * @hide
     */
    public static final class FrequencyProfile implements Parcelable {
        @Nullable
        private final Range<Float> mFrequencyRangeHz;
        private final float mMinFrequencyHz;
        private final float mResonantFrequencyHz;
        private final float mFrequencyResolutionHz;
        private final float[] mMaxAmplitudes;

        FrequencyProfile(Parcel in) {
            this(in.readFloat(), in.readFloat(), in.readFloat(), in.createFloatArray());
        }

        /**
         * Default constructor.
         *
         * @param resonantFrequencyHz   The vibrator resonant frequency, in hertz.
         * @param minFrequencyHz        Minimum supported frequency, in hertz.
         * @param frequencyResolutionHz The frequency resolution, in hertz, used by the max
         *                              amplitude measurements.
         * @param maxAmplitudes         The max amplitude supported by each supported frequency,
         *                              starting at minimum frequency with jumps of frequency
         *                              resolution.
         * @hide
         */
        public FrequencyProfile(float resonantFrequencyHz, float minFrequencyHz,
                float frequencyResolutionHz, float[] maxAmplitudes) {
            mMinFrequencyHz = minFrequencyHz;
            mResonantFrequencyHz = resonantFrequencyHz;
            mFrequencyResolutionHz = frequencyResolutionHz;
            mMaxAmplitudes = new float[maxAmplitudes == null ? 0 : maxAmplitudes.length];
            if (maxAmplitudes != null) {
                System.arraycopy(maxAmplitudes, 0, mMaxAmplitudes, 0, maxAmplitudes.length);
            }

            // If any required field is undefined or has a bad value then this profile is invalid.
            boolean isValid = !Float.isNaN(resonantFrequencyHz)
                    && (resonantFrequencyHz > 0)
                    && !Float.isNaN(minFrequencyHz)
                    && (minFrequencyHz > 0)
                    && !Float.isNaN(frequencyResolutionHz)
                    && (frequencyResolutionHz > 0)
                    && (mMaxAmplitudes.length > 0);

            // If any max amplitude is outside the allowed range then this profile is invalid.
            for (int i = 0; i < mMaxAmplitudes.length; i++) {
                isValid &= (mMaxAmplitudes[i] >= 0) && (mMaxAmplitudes[i] <= 1);
            }

            float maxFrequencyHz = isValid
                    ? minFrequencyHz + frequencyResolutionHz * (mMaxAmplitudes.length - 1)
                    : Float.NaN;

            // If the constraint min < resonant < max is not met then it is invalid.
            isValid &= !Float.isNaN(maxFrequencyHz)
                    && (resonantFrequencyHz >= minFrequencyHz)
                    && (resonantFrequencyHz <= maxFrequencyHz)
                    && (minFrequencyHz < maxFrequencyHz);

            mFrequencyRangeHz = isValid ? Range.create(minFrequencyHz, maxFrequencyHz) : null;
        }

        /** Returns true if the supported frequency range is empty. */
        public boolean isEmpty() {
            return mFrequencyRangeHz == null;
        }

        /** Returns the supported frequency range, in hertz. */
        @Nullable
        public Range<Float> getFrequencyRangeHz() {
            return mFrequencyRangeHz;
        }

        /**
         * Returns the maximum relative amplitude the vibrator can reach while playing at the
         * given frequency.
         *
         * @param frequencyHz frequency, in hertz, for query.
         * @return A value in [0,1] representing the max relative amplitude supported at the given
         * frequency. This will return 0 if the frequency is outside the supported range, or if the
         * supported frequency range is empty.
         */
        public float getMaxAmplitude(float frequencyHz) {
            if (isEmpty() || Float.isNaN(frequencyHz) || !mFrequencyRangeHz.contains(frequencyHz)) {
                // Unsupported frequency requested, vibrator cannot play at this frequency.
                return 0;
            }

            // Subtract minFrequencyHz to simplify offset calculations.
            float mappingFreq = frequencyHz - mMinFrequencyHz;

            // Find the bucket to interpolate within.
            // Any calculated index should be safe, except exactly equal to max amplitude can be
            // one step too high, so constrain it to guarantee safety.
            int startIdx = MathUtils.constrain(
                    /* amount= */ (int) Math.floor(mappingFreq / mFrequencyResolutionHz),
                    /* low= */ 0, /* high= */ mMaxAmplitudes.length - 1);
            int nextIdx = MathUtils.constrain(
                    /* amount= */ startIdx + 1,
                    /* low= */ 0, /* high= */ mMaxAmplitudes.length - 1);

            // Linearly interpolate the amplitudes based on the frequency range of the bucket.
            return MathUtils.constrainedMap(
                    mMaxAmplitudes[startIdx], mMaxAmplitudes[nextIdx],
                    startIdx * mFrequencyResolutionHz, nextIdx * mFrequencyResolutionHz,
                    mappingFreq);
        }

        /** Returns the raw list of maximum relative output accelerations from the vibrator. */
        @NonNull
        public float[] getMaxAmplitudes() {
            return Arrays.copyOf(mMaxAmplitudes, mMaxAmplitudes.length);
        }

        /** Returns the raw frequency resolution used for max amplitude measurements, in hertz. */
        public float getFrequencyResolutionHz() {
            return mFrequencyResolutionHz;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeFloat(mResonantFrequencyHz);
            dest.writeFloat(mMinFrequencyHz);
            dest.writeFloat(mFrequencyResolutionHz);
            dest.writeFloatArray(mMaxAmplitudes);
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
            if (!(o instanceof FrequencyProfile)) {
                return false;
            }
            FrequencyProfile that = (FrequencyProfile) o;
            return Float.compare(mMinFrequencyHz, that.mMinFrequencyHz) == 0
                    && Float.compare(mResonantFrequencyHz, that.mResonantFrequencyHz) == 0
                    && Float.compare(mFrequencyResolutionHz, that.mFrequencyResolutionHz) == 0
                    && Arrays.equals(mMaxAmplitudes, that.mMaxAmplitudes);
        }

        @Override
        public int hashCode() {
            int hashCode = Objects.hash(mMinFrequencyHz, mFrequencyResolutionHz,
                    mFrequencyResolutionHz);
            hashCode = 31 * hashCode + Arrays.hashCode(mMaxAmplitudes);
            return hashCode;
        }

        @Override
        public String toString() {
            return "FrequencyProfile{"
                    + "mFrequencyRange=" + mFrequencyRangeHz
                    + ", mMinFrequency=" + mMinFrequencyHz
                    + ", mResonantFrequency=" + mResonantFrequencyHz
                    + ", mFrequencyResolution=" + mFrequencyResolutionHz
                    + ", mMaxAmplitudes count=" + mMaxAmplitudes.length
                    + '}';
        }

        @NonNull
        public static final Creator<FrequencyProfile> CREATOR =
                new Creator<FrequencyProfile>() {
                    @Override
                    public FrequencyProfile createFromParcel(Parcel in) {
                        return new FrequencyProfile(in);
                    }

                    @Override
                    public FrequencyProfile[] newArray(int size) {
                        return new FrequencyProfile[size];
                    }
                };
    }

    /** @hide */
    public static final class Builder {
        private final int mId;
        private long mCapabilities;
        private SparseBooleanArray mSupportedEffects;
        private SparseBooleanArray mSupportedBraking;
        private SparseIntArray mSupportedPrimitives = new SparseIntArray();
        private int mPrimitiveDelayMax;
        private int mCompositionSizeMax;
        private int mPwlePrimitiveDurationMax;
        private int mPwleSizeMax;
        private float mQFactor = Float.NaN;
        private FrequencyProfile mFrequencyProfile =
                new FrequencyProfile(Float.NaN, Float.NaN, Float.NaN, null);
        private int mMaxEnvelopeEffectSize;
        private int mMinEnvelopeEffectControlPointDurationMillis;
        private int mMaxEnvelopeEffectControlPointDurationMillis;

        /** A builder class for a {@link VibratorInfo}. */
        public Builder(int id) {
            mId = id;
        }

        /** Configure the vibrator capabilities with a combination of IVibrator.CAP_* values. */
        @NonNull
        public Builder setCapabilities(long capabilities) {
            mCapabilities = capabilities;
            return this;
        }

        /** Configure the effects supported with {@link android.hardware.vibrator.Effect} values. */
        @NonNull
        public Builder setSupportedEffects(int... supportedEffects) {
            mSupportedEffects = toSparseBooleanArray(supportedEffects);
            return this;
        }

        /** Configure braking supported with {@link android.hardware.vibrator.Braking} values. */
        @NonNull
        public Builder setSupportedBraking(int... supportedBraking) {
            mSupportedBraking = toSparseBooleanArray(supportedBraking);
            return this;
        }

        /** Configure maximum duration, in milliseconds, of a PWLE primitive. */
        @NonNull
        public Builder setPwlePrimitiveDurationMax(int pwlePrimitiveDurationMax) {
            mPwlePrimitiveDurationMax = pwlePrimitiveDurationMax;
            return this;
        }

        /** Configure maximum number of primitives supported in a single PWLE composed effect. */
        @NonNull
        public Builder setPwleSizeMax(int pwleSizeMax) {
            mPwleSizeMax = pwleSizeMax;
            return this;
        }

        /** Configure the duration of a {@link android.hardware.vibrator.CompositePrimitive}. */
        @NonNull
        public Builder setSupportedPrimitive(int primitiveId, int duration) {
            mSupportedPrimitives.put(primitiveId, duration);
            return this;
        }

        /** Configure maximum delay, in milliseconds, supported in a composed effect primitive. */
        @NonNull
        public Builder setPrimitiveDelayMax(int primitiveDelayMax) {
            mPrimitiveDelayMax = primitiveDelayMax;
            return this;
        }

        /** Configure maximum number of primitives supported in a single composed effect. */
        @NonNull
        public Builder setCompositionSizeMax(int compositionSizeMax) {
            mCompositionSizeMax = compositionSizeMax;
            return this;
        }

        /** Configure the vibrator quality factor. */
        @NonNull
        public Builder setQFactor(float qFactor) {
            mQFactor = qFactor;
            return this;
        }

        /** Configure the vibrator frequency information like resonant frequency and bandwidth. */
        @NonNull
        public Builder setFrequencyProfile(@NonNull FrequencyProfile frequencyProfile) {
            mFrequencyProfile = frequencyProfile;
            return this;
        }

        /**
         * Configure the maximum number of control points supported for envelope effects on this
         * device.
         */
        @NonNull
        public Builder setMaxEnvelopeEffectSize(int maxEnvelopeEffectSize) {
            mMaxEnvelopeEffectSize = maxEnvelopeEffectSize;
            return this;
        }

        /**
         * Configure the minimum supported duration for any individual segment within an
         * envelope effect in milliseconds.
         */
        @NonNull
        public Builder setMinEnvelopeEffectControlPointDurationMillis(
                int minEnvelopeEffectControlPointDuration) {
            mMinEnvelopeEffectControlPointDurationMillis = minEnvelopeEffectControlPointDuration;
            return this;
        }

        /**
         * Configure the maximum supported duration for any individual segment within an
         * envelope effect in milliseconds.
         */
        @NonNull
        public Builder setMaxEnvelopeEffectControlPointDurationMillis(
                int maxEnvelopeEffectControlPointDuration) {
            mMaxEnvelopeEffectControlPointDurationMillis = maxEnvelopeEffectControlPointDuration;
            return this;
        }

        /** Build the configured {@link VibratorInfo}. */
        @NonNull
        public VibratorInfo build() {
            return new VibratorInfo(mId, mCapabilities, mSupportedEffects, mSupportedBraking,
                    mSupportedPrimitives, mPrimitiveDelayMax, mCompositionSizeMax,
                    mPwlePrimitiveDurationMax, mPwleSizeMax, mQFactor, mFrequencyProfile,
                    mMaxEnvelopeEffectSize, mMinEnvelopeEffectControlPointDurationMillis,
                    mMaxEnvelopeEffectControlPointDurationMillis);
        }

        /**
         * Create a {@link SparseBooleanArray} from given {@code supportedKeys} where each key is
         * mapped
         * to {@code true}.
         */
        @Nullable
        private static SparseBooleanArray toSparseBooleanArray(int[] supportedKeys) {
            if (supportedKeys == null) {
                return null;
            }
            SparseBooleanArray array = new SparseBooleanArray();
            for (int key : supportedKeys) {
                array.put(key, true);
            }
            return array;
        }
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
