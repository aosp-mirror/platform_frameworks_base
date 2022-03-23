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

import com.android.ims.ImsFeatureContainer;
/**
 *  Interface from ImsResolver to FeatureConnections.
 * Callback to FeatureConnections when a feature's status changes.
 * {@hide}
 */
oneway interface IImsServiceFeatureCallback {
    void imsFeatureCreated(in ImsFeatureContainer feature, int subId);
    // Reason defined in FeatureConnector.UnavailableReason
    void imsFeatureRemoved(int reason);
    // Status defined in ImsFeature.ImsState.
    void imsStatusChanged(int status, int subId);
    //Capabilities defined in ImsService.ImsServiceCapability
    void updateCapabilities(long capabilities);
}