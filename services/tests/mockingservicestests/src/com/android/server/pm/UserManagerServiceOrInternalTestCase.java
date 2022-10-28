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
import android.util.SparseArray;

import androidx.test.annotation.UiThreadTest;

import com.android.dx.mockito.inline.extended.StaticMockitoSessionBuilder;
import com.android.server.ExtendedMockitoTestCase;
import com.android.server.LocalServices;
import com.android.server.am.UserState;
import com.android.server.pm.UserManagerService.UserData;

import org.junit.After;
import org.junit.Before;
import org.mockito.Mock;

/**
 * Base class for {@link UserManagerInternalTest} and {@link UserManagerInternalTest}.
 *
 * <p>{@link UserManagerService} and its {@link UserManagerInternal} implementation have a
 * "symbiotic relationship - some methods of the former simply call the latter and vice versa.
 *
 * <p>Ideally, only one of them should have the logic, but since that's not the case, this class
 * provides the infra to make it easier to test both (which in turn would make it easier / safer to
 * refactor their logic later).
 */
// TODO(b/244644281): there is no UserManagerInternalTest anymore as the logic being tested there
// moved to UserVisibilityController, so it might be simpler to merge this class into
// UserManagerServiceTest (once the UserVisibilityController -> UserManagerService dependency is
// fixed)
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

    private final Object mPackagesLock = new Object();
    private final Context mRealContext = androidx.test.InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private final SparseArray<UserData> mUsers = new SparseArray<>();

    private Context mSpiedContext;

    private @Mock PackageManagerService mMockPms;
    private @Mock UserDataPreparer mMockUserDataPreparer;
    private @Mock ActivityManagerInternal mActivityManagerInternal;

    /**
     * Reference to the {@link UserManagerService} being tested.
     */
    protected UserManagerService mUms;

    /**
     * Reference to the {@link UserManagerInternal} being tested.
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

        // Must construct UserManagerService in the UiThread
        mUms = new UserManagerService(mSpiedContext, mMockPms, mMockUserDataPreparer,
                mPackagesLock, mRealContext.getDataDir(), mUsers);
        mUmi = LocalServices.getService(UserManagerInternal.class);
        assertWithMessage("LocalServices.getService(UserManagerInternal.class)").that(mUmi)
                .isNotNull();
    }

    @After
    public final void resetUserManagerInternal() {
        // LocalServices follows the "Highlander rule" - There can be only one!
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    ///////////////////////////////////////////
    // Helper methods exposed to sub-classes //
    ///////////////////////////////////////////

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
        startUser(PROFILE_USER_ID);
    }

    protected final void stopDefaultProfile() {
        stopUser(PROFILE_USER_ID);
    }

    protected final void startUser(@UserIdInt int userId) {
        setUserState(userId, UserState.STATE_RUNNING_UNLOCKED);
    }

    protected final void stopUser(@UserIdInt int userId) {
        setUserState(userId, UserState.STATE_STOPPING);
    }

    protected final void setUserState(@UserIdInt int userId, int userState) {
        mUmi.setUserState(userId, userState);
    }

    ///////////////////
    // Private infra //
    ///////////////////

    private void addUserData(TestUserData userData) {
        Log.d(TAG, "Adding " + userData);
        mUsers.put(userData.info.id, userData);
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
