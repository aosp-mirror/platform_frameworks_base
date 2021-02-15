/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;
import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.os.UserHandle;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class LockPatternUtilsTest {

    private static final int INVALID_USER_ID = -1;
    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    @Test
    public void testUserFrp_isNotRegularUser() throws Exception {
        assertTrue(LockPatternUtils.USER_FRP < 0);
    }

    @Test
    public void testUserFrp_isNotAReservedSpecialUser() throws Exception {
        assertNotEquals(UserHandle.USER_NULL, LockPatternUtils.USER_FRP);
        assertNotEquals(UserHandle.USER_ALL, LockPatternUtils.USER_FRP);
        assertNotEquals(UserHandle.USER_CURRENT, LockPatternUtils.USER_FRP);
        assertNotEquals(UserHandle.USER_CURRENT_OR_SELF, LockPatternUtils.USER_FRP);
    }

    @Test
    public void getCredentialTypeForUser_invalidUserId_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException.class,
                () -> new LockPatternUtils(mContext).getCredentialTypeForUser(INVALID_USER_ID));
    }
}
