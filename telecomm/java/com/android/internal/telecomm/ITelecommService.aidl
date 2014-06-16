/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.internal.telecomm;

import android.content.ComponentName;

/**
 * Interface used to interact with Telecomm. Mostly this is used by TelephonyManager for passing
 * commands that were previously handled by ITelephony.
 * {@hide}
 */
interface ITelecommService {

    /**
     * Silence the ringer if an incoming call is currently ringing.
     * (If vibrating, stop the vibrator also.)
     *
     * It's safe to call this if the ringer has already been silenced, or
     * even if there's no incoming call.  (If so, this method will do nothing.)
     */
    void silenceRinger();

    /**
     * Brings the in-call screen to the foreground if there is an active call.
     *
     * @param showDialpad if true, make the dialpad visible initially.
     */
    void showCallScreen(boolean showDialpad);

    /**
     * Returns the component name of the phone application installed on the system partition.
     */
    ComponentName getSystemPhoneApplication();
}
