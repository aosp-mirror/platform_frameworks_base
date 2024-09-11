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

import android.app.ActivityManager;
import android.os.Binder;
import android.os.UserHandle;

/**
 * Controller to handle users in {@link com.android.server.broadcastradio.BroadcastRadioService}
 */
public final class RadioServiceUserController {

    private RadioServiceUserController() {
        throw new UnsupportedOperationException(
                "RadioServiceUserController class is noninstantiable");
    }

    /**
     * Check if the user calling the method in Broadcast Radio Service is the current user or the
     * system user.
     *
     * @return {@code true} if the user calling this method is the current user of system user,
     * {@code false} otherwise.
     */
    public static boolean isCurrentOrSystemUser() {
        int callingUser = Binder.getCallingUserHandle().getIdentifier();
        return callingUser == getCurrentUser() || callingUser == UserHandle.USER_SYSTEM;
    }

    /**
     * Get current foreground user for Broadcast Radio Service
     *
     * @return foreground user id.
     */
    public static int getCurrentUser() {
        int userId = UserHandle.USER_NULL;
        final long identity = Binder.clearCallingIdentity();
        try {
            userId = ActivityManager.getCurrentUser();
        } catch (RuntimeException e) {
            // Activity manager not running, nothing we can do assume user 0.
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
        return userId;
    }
}
