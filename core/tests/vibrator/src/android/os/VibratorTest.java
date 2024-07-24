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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.media.AudioAttributes;
import android.os.test.TestLooper;

import androidx.test.InstrumentationRegistry;

import com.android.internal.util.test.FakeSettingsProvider;
import com.android.internal.util.test.FakeSettingsProviderRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class VibratorTest {

    @Rule
    public FakeSettingsProviderRule mSettingsProviderRule = FakeSettingsProvider.rule();

    private Context mContextSpy;
    private Vibrator mVibratorSpy;
    private TestLooper mTestLooper;

    @Before
    public void setUp() {
        mContextSpy = spy(new ContextWrapper(InstrumentationRegistry.getContext()));

        ContentResolver contentResolver = mSettingsProviderRule.mockContentResolver(mContextSpy);
        when(mContextSpy.getContentResolver()).thenReturn(contentResolver);
        mVibratorSpy = spy(new SystemVibrator(mContextSpy));
        mTestLooper = new TestLooper();
    }

    @Test
    public void getId_returnsDefaultId() {
        assertEquals(-1, mVibratorSpy.getId());
    }

    @Test
    public void areEffectsSupported_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.areEffectsSupported(new int[0]).length);
        assertEquals(1,
                mVibratorSpy.areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK}).length);
        assertEquals(2,
                mVibratorSpy.areEffectsSupported(new int[]{VibrationEffect.EFFECT_CLICK,
                        VibrationEffect.EFFECT_TICK}).length);
    }

    @Test
    public void arePrimitivesSupported_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.arePrimitivesSupported(new int[0]).length);
        assertEquals(1, mVibratorSpy.arePrimitivesSupported(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK}).length);
        assertEquals(2, mVibratorSpy.arePrimitivesSupported(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE}).length);
    }

    @Test
    public void getPrimitivesDurations_returnsArrayOfSameSize() {
        assertEquals(0, mVibratorSpy.getPrimitiveDurations(new int[0]).length);
        assertEquals(1, mVibratorSpy.getPrimitiveDurations(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK}).length);
        assertEquals(2, mVibratorSpy.getPrimitiveDurations(
                new int[]{VibrationEffect.Composition.PRIMITIVE_CLICK,
                        VibrationEffect.Composition.PRIMITIVE_QUICK_RISE}).length);
    }

    @Test
    public void onVibratorStateChanged_noVibrator_registersNoListenerToVibratorManager() {
        VibratorManager mockVibratorManager = mock(VibratorManager.class);
        when(mockVibratorManager.getVibratorIds()).thenReturn(new int[0]);

        Vibrator.OnVibratorStateChangedListener mockListener =
                mock(Vibrator.OnVibratorStateChangedListener.class);
        SystemVibrator.MultiVibratorStateListener multiVibratorListener =
                new SystemVibrator.MultiVibratorStateListener(
                        mTestLooper.getNewExecutor(), mockListener);

        multiVibratorListener.register(mockVibratorManager);

        // Never tries to register a listener to an individual vibrator.
        assertFalse(multiVibratorListener.hasRegisteredListeners());
        verify(mockVibratorManager, never()).getVibrator(anyInt());
    }

    @Test
    public void onVibratorStateChanged_singleVibrator_forwardsAllCallbacks() {
        VibratorManager mockVibratorManager = mock(VibratorManager.class);
        when(mockVibratorManager.getVibratorIds()).thenReturn(new int[] { 1 });
        when(mockVibratorManager.getVibrator(anyInt())).thenReturn(NullVibrator.getInstance());

        Vibrator.OnVibratorStateChangedListener mockListener =
                mock(Vibrator.OnVibratorStateChangedListener.class);
        SystemVibrator.MultiVibratorStateListener multiVibratorListener =
                new SystemVibrator.MultiVibratorStateListener(
                        mTestLooper.getNewExecutor(), mockListener);

        multiVibratorListener.register(mockVibratorManager);
        assertTrue(multiVibratorListener.hasRegisteredListeners());

        multiVibratorListener.onVibrating(/* vibratorIdx= */ 0, /* vibrating= */ false);
        multiVibratorListener.onVibrating(/* vibratorIdx= */ 0, /* vibrating= */ true);
        multiVibratorListener.onVibrating(/* vibratorIdx= */ 0, /* vibrating= */ false);

        mTestLooper.dispatchAll();

        InOrder inOrder = inOrder(mockListener);
        inOrder.verify(mockListener).onVibratorStateChanged(eq(false));
        inOrder.verify(mockListener).onVibratorStateChanged(eq(true));
        inOrder.verify(mockListener).onVibratorStateChanged(eq(false));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void onVibratorStateChanged_multipleVibrators_triggersOnlyWhenAllVibratorsInitialized() {
        VibratorManager mockVibratorManager = mock(VibratorManager.class);
        when(mockVibratorManager.getVibratorIds()).thenReturn(new int[] { 1, 2 });
        when(mockVibratorManager.getVibrator(anyInt())).thenReturn(NullVibrator.getInstance());

        Vibrator.OnVibratorStateChangedListener mockListener =
                mock(Vibrator.OnVibratorStateChangedListener.class);
        SystemVibrator.MultiVibratorStateListener multiVibratorListener =
                new SystemVibrator.MultiVibratorStateListener(
                        mTestLooper.getNewExecutor(), mockListener);

        multiVibratorListener.register(mockVibratorManager);
        assertTrue(multiVibratorListener.hasRegisteredListeners());

        multiVibratorListener.onVibrating(/* vibratorIdx= */ 0, /* vibrating= */ false);
        mTestLooper.dispatchAll();
        verify(mockListener, never()).onVibratorStateChanged(anyBoolean());

        multiVibratorListener.onVibrating(/* vibratorIdx= */ 1, /* vibrating= */ false);
        mTestLooper.dispatchAll();
        verify(mockListener).onVibratorStateChanged(eq(false));
        verifyNoMoreInteractions(mockListener);
    }

    @Test
    public void onVibratorStateChanged_multipleVibrators_stateChangeIsDeduped() {
        VibratorManager mockVibratorManager = mock(VibratorManager.class);
        when(mockVibratorManager.getVibratorIds()).thenReturn(new int[] { 1, 2 });
        when(mockVibratorManager.getVibrator(anyInt())).thenReturn(NullVibrator.getInstance());

        Vibrator.OnVibratorStateChangedListener mockListener =
                mock(Vibrator.OnVibratorStateChangedListener.class);
        SystemVibrator.MultiVibratorStateListener multiVibratorListener =
                new SystemVibrator.MultiVibratorStateListener(
                        mTestLooper.getNewExecutor(), mockListener);

        multiVibratorListener.register(mockVibratorManager);
        assertTrue(multiVibratorListener.hasRegisteredListeners());

        multiVibratorListener.onVibrating(/* vibratorIdx= */ 0, /* vibrating= */ false); // none
        multiVibratorListener.onVibrating(/* vibratorIdx= */ 1, /* vibrating= */ false); // false
        multiVibratorListener.onVibrating(/* vibratorIdx= */ 0, /* vibrating= */ true);  // true
        multiVibratorListener.onVibrating(/* vibratorIdx= */ 1, /* vibrating= */ true);  // true
        multiVibratorListener.onVibrating(/* vibratorIdx= */ 0, /* vibrating= */ false); // true
        multiVibratorListener.onVibrating(/* vibratorIdx= */ 1, /* vibrating= */ false); // false

        mTestLooper.dispatchAll();

        InOrder inOrder = inOrder(mockListener);
        inOrder.verify(mockListener).onVibratorStateChanged(eq(false));
        inOrder.verify(mockListener).onVibratorStateChanged(eq(true));
        inOrder.verify(mockListener).onVibratorStateChanged(eq(false));
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    public void vibrate_withVibrationAttributes_usesGivenAttributes() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        VibrationAttributes attributes = new VibrationAttributes.Builder().setUsage(
                VibrationAttributes.USAGE_TOUCH).build();

        mVibratorSpy.vibrate(effect, attributes);

        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), eq(attributes));
    }

    @Test
    public void vibrate_withVibrationAttributesAndReason_usesGivenAttributesAndReason() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        VibrationAttributes attributes = new VibrationAttributes.Builder().setUsage(
                VibrationAttributes.USAGE_TOUCH).build();
        String reason = "reason";

        mVibratorSpy.vibrate(effect, attributes, reason);

        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), eq(reason), eq(attributes));
    }

    @Test
    public void vibrate_withAudioAttributes_createsVibrationAttributesWithSameUsage() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        AudioAttributes audioAttributes = new AudioAttributes.Builder().setUsage(
                AudioAttributes.USAGE_VOICE_COMMUNICATION).build();

        mVibratorSpy.vibrate(effect, audioAttributes);

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), eq(effect), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(VibrationAttributes.USAGE_COMMUNICATION_REQUEST,
                vibrationAttributes.getUsage());
        // Keeps original AudioAttributes usage to be used by the VibratorService.
        assertEquals(AudioAttributes.USAGE_VOICE_COMMUNICATION,
                vibrationAttributes.getAudioUsage());
    }

    @Test
    public void vibrate_withoutAudioAttributes_passesOnDefaultAttributes() {
        mVibratorSpy.vibrate(VibrationEffect.get(VibrationEffect.EFFECT_CLICK));

        ArgumentCaptor<VibrationAttributes> captor = ArgumentCaptor.forClass(
                VibrationAttributes.class);
        verify(mVibratorSpy).vibrate(anyInt(), anyString(), any(), isNull(), captor.capture());

        VibrationAttributes vibrationAttributes = captor.getValue();
        assertEquals(new VibrationAttributes.Builder().build(), vibrationAttributes);
    }
}
