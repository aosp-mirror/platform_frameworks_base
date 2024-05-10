/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.server.timezonedetector;

import android.annotation.UserIdInt;
import android.app.ActivityManager;
import android.os.Binder;
import android.os.UserHandle;

/**
 * An interface to wrap various difficult-to-intercept calls that services make to access / manage
 * caller identity, e.g. {@link Binder#clearCallingIdentity()}.
 */
public interface CallerIdentityInjector {

    /** A singleton for the real implementation of {@link CallerIdentityInjector}. */
    CallerIdentityInjector REAL = new Real();

    /**
     * A {@link ActivityManager#handleIncomingUser} call. This can be used to map the abstract
     * user ID value USER_CURRENT to the actual user ID.
     */
    @UserIdInt int resolveUserId(@UserIdInt int userId, String debugInfo);

    /** A {@link UserHandle#getCallingUserId()} call. */
    @UserIdInt int getCallingUserId();

    /** A {@link Binder#clearCallingIdentity()} call. */
    long clearCallingIdentity();

    /** A {@link Binder#restoreCallingIdentity(long)} ()} call. */
    void restoreCallingIdentity(long token);

    /** The real implementation of {@link CallerIdentityInjector}. */
    class Real implements CallerIdentityInjector {

        protected Real() {
        }

        @Override
        public int resolveUserId(@UserIdInt int userId, String debugName) {
            return ActivityManager.handleIncomingUser(Binder.getCallingPid(),
                    Binder.getCallingUid(), userId, false, false, debugName, null);
        }

        @Override
        public int getCallingUserId() {
            return UserHandle.getCallingUserId();
        }

        @Override
        public long clearCallingIdentity() {
            return Binder.clearCallingIdentity();
        }

        @Override
        public void restoreCallingIdentity(long token) {
            Binder.restoreCallingIdentity(token);
        }
    }
}
