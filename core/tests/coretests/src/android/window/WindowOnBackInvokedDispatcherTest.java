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

import static android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT;
import static android.window.OnBackInvokedDispatcher.PRIORITY_OVERLAY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IWindow;
import android.view.IWindowSession;
import android.view.ImeBackAnimationController;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

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
    @Mock
    private ImeOnBackInvokedDispatcher.ImeOnBackInvokedCallback mImeCallback;
    @Mock
    private ImeOnBackInvokedDispatcher.DefaultImeOnBackAnimationCallback mDefaultImeCallback;
    @Mock
    private ImeBackAnimationController mImeBackAnimationController;
    @Mock
    private Context mContext;
    @Mock
    private ApplicationInfo mApplicationInfo;

    private int mCallbackInfoCalls = 0;

    private final BackMotionEvent mBackEvent = new BackMotionEvent(
            /* touchX = */ 0,
            /* touchY = */ 0,
            /* progress = */ 0,
            /* velocityX = */ 0,
            /* velocityY = */ 0,
            /* triggerBack = */ false,
            /* swipeEdge = */ BackEvent.EDGE_LEFT,
            /* departingAnimationTarget = */ null);
    private final MotionEvent mMotionEvent =
            MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 100, 100, 0);

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        doReturn(true).when(mApplicationInfo).isOnBackInvokedCallbackEnabled();
        doReturn(mApplicationInfo).when(mContext).getApplicationInfo();

        mDispatcher = new WindowOnBackInvokedDispatcher(mContext, Looper.getMainLooper());
        mDispatcher.attachToWindow(mWindowSession, mWindow, null, mImeBackAnimationController);
    }

    private void waitForIdle() {
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    }

    private List<OnBackInvokedCallbackInfo> captureCallbackInfo() throws RemoteException {
        ArgumentCaptor<OnBackInvokedCallbackInfo> captor = ArgumentCaptor
                .forClass(OnBackInvokedCallbackInfo.class);
        // atLeast(0) -> get all setOnBackInvokedCallbackInfo() invocations
        verify(mWindowSession, atLeast(0))
                .setOnBackInvokedCallbackInfo(Mockito.eq(mWindow), captor.capture());
        verifyNoMoreInteractions(mWindowSession);
        return captor.getAllValues();
    }

    private OnBackInvokedCallbackInfo assertSetCallbackInfo() throws RemoteException {
        List<OnBackInvokedCallbackInfo> callbackInfos = captureCallbackInfo();
        int actual = callbackInfos.size();
        assertEquals("setOnBackInvokedCallbackInfo", ++mCallbackInfoCalls, actual);
        return callbackInfos.get(mCallbackInfoCalls - 1);
    }

    private void assertNoSetCallbackInfo() throws RemoteException {
        List<OnBackInvokedCallbackInfo> callbackInfos = captureCallbackInfo();
        int actual = callbackInfos.size();
        assertEquals("No setOnBackInvokedCallbackInfo", mCallbackInfoCalls, actual);
    }

    private void assertCallbacksSize(int expectedDefault, int expectedOverlay) {
        ArrayList<OnBackInvokedCallback> callbacksDefault = mDispatcher
                .mOnBackInvokedCallbacks.get(PRIORITY_DEFAULT);
        int actualSizeDefault = callbacksDefault != null ? callbacksDefault.size() : 0;
        assertEquals("mOnBackInvokedCallbacks DEFAULT size", expectedDefault, actualSizeDefault);

        ArrayList<OnBackInvokedCallback> callbacksOverlay = mDispatcher
                .mOnBackInvokedCallbacks.get(PRIORITY_OVERLAY);
        int actualSizeOverlay = callbacksOverlay != null ? callbacksOverlay.size() : 0;
        assertEquals("mOnBackInvokedCallbacks OVERLAY size", expectedOverlay, actualSizeOverlay);
    }

    private void assertTopCallback(OnBackInvokedCallback expectedCallback) {
        assertEquals("topCallback", expectedCallback, mDispatcher.getTopCallback());
    }

    @Test
    public void registerCallback_samePriority_sameCallback() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        assertCallbacksSize(/* default */ 1, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mCallback1);

        // The callback is removed and added again
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        assertCallbacksSize(/* default */ 1, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mCallback1);

        waitForIdle();
        verifyNoMoreInteractions(mWindowSession);
        verifyNoMoreInteractions(mCallback1);
    }

    @Test
    public void registerCallback_samePriority_differentCallback() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        assertCallbacksSize(/* default */ 1, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mCallback1);

        // The new callback becomes the TopCallback
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback2);
        assertCallbacksSize(/* default */ 2, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mCallback2);

        waitForIdle();
        verifyNoMoreInteractions(mWindowSession);
        verifyNoMoreInteractions(mCallback1);
        verifyNoMoreInteractions(mCallback2);
    }

    @Test
    public void registerCallback_differentPriority_sameCallback() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, mCallback1);
        assertCallbacksSize(/* default */ 0, /* overlay */ 1);
        assertSetCallbackInfo();
        assertTopCallback(mCallback1);

        // The callback is moved to the new priority list
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        assertCallbacksSize(/* default */ 1, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mCallback1);

        waitForIdle();
        verifyNoMoreInteractions(mWindowSession);
        verifyNoMoreInteractions(mCallback1);
    }

    @Test
    public void registerCallback_differentPriority_differentCallback() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, mCallback1);
        assertSetCallbackInfo();
        assertCallbacksSize(/* default */ 0, /* overlay */ 1);
        assertTopCallback(mCallback1);

        // The callback with higher priority is still the TopCallback
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback2);
        assertNoSetCallbackInfo();
        assertCallbacksSize(/* default */ 1, /* overlay */ 1);
        assertTopCallback(mCallback1);

        waitForIdle();
        verifyNoMoreInteractions(mWindowSession);
        verifyNoMoreInteractions(mCallback1);
        verifyNoMoreInteractions(mCallback2);
    }

    @Test
    public void registerCallback_sameInstanceAddedTwice() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, mCallback1);
        assertCallbacksSize(/* default */ 0, /* overlay */ 1);
        assertSetCallbackInfo();
        assertTopCallback(mCallback1);

        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback2);
        assertCallbacksSize(/* default */ 1, /* overlay */ 1);
        assertNoSetCallbackInfo();
        assertTopCallback(mCallback1);

        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        assertCallbacksSize(/* default */ 2, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mCallback1);

        mDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, mCallback2);
        assertCallbacksSize(/* default */ 1, /* overlay */ 1);
        assertSetCallbackInfo();
        assertTopCallback(mCallback2);

        waitForIdle();
        verifyNoMoreInteractions(mWindowSession);
        verifyNoMoreInteractions(mCallback1);
        verifyNoMoreInteractions(mCallback2);
    }

    @Test
    public void propagatesTopCallback_samePriority() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        OnBackInvokedCallbackInfo callbackInfo1 = assertSetCallbackInfo();

        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback2);
        OnBackInvokedCallbackInfo callbackInfo2 = assertSetCallbackInfo();

        callbackInfo1.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback1, times(1)).onBackStarted(any(BackEvent.class));
        verify(mCallback2, never()).onBackStarted(any(BackEvent.class));
        clearInvocations(mCallback1);

        callbackInfo2.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback1, never()).onBackStarted(any(BackEvent.class));
        verify(mCallback2, times(1)).onBackStarted(any(BackEvent.class));
    }

    @Test
    public void propagatesTopCallback_differentPriority() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, mCallback1);
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback2);

        OnBackInvokedCallbackInfo callbackInfo = assertSetCallbackInfo();

        verifyNoMoreInteractions(mWindowSession);
        assertEquals(callbackInfo.getPriority(), PRIORITY_OVERLAY);

        callbackInfo.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback1).onBackStarted(any(BackEvent.class));
    }

    @Test
    public void propagatesTopCallback_withRemoval() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        assertSetCallbackInfo();

        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback2);
        assertSetCallbackInfo();

        mDispatcher.unregisterOnBackInvokedCallback(mCallback1);

        waitForIdle();
        verifyNoMoreInteractions(mWindowSession);
        verifyNoMoreInteractions(mCallback1);

        mDispatcher.unregisterOnBackInvokedCallback(mCallback2);

        waitForIdle();
        verify(mWindowSession).setOnBackInvokedCallbackInfo(Mockito.eq(mWindow), isNull());
    }


    @Test
    public void propagatesTopCallback_sameInstanceAddedTwice() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, mCallback1);
        assertSetCallbackInfo();
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback2);
        assertNoSetCallbackInfo();
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        assertSetCallbackInfo();

        mDispatcher.registerOnBackInvokedCallback(PRIORITY_OVERLAY, mCallback2);

        OnBackInvokedCallbackInfo lastCallbackInfo = assertSetCallbackInfo();

        lastCallbackInfo.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback2).onBackStarted(any(BackEvent.class));
    }

    @Test
    public void onUnregisterWhileBackInProgress_callOnBackCancelled() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);

        OnBackInvokedCallbackInfo callbackInfo = assertSetCallbackInfo();

        callbackInfo.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback1).onBackStarted(any(BackEvent.class));

        mDispatcher.unregisterOnBackInvokedCallback(mCallback1);

        waitForIdle();
        verify(mCallback1).onBackCancelled();
        verify(mWindowSession).setOnBackInvokedCallbackInfo(Mockito.eq(mWindow), isNull());
    }

    @Test
    public void onBackInvoked_calledAfterOnBackStarted() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        OnBackInvokedCallbackInfo callbackInfo = assertSetCallbackInfo();

        callbackInfo.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback1).onBackStarted(any(BackEvent.class));

        callbackInfo.getCallback().onBackInvoked();

        waitForIdle();
        verify(mCallback1, timeout(/*millis*/ 1000)).onBackInvoked();
        verify(mCallback1, never()).onBackCancelled();
    }

    @Test
    public void onBackCancelled_calledBeforeOnBackStartedOfNewGesture() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        OnBackInvokedCallbackInfo callbackInfo = assertSetCallbackInfo();

        callbackInfo.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback1).onBackStarted(any(BackEvent.class));
        clearInvocations(mCallback1);

        callbackInfo.getCallback().onBackCancelled();
        waitForIdle();

        // simulate start of new gesture while cancel animation is still running
        callbackInfo.getCallback().onBackStarted(mBackEvent);
        waitForIdle();

        // verify that onBackCancelled is called before onBackStarted
        InOrder orderVerifier = Mockito.inOrder(mCallback1);
        orderVerifier.verify(mCallback1).onBackCancelled();
        orderVerifier.verify(mCallback1).onBackStarted(any(BackEvent.class));
    }

    @Test
    public void onDetachFromWindow_cancelCallbackAndIgnoreOnBackInvoked() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);

        OnBackInvokedCallbackInfo callbackInfo = assertSetCallbackInfo();

        callbackInfo.getCallback().onBackStarted(mBackEvent);

        waitForIdle();
        verify(mCallback1).onBackStarted(any(BackEvent.class));

        // This should trigger mCallback1.onBackCancelled()
        mDispatcher.detachFromWindow();
        // This should be ignored by mCallback1
        callbackInfo.getCallback().onBackInvoked();

        waitForIdle();
        verify(mCallback1, never()).onBackInvoked();
        verify(mCallback1).onBackCancelled();
    }

    @Test
    public void updatesDispatchingState() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        OnBackInvokedCallbackInfo callbackInfo = assertSetCallbackInfo();

        callbackInfo.getCallback().onBackStarted(mBackEvent);
        waitForIdle();
        assertTrue(mDispatcher.isBackGestureInProgress());

        callbackInfo.getCallback().onBackInvoked();
        waitForIdle();
        assertFalse(mDispatcher.isBackGestureInProgress());
    }

    @Test
    public void handlesMotionEvent() throws RemoteException {
        mDispatcher.registerOnBackInvokedCallback(PRIORITY_DEFAULT, mCallback1);
        OnBackInvokedCallbackInfo callbackInfo = assertSetCallbackInfo();

        // Send motion event in View's main thread.
        final Handler main = Handler.getMain();
        main.runWithScissors(() -> mDispatcher.onMotionEvent(mMotionEvent), 100);
        assertFalse(mDispatcher.mTouchTracker.isActive());

        callbackInfo.getCallback().onBackStarted(mBackEvent);
        waitForIdle();
        assertTrue(mDispatcher.isBackGestureInProgress());
        assertTrue(mDispatcher.mTouchTracker.isActive());

        main.runWithScissors(() -> mDispatcher.onMotionEvent(mMotionEvent), 100);
        waitForIdle();
        // onBackPressed is called from animator, so it can happen more than once.
        verify(mCallback1, atLeast(1)).onBackProgressed(any());
    }

    @Test
    public void registerImeCallbacks_onBackInvokedCallbackEnabled() throws RemoteException {
        verifyImeCallackRegistrations();
    }

    @Test
    public void registerImeCallbacks_onBackInvokedCallbackDisabled() throws RemoteException {
        doReturn(false).when(mApplicationInfo).isOnBackInvokedCallbackEnabled();
        verifyImeCallackRegistrations();
    }

    private void verifyImeCallackRegistrations() throws RemoteException {
        // verify default callback is replaced with ImeBackAnimationController
        mDispatcher.registerOnBackInvokedCallbackUnchecked(mDefaultImeCallback, PRIORITY_DEFAULT);
        assertCallbacksSize(/* default */ 1, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mImeBackAnimationController);

        // verify regular ime callback is successfully registered
        mDispatcher.registerOnBackInvokedCallbackUnchecked(mImeCallback, PRIORITY_DEFAULT);
        assertCallbacksSize(/* default */ 2, /* overlay */ 0);
        assertSetCallbackInfo();
        assertTopCallback(mImeCallback);
    }
}
