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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.annotation.UserIdInt;
import android.app.ActivityManagerInternal;
import android.content.Context;
import android.content.pm.UserInfo;
import android.os.UserManager;
import android.util.Log;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseIntArray;

import androidx.test.annotation.UiThreadTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.ExtendedMockitoTestCase;
import com.android.server.LocalServices;
import com.android.server.am.UserState;
import com.android.server.pm.UserManagerService.UserData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Base class for {@link UserManagerInternalTest} and {@link UserManagerInternalTest}.
 *
 * <p>{@link UserManagerService} and its {@link UserManagerInternal} implementation have a
 * "symbiotic relationship - some methods of the former simply call the latter and vice versa.
 *
 * <p>Ideally, only one of them should have the logic, but since that's not the case, this class
 * provices the infra to make it easier to test both (which in turn would make it easier / safer to
 * refactor their logic later).
 */
abstract class UserManagerServiceOrInternalTestCase extends ExtendedMockitoTestCase {

    private static final String TAG = UserManagerServiceOrInternalTestCase.class.getSimpleName();

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

    /**
     * Id of another secondary display (i.e, not {@link android.view.Display.DEFAULT_DISPLAY}).
     */
    private static final int ANOTHER_SECONDARY_DISPLAY_ID = 108;

    private final Object mPackagesLock = new Object();
    private final Context mRealContext = androidx.test.InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private final SparseArray<UserData> mUsers = new SparseArray<>();

    // TODO(b/244644281): manipulating mUsersOnSecondaryDisplays directly leaks implementation
    // details into the unit test, but it's fine for now - in the long term, this logic should be
    // refactored into a proper UserDisplayAssignment class.
    private final SparseIntArray mUsersOnSecondaryDisplays = new SparseIntArray();

    private Context mSpiedContext;
    private UserManagerService mStandardUms;
    private UserManagerService mMumdUms;
    private UserManagerInternal mStandardUmi;
    private UserManagerInternal mMumdUmi;

    private @Mock PackageManagerService mMockPms;
    private @Mock UserDataPreparer mMockUserDataPreparer;
    private @Mock ActivityManagerInternal mActivityManagerInternal;

    /**
     * Reference to the {@link UserManagerService} being tested.
     *
     * <p>By default, such service doesn't support {@code MUMD} (Multiple Users on Multiple
     * Displays), but that can be changed by calling {@link #enableUsersOnSecondaryDisplays()}.
     */
    protected UserManagerService mUms;

    /**
     * Reference to the {@link UserManagerInternal} being tested.
     *
     * <p>By default, such service doesn't support {@code MUMD} (Multiple Users on Multiple
     * Displays), but that can be changed by calling {@link #enableUsersOnSecondaryDisplays()}.
     */
    protected UserManagerInternal mUmi;

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder
                .spyStatic(UserManager.class)
                .spyStatic(LocalServices.class);
    }

    @Before
    @UiThreadTest // Needed to initialize main handler
    public final void setFixtures() {
        mSpiedContext = spy(mRealContext);

        // Called when WatchedUserStates is constructed
        doNothing().when(() -> UserManager.invalidateIsUserUnlockedCache());

        // Need to set both UserManagerService instances here, as they need to be run in the
        // UiThread

        // mMumdUms / mMumdUmi
        mockIsUsersOnSecondaryDisplaysEnabled(/* usersOnSecondaryDisplaysEnabled= */ true);
        mMumdUms = new UserManagerService(mSpiedContext, mMockPms, mMockUserDataPreparer,
                mPackagesLock, mRealContext.getDataDir(), mUsers, mUsersOnSecondaryDisplays);
        assertWithMessage("UserManagerService.isUsersOnSecondaryDisplaysEnabled()")
                .that(mMumdUms.isUsersOnSecondaryDisplaysEnabled())
                .isTrue();
        mMumdUmi = LocalServices.getService(UserManagerInternal.class);
        assertWithMessage("LocalServices.getService(UserManagerInternal.class)").that(mMumdUmi)
                .isNotNull();
        resetUserManagerInternal();

        // mStandardUms / mStandardUmi
        mockIsUsersOnSecondaryDisplaysEnabled(/* usersOnSecondaryDisplaysEnabled= */ false);
        mStandardUms = new UserManagerService(mSpiedContext, mMockPms, mMockUserDataPreparer,
                mPackagesLock, mRealContext.getDataDir(), mUsers, mUsersOnSecondaryDisplays);
        assertWithMessage("UserManagerService.isUsersOnSecondaryDisplaysEnabled()")
                .that(mStandardUms.isUsersOnSecondaryDisplaysEnabled())
                .isFalse();
        mStandardUmi = LocalServices.getService(UserManagerInternal.class);
        assertWithMessage("LocalServices.getService(UserManagerInternal.class)").that(mStandardUmi)
                .isNotNull();
        setServiceFixtures(/*usersOnSecondaryDisplaysEnabled= */ false);
    }

    @After
    public final void resetUserManagerInternal() {
        // LocalServices follows the "Highlander rule" - There can be only one!
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    //////////////////////////////////////////////////////////////////////////////////////////////
    // Methods whose UMS implementation calls UMI or vice-versa - they're tested in this class, //
    // but the subclass must provide the proper implementation                                  //
    //////////////////////////////////////////////////////////////////////////////////////////////

    protected abstract boolean isUserVisible(int userId);
    protected abstract boolean isUserVisibleOnDisplay(int userId, int displayId);
    protected abstract int getDisplayAssignedToUser(int userId);
    protected abstract int getUserAssignedToDisplay(int displayId);

    /////////////////////////////////
    // Tests for the above methods //
    /////////////////////////////////

    @Test
    public void testIsUserVisible_invalidUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s)", USER_NULL).that(isUserVisible(USER_NULL)).isFalse();
    }

    @Test
    public void testIsUserVisible_currentUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisible(%s)", USER_ID).that(isUserVisible(USER_ID)).isTrue();
    }

    @Test
    public void testIsUserVisible_nonCurrentUser() {
        mockCurrentUser(OTHER_USER_ID);

        assertWithMessage("isUserVisible(%s)", USER_ID).that(isUserVisible(USER_ID)).isFalse();
    }

    @Test
    public void testIsUserVisible_startedProfileOfcurrentUser() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, UserState.STATE_RUNNING_UNLOCKED);

        assertWithMessage("isUserVisible(%s)", PROFILE_USER_ID).that(isUserVisible(PROFILE_USER_ID))
                .isTrue();
    }

    @Test
    public void testIsUserVisible_stoppedProfileOfcurrentUser() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisible(%s)", PROFILE_USER_ID).that(isUserVisible(PROFILE_USER_ID))
                .isFalse();
    }

    @Test
    public void testIsUserVisible_bgUserOnSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisible(%s)", USER_ID).that(isUserVisible(USER_ID)).isTrue();
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // isUserVisible() for bg users relies only on the user / display assignments

    @Test
    public void testIsUserVisibleOnDisplay_invalidUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_NULL, DEFAULT_DISPLAY)
                .that(isUserVisibleOnDisplay(USER_NULL, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_currentUserInvalidDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, INVALID_DISPLAY)
                .that(isUserVisibleOnDisplay(USER_ID, INVALID_DISPLAY)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_currentUserDefaultDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, DEFAULT_DISPLAY)
                .that(isUserVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_currentUserSecondaryDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_mumd_currentUserUnassignedSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(USER_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_mumd_currentUserSecondaryDisplayAssignedToAnotherUser() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(USER_ID);
        assignUserToDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_mumd_startedProfileOfCurrentUserSecondaryDisplayAssignedToAnotherUser() {
        enableUsersOnSecondaryDisplays();
        addDefaultProfileAndParent();
        startDefaultProfile();
        mockCurrentUser(PARENT_USER_ID);
        assignUserToDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_mumd_stoppedProfileOfCurrentUserSecondaryDisplayAssignedToAnotherUser() {
        enableUsersOnSecondaryDisplays();
        addDefaultProfileAndParent();
        stopDefaultProfile();
        mockCurrentUser(PARENT_USER_ID);
        assignUserToDisplay(OTHER_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_nonCurrentUserDefaultDisplay() {
        mockCurrentUser(OTHER_USER_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, DEFAULT_DISPLAY)
                .that(isUserVisibleOnDisplay(USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_startedProfileOfcurrentUserInvalidDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, UserState.STATE_RUNNING_UNLOCKED);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, INVALID_DISPLAY)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserInvalidDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, INVALID_DISPLAY)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_startedProfileOfcurrentUserDefaultDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, UserState.STATE_RUNNING_UNLOCKED);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, DEFAULT_DISPLAY)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserDefaultDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, DEFAULT_DISPLAY)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, DEFAULT_DISPLAY)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_startedProfileOfcurrentUserSecondaryDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, UserState.STATE_RUNNING_UNLOCKED);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_stoppedProfileOfcurrentUserSecondaryDisplay() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", PROFILE_USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID)).isFalse();
    }

    @Test
    public void testIsUserVisibleOnDisplay_mumd_bgUserOnSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(USER_ID, SECONDARY_DISPLAY_ID)).isTrue();
    }

    @Test
    public void testIsUserVisibleOnDisplay_mumd_bgUserOnAnotherSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("isUserVisibleOnDisplay(%s, %s)", USER_ID, SECONDARY_DISPLAY_ID)
                .that(isUserVisibleOnDisplay(USER_ID, ANOTHER_SECONDARY_DISPLAY_ID)).isFalse();
    }


    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // isUserVisibleOnDisplay() for bg users relies only on the user / display assignments

    @Test
    public void testGetDisplayAssignedToUser_invalidUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_NULL)
                .that(getDisplayAssignedToUser(USER_NULL)).isEqualTo(INVALID_DISPLAY);
    }

    @Test
    public void testGetDisplayAssignedToUser_currentUser() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_ID)
                .that(getDisplayAssignedToUser(USER_ID)).isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public void testGetDisplayAssignedToUser_nonCurrentUser() {
        mockCurrentUser(OTHER_USER_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_ID)
                .that(getDisplayAssignedToUser(USER_ID)).isEqualTo(INVALID_DISPLAY);
    }

    @Test
    public void testGetDisplayAssignedToUser_startedProfileOfcurrentUser() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        startDefaultProfile();
        setUserState(PROFILE_USER_ID, UserState.STATE_RUNNING_UNLOCKED);

        assertWithMessage("getDisplayAssignedToUser(%s)", PROFILE_USER_ID)
                .that(getDisplayAssignedToUser(PROFILE_USER_ID)).isEqualTo(DEFAULT_DISPLAY);
    }

    @Test
    public void testGetDisplayAssignedToUser_stoppedProfileOfcurrentUser() {
        addDefaultProfileAndParent();
        mockCurrentUser(PARENT_USER_ID);
        stopDefaultProfile();

        assertWithMessage("getDisplayAssignedToUser(%s)", PROFILE_USER_ID)
                .that(getDisplayAssignedToUser(PROFILE_USER_ID)).isEqualTo(INVALID_DISPLAY);
    }

    @Test
    public void testGetDisplayAssignedToUser_bgUserOnSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("getDisplayAssignedToUser(%s)", USER_ID)
                .that(getDisplayAssignedToUser(USER_ID)).isEqualTo(SECONDARY_DISPLAY_ID);
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // getDisplayAssignedToUser() for bg users relies only on the user / display assignments

    @Test
    public void testGetUserAssignedToDisplay_invalidDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", INVALID_DISPLAY)
                .that(getUserAssignedToDisplay(INVALID_DISPLAY)).isEqualTo(USER_ID);
    }

    @Test
    public void testGetUserAssignedToDisplay_defaultDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", DEFAULT_DISPLAY)
                .that(getUserAssignedToDisplay(DEFAULT_DISPLAY)).isEqualTo(USER_ID);
    }

    @Test
    public void testGetUserAssignedToDisplay_secondaryDisplay() {
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    @Test
    public void testGetUserAssignedToDisplay_mumd_bgUserOnSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(OTHER_USER_ID);
        assignUserToDisplay(USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    @Test
    public void testGetUserAssignedToDisplay_mumd_noUserOnSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        mockCurrentUser(USER_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    // TODO(b/244644281): scenario below shouldn't happen on "real life", as the profile cannot be
    // started on secondary display if its parent isn't, so we might need to remove (or refactor
    // this test) if/when the underlying logic changes
    @Test
    public void testGetUserAssignedToDisplay_mumd_profileOnSecondaryDisplay() {
        enableUsersOnSecondaryDisplays();
        addDefaultProfileAndParent();
        mockCurrentUser(USER_ID);
        assignUserToDisplay(PROFILE_USER_ID, SECONDARY_DISPLAY_ID);

        assertWithMessage("getUserAssignedToDisplay(%s)", SECONDARY_DISPLAY_ID)
                .that(getUserAssignedToDisplay(SECONDARY_DISPLAY_ID)).isEqualTo(USER_ID);
    }

    // NOTE: we don't need to add tests for profiles (started / stopped profiles of bg user), as
    // getUserAssignedToDisplay() for bg users relies only on the user / display assignments


    ///////////////////////////////////////////
    // Helper methods exposed to sub-classes //
    ///////////////////////////////////////////

    /**
     * Change test fixtures to use a version that supports {@code MUMD} (Multiple Users on Multiple
     * Displays).
     */
    protected final void enableUsersOnSecondaryDisplays() {
        setServiceFixtures(/* usersOnSecondaryDisplaysEnabled= */ true);
    }

    protected final void mockCurrentUser(@UserIdInt int userId) {
        mockGetLocalService(ActivityManagerInternal.class, mActivityManagerInternal);

        when(mActivityManagerInternal.getCurrentUserId()).thenReturn(userId);
    }

    protected final <T> void mockGetLocalService(Class<T> serviceClass, T service) {
        doReturn(service).when(() -> LocalServices.getService(serviceClass));
    }

    protected final void addDefaultProfileAndParent() {
        addUser(PARENT_USER_ID);
        addProfile(PROFILE_USER_ID, PARENT_USER_ID);
    }

    protected final void addProfile(@UserIdInt int profileId, @UserIdInt int parentId) {
        TestUserData profileData = new TestUserData(profileId);
        profileData.info.flags = UserInfo.FLAG_PROFILE;
        profileData.info.profileGroupId = parentId;

        addUserData(profileData);
    }

    protected final void addUser(@UserIdInt int userId) {
        TestUserData userData = new TestUserData(userId);

        addUserData(userData);
    }

    protected final void startDefaultProfile() {
        setUserState(PROFILE_USER_ID, UserState.STATE_RUNNING_UNLOCKED);
    }

    protected final void stopDefaultProfile() {
        // TODO(b/244798930): should set it to STATE_STOPPING or STATE_SHUTDOWN instead
        removeUserState(PROFILE_USER_ID);
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

    @SafeVarargs
    protected final void assertUsersAssignedToDisplays(@UserIdInt int userId, int displayId,
            @SuppressWarnings("unchecked") Pair<Integer, Integer>... others) {
        Object[] otherObjects = new Object[others.length * 2];
        for (int i = 0; i < others.length; i++) {
            Pair<Integer, Integer> other = others[i];
            otherObjects[i * 2] = other.first;
            otherObjects[i * 2 + 1] = other.second;

        }
        assertWithMessage("mUsersOnSecondaryDisplays()").that(usersOnSecondaryDisplaysAsMap())
                .containsExactly(userId, displayId, otherObjects);
    }

    protected static Pair<Integer, Integer> pair(@UserIdInt int userId, int secondaryDisplayId) {
        return new Pair<>(userId, secondaryDisplayId);
    }

    ///////////////////
    // Private infra //
    ///////////////////

    private void setServiceFixtures(boolean usersOnSecondaryDisplaysEnabled) {
        Log.d(TAG, "Setting fixtures for usersOnSecondaryDisplaysEnabled="
                + usersOnSecondaryDisplaysEnabled);
        if (usersOnSecondaryDisplaysEnabled) {
            mUms = mMumdUms;
            mUmi = mMumdUmi;
        } else {
            mUms = mStandardUms;
            mUmi = mStandardUmi;
        }
    }

    private void mockIsUsersOnSecondaryDisplaysEnabled(boolean enabled) {
        Log.d(TAG, "Mocking UserManager.isUsersOnSecondaryDisplaysEnabled() to return " + enabled);
        doReturn(enabled).when(() -> UserManager.isUsersOnSecondaryDisplaysEnabled());
    }

    private void addUserData(TestUserData userData) {
        Log.d(TAG, "Adding " + userData);
        mUsers.put(userData.info.id, userData);
    }

    private void setUserState(@UserIdInt int userId, int userState) {
        mUmi.setUserState(userId, userState);
    }

    private void removeUserState(@UserIdInt int userId) {
        mUmi.removeUserState(userId);
    }

    private Map<Integer, Integer> usersOnSecondaryDisplaysAsMap() {
        int size = mUsersOnSecondaryDisplays.size();
        Map<Integer, Integer> map = new LinkedHashMap<>(size);
        for (int i = 0; i < size; i++) {
            map.put(mUsersOnSecondaryDisplays.keyAt(i), mUsersOnSecondaryDisplays.valueAt(i));
        }
        return map;
    }

    private static final class TestUserData extends UserData {

        @SuppressWarnings("deprecation")
        TestUserData(@UserIdInt int userId) {
            info = new UserInfo();
            info.id = userId;
        }

        @Override
        public String toString() {
            return "TestUserData[" + info.toFullString() + "]";
        }
    }
}
