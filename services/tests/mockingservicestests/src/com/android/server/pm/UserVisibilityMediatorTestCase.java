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

import static android.content.pm.UserInfo.NO_PROFILE_GROUP_ID;
import static android.os.UserHandle.USER_NULL;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserManagerInternal.userAssignmentResultToString;
import static com.android.server.pm.UserVisibilityMediator.INITIAL_CURRENT_USER_ID;

import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.UserIdInt;
import android.util.Log;

import com.android.server.ExtendedMockitoTestCase;

import org.junit.Before;
import org.junit.Test;

/**
 * Base class for {@link UserVisibilityMediator} tests.
 *
 * <p>It contains common logics and tests for behaviors that should be invariant regardless of the
 * device mode (for example, whether the device supports concurrent multiple users on multiple
 * displays or not).
 */
abstract class UserVisibilityMediatorTestCase extends ExtendedMockitoTestCase {

    private static final String TAG = UserVisibilityMediatorTestCase.class.getSimpleName();

    /**
     * Id for a simple user (that doesn't have profiles).
     */
    protected static final int USER_ID = 600;

    /**
     * Id for another simple user.
     */
    protected static final int OTHER_USER_ID = 666;

    /**
     * Id for a user that has one profile (whose id is {@link #PROFILE_USER_ID}.
     *
     * <p>You can use {@link #addDefaultProfileAndParent()} to add both of this user to the service.
     */
    protected static final int PARENT_USER_ID = 642;

    /**
     * Id for a profile whose parent is {@link #PARENTUSER_ID}.
     *
     * <p>You can use {@link #addDefaultProfileAndParent()} to add both of this user to the service.
     */
    protected static final int PROFILE_USER_ID = 643;

    /**
     * Id of a secondary display (i.e, not {@link android.view.Display.DEFAULT_DISPLAY}).
     */
    protected static final int SECONDARY_DISPLAY_ID = 42;

    /**
     * Id of another secondary display (i.e, not {@link android.view.Display.DEFAULT_DISPLAY}).
     */
    protected static final int OTHER_SECONDARY_DISPLAY_ID = 108;

    private static final boolean FG = true;
    private static final boolean BG = false;

    private final boolean mUsersOnSecondaryDisplaysEnabled;

    protected UserVisibilityMediator mMediator;

    protected UserVisibilityMediatorTestCase(boolean usersOnSecondaryDisplaysEnabled) {
        mUsersOnSecondaryDisplaysEnabled = usersOnSecondaryDisplaysEnabled;
    }

    @Before
    public final void setMediator() {
        mMediator = new UserVisibilityMediator(mUsersOnSecondaryDisplaysEnabled);
        mDumpableDumperRule.addDumpable(mMediator);
    }

    @Test
    public final void testStartUser_currentUser() {
        int result = mMediator.startOnly(USER_ID, USER_ID, FG, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        assertCurrentUser(USER_ID);
        assertIsCurrentUserOrRunningProfileOfCurrentUser(USER_ID);
        assertStartedProfileGroupIdOf(USER_ID, USER_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public final void testStartUser_currentUserSecondaryDisplay() {
        int result = mMediator.startOnly(USER_ID, USER_ID, FG, SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        assertCurrentUser(INITIAL_CURRENT_USER_ID);
        assertIsNotCurrentUserOrRunningProfileOfCurrentUser(USER_ID);
        assertStartedProfileGroupIdOf(USER_ID, NO_PROFILE_GROUP_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public final void testStartUser_profileBg_parentStarted() {
        mockCurrentUser(PARENT_USER_ID);

        int result = mMediator.startOnly(PROFILE_USER_ID, PARENT_USER_ID, BG, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        assertCurrentUser(PARENT_USER_ID);
        assertIsCurrentUserOrRunningProfileOfCurrentUser(PROFILE_USER_ID);
        assertStartedProfileGroupIdOf(PROFILE_USER_ID, PARENT_USER_ID);
        assertProfileIsStarted(PROFILE_USER_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public final void testStartUser_profileBg_parentNotStarted() {
        int result = mMediator.startOnly(PROFILE_USER_ID, PARENT_USER_ID, BG, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        assertCurrentUser(INITIAL_CURRENT_USER_ID);
        assertIsNotCurrentUserOrRunningProfileOfCurrentUser(PROFILE_USER_ID);
        assertStartedProfileGroupIdOf(PROFILE_USER_ID, PARENT_USER_ID);
        assertProfileIsStarted(PROFILE_USER_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public final void testStartUser_profileBg_secondaryDisplay() {
        int result = mMediator.startOnly(PROFILE_USER_ID, PARENT_USER_ID, BG, SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        assertCurrentUser(INITIAL_CURRENT_USER_ID);
        assertIsNotCurrentUserOrRunningProfileOfCurrentUser(PROFILE_USER_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public final void testStartUser_profileFg() {
        int result = mMediator.startOnly(PROFILE_USER_ID, PARENT_USER_ID, FG, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        assertCurrentUser(INITIAL_CURRENT_USER_ID);
        assertIsNotCurrentUserOrRunningProfileOfCurrentUser(PROFILE_USER_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public final void testStartUser_profileFgSecondaryDisplay() {
        int result = mMediator.startOnly(PROFILE_USER_ID, PARENT_USER_ID, FG, SECONDARY_DISPLAY_ID);

        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);
        assertCurrentUser(INITIAL_CURRENT_USER_ID);

        stopUserAndAssertState(USER_ID);
    }

    @Test
    public final void testGetStartedProfileGroupId_whenStartedWithNoProfileGroupId() {
        int result = mMediator.startOnly(USER_ID, NO_PROFILE_GROUP_ID, FG, DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        assertWithMessage("shit").that(mMediator.getStartedProfileGroupId(USER_ID))
                .isEqualTo(USER_ID);
    }

    @Test
    public final void testAssignUserToDisplay_defaultDisplayIgnored() {
        mMediator.assignUserToDisplay(USER_ID, USER_ID, DEFAULT_DISPLAY);

        assertNoUserAssignedToDisplay();
    }

    @Test
    public final void testIsUserVisible_invalidUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s)", USER_NULL)
                .that(mMediator.isUserVisible(USER_NULL)).isFalse();
    }

    @Test
    public final void testIsUserVisible_currentUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s)", USER_ID)
                .that(mMediator.isUserVisible(USER_ID)).isTrue();
    }

    @Test
    public final void testIsUserVisible_nonCurrentUser() {
        mockCurrentUser(OTHER_USER_ID);

        assertWithMessage("isUserVisible(%s)", USER_ID)
                .that(mMediator.isUserVisible(USER_ID)).isFalse();
    }

    @Test
    public final void testIsUserVisible_startedProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        assertWithMessage("isUserVisible(%s)", PROFILE_USER_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID)).isTrue();
    }

    @Test
    public final void testIsUserVisible_stoppedProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisible(%s)", PROFILE_USER_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_invalidUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_NULL, DEFAULT_DISPLAY)
                .that(mMediator.isUserVisible(USER_NULL, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_currentUserInvalidDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, INVALID_DISPLAY)
                .that(mMediator.isUserVisible(USER_ID, INVALID_DISPLAY)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_currentUserDefaultDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, DEFAULT_DISPLAY)
                .that(mMediator.isUserVisible(USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_currentUserSecondaryDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_nonCurrentUserDefaultDisplay() {
        mockCurrentUser(OTHER_USER_ID);

        assertWithMessage("isUserVisible(%s, %s)", USER_ID, DEFAULT_DISPLAY)
                .that(mMediator.isUserVisible(USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_startedProfileOfcurrentUserInvalidDisplay() {
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, INVALID_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserInvalidDisplay() {
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, INVALID_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_startedProfileOfcurrentUserDefaultDisplay() {
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, DEFAULT_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserDefaultDisplay() {
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, DEFAULT_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_startedProfileOfCurrentUserSecondaryDisplay() {
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserSecondaryDisplay() {
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testGetDisplayAssignedToUser_invalidUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_NULL)
                .that(mMediator.getDisplayAssignedToUser(USER_NULL)).isEqualTo(INVALID_DISPLAY);
    }

    @Test
    public void testGetDisplayAssignedToUser_currentUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_ID)
                .that(mMediator.getDisplayAssignedToUser(USER_ID)).isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public final void testGetDisplayAssignedToUser_nonCurrentUser() {
        mockCurrentUser(OTHER_USER_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_ID)
                .that(mMediator.getDisplayAssignedToUser(USER_ID)).isEqualTo(INVALID_DISPLAY);
    }

    @Test
    public final void testGetDisplayAssignedToUser_startedProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        assertWithMessage("getDisplayAssignedToUser(%s)", PROFILE_USER_ID)
                .that(mMediator.getDisplayAssignedToUser(PROFILE_USER_ID))
                .isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public final void testGetDisplayAssignedToUser_stoppedProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("getDisplayAssignedToUser(%s)", PROFILE_USER_ID)
                .that(mMediator.getDisplayAssignedToUser(PROFILE_USER_ID))
                .isEqualTo(INVALID_DISPLAY);
    }

    @Test
    public void testGetUserAssignedToDisplay_invalidDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", INVALID_DISPLAY)
                .that(mMediator.getUserAssignedToDisplay(INVALID_DISPLAY)).isEqualTo(USER_ID);
    }

    @Test
    public final void testGetUserAssignedToDisplay_defaultDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", DEFAULT_DISPLAY)
                .that(mMediator.getUserAssignedToDisplay(DEFAULT_DISPLAY)).isEqualTo(USER_ID);
    }

    @Test
    public final void testGetUserAssignedToDisplay_secondaryDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(mMediator.getUserAssignedToDisplay(SECONDARY_DISPLAY_ID))
                .isEqualTo(USER_ID);
    }

    /**
     * Stops the given user and assert the proper state is set.
     *
     * <p>This method should be called at the end of tests that starts a user, so it can test
     * {@code stopUser()} as well (technically speaking, {@code stopUser()} should be tested on its
     * own methods, but it depends on the user being started at first place, so pragmatically
     * speaking, it's better to "reuse" such tests for both (start and stop)
     */
    private void stopUserAndAssertState(@UserIdInt int userId) {
        mMediator.stopUser(userId);

        assertUserIsStopped(userId);
        assertNoUserAssignedToDisplay();
    }

    // TODO(b/244644281): remove if start & assign are merged; if they aren't, add a note explaining
    // it's not meant to be used to test startUser() itself.
    protected void mockCurrentUser(@UserIdInt int userId) {
        Log.d(TAG, "mockCurrentUser(" + userId + ")");
        int result = mMediator.startOnly(userId, userId, FG, DEFAULT_DISPLAY);
        if (result != USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE) {
            throw new IllegalStateException("Failed to mock current user " + userId
                    + ": mediator returned " + userAssignmentResultToString(result));
        }
    }

    // TODO(b/244644281): remove when start & assign are merged; or add a note explaining
    // it's not meant to be used to test startUser() itself.
    protected void startDefaultProfile() {
        mockCurrentUser(PARENT_USER_ID);
        Log.d(TAG, "starting default profile (" + PROFILE_USER_ID + ") in background after starting"
                + " its parent (" + PARENT_USER_ID + ") on foreground");

        int result = mMediator.startOnly(PROFILE_USER_ID, PARENT_USER_ID, BG, DEFAULT_DISPLAY);
        if (result != USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE) {
            throw new IllegalStateException("Failed to start profile user " + PROFILE_USER_ID
                    + ": mediator returned " + userAssignmentResultToString(result));
        }
    }

    // TODO(b/244644281): remove when start & assign are merged; or add a note explaining
    // it's not meant to be used to test stopUser() itself.
    protected void stopDefaultProfile() {
        Log.d(TAG, "stopping default profile");
        mMediator.stopUser(PROFILE_USER_ID);
    }

    // TODO(b/244644281): remove when start & assign are merged; or add a note explaining
    // it's not meant to be used to test assignUserToDisplay() itself.
    protected final void assignUserToDisplay(@UserIdInt int userId, int displayId) {
        Log.d(TAG, "assignUserToDisplay(" + userId + ", " + displayId + ")");
        int result = mMediator.startOnly(userId, userId, BG, displayId);
        if (result != USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE) {
            throw new IllegalStateException("Failed to startuser " + userId
                    + " on background: mediator returned " + userAssignmentResultToString(result));
        }
        mMediator.assignUserToDisplay(userId, userId, displayId);

    }

    // TODO(b/244644281): remove when start & assign are merged; or rename to
    // assertNoUserAssignedToSecondaryDisplays
    protected final void assertNoUserAssignedToDisplay() {
        assertWithMessage("users on secondary displays")
                .that(mMediator.getUsersOnSecondaryDisplays())
                .isEmpty();
    }

    // TODO(b/244644281): remove when start & assign are merged; or rename to
    // assertUserAssignedToSecondaryDisplay
    protected final void assertUserAssignedToDisplay(@UserIdInt int userId, int displayId) {
        assertWithMessage("users on secondary displays")
                .that(mMediator.getUsersOnSecondaryDisplays())
                .containsExactly(userId, displayId);
    }

    private void assertCurrentUser(@UserIdInt int userId) {
        assertWithMessage("mediator.getCurrentUserId()").that(mMediator.getCurrentUserId())
                .isEqualTo(userId);
        if (userId != INITIAL_CURRENT_USER_ID) {
            assertUserIsStarted(userId);
        }
    }

    private void assertUserIsStarted(@UserIdInt int userId) {
        assertWithMessage("mediator.isStarted(%s)", userId).that(mMediator.isStartedUser(userId))
                .isTrue();
    }

    private void assertUserIsStopped(@UserIdInt int userId) {
        assertWithMessage("mediator.isStarted(%s)", userId).that(mMediator.isStartedUser(userId))
                .isFalse();
    }

    private void assertProfileIsStarted(@UserIdInt int userId) {
        assertWithMessage("mediator.isStartedProfile(%s)", userId)
                .that(mMediator.isStartedProfile(userId))
                .isTrue();
        assertUserIsStarted(userId);
    }

    private void assertStartedProfileGroupIdOf(@UserIdInt int userId,
            @UserIdInt int profileGroupId) {
        assertWithMessage("mediator.getStartedProfileGroupId(%s)", userId)
                .that(mMediator.getStartedProfileGroupId(userId))
                .isEqualTo(profileGroupId);
    }

    private void assertIsCurrentUserOrRunningProfileOfCurrentUser(@UserIdInt int userId) {
        assertWithMessage("mediator.isCurrentUserOrRunningProfileOfCurrentUser(%s)", userId)
                .that(mMediator.isCurrentUserOrRunningProfileOfCurrentUser(userId))
                .isTrue();
        if (mMediator.getCurrentUserId() == userId) {
            assertUserIsStarted(userId);
        } else {
            assertProfileIsStarted(userId);
        }
    }

    private void assertIsNotCurrentUserOrRunningProfileOfCurrentUser(int userId) {
        assertWithMessage("mediator.isCurrentUserOrRunningProfileOfCurrentUser(%s)", userId)
                .that(mMediator.isCurrentUserOrRunningProfileOfCurrentUser(userId))
                .isFalse();
    }

    private void assertStartUserResult(int actualResult, int expectedResult) {
        assertWithMessage("startUser() result (where %s=%s and %s=%s)",
                actualResult, userAssignmentResultToString(actualResult),
                expectedResult, userAssignmentResultToString(expectedResult))
                        .that(actualResult).isEqualTo(expectedResult);
    }
}
