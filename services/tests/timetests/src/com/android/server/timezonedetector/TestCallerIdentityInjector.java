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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import android.annotation.UserIdInt;

/** A fake {@link CallerIdentityInjector} used in tests. */
public class TestCallerIdentityInjector implements CallerIdentityInjector {

    private long mToken = 9999L;
    private int mCallingUserId;
    private Integer mCurrentCallingUserId;

    /** Initializes the calling user ID. */
    public void initializeCallingUserId(@UserIdInt int userId) {
        mCallingUserId = userId;
        mCurrentCallingUserId = userId;
    }

    @Override
    public int resolveUserId(int userId, String debugInfo) {
        return userId;
    }

    @Override
    public int getCallingUserId() {
        assertNotNull("callingUserId has been cleared", mCurrentCallingUserId);
        return mCurrentCallingUserId;
    }

    @Override
    public long clearCallingIdentity() {
        mCurrentCallingUserId = null;
        return mToken;
    }

    @Override
    public void restoreCallingIdentity(long token) {
        assertEquals(token, mToken);
        mCurrentCallingUserId = mCallingUserId;
    }
}
