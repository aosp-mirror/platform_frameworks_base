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

/** A fake {@link CurrentUserIdentityInjector} used in tests. */
public class TestCurrentUserIdentityInjector implements CurrentUserIdentityInjector {

    private Integer mCurrentUserId;

    /** Initializes the current user ID. */
    public void initializeCurrentUserId(@UserIdInt int userId) {
        mCurrentUserId = userId;
    }

    @Override
    public int getCurrentUserId() {
        return mCurrentUserId;
    }
}
