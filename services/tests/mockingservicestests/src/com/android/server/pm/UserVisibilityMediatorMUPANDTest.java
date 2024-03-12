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

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserVisibilityChangedEvent.onInvisible;
import static com.android.server.pm.UserVisibilityChangedEvent.onVisible;
import static com.android.server.pm.UserVisibilityMediator.INITIAL_CURRENT_USER_ID;

import org.junit.Test;

/**
 * Tests for {@link UserVisibilityMediator} tests for devices that support not only concurrent
 * Multiple Users on Multiple Displays, but also let background users to be visible in the default
 * display (A.K.A {@code MUPAND} - MUltiple Passengers, No Driver).
 *
 * <p> Run as {@code
* atest FrameworksMockingServicesTests:com.android.server.pm.UserVisibilityMediatorMUPANDTest}
 */
public final class UserVisibilityMediatorMUPANDTest
        extends UserVisibilityMediatorVisibleBackgroundUserTestCase {

    public UserVisibilityMediatorMUPANDTest() throws Exception {
        super(/* backgroundUsersOnDisplaysEnabled= */ true,
                /* backgroundUserOnDefaultDisplayAllowed= */ true);
    }

    @Test
    public void testStartVisibleBgUser_onDefaultDisplay_initialCurrentUserId()
            throws Exception {
        int currentUserId = INITIAL_CURRENT_USER_ID;
        int visibleBgUserId = USER_ID;
        int otherUserId = OTHER_USER_ID;

        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(visibleBgUserId));

        int result = mMediator.assignUserToDisplayOnStart(visibleBgUserId, visibleBgUserId,
                BG_VISIBLE, DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectVisibleUsers(currentUserId, visibleBgUserId);

        // Assert bg user visibility
        expectUserIsVisible(visibleBgUserId);
        expectUserIsVisibleOnDisplay(visibleBgUserId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(visibleBgUserId, INVALID_DISPLAY);
        expectDisplayAssignedToUser(visibleBgUserId, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, visibleBgUserId);

        // Assert current user visibility
        expectUserIsVisible(currentUserId);
        expectUserIsVisibleOnDisplay(currentUserId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(currentUserId, INVALID_DISPLAY);
        expectDisplayAssignedToUser(currentUserId, INVALID_DISPLAY);

        assertUserCanBeAssignedExtraDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        // Make sure another user cannot be started on default display
        int result2 = mMediator.assignUserToDisplayOnStart(otherUserId, otherUserId, BG_VISIBLE,
                DEFAULT_DISPLAY, false);
        assertStartUserResult(result2, USER_ASSIGNMENT_RESULT_FAILURE,
                "when user (%d) is starting on default display after it was started by user %d",
                otherUserId, visibleBgUserId);
        expectVisibleUsers(currentUserId, visibleBgUserId);

        listener.verify();
    }

    @Test
    public void testStartVisibleBgUser_onDefaultDisplay_nonInitialCurrentUserId()
            throws Exception {
        int currentUserId = OTHER_USER_ID;
        int visibleBgUserId = USER_ID;
        int otherUserId = YET_ANOTHER_USER_ID;

        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(currentUserId),
                onVisible(visibleBgUserId));
        startForegroundUser(currentUserId);

        int result = mMediator.assignUserToDisplayOnStart(visibleBgUserId, visibleBgUserId,
                BG_VISIBLE, DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectVisibleUsers(currentUserId, visibleBgUserId);

        // Assert bg user visibility
        expectUserIsVisible(visibleBgUserId);
        expectUserIsVisibleOnDisplay(visibleBgUserId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(visibleBgUserId, INVALID_DISPLAY);
        expectDisplayAssignedToUser(visibleBgUserId, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, visibleBgUserId);

        // Assert current user visibility
        expectUserIsVisible(currentUserId);
        expectUserIsVisibleOnDisplay(currentUserId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(currentUserId, INVALID_DISPLAY);
        expectDisplayAssignedToUser(currentUserId, INVALID_DISPLAY);

        assertUserCanBeAssignedExtraDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        // Make sure another user cannot be started on default display
        int result2 = mMediator.assignUserToDisplayOnStart(otherUserId, otherUserId, BG_VISIBLE,
                DEFAULT_DISPLAY, false);
        assertStartUserResult(result2, USER_ASSIGNMENT_RESULT_FAILURE,
                "when user (%d) is starting on default display after it was started by user %d",
                otherUserId, visibleBgUserId);
        expectVisibleUsers(currentUserId, visibleBgUserId);

        listener.verify();
    }

    @Test
    public void
       testStartVisibleBgProfile_onDefaultDisplay_whenParentIsStartedVisibleOnBgOnSecondaryDisplay()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(PARENT_USER_ID));
        startUserInSecondaryDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID,
                BG_VISIBLE, DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectUserAssignedToDisplay(OTHER_SECONDARY_DISPLAY_ID, PARENT_USER_ID);

        assertInvisibleUserCannotBeAssignedExtraDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public void
        testStartVisibleBgProfile_onDefaultDisplay_whenParentIsStartedVisibleOnBgOnDefaultDisplay()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onVisible(PARENT_USER_ID),
                onVisible(PROFILE_USER_ID));
        startUserInSecondaryDisplay(PARENT_USER_ID, DEFAULT_DISPLAY);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID,
                BG_VISIBLE, DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        // Assert parent user visibility
        expectUserIsVisible(PARENT_USER_ID);
        expectUserIsVisibleOnDisplay(PARENT_USER_ID, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(PARENT_USER_ID, INVALID_DISPLAY);
        expectDisplayAssignedToUser(PARENT_USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, PARENT_USER_ID);
        assertUserCanBeAssignedExtraDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        // Assert profile user visibility
        expectUserIsVisible(PROFILE_USER_ID);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, INVALID_DISPLAY);
        // Only full user (parent) is assigned to the display
        expectDisplayAssignedToUser(PROFILE_USER_ID, INVALID_DISPLAY);
        assertUserCannotBeAssignedExtraDisplay(PROFILE_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public void testStartBgProfile_onDefaultDisplay_whenParentIsStartedVisibleOnBg()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(PARENT_USER_ID));
        startUserInSecondaryDisplay(PARENT_USER_ID, DEFAULT_DISPLAY);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        // Assert parent user visibility
        expectUserIsVisible(PARENT_USER_ID);
        expectUserIsVisibleOnDisplay(PARENT_USER_ID, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(PARENT_USER_ID, INVALID_DISPLAY);
        expectDisplayAssignedToUser(PARENT_USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, PARENT_USER_ID);
        assertUserCanBeAssignedExtraDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        // Assert profile user visibility
        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        assertUserCannotBeAssignedExtraDisplay(PROFILE_USER_ID, OTHER_SECONDARY_DISPLAY_ID);
        listener.verify();
    }

    @Test
    public void testStartVisibleBgUser_onDefaultDisplay_currentUserId() throws Exception {
        int currentUserId = INITIAL_CURRENT_USER_ID;

        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(currentUserId, currentUserId,
                BG_VISIBLE, DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE);

        // Assert current user visibility
        expectUserIsVisible(currentUserId);
        expectUserIsVisibleOnDisplay(currentUserId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(currentUserId, INVALID_DISPLAY);
        expectDisplayAssignedToUser(currentUserId, DEFAULT_DISPLAY);

        assertUserCanBeAssignedExtraDisplay(currentUserId, OTHER_SECONDARY_DISPLAY_ID);

        listener.verify();
    }
}
