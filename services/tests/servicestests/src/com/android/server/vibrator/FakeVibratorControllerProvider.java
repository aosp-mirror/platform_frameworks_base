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

import com.android.server.vibrator.VibratorController.OnVibrationCompleteListener;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Provides {@link VibratorController} with controlled vibrator hardware capabilities and
 * interactions.
 */
final class FakeVibratorControllerProvider {

    private static final int EFFECT_DURATION = 20;

    private final Map<Long, VibrationEffect.Prebaked> mEnabledAlwaysOnEffects = new HashMap<>();
    private final List<VibrationEffect> mEffects = new ArrayList<>();
    private final List<Integer> mAmplitudes = new ArrayList<>();
    private final Handler mHandler;
    private final FakeNativeWrapper mNativeWrapper;

    private boolean mIsAvailable = true;
    private long mLatency;

    private int mCapabilities;
    private int[] mSupportedEffects;
    private int[] mSupportedPrimitives;

    private final class FakeNativeWrapper extends VibratorController.NativeWrapper {
        public int vibratorId;
        public OnVibrationCompleteListener listener;
        public boolean isInitialized;

        public void init(int vibratorId, OnVibrationCompleteListener listener) {
            isInitialized = true;
            this.vibratorId = vibratorId;
            this.listener = listener;
        }

        public boolean isAvailable() {
            return mIsAvailable;
        }

        public void on(long milliseconds, long vibrationId) {
            VibrationEffect effect = VibrationEffect.createOneShot(
                    milliseconds, VibrationEffect.DEFAULT_AMPLITUDE);
            mEffects.add(effect);
            applyLatency();
            scheduleListener(milliseconds, vibrationId);
        }

        public void off() {
        }

        public void setAmplitude(int amplitude) {
            mAmplitudes.add(amplitude);
            applyLatency();
        }

        public int[] getSupportedEffects() {
            return mSupportedEffects;
        }

        public int[] getSupportedPrimitives() {
            return mSupportedPrimitives;
        }

        public long perform(long effect, long strength, long vibrationId) {
            if (mSupportedEffects == null
                    || Arrays.binarySearch(mSupportedEffects, (int) effect) < 0) {
                return 0;
            }
            mEffects.add(new VibrationEffect.Prebaked((int) effect, false, (int) strength));
            applyLatency();
            scheduleListener(EFFECT_DURATION, vibrationId);
            return EFFECT_DURATION;
        }

        public long compose(VibrationEffect.Composition.PrimitiveEffect[] effect,
                long vibrationId) {
            VibrationEffect.Composed composed = new VibrationEffect.Composed(Arrays.asList(effect));
            mEffects.add(composed);
            applyLatency();
            long duration = 0;
            for (VibrationEffect.Composition.PrimitiveEffect e : effect) {
                duration += EFFECT_DURATION + e.delay;
            }
            scheduleListener(duration, vibrationId);
            return duration;
        }

        public void setExternalControl(boolean enabled) {
        }

        public long getCapabilities() {
            return mCapabilities;
        }

        public void alwaysOnEnable(long id, long effect, long strength) {
            VibrationEffect.Prebaked prebaked = new VibrationEffect.Prebaked((int) effect, false,
                    (int) strength);
            mEnabledAlwaysOnEffects.put(id, prebaked);
        }

        public void alwaysOnDisable(long id) {
            mEnabledAlwaysOnEffects.remove(id);
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

    /** Set the primitives supported by the fake vibrator hardware. */
    public void setSupportedPrimitives(int... primitives) {
        if (primitives != null) {
            primitives = Arrays.copyOf(primitives, primitives.length);
            Arrays.sort(primitives);
        }
        mSupportedPrimitives = primitives;
    }

    /**
     * Return the amplitudes set by this controller, including zeroes for each time the vibrator was
     * turned off.
     */
    public List<Integer> getAmplitudes() {
        return new ArrayList<>(mAmplitudes);
    }

    /** Return list of {@link VibrationEffect} played by this controller, in order. */
    public List<VibrationEffect> getEffects() {
        return new ArrayList<>(mEffects);
    }

    /**
     * Return the {@link VibrationEffect.Prebaked} effect enabled with given id, or {@code null} if
     * missing or disabled.
     */
    @Nullable
    public VibrationEffect.Prebaked getAlwaysOnEffect(int id) {
        return mEnabledAlwaysOnEffects.get((long) id);
    }
}
