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

import android.os.Bundle;

/**
 * Used by {@link ICallServiceProvider} to return a list of {@link ICallService} implementations or
 * an errorCode in case of error.
 */
oneway interface ICallServiceLookupResponse {
    /**
     * Receives the list of {@link ICallServices} as a data value in the bundle parameter.
     * TODO(santoscordon): Needs more specifics about the data key used for the list.
     *
     * @param bundle Container for the list of call services.
     */
    void onResult(in Bundle bundle);

    /**
     * Receives error code upon failure in retrieving the list of call services.
     * TODO(santoscordon): Needs list of potential error codes. Also, do we really need this
     * method or can we return codes as part of onResult?
     *
     * @param errorCode Error code describing the error condition.
     */
    void onError(int errorCode);
}
