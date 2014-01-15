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

package android.telecomm;

import android.telecomm.ICallService;
import android.telecomm.ICallServiceSelectionResponse;
import android.telecomm.ICallSwitchabilityResponse;

import java.util.List;

/**
 * Interface for call-service selector implementations.
 *
 * Call-service selectors are ultimately responsible for deciding which of the available call
 * service implementations should be used to place an outgoing call, as well as which service
 * to switch the call to upon system-provided opportunities to switch call services.
 *
 * Outgoing call scenario:
 *
 * Telecomm maintains a prioritized list of call-service selectors.  Upon attempting to issue
 * outgoing calls, the switchboard iterates through these (starting with the highest-priority
 * selector). It then invokes the "select" API below passing -- among other parameters -- the
 * list of available call services, excluding fully-utilized services that temporarily aren't
 * capable of accommodating additional calls.  Once invoked, selectors return a sorted subset
 * from the specified list, containing the preferred services through which to place the call.
 * Upon empty selection the switchboard continues to the next selector.  Otherwise, upon non-
 * empty selection, the returned call services are attempted in the specified order. The flow
 * is concluded either when the call is placed by one of the specified services (e.g. ringing)
 * or upon failure to connect by the time the set of selectors is exhausted. Failed calls are
 * essentially abandoned by this flow and then picked up by the switchboard's monitor.
 *
 * Note that attempted-yet-failed call services within one outgoing-call cycle may be omitted
 * from the set passed to the next selector. As for selector priority, at the time of writing
 * this is intended to be a blend of built-in priorities (e.g. to handle emergency calls) and
 * user-specified preferences (via settings, e.g. electing to use a particular selector prior
 * to attempting the system-default call-service selector).
 *
 * Call-services switching scenario (applying to both incoming and outgoing calls):
 *
 * The switchboard may invoke any active selector (a selector associated with one or more on-
 * going calls) up to once per ongoing call at its discretion (e.g. periodically), once again
 * passing the available call services to the "select" API.  As in the outgoing-call scenario
 * above, returning the empty list means "pass" -- basically indicating that the current call
 * service for this call need not be switched at this time. In cases where switching isn't at
 * all supported (either for a given call or globally across a given selector) , isSwitchable
 * below can return false blindly to suppress all "select" calls beyond the initial one (that
 * is used to establish outgoing calls).
 *
 * @hide
 */
oneway interface ICallServiceSelector {

    /**
     * Initiates the process to retrieve the sorted set of call services that are preferred
     * by this call-service selector.
     * TODO(gilad): Pass reduced-visibility call-service objects, see isSwitchable/callId below.
     * TODO(gilad): Consider passing a Call object encapsulating the relevant call details (e.g.
     * handle, contactInfo, etc).
     *
     * @param handle The handle to dial.
     * @param callServiceBinders The list of binders to select call services from.
     * @param response The response object through which the selected services are passed back
     *         to the switchboard.
     */
    void select(
            String handle,
            in List<IBinder> callServiceBinders,
            in ICallServiceSelectionResponse response);

    /**
     * Determines if the specified ongoing call can/should be switched from the currently-used
     * call service to another.
     * TODO(gilad): Pass a CallInfo instead that contains (among other fields) the callId.
     *
     * @param callId The identifier of the call to disconnect.
     * @param response The response object to be populated and returned to switchboard.
     */
    void isSwitchable(String callId, in ICallSwitchabilityResponse response);
}
