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

import android.os.IBinder;
import java.util.List;

/**
 * Used by {@link ICallServiceProvider} to return a list of {@link ICallService} implementations.
 * @hide
 */
oneway interface ICallServiceLookupResponse {
    /**
     * Forwards the list of {@link ICallService}s as a list of {@link IBinder}s to be processed by
     * Telecomm which will choose which call service, among potentially many, to place a call.
     *
     * @param callServices List of call services from {@link ICallServiceProvider}.
     * TODO(gilad): Rename to callServiceBinders.
     */
    void setCallServices(in List<IBinder> callServices);
}
