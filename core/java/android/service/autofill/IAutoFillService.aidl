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

package android.service.autofill;

import android.os.IBinder;
import android.service.autofill.ConvertCredentialRequest;
import android.service.autofill.IConvertCredentialCallback;
import android.service.autofill.FillRequest;
import android.service.autofill.IFillCallback;
import android.service.autofill.ISaveCallback;
import android.service.autofill.SaveRequest;
import com.android.internal.os.IResultReceiver;

/**
 * Interface from the system to an auto fill service.
 *
 * @hide
 */
oneway interface IAutoFillService {
    void onConnectedStateChanged(boolean connected);
    void onFillRequest(in FillRequest request, in IFillCallback callback);
    void onFillCredentialRequest(in FillRequest request, in IFillCallback callback,
        in IBinder client);
    void onSaveRequest(in SaveRequest request, in ISaveCallback callback);
    void onSavedPasswordCountRequest(in IResultReceiver receiver);
    void onConvertCredentialRequest(in ConvertCredentialRequest convertCredentialRequest, in IConvertCredentialCallback convertCredentialCallback);
}
