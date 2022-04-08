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

import android.telephony.data.DataCallResponse;

/**
 * The call back interface
 * @hide
 */
oneway interface IDataServiceCallback
{
    void onSetupDataCallComplete(int result, in DataCallResponse dataCallResponse);
    void onDeactivateDataCallComplete(int result);
    void onSetInitialAttachApnComplete(int result);
    void onSetDataProfileComplete(int result);
    void onRequestDataCallListComplete(int result, in List<DataCallResponse> dataCallList);
    void onDataCallListChanged(in List<DataCallResponse> dataCallList);
}
