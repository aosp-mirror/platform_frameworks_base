/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.os.StatsDimensionsValueParcel;

/**
  * Binder interface to hold a PendingIntent for StatsCompanionService.
  * {@hide}
  */
interface IPendingIntentRef {

    /**
     * Sends a broadcast to the specified PendingIntent that it should getData now.
     * This should be only called from StatsCompanionService.
     */
     oneway void sendDataBroadcast(long lastReportTimeNs);

    /**
     * Send a broadcast to the specified PendingIntent notifying it that the list of active configs
     * has changed. This should be only called from StatsCompanionService.
     */
     oneway void sendActiveConfigsChangedBroadcast(in long[] configIds);

     /**
      * Send a broadcast to the specified PendingIntent, along with the other information
      * specified. This should only be called from StatsCompanionService.
      */
     oneway void sendSubscriberBroadcast(long configUid, long configId, long subscriptionId,
                                         long subscriptionRuleId, in String[] cookies,
                                         in StatsDimensionsValueParcel dimensionsValueParcel);
}
