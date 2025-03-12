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

package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.app.Application;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.UserInfo;
import android.os.UserHandle;
import android.os.UserManager;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper.RunWithLooper;

import androidx.test.filters.SmallTest;

import com.android.server.UiServiceTestCase;
import com.android.server.pm.PackageManagerService;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.stubbing.Answer;

import java.util.List;

@RunWith(AndroidTestingRunner.class)
@SmallTest
@RunWithLooper
public class EventConditionProviderTest extends UiServiceTestCase {
    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private EventConditionProvider mService;
    @Mock private UserManager mUserManager;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        Intent startIntent =
                new Intent("com.android.server.notification.EventConditionProvider");
        startIntent.setPackage("android");
        EventConditionProvider service = new EventConditionProvider();
        service.attach(
                getContext(),
                null,               // ActivityThread not actually used in Service
                CountdownConditionProvider.class.getName(),
                null,               // token not needed when not talking with the activity manager
                mock(Application.class),
                null                // mocked services don't talk with the activity manager
                );
        service.onCreate();
        service.onBind(startIntent);
        mService = spy(service);
        mService.mContext = this.getContext();

        mContext.addMockSystemService(UserManager.class, mUserManager);
        when(mUserManager.getProfiles(eq(mUserId))).thenReturn(
                List.of(new UserInfo(mUserId, "mUserId", 0)));

        doAnswer((Answer<Context>) invocationOnMock -> {
            Context mockUserContext = mock(Context.class);
            UserHandle userHandle = invocationOnMock.getArgument(2);
            when(mockUserContext.getUserId()).thenReturn(userHandle.getIdentifier());
            return mockUserContext;
        }).when(mContext).createPackageContextAsUser(any(), anyInt(), any());
    }

    @Test
    public void getComponent_returnsComponent() {
        assertThat(mService.getComponent()).isEqualTo(new ComponentName("android",
                "com.android.server.notification.EventConditionProvider"));
    }

    @Test
    public void testGetPendingIntent() {
        PendingIntent pi = mService.getPendingIntent(1000);
        assertEquals(PackageManagerService.PLATFORM_PACKAGE_NAME, pi.getIntent().getPackage());
    }

    @Test
    @DisableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onBootComplete_flagOff_loadsTrackers() {
        when(mUserManager.getUserProfiles()).thenReturn(List.of(UserHandle.of(mUserId)));

        mService.onBootComplete();

        assertThat(mService.getTrackers().size()).isEqualTo(1);
        assertThat(mService.getTrackers().keyAt(0)).isEqualTo(mUserId);
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onBootComplete_loadsTrackersForSystemUser() {
        mService.onBootComplete();

        assertThat(mService.getTrackers().size()).isEqualTo(1);
        assertThat(mService.getTrackers().keyAt(0)).isEqualTo(UserHandle.USER_SYSTEM);
        assertThat(mService.getTrackers().valueAt(0).getUserId()).isEqualTo(UserHandle.USER_SYSTEM);
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onUserSwitched_reloadsTrackers() {
        UserHandle someUser = UserHandle.of(42);
        when(mUserManager.getProfiles(eq(42))).thenReturn(List.of(new UserInfo(42, "user 42", 0)));

        mService.onUserSwitched(someUser);

        assertThat(mService.getTrackers().size()).isEqualTo(1);
        assertThat(mService.getTrackers().keyAt(0)).isEqualTo(42);
        assertThat(mService.getTrackers().valueAt(0).getUserId()).isEqualTo(42);
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onUserSwitched_reloadsTrackersIncludingProfiles() {
        UserHandle anotherUser = UserHandle.of(42);
        when(mUserManager.getProfiles(eq(42))).thenReturn(List.of(
                new UserInfo(42, "user 42", 0),
                new UserInfo(43, "profile 43", 0)));

        mService.onUserSwitched(anotherUser);

        assertThat(mService.getTrackers().size()).isEqualTo(2);
        assertThat(mService.getTrackers().keyAt(0)).isEqualTo(42);
        assertThat(mService.getTrackers().valueAt(0).getUserId()).isEqualTo(42);
        assertThat(mService.getTrackers().keyAt(1)).isEqualTo(43);
        assertThat(mService.getTrackers().valueAt(1).getUserId()).isEqualTo(43);
    }

    @Test
    @EnableFlags(android.app.Flags.FLAG_MODES_HSUM)
    public void onUserSwitched_sameUser_doesNothing() {
        UserHandle someUser = UserHandle.of(42);
        when(mUserManager.getProfiles(eq(42))).thenReturn(List.of(new UserInfo(42, "user 42", 0)));

        mService.onUserSwitched(someUser);
        assertThat(mService.getTrackers().size()).isEqualTo(1);
        assertThat(mService.getTrackers().keyAt(0)).isEqualTo(42);
        CalendarTracker originalTracker = mService.getTrackers().valueAt(0);

        mService.onUserSwitched(someUser);
        assertThat(mService.getTrackers().size()).isEqualTo(1);
        assertThat(mService.getTrackers().keyAt(0)).isEqualTo(42);
        assertThat(mService.getTrackers().valueAt(0)).isSameInstanceAs(originalTracker);
    }
}
