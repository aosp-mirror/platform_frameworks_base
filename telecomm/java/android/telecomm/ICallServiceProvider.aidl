/*
 * Copyright (C) 2013 The Android Open Source Project
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

package android.telecomm;

import android.telecomm.ICallServiceProviderAdapter;

/**
 * Interface for applications interested in providing call-service implementations.  Only used in
 * outgoing-call scenarios where the best-candidate service to issue the call over may need to be
 * decided dynamically (unlike incoming call scenario where the call-service is known).
 *
 * Intended usage at time of writing is: Call intent received by the CallsManager, which in turn
 * gathers and binds all ICallServiceProvider implementations (using the framework).  The actual
 * bind is between each CallServiceProvider and the CallServiceProviderAdapter.  Once bound, the
 * CallsManager invokes the initiateDiscoveryProtocol API of each bound provider and waits until
 * either all providers reply (asynchronously) or some timeout is met. The resulted list is then
 * processed by the CallsManager and its helpers (potentially requesting input from the user) to
 * identify the best CallService.  The user should obviously be notified upon zero candidates as
 * well as all (one or more) candidates failing to issue the call.
 * @hide
 */
oneway interface ICallServiceProvider {

    /**
     * Sets an implementation of ICallServiceProviderAdapter to allow call-service providers to
     * communicate with the CallsManager.
     *
     * @param callServiceProviderAdapter The interface through which {@link ICallService}
     *     implementations are passed to CallsManager.
     */
    void setCallServiceProviderAdapter(in ICallServiceProviderAdapter callServiceProviderAdapter);

    /**
     * Provides the application with the opportunity to "register" ICallServiceProvider
     * implementations with the CallsManager for the purpose of issuing outgoing calls.
     */
    void initiateDiscoveryProtocol();
}
