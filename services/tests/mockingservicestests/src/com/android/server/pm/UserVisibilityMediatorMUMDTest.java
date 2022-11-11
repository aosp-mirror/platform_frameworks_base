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

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.util.Log;

import org.junit.Test;

/**
 * Tests for {@link UserVisibilityMediator} tests for devices that support concurrent Multiple
 * Users on Multiple Displays (A.K.A {@code MUMD}).
 *
 * <p>Run as
 * {@code atest FrameworksMockingServicesTests:com.android.server.pm.UserVisibilityMediatorMUMDTest}
 */
public final class UserVisibilityMediatorMUMDTest extends UserVisibilityMediatorTestCase {

    private static final String TAG = UserVisibilityMediatorMUMDTest.class.getSimpleName();

    public UserVisibilityMediatorMUMDTest() {
        super(/* usersOnSecondaryDisplaysEnabled= */ true);
    }

    @Test
    public void testAssignUserToDisplay_systemUser() {
        assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplay(USER_SYSTEM, USER_SYSTEM, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_invalidDisplay() {
        assertThrows(IllegalArgumentException.class,
                () -> mMediator.assignUserToDisplay(USER_ID, USER_ID, INVALID_DISPLAY));
    }

    @Test
    public void testAssignUserToDisplay_currentUser() {
        mockCurrentUser(USER_ID);

        assertThrows(IllegalArgumentException.class,
                () -> mMediator.assignUserToDisplay(USER_ID, USER_ID, SECONDARY_DISPLAY_ID));

        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_startedProfileOfCurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplay(PROFILE_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_stoppedProfileOfCurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplay(PROFILE_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_displayAvailable() {
        mMediator.assignUserToDisplay(USER_ID, USER_ID, SECONDARY_DISPLAY_ID);

        assertUserAssignedToDisplay(USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testAssignUserToDisplay_displayAlreadyAssigned() {
        mMediator.assignUserToDisplay(USER_ID, USER_ID, SECONDARY_DISPLAY_ID);

        IllegalStateException e = assertThrows(IllegalStateException.class, () -> mMediator
                .assignUserToDisplay(OTHER_USER_ID, OTHER_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .matches("Cannot.*" + OTHER_USER_ID + ".*" + SECONDARY_DISPLAY_ID + ".*already.*"
                        + USER_ID + ".*");
    }

    @Test
    public void testAssignUserToDisplay_userAlreadyAssigned() {
        mMediator.assignUserToDisplay(USER_ID, USER_ID, SECONDARY_DISPLAY_ID);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mMediator.assignUserToDisplay(USER_ID, USER_ID, OTHER_SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .matches("Cannot.*" + USER_ID + ".*" + OTHER_SECONDARY_DISPLAY_ID + ".*already.*"
                        + SECONDARY_DISPLAY_ID + ".*");

        assertUserAssignedToDisplay(USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testAssignUserToDisplay_profileOnSameDisplayAsParent() {
        mMediator.assignUserToDisplay(PARENT_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplay(PROFILE_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertUserAssignedToDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testAssignUserToDisplay_profileOnDifferentDisplayAsParent() {
        mMediator.assignUserToDisplay(PARENT_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplay(PROFILE_USER_ID, PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertUserAssignedToDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testAssignUserToDisplay_profileDefaultDisplayParentOnSecondaryDisplay() {
        mMediator.assignUserToDisplay(PARENT_USER_ID, PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplay(PROFILE_USER_ID, PARENT_USER_ID, DEFAULT_DISPLAY));

        Log.v(TAG, "Exception: " + e);
        assertUserAssignedToDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testUnassignUserFromDisplay() {
        testAssignUserToDisplay_displayAvailable();

        mMediator.unassignUserFromDisplay(USER_ID);

        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testIsUserVisible_bgUserOnSecondaryDisplay() {
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s)", USER_ID)
                .that(mMediator.isUserVisible(USER_ID)).isTrue();
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // isUserVisible() for bg users relies only on the user / display assignments

    @Test
    public void testIsUserVisibleOnDisplay_currentUserUnassignedSecondaryDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_currentUserSecondaryDisplayAssignedToAnotherUser() {
        mockCurrentUser(USER_ID);
        assignUserToDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_startedProfileOfCurrentUserSecondaryDisplayAssignedToAnotherUser() {
        startDefaultProfile();
        mockCurrentUser(PARENT_USER_ID);
        assignUserToDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_stoppedProfileOfCurrentUserSecondaryDisplayAssignedToAnotherUser() {
        stopDefaultProfile();
        mockCurrentUser(PARENT_USER_ID);
        assignUserToDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_startedProfileOfCurrentUserOnUnassignedSecondaryDisplay() {
        startDefaultProfile();
        mockCurrentUser(PARENT_USER_ID);

        // TODO(b/244644281): change it to isFalse() once isUserVisible() is fixed (see note there)
        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_bgUserOnSecondaryDisplay() {
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_bgUserOnAnotherSecondaryDisplay() {
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, OTHER_SECONDARY_DISPLAY_ID)).isFalse();
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // the tests for isUserVisible(userId, display) for non-current users relies on the explicit
    // user / display assignments
    // TODO(b/244644281): add such tests if the logic change

    @Test
    public void testGetDisplayAssignedToUser_bgUserOnSecondaryDisplay() {
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_ID)
                .that(mMediator.getDisplayAssignedToUser(USER_ID))
                .isEqualTo(SECONDARY_DISPLAY_ID);
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // getDisplayAssignedToUser() for bg users relies only on the user / display assignments

    @Test
    public void testGetUserAssignedToDisplay_bgUserOnSecondaryDisplay() {
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(mMediator.getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    @Test
    public void testGetUserAssignedToDisplay_noUserOnSecondaryDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(mMediator.getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // getUserAssignedToDisplay() for bg users relies only on the user / display assignments
}
