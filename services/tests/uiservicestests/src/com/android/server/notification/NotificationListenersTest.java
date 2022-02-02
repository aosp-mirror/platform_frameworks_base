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
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.INotificationManager;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.service.notification.NotificationStats;
import android.service.notification.StatusBarNotification;
import android.testing.TestableContext;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.internal.util.reflection.FieldSetter;

import java.util.List;

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
        NotificationRecord r = mock(NotificationRecord.class);
        NotificationRecord old = mock(NotificationRecord.class);

        // before the lockdown mode
        when(mNm.isInLockDownMode()).thenReturn(false);
        mListeners.notifyPostedLocked(r, old, true);
        mListeners.notifyPostedLocked(r, old, false);
        verify(mListeners, times(2)).getServices();

        // in the lockdown mode
        reset(r);
        reset(old);
        reset(mListeners);
        when(mNm.isInLockDownMode()).thenReturn(true);
        mListeners.notifyPostedLocked(r, old, true);
        mListeners.notifyPostedLocked(r, old, false);
        verify(mListeners, never()).getServices();
    }

    @Test
    public void testnotifyRankingUpdateLockedInLockdownMode() {
        List chn = mock(List.class);

        // before the lockdown mode
        when(mNm.isInLockDownMode()).thenReturn(false);
        mListeners.notifyRankingUpdateLocked(chn);
        verify(chn, times(1)).size();

        // in the lockdown mode
        reset(chn);
        when(mNm.isInLockDownMode()).thenReturn(true);
        mListeners.notifyRankingUpdateLocked(chn);
        verify(chn, never()).size();
    }

    @Test
    public void testNotifyRemovedLockedInLockdownMode() throws NoSuchFieldException {
        StatusBarNotification sbn = mock(StatusBarNotification.class);
        NotificationRecord r = mock(NotificationRecord.class);
        NotificationStats rs = mock(NotificationStats.class);
        FieldSetter.setField(r,
                NotificationRecord.class.getDeclaredField("sbn"),
                sbn);
        FieldSetter.setField(mNm,
                NotificationManagerService.class.getDeclaredField("mHandler"),
                mock(NotificationManagerService.WorkerHandler.class));

        // before the lockdown mode
        when(mNm.isInLockDownMode()).thenReturn(false);
        mListeners.notifyRemovedLocked(r, 0, rs);
        mListeners.notifyRemovedLocked(r, 0, rs);
        verify(sbn, times(2)).cloneLight();

        // in the lockdown mode
        reset(sbn);
        reset(r);
        reset(rs);
        when(mNm.isInLockDownMode()).thenReturn(true);
        mListeners.notifyRemovedLocked(r, 0, rs);
        mListeners.notifyRemovedLocked(r, 0, rs);
        verify(sbn, never()).cloneLight();
    }
}
