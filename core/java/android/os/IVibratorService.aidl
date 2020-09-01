/**
 * Copyright (c) 2007, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.os;

import android.os.VibrationEffect;
import android.os.VibrationAttributes;
import android.os.IVibratorStateListener;

/** {@hide} */
interface IVibratorService
{
    boolean hasVibrator();
    boolean isVibrating();
    boolean registerVibratorStateListener(in IVibratorStateListener listener);
    boolean unregisterVibratorStateListener(in IVibratorStateListener listener);
    boolean hasAmplitudeControl();
    int[] areEffectsSupported(in int[] effectIds);
    boolean[] arePrimitivesSupported(in int[] primitiveIds);
    boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId, in VibrationEffect effect,
            in VibrationAttributes attributes);
    void vibrate(int uid, String opPkg, in VibrationEffect effect,
            in VibrationAttributes attributes, String reason, IBinder token);
    void cancelVibrate(IBinder token);
}

