/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.testng.Assert.assertThrows;

import android.content.ContentInterface;
import android.content.ContentResolver;
import android.content.Context;
import android.content.res.Resources;
import android.net.Uri;
import android.os.vibrator.PrebakedSegment;
import android.os.vibrator.PrimitiveSegment;
import android.os.vibrator.StepSegment;
import android.platform.test.annotations.Presubmit;

import com.android.internal.R;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

@Presubmit
@RunWith(MockitoJUnitRunner.class)
public class VibrationEffectTest {

    private static final String RINGTONE_URI_1 = "content://test/system/ringtone_1";
    private static final String RINGTONE_URI_2 = "content://test/system/ringtone_2";
    private static final String RINGTONE_URI_3 = "content://test/system/ringtone_3";
    private static final String UNKNOWN_URI = "content://test/system/other_audio";

    private static final long TEST_TIMING = 100;
    private static final int TEST_AMPLITUDE = 100;
    private static final long[] TEST_TIMINGS = new long[] { 100, 100, 200 };
    private static final int[] TEST_AMPLITUDES =
            new int[] { 255, 0, VibrationEffect.DEFAULT_AMPLITUDE };

    private static final VibrationEffect TEST_ONE_SHOT =
            VibrationEffect.createOneShot(TEST_TIMING, TEST_AMPLITUDE);
    private static final VibrationEffect DEFAULT_ONE_SHOT =
            VibrationEffect.createOneShot(TEST_TIMING, VibrationEffect.DEFAULT_AMPLITUDE);
    private static final VibrationEffect TEST_WAVEFORM =
            VibrationEffect.createWaveform(TEST_TIMINGS, TEST_AMPLITUDES, -1);

    @Test
    public void getRingtones_noPrebakedRingtones() {
        Resources r = mockRingtoneResources(new String[0]);
        Context context = mockContext(r);
        VibrationEffect effect = VibrationEffect.get(Uri.parse(RINGTONE_URI_1), context);
        assertNull(effect);
    }

    @Test
    public void getRingtones_noPrebakedRingtoneForUri() {
        Resources r = mockRingtoneResources();
        Context context = mockContext(r);
        VibrationEffect effect = VibrationEffect.get(Uri.parse(UNKNOWN_URI), context);
        assertNull(effect);
    }

    @Test
    public void getRingtones_getPrebakedRingtone() {
        Resources r = mockRingtoneResources();
        Context context = mockContext(r);
        VibrationEffect effect = VibrationEffect.get(Uri.parse(RINGTONE_URI_2), context);
        VibrationEffect expectedEffect = VibrationEffect.get(VibrationEffect.RINGTONES[1]);
        assertNotNull(expectedEffect);
        assertEquals(expectedEffect, effect);
    }

    @Test
    public void testValidateOneShot() {
        VibrationEffect.createOneShot(1, 255).validate();
        VibrationEffect.createOneShot(1, VibrationEffect.DEFAULT_AMPLITUDE).validate();

        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(-1, 255).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(0, 255).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(1, -2).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createOneShot(1, 256).validate());
    }

    @Test
    public void testValidatePrebaked() {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK).validate();
        VibrationEffect.createPredefined(VibrationEffect.RINGTONES[1]).validate();

        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createPredefined(-1).validate());
    }

    @Test
    public void testValidateWaveform() {
        VibrationEffect.createWaveform(TEST_TIMINGS, TEST_AMPLITUDES, -1).validate();
        VibrationEffect.createWaveform(TEST_TIMINGS, TEST_AMPLITUDES, 0).validate();
        VibrationEffect.startWaveform()
                .addStep(/* amplitude= */ 1, /* duration= */ 10)
                .addRamp(/* amplitude= */ 0, /* duration= */ 20)
                .addStep(/* amplitude= */ 1, /* frequency*/ 1, /* duration= */ 100)
                .addRamp(/* amplitude= */ 0.5f, /* frequency*/ -1, /* duration= */ 50)
                .build()
                .validate();

        assertThrows(IllegalStateException.class,
                () -> VibrationEffect.startWaveform().build().validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(new long[0], new int[0], -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(TEST_TIMINGS, new int[0], -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(
                        new long[]{0, 0, 0}, TEST_AMPLITUDES, -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(
                        TEST_TIMINGS, new int[]{-1, -1, -2}, -1).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.createWaveform(
                        TEST_TIMINGS, TEST_AMPLITUDES, TEST_TIMINGS.length).validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startWaveform()
                        .addStep(/* amplitude= */ -2, 10).build().validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startWaveform()
                        .addStep(1, /* duration= */ -1).build().validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startWaveform()
                        .addStep(1, 0, /* duration= */ -1).build().validate());
    }

    @Test
    public void testValidateComposed() {
        VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .addEffect(TEST_ONE_SHOT)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f)
                .addEffect(TEST_WAVEFORM, 100)
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 10)
                .addEffect(VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .compose()
                .validate();

        assertThrows(IllegalStateException.class,
                () -> VibrationEffect.startComposition().compose().validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition().addPrimitive(-1).compose().validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, -1, 10)
                        .compose()
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1, -10)
                        .compose()
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .addEffect(TEST_ONE_SHOT, /* delay= */ -10)
                        .compose()
                        .validate());
        assertThrows(IllegalArgumentException.class,
                () -> VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, -1)
                        .compose()
                        .validate());
    }

    @Test
    public void testResolveOneShot() {
        VibrationEffect.Composed resolved = DEFAULT_ONE_SHOT.resolve(51);
        assertEquals(0.2f, ((StepSegment) resolved.getSegments().get(0)).getAmplitude());

        assertThrows(IllegalArgumentException.class, () -> DEFAULT_ONE_SHOT.resolve(1000));
    }

    @Test
    public void testResolveWaveform() {
        VibrationEffect.Composed resolved = TEST_WAVEFORM.resolve(102);
        assertEquals(0.4f, ((StepSegment) resolved.getSegments().get(2)).getAmplitude());

        assertThrows(IllegalArgumentException.class, () -> TEST_WAVEFORM.resolve(1000));
    }

    @Test
    public void testResolvePrebaked() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);
        assertEquals(effect, effect.resolve(51));
    }

    @Test
    public void testResolveComposed() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 1f, 1)
                .compose();
        assertEquals(effect, effect.resolve(51));

        VibrationEffect.Composed resolved = VibrationEffect.startComposition()
                .addEffect(DEFAULT_ONE_SHOT)
                .compose()
                .resolve(51);
        assertEquals(0.2f, ((StepSegment) resolved.getSegments().get(0)).getAmplitude());
    }

    @Test
    public void testApplyEffectStrengthOneShot() {
        VibrationEffect.Composed applied = DEFAULT_ONE_SHOT.applyEffectStrength(
                VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertEquals(DEFAULT_ONE_SHOT, applied);
    }

    @Test
    public void testApplyEffectStrengthWaveform() {
        VibrationEffect.Composed applied = TEST_WAVEFORM.applyEffectStrength(
                VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertEquals(TEST_WAVEFORM, applied);
    }

    @Test
    public void testApplyEffectStrengthPrebaked() {
        VibrationEffect.Composed applied = VibrationEffect.get(VibrationEffect.EFFECT_CLICK)
                .applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertEquals(VibrationEffect.EFFECT_STRENGTH_LIGHT,
                ((PrebakedSegment) applied.getSegments().get(0)).getEffectStrength());
    }

    @Test
    public void testApplyEffectStrengthComposed() {
        VibrationEffect effect = VibrationEffect.startComposition()
                .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 1)
                .compose();
        assertEquals(effect, effect.applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_LIGHT));

        VibrationEffect.Composed applied = VibrationEffect.startComposition()
                .addEffect(VibrationEffect.get(VibrationEffect.EFFECT_CLICK))
                .compose()
                .applyEffectStrength(VibrationEffect.EFFECT_STRENGTH_LIGHT);
        assertEquals(VibrationEffect.EFFECT_STRENGTH_LIGHT,
                ((PrebakedSegment) applied.getSegments().get(0)).getEffectStrength());
    }

    @Test
    public void testScaleOneShot() {
        VibrationEffect.Composed scaledUp = TEST_ONE_SHOT.scale(1.5f);
        assertTrue(100 / 255f < ((StepSegment) scaledUp.getSegments().get(0)).getAmplitude());

        VibrationEffect.Composed scaledDown = TEST_ONE_SHOT.scale(0.5f);
        assertTrue(100 / 255f > ((StepSegment) scaledDown.getSegments().get(0)).getAmplitude());
    }

    @Test
    public void testScaleWaveform() {
        VibrationEffect.Composed scaledUp = TEST_WAVEFORM.scale(1.5f);
        assertEquals(1f, ((StepSegment) scaledUp.getSegments().get(0)).getAmplitude(), 1e-5f);

        VibrationEffect.Composed scaledDown = TEST_WAVEFORM.scale(0.5f);
        assertTrue(1f > ((StepSegment) scaledDown.getSegments().get(0)).getAmplitude());
    }

    @Test
    public void testScalePrebaked() {
        VibrationEffect effect = VibrationEffect.get(VibrationEffect.EFFECT_CLICK);

        VibrationEffect.Composed scaledUp = effect.scale(1.5f);
        assertEquals(effect, scaledUp);

        VibrationEffect.Composed scaledDown = effect.scale(0.5f);
        assertEquals(effect, scaledDown);
    }

    @Test
    public void testScaleComposed() {
        VibrationEffect.Composed effect =
                (VibrationEffect.Composed) VibrationEffect.startComposition()
                        .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.5f, 1)
                        .addEffect(TEST_ONE_SHOT)
                        .compose();

        VibrationEffect.Composed scaledUp = effect.scale(1.5f);
        assertTrue(0.5f < ((PrimitiveSegment) scaledUp.getSegments().get(0)).getScale());
        assertTrue(100 / 255f < ((StepSegment) scaledUp.getSegments().get(1)).getAmplitude());

        VibrationEffect.Composed scaledDown = effect.scale(0.5f);
        assertTrue(0.5f > ((PrimitiveSegment) scaledDown.getSegments().get(0)).getScale());
        assertTrue(100 / 255f > ((StepSegment) scaledDown.getSegments().get(1)).getAmplitude());
    }

    private Resources mockRingtoneResources() {
        return mockRingtoneResources(new String[] {
                RINGTONE_URI_1,
                RINGTONE_URI_2,
                RINGTONE_URI_3
        });
    }

    private Resources mockRingtoneResources(String[] ringtoneUris) {
        Resources mockResources = mock(Resources.class);
        when(mockResources.getStringArray(R.array.config_ringtoneEffectUris))
                .thenReturn(ringtoneUris);
        return mockResources;
    }

    private Context mockContext(Resources resources) {
        Context context = mock(Context.class);
        ContentInterface contentInterface = mock(ContentInterface.class);
        ContentResolver contentResolver = ContentResolver.wrap(contentInterface);

        try {
            // ContentResolver#uncanonicalize is final, so we need to mock the ContentInterface it
            // delegates the call to for the tests that require matching with the mocked URIs.
            when(contentInterface.uncanonicalize(any())).then(
                    invocation -> invocation.getArgument(0));
            when(context.getContentResolver()).thenReturn(contentResolver);
            when(context.getResources()).thenReturn(resources);
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        return context;
    }
}
