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

package com.android.server.vibrator;

import static android.os.VibrationEffect.Composition.PRIMITIVE_CLICK;
import static android.os.VibrationEffect.Composition.PRIMITIVE_TICK;
import static android.os.VibrationEffect.EFFECT_TEXTURE_TICK;
import static android.os.VibrationEffect.EFFECT_TICK;
import static android.view.HapticFeedbackConstants.CLOCK_TICK;
import static android.view.HapticFeedbackConstants.CONTEXT_CLICK;
import static android.view.HapticFeedbackConstants.SAFE_MODE_ENABLED;
import static android.view.HapticFeedbackConstants.TEXT_HANDLE_MOVE;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;
import android.content.res.Resources;
import android.os.VibrationEffect;
import android.os.test.FakeVibrator;
import android.util.AtomicFile;
import android.util.SparseArray;

import androidx.test.InstrumentationRegistry;

import com.android.internal.R;

import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.File;
import java.io.FileOutputStream;

public class HapticFeedbackVibrationProviderTest {
    @Rule public MockitoRule rule = MockitoJUnit.rule();

    private static final VibrationEffect PRIMITIVE_TICK_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_TICK, 0.2497f).compose();
    private static final VibrationEffect PRIMITIVE_CLICK_EFFECT =
            VibrationEffect.startComposition().addPrimitive(PRIMITIVE_CLICK, 0.3497f).compose();

    private Context mContext = InstrumentationRegistry.getContext();
    private FakeVibrator mVibrator = new FakeVibrator(mContext);

    @Mock private Resources mResourcesMock;

    @Test
    public void testNonExistentCustomization_useDefault() throws Exception {
        // No customization file is set.
        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator);

        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));

        // The customization file specifies no customization.
        setupCustomizationFile("<haptic-feedback-constants></haptic-feedback-constants>");
        hapticProvider = new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator);

        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));
    }

    @Test
    public void testExceptionParsingCustomizations_useDefault() throws Exception {
        setupCustomizationFile("<bad-xml></bad-xml>");
        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator);

        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));
    }

    @Test
    public void testUseValidCustomizedVibration() throws Exception {
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(CONTEXT_CLICK, PRIMITIVE_CLICK_EFFECT);

        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        // The override for `CONTEXT_CLICK` is used.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);
        // `CLOCK_TICK` has no override, so the default vibration is used.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CLOCK_TICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testDoNotUseInvalidCustomizedVibration() throws Exception {
        mockVibratorPrimitiveSupport(new int[] {});
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(CONTEXT_CLICK, PRIMITIVE_CLICK_EFFECT);

        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        // The override for `CONTEXT_CLICK` is not used because the vibration is not supported.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CONTEXT_CLICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TICK));
        // `CLOCK_TICK` has no override, so the default vibration is used.
        assertThat(hapticProvider.getVibrationForHapticFeedback(CLOCK_TICK))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testHapticTextDisabled_noVibrationReturnedForTextHandleMove() throws Exception {
        mockHapticTextSupport(false);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(TEXT_HANDLE_MOVE, PRIMITIVE_CLICK_EFFECT);

        // Test with a customization available for `TEXT_HANDLE_MOVE`.
        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE)).isNull();

        // Test with no customization available for `TEXT_HANDLE_MOVE`.
        hapticProvider =
                new HapticFeedbackVibrationProvider(
                        mResourcesMock, mVibrator, /* hapticCustomizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE)).isNull();
    }

    @Test
    public void testHapticTextEnabled_vibrationReturnedForTextHandleMove() throws Exception {
        mockHapticTextSupport(true);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(TEXT_HANDLE_MOVE, PRIMITIVE_CLICK_EFFECT);

        // Test with a customization available for `TEXT_HANDLE_MOVE`.
        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);

        // Test with no customization available for `TEXT_HANDLE_MOVE`.
        hapticProvider =
                new HapticFeedbackVibrationProvider(
                        mResourcesMock, mVibrator, /* hapticCustomizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(TEXT_HANDLE_MOVE))
                .isEqualTo(VibrationEffect.get(EFFECT_TEXTURE_TICK));
    }

    @Test
    public void testValidCustomizationPresentForSafeModeEnabled_usedRegardlessOfVibrationResource()
                throws Exception {
        mockSafeModeEnabledVibration(10, 20, 30, 40);
        mockVibratorPrimitiveSupport(PRIMITIVE_CLICK);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(SAFE_MODE_ENABLED, PRIMITIVE_CLICK_EFFECT);

        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);

        mockSafeModeEnabledVibration(null);
        hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED))
                .isEqualTo(PRIMITIVE_CLICK_EFFECT);
    }

    @Test
    public void testNoValidCustomizationPresentForSafeModeEnabled_resourceBasedVibrationUsed()
                throws Exception {
        mockSafeModeEnabledVibration(10, 20, 30, 40);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(SAFE_MODE_ENABLED, PRIMITIVE_CLICK_EFFECT);

        // Test with a customization that is not supported by the vibrator.
        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED))
                .isEqualTo(VibrationEffect.createWaveform(new long[] {10, 20, 30, 40}, -1));

        // Test with no customizations.
        hapticProvider =
                new HapticFeedbackVibrationProvider(
                        mResourcesMock, mVibrator, /* hapticCustomizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED))
                .isEqualTo(VibrationEffect.createWaveform(new long[] {10, 20, 30, 40}, -1));
    }

    @Test
    public void testNoValidCustomizationAndResourcePresentForSafeModeEnabled_noVibrationUsed()
                throws Exception {
        mockSafeModeEnabledVibration(null);
        SparseArray<VibrationEffect> customizations = new SparseArray<>();
        customizations.put(SAFE_MODE_ENABLED, PRIMITIVE_CLICK_EFFECT);

        // Test with a customization that is not supported by the vibrator.
        HapticFeedbackVibrationProvider hapticProvider =
                new HapticFeedbackVibrationProvider(mResourcesMock, mVibrator, customizations);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED)).isNull();

        // Test with no customizations.
        hapticProvider =
                new HapticFeedbackVibrationProvider(
                        mResourcesMock, mVibrator, /* hapticCustomizations= */ null);

        assertThat(hapticProvider.getVibrationForHapticFeedback(SAFE_MODE_ENABLED)).isNull();
    }

    private void mockVibratorPrimitiveSupport(int... supportedPrimitives) {
        mVibrator = new FakeVibrator(mContext, supportedPrimitives);
    }

    private void mockHapticTextSupport(boolean supported) {
        when(mResourcesMock.getBoolean(R.bool.config_enableHapticTextHandle)).thenReturn(supported);
    }

    private void mockSafeModeEnabledVibration(int... vibrationPattern) {
        when(mResourcesMock.getIntArray(R.array.config_safeModeEnabledVibePattern))
                .thenReturn(vibrationPattern);
    }

    private void setupCustomizationFile(String xml) throws Exception {
        File file = new File(mContext.getCacheDir(), "test.xml");
        file.createNewFile();

        AtomicFile atomicXmlFile = new AtomicFile(file);
        FileOutputStream fos = atomicXmlFile.startWrite();
        fos.write(xml.getBytes());
        atomicXmlFile.finishWrite(fos);

        when(mResourcesMock.getString(R.string.config_hapticFeedbackCustomizationFile))
                .thenReturn(file.getAbsolutePath());
    }
}
