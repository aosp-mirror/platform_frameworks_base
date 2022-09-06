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
import android.os.UserHandle;
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
import org.junit.Test;
import org.mockito.Mock;

/**
 * Run as {@code atest FrameworksMockingServicesTests:com.android.server.pm.UserManagerServiceTest}
 */
public final class UserManagerServiceTest extends ExtendedMockitoTestCase {

    private static final String TAG = UserManagerServiceTest.class.getSimpleName();

    private final Object mPackagesLock = new Object();
    private final Context mRealContext = androidx.test.InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private Context mSpiedContext;

    private @Mock PackageManagerService mMockPms;
    private @Mock UserDataPreparer mMockUserDataPreparer;
    private @Mock ActivityManagerInternal mActivityManagerInternal;

    private final SparseArray<UserData> mUsers = new SparseArray<>();
    private UserManagerService mUms;
    private UserManagerInternal mUmi;

    @Override
    protected void initializeSession(StaticMockitoSessionBuilder builder) {
        builder
                .spyStatic(UserManager.class)
                .spyStatic(LocalServices.class);
    }

    @Before
    @UiThreadTest // Needed to initialize main handler
    public void setFixtures() {
        mSpiedContext = spy(mRealContext);

        // Called when WatchedUserStates is constructed
        doNothing().when(() -> UserManager.invalidateIsUserUnlockedCache());

        mUms = new UserManagerService(mSpiedContext, mMockPms, mMockUserDataPreparer,
                mPackagesLock, mRealContext.getDataDir(), mUsers);
        mUmi = LocalServices.getService(UserManagerInternal.class);

        assertWithMessage("LocalServices.getService(UserManagerInternal.class)").that(mUmi)
                .isNotNull();
    }

    @After
    public void resetLocalService() {
        // LocalServices follows the "Highlander rule" - There can be only one!
        LocalServices.removeServiceForTest(UserManagerInternal.class);
    }

    @Test
    public void testgetCurrentUserId_amInternalNotReady() {
        mockGetLocalService(ActivityManagerInternal.class, null);

        assertWithMessage("getCurrentUserId()").that(mUms.getCurrentUserId())
                .isEqualTo(UserHandle.USER_NULL);
    }

    @Test
    public void testgetCurrentUserId() {
        mockCurrentUser(42);

        assertWithMessage("getCurrentUserId()").that(mUms.getCurrentUserId())
                .isEqualTo(42);
    }

    @Test
    public void testIsCurrentUserOrRunningProfileOfCurrentUser_currentUser() {
        int userId = 42;
        mockCurrentUser(userId);

        assertWithMessage("isCurrentUserOrRunningProfileOfCurrentUser(%s)", userId)
                .that(mUms.isCurrentUserOrRunningProfileOfCurrentUser(userId)).isTrue();
    }

    @Test
    public void testIsCurrentUserOrRunningProfileOfCurrentUser_notCurrentUser() {
        int userId = 42;
        mockCurrentUser(108);

        assertWithMessage("isCurrentUserOrRunningProfileOfCurrentUser(%s)", userId)
                .that(mUms.isCurrentUserOrRunningProfileOfCurrentUser(userId)).isFalse();
    }

    @Test
    public void testIsCurrentUserOrRunningProfileOfCurrentUser_startedProfileOfCurrentUser() {
        int parentId = 108;
        int profileId = 42;
        addUser(parentId);
        addProfile(profileId, parentId);
        mockCurrentUser(parentId);
        setUserState(profileId, UserState.STATE_RUNNING_UNLOCKED);

        assertWithMessage("isCurrentUserOrRunningProfileOfCurrentUser(%s)", profileId)
                .that(mUms.isCurrentUserOrRunningProfileOfCurrentUser(profileId)).isTrue();
    }

    @Test
    public void testIsCurrentUserOrRunningProfileOfCurrentUser_stoppedProfileOfCurrentUser() {
        int parentId = 108;
        int profileId = 42;
        addUser(parentId);
        addProfile(profileId, parentId);
        mockCurrentUser(parentId);
        // TODO(b/244798930): should set it to STATE_STOPPING or STATE_SHUTDOWN instead
        removeUserState(profileId);

        assertWithMessage("isCurrentUserOrRunningProfileOfCurrentUser(%s)", profileId)
                .that(mUms.isCurrentUserOrRunningProfileOfCurrentUser(profileId)).isFalse();
    }

    @Test
    public void testIsCurrentUserOrRunningProfileOfCurrentUser_profileOfNonCurrentUSer() {
        int parentId = 108;
        int profileId = 42;
        int currentUserId = 666;
        addUser(parentId);
        addProfile(profileId, parentId);
        mockCurrentUser(currentUserId);

        assertWithMessage("isCurrentUserOrRunningProfileOfCurrentUser(%s)", profileId)
                .that(mUms.isCurrentUserOrRunningProfileOfCurrentUser(profileId)).isFalse();
    }

    private void mockCurrentUser(@UserIdInt int userId) {
        mockGetLocalService(ActivityManagerInternal.class, mActivityManagerInternal);

        when(mActivityManagerInternal.getCurrentUserId()).thenReturn(userId);
    }

    private <T> void mockGetLocalService(Class<T> serviceClass, T service) {
        doReturn(service).when(() -> LocalServices.getService(serviceClass));
    }

    private void addProfile(@UserIdInt int profileId, @UserIdInt int parentId) {
        TestUserData profileData = new TestUserData(profileId);
        profileData.info.flags = UserInfo.FLAG_PROFILE;
        profileData.info.profileGroupId = parentId;

        addUserData(profileData);
    }

    private void addUser(@UserIdInt int userId) {
        TestUserData userData = new TestUserData(userId);

        addUserData(userData);
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

    private static final class TestUserData extends UserData {

        @SuppressWarnings("unused")
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
