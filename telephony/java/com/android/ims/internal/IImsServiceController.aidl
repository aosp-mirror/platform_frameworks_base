/*
 * Copyright (c) 2017 The Android Open Source Project
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

package com.android.ims.internal;

import com.android.ims.internal.IImsFeatureStatusCallback;
import com.android.ims.internal.IImsMMTelFeature;
import com.android.ims.internal.IImsRcsFeature;

/**
 * See ImsService and MMTelFeature for more information.
 * {@hide}
 */
interface IImsServiceController {
    IImsMMTelFeature createEmergencyMMTelFeature(int slotId, in IImsFeatureStatusCallback c);
    IImsMMTelFeature createMMTelFeature(int slotId, in IImsFeatureStatusCallback c);
    IImsRcsFeature createRcsFeature(int slotId, in IImsFeatureStatusCallback c);
    void removeImsFeature(int slotId, int featureType, in IImsFeatureStatusCallback c);
}
