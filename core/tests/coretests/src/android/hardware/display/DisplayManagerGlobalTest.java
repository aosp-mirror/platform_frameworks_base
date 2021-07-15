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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import android.content.Context;
import android.os.Handler;
import android.os.RemoteException;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

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
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler, ALL_DISPLAY_EVENTS);
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), anyLong());
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        int displayId = 1;
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
        waitForHandler();
        Mockito.verify(mListener).onDisplayAdded(eq(displayId));
        Mockito.verifyNoMoreInteractions(mListener);

        Mockito.reset(mListener);
        callback.onDisplayEvent(1, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
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
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler, ALL_DISPLAY_EVENTS);
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), anyLong());
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        int displayId = 1;
        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                ALL_DISPLAY_EVENTS & ~DisplayManager.EVENT_FLAG_DISPLAY_ADDED);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mListener);

        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                ALL_DISPLAY_EVENTS & ~DisplayManager.EVENT_FLAG_DISPLAY_CHANGED);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mListener);

        mDisplayManagerGlobal.registerDisplayListener(mListener, mHandler,
                ALL_DISPLAY_EVENTS & ~DisplayManager.EVENT_FLAG_DISPLAY_REMOVED);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mListener);
    }

    private void waitForHandler() {
        mHandler.runWithScissors(() -> { }, 0);
    }
}
