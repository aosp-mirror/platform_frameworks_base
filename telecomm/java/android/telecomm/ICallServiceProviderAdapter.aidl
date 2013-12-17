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

import android.telecomm.ICallService;

/**
 * Provides ICallServiceProvider implementations with the relevant CallsManager APIs.
 * @hide
 */
oneway interface ICallServiceProviderAdapter {

    /**
     * Provides the CallsManager with the services made available by this application.
     *
     * @param callServices The relevant services to make the CallsManager aware of. Parameter is
     *     a list of IBinder which can be cast to ICallService.
     *     NOTE: IBinder is required by AIDL processor when passing a list of interfaces.
     */
    void registerCallServices(in List<IBinder> callServices);
}
