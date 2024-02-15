/*
 * Copyright (C) 2024 The Android Open Source Project
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

package com.android.internal.widget;

/**
 * Callback interface between LockSettingService and other system services to be notified about the
 * state of primary authentication (i.e. PIN/pattern/password).
 * @hide
 */
public interface LockSettingsStateListener {
    /**
     * Defines behavior in response to a successful authentication
     * @param userId The user Id for the requested authentication
     */
    void onAuthenticationSucceeded(int userId);

    /**
     * Defines behavior in response to a failed authentication
     * @param userId The user Id for the requested authentication
     */
    void onAuthenticationFailed(int userId);
}
