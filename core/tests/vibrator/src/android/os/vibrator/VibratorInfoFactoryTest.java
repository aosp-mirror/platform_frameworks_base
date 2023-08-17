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

import static junit.framework.Assert.assertTrue;
import static junit.framework.TestCase.assertEquals;

import android.hardware.vibrator.IVibrator;
import android.os.VibrationEffect;
import android.os.VibratorInfo;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class VibratorInfoFactoryTest {

    @Test
    public void testCreatedInfo_hasTheRequestedId() {
        // Empty info list.
        VibratorInfo infoFromEmptyInfos =
                VibratorInfoFactory.create(/* id= */ 3, new VibratorInfo[] {});
        VibratorInfo info1 = new VibratorInfo.Builder(/* id= */ 1)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo info2 = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();
        VibratorInfo infoFromOneInfo =
                VibratorInfoFactory.create(/* id= */ -1, new VibratorInfo[] {info1});
        VibratorInfo infoFromTwoInfos =
                VibratorInfoFactory.create(/* id= */ -3, new VibratorInfo[] {info1, info2});

        assertEquals(3, infoFromEmptyInfos.getId());
        assertEquals(-1, infoFromOneInfo.getId());
        assertEquals(-3, infoFromTwoInfos.getId());
    }

    @Test
    public void testCreatedInfo_fromEmptyVibratorInfos_returnsEmptyVibratorInfo() {
        VibratorInfo info = VibratorInfoFactory.create(/* id= */ 2, new VibratorInfo[] {});

        assertEqualContent(VibratorInfo.EMPTY_VIBRATOR_INFO, info);
    }

    @Test
    public void testCreatedInfo_fromSingleVibratorInfo_hasEqualContent() {
        VibratorInfo info = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS | IVibrator.CAP_FREQUENCY_CONTROL)
                .setSupportedEffects(VibrationEffect.EFFECT_TICK, VibrationEffect.EFFECT_THUD)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 20)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 30)
                .build();

        VibratorInfo createdInfo =
                VibratorInfoFactory.create(/* id= */ -1, new VibratorInfo[] {info});

        assertEqualContent(info, createdInfo);
    }

    @Test
    public void testCreatedInfo_hasEqualContentRegardlessOfSourceInfoOrder() {
        VibratorInfo info1 = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_FREQUENCY_CONTROL)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        VibratorInfo info2 = new VibratorInfo.Builder(/* id= */ 2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .build();

        assertEqualContent(
                VibratorInfoFactory.create(/* id= */ -1, new VibratorInfo[] {info1, info2}),
                VibratorInfoFactory.create(/* id= */ -1, new VibratorInfo[] {info2, info1}));
    }

    @Test
    public void testCreatedInfoContents() {
        VibratorInfo info1 = new VibratorInfo.Builder(/* id= */ -1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS | IVibrator.CAP_FREQUENCY_CONTROL)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK, VibrationEffect.EFFECT_POP)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 5)
                .build();
        VibratorInfo info2 = new VibratorInfo.Builder(/* id= */ -2)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS | IVibrator.CAP_AMPLITUDE_CONTROL)
                .setSupportedEffects(VibrationEffect.EFFECT_POP, VibrationEffect.EFFECT_THUD)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 10)
                .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 20)
                .build();
        VibratorInfo info3 = new VibratorInfo.Builder(/* id= */ -3)
                .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                .build();

        assertEquals(
                new VibratorInfo.Builder(/* id= */ 3)
                        .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                        .setSupportedEffects(VibrationEffect.EFFECT_POP)
                        .setSupportedPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 20)
                        .build(),
                VibratorInfoFactory.create(/* id= */ 3, new VibratorInfo[] {info1, info2}));
        assertEquals(
                new VibratorInfo.Builder(/* id= */ 3)
                        .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                        .build(),
                VibratorInfoFactory.create(/* id= */ 3, new VibratorInfo[] {info2, info3}));
        assertEquals(
                new VibratorInfo.Builder(/* id= */ 3).build(),
                VibratorInfoFactory.create(/* id= */ 3, new VibratorInfo[] {info1, info3}));
    }

    private static void assertEqualContent(VibratorInfo info1, VibratorInfo info2) {
        assertTrue(info1.equalContent(info2));
    }
}
