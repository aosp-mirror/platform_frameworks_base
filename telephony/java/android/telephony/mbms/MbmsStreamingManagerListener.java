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

package android.telephony.mbms;

import java.util.List;

/**
 * A Parcelable class with Cell-Broadcast service information.
 * @hide
 */
public class MbmsStreamingManagerListener extends IMbmsStreamingManagerListener.Stub {

    public final static int ERROR_CARRIER_NOT_SUPPORTED      = 1;
    public final static int ERROR_UNABLE_TO_INITIALIZE       = 2;
    public final static int ERROR_UNABLE_TO_ALLOCATE_MEMORY  = 3;


    public void error(int errorCode, String message) {
        // default implementation empty
    }

    /**
     * Called to indicate published Streaming Services have changed.
     *
     * This will only be called after the application has requested
     * a list of streaming services and specified a service class list
     * of interest AND the results of a subsequent getStreamServices
     * call with the same service class list would return different
     * results.
     *
     * @param services a List of StreamingServiceInfos
     *
     */
    public void streamingServicesUpdated(List<StreamingServiceInfo> services) {
        // default implementation empty
    }

    /**
     * Called to indicate the active Streaming Services have changed.
     *
     * This will be caused whenever a new service starts streaming or whenever
     * MbmsStreamServiceManager.getActiveStreamingServices is called.
     *
     * @param services a list of StreamingServiceInfos.  May be empty if
     *                 there are no active StreamingServices
     */
    public void activeStreamingServicesUpdated(List<StreamingServiceInfo> services) {
        // default implementation empty
    }
}
