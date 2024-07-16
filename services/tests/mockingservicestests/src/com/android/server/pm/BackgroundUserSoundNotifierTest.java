/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.app.Notification;
import android.app.NotificationManager;
import android.content.Context;
import android.content.pm.UserInfo;
import android.media.AudioAttributes;
import android.media.AudioFocusInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.RemoteException;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.ArraySet;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)

public class BackgroundUserSoundNotifierTest {
    private final Context mRealContext = androidx.test.InstrumentationRegistry.getInstrumentation()
            .getTargetContext();
    private Context mSpiedContext;
    private BackgroundUserSoundNotifier mBackgroundUserSoundNotifier;

    private UserManager mUserManager;
    private ArraySet<Integer> mUsersToRemove;

    @Mock
    private NotificationManager mNotificationManager;
    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mSpiedContext = spy(mRealContext);
        mUsersToRemove = new ArraySet<>();
        mUserManager = UserManager.get(mRealContext);
        doReturn(mNotificationManager)
                .when(mSpiedContext).getSystemService(NotificationManager.class);
        mBackgroundUserSoundNotifier = new BackgroundUserSoundNotifier(mSpiedContext);
    }

    @After
    public void tearDown() throws Exception {
        mUsersToRemove.stream().toList().forEach(this::removeUser);
    }
    @Test
    public void testAlarmOnBackgroundUser_ForegroundUserNotified() throws RemoteException {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).build();
        UserInfo user = createUser("User",
                UserManager.USER_TYPE_FULL_SECONDARY,
                0);
        final int fgUserId = mSpiedContext.getUserId();
        final int bgUserUid = user.id * 100000;
        doReturn(UserHandle.of(fgUserId)).when(mSpiedContext).getUser();
        AudioFocusInfo afi = new AudioFocusInfo(aa, bgUserUid, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi, mSpiedContext);
        verify(mNotificationManager)
                .notifyAsUser(eq(BackgroundUserSoundNotifier.class.getSimpleName()),
                        eq(afi.getClientUid()), any(Notification.class),
                        eq(UserHandle.of(fgUserId)));
    }

    @Test
    public void testMediaOnBackgroundUser_ForegroundUserNotNotified() throws RemoteException {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).build();
        UserInfo user = createUser("User", UserManager.USER_TYPE_FULL_SECONDARY, 0);
        final int bgUserUid = mSpiedContext.getUserId() * 100000;
        AudioFocusInfo afi = new AudioFocusInfo(aa, bgUserUid, "",
                /* packageName= */ "com.android.car.audio", AudioManager.AUDIOFOCUS_GAIN,
                AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0, Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi, mSpiedContext);
        verifyZeroInteractions(mNotificationManager);
    }

    @Test
    public void testAlarmOnForegroundUser_ForegroundUserNotNotified() throws RemoteException {
        AudioAttributes aa = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ALARM).build();
        final int fgUserId = mSpiedContext.getUserId();
        final int fgUserUid = fgUserId * 100000;
        doReturn(UserHandle.of(fgUserId)).when(mSpiedContext).getUser();
        AudioFocusInfo afi = new AudioFocusInfo(aa, fgUserUid, "", /* packageName= */ "",
                AudioManager.AUDIOFOCUS_GAIN, AudioManager.AUDIOFOCUS_NONE, /* flags= */ 0,
                Build.VERSION.SDK_INT);
        clearInvocations(mNotificationManager);
        mBackgroundUserSoundNotifier.notifyForegroundUserAboutSoundIfNecessary(afi, mSpiedContext);
        verifyZeroInteractions(mNotificationManager);
    }


    private UserInfo createUser(String name, String userType, int flags) {
        UserInfo user = mUserManager.createUser(name, userType, flags);
        if (user != null) {
            mUsersToRemove.add(user.id);
        }
        return user;
    }
    private void removeUser(int userId) {
        mUserManager.removeUser(userId);
    }

}
