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

package com.android.server.broadcastradio.aidl;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.hardware.radio.Announcement;
import android.hardware.radio.IAnnouncementListener;
import android.hardware.radio.ICloseHandle;
import android.os.IBinder;
import android.os.RemoteException;

import com.google.common.truth.Expect;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Arrays;
import java.util.List;

/**
 * Tests for AIDL HAL AnnouncementAggregator.
 */
@RunWith(MockitoJUnitRunner.class)
public final class AnnouncementAggregatorTest {
    private static final int[] TEST_ENABLED_TYPES = new int[]{Announcement.TYPE_TRAFFIC};

    private final Object mLock = new Object();
    private AnnouncementAggregator mAnnouncementAggregator;
    private IBinder.DeathRecipient mDeathRecipient;

    @Rule
    public final Expect mExpect = Expect.create();

    @Mock
    private IAnnouncementListener mListenerMock;
    @Mock
    private IBinder mBinderMock;
    private RadioModule[] mRadioModuleMocks;
    private ICloseHandle[] mCloseHandleMocks;
    private Announcement[] mAnnouncementMocks;

    @Before
    public void setUp() throws Exception {
        ArgumentCaptor<IBinder.DeathRecipient> deathRecipientCaptor =
                ArgumentCaptor.forClass(IBinder.DeathRecipient.class);
        when(mListenerMock.asBinder()).thenReturn(mBinderMock);

        mAnnouncementAggregator = new AnnouncementAggregator(mListenerMock, mLock);

        verify(mBinderMock).linkToDeath(deathRecipientCaptor.capture(), eq(0));
        mDeathRecipient = deathRecipientCaptor.getValue();
    }

    @Test
    public void constructor_withBinderDied() throws Exception {
        RemoteException remoteException = new RemoteException("Binder is died");
        doThrow(remoteException).when(mBinderMock).linkToDeath(any(), anyInt());

        RuntimeException thrown = assertThrows(RuntimeException.class, () ->
                new AnnouncementAggregator(mListenerMock, mLock));

        mExpect.withMessage("Exception for dead binder").that(thrown).hasMessageThat()
                .contains(remoteException.getMessage());
    }

    @Test
    public void onListUpdated_withOneModuleWatcher() throws Exception {
        ArgumentCaptor<IAnnouncementListener> moduleWatcherCaptor =
                ArgumentCaptor.forClass(IAnnouncementListener.class);
        watchModules(/* moduleNumber= */ 1);

        verify(mRadioModuleMocks[0]).addAnnouncementListener(moduleWatcherCaptor.capture(), any());

        moduleWatcherCaptor.getValue().onListUpdated(Arrays.asList(mAnnouncementMocks[0]));

        verify(mListenerMock).onListUpdated(any());
    }

    @Test
    public void onListUpdated_withMultipleModuleWatchers() throws Exception {
        int moduleNumber = 3;
        watchModules(moduleNumber);

        for (int index = 0; index < moduleNumber; index++) {
            ArgumentCaptor<IAnnouncementListener> moduleWatcherCaptor =
                    ArgumentCaptor.forClass(IAnnouncementListener.class);
            ArgumentCaptor<List<Announcement>> announcementsCaptor =
                    ArgumentCaptor.forClass(List.class);
            verify(mRadioModuleMocks[index])
                    .addAnnouncementListener(moduleWatcherCaptor.capture(), any());

            moduleWatcherCaptor.getValue().onListUpdated(Arrays.asList(mAnnouncementMocks[index]));

            verify(mListenerMock, times(index + 1)).onListUpdated(announcementsCaptor.capture());
            mExpect.withMessage("Number of announcements %s after %s announcements were updated",
                    announcementsCaptor.getValue(), index + 1)
                    .that(announcementsCaptor.getValue().size()).isEqualTo(index + 1);
        }
    }

    @Test
    public void onListUpdated_afterClosed_notUpdated() throws Exception {
        ArgumentCaptor<IAnnouncementListener> moduleWatcherCaptor =
                ArgumentCaptor.forClass(IAnnouncementListener.class);
        watchModules(/* moduleNumber= */ 1);
        verify(mRadioModuleMocks[0]).addAnnouncementListener(moduleWatcherCaptor.capture(), any());
        mAnnouncementAggregator.close();

        moduleWatcherCaptor.getValue().onListUpdated(Arrays.asList(mAnnouncementMocks[0]));

        verify(mListenerMock, never()).onListUpdated(any());
    }

    @Test
    public void watchModule_afterClosed_throwsException() throws Exception {
        watchModules(/* moduleNumber= */ 1);
        mAnnouncementAggregator.close();

        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> mAnnouncementAggregator.watchModule(mRadioModuleMocks[0],
                        TEST_ENABLED_TYPES));

        mExpect.withMessage("Exception for watching module after aggregator has been closed")
                .that(thrown).hasMessageThat()
                .contains("announcement aggregator has already been closed");
    }

    @Test
    public void close_withOneModuleWatcher_invokesCloseHandle() throws Exception {
        watchModules(/* moduleNumber= */ 1);

        mAnnouncementAggregator.close();

        verify(mCloseHandleMocks[0]).close();
        verify(mBinderMock).unlinkToDeath(mDeathRecipient, 0);
    }

    @Test
    public void close_withMultipleModuleWatcher_invokesCloseHandles() throws Exception {
        int moduleNumber = 3;
        watchModules(moduleNumber);

        mAnnouncementAggregator.close();

        for (int index = 0; index < moduleNumber; index++) {
            verify(mCloseHandleMocks[index]).close();
        }
    }

    @Test
    public void close_twice_invokesCloseHandleOnce() throws Exception {
        watchModules(/* moduleNumber= */ 1);

        mAnnouncementAggregator.close();
        mAnnouncementAggregator.close();

        verify(mCloseHandleMocks[0]).close();
        verify(mBinderMock).unlinkToDeath(mDeathRecipient, 0);
    }

    @Test
    public void binderDied_forDeathRecipient_invokesCloseHandle() throws Exception {
        watchModules(/* moduleNumber= */ 1);

        mDeathRecipient.binderDied();

        verify(mCloseHandleMocks[0]).close();

    }

    private void watchModules(int moduleNumber) throws RemoteException {
        mRadioModuleMocks = new RadioModule[moduleNumber];
        mCloseHandleMocks = new ICloseHandle[moduleNumber];
        mAnnouncementMocks = new Announcement[moduleNumber];

        for (int index = 0; index < moduleNumber; index++) {
            mRadioModuleMocks[index] = mock(RadioModule.class);
            mCloseHandleMocks[index] = mock(ICloseHandle.class);
            mAnnouncementMocks[index] = mock(Announcement.class);

            when(mRadioModuleMocks[index].addAnnouncementListener(any(), any()))
                    .thenReturn(mCloseHandleMocks[index]);
            mAnnouncementAggregator.watchModule(mRadioModuleMocks[index], TEST_ENABLED_TYPES);
        }
    }
}
