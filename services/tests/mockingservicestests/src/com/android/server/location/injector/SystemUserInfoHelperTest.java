/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.server.location.injector;

import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.MockitoAnnotations.initMocks;

import android.app.ActivityManagerInternal;
import android.content.Context;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.LocalServices;
import com.android.server.location.injector.UserInfoHelper.UserListener;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class SystemUserInfoHelperTest {

    private static final int USER1_ID = 1;
    private static final int USER1_MANAGED_ID = 11;
    private static final int[] USER1_PROFILES = new int[]{USER1_ID, USER1_MANAGED_ID};
    private static final int USER2_ID = 2;
    private static final int USER2_MANAGED_ID = 12;
    private static final int[] USER2_PROFILES = new int[]{USER2_ID, USER2_MANAGED_ID};

    @Mock private Context mContext;
    @Mock private UserManager mUserManager;

    private SystemUserInfoHelper mHelper;

    @Before
    public void setUp() {
        initMocks(this);

        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        doReturn(USER1_PROFILES).when(mUserManager).getEnabledProfileIds(USER1_ID);
        doReturn(USER2_PROFILES).when(mUserManager).getEnabledProfileIds(USER2_ID);

        mHelper = new SystemUserInfoHelper(mContext);
    }

    @After
    public void tearDown() {
        LocalServices.removeServiceForTest(ActivityManagerInternal.class);
    }

    @Test
    public void testListener_SwitchUser() {
        UserListener listener = mock(UserListener.class);
        mHelper.addListener(listener);

        mHelper.dispatchOnCurrentUserChanged(USER1_ID, USER2_ID);
        verify(listener, times(1)).onUserChanged(USER1_ID, UserListener.CURRENT_USER_CHANGED);
        verify(listener, times(1)).onUserChanged(USER1_MANAGED_ID,
                UserListener.CURRENT_USER_CHANGED);
        verify(listener, times(1)).onUserChanged(USER2_ID, UserListener.CURRENT_USER_CHANGED);
        verify(listener, times(1)).onUserChanged(USER2_MANAGED_ID,
                UserListener.CURRENT_USER_CHANGED);

        mHelper.dispatchOnCurrentUserChanged(USER2_ID, USER1_ID);
        verify(listener, times(2)).onUserChanged(USER2_ID, UserListener.CURRENT_USER_CHANGED);
        verify(listener, times(2)).onUserChanged(USER2_MANAGED_ID,
                UserListener.CURRENT_USER_CHANGED);
        verify(listener, times(2)).onUserChanged(USER1_ID, UserListener.CURRENT_USER_CHANGED);
        verify(listener, times(2)).onUserChanged(USER1_MANAGED_ID,
                UserListener.CURRENT_USER_CHANGED);
    }

    @Test
    public void testListener_StartUser() {
        UserListener listener = mock(UserListener.class);
        mHelper.addListener(listener);

        mHelper.dispatchOnUserStarted(USER1_ID);
        verify(listener).onUserChanged(USER1_ID, UserListener.USER_STARTED);

        mHelper.dispatchOnUserStarted(USER1_MANAGED_ID);
        verify(listener).onUserChanged(USER1_MANAGED_ID, UserListener.USER_STARTED);
    }

    @Test
    public void testListener_StopUser() {
        UserListener listener = mock(UserListener.class);
        mHelper.addListener(listener);

        mHelper.dispatchOnUserStopped(USER2_ID);
        verify(listener).onUserChanged(USER2_ID, UserListener.USER_STOPPED);

        mHelper.dispatchOnUserStopped(USER2_MANAGED_ID);
        verify(listener).onUserChanged(USER2_MANAGED_ID, UserListener.USER_STOPPED);
    }
}
