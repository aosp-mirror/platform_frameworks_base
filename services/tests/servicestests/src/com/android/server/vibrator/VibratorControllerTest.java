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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.notNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.ContextWrapper;
import android.hardware.vibrator.Braking;
import android.hardware.vibrator.IVibrator;
import android.os.IBinder;
import android.os.IVibratorStateListener;
import android.os.VibrationEffect;
import android.os.VibratorInfo;
import android.os.test.TestLooper;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.RampSegment;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

/**
 * Tests for {@link VibratorController}.
 *
 * Build/Install/Run:
 * atest FrameworksServicesTests:VibratorControllerTest
 */
@Presubmit
public class VibratorControllerTest {
    private static final int VIBRATOR_ID = 0;

    @Rule public MockitoRule rule = MockitoJUnit.rule();
    @Rule public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    @Mock private VibratorController.OnVibrationCompleteListener mOnCompleteListenerMock;
    @Mock private VibratorController.NativeWrapper mNativeWrapperMock;
    @Mock private IVibratorStateListener mVibratorStateListenerMock;
    @Mock private IBinder mVibratorStateListenerBinderMock;

    private TestLooper mTestLooper;
    private ContextWrapper mContextSpy;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        when(mVibratorStateListenerMock.asBinder()).thenReturn(mVibratorStateListenerBinderMock);
        mockVibratorCapabilities(0);
    }

    private VibratorController createController() {
        return new VibratorController(VIBRATOR_ID, mOnCompleteListenerMock, mNativeWrapperMock);
    }

    @Test
    public void createController_initializesNativeWrapper() {
        VibratorController controller = createController();
        assertEquals(VIBRATOR_ID, controller.getVibratorInfo().getId());
        verify(mNativeWrapperMock).init(eq(VIBRATOR_ID), notNull());
    }

    @Test
    public void isAvailable_withVibratorHalPresent_returnsTrue() {
        when(mNativeWrapperMock.isAvailable()).thenReturn(true);
        assertTrue(createController().isAvailable());
    }

    @Test
    public void isAvailable_withNoVibratorHalPresent_returnsFalse() {
        when(mNativeWrapperMock.isAvailable()).thenReturn(false);
        assertFalse(createController().isAvailable());
    }

    @Test
    public void hasCapability_withSupport_returnsTrue() {
        mockVibratorCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL);
        assertTrue(createController().hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL));
    }

    @Test
    public void hasCapability_withNoSupport_returnsFalse() {
        assertFalse(createController().hasCapability(IVibrator.CAP_ALWAYS_ON_CONTROL));
        assertFalse(createController().hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL));
        assertFalse(createController().hasCapability(IVibrator.CAP_COMPOSE_EFFECTS));
        assertFalse(createController().hasCapability(IVibrator.CAP_EXTERNAL_CONTROL));
        assertFalse(createController().hasCapability(IVibrator.CAP_ON_CALLBACK));
    }

    @Test
    public void setExternalControl_withCapability_enablesExternalControl() {
        mockVibratorCapabilities(IVibrator.CAP_EXTERNAL_CONTROL);
        VibratorController controller = createController();
        assertFalse(controller.isUnderExternalControl());

        controller.setExternalControl(true);
        assertTrue(controller.isUnderExternalControl());

        controller.setExternalControl(false);
        assertFalse(controller.isUnderExternalControl());

        InOrder inOrderVerifier = inOrder(mNativeWrapperMock);
        inOrderVerifier.verify(mNativeWrapperMock).setExternalControl(eq(true));
        inOrderVerifier.verify(mNativeWrapperMock).setExternalControl(eq(false));
    }

    @Test
    public void setExternalControl_withNoCapability_ignoresExternalControl() {
        VibratorController controller = createController();
        assertFalse(controller.isUnderExternalControl());

        controller.setExternalControl(true);
        assertFalse(controller.isUnderExternalControl());

        verify(mNativeWrapperMock, never()).setExternalControl(anyBoolean());
    }

    @Test
    public void updateAlwaysOn_withCapability_enablesAlwaysOnEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        PrebakedSegment prebaked = createPrebaked(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        createController().updateAlwaysOn(1, prebaked);

        verify(mNativeWrapperMock).alwaysOnEnable(
                eq(1L), eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_MEDIUM));
    }

    @Test
    public void updateAlwaysOn_withNullEffect_disablesAlwaysOnEffect() {
        mockVibratorCapabilities(IVibrator.CAP_ALWAYS_ON_CONTROL);
        createController().updateAlwaysOn(1, null);
        verify(mNativeWrapperMock).alwaysOnDisable(eq(1L));
    }

    @Test
    public void updateAlwaysOn_withoutCapability_ignoresEffect() {
        PrebakedSegment prebaked = createPrebaked(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        createController().updateAlwaysOn(1, prebaked);

        verify(mNativeWrapperMock, never()).alwaysOnDisable(anyLong());
        verify(mNativeWrapperMock, never()).alwaysOnEnable(anyLong(), anyLong(), anyLong());
    }

    @Test
    public void on_withDuration_turnsVibratorOn() {
        when(mNativeWrapperMock.on(anyLong(), anyLong())).thenAnswer(args -> args.getArgument(0));
        VibratorController controller = createController();
        controller.on(100, 10);

        assertTrue(controller.isVibrating());
        verify(mNativeWrapperMock).on(eq(100L), eq(10L));
    }

    @Test
    public void on_withPrebaked_performsEffect() {
        when(mNativeWrapperMock.perform(anyLong(), anyLong(), anyLong())).thenReturn(10L);
        VibratorController controller = createController();

        PrebakedSegment prebaked = createPrebaked(VibrationEffect.EFFECT_CLICK,
                VibrationEffect.EFFECT_STRENGTH_MEDIUM);
        assertEquals(10L, controller.on(prebaked, 11));

        assertTrue(controller.isVibrating());
        verify(mNativeWrapperMock).perform(eq((long) VibrationEffect.EFFECT_CLICK),
                eq((long) VibrationEffect.EFFECT_STRENGTH_MEDIUM), eq(11L));
    }

    @Test
    public void on_withComposed_performsEffect() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        when(mNativeWrapperMock.compose(any(), anyLong())).thenReturn(15L);
        VibratorController controller = createController();

        PrimitiveSegment[] primitives = new PrimitiveSegment[]{
                new PrimitiveSegment(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
        };
        assertEquals(15L, controller.on(primitives, 12));

        assertTrue(controller.isVibrating());
        verify(mNativeWrapperMock).compose(eq(primitives), eq(12L));
    }

    @Test
    public void on_withComposedPwle_performsEffect() {
        mockVibratorCapabilities(IVibrator.CAP_COMPOSE_PWLE_EFFECTS);
        when(mNativeWrapperMock.composePwle(any(), anyInt(), anyLong())).thenReturn(15L);
        VibratorController controller = createController();

        RampSegment[] primitives = new RampSegment[]{
                new RampSegment(/* startAmplitude= */ 0, /* endAmplitude= */ 1,
                        /* startFrequency= */ -1, /* endFrequency= */ 1, /* duration= */ 10)
        };
        assertEquals(15L, controller.on(primitives, 12));
        assertTrue(controller.isVibrating());

        verify(mNativeWrapperMock).composePwle(eq(primitives), eq(Braking.NONE), eq(12L));
    }

    @Test
    public void off_turnsOffVibrator() {
        when(mNativeWrapperMock.on(anyLong(), anyLong())).thenAnswer(args -> args.getArgument(0));
        VibratorController controller = createController();

        controller.on(100, 1);
        assertTrue(controller.isVibrating());

        controller.off();
        controller.off();
        assertFalse(controller.isVibrating());
        verify(mNativeWrapperMock, times(2)).off();
    }

    @Test
    public void registerVibratorStateListener_callbacksAreTriggered() throws Exception {
        when(mNativeWrapperMock.on(anyLong(), anyLong())).thenAnswer(args -> args.getArgument(0));
        VibratorController controller = createController();

        controller.registerVibratorStateListener(mVibratorStateListenerMock);
        controller.on(10, 1);
        controller.on(100, 2);
        controller.off();
        controller.off();

        InOrder inOrderVerifier = inOrder(mVibratorStateListenerMock);
        // First notification done when listener is registered.
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(false);
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(true));
        inOrderVerifier.verify(mVibratorStateListenerMock).onVibrating(eq(false));
        inOrderVerifier.verifyNoMoreInteractions();
    }

    @Test
    public void unregisterVibratorStateListener_callbackNotTriggeredAfter() throws Exception {
        when(mNativeWrapperMock.on(anyLong(), anyLong())).thenAnswer(args -> args.getArgument(0));
        VibratorController controller = createController();

        controller.registerVibratorStateListener(mVibratorStateListenerMock);
        verify(mVibratorStateListenerMock).onVibrating(false);

        controller.on(10, 1);
        verify(mVibratorStateListenerMock).onVibrating(true);

        controller.unregisterVibratorStateListener(mVibratorStateListenerMock);
        Mockito.clearInvocations(mVibratorStateListenerMock);

        controller.on(10, 1);
        verifyNoMoreInteractions(mVibratorStateListenerMock);
    }

    private void mockVibratorCapabilities(int capabilities) {
        VibratorInfo.FrequencyMapping frequencyMapping = new VibratorInfo.FrequencyMapping(
                Float.NaN, Float.NaN, Float.NaN, Float.NaN, null);
        when(mNativeWrapperMock.getInfo(/* suggestedFrequencyRange= */ 100)).thenReturn(
                new VibratorInfo(VIBRATOR_ID, capabilities, null, null, null, Float.NaN,
                        frequencyMapping));
    }

    private PrebakedSegment createPrebaked(int effectId, int effectStrength) {
        return new PrebakedSegment(effectId, /* shouldFallback= */ false, effectStrength);
    }
}
