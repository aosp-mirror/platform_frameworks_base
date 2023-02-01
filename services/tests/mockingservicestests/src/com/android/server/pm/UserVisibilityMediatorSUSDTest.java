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
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserVisibilityChangedEvent.onInvisible;
import static com.android.server.pm.UserVisibilityChangedEvent.onVisible;
import static com.android.server.pm.UserVisibilityMediator.INITIAL_CURRENT_USER_ID;

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
        super(/* backgroundUsersOnDisplaysEnabled= */ false,
                /* backgroundUserOnDefaultDisplayAllowed= */ false);
    }

    @Test
    public void testStartVisibleBgUser_onDefaultDisplay() throws Exception {
        int userId = visibleBgUserCannotBeStartedOnDefaultDisplayTest();

        assertInvisibleUserCannotBeAssignedExtraDisplay(userId, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testStartFgUser_onDefaultDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(USER_ID));

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, FG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectUserCannotBeUnassignedFromDisplay(USER_ID, DEFAULT_DISPLAY);

        expectUserIsVisible(USER_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectUserIsVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectVisibleUsers(USER_ID);

        expectDisplayAssignedToUser(USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(INVALID_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);

        expectNoDisplayAssignedToUser(USER_NULL);

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
        expectUserCannotBeUnassignedFromDisplay(currentUserId, DEFAULT_DISPLAY);

        expectUserIsVisible(currentUserId);
        expectUserIsNotVisibleOnDisplay(currentUserId, INVALID_DISPLAY);
        expectUserIsVisibleOnDisplay(currentUserId, DEFAULT_DISPLAY);
        expectUserIsVisibleOnDisplay(currentUserId, SECONDARY_DISPLAY_ID);
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
    public void testStartBgUser_onDefaultDisplay_visible() throws Exception {
        visibleBgUserCannotBeStartedOnDefaultDisplayTest();
    }

    @Test
    public void testStartVisibleBgProfile_onDefaultDisplay_whenParentIsCurrentUser()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(PARENT_USER_ID),
                onVisible(PROFILE_USER_ID));
        startForegroundUser(PARENT_USER_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID,
                BG_VISIBLE, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectUserCannotBeUnassignedFromDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY);

        expectUserIsVisible(PROFILE_USER_ID);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, INVALID_DISPLAY);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);
        expectVisibleUsers(PARENT_USER_ID, PROFILE_USER_ID);

        expectDisplayAssignedToUser(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, PARENT_USER_ID);

        listener.verify();
    }

    @Test
    public void testStartVisibleBgUser_onSecondaryDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_ID);
        expectNoDisplayAssignedToUser(USER_ID);

        expectInitialCurrentUserAssignedToDisplay(SECONDARY_DISPLAY_ID);

        listener.verify();
    }
}
