/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.service.autofill.augmented;

import android.content.ComponentName;
import android.os.IBinder;

import android.service.autofill.augmented.IFillCallback;
import android.view.autofill.AutofillId;
import android.view.autofill.AutofillValue;

import java.util.List;

/**
 * Interface from the system to an Augmented Autofill service.
 *
 * @hide
 */
oneway interface IAugmentedAutofillService {
    void onConnected();
    void onDisconnected();
    void onFillRequest(int sessionId, in IBinder autofillManagerClient, int taskId,
                       in ComponentName activityComponent, in AutofillId focusedId,
                       in AutofillValue focusedValue, long requestTime, in IFillCallback callback);

    void onDestroyAllFillWindowsRequest();
}
