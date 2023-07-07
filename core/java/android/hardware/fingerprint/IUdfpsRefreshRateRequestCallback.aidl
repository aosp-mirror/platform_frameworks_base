/*
 * Copyright (C) 2022 The Android Open Source Project
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
package android.hardware.fingerprint;

/**
 * A callback for UDFPS refresh rate. This allows other components to
 * perform certain actions when the refresh rate is enabled or disabled.
 * For example, a display manager implementation can subscribe to these
 * events from UdfpsController when refresh rate is enabled or disabled.
 *
 * @hide
 */
oneway interface IUdfpsRefreshRateRequestCallback {
    /**
     * Sets the appropriate display refresh rate for UDFPS.
     *
     * @param displayId The displayId for which the refresh rate should be set. See
     *        {@link android.view.Display#getDisplayId()}.
     */
    void onRequestEnabled(int displayId);

    /**
     * Unsets the appropriate display refresh rate for UDFPS.
     *
     * @param displayId The displayId for which the refresh rate should be unset. See
     *        {@link android.view.Display#getDisplayId()}.
     */
    void onRequestDisabled(int displayId);

    /**
     * To avoid delay in switching refresh rate when activating LHBM, allow screens to request
     * higher refersh rate if auth is possible on particular screen
     *
     * @param displayId The displayId for which the refresh rate should be unset. See
     *        {@link android.view.Display#getDisplayId()}.
     * @param isPossible If authentication is possible on particualr screen
     */
    void onAuthenticationPossible(int displayId, boolean isPossible);
}

