/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.internal.telecom;

import android.content.ComponentName;
import android.telecom.CallScreeningService;

/**
 * Internal remote callback interface for call screening services.
 *
 * @see android.telecom.CallScreeningService
 *
 * {@hide}
 */
oneway interface ICallScreeningAdapter {
    void onScreeningResponse(String callId, in ComponentName componentName,
            in CallScreeningService.ParcelableCallResponse response);
}
