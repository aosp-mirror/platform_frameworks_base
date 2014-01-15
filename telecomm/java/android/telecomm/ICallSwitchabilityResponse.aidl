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
 * Used by {@link ICallServiceSelector}s to return whether or not the relevant
 * call is switchable.
 * @hide
 */
oneway interface ICallSwitchabilityResponse {
    /**
     * Records whether or not the corresponding call can potentially be switched to another
     * call service.
     *
     * @param isSwitchable True if the associated call-service selector may be interested
     *         in switching call services.  Setting isSwitchable to true should generally
     *         guarantee the "select" API of the associated selector to be invoked, hence
     *         allowing the selector to return either the empty list (meaning pass, don't
     *         switch) or the prioritized list of call-services to attempt switching to.
     */
    void setIsSwitchable(boolean isSwitchable);
}
