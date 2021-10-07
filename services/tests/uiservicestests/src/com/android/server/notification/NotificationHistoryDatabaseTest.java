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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AlarmManager;
import android.app.NotificationHistory;
import android.app.NotificationHistory.HistoricalNotification;
import android.content.Context;
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
import java.util.ArrayList;
import java.util.Calendar;
import java.util.GregorianCalendar;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class NotificationHistoryDatabaseTest extends UiServiceTestCase {

    File mRootDir;
    @Mock
    Handler mFileWriteHandler;
    @Mock
    Context mContext;
    @Mock
    AlarmManager mAlarmManager;

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
        when(mContext.getSystemService(AlarmManager.class)).thenReturn(mAlarmManager);
        when(mContext.getUser()).thenReturn(getContext().getUser());
        when(mContext.getPackageName()).thenReturn(getContext().getPackageName());

        mRootDir = new File(mContext.getFilesDir(), "NotificationHistoryDatabaseTest");

        mDataBase = new NotificationHistoryDatabase(mContext, mFileWriteHandler, mRootDir);
        mDataBase.init();
    }

    @Test
    public void testDeletionReceiver() {
        verify(mContext, times(1)).registerReceiver(any(), any());
    }

    @Test
    public void testPrune() throws Exception {
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTimeInMillis(10);
        int retainDays = 1;

        List<AtomicFile> expectedFiles = new ArrayList<>();

        // add 5 files with a creation date of "today"
        for (long i = cal.getTimeInMillis(); i >= 5; i--) {
            File file = mock(File.class);
            when(file.getName()).thenReturn(String.valueOf(i));
            when(file.getAbsolutePath()).thenReturn(String.valueOf(i));
            AtomicFile af = new AtomicFile(file);
            expectedFiles.add(af);
            mDataBase.mHistoryFiles.addLast(af);
        }

        cal.add(Calendar.DATE, -1 * retainDays);
        // Add 5 more files more than retainDays old
        for (int i = 5; i >= 0; i--) {
            File file = mock(File.class);
            when(file.getName()).thenReturn(String.valueOf(cal.getTimeInMillis() - i));
            when(file.getAbsolutePath()).thenReturn(String.valueOf(cal.getTimeInMillis() - i));
            AtomicFile af = new AtomicFile(file);
            mDataBase.mHistoryFiles.addLast(af);
        }

        // back to today; trim everything a day + old
        cal.add(Calendar.DATE, 1 * retainDays);
        mDataBase.prune(retainDays, cal.getTimeInMillis());

        assertThat(mDataBase.mHistoryFiles).containsExactlyElementsIn(expectedFiles);

        verify(mAlarmManager, times(6)).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testPrune_badFileName_noCrash() {
        GregorianCalendar cal = new GregorianCalendar();
        cal.setTimeInMillis(10);
        int retainDays = 1;

        List<AtomicFile> expectedFiles = new ArrayList<>();

        // add 5 files with a creation date of "today", but the file names are bad
        for (long i = cal.getTimeInMillis(); i >= 5; i--) {
            File file = mock(File.class);
            when(file.getName()).thenReturn(i + ".bak");
            when(file.getAbsolutePath()).thenReturn(i + ".bak");
            AtomicFile af = new AtomicFile(file);
            mDataBase.mHistoryFiles.addLast(af);
        }

        // trim everything a day+ old
        cal.add(Calendar.DATE, 1 * retainDays);
        mDataBase.prune(retainDays, cal.getTimeInMillis());

        assertThat(mDataBase.mHistoryFiles).containsExactlyElementsIn(expectedFiles);
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
    public void testForceWriteToDisk_bypassesExistingWrites() {
        when(mFileWriteHandler.hasCallbacks(any())).thenReturn(true);
        mDataBase.forceWriteToDisk();
        verify(mFileWriteHandler, times(1)).post(any());
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
    public void testAddNotification_newestFirst() {
        HistoricalNotification n = getHistoricalNotification(1);
        HistoricalNotification n2 = getHistoricalNotification(2);

        mDataBase.addNotification(n);

        // second add should not trigger another write
        mDataBase.addNotification(n2);

        assertThat(mDataBase.mBuffer.getNotificationsToWrite().get(0)).isEqualTo(n2);
        assertThat(mDataBase.mBuffer.getNotificationsToWrite().get(1)).isEqualTo(n);
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
    public void testReadNotificationHistory_readsBuffer() throws Exception {
        HistoricalNotification hn = getHistoricalNotification(1);
        mDataBase.addNotification(hn);

        NotificationHistory nh = mDataBase.readNotificationHistory();

        assertThat(nh.getNotificationsToWrite()).contains(hn);
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

    @Test
    public void testRemoveNotificationRunnable() throws Exception {
        NotificationHistory nh = mock(NotificationHistory.class);
        NotificationHistoryDatabase.RemoveNotificationRunnable rnr =
                mDataBase.new RemoveNotificationRunnable("pkg", 123);
        rnr.setNotificationHistory(nh);

        AtomicFile af = mock(AtomicFile.class);
        when(af.getBaseFile()).thenReturn(new File(mRootDir, "af"));
        mDataBase.mHistoryFiles.addLast(af);

        when(nh.removeNotificationFromWrite("pkg", 123)).thenReturn(true);

        mDataBase.mBuffer = mock(NotificationHistory.class);

        rnr.run();

        verify(mDataBase.mBuffer).removeNotificationFromWrite("pkg", 123);
        verify(af).openRead();
        verify(nh).removeNotificationFromWrite("pkg", 123);
        verify(af).startWrite();
    }

    @Test
    public void testRemoveNotificationRunnable_noChanges() throws Exception {
        NotificationHistory nh = mock(NotificationHistory.class);
        NotificationHistoryDatabase.RemoveNotificationRunnable rnr =
                mDataBase.new RemoveNotificationRunnable("pkg", 123);
        rnr.setNotificationHistory(nh);

        AtomicFile af = mock(AtomicFile.class);
        when(af.getBaseFile()).thenReturn(new File(mRootDir, "af"));
        mDataBase.mHistoryFiles.addLast(af);

        when(nh.removeNotificationFromWrite("pkg", 123)).thenReturn(false);

        mDataBase.mBuffer = mock(NotificationHistory.class);

        rnr.run();

        verify(mDataBase.mBuffer).removeNotificationFromWrite("pkg", 123);
        verify(af).openRead();
        verify(nh).removeNotificationFromWrite("pkg", 123);
        verify(af, never()).startWrite();
    }

    @Test
    public void testRemoveConversationRunnable() throws Exception {
        NotificationHistory nh = mock(NotificationHistory.class);
        NotificationHistoryDatabase.RemoveConversationRunnable rcr =
                mDataBase.new RemoveConversationRunnable("pkg", Set.of("convo", "another"));
        rcr.setNotificationHistory(nh);

        AtomicFile af = mock(AtomicFile.class);
        when(af.getBaseFile()).thenReturn(new File(mRootDir, "af"));
        mDataBase.mHistoryFiles.addLast(af);

        when(nh.removeConversationsFromWrite("pkg", Set.of("convo", "another"))).thenReturn(true);

        mDataBase.mBuffer = mock(NotificationHistory.class);

        rcr.run();

        verify(mDataBase.mBuffer).removeConversationsFromWrite("pkg",Set.of("convo", "another"));
        verify(af).openRead();
        verify(nh).removeConversationsFromWrite("pkg",Set.of("convo", "another"));
        verify(af).startWrite();
    }

    @Test
    public void testRemoveConversationRunnable_noChanges() throws Exception {
        NotificationHistory nh = mock(NotificationHistory.class);
        NotificationHistoryDatabase.RemoveConversationRunnable rcr =
                mDataBase.new RemoveConversationRunnable("pkg", Set.of("convo"));
        rcr.setNotificationHistory(nh);

        AtomicFile af = mock(AtomicFile.class);
        when(af.getBaseFile()).thenReturn(new File(mRootDir, "af"));
        mDataBase.mHistoryFiles.addLast(af);

        when(nh.removeConversationsFromWrite("pkg", Set.of("convo"))).thenReturn(false);

        mDataBase.mBuffer = mock(NotificationHistory.class);

        rcr.run();

        verify(mDataBase.mBuffer).removeConversationsFromWrite("pkg", Set.of("convo"));
        verify(af).openRead();
        verify(nh).removeConversationsFromWrite("pkg", Set.of("convo"));
        verify(af, never()).startWrite();
    }

    @Test
    public void testRemoveChannelRunnable() throws Exception {
        NotificationHistory nh = mock(NotificationHistory.class);
        NotificationHistoryDatabase.RemoveChannelRunnable rcr =
                mDataBase.new RemoveChannelRunnable("pkg", "channel");
        rcr.setNotificationHistory(nh);

        AtomicFile af = mock(AtomicFile.class);
        when(af.getBaseFile()).thenReturn(new File(mRootDir, "af"));
        mDataBase.mHistoryFiles.addLast(af);

        when(nh.removeChannelFromWrite("pkg", "channel")).thenReturn(true);

        mDataBase.mBuffer = mock(NotificationHistory.class);

        rcr.run();

        verify(mDataBase.mBuffer).removeChannelFromWrite("pkg", "channel");
        verify(af).openRead();
        verify(nh).removeChannelFromWrite("pkg", "channel");
        verify(af).startWrite();
    }

    @Test
    public void testRemoveChannelRunnable_noChanges() throws Exception {
        NotificationHistory nh = mock(NotificationHistory.class);
        NotificationHistoryDatabase.RemoveChannelRunnable rcr =
                mDataBase.new RemoveChannelRunnable("pkg", "channel");
        rcr.setNotificationHistory(nh);

        AtomicFile af = mock(AtomicFile.class);
        when(af.getBaseFile()).thenReturn(new File(mRootDir, "af"));
        mDataBase.mHistoryFiles.addLast(af);

        when(nh.removeChannelFromWrite("pkg", "channel")).thenReturn(false);

        mDataBase.mBuffer = mock(NotificationHistory.class);

        rcr.run();

        verify(mDataBase.mBuffer).removeChannelFromWrite("pkg", "channel");
        verify(af).openRead();
        verify(nh).removeChannelFromWrite("pkg", "channel");
        verify(af, never()).startWrite();
    }

    @Test
    public void testWriteBufferRunnable() throws Exception {
        NotificationHistory nh = mock(NotificationHistory.class);
        when(nh.getPooledStringsToWrite()).thenReturn(new String[]{});
        when(nh.getNotificationsToWrite()).thenReturn(new ArrayList<>());
        NotificationHistoryDatabase.WriteBufferRunnable wbr =
                mDataBase.new WriteBufferRunnable();

        mDataBase.mBuffer = nh;
        AtomicFile af = mock(AtomicFile.class);
        File file = mock(File.class);
        when(file.getName()).thenReturn("5");
        when(af.getBaseFile()).thenReturn(file);

        wbr.run(5, af);

        assertThat(mDataBase.mHistoryFiles.size()).isEqualTo(1);
        assertThat(mDataBase.mBuffer).isNotEqualTo(nh);
        verify(mAlarmManager, times(1)).setExactAndAllowWhileIdle(anyInt(), anyLong(), any());
    }

    @Test
    public void testRemoveFilePathFromHistory_hasMatch() throws Exception {
        for (int i = 0; i < 5; i++) {
            AtomicFile af = mock(AtomicFile.class);
            when(af.getBaseFile()).thenReturn(new File(mRootDir, "af" + i));
            mDataBase.mHistoryFiles.addLast(af);
        }
        // Baseline size of history files
        assertThat(mDataBase.mHistoryFiles.size()).isEqualTo(5);

        // Remove only file number 3
        String filePathToRemove = new File(mRootDir, "af3").getAbsolutePath();
        mDataBase.removeFilePathFromHistory(filePathToRemove);
        assertThat(mDataBase.mHistoryFiles.size()).isEqualTo(4);
    }

    @Test
    public void testRemoveFilePathFromHistory_noMatch() throws Exception {
        for (int i = 0; i < 5; i++) {
            AtomicFile af = mock(AtomicFile.class);
            when(af.getBaseFile()).thenReturn(new File(mRootDir, "af" + i));
            mDataBase.mHistoryFiles.addLast(af);
        }
        // Baseline size of history files
        assertThat(mDataBase.mHistoryFiles.size()).isEqualTo(5);

        // Attempt to remove a filename that doesn't exist, expect nothing to break or change
        String filePathToRemove = new File(mRootDir, "af.thisfileisfake").getAbsolutePath();
        mDataBase.removeFilePathFromHistory(filePathToRemove);
        assertThat(mDataBase.mHistoryFiles.size()).isEqualTo(5);
    }
}
