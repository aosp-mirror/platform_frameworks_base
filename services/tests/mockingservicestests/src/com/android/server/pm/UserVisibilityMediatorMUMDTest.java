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
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
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
    public void testStartFgUser_onInvalidDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, FG, INVALID_DISPLAY);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        listener.verify();
    }

    @Test
    public void testStartBgUser_onInvalidDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG, INVALID_DISPLAY);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_ID);

        listener.verify();
    }

    @Test
    public void testStartBgUser_onSecondaryDisplay_displayAvailable() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(USER_ID));

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG,
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
        expectUserIsVisibleOnDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsNotVisibleOnDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testStartBgUser_onSecondaryDisplay_displayAlreadyAssigned() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(OTHER_USER_ID));
        startUserInSecondaryDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG,
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

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG,
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

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectUserAssignedToDisplay(OTHER_SECONDARY_DISPLAY_ID, PARENT_USER_ID);

        listener.verify();
    }
}
