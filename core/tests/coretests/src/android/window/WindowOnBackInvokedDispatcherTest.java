/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.window;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IWindow;
import android.view.IWindowSession;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link WindowOnBackInvokedDispatcherTest}
 *
 * <p>Build/Install/Run:
 * atest FrameworksCoreTests:WindowOnBackInvokedDispatcherTest
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowOnBackInvokedDispatcherTest {
    @Mock
    private IWindowSession mWindowSession;
    @Mock
    private IWindow mWindow;
    private WindowOnBackInvokedDispatcher mDispatcher;
    @Mock
    private OnBackAnimationCallback mCallback1;
    @Mock
    private OnBackAnimationCallback mCallback2;
    private final BackMotionEvent mBackEvent = new BackMotionEvent(
            0, 0, 0, BackEvent.EDGE_LEFT, null);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mDispatcher = new WindowOnBackInvokedDispatcher(true /* applicationCallbackEnabled */);
        mDispatcher.attachToWindow(mWindowSession, mWindow);
    }

    private void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    @Test
    public void propagatesTopCallback_samePriority() throws RemoteException {
        ArgumentCaptor<OnBackInvokedCallbackInfo> captor =
                ArgumentCaptor.forClass(OnBackInvokedCallbackInfo.class);

        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCallback1);
        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCallback2);

        verify(mWindowSession, times(2)).setOnBackInvokedCallbackInfo(
                Mockito.eq(mWindow),
                captor.capture());
        captor.getAllValues().get(0).getCallback().onBackStarted(mBackEvent);
        waitForIdle();
        verify(mCallback1).onBackStarted(any(BackEvent.class));
        verifyZeroInteractions(mCallback2);

        captor.getAllValues().get(1).getCallback().onBackStarted(mBackEvent);
        waitForIdle();
        verify(mCallback2).onBackStarted(any(BackEvent.class));
        verifyNoMoreInteractions(mCallback1);
    }

    @Test
    public void propagatesTopCallback_differentPriority() throws RemoteException {
        ArgumentCaptor<OnBackInvokedCallbackInfo> captor =
                ArgumentCaptor.forClass(OnBackInvokedCallbackInfo.class);

        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, mCallback1);
        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCallback2);

        verify(mWindowSession).setOnBackInvokedCallbackInfo(
                Mockito.eq(mWindow), captor.capture());
        verifyNoMoreInteractions(mWindowSession);
        assertEquals(captor.getValue().getPriority(), OnBackInvokedDispatcher.PRIORITY_OVERLAY);
        captor.getValue().getCallback().onBackStarted(mBackEvent);
        waitForIdle();
        verify(mCallback1).onBackStarted(any(BackEvent.class));
    }

    @Test
    public void propagatesTopCallback_withRemoval() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCallback1);
        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCallback2);

        reset(mWindowSession);
        mDispatcher.unregisterOnBackInvokedCallback(mCallback1);
        verifyZeroInteractions(mWindowSession);

        mDispatcher.unregisterOnBackInvokedCallback(mCallback2);
        verify(mWindowSession).setOnBackInvokedCallbackInfo(Mockito.eq(mWindow), isNull());
    }


    @Test
    public void propagatesTopCallback_sameInstanceAddedTwice() throws RemoteException {
        ArgumentCaptor<OnBackInvokedCallbackInfo> captor =
                ArgumentCaptor.forClass(OnBackInvokedCallbackInfo.class);

        mDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY,
                mCallback1
        );
        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCallback2);
        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_DEFAULT, mCallback1);

        reset(mWindowSession);
        mDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY, mCallback2);
        verify(mWindowSession).setOnBackInvokedCallbackInfo(Mockito.eq(mWindow), captor.capture());
        captor.getValue().getCallback().onBackStarted(mBackEvent);
        waitForIdle();
        verify(mCallback2).onBackStarted(any(BackEvent.class));
    }
}
