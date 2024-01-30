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

package com.android.systemui.screenrecord;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.ActivityOptions.LaunchCookie;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.Intent;
import android.os.Handler;
import android.os.RemoteException;
import android.os.UserHandle;
import android.testing.AndroidTestingRunner;

import androidx.test.filters.SmallTest;

import com.android.internal.logging.UiEventLogger;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.mediaprojection.MediaProjectionCaptureTarget;
import com.android.systemui.plugins.ActivityStarter;
import com.android.systemui.settings.UserContextProvider;
import com.android.systemui.statusbar.SysuiStatusBarStateController;
import com.android.systemui.statusbar.phone.KeyguardDismissUtil;
import com.android.systemui.statusbar.policy.KeyguardStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.io.IOException;
import java.util.concurrent.Executor;

@RunWith(AndroidTestingRunner.class)
@SmallTest
public class RecordingServiceTest extends SysuiTestCase {

    @Mock
    private UiEventLogger mUiEventLogger;
    @Mock
    private RecordingController mController;
    @Mock
    private NotificationManager mNotificationManager;
    @Mock
    private ScreenMediaRecorder mScreenMediaRecorder;
    @Mock
    private Notification mNotification;
    @Mock
    private Executor mExecutor;
    @Mock
    private Handler mHandler;
    @Mock
    private UserContextProvider mUserContextTracker;
    @Captor
    private ArgumentCaptor<Runnable> mRunnableCaptor;
    @Mock
    private KeyguardStateController mKeyguardStateController;
    @Mock
    private SysuiStatusBarStateController mStatusBarStateController;
    @Mock
    private ActivityStarter mActivityStarter;

    private static final String PERMISSION_SELF = "com.android.systemui.permission.SELF";

    private KeyguardDismissUtil mKeyguardDismissUtil = new KeyguardDismissUtil(
            mKeyguardStateController, mStatusBarStateController, mActivityStarter);

    private RecordingService mRecordingService;

    private class RecordingServiceTestable extends RecordingService {
        RecordingServiceTestable(
                RecordingController controller, Executor executor,
                Handler handler, UiEventLogger uiEventLogger,
                NotificationManager notificationManager,
                UserContextProvider userContextTracker, KeyguardDismissUtil keyguardDismissUtil) {
            super(controller, executor, handler,
                    uiEventLogger, notificationManager, userContextTracker, keyguardDismissUtil);
            attachBaseContext(mContext);
        }
    }

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mRecordingService = Mockito.spy(new RecordingServiceTestable(mController, mExecutor,
                mHandler, mUiEventLogger, mNotificationManager,
                mUserContextTracker, mKeyguardDismissUtil));

        // Return actual context info
        doReturn(mContext).when(mRecordingService).getApplicationContext();
        doReturn(mContext.getUserId()).when(mRecordingService).getUserId();
        doReturn(mContext.getPackageName()).when(mRecordingService).getPackageName();
        doReturn(mContext.getContentResolver()).when(mRecordingService).getContentResolver();
        doReturn(mContext.getResources()).when(mRecordingService).getResources();

        // Mock notifications
        doNothing().when(mRecordingService).createRecordingNotification();
        doReturn(mNotification).when(mRecordingService).createProcessingNotification();
        doReturn(mNotification).when(mRecordingService).createSaveNotification(any());
        doNothing().when(mRecordingService).createErrorNotification();
        doNothing().when(mRecordingService).showErrorToast(anyInt());
        doNothing().when(mRecordingService).stopForeground(anyInt());

        doNothing().when(mRecordingService).startForeground(anyInt(), any());
        doReturn(mScreenMediaRecorder).when(mRecordingService).getRecorder();

        doReturn(mContext).when(mUserContextTracker).getUserContext();
    }

    @Test
    public void testLogStartFullScreenRecording() {
        Intent startIntent = RecordingService.getStartIntent(mContext, 0, 0, false, null);
        mRecordingService.onStartCommand(startIntent, 0, 0);

        verify(mUiEventLogger, times(1)).log(Events.ScreenRecordEvent.SCREEN_RECORD_START);
    }

    @Test
    public void testLogStartPartialRecording() {
        MediaProjectionCaptureTarget target = new MediaProjectionCaptureTarget(new LaunchCookie());
        Intent startIntent = RecordingService.getStartIntent(mContext, 0, 0, false, target);
        mRecordingService.onStartCommand(startIntent, 0, 0);

        verify(mUiEventLogger, times(1)).log(Events.ScreenRecordEvent.SCREEN_RECORD_START);
    }

    @Test
    public void testLogStopFromQsTile() {
        Intent stopIntent = RecordingService.getStopIntent(mContext);
        mRecordingService.onStartCommand(stopIntent, 0, 0);

        // Verify that we log the correct event
        verify(mUiEventLogger, times(1)).log(Events.ScreenRecordEvent.SCREEN_RECORD_END_QS_TILE);
        verify(mUiEventLogger, times(0))
                .log(Events.ScreenRecordEvent.SCREEN_RECORD_END_NOTIFICATION);
    }

    @Test
    public void testLogStopFromNotificationIntent() {
        Intent stopIntent = RecordingService.getNotificationIntent(mContext);
        mRecordingService.onStartCommand(stopIntent, 0, 0);

        // Verify that we log the correct event
        verify(mUiEventLogger, times(1))
                .log(Events.ScreenRecordEvent.SCREEN_RECORD_END_NOTIFICATION);
        verify(mUiEventLogger, times(0)).log(Events.ScreenRecordEvent.SCREEN_RECORD_END_QS_TILE);
    }

    @Test
    public void testErrorUpdatesState() throws IOException, RemoteException {
        // When the screen recording does not start properly
        doThrow(new RuntimeException("fail")).when(mScreenMediaRecorder).start();

        Intent startIntent = RecordingService.getStartIntent(mContext, 0, 0, false, null);
        mRecordingService.onStartCommand(startIntent, 0, 0);

        assertUpdateState(false);
    }

    @Test
    public void testOnSystemRequestedStop_recordingInProgress_endsRecording() throws IOException {
        doReturn(true).when(mController).isRecording();

        mRecordingService.onStopped();

        verify(mScreenMediaRecorder).end();
    }

    @Test
    public void testOnSystemRequestedStop_recordingInProgress_updatesState() {
        doReturn(true).when(mController).isRecording();

        mRecordingService.onStopped();

        assertUpdateState(false);
    }

    @Test
    public void testOnSystemRequestedStop_recordingIsNotInProgress_doesNotEndRecording()
            throws IOException {
        doReturn(false).when(mController).isRecording();

        mRecordingService.onStopped();

        verify(mScreenMediaRecorder, never()).end();
    }

    @Test
    public void testOnSystemRequestedStop_recorderEndThrowsRuntimeException_releasesRecording()
            throws IOException {
        doReturn(true).when(mController).isRecording();
        doThrow(new RuntimeException()).when(mScreenMediaRecorder).end();

        mRecordingService.onStopped();

        verify(mScreenMediaRecorder).release();
    }

    @Test
    public void testOnSystemRequestedStop_recorderEndThrowsRuntimeException_showsErrorNotification()
            throws IOException {
        doReturn(true).when(mController).isRecording();
        doThrow(new RuntimeException()).when(mScreenMediaRecorder).end();

        mRecordingService.onStopped();

        verify(mRecordingService).createErrorNotification();
    }

    @Test
    public void testOnSystemRequestedStop_recorderEndThrowsOOMError_releasesRecording()
            throws IOException {
        doReturn(true).when(mController).isRecording();
        doThrow(new OutOfMemoryError()).when(mScreenMediaRecorder).end();

        assertThrows(Throwable.class, () -> mRecordingService.onStopped());

        verify(mScreenMediaRecorder).release();
    }

    @Test
    public void testOnErrorSaving() throws IOException {
        // When the screen recording does not save properly
        doThrow(new IllegalStateException("fail")).when(mScreenMediaRecorder).save();

        Intent startIntent = RecordingService.getStopIntent(mContext);
        mRecordingService.onStartCommand(startIntent, 0, 0);
        verify(mExecutor).execute(mRunnableCaptor.capture());
        mRunnableCaptor.getValue().run();

        assertUpdateState(false);
        verify(mNotificationManager).cancelAsUser(any(), anyInt(), any());
    }

    private void assertUpdateState(boolean state) {
        // Then the state is set to not recording, and we cancel the notification
        // non SYSTEM user doesn't have the reference to the correct controller,
        // so a broadcast is sent in case of non SYSTEM user.
        if (UserHandle.USER_SYSTEM == mContext.getUserId()) {
            verify(mController).updateState(state);
        } else {
            ArgumentCaptor<Intent> argumentCaptor = ArgumentCaptor.forClass(Intent.class);
            verify(mRecordingService).sendBroadcast(argumentCaptor.capture(), eq(PERMISSION_SELF));
            assertEquals(RecordingController.INTENT_UPDATE_STATE,
                    argumentCaptor.getValue().getAction());
        }
    }
}
