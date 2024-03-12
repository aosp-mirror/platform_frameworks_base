/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.os.vibrator.persistence;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;

import com.google.common.truth.Subject;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.util.List;

/** Unit tests for {@link ParsedVibration}. */
@RunWith(MockitoJUnitRunner.class)
public class ParsedVibrationTest {

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();

    @Mock Vibrator mVibratorMock;
    @Mock VibratorInfo mVibratorInfoMock;

    @Mock VibrationEffect mEffect1;
    @Mock VibrationEffect mEffect2;
    @Mock VibrationEffect mEffect3;

    @Before
    public void setUp() {
        when(mVibratorMock.getInfo()).thenReturn(mVibratorInfoMock);
    }

    @Test
    public void empty() {
        assertThat(new ParsedVibration(List.of()).resolve(mVibratorMock)).isNull();
    }

    @Test
    public void testResolve_allUnsupportedVibrations() {
        when(mVibratorInfoMock.areVibrationFeaturesSupported(any())).thenReturn(false);

        assertThatResolution(mVibratorMock,  mEffect1).isNull();
        assertThatResolution(mVibratorMock, List.of(mEffect1, mEffect2)).isNull();
    }

    @Test
    public void testResolve_allSupportedVibrations() {
        when(mVibratorInfoMock.areVibrationFeaturesSupported(any())).thenReturn(true);

        assertThatResolution(mVibratorMock, mEffect1).isEqualTo(mEffect1);
        assertThatResolution(mVibratorMock, List.of(mEffect1, mEffect2)).isEqualTo(mEffect1);
    }

    @Test
    public void testResolve_mixedSupportedAndUnsupportedVibrations() {
        when(mVibratorInfoMock.areVibrationFeaturesSupported(mEffect1)).thenReturn(true);
        when(mVibratorInfoMock.areVibrationFeaturesSupported(mEffect2)).thenReturn(true);
        when(mVibratorInfoMock.areVibrationFeaturesSupported(mEffect3)).thenReturn(false);

        assertThatResolution(mVibratorMock, List.of(mEffect1, mEffect3)).isEqualTo(mEffect1);
        assertThatResolution(mVibratorMock, List.of(mEffect3, mEffect1, mEffect2))
                .isEqualTo(mEffect1);
        assertThatResolution(mVibratorMock, List.of(mEffect1, mEffect2, mEffect3))
                .isEqualTo(mEffect1);
    }

    @Test
    public void testGetVibrationEffects() {
        ParsedVibration parsedVibration =
                new ParsedVibration(List.of(mEffect1, mEffect2, mEffect3));
        assertThat(parsedVibration.getVibrationEffects())
                .containsExactly(mEffect1, mEffect2, mEffect3)
                .inOrder();

        parsedVibration = new ParsedVibration(List.of(mEffect1));
        assertThat(parsedVibration.getVibrationEffects()).containsExactly(mEffect1);

        parsedVibration = new ParsedVibration(List.of());
        assertThat(parsedVibration.getVibrationEffects()).isEmpty();
    }

    private Subject assertThatResolution(
            Vibrator vibrator, List<VibrationEffect> componentVibrations) {
        return assertThat(new ParsedVibration(componentVibrations).resolve(vibrator));
    }

    private Subject assertThatResolution(Vibrator vibrator, VibrationEffect vibration) {
        return assertThat(new ParsedVibration(vibration).resolve(vibrator));
    }
}
