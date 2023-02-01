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

import android.annotation.NonNull;
import android.content.Context;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;

/** Fake implementation of {@link Vibrator} for service tests. */
final class FakeVibrator extends Vibrator {

    FakeVibrator(Context context) {
        super(context);
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
    public void vibrate(int uid, String opPkg, @NonNull VibrationEffect vibe, String reason,
            @NonNull VibrationAttributes attributes) {
    }

    @Override
    public void cancel() {
    }

    @Override
    public void cancel(int usageFilter) {
    }
}
