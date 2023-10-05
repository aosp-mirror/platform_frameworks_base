/*
 * Copyright 2022 The Android Open Source Project
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
import android.app.ActivityManagerInternal;

import com.android.server.LocalServices;

/**
 * An interface to access the current user identity in an easy to fake for tests way.
 */
public interface CurrentUserIdentityInjector {

    /** A singleton for the real implementation of {@link CurrentUserIdentityInjector}. */
    CurrentUserIdentityInjector REAL = new Real();

    /** A {@link ActivityManagerInternal#getCurrentUserId()} call. */
    @UserIdInt int getCurrentUserId();

    /** The real implementation of {@link CurrentUserIdentityInjector}. */
    class Real implements CurrentUserIdentityInjector {

        protected Real() {
        }

        @Override
        public int getCurrentUserId() {
            return LocalServices.getService(ActivityManagerInternal.class).getCurrentUserId();
        }
    }
}
