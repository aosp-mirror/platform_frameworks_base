/*
 * Copyright (C) 2015 The Android Open Source Project
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
package android.hardware.fingerprint;

import android.hardware.fingerprint.Fingerprint;
import android.os.Bundle;
import android.os.UserHandle;

/**
 * Callback when lockout period expired and clients are allowed to authenticate again.
 * @hide
 */
interface IFingerprintServiceLockoutResetCallback {

    /** Method is synchronous so wakelock is held when this is called from a WAKEUP alarm. */
    void onLockoutReset(long deviceId);
}
