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

import android.annotation.FloatRange;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.vibrator.Braking;
import android.hardware.vibrator.IVibrator;
import android.util.Log;
import android.util.MathUtils;
import android.util.Range;
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
    private final SparseBooleanArray mSupportedBraking;
    @Nullable
    private final SparseBooleanArray mSupportedPrimitives;
    private final float mQFactor;
    private final FrequencyMapping mFrequencyMapping;

    VibratorInfo(Parcel in) {
        mId = in.readInt();
        mCapabilities = in.readLong();
        mSupportedEffects = in.readSparseBooleanArray();
        mSupportedBraking = in.readSparseBooleanArray();
        mSupportedPrimitives = in.readSparseBooleanArray();
        mQFactor = in.readFloat();
        mFrequencyMapping = in.readParcelable(VibratorInfo.class.getClassLoader());
    }

    /** @hide */
    public VibratorInfo(int id, long capabilities, int[] supportedEffects, int[] supportedBraking,
            int[] supportedPrimitives, float qFactor, @NonNull FrequencyMapping frequencyMapping) {
        mId = id;
        mCapabilities = capabilities;
        mSupportedEffects = toSparseBooleanArray(supportedEffects);
        mSupportedBraking = toSparseBooleanArray(supportedBraking);
        mSupportedPrimitives = toSparseBooleanArray(supportedPrimitives);
        mQFactor = qFactor;
        mFrequencyMapping = frequencyMapping;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(mId);
        dest.writeLong(mCapabilities);
        dest.writeSparseBooleanArray(mSupportedEffects);
        dest.writeSparseBooleanArray(mSupportedBraking);
        dest.writeSparseBooleanArray(mSupportedPrimitives);
        dest.writeFloat(mQFactor);
        dest.writeParcelable(mFrequencyMapping, flags);
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
                && Objects.equals(mSupportedBraking, that.mSupportedBraking)
                && Objects.equals(mSupportedPrimitives, that.mSupportedPrimitives)
                && Objects.equals(mQFactor, that.mQFactor)
                && Objects.equals(mFrequencyMapping, that.mFrequencyMapping);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mId, mCapabilities, mSupportedEffects, mSupportedBraking,
                mSupportedPrimitives, mQFactor, mFrequencyMapping);
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
                + ", mQFactor=" + mQFactor
                + ", mFrequencyMapping=" + mFrequencyMapping
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
     * Returns a default value to be applied to composed PWLE effects for braking.
     *
     * @return a supported braking value, one of android.hardware.vibrator.Braking.*
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

    /**
     * Query whether the vibrator supports the given primitive.
     *
     * @param primitiveId Which primitives to query for.
     * @return Whether the primitive is supported.
     */
    public boolean isPrimitiveSupported(
            @VibrationEffect.Composition.PrimitiveType int primitiveId) {
        return hasCapability(IVibrator.CAP_COMPOSE_EFFECTS) && mSupportedPrimitives != null
                && mSupportedPrimitives.get(primitiveId);
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
     *         this vibrator is a composite of multiple physical devices.
     */
    public float getResonantFrequency() {
        return mFrequencyMapping.mResonantFrequencyHz;
    }

    /**
     * Gets the <a href="https://en.wikipedia.org/wiki/Q_factor">Q factor</a> of the vibrator.
     *
     * @return the Q factor of the vibrator, or {@link Float#NaN NaN} if it's unknown or
     *         this vibrator is a composite of multiple physical devices.
     */
    public float getQFactor() {
        return mQFactor;
    }

    /**
     * Return a range of relative frequency values supported by the vibrator.
     *
     * @return A range of relative frequency values supported. The range will always contain the
     * value 0, representing the device resonant frequency. Devices without frequency control will
     * return the range [0,0]. Devices with frequency control will always return a range containing
     * the safe range [-1, 1].
     * @hide
     */
    public Range<Float> getFrequencyRange() {
        return mFrequencyMapping.mRelativeFrequencyRange;
    }

    /**
     * Return the maximum amplitude the vibrator can play at given relative frequency.
     *
     * @return a value in [0,1] representing the maximum amplitude the device can play at given
     * relative frequency. Devices without frequency control will return 1 for the input zero
     * (resonant frequency), and 0 to any other input. Devices with frequency control will return
     * the supported value, for input in {@code #getFrequencyRange()}, and 0 for any other input.
     * @hide
     */
    @FloatRange(from = 0, to = 1)
    public float getMaxAmplitude(float relativeFrequency) {
        if (mFrequencyMapping.isEmpty()) {
            // The vibrator has not provided values for frequency mapping.
            // Return the expected behavior for devices without frequency control.
            return Float.compare(relativeFrequency, 0) == 0 ? 1 : 0;
        }
        return mFrequencyMapping.getMaxAmplitude(relativeFrequency);
    }

    /**
     * Return absolute frequency value for this vibrator, in hertz, that corresponds to given
     * relative frequency.
     *
     * @retur a value in hertz that corresponds to given relative frequency. Input values outside
     * {@link #getFrequencyRange()} will return {@link Float#NaN}. Devices without frequency control
     * will return {@link Float#NaN} for any input.
     * @hide
     */
    @FloatRange(from = 0)
    public float getAbsoluteFrequency(float relativeFrequency) {
        return mFrequencyMapping.toHertz(relativeFrequency);
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

    /**
     * Describes how frequency should be mapped to absolute values for a specific {@link Vibrator}.
     *
     * <p>This mapping is defined by the following parameters:
     *
     * <ol>
     *     <li>{@code minFrequency}, {@code resonantFrequency} and {@code frequencyResolution}, in
     *         hertz, provided by the vibrator.
     *     <li>{@code maxAmplitudes} a list of values in [0,1] provided by the vibrator, where
     *         {@code maxAmplitudes[i]} represents max supported amplitude at frequency
     *         {@code minFrequency + frequencyResolution * i}.
     *     <li>{@code maxFrequency = minFrequency + frequencyResolution * (maxAmplitudes.length-1)}
     *     <li>{@code suggestedSafeRangeHz} is the suggested frequency range in hertz that should be
     *         mapped to relative values -1 and 1, where 0 maps to {@code resonantFrequency}.
     * </ol>
     *
     * <p>The mapping is defined linearly by the following points:
     *
     * <ol>
     *     <li>{@code toHertz(relativeMinFrequency} = minFrequency
     *     <li>{@code                   toHertz(-1) = resonantFrequency - safeRange / 2}
     *     <li>{@code                    toHertz(0) = resonantFrequency}
     *     <li>{@code                    toHertz(1) = resonantFrequency + safeRange / 2}
     *     <li>{@code toHertz(relativeMaxFrequency) = maxFrequency}
     * </ol>
     *
     * @hide
     */
    public static final class FrequencyMapping implements Parcelable {
        private final float mMinFrequencyHz;
        private final float mResonantFrequencyHz;
        private final float mFrequencyResolutionHz;
        private final float mSuggestedSafeRangeHz;
        private final float[] mMaxAmplitudes;

        // Relative fields calculated from input values:
        private final Range<Float> mRelativeFrequencyRange;

        FrequencyMapping(Parcel in) {
            this(in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                    in.createFloatArray());
        }

        /** @hide */
        public FrequencyMapping(float minFrequencyHz, float resonantFrequencyHz,
                float frequencyResolutionHz, float suggestedSafeRangeHz, float[] maxAmplitudes) {
            mMinFrequencyHz = minFrequencyHz;
            mResonantFrequencyHz = resonantFrequencyHz;
            mFrequencyResolutionHz = frequencyResolutionHz;
            mSuggestedSafeRangeHz = suggestedSafeRangeHz;
            mMaxAmplitudes = new float[maxAmplitudes == null ? 0 : maxAmplitudes.length];
            if (maxAmplitudes != null) {
                System.arraycopy(maxAmplitudes, 0, mMaxAmplitudes, 0, maxAmplitudes.length);
            }

            float maxFrequencyHz =
                    minFrequencyHz + frequencyResolutionHz * (mMaxAmplitudes.length - 1);
            if (Float.isNaN(resonantFrequencyHz) || Float.isNaN(minFrequencyHz)
                    || Float.isNaN(frequencyResolutionHz) || Float.isNaN(suggestedSafeRangeHz)
                    || resonantFrequencyHz < minFrequencyHz
                    || resonantFrequencyHz > maxFrequencyHz) {
                // Some required fields are undefined or have bad values.
                // Leave this mapping empty.
                mRelativeFrequencyRange = Range.create(0f, 0f);
                return;
            }

            // Calculate actual safe range, limiting the suggested one by the device supported range
            float safeDelta = MathUtils.min(
                    suggestedSafeRangeHz / 2,
                    resonantFrequencyHz - minFrequencyHz,
                    maxFrequencyHz - resonantFrequencyHz);
            mRelativeFrequencyRange = Range.create(
                    (minFrequencyHz - resonantFrequencyHz) / safeDelta,
                    (maxFrequencyHz - resonantFrequencyHz) / safeDelta);
        }

        /**
         * Returns true if this frequency mapping is empty, i.e. the only supported relative
         * frequency is 0 (resonant frequency).
         */
        public boolean isEmpty() {
            return Float.compare(mRelativeFrequencyRange.getLower(),
                    mRelativeFrequencyRange.getUpper()) == 0;
        }

        /**
         * Returns the frequency value in hertz that is mapped to the given relative frequency.
         *
         * @return The mapped frequency, in hertz, or {@link Float#NaN} is value outside the device
         * supported range.
         */
        public float toHertz(float relativeFrequency) {
            if (!mRelativeFrequencyRange.contains(relativeFrequency)) {
                return Float.NaN;
            }
            float relativeMinFrequency = mRelativeFrequencyRange.getLower();
            if (Float.compare(relativeMinFrequency, 0) == 0) {
                // relative supported range is [0,0], so toHertz(0) should be the resonant frequency
                return mResonantFrequencyHz;
            }
            float shift = (mMinFrequencyHz - mResonantFrequencyHz) / relativeMinFrequency;
            return mResonantFrequencyHz + relativeFrequency * shift;
        }

        /**
         * Returns the maximum amplitude the vibrator can reach while playing at given relative
         * frequency.
         *
         * @return A value in [0,1] representing the max amplitude supported at given relative
         * frequency. This will return 0 if frequency is outside supported range, or if max
         * amplitude mapping is empty.
         */
        public float getMaxAmplitude(float relativeFrequency) {
            float frequencyHz = toHertz(relativeFrequency);
            if (Float.isNaN(frequencyHz)) {
                // Unsupported frequency requested, vibrator cannot play at this frequency.
                return 0;
            }
            float position = (frequencyHz - mMinFrequencyHz) / mFrequencyResolutionHz;
            int floorIndex = (int) Math.floor(position);
            int ceilIndex = (int) Math.ceil(position);
            if (floorIndex < 0 || floorIndex >= mMaxAmplitudes.length) {
                if (mMaxAmplitudes.length > 0) {
                    // This should never happen if the setup of relative frequencies was correct.
                    Log.w(TAG, "Max amplitudes has " + mMaxAmplitudes.length
                            + " entries and was expected to cover the frequency " + frequencyHz
                            + " Hz when starting at min frequency of " + mMinFrequencyHz
                            + " Hz with resolution of " + mFrequencyResolutionHz + " Hz.");
                }
                return 0;
            }
            if (floorIndex != ceilIndex && ceilIndex < mMaxAmplitudes.length) {
                // Value in between two mapped frequency values, use the lowest supported one.
                return MathUtils.min(mMaxAmplitudes[floorIndex], mMaxAmplitudes[ceilIndex]);
            }
            return mMaxAmplitudes[floorIndex];
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeFloat(mMinFrequencyHz);
            dest.writeFloat(mResonantFrequencyHz);
            dest.writeFloat(mFrequencyResolutionHz);
            dest.writeFloat(mSuggestedSafeRangeHz);
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
            if (!(o instanceof FrequencyMapping)) {
                return false;
            }
            FrequencyMapping that = (FrequencyMapping) o;
            return Float.compare(mMinFrequencyHz, that.mMinFrequencyHz) == 0
                    && Float.compare(mResonantFrequencyHz, that.mResonantFrequencyHz) == 0
                    && Float.compare(mFrequencyResolutionHz, that.mFrequencyResolutionHz) == 0
                    && Float.compare(mSuggestedSafeRangeHz, that.mSuggestedSafeRangeHz) == 0
                    && Arrays.equals(mMaxAmplitudes, that.mMaxAmplitudes);
        }

        @Override
        public int hashCode() {
            return Objects.hash(mMinFrequencyHz, mFrequencyResolutionHz, mFrequencyResolutionHz,
                    mSuggestedSafeRangeHz, mMaxAmplitudes);
        }

        @Override
        public String toString() {
            return "FrequencyMapping{"
                    + "mRelativeFrequencyRange=" + mRelativeFrequencyRange
                    + ", mMinFrequency=" + mMinFrequencyHz
                    + ", mResonantFrequency=" + mResonantFrequencyHz
                    + ", mMaxFrequency="
                    + (mMinFrequencyHz + mFrequencyResolutionHz * (mMaxAmplitudes.length - 1))
                    + ", mFrequencyResolution=" + mFrequencyResolutionHz
                    + ", mSuggestedSafeRange=" + mSuggestedSafeRangeHz
                    + ", mMaxAmplitudes count=" + mMaxAmplitudes.length
                    + '}';
        }

        @NonNull
        public static final Creator<FrequencyMapping> CREATOR =
                new Creator<FrequencyMapping>() {
                    @Override
                    public FrequencyMapping createFromParcel(Parcel in) {
                        return new FrequencyMapping(in);
                    }

                    @Override
                    public FrequencyMapping[] newArray(int size) {
                        return new FrequencyMapping[size];
                    }
                };
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
