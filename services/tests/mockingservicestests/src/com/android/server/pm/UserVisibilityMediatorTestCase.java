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

import static com.android.server.am.UserState.STATE_RUNNING_UNLOCKED;

import static com.google.common.truth.Truth.assertWithMessage;

import android.annotation.UserIdInt;
import android.util.SparseIntArray;

import org.junit.Before;
import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for {@link UserVisibilityMediator} tests.
 *
 * <p>It contains common logics and tests for behaviors that should be invariant regardless of the
 * device mode (for example, whether the device supports concurrent multiple users on multiple
 * displays or not).
 */
abstract class UserVisibilityMediatorTestCase extends UserManagerServiceOrInternalTestCase {

    /**
     * Id of a secondary display (i.e, not {@link android.view.Display.DEFAULT_DISPLAY}).
     */
    protected static final int SECONDARY_DISPLAY_ID = 42;

    /**
     * Id of another secondary display (i.e, not {@link android.view.Display.DEFAULT_DISPLAY}).
     */
    protected static final int OTHER_SECONDARY_DISPLAY_ID = 108;

    private final boolean mUsersOnSecondaryDisplaysEnabled;

    // TODO(b/244644281): manipulating mUsersOnSecondaryDisplays directly leaks implementation
    // details into the unit test, but it's fine for now as the tests were copied "as is" - it
    // would be better to use a geter() instead
    protected final SparseIntArray mUsersOnSecondaryDisplays = new SparseIntArray();

    protected UserVisibilityMediator mMediator;

    protected UserVisibilityMediatorTestCase(boolean usersOnSecondaryDisplaysEnabled) {
        mUsersOnSecondaryDisplaysEnabled = usersOnSecondaryDisplaysEnabled;
    }

    @Before
    public final void setMediator() {
        mMediator = new UserVisibilityMediator(mUms, mUsersOnSecondaryDisplaysEnabled,
                mUsersOnSecondaryDisplays);
    }

    @Test
    public final void testAssignUserToDisplay_defaultDisplayIgnored() {
        mMediator.assignUserToDisplay(USER_ID, DEFAULT_DISPLAY);

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
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, STATE_RUNNING_UNLOCKED);

        assertWithMessage("isUserVisible(%s)", PROFILE_USER_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID)).isTrue();
    }

    @Test
    public final void testIsUserVisible_stoppedProfileOfcurrentUser() {
        addDefaultProfileAndParent();
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
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, INVALID_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserInvalidDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, INVALID_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_startedProfileOfcurrentUserDefaultDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, STATE_RUNNING_UNLOCKED);

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, DEFAULT_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserDefaultDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, DEFAULT_DISPLAY)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public final void testIsUserVisibleOnDisplay_startedProfileOfCurrentUserSecondaryDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, STATE_RUNNING_UNLOCKED);

        assertWithMessage("isUserVisible(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(mMediator.isUserVisible(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserSecondaryDisplay() {
        addDefaultProfileAndParent();
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
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, STATE_RUNNING_UNLOCKED);

        assertWithMessage("getDisplayAssignedToUser(%s)", PROFILE_USER_ID)
                .that(mMediator.getDisplayAssignedToUser(PROFILE_USER_ID))
                .isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public final void testGetDisplayAssignedToUser_stoppedProfileOfcurrentUser() {
        addDefaultProfileAndParent();
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

    // NOTE: should only called by tests that indirectly needs to check user assignments (like
    // isUserVisible), not by tests for the user assignment methods per se.
    protected final void assignUserToDisplay(@UserIdInt int userId, int displayId) {
        mUsersOnSecondaryDisplays.put(userId, displayId);
    }

    protected final void assertNoUserAssignedToDisplay() {
        assertWithMessage("mUsersOnSecondaryDisplays()").that(usersOnSecondaryDisplaysAsMap())
                .isEmpty();
    }

    protected final void assertUserAssignedToDisplay(@UserIdInt int userId, int displayId) {
        assertWithMessage("mUsersOnSecondaryDisplays()").that(usersOnSecondaryDisplaysAsMap())
                .containsExactly(userId, displayId);
    }

    private Map<Integer, Integer> usersOnSecondaryDisplaysAsMap() {
        int size = mUsersOnSecondaryDisplays.size();
        Map<Integer, Integer> map = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(mUsersOnSecondaryDisplays.keyAt(i), mUsersOnSecondaryDisplays.valueAt(i));
        }
        return map;
    }
}
