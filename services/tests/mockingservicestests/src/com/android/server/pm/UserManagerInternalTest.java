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

import static com.android.server.pm.UserManagerInternal.PARENT_DISPLAY;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;

import android.util.Log;

import org.junit.Test;

/**
 * Run as {@code atest FrameworksMockingServicesTests:com.android.server.pm.UserManagerInternalTest}
 */
public final class UserManagerInternalTest extends UserManagerServiceOrInternalTestCase {

    private static final String TAG = UserManagerInternalTest.class.getSimpleName();

    // NOTE: most the tests below only apply to MUMD configurations, so we're not adding _mumd_
    // in the test names, but _nonMumd_ instead

    @Test
    public void testAssignUserToDisplay_nonMumd_defaultDisplayIgnored() {
        mUmi.assignUserToDisplay(USER_ID, DEFAULT_DISPLAY);

        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_nonMumd_otherDisplay_currentUser() {
        mockCurrentUser(USER_ID);

        assertThrows(UnsupportedOperationException.class,
                () -> mUmi.assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_nonMumd_otherDisplay_startProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        addDefaultProfileAndParent();
        startDefaultProfile();

        assertThrows(UnsupportedOperationException.class,
                () -> mUmi.assignUserToDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_nonMumd_otherDisplay_stoppedProfileOfcurrentUser() {
        mockCurrentUser(PARENT_USER_ID);
        addDefaultProfileAndParent();
        stopDefaultProfile();

        assertThrows(UnsupportedOperationException.class,
                () -> mUmi.assignUserToDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_defaultDisplayIgnored() {
        enableUsersOnSecondaryDisplays();

        mUmi.assignUserToDisplay(USER_ID, DEFAULT_DISPLAY);

        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_systemUser() {
        enableUsersOnSecondaryDisplays();

        assertThrows(IllegalArgumentException.class,
                () -> mUmi.assignUserToDisplay(USER_SYSTEM, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_invalidDisplay() {
        enableUsersOnSecondaryDisplays();

        assertThrows(IllegalArgumentException.class,
                () -> mUmi.assignUserToDisplay(USER_ID, INVALID_DISPLAY));
    }

    @Test
    public void testAssignUserToDisplay_currentUser() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(USER_ID);

        assertThrows(IllegalArgumentException.class,
                () -> mUmi.assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID));

        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_startedProfileOfCurrentUser() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(PARENT_USER_ID);
        addDefaultProfileAndParent();
        startDefaultProfile();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> mUmi.assignUserToDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_stoppedProfileOfCurrentUser() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(PARENT_USER_ID);
        addDefaultProfileAndParent();
        stopDefaultProfile();

        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> mUmi.assignUserToDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testAssignUserToDisplay_displayAvailable() {
        enableUsersOnSecondaryDisplays();

        mUmi.assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertUserAssignedToDisplay(USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testAssignUserToDisplay_displayAlreadyAssigned() {
        enableUsersOnSecondaryDisplays();

        mUmi.assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mUmi.assignUserToDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .matches("Cannot.*" + OTHER_USER_ID + ".*" + SECONDARY_DISPLAY_ID + ".*already.*"
                        + USER_ID + ".*");
    }

    @Test
    public void testAssignUserToDisplay_userAlreadyAssigned() {
        enableUsersOnSecondaryDisplays();

        mUmi.assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> mUmi.assignUserToDisplay(USER_ID, OTHER_SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertWithMessage("exception (%s) message", e).that(e).hasMessageThat()
                .matches("Cannot.*" + USER_ID + ".*" + OTHER_SECONDARY_DISPLAY_ID + ".*already.*"
                        + SECONDARY_DISPLAY_ID + ".*");

        assertUserAssignedToDisplay(USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testAssignUserToDisplay_profileOnSameDisplayAsParent() {
        enableUsersOnSecondaryDisplays();
        addDefaultProfileAndParent();

        mUmi.assignUserToDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        mUmi.assignUserToDisplay(PROFILE_USER_ID, PARENT_DISPLAY);

        assertUsersAssignedToDisplays(PARENT_USER_ID, SECONDARY_DISPLAY_ID,
                pair(PROFILE_USER_ID, SECONDARY_DISPLAY_ID));
    }

    @Test
    public void testAssignUserToDisplay_profileOnDifferentDisplayAsParent() {
        enableUsersOnSecondaryDisplays();
        addDefaultProfileAndParent();

        mUmi.assignUserToDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> mUmi.assignUserToDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID));

        Log.v(TAG, "Exception: " + e);
        assertUserAssignedToDisplay(PARENT_USER_ID, SECONDARY_DISPLAY_ID);
    }

    @Test
    public void testUnassignUserFromDisplay_nonMumd_ignored() {
        mockCurrentUser(USER_ID);

        mUmi.unassignUserFromDisplay(USER_SYSTEM);
        mUmi.unassignUserFromDisplay(USER_ID);
        mUmi.unassignUserFromDisplay(OTHER_USER_ID);

        assertNoUserAssignedToDisplay();
    }

    @Test
    public void testUnassignUserFromDisplay() {
        testAssignUserToDisplay_displayAvailable();

        mUmi.unassignUserFromDisplay(USER_ID);

        assertNoUserAssignedToDisplay();
    }

    @Override
    protected boolean isUserVisible(int userId) {
        return mUmi.isUserVisible(userId);
    }

    @Override
    protected boolean isUserVisibleOnDisplay(int userId, int displayId) {
        return mUmi.isUserVisible(userId, displayId);
    }

    @Override
    protected int getDisplayAssignedToUser(int userId) {
        return mUmi.getDisplayAssignedToUser(userId);
    }

    @Override
    protected int getUserAssignedToDisplay(int displayId) {
        return mUmi.getUserAssignedToDisplay(displayId);
    }
}
