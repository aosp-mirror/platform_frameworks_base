/*
 * Copyright 2022 The Android Open Source Project
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

package android.credentials;

import java.util.List;

import android.credentials.CredentialProviderInfo;
import android.credentials.ClearCredentialStateRequest;
import android.credentials.CreateCredentialRequest;
import android.credentials.GetCandidateCredentialsRequest;
import android.credentials.GetCredentialRequest;
import android.credentials.RegisterCredentialDescriptionRequest;
import android.credentials.UnregisterCredentialDescriptionRequest;
import android.credentials.IClearCredentialStateCallback;
import android.credentials.ICreateCredentialCallback;
import android.credentials.IGetCredentialCallback;
import android.credentials.IGetCandidateCredentialsCallback;
import android.credentials.IPrepareGetCredentialCallback;
import android.credentials.ISetEnabledProvidersCallback;
import android.content.ComponentName;
import android.os.IBinder;
import android.os.ICancellationSignal;

/**
 * System private interface for talking to the credential manager service.
 *
 * @hide
 */
interface ICredentialManager {

    @nullable ICancellationSignal executeGetCredential(in GetCredentialRequest request, in IGetCredentialCallback callback, String callingPackage);

    @nullable ICancellationSignal executePrepareGetCredential(in GetCredentialRequest request, in IPrepareGetCredentialCallback prepareGetCredentialCallback, in IGetCredentialCallback getCredentialCallback, String callingPackage);

    @nullable ICancellationSignal executeCreateCredential(in CreateCredentialRequest request, in ICreateCredentialCallback callback, String callingPackage);

    @nullable ICancellationSignal getCandidateCredentials(in GetCredentialRequest request, in IGetCandidateCredentialsCallback callback, in IBinder clientCallback, String callingPackage);

    @nullable ICancellationSignal clearCredentialState(in ClearCredentialStateRequest request, in IClearCredentialStateCallback callback, String callingPackage);

    void setEnabledProviders(in List<String> primaryProviders, in List<String> providers, in int userId, in ISetEnabledProvidersCallback callback);

    void registerCredentialDescription(in RegisterCredentialDescriptionRequest request, String callingPackage);

    void unregisterCredentialDescription(in UnregisterCredentialDescriptionRequest request, String callingPackage);

    boolean isEnabledCredentialProviderService(in ComponentName componentName, String callingPackage);

    List<CredentialProviderInfo> getCredentialProviderServices(in int userId, in int providerFilter);

    List<CredentialProviderInfo> getCredentialProviderServicesForTesting(in int providerFilter);

    boolean isServiceEnabled();
}

