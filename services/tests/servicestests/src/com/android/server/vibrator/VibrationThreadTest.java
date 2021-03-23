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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.vibrator.IVibrator;
import android.hardware.vibrator.IVibratorManager;
import android.os.CombinedVibrationEffect;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.os.vibrator.VibrationEffectSegment;
import android.platform.test.annotations.LargeTest;
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
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId),
                eq(Vibration.Status.IGNORED_UNSUPPORTED));
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
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId),
                eq(Vibration.Status.IGNORED_UNSUPPORTED));
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
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments());
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
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
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments());
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
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments());
        assertEquals(expectedAmplitudes(1, 2, 3),
                mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
    }

    @Test
    public void vibrate_singleVibratorRepeatingWaveform_runsVibrationUntilThreadCancelled()
            throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        int[] amplitudes = new int[]{1, 2, 3};
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5, 5, 5}, amplitudes, 0);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(
                waitUntil(t -> fakeVibrator.getAmplitudes().size() > 2 * amplitudes.length,
                        thread, TEST_TIMEOUT_MILLIS));
        // Vibration still running after 2 cycles.
        assertTrue(thread.isAlive());
        assertTrue(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        thread.cancel();
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), anyLong());
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        List<Float> playedAmplitudes = fakeVibrator.getAmplitudes();
        assertFalse(fakeVibrator.getEffectSegments().isEmpty());
        assertFalse(playedAmplitudes.isEmpty());

        for (int i = 0; i < playedAmplitudes.size(); i++) {
            assertEquals(amplitudes[i % amplitudes.length] / 255f, playedAmplitudes.get(i), 1e-5);
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

        assertTrue(waitUntil(t -> t.getVibrators().get(VIBRATOR_ID).isVibrating(), vibrationThread,
                TEST_TIMEOUT_MILLIS));
        assertTrue(vibrationThread.isAlive());

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

        assertTrue(waitUntil(t -> t.getVibrators().get(VIBRATOR_ID).isVibrating(), vibrationThread,
                TEST_TIMEOUT_MILLIS));
        assertTrue(vibrationThread.isAlive());

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
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments());
    }

    @Test
    public void vibrate_singleVibratorPrebakedAndUnsupportedEffectWithFallback_runsFallback()
            throws Exception {
        mVibratorProviders.get(VIBRATOR_ID).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect fallback = VibrationEffect.createOneShot(10, 100);
        Vibration vibration = createVibration(vibrationId, CombinedVibrationEffect.createSynced(
                VibrationEffect.get(VibrationEffect.EFFECT_CLICK)));
        vibration.addFallback(VibrationEffect.EFFECT_CLICK, fallback);
        VibrationThread thread = startThreadAndDispatcher(vibration);
        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(10L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(VIBRATOR_ID), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments());
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(VIBRATOR_ID).getAmplitudes());
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
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getEffectSegments().isEmpty());
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
        assertEquals(Arrays.asList(
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0),
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.5f, 0)),
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments());
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
        assertTrue(mVibratorProviders.get(VIBRATOR_ID).getEffectSegments().isEmpty());
    }

    @Test
    public void vibrate_singleVibratorCancelled_vibratorStopped() throws Exception {
        FakeVibratorControllerProvider fakeVibrator = mVibratorProviders.get(VIBRATOR_ID);
        fakeVibrator.setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        VibrationEffect effect = VibrationEffect.createWaveform(new long[]{5}, new int[]{100}, 0);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(t -> fakeVibrator.getAmplitudes().size() > 2, thread,
                TEST_TIMEOUT_MILLIS));
        // Vibration still running after 2 cycles.
        assertTrue(thread.isAlive());
        assertTrue(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        thread.binderDied();
        waitForCompletion(thread);
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());

        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
    }

    @Test
    public void vibrate_singleVibrator_skipsSyncedCallbacks() {
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);

        long vibrationId = 1;
        waitForCompletion(startThreadAndDispatcher(vibrationId++,
                VibrationEffect.createOneShot(10, 100)));

        verify(mThreadCallbacks).onVibrationEnded(anyLong(), eq(Vibration.Status.FINISHED));
        verify(mThreadCallbacks, never()).prepareSyncedVibration(anyLong(), any());
        verify(mThreadCallbacks, never()).triggerSyncedVibration(anyLong());
        verify(mThreadCallbacks, never()).cancelSyncedVibration();
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
                mVibratorProviders.get(VIBRATOR_ID).getEffectSegments());
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

        VibrationEffectSegment expected = expectedPrebaked(VibrationEffect.EFFECT_CLICK);
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(1).getEffectSegments());
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(2).getEffectSegments());
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(3).getEffectSegments());
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
                mVibratorProviders.get(1).getEffectSegments());
        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(2).getEffectSegments());
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(2).getAmplitudes());
        assertEquals(Arrays.asList(expectedOneShot(20)),
                mVibratorProviders.get(3).getEffectSegments());
        assertEquals(expectedAmplitudes(1, 2), mVibratorProviders.get(3).getAmplitudes());
        assertEquals(Arrays.asList(
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0)),
                mVibratorProviders.get(4).getEffectSegments());
    }

    @Test
    public void vibrate_multipleSequential_runsVibrationInOrderWithDelays() throws Exception {
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

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(1).getEffectSegments());
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(Arrays.asList(
                expectedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 0)),
                mVibratorProviders.get(2).getEffectSegments());
        assertEquals(Arrays.asList(expectedPrebaked(VibrationEffect.EFFECT_CLICK)),
                mVibratorProviders.get(3).getEffectSegments());
    }

    @Test
    public void vibrate_multipleSyncedCallbackTriggered_finishSteps() throws Exception {
        int[] vibratorIds = new int[]{1, 2};
        long vibrationId = 1;
        mockVibrators(vibratorIds);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        when(mThreadCallbacks.prepareSyncedVibration(anyLong(), eq(vibratorIds))).thenReturn(true);
        when(mThreadCallbacks.triggerSyncedVibration(eq(vibrationId))).thenReturn(true);

        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 100)
                .compose();
        CombinedVibrationEffect effect = CombinedVibrationEffect.createSynced(composed);
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);

        assertTrue(waitUntil(t -> !mVibratorProviders.get(1).getEffectSegments().isEmpty()
                        && !mVibratorProviders.get(2).getEffectSegments().isEmpty(), thread,
                TEST_TIMEOUT_MILLIS));
        thread.syncedVibrationComplete();
        waitForCompletion(thread);

        long expectedCap = IVibratorManager.CAP_SYNC | IVibratorManager.CAP_PREPARE_COMPOSE;
        verify(mThreadCallbacks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mThreadCallbacks).triggerSyncedVibration(eq(vibrationId));
        verify(mThreadCallbacks, never()).cancelSyncedVibration();
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));

        VibrationEffectSegment expected = expectedPrimitive(
                VibrationEffect.Composition.PRIMITIVE_CLICK, 1, 100);
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(1).getEffectSegments());
        assertEquals(Arrays.asList(expected), mVibratorProviders.get(2).getEffectSegments());
    }

    @Test
    public void vibrate_multipleSynced_callsPrepareAndTriggerCallbacks() {
        int[] vibratorIds = new int[]{1, 2, 3, 4};
        mockVibrators(vibratorIds);
        mVibratorProviders.get(1).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        mVibratorProviders.get(4).setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        when(mThreadCallbacks.prepareSyncedVibration(anyLong(), any())).thenReturn(true);
        when(mThreadCallbacks.triggerSyncedVibration(anyLong())).thenReturn(true);

        long vibrationId = 1;
        VibrationEffect composed = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .compose();
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .addVibrator(2, VibrationEffect.createOneShot(10, 100))
                .addVibrator(3, VibrationEffect.createWaveform(new long[]{10}, new int[]{100}, -1))
                .addVibrator(4, composed)
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        long expectedCap = IVibratorManager.CAP_SYNC
                | IVibratorManager.CAP_PREPARE_ON
                | IVibratorManager.CAP_PREPARE_PERFORM
                | IVibratorManager.CAP_PREPARE_COMPOSE
                | IVibratorManager.CAP_MIXED_TRIGGER_ON
                | IVibratorManager.CAP_MIXED_TRIGGER_PERFORM
                | IVibratorManager.CAP_MIXED_TRIGGER_COMPOSE;
        verify(mThreadCallbacks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mThreadCallbacks).triggerSyncedVibration(eq(vibrationId));
        verify(mThreadCallbacks, never()).cancelSyncedVibration();
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
    }

    @Test
    public void vibrate_multipleSyncedPrepareFailed_skipTriggerStepAndVibrates() {
        int[] vibratorIds = new int[]{1, 2};
        mockVibrators(vibratorIds);
        mVibratorProviders.get(1).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        mVibratorProviders.get(2).setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        when(mThreadCallbacks.prepareSyncedVibration(anyLong(), any())).thenReturn(false);

        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.createOneShot(10, 100))
                .addVibrator(2, VibrationEffect.createWaveform(new long[]{5}, new int[]{200}, -1))
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        long expectedCap = IVibratorManager.CAP_SYNC | IVibratorManager.CAP_PREPARE_ON;
        verify(mThreadCallbacks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mThreadCallbacks, never()).triggerSyncedVibration(eq(vibrationId));
        verify(mThreadCallbacks, never()).cancelSyncedVibration();

        assertEquals(Arrays.asList(expectedOneShot(10)),
                mVibratorProviders.get(1).getEffectSegments());
        assertEquals(expectedAmplitudes(100), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(Arrays.asList(expectedOneShot(5)),
                mVibratorProviders.get(2).getEffectSegments());
        assertEquals(expectedAmplitudes(200), mVibratorProviders.get(2).getAmplitudes());
    }

    @Test
    public void vibrate_multipleSyncedTriggerFailed_cancelPreparedVibrationAndSkipSetAmplitude() {
        int[] vibratorIds = new int[]{1, 2};
        mockVibrators(vibratorIds);
        mVibratorProviders.get(2).setSupportedEffects(VibrationEffect.EFFECT_CLICK);
        when(mThreadCallbacks.prepareSyncedVibration(anyLong(), any())).thenReturn(true);
        when(mThreadCallbacks.triggerSyncedVibration(anyLong())).thenReturn(false);

        long vibrationId = 1;
        CombinedVibrationEffect effect = CombinedVibrationEffect.startSynced()
                .addVibrator(1, VibrationEffect.createOneShot(10, 100))
                .addVibrator(2, VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .combine();
        VibrationThread thread = startThreadAndDispatcher(vibrationId, effect);
        waitForCompletion(thread);

        long expectedCap = IVibratorManager.CAP_SYNC
                | IVibratorManager.CAP_PREPARE_ON
                | IVibratorManager.CAP_PREPARE_PERFORM
                | IVibratorManager.CAP_MIXED_TRIGGER_ON
                | IVibratorManager.CAP_MIXED_TRIGGER_PERFORM;
        verify(mThreadCallbacks).prepareSyncedVibration(eq(expectedCap), eq(vibratorIds));
        verify(mThreadCallbacks).triggerSyncedVibration(eq(vibrationId));
        verify(mThreadCallbacks).cancelSyncedVibration();
        assertTrue(mVibratorProviders.get(1).getAmplitudes().isEmpty());
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

        // All vibrators are turned on in parallel.
        assertTrue(waitUntil(
                t -> t.getVibrators().get(1).isVibrating()
                        && t.getVibrators().get(2).isVibrating()
                        && t.getVibrators().get(3).isVibrating(),
                thread, TEST_TIMEOUT_MILLIS));

        waitForCompletion(thread);

        verify(mIBatteryStatsMock).noteVibratorOn(eq(UID), eq(80L));
        verify(mIBatteryStatsMock).noteVibratorOff(eq(UID));
        verify(mControllerCallbacks).onComplete(eq(1), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(2), eq(vibrationId));
        verify(mControllerCallbacks).onComplete(eq(3), eq(vibrationId));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.FINISHED));
        assertFalse(thread.getVibrators().get(1).isVibrating());
        assertFalse(thread.getVibrators().get(2).isVibrating());
        assertFalse(thread.getVibrators().get(3).isVibrating());

        assertEquals(Arrays.asList(expectedOneShot(25)),
                mVibratorProviders.get(1).getEffectSegments());
        assertEquals(Arrays.asList(expectedOneShot(80)),
                mVibratorProviders.get(2).getEffectSegments());
        assertEquals(Arrays.asList(expectedOneShot(60)),
                mVibratorProviders.get(3).getEffectSegments());
        assertEquals(expectedAmplitudes(1, 2, 3), mVibratorProviders.get(1).getAmplitudes());
        assertEquals(expectedAmplitudes(4, 5), mVibratorProviders.get(2).getAmplitudes());
        assertEquals(expectedAmplitudes(6), mVibratorProviders.get(3).getAmplitudes());
    }

    @LargeTest
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

        assertTrue(waitUntil(t -> t.getVibrators().get(2).isVibrating(), vibrationThread,
                TEST_TIMEOUT_MILLIS));
        assertTrue(vibrationThread.isAlive());

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

        assertTrue(waitUntil(t -> t.getVibrators().get(1).isVibrating()
                        && t.getVibrators().get(2).isVibrating(),
                vibrationThread, TEST_TIMEOUT_MILLIS));
        assertTrue(vibrationThread.isAlive());

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

        assertTrue(waitUntil(t -> t.getVibrators().get(VIBRATOR_ID).isVibrating(), thread,
                TEST_TIMEOUT_MILLIS));
        assertTrue(thread.isAlive());

        thread.binderDied();
        waitForCompletion(thread);

        verify(mVibrationToken).linkToDeath(same(thread), eq(0));
        verify(mVibrationToken).unlinkToDeath(same(thread), eq(0));
        verify(mThreadCallbacks).onVibrationEnded(eq(vibrationId), eq(Vibration.Status.CANCELLED));
        assertFalse(mVibratorProviders.get(VIBRATOR_ID).getEffectSegments().isEmpty());
        assertFalse(thread.getVibrators().get(VIBRATOR_ID).isVibrating());
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
        return startThreadAndDispatcher(createVibration(vibrationId, effect));
    }

    private VibrationThread startThreadAndDispatcher(Vibration vib) {
        VibrationThread thread = new VibrationThread(vib, createVibratorControllers(), mWakeLock,
                mIBatteryStatsMock, mThreadCallbacks);
        doAnswer(answer -> {
            thread.vibratorComplete(answer.getArgument(0));
            return null;
        }).when(mControllerCallbacks).onComplete(anyInt(), eq(vib.id));
        mTestLooper.startAutoDispatch();
        thread.start();
        return thread;
    }

    private boolean waitUntil(Predicate<VibrationThread> predicate, VibrationThread thread,
            long timeout) throws InterruptedException {
        long timeoutTimestamp = SystemClock.uptimeMillis() + timeout;
        boolean predicateResult = false;
        while (!predicateResult && SystemClock.uptimeMillis() < timeoutTimestamp) {
            Thread.sleep(10);
            predicateResult = predicate.test(thread);
        }
        return predicateResult;
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

    private VibrationEffectSegment expectedOneShot(long millis) {
        return new StepSegment(VibrationEffect.DEFAULT_AMPLITUDE, /* frequency= */ 0, (int) millis);
    }

    private VibrationEffectSegment expectedPrebaked(int effectId) {
        return new PrebakedSegment(effectId, false, VibrationEffect.EFFECT_STRENGTH_MEDIUM);
    }

    private VibrationEffectSegment expectedPrimitive(int primitiveId, float scale, int delay) {
        return new PrimitiveSegment(primitiveId, scale, delay);
    }

    private List<Float> expectedAmplitudes(int... amplitudes) {
        return Arrays.stream(amplitudes)
                .mapToObj(amplitude -> amplitude / 255f)
                .collect(Collectors.toList());
    }
}
