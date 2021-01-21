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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.hardware.vibrator.IVibrator;
import android.os.CombinedVibrationEffect;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.test.TestLooper;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.internal.app.IBatteryStats;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link VibrationThread}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibrationThreadTest
 */
@Presubmit
public class VibrationThreadTest {

    private static final int TEST_TIMEOUT_MILLIS = 1_000;
    private static final int UID = Process.ROOT_UID;
    private static final int VIBRATOR_ID = 1;
    private static final String PACKAGE_NAME = "package";
    private static final VibrationAttributes ATTRS = new VibrationAttributes.Builder().build();

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock private VibrationThread.VibrationCallbacks mThreadCallbacks;
    @Mock private VibratorController.OnVibrationCompleteListener mControllerCallbacks;
    @Mock private IBinder mVibrationToken;
    @Mock private IBatteryStats mIBatteryStatsMock;

    private final Map<Integer, FakeVibratorControllerProvider> mVibratorProviders = new HashMap<>();
    private PowerManager.WakeLock mWakeLock;
    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mWakeLock = InstrumentationRegistry.getContext().getSystemService(
                PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "*vibrator*");

        mockVibrators(VIBRATOR_ID);
    }

    @Test
    public void vibrate_noVibrator_ignoresVibration() {
        mVibratorProviders.clear();
        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mControllerCallbacks, never()).onComplete(anyInt(), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.IGNORED));
    }

    @Test
    public void vibrate_missingVibrators_ignoresVibration() {
        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSequential()
                .addNext(2, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addNext(3, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mControllerCallbacks, never()).onComplete(anyInt(), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.IGNORED));
    }

    @Test
    public void vibrate_singleVibratorOneShot_runsVibrationAndSetsAmplitude() throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createOneShot(10, 100);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(10L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(VIBRATOR_ID).getEffects());
        assertEquals(Arrays.asList(100), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_oneShotWithoutAmplitudeControl_runsVibrationWithDefaultAmplitude()
            throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createOneShot(10, 100);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(10L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(VIBRATOR_ID).getEffects());
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getAmplitudes().isEmpty());
    }

    @Test
    public void vibrate_singleVibratorWaveform_runsVibrationAndChangesAmplitudes()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(
                new long[]{5, 5, 5}, new int[]{1, 2, 3}, -1);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(15L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(15)),
                mVibratorProviders.get(VIBRATOR_ID).getEffects());
        assertEquals(Arrays.asList(1, 2, 3), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_singleVibratorRepeatingWaveform_runsVibrationUntilThreadCancelled()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        int[] amplitudes = new int[]{1, 2, 3};
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5, 5, 5}, amplitudes, 0);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(35);
        // Vibration still running after 2 cycles.
        assertTrue(thread.isAlive());
        assertTrue(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        thread.cancel();
        waitForCompletion(thread);

        verify(mIBatteryStatsMock, never()).noteVibratorOn(eq(UID), anyLong());
        verify(mIBatteryStatsMock, never()).noteVibratorOff(eq(UID));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        List<Integer> playedAmplitudes = mVibratorProviders.get(VIBRATOR_ID).getAmplitudes();
        assertFalse(mVibratorProviders.get(VIBRATOR_ID).getEffects().isEmpty());
        assertFalse(playedAmplitudes.isEmpty());

        for (int i = 0; i < playedAmplitudes.size(); i++) {
            assertEquals(amplitudes[i % amplitudes.length], playedAmplitudes.get(i).intValue());
        }
    }

    @Test
    public void vibrate_singleVibratorPredefinedCancel_cancelsVibrationImmediately()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                .compose();
        VibrationThread vibrationThread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(20);
        assertTrue(vibrationThread.isAlive());
        assertTrue(vibrationThread.getVibrators().get(VIBRATOR_ID).isVibrating());

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread = new Thread(() -> vibrationThread.cancel());
        cancellingThread.start();

        waitForCompletion(vibrationThread, 20);
        waitForCompletion(cancellingThread);

        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(vibrationThread.getVibrators().get(VIBRATOR_ID).isVibrating());
    }

    @Test
    public void vibrate_singleVibratorWaveformCancel_cancelsVibrationImmediately()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{100}, new int[]{100}, 0);
        VibrationThread vibrationThread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(20);
        assertTrue(vibrationThread.isAlive());
        assertTrue(vibrationThread.getVibrators().get(VIBRATOR_ID).isVibrating());

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread = new Thread(() -> vibrationThread.cancel());
        cancellingThread.start();

        waitForCompletion(vibrationThread, 20);
        waitForCompletion(cancellingThread);

        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(vibrationThread.getVibrators().get(VIBRATOR_ID).isVibrating());
    }

    @Test
    public void vibrate_singleVibratorPrebaked_runsVibration() throws Exception {
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_THUD);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_THUD);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(20L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_THUD)),
                mVibratorProviders.get(VIBRATOR_ID).getEffects());
    }

    @Test
    public void vibrate_singleVibratorPrebakedAndUnsupportedEffectWithFallback_runsFallback()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect fallback = VibrationEffect.createOneShot(10, 100);
        VibrationEffect.Prebaked effect = new VibrationEffect.Prebaked(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_STRENGTH_STRONG, fallback);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(10L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(VIBRATOR_ID).getEffects());
        assertEquals(Arrays.asList(100), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_singleVibratorPrebakedAndUnsupportedEffect_ignoresVibration()
            throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock, never()).noteVibratorOn(eq(UID), anyLong());
        verify(mIBatteryStatsMock, never()).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks, never()).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId),
                eq(Vibration.Status.IGNORED_UNSUPPORTED));
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getEffects().isEmpty());
    }

    @Test
    public void vibrate_singleVibratorComposed_runsVibration() throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f)
                .compose();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(40L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());
        assertEquals(Arrays.asList(effect), mVibratorProviders.get(VIBRATOR_ID).getEffects());
    }

    @Test
    public void vibrate_singleVibratorComposedAndNoCapability_ignoresVibration() throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .compose();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock, never()).noteVibratorOn(eq(UID), anyLong());
        verify(mIBatteryStatsMock, never()).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks, never()).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId),
                eq(Vibration.Status.IGNORED_UNSUPPORTED));
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getEffects().isEmpty());
    }

    @Test
    public void vibrate_singleVibratorCancelled_vibratorStopped() throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5}, new int[]{100}, 0);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(15);
        // Vibration still running after 2 cycles.
        assertTrue(thread.isAlive());
        assertTrue(thread.getVibrators().get(1).isVibrating());

        thread.binderDied();
        waitForCompletion(thread);
        assertFalse(thread.getVibrators().get(1).isVibrating());

        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
    }

    @Test
    public void vibrate_multipleExistingAndMissingVibrators_vibratesOnlyExistingOnes()
            throws Exception {
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_TICK);

        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(VIBRATOR_ID, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .addVibrator(2, VibrationEffect.get(VibrationEffect.EFFECT_TICK))
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(20L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mControllerCallbacks, never()).onComplete(eq(2), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_TICK)),
                mVibratorProviders.get(VIBRATOR_ID).getEffects());
    }

    @Test
    public void vibrate_multipleMono_runsSameEffectInAllVibrators() throws Exception {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(2).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(3).setSupportedEffects(VibrationEffect.EFFECT_CLICK);

        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK));
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(20L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(1).isVibrating());
        assertFalse(thread.getVibrators().get(2).isVibrating());
        assertFalse(thread.getVibrators().get(3).isVibrating());

        VibrationEffect expected = expectedPrebaked(VibrationEffect.EFFECT_CLICK);
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(1).getEffects());
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(2).getEffects());
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(3).getEffects());
    }

    @Test
    public void vibrate_multipleStereo_runsVibrationOnRightVibrators() throws Exception {
        mockVibrators(1, 2, 3, 4);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(3).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(4).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createOneShot(10, 100))
                .addVibrator(3, VibrationEffect.createWaveform(
                        new long[]{10, 10}, new int[]{1, 2}, -1))
                .addVibrator(4, composed)
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(20L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(4), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(1).isVibrating());
        assertFalse(thread.getVibrators().get(2).isVibrating());
        assertFalse(thread.getVibrators().get(3).isVibrating());
        assertFalse(thread.getVibrators().get(4).isVibrating());

        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                mVibratorProviders.get(1).getEffects());
        assertEquals(Arrays.asList(expectedOneShot(10)), mVibratorProviders.get(2).getEffects());
        assertEquals(Arrays.asList(100), mVibratorProviders.get(2).getAmplitudes());
        assertEquals(Arrays.asList(expectedOneShot(20)), mVibratorProviders.get(3).getEffects());
        assertEquals(Arrays.asList(1, 2), mVibratorProviders.get(3).getAmplitudes());
        assertEquals(Arrays.asList(composed), mVibratorProviders.get(4).getEffects());
    }

    @Test
    public void vibrate_multipleSequential_runsVibrationInOrderWithDelays()
            throws Exception {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(3).setSupportedEffects(VibrationEffect.EFFECT_CLICK);

        long vibrationId = 1;
        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSequential()
                .addNext(3, VibrationEffect.get(VibrationEffect.EFFECT_CLICK), /* delay= */ 50)
                .addNext(1, VibrationEffect.createOneShot(10, 100), /* delay= */ 50)
                .addNext(2, composed, /* delay= */ 50)
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        waitForCompletion(thread);
        InOrder controllerVerifier = inOrder(mControllerCallbacks);
        controllerVerifier.verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        controllerVerifier.verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        controllerVerifier.verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));

        InOrder batterVerifier = inOrder(mIBatteryStatsMock);
        batterVerifier.verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(20L));
        batterVerifier.verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        batterVerifier.verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(10L));
        batterVerifier.verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        batterVerifier.verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(20L));
        batterVerifier.verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));

        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(1).isVibrating());
        assertFalse(thread.getVibrators().get(2).isVibrating());
        assertFalse(thread.getVibrators().get(3).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)), mVibratorProviders.get(1).getEffects());
        assertEquals(Arrays.asList(100), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(Arrays.asList(composed), mVibratorProviders.get(2).getEffects());
        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                mVibratorProviders.get(3).getEffects());
    }

    @Test
    public void vibrate_multipleWaveforms_playsWaveformsInParallel() throws Exception {
        mockVibrators(1, 2, 3);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(3).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.createWaveform(
                        new long[]{5, 10, 10}, new int[]{1, 2, 3}, -1))
                .addVibrator(2, VibrationEffect.createWaveform(
                        new long[]{20, 60}, new int[]{4, 5}, -1))
                .addVibrator(3, VibrationEffect.createWaveform(
                        new long[]{60}, new int[]{6}, -1))
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(40);
        // First waveform has finished.
        verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        assertEquals(Arrays.asList(1, 2, 3), mVibratorProviders.get(1).getAmplitudes());
        // Second waveform is halfway through.
        assertEquals(Arrays.asList(4, 5), mVibratorProviders.get(2).getAmplitudes());
        // Third waveform is almost ending.
        assertEquals(Arrays.asList(6), mVibratorProviders.get(3).getAmplitudes());

        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(80L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(1).isVibrating());
        assertFalse(thread.getVibrators().get(2).isVibrating());
        assertFalse(thread.getVibrators().get(3).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(25)), mVibratorProviders.get(1).getEffects());
        assertEquals(Arrays.asList(expectedOneShot(80)), mVibratorProviders.get(2).getEffects());
        assertEquals(Arrays.asList(expectedOneShot(60)), mVibratorProviders.get(3).getEffects());
        assertEquals(Arrays.asList(1, 2, 3), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(Arrays.asList(4, 5), mVibratorProviders.get(2).getAmplitudes());
        assertEquals(Arrays.asList(6), mVibratorProviders.get(3).getAmplitudes());
    }

    @Test
    public void vibrate_withWaveform_totalVibrationTimeRespected() {
        int totalDuration = 10_000; // 10s
        int stepDuration = 25; // 25ms

        // 25% of the first waveform step will be spent on the native on() call.
        // 25% of each waveform step will be spent on the native setAmplitude() call..
        mVibratorProviders.get(VIBRATOR_ID).setLatency(stepDuration / 4);
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        int stepCount = totalDuration / stepDuration;
        long[] timings = new long[stepCount];
        int[] amplitudes = new int[stepCount];
        Arrays.fill(timings, stepDuration);
        Arrays.fill(amplitudes, VibrationEffect.DEFAULT_AMPLITUDE);
        VibrationEffect effect = VibrationEffect.createWaveform(timings, amplitudes, -1);

        long vibrationId = 1;
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        long startTime = SystemClock.elapsedRealtime();

        waitForCompletion(thread, totalDuration + TEST_TIMEOUT_MILLIS);
        long delay = Math.abs(SystemClock.elapsedRealtime() - startTime - totalDuration);

        // Allow some delay for thread scheduling and callback triggering.
        int maxDelay = (int) (0.05 * totalDuration); // < 5% of total duration
        assertTrue("Waveform with perceived delay of " + delay + "ms,"
                        + " expected less than " + maxDelay + "ms",
                delay < maxDelay);
    }

    @Test
    public void vibrate_multiplePredefinedCancel_cancelsVibrationImmediately() throws Exception {
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);

        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 100)
                        .compose())
                .combine();
        VibrationThread vibrationThread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(10);
        assertTrue(vibrationThread.isAlive());
        assertTrue(vibrationThread.getVibrators().get(1).isVibrating());
        assertTrue(vibrationThread.getVibrators().get(2).isVibrating());

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread = new Thread(() -> vibrationThread.cancel());
        cancellingThread.start();

        waitForCompletion(vibrationThread, 20);
        waitForCompletion(cancellingThread);

        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(vibrationThread.getVibrators().get(1).isVibrating());
        assertFalse(vibrationThread.getVibrators().get(2).isVibrating());
    }

    @Test
    public void vibrate_multipleWaveformCancel_cancelsVibrationImmediately() throws Exception {
        mockVibrators(1, 2);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.createWaveform(
                        new long[]{100, 100}, new int[]{1, 2}, 0))
                .addVibrator(2, VibrationEffect.createOneShot(100, 100))
                .combine();
        VibrationThread vibrationThread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(10);
        assertTrue(vibrationThread.isAlive());
        assertTrue(vibrationThread.getVibrators().get(1).isVibrating());
        assertTrue(vibrationThread.getVibrators().get(2).isVibrating());

        // Run cancel in a separate thread so if VibrationThread.cancel blocks then this test should
        // fail at waitForCompletion(vibrationThread) if the vibration not cancelled immediately.
        Thread cancellingThread = new Thread(() -> vibrationThread.cancel());
        cancellingThread.start();

        waitForCompletion(vibrationThread, 20);
        waitForCompletion(cancellingThread);

        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(vibrationThread.getVibrators().get(1).isVibrating());
        assertFalse(vibrationThread.getVibrators().get(2).isVibrating());
    }

    @Test
    public void vibrate_binderDied_cancelsVibration() throws Exception {
        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5}, new int[]{100}, 0);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        Thread.sleep(15);
        // Vibration still running after 2 cycles.
        assertTrue(thread.isAlive());
        assertTrue(thread.getVibrators().get(1).isVibrating());

        thread.binderDied();
        waitForCompletion(thread);

        verify(mVibrationToken).linkToDeath(same(thread), eq(0));
        verify(mVibrationToken).unlinkToDeath(same(thread), eq(0));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(mVibratorProviders.get(VIBRATOR_ID).getEffects().isEmpty());
        assertFalse(thread.getVibrators().get(1).isVibrating());
    }

    private void mockVibrators(int... vibratorIds) {
        for (int vibratorId : vibratorIds) {
            mVibratorProviders.put(vibratorId,
                    new FakeVibratorControllerProvider(mTestLooper.getLooper()));
        }
    }

    private VibrationThread startThreadAndDispatcher(long vibrationId, VibrationEffect effect) {
        return startThreadAndDispatcher(vibrationId, CombinedVibrationEffect.createSynced(effect));
    }

    private VibrationThread startThreadAndDispatcher(long vibrationId,
            CombinedVibrationEffect effect) {
        VibrationThread thread = new VibrationThread(createVibration(vibrationId, effect),
                createVibratorControllers(), mWakeLock, mIBatteryStatsMock, mThreadCallbacks);
        doAnswer(answer -> {
            thread.vibratorComplete(answer.getArgument(0));
            return null;
        }).when(mControllerCallbacks).onComplete(anyInt(), eq(vibrationId));
        mTestLooper.startAutoDispatch();
        thread.start();
        return thread;
    }

    private void waitForCompletion(Thread thread) {
        waitForCompletion(thread, TEST_TIMEOUT_MILLIS);
    }

    private void waitForCompletion(Thread thread, long timeout) {
        try {
            thread.join(timeout);
        } catch (InterruptedException e) {
        }
        assertFalse(thread.isAlive());
        mTestLooper.dispatchAll();
    }

    private Vibration createVibration(long id, CombinedVibrationEffect effect) {
        return new Vibration(mVibrationToken, (int) id, effect, ATTRS, UID, PACKAGE_NAME, "reason");
    }

    private SparseArray<VibratorController> createVibratorControllers() {
        SparseArray<VibratorController> array = new SparseArray<>();
        for (Map.Entry<Integer, FakeVibratorControllerProvider> e : mVibratorProviders.entrySet()) {
            int id = e.getKey();
            array.put(id, e.getValue().newVibratorController(id, mControllerCallbacks));
        }
        return array;
    }

    private VibrationEffect expectedOneShot(long millis) {
        return VibrationEffect.createOneShot(millis, VibrationEffect.DEFAULT_AMPLITUDE);
    }

    private VibrationEffect expectedPrebaked(int effectId) {
        return new VibrationEffect.Prebaked(effectId, false,
                VibrationEffect.EFFECT_STRENGTH_MEDIUM);
    }
}
