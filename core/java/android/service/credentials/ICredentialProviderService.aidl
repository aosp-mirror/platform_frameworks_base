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

package android.service.credentials;

import android.os.ICancellationSignal;
import android.service.credentials.BeginGetCredentialRequest;
import android.service.credentials.BeginCreateCredentialRequest;
import android.service.credentials.IBeginGetCredentialCallback;
import android.service.credentials.ClearCredentialStateRequest;
import android.service.credentials.IBeginCreateCredentialCallback;
import android.service.credentials.IClearCredentialStateCallback;
import android.os.ICancellationSignal;

/**
 * Interface from the system to a credential provider service.
 *
 * @hide
 */
oneway interface ICredentialProviderService {
    void onBeginGetCredential(in BeginGetCredentialRequest request, in IBeginGetCredentialCallback callback);
    void onBeginCreateCredential(in BeginCreateCredentialRequest request, in IBeginCreateCredentialCallback callback);
    void onClearCredentialState(in ClearCredentialStateRequest request, in IClearCredentialStateCallback callback);
}
