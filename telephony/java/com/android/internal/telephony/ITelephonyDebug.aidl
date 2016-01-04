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

package com.android.internal.telephony;

import android.os.Bundle;


/**
 * Interface used to interact with the Telephony debug service.
 *
 * {@hide}
 */
interface ITelephonyDebug {

    /**
     * Write telephony event
     * @param timestamp returned by System.currentTimeMillis()
     * @param phoneId for which event is written
     * @param tag constant defined in TelephonyEventLog
     * @param param1 optional
     * @param param2 optional
     * @param data optional
     */
    void writeEvent(long timestamp, int phoneId, int tag, int param1, int param2, in Bundle data);
}
