/*
 * Copyright 2017 The Android Open Source Project
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

package android.telephony.data;

import android.net.LinkProperties;
import android.telephony.data.DataProfile;
import android.telephony.data.IDataServiceCallback;

/**
 * {@hide}
 */
oneway interface IDataService
{
    void createDataServiceProvider(int slotId);
    void removeDataServiceProvider(int slotId);
    void setupDataCall(int slotId, int accessNetwork, in DataProfile dataProfile, boolean isRoaming,
                       boolean allowRoaming, int reason, in LinkProperties linkProperties,
                       IDataServiceCallback callback);
    void deactivateDataCall(int slotId, int cid, int reason, IDataServiceCallback callback);
    void setInitialAttachApn(int slotId, in DataProfile dataProfile, boolean isRoaming,
                             IDataServiceCallback callback);
    void setDataProfile(int slotId, in List<DataProfile> dps, boolean isRoaming,
                        IDataServiceCallback callback);
    void getDataCallList(int slotId, IDataServiceCallback callback);
    void registerForDataCallListChanged(int slotId, IDataServiceCallback callback);
    void unregisterForDataCallListChanged(int slotId, IDataServiceCallback callback);
}
