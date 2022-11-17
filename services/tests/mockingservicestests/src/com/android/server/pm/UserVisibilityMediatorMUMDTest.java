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
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;

import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.Test;

/**
 * Tests for {@link UserVisibilityMediator} tests for devices that support concurrent Multiple
 * Users on Multiple Displays (A.K.A {@code MUMD}).
 *
 * <p>Run as
 * {@code atest FrameworksMockingServicesTests:com.android.server.pm.UserVisibilityMediatorMUMDTest}
 */
public final class UserVisibilityMediatorMUMDTest extends UserVisibilityMediatorTestCase {

    public UserVisibilityMediatorMUMDTest() {
        super(/* usersOnSecondaryDisplaysEnabled= */ true);
    }

    @Test
    public void testStartUser_systemUser() {
        int result = mMediator.startUser(USER_SYSTEM, USER_SYSTEM, FG, SECONDARY_DISPLAY_ID);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);
    }

    @Test
    public void testStartUser_invalidDisplay() {
        int result = mMediator.startUser(USER_ID, USER_ID, FG, INVALID_DISPLAY);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);
    }

    @Test
    public void testStartUser_displayAvailable() {
        int result = mMediator.startUser(USER_ID, USER_ID, BG, SECONDARY_DISPLAY_ID);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        assertIsNotCurrentUserOrRunningProfileOfCurrentUser(USER_ID);
        assertStartedProfileGroupIdOf(USER_ID, USER_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public void testStartUser_displayAlreadyAssigned() {
        startUserInSecondaryDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        int result = mMediator.startUser(USER_ID, USER_ID, BG, SECONDARY_DISPLAY_ID);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        stopUserAndAssertState(PROFILE_USER_ID);
    }

    @Test
    public void testStartUser_userAlreadyAssigned() {
        startUserInSecondaryDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.startUser(USER_ID, USER_ID, BG, SECONDARY_DISPLAY_ID);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);
    }

    @Test
    public void testStartUser_profileOnSameDisplayAsParent() {
        startUserInSecondaryDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.startUser(PROFILE_USER_ID, PARENT_USER_ID, BG, SECONDARY_DISPLAY_ID);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        stopUserAndAssertState(PROFILE_USER_ID);
    }

    @Test
    public void testStartUser_profileOnDifferentDisplayAsParent() {
        startUserInSecondaryDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.startUser(PROFILE_USER_ID, PARENT_USER_ID, BG,
                OTHER_SECONDARY_DISPLAY_ID);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        stopUserAndAssertState(PROFILE_USER_ID);
    }

    @Test
    public void testStartUser_profileDefaultDisplayParentOnSecondaryDisplay() {
        startUserInSecondaryDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.startUser(PROFILE_USER_ID, PARENT_USER_ID, BG, DEFAULT_DISPLAY);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        stopUserAndAssertState(PROFILE_USER_ID);
    }

    @Test
    public void testIsUserVisible_bgUserOnSecondaryDisplay() {
        startForegroundUser(OTHER_USER_ID);
        startUserInSecondaryDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s)", USER_ID)
                .that(mMediator.isUserVisible(USER_ID)).isTrue();
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // isUserVisible() for bg users relies only on the user / display assignments

    @Test
    public void testIsUserVisibleOnDisplay_currentUserUnassignedSecondaryDisplay() {
        startForegroundUser(USER_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_currentUserSecondaryDisplayAssignedToAnotherUser() {
        startForegroundUser(USER_ID);
        startUserInSecondaryDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_startedProfileOfCurrentUserSecondaryDisplayAssignedToAnotherUser() {
        startDefaultProfile();
        startForegroundUser(PARENT_USER_ID);
        startUserInSecondaryDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_stoppedProfileOfCurrentUserSecondaryDisplayAssignedToAnotherUser() {
        startForegroundUser(PARENT_USER_ID);
        startUserInSecondaryDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_startedProfileOfCurrentUserOnUnassignedSecondaryDisplay() {
        startDefaultProfile();
        startForegroundUser(PARENT_USER_ID);

        // TODO(b/244644281): change it to isFalse() once isUserVisible() is fixed (see note there)
        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_bgUserOnSecondaryDisplay() {
        startForegroundUser(OTHER_USER_ID);
        startUserInSecondaryDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_bgUserOnAnotherSecondaryDisplay() {
        startForegroundUser(OTHER_USER_ID);
        startUserInSecondaryDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, OTHER_SECONDARY_DISPLAY_ID)).isFalse();
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // the tests for isUserVisible(userId, display) for non-current users relies on the explicit
    // user / display assignments
    // TODO(b/244644281): add such tests if the logic change

    @Test
    public void testGetDisplayAssignedToUser_bgUserOnSecondaryDisplay() {
        startForegroundUser(OTHER_USER_ID);
        startUserInSecondaryDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_ID)
                .that(mMediator.getDisplayAssignedToUser(USER_ID))
                .isEqualTo(SECONDARY_DISPLAY_ID);
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // getDisplayAssignedToUser() for bg users relies only on the user / display assignments

    @Test
    public void testGetUserAssignedToDisplay_bgUserOnSecondaryDisplay() {
        startForegroundUser(OTHER_USER_ID);
        startUserInSecondaryDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(mMediator.getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    @Test
    public void testGetUserAssignedToDisplay_noUserOnSecondaryDisplay() {
        startForegroundUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(mMediator.getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // getUserAssignedToDisplay() for bg users relies only on the user / display assignments
}
