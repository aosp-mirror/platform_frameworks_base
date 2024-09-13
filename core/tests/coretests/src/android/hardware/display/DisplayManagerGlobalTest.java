/*
 * Copyright 2021 The Android Open Source Project
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

package android.hardware.display;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.DisplayInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/**
 * Tests for {@link DisplayManagerGlobal}.
 *
 * Build/Install/Run:
 *  atest FrameworksCoreTests:DisplayManagerGlobalTest
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class DisplayManagerGlobalTest {

    private static final long ALL_DISPLAY_EVENTS = DisplayManager.EVENT_FLAG_DISPLAY_ADDED
            | DisplayManager.EVENT_FLAG_DISPLAY_CHANGED
            | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED;

    @Mock
    private IDisplayManager mDisplayManager;

    @Mock
    private DisplayManager.DisplayListener mListener;

    @Mock
    private DisplayManager.DisplayListener mListener2;

    @Captor
    private ArgumentCaptor<IDisplayManagerCallback> mCallbackCaptor;

    private Context mContext;
    private DisplayManagerGlobal mDisplayManagerGlobal;
    private Handler mHandler;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        Mockito.when(mDisplayManager.getPreferredWideGamutColorSpaceId()).thenReturn(0);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mHandler = mContext.getMainThreadHandler();
        mDisplayManagerGlobal = new DisplayManagerGlobal(mDisplayManager);
    }

    @Test
    public void testDisplayListenerIsCalled_WhenDisplayEventOccurs() throws RemoteException {
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler, ALL_DISPLAY_EVENTS,
                null);
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), anyLong());
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        int displayId = 1;
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
        waitForHandler();
        Mockito.verify(mListener).onDisplayAdded(eq(displayId));
        Mockito.verifyNoMoreInteractions(mListener);

        Mockito.reset(mListener);
        // Mock IDisplayManager to return a different display info to trigger display change.
        final DisplayInfo newDisplayInfo = new DisplayInfo();
        newDisplayInfo.rotation++;
        doReturn(newDisplayInfo).when(mDisplayManager).getDisplayInfo(displayId);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        waitForHandler();
        Mockito.verify(mListener).onDisplayChanged(eq(displayId));
        Mockito.verifyNoMoreInteractions(mListener);

        Mockito.reset(mListener);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
        waitForHandler();
        Mockito.verify(mListener).onDisplayRemoved(eq(displayId));
        Mockito.verifyNoMoreInteractions(mListener);
    }

    @Test
    public void testDisplayListenerIsNotCalled_WhenClientIsNotSubscribed() throws RemoteException {
        // First we subscribe to all events in order to test that the subsequent calls to
        // registerDisplayListener will update the event mask.
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler, ALL_DISPLAY_EVENTS,
                null);
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), anyLong());
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        int displayId = 1;
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                ALL_DISPLAY_EVENTS & ~DisplayManager.EVENT_FLAG_DISPLAY_ADDED, null);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mListener);

        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                ALL_DISPLAY_EVENTS & ~DisplayManager.EVENT_FLAG_DISPLAY_CHANGED, null);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mListener);

        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                ALL_DISPLAY_EVENTS & ~DisplayManager.EVENT_FLAG_DISPLAY_REMOVED, null);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mListener);
    }

    @Test
    public void testDisplayManagerGlobalRegistersWithDisplayManager_WhenThereAreNoOtherListeners()
            throws RemoteException {
        mDisplayManagerGlobal.registerNativeChoreographerForRefreshRateCallbacks();
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), eq(ALL_DISPLAY_EVENTS));

        mDisplayManagerGlobal.unregisterNativeChoreographerForRefreshRateCallbacks();
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), eq(0L));

    }

    @Test
    public void testDisplayManagerGlobalRegistersWithDisplayManager_WhenThereAreListeners()
            throws RemoteException {
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS, null);
        InOrder inOrder = Mockito.inOrder(mDisplayManager);

        inOrder.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(),
                        eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));

        mDisplayManagerGlobal.registerNativeChoreographerForRefreshRateCallbacks();
        inOrder.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(),
                        eq(ALL_DISPLAY_EVENTS | DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));

        mDisplayManagerGlobal.unregisterNativeChoreographerForRefreshRateCallbacks();
        inOrder.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(),
                        eq(DisplayManager.EVENT_FLAG_DISPLAY_BRIGHTNESS));

        mDisplayManagerGlobal.unregisterDisplayListener(mListener);
        inOrder.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), eq(0L));
    }

    @Test
    public void testHandleDisplayChangeFromWindowManager() throws RemoteException {
        // Mock IDisplayManager to return a display info to trigger display change.
        final DisplayInfo newDisplayInfo = new DisplayInfo();
        doReturn(newDisplayInfo).when(mDisplayManager).getDisplayInfo(123);
        doReturn(newDisplayInfo).when(mDisplayManager).getDisplayInfo(321);

        // Nothing happens when there is no listener.
        mDisplayManagerGlobal.handleDisplayChangeFromWindowManager(123);

        // One listener listens on add/remove, and the other one listens on change.
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                DisplayManager.EVENT_FLAG_DISPLAY_ADDED
                        | DisplayManager.EVENT_FLAG_DISPLAY_REMOVED, null /* packageName */);
        mDisplayManagerGlobal.registerDisplayListener(mListener2, mHandler,
                DisplayManager.EVENT_FLAG_DISPLAY_CHANGED, null /* packageName */);

        mDisplayManagerGlobal.handleDisplayChangeFromWindowManager(321);
        waitForHandler();

        verify(mListener, never()).onDisplayChanged(anyInt());
        verify(mListener2).onDisplayChanged(321);

        // Trigger the callback again even if the display info is not changed.
        clearInvocations(mListener2);
        mDisplayManagerGlobal.handleDisplayChangeFromWindowManager(321);
        waitForHandler();

        verify(mListener2).onDisplayChanged(321);

        // No callback for non-existing display (no display info returned from IDisplayManager).
        clearInvocations(mListener2);
        mDisplayManagerGlobal.handleDisplayChangeFromWindowManager(456);
        waitForHandler();

        verify(mListener2, never()).onDisplayChanged(anyInt());
    }

    private void waitForHandler() {
        mHandler.runWithScissors(() -> {
        }, 0);
    }
}
