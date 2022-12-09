/**
 * Copyright (c) 2020, The Android Open Source Project
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

import android.os.CombinedVibration;
import android.os.IVibratorStateListener;
import android.os.VibrationAttributes;
import android.os.VibratorInfo;

/** {@hide} */
interface IVibratorManagerService {
    int[] getVibratorIds();
    VibratorInfo getVibratorInfo(int vibratorId);
    boolean isVibrating(int vibratorId);
    boolean registerVibratorStateListener(int vibratorId, in IVibratorStateListener listener);
    boolean unregisterVibratorStateListener(int vibratorId, in IVibratorStateListener listener);
    boolean setAlwaysOnEffect(int uid, String opPkg, int alwaysOnId,
            in CombinedVibration vibration, in VibrationAttributes attributes);
    void vibrate(int uid, int displayId, String opPkg, in CombinedVibration vibration,
            in VibrationAttributes attributes, String reason, IBinder token);
    void cancelVibrate(int usageFilter, IBinder token);
}
