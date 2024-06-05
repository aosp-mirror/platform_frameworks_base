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

package android.os.test;

import android.annotation.NonNull;
import android.content.Context;
import android.hardware.vibrator.IVibrator;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorInfo;

/** Fake implementation of {@link Vibrator} for service tests. */
public final class FakeVibrator extends Vibrator {
    private final VibratorInfo mVibratorInfo;

    public FakeVibrator(Context context) {
        this(context, /* supportedPrimitives= */ null);
    }

    public FakeVibrator(Context context, int[] supportedPrimitives) {
        super(context);
        mVibratorInfo = buildInfoSupportingPrimitives(supportedPrimitives);
    }

    @Override
    public boolean hasVibrator() {
        return true;
    }

    @Override
    public boolean hasAmplitudeControl() {
        return true;
    }

    @Override
    public VibratorInfo getInfo() {
        return mVibratorInfo;
    }

    @Override
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe, String reason,
            @NonNull VibrationAttributes attributes) {
    }

    @Override
    public void cancel() {
    }

    @Override
    public void cancel(int usageFilter) {
    }

    private static VibratorInfo buildInfoSupportingPrimitives(int... primitives) {
        if (primitives == null || primitives.length == 0) {
            return VibratorInfo.EMPTY_VIBRATOR_INFO;
        }
        VibratorInfo.Builder builder = new VibratorInfo.Builder(/* id= */ 1)
                .setCapabilities(IVibrator.CAP_COMPOSE_EFFECTS);
        for (int primitive : primitives) {
            builder.setSupportedPrimitive(primitive, 10);
        }
        return builder.build();
    }
}
