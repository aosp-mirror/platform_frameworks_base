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

/**
 * Defines constants for use with the Telecomm system.
 */
public final class TelecommConstants {
    /**
     * <p>Activity action: Starts the UI for handing an incoming call. This intent starts the
     * in-call UI by notifying the Telecomm system that an incoming call exists for a specific call
     * service (see {@link android.telecomm.ICallService}). Telecomm reads the Intent extras to find
     * and bind to the appropriate {@link android.telecomm.ICallServiceProvider} and
     * {@link android.telecomm.ICallService} implementations which Telecomm will ultimately use to
     * control and get information about the call.</p>
     *
     * <p>Input: get*Extra field {@link #EXTRA_CALL_SERVICE_PROVIDER} contains the component name
     * of the {@link android.telecomm.ICallServiceProvider} service Telecomm should bind to.
     * {@link #EXTRA_CALL_SERVICE_ID} contains a string-based identifier that Telecomm will pass to
     * {@link ICallServiceProvider#getCallService} to get the appropriate {@link ICallService}.</p>
     *
     * TODO(santoscordon): Needs permissions.
     * TODO(santoscordon): Consider moving this into a simple method call on a system service.
     */
    public static final String ACTION_INCOMING_CALL = "android.intent.action.INCOMING_CALL";

    /**
     * Extra for {@link #ACTION_INCOMING_CALL} that contains the component name of the call-service
     * provider used by Telecomm to obtain the correct call service for the incoming call.
     */
    public static final String EXTRA_CALL_SERVICE_PROVIDER =
            "android.intent.extra.CALL_SERVICE_PROVIDER";

    /**
     * Extra for {@link #ACTION_INCOMING_CALL} that contains the String-based ID that Telecomm will
     * use to obtain the correct call service for the incoming call via
     * {@link android.telecomm.ICallServiceProvider#getCallService}.
     */
    public static final String EXTRA_CALL_SERVICE_ID = "android.intent.extra.CALL_SERVICE_ID";
}
