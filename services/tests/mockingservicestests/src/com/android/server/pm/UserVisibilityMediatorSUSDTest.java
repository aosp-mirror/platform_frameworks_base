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
package com.android.server.pm;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;

import org.junit.Test;

/**
 * Tests for {@link UserVisibilityMediator} tests for devices that DO NOT support concurrent
 * multiple users on multiple displays (A.K.A {@code SUSD} - Single User on Single Device).
 *
 * <p>Run as
 * {@code atest FrameworksMockingServicesTests:com.android.server.pm.UserVisibilityMediatorSUSDTest}
 */
public final class UserVisibilityMediatorSUSDTest extends UserVisibilityMediatorTestCase {

    public UserVisibilityMediatorSUSDTest() {
        super(/* usersOnSecondaryDisplaysEnabled= */ false);
    }

    @Test
    public void testStartBgUser_onSecondaryDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_ID);
        expectNoDisplayAssignedToUser(USER_ID);

        expectNoUserAssignedToDisplay(SECONDARY_DISPLAY_ID);

        listener.verify();
    }
}
