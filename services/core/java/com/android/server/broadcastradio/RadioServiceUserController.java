/**
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

package com.android.server.broadcastradio;

/**
 * Controller interface to handle users in
 * {@link com.android.server.broadcastradio.BroadcastRadioService}
 */
public interface RadioServiceUserController {

    /**
     * Check if the user calling the method in Broadcast Radio Service is the current user or the
     * system user.
     *
     * @return {@code true} if the user calling this method is the current user of system user,
     * {@code false} otherwise.
     */
    boolean isCurrentOrSystemUser();

    /**
     * Get current foreground user for Broadcast Radio Service
     *
     * @return foreground user id.
     */
    int getCurrentUser();

    /**
     * Get id of the user handle assigned to the process that sent the binder transaction that is
     * being processed
     *
     * @return Id of the user handle
     */
    int getCallingUserId();
}