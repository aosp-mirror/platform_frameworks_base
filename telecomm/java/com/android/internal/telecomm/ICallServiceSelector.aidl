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

import android.telecomm.CallInfo;
import android.telecomm.CallServiceDescriptor;

import com.android.internal.telecomm.ICallService;
import com.android.internal.telecomm.ICallServiceSelectorAdapter;

import java.util.List;

/**
 * Internal remote interface for call service selectors.
 *
 * @see android.telecomm.CallServiceSelector
 *
 * @hide
 */
oneway interface ICallServiceSelector {
    void setCallServiceSelectorAdapter(in ICallServiceSelectorAdapter adapter);

    void select(in CallInfo callInfo, in List<CallServiceDescriptor> callServiceDescriptors);

    void onCallUpdated(in CallInfo callInfo);

    void onCallRemoved(String callId);
}
