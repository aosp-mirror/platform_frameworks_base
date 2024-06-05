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

package android.os.vibrator;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.hardware.vibrator.IVibrator;
import android.os.Vibrator;
import android.os.VibratorInfo;
import android.util.Range;
import android.util.Slog;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import java.util.Arrays;
import java.util.function.Function;

/**
 * Represents multiple vibrator information as a single {@link VibratorInfo}.
 *
 * <p>This uses an intersection of all vibrators to decide the capabilities and effect/primitive
 * support.
 *
 * @hide
 */
public final class MultiVibratorInfo extends VibratorInfo {
    private static final String TAG = "MultiVibratorInfo";

    // Epsilon used for float comparison applied in calculations for the merged info.
    private static final float EPSILON = 1e-5f;

    public MultiVibratorInfo(int id, VibratorInfo[] vibrators) {
        this(id, vibrators, frequencyProfileIntersection(vibrators));
    }

    private MultiVibratorInfo(
            int id, VibratorInfo[] vibrators, VibratorInfo.FrequencyProfile mergedProfile) {
        super(id,
                capabilitiesIntersection(vibrators, mergedProfile.isEmpty()),
                supportedEffectsIntersection(vibrators),
                supportedBrakingIntersection(vibrators),
                supportedPrimitivesAndDurationsIntersection(vibrators),
                integerLimitIntersection(vibrators, VibratorInfo::getPrimitiveDelayMax),
                integerLimitIntersection(vibrators, VibratorInfo::getCompositionSizeMax),
                integerLimitIntersection(vibrators, VibratorInfo::getPwlePrimitiveDurationMax),
                integerLimitIntersection(vibrators, VibratorInfo::getPwleSizeMax),
                floatPropertyIntersection(vibrators, VibratorInfo::getQFactor),
                mergedProfile);
    }

    private static int capabilitiesIntersection(VibratorInfo[] infos,
            boolean frequencyProfileIsEmpty) {
        int intersection = ~0;
        for (VibratorInfo info : infos) {
            intersection &= info.getCapabilities();
        }
        if (frequencyProfileIsEmpty) {
            // Revoke frequency control if the merged frequency profile ended up empty.
            intersection &= ~IVibrator.CAP_FREQUENCY_CONTROL;
        }
        return intersection;
    }

    @Nullable
    private static SparseBooleanArray supportedBrakingIntersection(VibratorInfo[] infos) {
        for (VibratorInfo info : infos) {
            if (!info.isBrakingSupportKnown()) {
                // If one vibrator support is unknown, then the intersection is also unknown.
                return null;
            }
        }

        SparseBooleanArray intersection = new SparseBooleanArray();
        SparseBooleanArray firstVibratorBraking = infos[0].getSupportedBraking();

        brakingIdLoop:
        for (int i = 0; i < firstVibratorBraking.size(); i++) {
            int brakingId = firstVibratorBraking.keyAt(i);
            if (!firstVibratorBraking.valueAt(i)) {
                // The first vibrator already doesn't support this braking, so skip it.
                continue brakingIdLoop;
            }

            for (int j = 1; j < infos.length; j++) {
                if (!infos[j].hasBrakingSupport(brakingId)) {
                    // One vibrator doesn't support this braking, so the intersection doesn't.
                    continue brakingIdLoop;
                }
            }

            intersection.put(brakingId, true);
        }

        return intersection;
    }

    @Nullable
    private static SparseBooleanArray supportedEffectsIntersection(VibratorInfo[] infos) {
        for (VibratorInfo info : infos) {
            if (!info.isEffectSupportKnown()) {
                // If one vibrator support is unknown, then the intersection is also unknown.
                return null;
            }
        }

        SparseBooleanArray intersection = new SparseBooleanArray();
        SparseBooleanArray firstVibratorEffects = infos[0].getSupportedEffects();

        effectIdLoop:
        for (int i = 0; i < firstVibratorEffects.size(); i++) {
            int effectId = firstVibratorEffects.keyAt(i);
            if (!firstVibratorEffects.valueAt(i)) {
                // The first vibrator already doesn't support this effect, so skip it.
                continue effectIdLoop;
            }

            for (int j = 1; j < infos.length; j++) {
                if (infos[j].isEffectSupported(effectId) != Vibrator.VIBRATION_EFFECT_SUPPORT_YES) {
                    // One vibrator doesn't support this effect, so the intersection doesn't.
                    continue effectIdLoop;
                }
            }

            intersection.put(effectId, true);
        }

        return intersection;
    }

    @NonNull
    private static SparseIntArray supportedPrimitivesAndDurationsIntersection(
            VibratorInfo[] infos) {
        SparseIntArray intersection = new SparseIntArray();
        SparseIntArray firstVibratorPrimitives = infos[0].getSupportedPrimitives();

        primitiveIdLoop:
        for (int i = 0; i < firstVibratorPrimitives.size(); i++) {
            int primitiveId = firstVibratorPrimitives.keyAt(i);
            int primitiveDuration = firstVibratorPrimitives.valueAt(i);
            if (primitiveDuration == 0) {
                // The first vibrator already doesn't support this primitive, so skip it.
                continue primitiveIdLoop;
            }

            for (int j = 1; j < infos.length; j++) {
                int vibratorPrimitiveDuration = infos[j].getPrimitiveDuration(primitiveId);
                if (vibratorPrimitiveDuration == 0) {
                    // One vibrator doesn't support this primitive, so the intersection doesn't.
                    continue primitiveIdLoop;
                } else {
                    // The primitive vibration duration is the maximum among all vibrators.
                    primitiveDuration = Math.max(primitiveDuration, vibratorPrimitiveDuration);
                }
            }

            intersection.put(primitiveId, primitiveDuration);
        }
        return intersection;
    }

    private static int integerLimitIntersection(VibratorInfo[] infos,
            Function<VibratorInfo, Integer> propertyGetter) {
        int limit = 0; // Limit 0 means unlimited
        for (VibratorInfo info : infos) {
            int vibratorLimit = propertyGetter.apply(info);
            if ((limit == 0) || (vibratorLimit > 0 && vibratorLimit < limit)) {
                // This vibrator is limited and intersection is unlimited or has a larger limit:
                // use smaller limit here for the intersection.
                limit = vibratorLimit;
            }
        }
        return limit;
    }

    private static float floatPropertyIntersection(VibratorInfo[] infos,
            Function<VibratorInfo, Float> propertyGetter) {
        float property = propertyGetter.apply(infos[0]);
        if (Float.isNaN(property)) {
            // If one vibrator is undefined then the intersection is undefined.
            return Float.NaN;
        }
        for (int i = 1; i < infos.length; i++) {
            if (Float.compare(property, propertyGetter.apply(infos[i])) != 0) {
                // If one vibrator has a different value then the intersection is undefined.
                return Float.NaN;
            }
        }
        return property;
    }

    @NonNull
    private static FrequencyProfile frequencyProfileIntersection(VibratorInfo[] infos) {
        float freqResolution = floatPropertyIntersection(infos,
                info -> info.getFrequencyProfile().getFrequencyResolutionHz());
        float resonantFreq = floatPropertyIntersection(infos,
                VibratorInfo::getResonantFrequencyHz);
        Range<Float> freqRange = frequencyRangeIntersection(infos, freqResolution);

        if ((freqRange == null) || Float.isNaN(freqResolution)) {
            return new FrequencyProfile(resonantFreq, Float.NaN, freqResolution, null);
        }

        int amplitudeCount =
                Math.round(1 + (freqRange.getUpper() - freqRange.getLower()) / freqResolution);
        float[] maxAmplitudes = new float[amplitudeCount];

        // Use MAX_VALUE here to ensure that the FrequencyProfile constructor called with this
        // will fail if the loop below is broken and do not replace filled values with actual
        // vibrator measurements.
        Arrays.fill(maxAmplitudes, Float.MAX_VALUE);

        for (VibratorInfo info : infos) {
            Range<Float> vibratorFreqRange = info.getFrequencyProfile().getFrequencyRangeHz();
            float[] vibratorMaxAmplitudes = info.getFrequencyProfile().getMaxAmplitudes();
            int vibratorStartIdx = Math.round(
                    (freqRange.getLower() - vibratorFreqRange.getLower()) / freqResolution);
            int vibratorEndIdx = vibratorStartIdx + maxAmplitudes.length - 1;

            if ((vibratorStartIdx < 0) || (vibratorEndIdx >= vibratorMaxAmplitudes.length)) {
                Slog.w(TAG, "Error calculating the intersection of vibrator frequency"
                        + " profiles: attempted to fetch from vibrator "
                        + info.getId() + " max amplitude with bad index " + vibratorStartIdx);
                return new FrequencyProfile(resonantFreq, Float.NaN, Float.NaN, null);
            }

            for (int i = 0; i < maxAmplitudes.length; i++) {
                maxAmplitudes[i] = Math.min(maxAmplitudes[i],
                        vibratorMaxAmplitudes[vibratorStartIdx + i]);
            }
        }

        return new FrequencyProfile(resonantFreq, freqRange.getLower(),
                freqResolution, maxAmplitudes);
    }

    @Nullable
    private static Range<Float> frequencyRangeIntersection(VibratorInfo[] infos,
            float frequencyResolution) {
        Range<Float> firstRange = infos[0].getFrequencyProfile().getFrequencyRangeHz();
        if (firstRange == null) {
            // If one vibrator is undefined then the intersection is undefined.
            return null;
        }
        float intersectionLower = firstRange.getLower();
        float intersectionUpper = firstRange.getUpper();

        // Generate the intersection of all vibrator supported ranges, making sure that both
        // min supported frequencies are aligned w.r.t. the frequency resolution.

        for (int i = 1; i < infos.length; i++) {
            Range<Float> vibratorRange = infos[i].getFrequencyProfile().getFrequencyRangeHz();
            if (vibratorRange == null) {
                // If one vibrator is undefined then the intersection is undefined.
                return null;
            }

            if ((vibratorRange.getLower() >= intersectionUpper)
                    || (vibratorRange.getUpper() <= intersectionLower)) {
                // If the range and intersection are disjoint then the intersection is undefined
                return null;
            }

            float frequencyDelta = Math.abs(intersectionLower - vibratorRange.getLower());
            if ((frequencyDelta % frequencyResolution) > EPSILON) {
                // If the intersection is not aligned with one vibrator then it's undefined
                return null;
            }

            intersectionLower = Math.max(intersectionLower, vibratorRange.getLower());
            intersectionUpper = Math.min(intersectionUpper, vibratorRange.getUpper());
        }

        if ((intersectionUpper - intersectionLower) < frequencyResolution) {
            // If the intersection is empty then it's undefined.
            return null;
        }

        return Range.create(intersectionLower, intersectionUpper);
    }
}
