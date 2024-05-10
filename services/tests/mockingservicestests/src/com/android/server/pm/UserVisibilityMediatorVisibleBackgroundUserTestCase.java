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
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserVisibilityChangedEvent.onInvisible;
import static com.android.server.pm.UserVisibilityChangedEvent.onVisible;
import static com.android.server.pm.UserVisibilityMediator.INITIAL_CURRENT_USER_ID;

import android.annotation.UserIdInt;

import org.junit.Test;

/**
 * Base class for {@link UserVisibilityMediator} test classe on devices that support starting
 * background users on visible displays (as defined by
 * {@link android.os.UserManagerInternal#isVisibleBackgroundUsersSupported}).
 */
abstract class UserVisibilityMediatorVisibleBackgroundUserTestCase
        extends UserVisibilityMediatorTestCase {

    UserVisibilityMediatorVisibleBackgroundUserTestCase(boolean backgroundUsersOnDisplaysEnabled,
            boolean backgroundUserOnDefaultDisplayAllowed) throws Exception {
        super(backgroundUsersOnDisplaysEnabled, backgroundUserOnDefaultDisplayAllowed);
    }

    @Test
    public final void testStartFgUser_onDefaultDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(USER_ID));

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, FG,
                DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectUserCannotBeUnassignedFromDisplay(USER_ID, DEFAULT_DISPLAY);

        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectVisibleUsers(USER_ID);

        expectDisplayAssignedToUser(USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(INVALID_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);

        expectNoDisplayAssignedToUser(USER_NULL);

        assertUserCanBeAssignedExtraDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public final void testSwitchFgUser_onDefaultDisplay() throws Exception {
        int previousCurrentUserId = OTHER_USER_ID;
        int currentUserId = USER_ID;
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(previousCurrentUserId),
                onInvisible(previousCurrentUserId),
                onVisible(currentUserId));
        startForegroundUser(previousCurrentUserId);

        int result = mMediator.assignUserToDisplayOnStart(currentUserId, currentUserId, FG,
                DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectUserCannotBeUnassignedFromDisplay(currentUserId, DEFAULT_DISPLAY);

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

        assertUserCanBeAssignedExtraDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public final void testStartFgUser_onInvalidDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, FG, INVALID_DISPLAY,
                false);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        assertInvisibleUserCannotBeAssignedExtraDisplay(USER_ID, DEFAULT_DISPLAY);
        assertInvisibleUserCannotBeAssignedExtraDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public final void testStartVisibleBgUser_onInvalidDisplay() throws Exception {
        AsyncUserVisibilityListener listener = addListenerForNoEvents();

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                INVALID_DISPLAY, false);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_ID);

        assertInvisibleUserCannotBeAssignedExtraDisplay(USER_ID, DEFAULT_DISPLAY);
        assertInvisibleUserCannotBeAssignedExtraDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public final void testStartVisibleBgUser_onSecondaryDisplay_displayAvailable()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(USER_ID));

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectUserCannotBeUnassignedFromDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(INITIAL_CURRENT_USER_ID, USER_ID);

        expectDisplayAssignedToUser(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);

        assertUserCanBeAssignedExtraDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        listener.verify();

        // Assign again, without unassigning (to make sure it becomes invisible on stop)
        AsyncUserVisibilityListener listener2 = addListenerForEvents(onInvisible(USER_ID));
        assertUserCanBeAssignedExtraDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID,
                /* unassign= */ false);

        assertBgUserBecomesInvisibleOnStop(USER_ID);

        listener2.verify();
    }

    @Test
    public final void testVisibilityOfCurrentUserAndProfilesOnDisplayAssignedToAnotherUser()
            throws Exception {
        startDefaultProfile();

        // Make sure they were visible before
        expectUserIsNotVisibleOnDisplay("before", PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay("before", PROFILE_USER_ID, SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsNotVisibleOnDisplay("after", PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay("after", PROFILE_USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public final void testStartVisibleBgUser_onSecondaryDisplay_displayAlreadyAssigned()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(OTHER_USER_ID));
        startUserInSecondaryDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_ID);
        expectNoDisplayAssignedToUser(USER_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, OTHER_USER_ID);

        assertUserCannotBeAssignedExtraDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public final void testStartVisibleBgUser_onSecondaryDisplay_displayAlreadyAssignedToSameUser()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(USER_ID));
        startUserInSecondaryDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(INITIAL_CURRENT_USER_ID, USER_ID);
        expectDisplayAssignedToUser(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);
        assertUserCanBeAssignedExtraDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_ALREADY_VISIBLE);

        // Run same assertions above
        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(INITIAL_CURRENT_USER_ID, USER_ID);
        expectDisplayAssignedToUser(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);
        assertUserCanBeAssignedExtraDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public final void testStartVisibleBgUser_onSecondaryDisplay_userAlreadyAssigned()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(USER_ID));
        startUserInSecondaryDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG_VISIBLE,
                SECONDARY_DISPLAY_ID, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsVisible(USER_ID);
        expectUserIsVisibleOnDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(INITIAL_CURRENT_USER_ID, USER_ID);

        expectDisplayAssignedToUser(USER_ID, OTHER_SECONDARY_DISPLAY_ID);
        expectUserAssignedToDisplay(OTHER_SECONDARY_DISPLAY_ID, USER_ID);

        assertUserCanBeAssignedExtraDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();

        // Assign again, without unassigning (to make sure it becomes invisible on stop)
        AsyncUserVisibilityListener listener2 = addListenerForEvents(onInvisible(USER_ID));
        assertUserCanBeAssignedExtraDisplay(USER_ID, SECONDARY_DISPLAY_ID,
                /* unassign= */ false);

        assertBgUserBecomesInvisibleOnStop(USER_ID);

        listener2.verify();
    }

    @Test
    public final void testStartVisibleBgProfile_onDefaultDisplay_whenParentIsCurrentUser()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(
                onInvisible(INITIAL_CURRENT_USER_ID),
                onVisible(PARENT_USER_ID),
                onVisible(PROFILE_USER_ID));
        startForegroundUser(PARENT_USER_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID,
                BG_VISIBLE, DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);
        expectUserCannotBeUnassignedFromDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY);

        expectUserIsVisible(PROFILE_USER_ID);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectVisibleUsers(PARENT_USER_ID, PROFILE_USER_ID);

        expectDisplayAssignedToUser(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, PARENT_USER_ID);

        assertUserCannotBeAssignedExtraDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    @Test
    public final void
            testStartVisibleBgProfile_onDefaultDisplay_whenParentIsStartedVisibleOnAnotherDisplay()
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

    // Not supported - profiles can only be started on default display
    @Test
    public final void
            testStartVisibleBgProfile_onSecondaryDisplay_whenParentIsStartedVisibleOnThatDisplay()
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
    public final void
            testStartProfile_onDefaultDisplay_whenParentIsStartedVisibleOnSecondaryDisplay()
            throws Exception {
        AsyncUserVisibilityListener listener = addListenerForEvents(onVisible(PARENT_USER_ID));
        startUserInSecondaryDisplay(PARENT_USER_ID, OTHER_SECONDARY_DISPLAY_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                DEFAULT_DISPLAY, false);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectUserAssignedToDisplay(OTHER_SECONDARY_DISPLAY_ID, PARENT_USER_ID);

        assertInvisibleUserCannotBeAssignedExtraDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);

        listener.verify();
    }

    // Conditions below are asserted on other tests, but they're explicitly checked in the 2
    // tests below (which call this method) as well
    private void currentUserVisibilityWhenNoDisplayIsAssignedTest(@UserIdInt int currentUserId) {
        expectUserIsVisible(currentUserId);
        expectUserIsVisibleOnDisplay(currentUserId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(currentUserId, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(currentUserId, OTHER_SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(currentUserId, INVALID_DISPLAY);

        expectDisplayAssignedToUser(currentUserId, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, currentUserId);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, currentUserId);
        expectUserAssignedToDisplay(OTHER_SECONDARY_DISPLAY_ID, currentUserId);
        expectUserAssignedToDisplay(INVALID_DISPLAY, currentUserId);
    }

    @Test
    public final void testCurrentUserVisibilityWhenNoDisplayIsAssigned_onBoot() throws Exception {
        currentUserVisibilityWhenNoDisplayIsAssignedTest(INITIAL_CURRENT_USER_ID);
    }

    @Test
    public final void testCurrentUserVisibilityWhenNoDisplayIsAssigned_afterSwitch()
            throws Exception {
        startForegroundUser(USER_ID);

        currentUserVisibilityWhenNoDisplayIsAssignedTest(USER_ID);
        expectUserIsNotVisibleAtAll(INITIAL_CURRENT_USER_ID);
        expectDisplayAssignedToUser(INITIAL_CURRENT_USER_ID, INVALID_DISPLAY);
    }

    @Test
    public final void testAssignUserToExtraDisplay_invalidDisplays() throws Exception {
        expectWithMessage("assignUserToExtraDisplay(%s, %s)", USER_ID, INVALID_DISPLAY)
                .that(mMediator.assignUserToExtraDisplay(USER_ID, INVALID_DISPLAY)).isFalse();
        // DEFAULT_DISPLAY is always assigned to the current user
        expectWithMessage("assignUserToExtraDisplay(%s, %s)", USER_ID, DEFAULT_DISPLAY)
                .that(mMediator.assignUserToExtraDisplay(USER_ID, DEFAULT_DISPLAY)).isFalse();
    }
}
