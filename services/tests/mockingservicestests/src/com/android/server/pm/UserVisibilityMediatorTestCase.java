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

import static android.os.UserHandle.USER_ALL;
import static android.os.UserHandle.USER_CURRENT;
import static android.os.UserHandle.USER_CURRENT_OR_SELF;
import static android.os.UserHandle.USER_NULL;
import static android.os.UserHandle.USER_SYSTEM;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;

import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_FAILURE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE;
import static com.android.server.pm.UserManagerInternal.USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE;
import static com.android.server.pm.UserManagerInternal.userAssignmentResultToString;
import static com.android.server.pm.UserVisibilityMediator.INITIAL_CURRENT_USER_ID;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.annotation.UserIdInt;
import android.util.IntArray;
import android.util.Log;

import com.android.internal.util.Preconditions;
import com.android.server.ExtendedMockitoTestCase;

import org.junit.Before;
import org.junit.Test;

import java.util.Arrays;

/**
 * Base class for {@link UserVisibilityMediator} tests.
 *
 * <p>It contains common logics and tests for behaviors that should be invariant regardless of the
 * device mode (for example, whether the device supports concurrent multiple users on multiple
 * displays or not).
 *
 * <p><P>NOTE: <p> rather than adopting the "one test case for method approach", this class (and
 * its subclass) adds "one test case for scenario" approach, so it can test many properties (if user
 * is visible, display associated to the user, etc...) for each scenario (full user started on fg,
 * profile user started on bg, etc...).
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

    protected static final boolean FG = true;
    protected static final boolean BG = false;

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
    public final void testAssignUserToDisplayOnStart_invalidUserIds() {
        assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplayOnStart(USER_NULL, USER_ID, FG, DEFAULT_DISPLAY));
        assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplayOnStart(USER_ALL, USER_ID, FG, DEFAULT_DISPLAY));
        assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplayOnStart(USER_CURRENT, USER_ID, FG, DEFAULT_DISPLAY));
        assertThrows(IllegalArgumentException.class, () -> mMediator
                .assignUserToDisplayOnStart(USER_CURRENT_OR_SELF, USER_ID, FG, DEFAULT_DISPLAY));
    }

    @Test
    public final void testStartFgUser_onDefaultDisplay() {
        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, FG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsVisible(USER_ID);
        expectUserIsNotVisibleOnDisplay(USER_ID, INVALID_DISPLAY);
        expectUserIsVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY);
        // TODO(b/244644281): once isUserVisible() is fixed (see note there), this assertion will
        // fail on MUMD, so we'll need to refactor / split this test (and possibly others)
        expectUserIsVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID);
        expectVisibleUsers(USER_ID);

        expectDisplayAssignedToUser(USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(INVALID_DISPLAY, USER_ID);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);

        expectDisplayAssignedToUser(USER_NULL, INVALID_DISPLAY);
    }

    @Test
    public final void testSwitchFgUser_onDefaultDisplay() {
        int previousCurrentUserId = OTHER_USER_ID;
        int currentUserId = USER_ID;
        startForegroundUser(previousCurrentUserId);

        int result = mMediator.assignUserToDisplayOnStart(currentUserId, currentUserId, FG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

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
    }

    @Test
    public final void testStartFgUser_onSecondaryDisplay() {
        int userId = USER_ID;

        int result = mMediator.assignUserToDisplayOnStart(userId, userId, FG, SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(userId);
        expectNoDisplayAssignedToUser(USER_ID);
        expectNoUserAssignedToDisplay(DEFAULT_DISPLAY);
    }

    @Test
    public final void testStartBgUser_onDefaultDisplay() {
        int result = mMediator.assignUserToDisplayOnStart(USER_ID, USER_ID, BG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        expectUserIsNotVisibleAtAll(USER_ID);
        expectNoDisplayAssignedToUser(USER_ID);
        expectNoUserAssignedToDisplay(DEFAULT_DISPLAY);
    }

    @Test
    public final void testStartBgSystemUser_onSecondaryDisplay() {
        // Must explicitly set current user, as USER_SYSTEM is the default current user
        startForegroundUser(USER_ID);

        int result = mMediator.assignUserToDisplayOnStart(USER_SYSTEM, USER_SYSTEM, BG,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(USER_SYSTEM);

        expectNoDisplayAssignedToUser(USER_SYSTEM);
        expectUserAssignedToDisplay(SECONDARY_DISPLAY_ID, USER_ID);
    }

    @Test
    public final void testStartBgProfile_onDefaultDisplay_whenParentIsCurrentUser() {
        startForegroundUser(PARENT_USER_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE);

        expectUserIsVisible(PROFILE_USER_ID);
        expectUserIsNotVisibleOnDisplay(PROFILE_USER_ID, INVALID_DISPLAY);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectUserIsVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);
        expectVisibleUsers(PARENT_USER_ID, PROFILE_USER_ID);

        expectDisplayAssignedToUser(PROFILE_USER_ID, DEFAULT_DISPLAY);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, PARENT_USER_ID);

    }

    @Test
    public final void testStopVisibleProfile() {
        startDefaultProfile();

        mMediator.unassignUserFromDisplayOnStop(PROFILE_USER_ID);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, PARENT_USER_ID);
    }

    @Test
    public final void testVisibleProfileBecomesInvisibleWhenParentIsSwitchedOut() {
        startDefaultProfile();

        startForegroundUser(OTHER_USER_ID);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectUserAssignedToDisplay(DEFAULT_DISPLAY, OTHER_USER_ID);
    }

    @Test
    public final void testStartBgProfile_onDefaultDisplay_whenParentIsNotStarted() {
        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
    }

    @Test
    public final void testStartBgProfile_onDefaultDisplay_whenParentIsStartedOnBg() {
        startBackgroundUser(PARENT_USER_ID);

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);

        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectNoUserAssignedToDisplay(DEFAULT_DISPLAY);
    }

    // Not supported - profiles can only be started on default display
    @Test
    public final void testStartBgProfile_onSecondaryDisplay() {
        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectNoUserAssignedToDisplay(SECONDARY_DISPLAY_ID);
    }

    @Test
    public final void testStartFgProfile_onDefaultDisplay() {
        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, FG,
                DEFAULT_DISPLAY);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);

        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectNoUserAssignedToDisplay(DEFAULT_DISPLAY);
    }

    @Test
    public final void testStartFgProfile_onSecondaryDisplay() {
        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, FG,
                SECONDARY_DISPLAY_ID);
        assertStartUserResult(result, USER_ASSIGNMENT_RESULT_FAILURE);

        expectUserIsNotVisibleAtAll(PROFILE_USER_ID);
        expectNoDisplayAssignedToUser(PROFILE_USER_ID);
        expectNoUserAssignedToDisplay(SECONDARY_DISPLAY_ID);
    }

    @Test
    public final void testIsUserVisible_invalidUsers() {
        expectWithMessage("isUserVisible(%s)", USER_NULL)
                .that(mMediator.isUserVisible(USER_NULL))
                .isFalse();
        expectWithMessage("isUserVisible(%s)", USER_NULL)
                .that(mMediator.isUserVisible(USER_ALL))
                .isFalse();
        expectWithMessage("isUserVisible(%s)", USER_NULL)
                .that(mMediator.isUserVisible(USER_CURRENT))
                .isFalse();
        expectWithMessage("isUserVisible(%s)", USER_NULL)
                .that(mMediator.isUserVisible(USER_CURRENT_OR_SELF))
                .isFalse();
    }

    /**
     * Starts a user in foreground on the default display, asserting it was properly started.
     *
     * <p><b>NOTE: </b>should only be used as a helper method, not to test the behavior of the
     * {@link UserVisibilityMediator#assignUserToDisplayOnStart(int, int, boolean, int)} method per
     * se.
     */
    protected void startForegroundUser(@UserIdInt int userId) {
        Log.d(TAG, "startForegroundUSer(" + userId + ")");
        int result = mMediator.assignUserToDisplayOnStart(userId, userId, FG, DEFAULT_DISPLAY);
        if (result != USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE) {
            throw new IllegalStateException("Failed to start foreground user " + userId
                    + ": mediator returned " + userAssignmentResultToString(result));
        }
    }

    /**
     * Starts a user in background on the default display, asserting it was properly started.
     *
     * <p><b>NOTE: </b>should only be used as a helper method, not to test the behavior of the
     * {@link UserVisibilityMediator#assignUserToDisplayOnStart(int, int, boolean, int)} method per
     * se.
     */
    protected void startBackgroundUser(@UserIdInt int userId) {
        Log.d(TAG, "startBackgroundUser(" + userId + ")");
        int result = mMediator.assignUserToDisplayOnStart(userId, userId, BG, DEFAULT_DISPLAY);
        if (result != USER_ASSIGNMENT_RESULT_SUCCESS_INVISIBLE) {
            throw new IllegalStateException("Failed to start background user " + userId
                    + ": mediator returned " + userAssignmentResultToString(result));
        }
    }

    /**
     * Starts the {@link #PROFILE_USER_ID default profile} in background and its
     * {@link #PARENT_USER_ID parent} in foreground on the main display, asserting that
     * both were properly started.
     *
     * <p><b>NOTE: </b>should only be used as a helper method, not to test the behavior of the
     * {@link UserVisibilityMediator#assignUserToDisplayOnStart(int, int, boolean, int)} method per
     * se.
     */
    protected void startDefaultProfile() {
        startForegroundUser(PARENT_USER_ID);
        Log.d(TAG, "starting default profile (" + PROFILE_USER_ID + ") in background after starting"
                + " its parent (" + PARENT_USER_ID + ") on foreground");

        int result = mMediator.assignUserToDisplayOnStart(PROFILE_USER_ID, PARENT_USER_ID, BG,
                DEFAULT_DISPLAY);
        if (result != USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE) {
            throw new IllegalStateException("Failed to start profile user " + PROFILE_USER_ID
                    + ": mediator returned " + userAssignmentResultToString(result));
        }
    }

    /**
     * Starts a user in background on the secondary display, asserting it was properly started.
     *
     * <p><b>NOTE: </b>should only be used as a helper method, not to test the behavior of the
     * {@link UserVisibilityMediator#assignUserToDisplayOnStart(int, int, boolean, int)} method per
     * se.
     */
    protected final void startUserInSecondaryDisplay(@UserIdInt int userId, int displayId) {
        Preconditions.checkArgument(displayId != INVALID_DISPLAY && displayId != DEFAULT_DISPLAY,
                "must pass a secondary display, not %d", displayId);
        Log.d(TAG, "startUserInSecondaryDisplay(" + userId + ", " + displayId + ")");
        int result = mMediator.assignUserToDisplayOnStart(userId, userId, BG, displayId);
        if (result != USER_ASSIGNMENT_RESULT_SUCCESS_VISIBLE) {
            throw new IllegalStateException("Failed to startuser " + userId
                    + " on background: mediator returned " + userAssignmentResultToString(result));
        }
    }

    protected void assertStartUserResult(int actualResult, int expectedResult) {
        assertWithMessage("startUser() result (where %s=%s and %s=%s)",
                expectedResult, userAssignmentResultToString(expectedResult),
                actualResult, userAssignmentResultToString(actualResult))
                        .that(actualResult).isEqualTo(expectedResult);
    }

    protected void expectUserIsVisible(@UserIdInt int userId) {
        expectWithMessage("mediator.isUserVisible(%s)", userId)
                .that(mMediator.isUserVisible(userId))
                .isTrue();
    }

    protected void expectVisibleUsers(@UserIdInt Integer... userIds) {
        IntArray visibleUsers = mMediator.getVisibleUsers();
        expectWithMessage("getVisibleUsers()").that(visibleUsers).isNotNull();
        expectWithMessage("getVisibleUsers()").that(visibleUsers.toArray()).asList()
                .containsExactlyElementsIn(Arrays.asList(userIds));
    }

    protected void expectUserIsVisibleOnDisplay(@UserIdInt int userId, int displayId) {
        expectWithMessage("mediator.isUserVisible(%s, %s)", userId, displayId)
                .that(mMediator.isUserVisible(userId, displayId))
                .isTrue();
    }

    protected void expectUserIsNotVisibleOnDisplay(@UserIdInt int userId, int displayId) {
        expectWithMessage("mediator.isUserVisible(%s, %s)", userId, displayId)
                .that(mMediator.isUserVisible(userId, displayId))
                .isFalse();
    }

    protected void expectUserIsNotVisibleAtAll(@UserIdInt int userId) {
        expectWithMessage("mediator.isUserVisible(%s)", userId)
                .that(mMediator.isUserVisible(userId))
                .isFalse();
        expectUserIsNotVisibleOnDisplay(userId, DEFAULT_DISPLAY);
        expectUserIsNotVisibleOnDisplay(userId, INVALID_DISPLAY);
        expectUserIsNotVisibleOnDisplay(userId, SECONDARY_DISPLAY_ID);
        expectUserIsNotVisibleOnDisplay(userId, OTHER_SECONDARY_DISPLAY_ID);
    }

    protected void expectDisplayAssignedToUser(@UserIdInt int userId, int displayId) {
        expectWithMessage("getDisplayAssignedToUser(%s)", userId)
                .that(mMediator.getDisplayAssignedToUser(userId)).isEqualTo(displayId);
    }

    protected void expectNoDisplayAssignedToUser(@UserIdInt int userId) {
        expectWithMessage("getDisplayAssignedToUser(%s)", userId)
                .that(mMediator.getDisplayAssignedToUser(userId)).isEqualTo(INVALID_DISPLAY);
    }

    protected void expectUserAssignedToDisplay(int displayId, @UserIdInt int userId) {
        expectWithMessage("getUserAssignedToDisplay(%s)", displayId)
                .that(mMediator.getUserAssignedToDisplay(displayId)).isEqualTo(userId);
    }

    protected void expectNoUserAssignedToDisplay(int displayId) {
        expectWithMessage("getUserAssignedToDisplay(%s)", displayId)
                .that(mMediator.getUserAssignedToDisplay(displayId))
                .isEqualTo(INITIAL_CURRENT_USER_ID);
    }
}
