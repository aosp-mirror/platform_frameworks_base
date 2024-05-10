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

package com.android.server.dreams;

import static android.os.PowerManager.USER_ACTIVITY_EVENT_OTHER;
import static android.os.PowerManager.USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityTaskManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.res.Resources;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.IPowerManager;
import android.os.IRemoteCallback;
import android.os.PowerManager;
import android.os.RemoteException;
import android.os.test.TestLooper;
import android.service.dreams.IDreamService;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class DreamControllerTest {
    @Mock
    private DreamController.Listener mListener;
    @Mock
    private Context mContext;

    @Mock
    private ActivityTaskManager mActivityTaskManager;
    @Mock
    private IPowerManager mPowerManager;

    @Mock
    private IBinder mIBinder;
    @Mock
    private IDreamService mIDreamService;

    @Mock
    private Resources mResources;

    @Captor
    private ArgumentCaptor<ServiceConnection> mServiceConnectionACaptor;
    @Captor
    private ArgumentCaptor<IBinder.DeathRecipient> mDeathRecipientCaptor;
    @Captor
    private ArgumentCaptor<IRemoteCallback> mRemoteCallbackCaptor;

    private final TestLooper mLooper = new TestLooper();
    private final Handler mHandler = new Handler(mLooper.getLooper());

    private DreamController mDreamController;

    private Binder mToken;
    private ComponentName mDreamName;
    private ComponentName mOverlayName;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mIDreamService.asBinder()).thenReturn(mIBinder);
        when(mIBinder.queryLocalInterface(anyString())).thenReturn(mIDreamService);
        when(mContext.bindServiceAsUser(any(), any(), anyInt(), any())).thenReturn(true);
        when(mContext.getSystemService(Context.ACTIVITY_TASK_SERVICE))
                .thenReturn(mActivityTaskManager);
        when(mContext.getSystemServiceName(ActivityTaskManager.class))
                .thenReturn(Context.ACTIVITY_TASK_SERVICE);

        final PowerManager powerManager = new PowerManager(mContext, mPowerManager, null, null);
        when(mContext.getSystemService(Context.POWER_SERVICE))
                .thenReturn(powerManager);
        when(mContext.getSystemServiceName(PowerManager.class))
                .thenReturn(Context.POWER_SERVICE);
        when(mContext.getResources()).thenReturn(mResources);

        mToken = new Binder();
        mDreamName = ComponentName.unflattenFromString("dream");
        mOverlayName = ComponentName.unflattenFromString("dream_overlay");
        mDreamController = new DreamController(mContext, mHandler, mListener);
    }

    @Test
    public void startDream_attachOnServiceConnected() throws RemoteException {
        // Call dream controller to start dreaming.
        mDreamController.startDream(mToken, mDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);

        // Mock service connected.
        final ServiceConnection serviceConnection = captureServiceConnection();
        serviceConnection.onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();

        // Verify that dream service is called to attach.
        verify(mIDreamService).attach(eq(mToken), eq(false) /*doze*/,
                eq(false) /*preview*/, any());
    }

    @Test
    public void startDream_dreamListenerNotified() {
        // Call dream controller to start dreaming.
        mDreamController.startDream(mToken, mDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);

        // Mock service connected.
        final ServiceConnection serviceConnection = captureServiceConnection();
        serviceConnection.onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();

        // Verify that dream service is called to attach.
        verify(mListener).onDreamStarted(any());
    }

    @Test
    public void stopDream_dreamListenerNotified() {
        // Start dream.
        mDreamController.startDream(mToken, mDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);
        captureServiceConnection().onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();

        // Stop dream.
        mDreamController.stopDream(true /*immediate*/, "test stop dream" /*reason*/);
        mLooper.dispatchAll();

        // Verify that dream service is called to detach.
        verify(mListener).onDreamStopped(any());
    }

    @Test
    public void startDream_attachOnServiceConnectedInPreviewMode() throws RemoteException {
        // Call dream controller to start dreaming.
        mDreamController.startDream(mToken, mDreamName, true /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);

        // Mock service connected.
        final ServiceConnection serviceConnection = captureServiceConnection();
        serviceConnection.onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();

        // Verify that dream service is called to attach.
        verify(mIDreamService).attach(eq(mToken), eq(false) /*doze*/,
                eq(true) /*preview*/, any());
    }

    @Test
    public void startDream_startASecondDream_detachOldDreamOnceNewDreamIsStarted()
            throws RemoteException {
        // Start first dream.
        mDreamController.startDream(mToken, mDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);
        captureServiceConnection().onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();
        clearInvocations(mContext);

        // Set up second dream.
        final Binder newToken = new Binder();
        final ComponentName newDreamName = ComponentName.unflattenFromString("new_dream");
        final ComponentName newOverlayName = ComponentName.unflattenFromString("new_dream_overlay");
        final IDreamService newDreamService = mock(IDreamService.class);
        final IBinder newBinder = mock(IBinder.class);
        when(newDreamService.asBinder()).thenReturn(newBinder);
        when(newBinder.queryLocalInterface(anyString())).thenReturn(newDreamService);

        // Start second dream.
        mDreamController.startDream(newToken, newDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, newOverlayName, "test" /*reason*/);
        captureServiceConnection().onServiceConnected(newDreamName, newBinder);
        mLooper.dispatchAll();

        // Mock second dream started.
        verify(newDreamService).attach(eq(newToken), eq(false) /*doze*/,
                eq(false) /*preview*/, mRemoteCallbackCaptor.capture());
        mRemoteCallbackCaptor.getValue().sendResult(null /*data*/);
        mLooper.dispatchAll();

        // Verify that the first dream is called to detach.
        verify(mIDreamService).detach();
    }

    @Test
    public void stopDream_detachFromService() throws RemoteException {
        // Start dream.
        mDreamController.startDream(mToken, mDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);
        captureServiceConnection().onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();

        // Stop dream.
        mDreamController.stopDream(true /*immediate*/, "test stop dream" /*reason*/);

        // Verify that dream service is called to detach.
        verify(mIDreamService).detach();
    }

    @Test
    @FlakyTest(bugId = 293109503)
    public void serviceDisconnect_resetsScreenTimeout() throws RemoteException {
        // Start dream.
        mDreamController.startDream(mToken, mDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);
        ServiceConnection serviceConnection = captureServiceConnection();
        serviceConnection.onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();

        // Dream disconnects unexpectedly.
        serviceConnection.onServiceDisconnected(mDreamName);
        mLooper.dispatchAll();

        // Power manager receives user activity signal.
        verify(mPowerManager).userActivity(/*displayId=*/ anyInt(), /*time=*/ anyLong(),
                eq(USER_ACTIVITY_EVENT_OTHER),
                eq(USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS));
    }

    @Test
    @FlakyTest(bugId = 293109503)
    public void binderDied_resetsScreenTimeout() throws RemoteException {
        // Start dream.
        mDreamController.startDream(mToken, mDreamName, false /*isPreview*/, false /*doze*/,
                0 /*userId*/, null /*wakeLock*/, mOverlayName, "test" /*reason*/);
        captureServiceConnection().onServiceConnected(mDreamName, mIBinder);
        mLooper.dispatchAll();

        // Dream binder dies.
        captureDeathRecipient().binderDied();
        mLooper.dispatchAll();

        // Power manager receives user activity signal.
        verify(mPowerManager).userActivity(/*displayId=*/ anyInt(), /*time=*/ anyLong(),
                eq(USER_ACTIVITY_EVENT_OTHER),
                eq(USER_ACTIVITY_FLAG_NO_CHANGE_LIGHTS));
    }

    private ServiceConnection captureServiceConnection() {
        verify(mContext).bindServiceAsUser(any(), mServiceConnectionACaptor.capture(), anyInt(),
                any());
        return mServiceConnectionACaptor.getValue();
    }

    private IBinder.DeathRecipient captureDeathRecipient() throws RemoteException {
        verify(mIBinder).linkToDeath(mDeathRecipientCaptor.capture(), anyInt());
        return mDeathRecipientCaptor.getValue();
    }
}
