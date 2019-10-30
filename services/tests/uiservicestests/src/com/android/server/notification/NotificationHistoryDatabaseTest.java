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
package com.android.server.notification;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.NotificationHistory.HistoricalNotification;
import android.graphics.drawable.Icon;
import android.os.Handler;
import android.util.AtomicFile;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.UiServiceTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.File;
import java.util.Calendar;
import java.util.GregorianCalendar;

@RunWith(AndroidJUnit4.class)
public class NotificationHistoryDatabaseTest extends UiServiceTestCase {

    File mRootDir;
    @Mock
    Handler mFileWriteHandler;

    NotificationHistoryDatabase mDataBase;

    private HistoricalNotification getHistoricalNotification(int index) {
        return getHistoricalNotification("package" + index, index);
    }

    private HistoricalNotification getHistoricalNotification(String packageName, int index) {
        String expectedChannelName = "channelName" + index;
        String expectedChannelId = "channelId" + index;
        int expectedUid = 1123456 + index;
        int expectedUserId = 11 + index;
        long expectedPostTime = 987654321 + index;
        String expectedTitle = "title" + index;
        String expectedText = "text" + index;
        Icon expectedIcon = Icon.createWithResource(InstrumentationRegistry.getContext(),
                index);

        return new HistoricalNotification.Builder()
                .setPackage(packageName)
                .setChannelName(expectedChannelName)
                .setChannelId(expectedChannelId)
                .setUid(expectedUid)
                .setUserId(expectedUserId)
                .setPostedTimeMs(expectedPostTime)
                .setTitle(expectedTitle)
                .setText(expectedText)
                .setIcon(expectedIcon)
                .build();
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mRootDir = new File(mContext.getFilesDir(), "NotificationHistoryDatabaseTest");

        mDataBase = new NotificationHistoryDatabase(mRootDir);
        mDataBase.init(mFileWriteHandler);
    }

    @Test
    public void testPrune() {
        int retainDays = 1;
        for (long i = 10; i >= 5; i--) {
            File file = mock(File.class);
            when(file.lastModified()).thenReturn(i);
            AtomicFile af = new AtomicFile(file);
            mDataBase.mHistoryFiles.addLast(af);
        }
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTimeInMillis(5);
        cal.add(Calendar.DATE, -1 * retainDays);
        for (int i = 5; i >= 0; i--) {
            File file = mock(File.class);
            when(file.lastModified()).thenReturn(cal.getTimeInMillis() - i);
            AtomicFile af = new AtomicFile(file);
            mDataBase.mHistoryFiles.addLast(af);
        }
        mDataBase.prune(retainDays, 10);

        for (AtomicFile file : mDataBase.mHistoryFiles) {
            assertThat(file.getBaseFile().lastModified() > 0);
        }
    }

    @Test
    public void testOnPackageRemove_posts() {
        mDataBase.onPackageRemoved("test");
        verify(mFileWriteHandler, times(1)).post(any());
    }

    @Test
    public void testForceWriteToDisk() {
        mDataBase.forceWriteToDisk();
        verify(mFileWriteHandler, times(1)).post(any());
    }

    @Test
    public void testOnlyOneWriteRunnableInQueue() {
        when(mFileWriteHandler.hasCallbacks(any())).thenReturn(true);
        mDataBase.forceWriteToDisk();
        verify(mFileWriteHandler, never()).post(any());
    }

    @Test
    public void testAddNotification() {
        HistoricalNotification n = getHistoricalNotification(1);
        HistoricalNotification n2 = getHistoricalNotification(2);

        mDataBase.addNotification(n);
        assertThat(mDataBase.mBuffer.getNotificationsToWrite()).contains(n);
        verify(mFileWriteHandler, times(1)).postDelayed(any(), anyLong());

        // second add should not trigger another write
        mDataBase.addNotification(n2);
        assertThat(mDataBase.mBuffer.getNotificationsToWrite()).contains(n2);
        verify(mFileWriteHandler, times(1)).postDelayed(any(), anyLong());
    }

    @Test
    public void testReadNotificationHistory_readsAllFiles() throws Exception {
        for (long i = 10; i >= 5; i--) {
            AtomicFile af = mock(AtomicFile.class);
            mDataBase.mHistoryFiles.addLast(af);
        }

        mDataBase.readNotificationHistory();

        for (AtomicFile file : mDataBase.mHistoryFiles) {
            verify(file, times(1)).openRead();
        }
    }

    @Test
    public void testReadNotificationHistory_withNumFilterDoesNotReadExtraFiles() throws Exception {
        AtomicFile af = mock(AtomicFile.class);
        when(af.getBaseFile()).thenReturn(new File(mRootDir, "af"));
        mDataBase.mHistoryFiles.addLast(af);

        AtomicFile af2 = mock(AtomicFile.class);
        when(af2.getBaseFile()).thenReturn(new File(mRootDir, "af2"));
        mDataBase.mHistoryFiles.addLast(af2);

        mDataBase.readNotificationHistory(null, null, 0);

        verify(af, times(1)).openRead();
        verify(af2, never()).openRead();
    }

}
