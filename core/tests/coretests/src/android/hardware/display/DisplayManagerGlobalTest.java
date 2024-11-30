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

import static android.hardware.display.DisplayManagerGlobal.EVENT_DISPLAY_STATE_CHANGED;
import static android.hardware.display.DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE;
import static android.hardware.display.DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_STATE;

import static org.junit.Assert.assertEquals;
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
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.view.DisplayInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.server.display.feature.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.Executor;
import java.util.function.Consumer;

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

    @Rule
    public final CheckFlagsRule mCheckFlagsRule =
            DeviceFlagsValueProvider.createCheckFlagsRule();

    private static final long ALL_DISPLAY_EVENTS =
            DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_ADDED
            | DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CHANGED
            | DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_REMOVED;

    @Mock
    private IDisplayManager mDisplayManager;

    @Mock
    private DisplayManager.DisplayListener mDisplayListener;

    @Mock
    private DisplayManager.DisplayListener mDisplayListener2;

    @Mock
    private Consumer<DisplayTopology> mTopologyListener;

    @Captor
    private ArgumentCaptor<IDisplayManagerCallback> mCallbackCaptor;

    private Context mContext;
    private DisplayManagerGlobal mDisplayManagerGlobal;
    private Handler mHandler;
    private Executor mExecutor;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        Mockito.when(mDisplayManager.getPreferredWideGamutColorSpaceId()).thenReturn(0);
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mHandler = mContext.getMainThreadHandler();
        mExecutor = mContext.getMainExecutor();
        mDisplayManagerGlobal = new DisplayManagerGlobal(mDisplayManager);
    }

    @Test
    public void testDisplayListenerIsCalled_WhenDisplayEventOccurs() throws RemoteException {
        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                ALL_DISPLAY_EVENTS, /* packageName= */ null);
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), anyLong());
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        int displayId = 1;
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
        waitForHandler();
        Mockito.verify(mDisplayListener).onDisplayAdded(eq(displayId));
        Mockito.verifyNoMoreInteractions(mDisplayListener);

        Mockito.reset(mDisplayListener);
        // Mock IDisplayManager to return a different display info to trigger display change.
        final DisplayInfo newDisplayInfo = new DisplayInfo();
        newDisplayInfo.rotation++;
        doReturn(newDisplayInfo).when(mDisplayManager).getDisplayInfo(displayId);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        waitForHandler();
        Mockito.verify(mDisplayListener).onDisplayChanged(eq(displayId));
        Mockito.verifyNoMoreInteractions(mDisplayListener);

        Mockito.reset(mDisplayListener);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
        waitForHandler();
        Mockito.verify(mDisplayListener).onDisplayRemoved(eq(displayId));
        Mockito.verifyNoMoreInteractions(mDisplayListener);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DISPLAY_LISTENER_PERFORMANCE_IMPROVEMENTS)
    public void testDisplayListenerIsCalled_WhenDisplayPropertyChangeEventOccurs()
            throws RemoteException {
        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE
                        | INTERNAL_EVENT_FLAG_DISPLAY_STATE,
                null);
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), anyLong());
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        int displayId = 1;

        Mockito.reset(mDisplayListener);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REFRESH_RATE_CHANGED);
        waitForHandler();
        Mockito.verify(mDisplayListener).onDisplayChanged(eq(displayId));
        Mockito.verifyNoMoreInteractions(mDisplayListener);

        Mockito.reset(mDisplayListener);
        callback.onDisplayEvent(displayId, EVENT_DISPLAY_STATE_CHANGED);
        waitForHandler();
        Mockito.verify(mDisplayListener).onDisplayChanged(eq(displayId));
        Mockito.verifyNoMoreInteractions(mDisplayListener);
    }

    @Test
    public void testDisplayListenerIsNotCalled_WhenClientIsNotSubscribed() throws RemoteException {
        // First we subscribe to all events in order to test that the subsequent calls to
        // registerDisplayListener will update the event mask.
        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                ALL_DISPLAY_EVENTS, /* packageName= */ null);
        Mockito.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(), anyLong());
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        int displayId = 1;
        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                ALL_DISPLAY_EVENTS
                        & ~DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_ADDED, null);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_ADDED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mDisplayListener);

        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                ALL_DISPLAY_EVENTS
                        & ~DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CHANGED, null);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_CHANGED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mDisplayListener);

        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                ALL_DISPLAY_EVENTS
                        & ~DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_REMOVED, null);
        callback.onDisplayEvent(displayId, DisplayManagerGlobal.EVENT_DISPLAY_REMOVED);
        waitForHandler();
        Mockito.verifyZeroInteractions(mDisplayListener);
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
        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED,
                null);
        InOrder inOrder = Mockito.inOrder(mDisplayManager);

        inOrder.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(),
                        eq(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED));

        mDisplayManagerGlobal.registerNativeChoreographerForRefreshRateCallbacks();
        inOrder.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(),
                        eq(ALL_DISPLAY_EVENTS
                                | DisplayManagerGlobal
                                .INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED));

        mDisplayManagerGlobal.unregisterNativeChoreographerForRefreshRateCallbacks();
        inOrder.verify(mDisplayManager)
                .registerCallbackWithEventMask(mCallbackCaptor.capture(),
                        eq(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED));

        mDisplayManagerGlobal.unregisterDisplayListener(mDisplayListener);
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
        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener, mHandler,
                DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_ADDED
                        | DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_REMOVED,
                null /* packageName */);
        mDisplayManagerGlobal.registerDisplayListener(mDisplayListener2, mHandler,
                DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CHANGED,
                null /* packageName */);

        mDisplayManagerGlobal.handleDisplayChangeFromWindowManager(321);
        waitForHandler();

        verify(mDisplayListener, never()).onDisplayChanged(anyInt());
        verify(mDisplayListener2).onDisplayChanged(321);

        // Trigger the callback again even if the display info is not changed.
        clearInvocations(mDisplayListener2);
        mDisplayManagerGlobal.handleDisplayChangeFromWindowManager(321);
        waitForHandler();

        verify(mDisplayListener2).onDisplayChanged(321);

        // No callback for non-existing display (no display info returned from IDisplayManager).
        clearInvocations(mDisplayListener2);
        mDisplayManagerGlobal.handleDisplayChangeFromWindowManager(456);
        waitForHandler();

        verify(mDisplayListener2, never()).onDisplayChanged(anyInt());
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DISPLAY_TOPOLOGY)
    public void testTopologyListenerIsCalled_WhenTopologyUpdateOccurs() throws RemoteException {
        mDisplayManagerGlobal.registerTopologyListener(mExecutor, mTopologyListener,
                /* packageName= */ null);
        Mockito.verify(mDisplayManager).registerCallbackWithEventMask(mCallbackCaptor.capture(),
                eq(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_TOPOLOGY_UPDATED));
        IDisplayManagerCallback callback = mCallbackCaptor.getValue();

        DisplayTopology topology = new DisplayTopology();
        callback.onTopologyChanged(topology);
        waitForHandler();
        Mockito.verify(mTopologyListener).accept(topology);
        Mockito.verifyNoMoreInteractions(mTopologyListener);
    }

    @Test
    @RequiresFlagsEnabled(Flags.FLAG_DISPLAY_LISTENER_PERFORMANCE_IMPROVEMENTS)
    public void testMapFlagsToInternalEventFlag() {
        // Test public flags mapping
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_ADDED,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(DisplayManager.EVENT_FLAG_DISPLAY_ADDED, 0));
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CHANGED,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(DisplayManager.EVENT_FLAG_DISPLAY_CHANGED, 0));
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_REMOVED,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(DisplayManager.EVENT_FLAG_DISPLAY_REMOVED, 0));
        assertEquals(INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(
                                DisplayManager.EVENT_FLAG_DISPLAY_REFRESH_RATE,
                                0));
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_STATE,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(
                                DisplayManager.EVENT_FLAG_DISPLAY_STATE,
                                0));

        // test private flags mapping
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(0,
                                DisplayManager.PRIVATE_EVENT_FLAG_DISPLAY_CONNECTION_CHANGED));
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_HDR_SDR_RATIO_CHANGED,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(0,
                                DisplayManager.PRIVATE_EVENT_FLAG_HDR_SDR_RATIO_CHANGED));
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(0,
                                DisplayManager.PRIVATE_EVENT_FLAG_DISPLAY_BRIGHTNESS));

        // Test both public and private flags mapping
        assertEquals(DisplayManagerGlobal.INTERNAL_EVENT_FLAG_DISPLAY_BRIGHTNESS_CHANGED
                        | INTERNAL_EVENT_FLAG_DISPLAY_REFRESH_RATE,
                mDisplayManagerGlobal
                        .mapFlagsToInternalEventFlag(
                                DisplayManager.EVENT_FLAG_DISPLAY_REFRESH_RATE,
                                DisplayManager.PRIVATE_EVENT_FLAG_DISPLAY_BRIGHTNESS));
    }

    private void waitForHandler() {
        mHandler.runWithScissors(() -> {
        }, 0);
    }
}
