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

import static android.os.UserHandle.USER_SYSTEM;

import static org.junit.Assert.assertThrows;

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
    public void testAssignUserToDisplay_otherDisplay_currentUser() {
        mockCurrentUser(USER_ID);

        assertThrows(UnsupportedOperationException.class,
                () -> mMediator.assignUserToDisplay(USER_ID, USER_ID, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_otherDisplay_startProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();

        assertThrows(UnsupportedOperationException.class, () -> mMediator
                .assignUserToDisplay(PROFILE_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_otherDisplay_stoppedProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertThrows(UnsupportedOperationException.class, () -> mMediator
                .assignUserToDisplay(PROFILE_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testUnassignUserFromDisplay_ignored() {
        mockCurrentUser(USER_ID);

        mMediator.unassignUserFromDisplay(USER_SYSTEM);
        mMediator.unassignUserFromDisplay(USER_ID);
        mMediator.unassignUserFromDisplay(OTHER_USER_ID);

        assertNoUserAssignedToDisplay();
    }
}
