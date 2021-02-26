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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import android.hardware.vibrator.IVibrator;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@Presubmit
@RunWith(JUnit4.class)
public class VibratorInfoTest {

    @Test
    public void testHasAmplitudeControl() {
        VibratorInfo noCapabilities = new InfoBuilder().build();
        assertFalse(noCapabilities.hasAmplitudeControl());
        VibratorInfo composeAndAmplitudeControl = new InfoBuilder()
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS
                        | IVibrator.CAP_AMPLITUDE_CONTROL)
                .build();
        assertTrue(composeAndAmplitudeControl.hasAmplitudeControl());
    }

    @Test
    public void testHasCapabilities() {
        VibratorInfo info = new InfoBuilder()
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .build();
        assertTrue(info.hasCapability(IVibrator.CAP_COMPOSE_EFFECTS));
        assertFalse(info.hasCapability(IVibrator.CAP_AMPLITUDE_CONTROL));
    }

    @Test
    public void testIsEffectSupported() {
        VibratorInfo noEffects = new InfoBuilder().build();
        VibratorInfo canClick = new InfoBuilder()
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .build();
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_UNKNOWN,
                noEffects.isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_YES,
                canClick.isEffectSupported(VibrationEffect.EFFECT_CLICK));
        assertEquals(Vibrator.VIBRATION_EFFECT_SUPPORT_NO,
                canClick.isEffectSupported(VibrationEffect.EFFECT_TICK));
    }

    @Test
    public void testIsPrimitiveSupported() {
        VibratorInfo info = new InfoBuilder()
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .build();
        assertTrue(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_TICK));

        // Returns false when there is no compose capability.
        info = new InfoBuilder()
                .setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .build();
        assertFalse(info.isPrimitiveSupported(VibrationEffect.Composition.PRIMITIVE_CLICK));
    }

    @Test
    public void testEquals() {
        InfoBuilder completeBuilder = new InfoBuilder()
                .setId(1)
                .setCapabilities(IVibrator.CAP_AMPLITUDE_CONTROL)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .setSupportedPrimitives(VibrationEffect.Composition.PRIMITIVE_CLICK)
                .setQFactor(2f)
                .setResonantFrequency(150f);
        VibratorInfo complete = completeBuilder.build();

        assertEquals(complete, complete);
        assertEquals(complete, completeBuilder.build());

        VibratorInfo completeWithComposeControl = completeBuilder
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .build();
        assertNotEquals(complete, completeWithComposeControl);

        VibratorInfo completeWithNoEffects = completeBuilder
                .setSupportedEffects()
                .setSupportedPrimitives()
                .build();
        assertNotEquals(complete, completeWithNoEffects);

        VibratorInfo completeWithUnknownEffects = completeBuilder
                .setSupportedEffects(null)
                .build();
        assertNotEquals(complete, completeWithNoEffects);

        VibratorInfo completeWithUnknownPrimitives = completeBuilder
                .setSupportedPrimitives(null)
                .build();
        assertNotEquals(complete, completeWithUnknownPrimitives);

        VibratorInfo completeWithDifferentF0 = completeBuilder
                .setResonantFrequency(complete.getResonantFrequency() + 3f)
                .build();
        assertNotEquals(complete, completeWithDifferentF0);

        VibratorInfo completeWithUnknownF0 = completeBuilder
                .setResonantFrequency(Float.NaN)
                .build();
        assertNotEquals(complete, completeWithUnknownF0);

        VibratorInfo completeWithUnknownQFactor = completeBuilder
                .setQFactor(Float.NaN)
                .build();
        assertNotEquals(complete, completeWithUnknownQFactor);

        VibratorInfo completeWithDifferentQFactor = completeBuilder
                .setQFactor(complete.getQFactor() + 3f)
                .build();
        assertNotEquals(complete, completeWithDifferentQFactor);

        VibratorInfo empty = new InfoBuilder().setId(1).build();
        VibratorInfo emptyWithKnownSupport = new InfoBuilder()
                .setId(1)
                .setSupportedEffects()
                .setSupportedPrimitives()
                .build();
        assertNotEquals(empty, emptyWithKnownSupport);
    }

    @Test
    public void testParceling() {
        VibratorInfo original = new InfoBuilder()
                .setId(1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS)
                .setSupportedEffects(VibrationEffect.EFFECT_CLICK)
                .setSupportedPrimitives(null)
                .setResonantFrequency(1.3f)
                .setQFactor(Float.NaN)
                .build();

        Parcel parcel = Parcel.obtain();
        original.writeToParcel(parcel, 0);
        parcel.setDataPosition(0);
        VibratorInfo restored = VibratorInfo.CREATOR.createFromParcel(parcel);
        assertEquals(original, restored);
    }

    private static class InfoBuilder {
        private int mId = 0;
        private int mCapabilities = 0;
        private int[] mSupportedEffects = null;
        private int[] mSupportedPrimitives = null;
        private float mResonantFrequency = Float.NaN;
        private float mQFactor = Float.NaN;

        public InfoBuilder setId(int id) {
            mId = id;
            return this;
        }

        public InfoBuilder setCapabilities(int capabilities) {
            mCapabilities = capabilities;
            return this;
        }

        public InfoBuilder setSupportedEffects(int... supportedEffects) {
            mSupportedEffects = supportedEffects;
            return this;
        }

        public InfoBuilder setSupportedPrimitives(int... supportedPrimitives) {
            mSupportedPrimitives = supportedPrimitives;
            return this;
        }

        public InfoBuilder setResonantFrequency(float resonantFrequency) {
            mResonantFrequency = resonantFrequency;
            return this;
        }

        public InfoBuilder setQFactor(float qFactor) {
            mQFactor = qFactor;
            return this;
        }

        public VibratorInfo build() {
            return new VibratorInfo(mId, mCapabilities, mSupportedEffects, mSupportedPrimitives,
                    mResonantFrequency, mQFactor);
        }
    }
}
