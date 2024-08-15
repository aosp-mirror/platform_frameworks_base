/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.systemui.volume;

import static android.media.AudioManager.CSD_WARNING_DOSE_REACHED_1X;
import static android.media.AudioManager.CSD_WARNING_DOSE_REPEATED_5X;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;


import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.AudioManager;
import android.platform.test.annotations.EnableFlags;
import android.testing.TestableLooper;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.Flags;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.plugins.VolumeDialog;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.collect.ImmutableList;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Optional;

@SmallTest
@RunWith(AndroidJUnit4.class)
@TestableLooper.RunWithLooper
public class CsdWarningDialogTest extends SysuiTestCase {

    private NotificationManager mNotificationManager;
    private AudioManager mAudioManager;
    private BroadcastDispatcher mFakeBroadcastDispatcher;
    private CsdWarningDialog mDialog;
    private static final String DISMISS_CSD_NOTIFICATION =
            "com.android.systemui.volume.DISMISS_CSD_NOTIFICATION";
    private final Optional<ImmutableList<CsdWarningAction>> mEmptyActions =
            Optional.of(ImmutableList.of());

    @Before
    public void setup() {
        mNotificationManager = mock(NotificationManager.class);
        mContext.addMockSystemService(NotificationManager.class, mNotificationManager);

        mAudioManager = mock(AudioManager.class);
        mContext.addMockSystemService(AudioManager.class, mAudioManager);
        mFakeBroadcastDispatcher = getFakeBroadcastDispatcher();
    }

    @Test
    public void create1XCsdDialogAndWait_sendsNotification() {
        FakeExecutor executor =  new FakeExecutor(new FakeSystemClock());
        // instantiate directly instead of via factory; we don't want executor to be @Background
        mDialog = new CsdWarningDialog(CSD_WARNING_DOSE_REACHED_1X, mContext,
                mAudioManager, mNotificationManager, executor, null,
                mEmptyActions,
                mFakeBroadcastDispatcher);

        mDialog.show();
        executor.advanceClockToLast();
        executor.runAllReady();
        mDialog.dismiss();

        verify(mNotificationManager).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_CSD_LOWER_AUDIO), any(Notification.class));
    }

    @Test
    public void create5XCsdDialogAndWait_willSendNotification() {
        FakeExecutor executor =  new FakeExecutor(new FakeSystemClock());
        mDialog = new CsdWarningDialog(CSD_WARNING_DOSE_REPEATED_5X, mContext,
                mAudioManager, mNotificationManager, executor, null,
                mEmptyActions,
                mFakeBroadcastDispatcher);

        mDialog.show();

        verify(mNotificationManager).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_CSD_LOWER_AUDIO), any(Notification.class));
    }

    @Test
    @EnableFlags(Flags.FLAG_SOUNDDOSE_CUSTOMIZATION)
    public void create1XCsdDialogWithActionsAndUndoIntent_willRegisterReceiverAndUndoVolume() {
        FakeExecutor executor = new FakeExecutor(new FakeSystemClock());
        Intent undoIntent = new Intent(VolumeDialog.ACTION_VOLUME_UNDO)
                .setPackage(mContext.getPackageName());
        mDialog = new CsdWarningDialog(CSD_WARNING_DOSE_REPEATED_5X, mContext,
                mAudioManager, mNotificationManager, executor, null,
                Optional.of(ImmutableList.of(new CsdWarningAction("Undo", undoIntent, false))),
                mFakeBroadcastDispatcher);

        when(mAudioManager.getStreamVolume(AudioManager.STREAM_MUSIC)).thenReturn(25);
        mDialog.show();
        executor.advanceClockToLast();
        executor.runAllReady();
        mDialog.dismiss();
        mDialog.mReceiverUndo.onReceive(mContext, undoIntent);

        verify(mNotificationManager).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_CSD_LOWER_AUDIO),
                any(Notification.class));
        verify(mAudioManager).setStreamVolume(
                eq(AudioManager.STREAM_MUSIC),
                eq(25),
                eq(AudioManager.FLAG_SHOW_UI));
    }

    @Test
    @EnableFlags(Flags.FLAG_SOUNDDOSE_CUSTOMIZATION)
    public void deleteNotificationIntent_willUnregisterAllReceivers() {
        FakeExecutor executor = new FakeExecutor(new FakeSystemClock());
        Intent undoIntent = new Intent(VolumeDialog.ACTION_VOLUME_UNDO)
                .setPackage(mContext.getPackageName());
        mDialog = new CsdWarningDialog(CSD_WARNING_DOSE_REPEATED_5X, mContext,
                mAudioManager, mNotificationManager, executor, null,
                Optional.of(ImmutableList.of(new CsdWarningAction("Undo", undoIntent, false))),
                mFakeBroadcastDispatcher);
        Intent dismissIntent = new Intent(DISMISS_CSD_NOTIFICATION)
                .setPackage(mContext.getPackageName());

        mDialog.mReceiverDismissNotification.onReceive(mContext, dismissIntent);
        mDialog.show();
        executor.advanceClockToLast();
        executor.runAllReady();
        mDialog.dismiss();

        List<ResolveInfo> resolveInfoListDismiss = mContext.getPackageManager()
                .queryBroadcastReceivers(dismissIntent, PackageManager.GET_RESOLVED_FILTER);
        assertThat(resolveInfoListDismiss).hasSize(0);
        List<ResolveInfo> resolveInfoListUndo = mContext.getPackageManager()
                .queryBroadcastReceivers(undoIntent, PackageManager.GET_RESOLVED_FILTER);
        assertThat(resolveInfoListUndo).hasSize(0);
    }

    @After
    public void tearDown() {
        mDialog.destroy();
    }
}
