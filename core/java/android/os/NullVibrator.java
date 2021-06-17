/*
 * Copyright (C) 2012 The Android Open Source Project
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

/**
 * Vibrator implementation that does nothing.
 *
 * @hide
 */
public class NullVibrator extends Vibrator {
    private static final NullVibrator sInstance = new NullVibrator();

    private NullVibrator() {
    }

    public static NullVibrator getInstance() {
        return sInstance;
    }

    @Override
    public boolean hasVibrator() {
        return false;
    }

    @Override
    public boolean isVibrating() {
        return false;
    }

    @Override
    public boolean hasAmplitudeControl() {
        return false;
    }

    @Override
    public void vibrate(int uid, String opPkg, VibrationEffect effect,
            String reason, VibrationAttributes attributes) {
    }

    @Override
    public void cancel() {
    }

    @Override
    public void cancel(int usageFilter) {
    }
}
