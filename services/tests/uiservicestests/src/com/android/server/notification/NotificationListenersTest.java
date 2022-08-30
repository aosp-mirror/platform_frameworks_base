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
package com.android.server.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.testing.TestableContext;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldSetter;

public class NotificationListenersTest extends UiServiceTestCase {

    @Mock
    private PackageManager mPm;
    @Mock
    private IPackageManager miPm;

    @Mock
    NotificationManagerService mNm;
    @Mock
    private INotificationManager mINm;
    private TestableContext mContext = spy(getContext());

    NotificationManagerService.NotificationListeners mListeners;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        getContext().setMockPackageManager(mPm);
        doNothing().when(mContext).sendBroadcastAsUser(any(), any(), any());

        mListeners = spy(mNm.new NotificationListeners(
                mContext, new Object(), mock(ManagedServices.UserProfiles.class), miPm));
        when(mNm.getBinderService()).thenReturn(mINm);
    }


    @Test
    public void testNotifyPostedLockedInLockdownMode() {
        NotificationRecord r0 = mock(NotificationRecord.class);
        NotificationRecord old0 = mock(NotificationRecord.class);
        UserHandle uh0 = mock(UserHandle.class);

        NotificationRecord r1 = mock(NotificationRecord.class);
        NotificationRecord old1 = mock(NotificationRecord.class);
        UserHandle uh1 = mock(UserHandle.class);

        // Neither user0 and user1 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(false);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);

        mListeners.notifyPostedLocked(r0, old0, true);
        mListeners.notifyPostedLocked(r0, old0, false);
        verify(r0, atLeast(2)).getSbn();

        mListeners.notifyPostedLocked(r1, old1, true);
        mListeners.notifyPostedLocked(r1, old1, false);
        verify(r1, atLeast(2)).getSbn();

        // Reset
        reset(r0);
        reset(old0);
        reset(r1);
        reset(old1);

        // Only user 0 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(true);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);

        mListeners.notifyPostedLocked(r0, old0, true);
        mListeners.notifyPostedLocked(r0, old0, false);
        verify(r0, never()).getSbn();

        mListeners.notifyPostedLocked(r1, old1, true);
        mListeners.notifyPostedLocked(r1, old1, false);
        verify(r1, atLeast(2)).getSbn();
    }

    @Test
    public void testNotifyRemovedLockedInLockdownMode() throws NoSuchFieldException {
        NotificationRecord r0 = mock(NotificationRecord.class);
        NotificationStats rs0 = mock(NotificationStats.class);
        UserHandle uh0 = mock(UserHandle.class);

        NotificationRecord r1 = mock(NotificationRecord.class);
        NotificationStats rs1 = mock(NotificationStats.class);
        UserHandle uh1 = mock(UserHandle.class);

        StatusBarNotification sbn = mock(StatusBarNotification.class);
        FieldSetter.setField(mNm,
                NotificationManagerService.class.getDeclaredField("mHandler"),
                mock(NotificationManagerService.WorkerHandler.class));

        // Neither user0 and user1 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(false);
        when(r0.getSbn()).thenReturn(sbn);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);
        when(r1.getSbn()).thenReturn(sbn);

        mListeners.notifyRemovedLocked(r0, 0, rs0);
        mListeners.notifyRemovedLocked(r0, 0, rs0);
        verify(r0, atLeast(2)).getSbn();

        mListeners.notifyRemovedLocked(r1, 0, rs1);
        mListeners.notifyRemovedLocked(r1, 0, rs1);
        verify(r1, atLeast(2)).getSbn();

        // Reset
        reset(r0);
        reset(rs0);
        reset(r1);
        reset(rs1);

        // Only user 0 is in the lockdown mode
        when(r0.getUser()).thenReturn(uh0);
        when(uh0.getIdentifier()).thenReturn(0);
        when(mNm.isInLockDownMode(0)).thenReturn(true);
        when(r0.getSbn()).thenReturn(sbn);

        when(r1.getUser()).thenReturn(uh1);
        when(uh1.getIdentifier()).thenReturn(1);
        when(mNm.isInLockDownMode(1)).thenReturn(false);
        when(r1.getSbn()).thenReturn(sbn);

        mListeners.notifyRemovedLocked(r0, 0, rs0);
        mListeners.notifyRemovedLocked(r0, 0, rs0);
        verify(r0, never()).getSbn();

        mListeners.notifyRemovedLocked(r1, 0, rs1);
        mListeners.notifyRemovedLocked(r1, 0, rs1);
        verify(r1, atLeast(2)).getSbn();
    }
}
