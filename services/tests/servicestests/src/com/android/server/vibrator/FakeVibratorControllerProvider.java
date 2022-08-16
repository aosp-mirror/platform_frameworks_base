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

package com.android.server.vibrator;

import android.annotation.Nullable;
import android.os.Handler;
import android.os.Looper;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;

import com.android.server.vibrator.VibratorController.OnVibrationCompleteListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Provides {@link VibratorController} with controlled vibrator hardware capabilities and
 * interactions.
 */
final class FakeVibratorControllerProvider {
    private static final int EFFECT_DURATION = 20;

    private final Map<Long, PrebakedSegment> mEnabledAlwaysOnEffects = new HashMap<>();
    private final Map<Long, List<VibrationEffectSegment>> mEffectSegments = new TreeMap<>();
    private final Map<Long, List<Integer>> mBraking = new HashMap<>();
    private final List<Float> mAmplitudes = new ArrayList<>();
    private final List<Boolean> mExternalControlStates = new ArrayList<>();
    private final Handler mHandler;
    private final FakeNativeWrapper mNativeWrapper;

    private boolean mIsAvailable = true;
    private boolean mIsInfoLoadSuccessful = true;
    private long mLatency;
    private int mOffCount;

    private int mCapabilities;
    private int[] mSupportedEffects;
    private int[] mSupportedBraking;
    private int[] mSupportedPrimitives;
    private int mCompositionSizeMax;
    private int mPwleSizeMax;
    private float mMinFrequency = Float.NaN;
    private float mResonantFrequency = Float.NaN;
    private float mFrequencyResolution = Float.NaN;
    private float mQFactor = Float.NaN;
    private float[] mMaxAmplitudes;

    void recordEffectSegment(long vibrationId, VibrationEffectSegment segment) {
        mEffectSegments.computeIfAbsent(vibrationId, k -> new ArrayList<>()).add(segment);
    }

    void recordBraking(long vibrationId, int braking) {
        mBraking.computeIfAbsent(vibrationId, k -> new ArrayList<>()).add(braking);
    }

    private final class FakeNativeWrapper extends VibratorController.NativeWrapper {
        public int vibratorId;
        public OnVibrationCompleteListener listener;
        public boolean isInitialized;

        @Override
        public void init(int vibratorId, OnVibrationCompleteListener listener) {
            isInitialized = true;
            this.vibratorId = vibratorId;
            this.listener = listener;
        }

        @Override
        public boolean isAvailable() {
            return mIsAvailable;
        }

        @Override
        public long on(long milliseconds, long vibrationId) {
            recordEffectSegment(vibrationId, new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE,
                    /* frequencyHz= */ 0, (int) milliseconds));
            applyLatency();
            scheduleListener(milliseconds, vibrationId);
            return milliseconds;
        }

        @Override
        public void off() {
            mOffCount++;
        }

        @Override
        public void setAmplitude(float amplitude) {
            mAmplitudes.add(amplitude);
            applyLatency();
        }

        @Override
        public long perform(long effect, long strength, long vibrationId) {
            if (mSupportedEffects == null
                    || Arrays.binarySearch(mSupportedEffects, (int) effect) < 0) {
                return 0;
            }
            recordEffectSegment(vibrationId,
                    new PrebakedSegment((int) effect, false, (int) strength));
            applyLatency();
            scheduleListener(EFFECT_DURATION, vibrationId);
            return EFFECT_DURATION;
        }

        @Override
        public long compose(PrimitiveSegment[] primitives, long vibrationId) {
            if (mSupportedPrimitives == null) {
                return 0;
            }
            for (PrimitiveSegment primitive : primitives) {
                if (Arrays.binarySearch(mSupportedPrimitives, primitive.getPrimitiveId()) < 0) {
                    return 0;
                }
            }
            long duration = 0;
            for (PrimitiveSegment primitive : primitives) {
                duration += EFFECT_DURATION + primitive.getDelay();
                recordEffectSegment(vibrationId, primitive);
            }
            applyLatency();
            scheduleListener(duration, vibrationId);
            return duration;
        }

        @Override
        public long composePwle(RampSegment[] primitives, int braking, long vibrationId) {
            long duration = 0;
            for (RampSegment primitive : primitives) {
                duration += primitive.getDuration();
                recordEffectSegment(vibrationId, primitive);
            }
            recordBraking(vibrationId, braking);
            applyLatency();
            scheduleListener(duration, vibrationId);
            return duration;
        }

        @Override
        public void setExternalControl(boolean enabled) {
            mExternalControlStates.add(enabled);
        }

        @Override
        public void alwaysOnEnable(long id, long effect, long strength) {
            PrebakedSegment prebaked = new PrebakedSegment((int) effect, false, (int) strength);
            mEnabledAlwaysOnEffects.put(id, prebaked);
        }

        @Override
        public void alwaysOnDisable(long id) {
            mEnabledAlwaysOnEffects.remove(id);
        }

        @Override
        public boolean getInfo(VibratorInfo.Builder infoBuilder) {
            infoBuilder.setCapabilities(mCapabilities);
            infoBuilder.setSupportedBraking(mSupportedBraking);
            infoBuilder.setPwleSizeMax(mPwleSizeMax);
            infoBuilder.setSupportedEffects(mSupportedEffects);
            if (mSupportedPrimitives != null) {
                for (int primitive : mSupportedPrimitives) {
                    infoBuilder.setSupportedPrimitive(primitive, EFFECT_DURATION);
                }
            }
            infoBuilder.setCompositionSizeMax(mCompositionSizeMax);
            infoBuilder.setQFactor(mQFactor);
            infoBuilder.setFrequencyProfile(new VibratorInfo.FrequencyProfile(
                    mResonantFrequency, mMinFrequency, mFrequencyResolution, mMaxAmplitudes));
            return mIsInfoLoadSuccessful;
        }

        private void applyLatency() {
            try {
                if (mLatency > 0) {
                    Thread.sleep(mLatency);
                }
            } catch (InterruptedException e) {
            }
        }

        private void scheduleListener(long vibrationDuration, long vibrationId) {
            mHandler.postDelayed(() -> listener.onComplete(vibratorId, vibrationId),
                    vibrationDuration);
        }
    }

    public FakeVibratorControllerProvider(Looper looper) {
        mHandler = new Handler(looper);
        mNativeWrapper = new FakeNativeWrapper();
    }

    public VibratorController newVibratorController(
            int vibratorId, OnVibrationCompleteListener listener) {
        return new VibratorController(vibratorId, listener, mNativeWrapper);
    }

    /** Return {@code true} if this controller was initialized. */
    public boolean isInitialized() {
        return mNativeWrapper.isInitialized;
    }

    /**
     * Disable fake vibrator hardware, mocking a state where the underlying service is unavailable.
     */
    public void disableVibrators() {
        mIsAvailable = false;
    }

    /**
     * Sets the result for the method that loads the {@link VibratorInfo}, for faking a vibrator
     * that fails to load some of the hardware data.
     */
    public void setVibratorInfoLoadSuccessful(boolean successful) {
        mIsInfoLoadSuccessful = successful;
    }

    /**
     * Sets the latency this controller should fake for turning the vibrator hardware on or setting
     * it's vibration amplitude.
     */
    public void setLatency(long millis) {
        mLatency = millis;
    }

    /** Set the capabilities of the fake vibrator hardware. */
    public void setCapabilities(int... capabilities) {
        mCapabilities = Arrays.stream(capabilities).reduce(0, (a, b) -> a | b);
    }

    /** Set the effects supported by the fake vibrator hardware. */
    public void setSupportedEffects(int... effects) {
        if (effects != null) {
            effects = Arrays.copyOf(effects, effects.length);
            Arrays.sort(effects);
        }
        mSupportedEffects = effects;
    }

    /** Set the effects supported by the fake vibrator hardware. */
    public void setSupportedBraking(int... braking) {
        if (braking != null) {
            braking = Arrays.copyOf(braking, braking.length);
            Arrays.sort(braking);
        }
        mSupportedBraking = braking;
    }

    /** Set the primitives supported by the fake vibrator hardware. */
    public void setSupportedPrimitives(int... primitives) {
        if (primitives != null) {
            primitives = Arrays.copyOf(primitives, primitives.length);
            Arrays.sort(primitives);
        }
        mSupportedPrimitives = primitives;
    }

    /** Set the max number of primitives allowed in a composition by the fake vibrator hardware. */
    public void setCompositionSizeMax(int compositionSizeMax) {
        mCompositionSizeMax = compositionSizeMax;
    }

    /** Set the max number of PWLEs allowed in a composition by the fake vibrator hardware. */
    public void setPwleSizeMax(int pwleSizeMax) {
        mPwleSizeMax = pwleSizeMax;
    }

    /** Set the resonant frequency of the fake vibrator hardware. */
    public void setResonantFrequency(float frequencyHz) {
        mResonantFrequency = frequencyHz;
    }

    /** Set the minimum frequency of the fake vibrator hardware. */
    public void setMinFrequency(float frequencyHz) {
        mMinFrequency = frequencyHz;
    }

    /** Set the frequency resolution of the fake vibrator hardware. */
    public void setFrequencyResolution(float frequencyHz) {
        mFrequencyResolution = frequencyHz;
    }

    /** Set the Q factor of the fake vibrator hardware. */
    public void setQFactor(float qFactor) {
        mQFactor = qFactor;
    }

    /** Set the max amplitude supported for each frequency f the fake vibrator hardware. */
    public void setMaxAmplitudes(float... maxAmplitudes) {
        mMaxAmplitudes = maxAmplitudes;
    }

    /**
     * Return the amplitudes set by this controller, including zeroes for each time the vibrator was
     * turned off.
     */
    public List<Float> getAmplitudes() {
        return new ArrayList<>(mAmplitudes);
    }

    /** Return the braking values passed to the compose PWLE method. */
    public List<Integer> getBraking(long vibrationId) {
        if (mBraking.containsKey(vibrationId)) {
            return new ArrayList<>(mBraking.get(vibrationId));
        } else {
            return new ArrayList<>();
        }
    }

    /** Return list of {@link VibrationEffectSegment} played by this controller, in order. */
    public List<VibrationEffectSegment> getEffectSegments(long vibrationId) {
        if (mEffectSegments.containsKey(vibrationId)) {
            return new ArrayList<>(mEffectSegments.get(vibrationId));
        } else {
            return new ArrayList<>();
        }
    }

    /**
     * Returns a list of all vibrations' effect segments, for external-use where vibration IDs
     * aren't exposed.
     */
    public List<VibrationEffectSegment> getAllEffectSegments() {
        // Returns segments in order of vibrationId, which increases over time. TreeMap gives order.
        ArrayList<VibrationEffectSegment> result = new ArrayList<>();
        for (List<VibrationEffectSegment> subList : mEffectSegments.values()) {
            result.addAll(subList);
        }
        return result;
    }
    /** Return list of states set for external control to the fake vibrator hardware. */
    public List<Boolean> getExternalControlStates() {
        return mExternalControlStates;
    }

    /** Returns the number of times the vibrator was turned off. */
    public int getOffCount() {
        return mOffCount;
    }

    /**
     * Return the {@link PrebakedSegment} effect enabled with given id, or {@code null} if
     * missing or disabled.
     */
    @Nullable
    public PrebakedSegment getAlwaysOnEffect(int id) {
        return mEnabledAlwaysOnEffects.get((long) id);
    }
}
