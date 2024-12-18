/**
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

package com.android.server.broadcastradio;

import android.app.ActivityManager;
import android.os.Binder;
import android.os.UserHandle;

/**
 * Implementation for the controller to handle users in
 * {@link com.android.server.broadcastradio.BroadcastRadioService}
 */
public final class RadioServiceUserControllerImpl implements RadioServiceUserController {

    /**
     * @see RadioServiceUserController#isCurrentOrSystemUser()
     */
    @Override
    public boolean isCurrentOrSystemUser() {
        int callingUser = getCallingUserId();
        return callingUser == getCurrentUser() || callingUser == UserHandle.USER_SYSTEM;
    }

    /**
     * @see RadioServiceUserController#getCurrentUser()
     */
    @Override
    public int getCurrentUser() {
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

    /**
     * @see RadioServiceUserController#getCallingUserId()
     */
    @Override
    public int getCallingUserId() {
        return Binder.getCallingUserHandle().getIdentifier();
    }
}
