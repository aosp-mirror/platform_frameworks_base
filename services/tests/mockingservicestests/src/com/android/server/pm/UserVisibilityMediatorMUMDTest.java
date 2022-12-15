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

import static android.os.UserHandle.USER_NULL;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserVisibilityChangedEvent.onInvisible;
import static com.android.server.pm.UserVisibilityChangedEvent.onVisible;
import static com.android.server.pm.UserVisibilityMediator.INITIAL_CURRENT_USER_ID;

import org.junit.Test;

/**
 * Tests for {@link UserVisibilityMediator} tests for devices that support concurrent Multiple
 * Users on Multiple Displays (A.K.A {@code MUMD}).
 *
 * <p>Run as
 * {@code atest FrameworksMockingServicesTests:com.android.server.pm.UserVisibilityMediatorMUMDTest}
 */
public final class UserVisibilityMediatorMUMDTest extends UserVisibilityMediatorTestCase {

    public UserVisibilityMediatorMUMDTest() throws Exception {
        super(/* usersOnSecondaryDisplaysEnabled= */ true);
    }

    @Test
    public void testStartFgUser_onDefaultDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(USER_ID));

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, FG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectVisibleUsers(USER_ID);

        expectDisplayAssignedToUser(USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(INVALID_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);

        expectDisplayAssignedToUser(USER_NULL, INVALID_DISPLAY);

        listener.verify();
    }

    @Test
    public void testSwitchFgUser_onDefaultDisplay() throws Exception {
        int previousCurrentUserId = OTHER_USER_ID;
        int currentUserId = USER_ID;
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(previousCurrentUserId),
                onInvisible(previousCurrentUserId),
                onVisible(currentUserId));
        startForegroundUser(previousCurrentUserId);

        int result = mMediator.assignUserToDisplayOnStart(currentUserId, currentUserId, FG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsVisible(currentUserId);
        expectUserIsVisibleOnDisplay(currentUserId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(currentUserId, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(currentUserId, SECONDARY_DISPLAY_ID);
        expectVisibleUsers(currentUserId);

        expectDisplayAssignedToUser(currentUserId, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, currentUserId);
        expectUserAssignedToDisplay(INVALID_DISPLAY, currentUserId);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, currentUserId);

        expectUserIsNotVisibleAtAll(previousCurrentUserId);
        expectNoDisplayAssignedToUser(previousCurrentUserId);

        listener.verify();
    }

    @Test
    public void testStartBgProfile_onDefaultDisplay_whenParentIsCurrentUser() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(PARENT_USER_ID),
                onVisible(PROFILE_USER_ID));
        startForegroundUser(PARENT_USER_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID,
                BG_VISIBLE, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsVisible(PROFILE_USER_ID);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(PARENT_USER_ID, PROFILE_USER_ID);

        expectDisplayAssignedToUser(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, PARENT_USER_ID);

        listener.verify();
    }

    @Test
    public void testStartFgUser_onInvalidDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, FG, INVALID_DISPLAY);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        listener.verify();
    }

    @Test
    public void testStartBgUser_onInvalidDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                INVALID_DISPLAY);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_ID);

        listener.verify();
    }

    @Test
    public void testStartBgUser_onSecondaryDisplay_displayAvailable() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(USER_ID));

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(INITIAL_CURRENT_USER_ID, USER_ID);

        expectDisplayAssignedToUser(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);

        listener.verify();
    }

    @Test
    public void testVisibilityOfCurrentUserAndProfilesOnDisplayAssignedToAnotherUser()
            throws Exception {
        startDefaultProfile();

        // Make sure they were visible before
        expectUserIsNotVisibleOnDisplay("before", PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay("before", PROFILE_USER_ID, SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsNotVisibleOnDisplay("after", PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay("after", PROFILE_USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testStartBgUser_onSecondaryDisplay_displayAlreadyAssigned() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(OTHER_USER_ID));
        startUserInSecondaryDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_ID);
        expectNoDisplayAssignedToUser(USER_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, OTHER_USER_ID);

        listener.verify();
    }

    @Test
    public void testStartBgUser_onSecondaryDisplay_userAlreadyAssigned() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(USER_ID));
        startUserInSecondaryDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(INITIAL_CURRENT_USER_ID, USER_ID);

        expectDisplayAssignedToUser(USER_ID, OTHER_SECONDARY_DISPLAY_ID);
        expectUserAssignedToDisplay(OTHER_SECONDARY_DISPLAY_ID, USER_ID);

        listener.verify();
    }

    @Test
    public void testStartBgProfile_onDefaultDisplay_whenParentVisibleOnSecondaryDisplay()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(PARENT_USER_ID));
        startUserInSecondaryDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID,
                BG_VISIBLE, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectUserAssignedToDisplay(OTHER_SECONDARY_DISPLAY_ID, PARENT_USER_ID);

        listener.verify();
    }
}
