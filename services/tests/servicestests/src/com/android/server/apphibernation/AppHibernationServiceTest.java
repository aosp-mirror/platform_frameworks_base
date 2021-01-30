/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.apphibernation;

import static org.junit.Assert.assertTrue;
import static org.mockito.AdditionalAnswers.returnsArgAt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.internal.verification.VerificationModeFactory.times;

import android.app.IActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageInfo;
import android.content.pm.ParceledListSlice;
import android.content.pm.UserInfo;
import android.net.Uri;
import android.os.RemoteException;
import android.os.UserManager;

import androidx.test.filters.SmallTest;

import com.android.server.SystemService;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for {@link com.android.server.apphibernation.AppHibernationService}
 */
@SmallTest
public final class AppHibernationServiceTest {
    private static final String PACKAGE_SCHEME = "package";
    private static final String PACKAGE_NAME_1 = "package1";
    private static final String PACKAGE_NAME_2 = "package2";
    private static final int USER_ID_1 = 1;
    private static final int USER_ID_2 = 2;

    private final List<UserInfo> mUserInfos = new ArrayList<>();

    private AppHibernationService mAppHibernationService;
    private BroadcastReceiver mBroadcastReceiver;
    @Mock
    private Context mContext;
    @Mock
    private IPackageManager mIPackageManager;
    @Mock
    private IActivityManager mIActivityManager;
    @Mock
    private UserManager mUserManager;
    @Captor
    private ArgumentCaptor<BroadcastReceiver> mReceiverCaptor;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        doReturn(mContext).when(mContext).createContextAsUser(any(), anyInt());

        mAppHibernationService = new AppHibernationService(mContext, mIPackageManager,
                mIActivityManager, mUserManager);

        verify(mContext, times(2)).registerReceiver(mReceiverCaptor.capture(), any());
        mBroadcastReceiver = mReceiverCaptor.getValue();

        doReturn(mUserInfos).when(mUserManager).getUsers();

        doAnswer(returnsArgAt(2)).when(mIActivityManager).handleIncomingUser(anyInt(), anyInt(),
                anyInt(), anyBoolean(), anyBoolean(), any(), any());

        addUser(USER_ID_1);
        mAppHibernationService.onBootPhase(SystemService.PHASE_BOOT_COMPLETED);
    }

    @Test
    public void testSetHibernatingForUser_packageIsHibernating() throws RemoteException {
        // WHEN we hibernate a package for a user
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // THEN the package is marked hibernating for the user
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_1));
    }

    @Test
    public void testSetHibernatingForUser_newPackageAdded_packageIsHibernating()
            throws RemoteException {
        // WHEN a new package is added and it is hibernated
        Intent intent = new Intent(Intent.ACTION_PACKAGE_ADDED,
                Uri.fromParts(PACKAGE_SCHEME, PACKAGE_NAME_2, null /* fragment */));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, USER_ID_1);
        mBroadcastReceiver.onReceive(mContext, intent);

        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_2, USER_ID_1, true);

        // THEN the new package is hibernated
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_2, USER_ID_1));
    }

    @Test
    public void testSetHibernatingForUser_newUserAdded_packageIsHibernating()
            throws RemoteException {
        // WHEN a new user is added and a package from the user is hibernated
        List<PackageInfo> userPackages = new ArrayList<>();
        userPackages.add(makePackageInfo(PACKAGE_NAME_1));
        doReturn(new ParceledListSlice<>(userPackages)).when(mIPackageManager)
                .getInstalledPackages(anyInt(), eq(USER_ID_2));
        Intent intent = new Intent(Intent.ACTION_USER_ADDED);
        intent.putExtra(Intent.EXTRA_USER_HANDLE, USER_ID_2);
        mBroadcastReceiver.onReceive(mContext, intent);

        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_2, true);

        // THEN the new user's package is hibernated
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_2));
    }

    @Test
    public void testIsHibernatingForUser_packageReplaced_stillReturnsHibernating()
            throws RemoteException {
        // GIVEN a package is currently hibernated
        mAppHibernationService.setHibernatingForUser(PACKAGE_NAME_1, USER_ID_1, true);

        // WHEN the package is removed but marked as replacing
        Intent intent = new Intent(Intent.ACTION_PACKAGE_REMOVED,
                Uri.fromParts(PACKAGE_SCHEME, PACKAGE_NAME_2, null /* fragment */));
        intent.putExtra(Intent.EXTRA_USER_HANDLE, USER_ID_1);
        intent.putExtra(Intent.EXTRA_REPLACING, true);
        mBroadcastReceiver.onReceive(mContext, intent);

        // THEN the package is still hibernating
        assertTrue(mAppHibernationService.isHibernatingForUser(PACKAGE_NAME_1, USER_ID_1));
    }

    @Test
    public void testSetHibernatingGlobally_packageIsHibernatingGlobally() throws RemoteException {
        // WHEN we hibernate a package
        mAppHibernationService.setHibernatingGlobally(PACKAGE_NAME_1, true);

        // THEN the package is marked hibernating for the user
        assertTrue(mAppHibernationService.isHibernatingGlobally(PACKAGE_NAME_1));
    }

    /**
     * Add a mock user with one package. Must be called before
     * {@link AppHibernationService#onBootPhase(int)} to work properly.
     */
    private void addUser(int userId) throws RemoteException {
        addUser(userId, new String[]{PACKAGE_NAME_1});
    }

    /**
     * Add a mock user with the packages specified. Must be called before
     * {@link AppHibernationService#onBootPhase(int)} to work properly
     */
    private void addUser(int userId, String[] packageNames) throws RemoteException {
        mUserInfos.add(new UserInfo(userId, "user_" + userId, 0 /* flags */));
        List<PackageInfo> userPackages = new ArrayList<>();
        for (String pkgName : packageNames) {
            userPackages.add(makePackageInfo(pkgName));
        }
        doReturn(new ParceledListSlice<>(userPackages)).when(mIPackageManager)
                .getInstalledPackages(anyInt(), eq(userId));
    }

    private static PackageInfo makePackageInfo(String packageName) {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = packageName;
        return pkg;
    }
}
