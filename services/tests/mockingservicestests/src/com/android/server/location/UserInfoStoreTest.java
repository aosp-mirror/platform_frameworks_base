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
package com.android.server.location;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.app.ActivityManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.UserInfo;
import android.os.Handler;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.List;

@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class UserInfoStoreTest {

    private static final int USER1_ID = 1;
    private static final int USER1_MANAGED_ID = 11;
    private static final int[] USER1_PROFILES = new int[]{USER1_ID, USER1_MANAGED_ID};
    private static final int USER2_ID = 2;
    private static final int USER2_MANAGED_ID = 12;
    private static final int[] USER2_PROFILES = new int[]{USER2_ID, USER2_MANAGED_ID};

    @Mock private Context mContext;
    @Mock private UserManager mUserManager;

    private StaticMockitoSession mMockingSession;
    private List<BroadcastReceiver> mBroadcastReceivers = new ArrayList<>();

    private UserInfoStore mStore;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .spyStatic(ActivityManager.class)
                .strictness(Strictness.WARN)
                .startMocking();

        doReturn(mUserManager).when(mContext).getSystemService(UserManager.class);
        doAnswer(invocation -> {
            mBroadcastReceivers.add(invocation.getArgument(0));
            return null;
        }).when(mContext).registerReceiverAsUser(any(BroadcastReceiver.class), any(
                UserHandle.class), any(IntentFilter.class), isNull(), any(Handler.class));
        doReturn(USER1_PROFILES).when(mUserManager).getProfileIdsWithDisabled(USER1_ID);
        doReturn(USER2_PROFILES).when(mUserManager).getProfileIdsWithDisabled(USER2_ID);
        doReturn(new UserInfo(USER1_ID, "", 0)).when(mUserManager).getProfileParent(
                USER1_MANAGED_ID);
        doReturn(new UserInfo(USER2_ID, "", 0)).when(mUserManager).getProfileParent(
                USER2_MANAGED_ID);

        doReturn(USER1_ID).when(ActivityManager::getCurrentUser);

        mStore = new UserInfoStore(mContext);
        mStore.onSystemReady();
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    private void switchUser(int userId) {
        doReturn(userId).when(ActivityManager::getCurrentUser);
        Intent intent = new Intent(Intent.ACTION_USER_SWITCHED).putExtra(Intent.EXTRA_USER_HANDLE,
                userId);
        for (BroadcastReceiver broadcastReceiver : mBroadcastReceivers) {
            broadcastReceiver.onReceive(mContext, intent);
        }
    }

    @Test
    public void testListeners() {
        UserInfoStore.UserChangedListener listener = mock(UserInfoStore.UserChangedListener.class);
        mStore.addListener(listener);

        switchUser(USER1_ID);
        verify(listener, never()).onUserChanged(anyInt(), anyInt());

        switchUser(USER2_ID);
        verify(listener).onUserChanged(USER1_ID, USER2_ID);

        switchUser(USER1_ID);
        verify(listener).onUserChanged(USER2_ID, USER1_ID);
    }

    @Test
    public void testCurrentUser() {
        assertThat(mStore.getCurrentUserId()).isEqualTo(USER1_ID);

        switchUser(USER2_ID);

        assertThat(mStore.getCurrentUserId()).isEqualTo(USER2_ID);

        switchUser(USER1_ID);

        assertThat(mStore.getCurrentUserId()).isEqualTo(USER1_ID);
    }

    @Test
    public void testIsCurrentUserOrProfile() {
        assertThat(mStore.isCurrentUserOrProfile(USER1_ID)).isTrue();
        assertThat(mStore.isCurrentUserOrProfile(USER1_MANAGED_ID)).isTrue();
        assertThat(mStore.isCurrentUserOrProfile(USER2_ID)).isFalse();
        assertThat(mStore.isCurrentUserOrProfile(USER2_MANAGED_ID)).isFalse();

        switchUser(USER2_ID);

        assertThat(mStore.isCurrentUserOrProfile(USER1_ID)).isFalse();
        assertThat(mStore.isCurrentUserOrProfile(USER2_ID)).isTrue();
        assertThat(mStore.isCurrentUserOrProfile(USER1_MANAGED_ID)).isFalse();
        assertThat(mStore.isCurrentUserOrProfile(USER2_MANAGED_ID)).isTrue();
    }

    @Test
    public void testGetParentUserId() {
        assertThat(mStore.getParentUserId(USER1_ID)).isEqualTo(USER1_ID);
        assertThat(mStore.getParentUserId(USER1_MANAGED_ID)).isEqualTo(USER1_ID);
        assertThat(mStore.getParentUserId(USER2_ID)).isEqualTo(USER2_ID);
        assertThat(mStore.getParentUserId(USER2_MANAGED_ID)).isEqualTo(USER2_ID);

        switchUser(USER2_ID);

        assertThat(mStore.getParentUserId(USER1_ID)).isEqualTo(USER1_ID);
        assertThat(mStore.getParentUserId(USER2_ID)).isEqualTo(USER2_ID);
        assertThat(mStore.getParentUserId(USER1_MANAGED_ID)).isEqualTo(USER1_ID);
        assertThat(mStore.getParentUserId(USER2_MANAGED_ID)).isEqualTo(USER2_ID);
    }
}
