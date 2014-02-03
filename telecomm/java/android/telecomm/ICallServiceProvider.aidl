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

import android.telecomm.ICallServiceLookupResponse;

/**
 * Interface for applications interested in providing call-service implementations.  Only used in
 * outgoing-call scenarios where the best-candidate service to issue the call over may need to be
 * decided dynamically (unlike incoming call scenario where the call-service is known).
 *
 * Intended usage at time of writing is: Call intent received by the CallsManager, which in turn
 * gathers and binds all ICallServiceProvider implementations (using the framework). Once bound, the
 * CallsManager invokes the lookupCallServices API of each bound provider and waits until
 * either all providers reply (asynchronously) or some timeout is met. The resulted list is then
 * processed by the CallsManager and its helpers (potentially requesting input from the user) to
 * identify the best CallService.  The user should obviously be notified upon zero candidates as
 * well as all (one or more) candidates failing to issue the call.
 */
oneway interface ICallServiceProvider {

    /**
     * Initiates the process to retrieve the list of {@link ICallService}s implemented by
     * this provider.
     * TODO(santoscordon): Needs comments on how to populate the list within
     * ICallServiceLookupResponse and how to handle error conditions.
     *
     * @param response The response object through which the list of call services is sent.
     */
    void lookupCallServices(in ICallServiceLookupResponse response);
}
