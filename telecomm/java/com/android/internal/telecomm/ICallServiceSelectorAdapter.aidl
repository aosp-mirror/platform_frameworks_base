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

import android.net.Uri;
import android.os.Bundle;
import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;

import java.util.List;

/**
 * Internal remote interface for call service selector adapter.
 *
 * @see android.telecomm.CallServiceSelectorAdapter
 *
 * @hide
 */
oneway interface ICallServiceSelectorAdapter {
    void setSelectedCallServices(
            String callId,
            in List<CallServiceDescriptor> selectedCallServiceDescriptors);

    void cancelOutgoingCall(String callId);

    void setHandoffInfo(String callId, in Uri handle, in Bundle extras);
}
