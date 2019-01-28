/*
 * Copyright (C) 2017 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server;

public interface AlarmManagerInternal {
    // Some other components in the system server need to know about
    // broadcast alarms currently in flight
    public interface InFlightListener {
        /** There is now an alarm pending delivery to the given app */
        void broadcastAlarmPending(int recipientUid);
        /** A broadcast alarm targeted to the given app has completed delivery */
        void broadcastAlarmComplete(int recipientUid);
    }

    public void removeAlarmsForUid(int uid);
    public void registerInFlightListener(InFlightListener callback);
}
