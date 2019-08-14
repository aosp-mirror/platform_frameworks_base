/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.server.backup;

import static android.Manifest.permission.BACKUP;
import static android.Manifest.permission.INTERACT_ACROSS_USERS_FULL;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.robolectric.Shadows.shadowOf;

import android.annotation.UserIdInt;
import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;
import android.util.SparseArray;

import com.android.server.testing.shadows.ShadowApplicationPackageManager;
import com.android.server.testing.shadows.ShadowBinder;
import com.android.server.testing.shadows.ShadowEnvironment;
import com.android.server.testing.shadows.ShadowSystemServiceRegistry;
import com.android.server.testing.shadows.ShadowUserManager;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContextWrapper;

/** Tests for {@link com.android.server.backup.Trampoline}. */
@RunWith(RobolectricTestRunner.class)
@Config(
        shadows = {
                ShadowApplicationPackageManager.class,
                ShadowBinder.class,
                ShadowUserManager.class,
                ShadowEnvironment.class,
                ShadowSystemServiceRegistry.class
        })
@Presubmit
public class TrampolineRoboTest {
    private Context mContext;
    private ShadowContextWrapper mShadowContext;
    private ShadowUserManager mShadowUserManager;
    @UserIdInt private int mUserOneId;
    @UserIdInt private int mUserTwoId;
    @Mock private UserBackupManagerService mUserOneService;
    @Mock private UserBackupManagerService mUserTwoService;

    /** Setup */
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Application application = RuntimeEnvironment.application;
        mContext = application;
        mShadowContext = shadowOf(application);
        mShadowUserManager = Shadow.extract(UserManager.get(application));

        mUserOneId = UserHandle.USER_SYSTEM + 1;
        mUserTwoId = mUserOneId + 1;
        mShadowUserManager.addUser(mUserOneId, "mUserOneId", 0);
        mShadowUserManager.addUser(mUserTwoId, "mUserTwoId", 0);

        mShadowContext.grantPermissions(BACKUP);
        mShadowContext.grantPermissions(INTERACT_ACROSS_USERS_FULL);

        ShadowBinder.setCallingUid(Process.SYSTEM_UID);
    }

    /** Test that the service registers users. */
    @Test
    public void testStartServiceForUser_registersUser() throws Exception {
        Trampoline backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);

        backupManagerService.startServiceForUser(mUserOneId);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isNotNull();
    }

    /** Test that the service registers users. */
    @Test
    public void testStartServiceForUser_withServiceInstance_registersUser() throws Exception {
        Trampoline backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);

        backupManagerService.startServiceForUser(mUserOneId, mUserOneService);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isEqualTo(mUserOneService);
    }

    /** Test that the service unregisters users when stopped. */
    @Test
    public void testStopServiceForUser_forRegisteredUser_unregistersCorrectUser() throws Exception {
        Trampoline backupManagerService =
                createServiceAndRegisterUser(mUserOneId, mUserOneService);
        backupManagerService.startServiceForUser(mUserTwoId, mUserTwoService);
        ShadowBinder.setCallingUid(Process.SYSTEM_UID);

        backupManagerService.stopServiceForUser(mUserOneId);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(1);
        assertThat(serviceUsers.get(mUserOneId)).isNull();
        assertThat(serviceUsers.get(mUserTwoId)).isEqualTo(mUserTwoService);
    }

    /** Test that the service unregisters users when stopped. */
    @Test
    public void testStopServiceForUser_forRegisteredUser_tearsDownCorrectUser() throws Exception {
        Trampoline backupManagerService =
                createServiceAndRegisterUser(mUserOneId, mUserOneService);
        backupManagerService.setBackupServiceActive(mUserTwoId, true);
        backupManagerService.startServiceForUser(mUserTwoId, mUserTwoService);

        backupManagerService.stopServiceForUser(mUserOneId);

        verify(mUserOneService).tearDownService();
        verify(mUserTwoService, never()).tearDownService();
    }

    /** Test that the service unregisters users when stopped. */
    @Test
    public void testStopServiceForUser_forUnknownUser_doesNothing() throws Exception {
        Trampoline backupManagerService = createService();
        backupManagerService.setBackupServiceActive(mUserOneId, true);
        ShadowBinder.setCallingUid(Process.SYSTEM_UID);

        backupManagerService.stopServiceForUser(mUserOneId);

        SparseArray<UserBackupManagerService> serviceUsers = backupManagerService.getUserServices();
        assertThat(serviceUsers.size()).isEqualTo(0);
    }

    private Trampoline createService() {
        return new Trampoline(mContext);
    }

    private Trampoline createServiceAndRegisterUser(
            int userId, UserBackupManagerService userBackupManagerService) {
        Trampoline backupManagerService = createService();
        backupManagerService.setBackupServiceActive(userBackupManagerService.getUserId(), true);
        backupManagerService.startServiceForUser(userId, userBackupManagerService);
        return backupManagerService;
    }
}
