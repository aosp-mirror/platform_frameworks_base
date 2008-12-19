/*
 * Copyright (c) 2007 The Android Open Source Project
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

import android.os.IParentalControlCallback;

/**
 * System private API for direct access to the checkin service.
 * Users should use the content provider instead.
 *
 * @see android.provider.Checkin
 * {@hide}
 */
interface ICheckinService {
    /** Direct submission of crash data; returns after writing the crash. */
    void reportCrashSync(in byte[] crashData);

    /** Asynchronous "fire and forget" version of crash reporting. */
    oneway void reportCrashAsync(in byte[] crashData);

    /** Reboot into the recovery system and wipe all user data. */
    void masterClear();

    /**
     * Determine if the device is under parental control. Return null if
     * we are unable to check the parental control status.
     */
    void getParentalControlState(IParentalControlCallback p,
                                 String requestingApp);
}
