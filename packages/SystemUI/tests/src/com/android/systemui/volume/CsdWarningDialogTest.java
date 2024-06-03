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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.app.Notification;
import android.app.NotificationManager;
import android.media.AudioManager;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;

import androidx.test.filters.SmallTest;

import com.android.internal.messages.nano.SystemMessageProto;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
public class CsdWarningDialogTest extends SysuiTestCase {

    private NotificationManager mNotificationManager;
    private AudioManager mAudioManager;

    @Before
    public void setup() {
        mNotificationManager = mock(NotificationManager.class);
        getContext().addMockSystemService(NotificationManager.class, mNotificationManager);

        mAudioManager = mock(AudioManager.class);
        getContext().addMockSystemService(AudioManager.class, mAudioManager);
    }

    @Test
    public void create1XCsdDialogAndWait_sendsNotification() {
        FakeExecutor executor =  new FakeExecutor(new FakeSystemClock());
        // instantiate directly instead of via factory; we don't want executor to be @Background
        CsdWarningDialog dialog = new CsdWarningDialog(CSD_WARNING_DOSE_REACHED_1X, mContext,
                mAudioManager, mNotificationManager, executor, null);

        dialog.show();
        executor.advanceClockToLast();
        executor.runAllReady();
        dialog.dismiss();

        verify(mNotificationManager).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_CSD_LOWER_AUDIO), any(Notification.class));
    }

    @Test
    public void create5XCsdDiSalogAndWait_willSendNotification() {
        FakeExecutor executor =  new FakeExecutor(new FakeSystemClock());
        CsdWarningDialog dialog = new CsdWarningDialog(CSD_WARNING_DOSE_REPEATED_5X, mContext,
                mAudioManager, mNotificationManager, executor, null);

        dialog.show();

        verify(mNotificationManager).notify(
                eq(SystemMessageProto.SystemMessage.NOTE_CSD_LOWER_AUDIO), any(Notification.class));
    }
}
