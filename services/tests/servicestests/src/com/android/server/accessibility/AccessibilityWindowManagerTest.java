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

package com.android.server.accessibility;

import static com.android.server.accessibility.AccessibilityWindowManagerTest.WindowChangesMatcher.a11yWindowChanges;
import static com.android.server.accessibility.AccessibilityWindowManagerTest.WindowIdMatcher.a11yWindowId;

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertNotNull;
import static junit.framework.Assert.assertNull;
import static junit.framework.Assert.assertTrue;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Region;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.SparseArray;
import android.view.IWindow;
import android.view.WindowInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;

import com.android.server.accessibility.AccessibilityWindowManager.RemoteAccessibilityConnection;
import com.android.server.wm.WindowManagerInternal;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

/**
 * Tests for the AccessibilityWindowManager
 */
public class AccessibilityWindowManagerTest {
    private static final String PACKAGE_NAME = "com.android.server.accessibility";
    private static final boolean FORCE_SEND = true;
    private static final boolean SEND_ON_WINDOW_CHANGES = false;
    private static final int USER_SYSTEM_ID = UserHandle.USER_SYSTEM;
    private static final int NUM_GLOBAL_WINDOWS = 4;
    private static final int NUM_APP_WINDOWS = 4;
    private static final int NUM_OF_WINDOWS = NUM_GLOBAL_WINDOWS + NUM_APP_WINDOWS;
    private static final int DEFAULT_FOCUSED_INDEX = 1;
    private static final int SCREEN_WIDTH = 1080;
    private static final int SCREEN_HEIGHT = 1920;

    private AccessibilityWindowManager mA11yWindowManager;

    // List of window token, mapping from windowId -> window token.
    private final SparseArray<IWindow> mA11yWindowTokens = new SparseArray<>();
    private final ArrayList<WindowInfo> mWindowInfos = new ArrayList<>();
    private final MessageCapturingHandler mHandler = new MessageCapturingHandler(null);

    @Mock private WindowManagerInternal mMockWindowManagerInternal;
    @Mock private AccessibilityWindowManager.AccessibilityEventSender mMockA11yEventSender;
    @Mock private AccessibilitySecurityPolicy mMockA11ySecurityPolicy;
    @Mock private AccessibilitySecurityPolicy.AccessibilityUserManager mMockA11yUserManager;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mMockA11yUserManager.getCurrentUserIdLocked()).thenReturn(USER_SYSTEM_ID);
        when(mMockA11ySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                USER_SYSTEM_ID)).thenReturn(USER_SYSTEM_ID);
        when(mMockA11ySecurityPolicy.resolveValidReportedPackageLocked(
                anyString(), anyInt(), anyInt())).thenReturn(PACKAGE_NAME);

        mA11yWindowManager = new AccessibilityWindowManager(new Object(), mHandler,
                mMockWindowManagerInternal,
                mMockA11yEventSender,
                mMockA11ySecurityPolicy,
                mMockA11yUserManager);

        // Add RemoteAccessibilityConnection into AccessibilityWindowManager, and copy
        // mock window token into mA11yWindowTokens. Also, preparing WindowInfo mWindowInfos
        // for the test.
        int layer = 0;
        for (int i = 0; i < NUM_GLOBAL_WINDOWS; i++) {
            final IWindow token = addAccessibilityInteractionConnection(true);
            addWindowInfo(token, layer++);

        }
        for (int i = 0; i < NUM_APP_WINDOWS; i++) {
            final IWindow token = addAccessibilityInteractionConnection(false);
            addWindowInfo(token, layer++);
        }
        // setup default focus
        mWindowInfos.get(DEFAULT_FOCUSED_INDEX).focused = true;
        // Turn on windows tracking, and update window info
        mA11yWindowManager.startTrackingWindows();
        mA11yWindowManager.onWindowsForAccessibilityChanged(FORCE_SEND, mWindowInfos);
        assertEquals(mA11yWindowManager.getWindowListLocked().size(),
                mWindowInfos.size());

        // AccessibilityEventSender is invoked during onWindowsForAccessibilityChanged.
        // Reset it for mockito verify of further test case.
        Mockito.reset(mMockA11yEventSender);
    }

    @After
    public void tearDown() {
        mHandler.removeAllMessages();
    }

    @Test
    public void startTrackingWindows_shouldEnableWindowManagerCallback() {
        // AccessibilityWindowManager#startTrackingWindows already invoked in setup
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked());
        verify(mMockWindowManagerInternal).setWindowsForAccessibilityCallback(any());
    }

    @Test
    public void stopTrackingWindows_shouldDisableWindowManagerCallback() {
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked());
        Mockito.reset(mMockWindowManagerInternal);

        mA11yWindowManager.stopTrackingWindows();
        assertFalse(mA11yWindowManager.isTrackingWindowsLocked());
        verify(mMockWindowManagerInternal).setWindowsForAccessibilityCallback(any());
    }

    @Test
    public void stopTrackingWindows_shouldClearWindows() {
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked());
        final int activeWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);

        mA11yWindowManager.stopTrackingWindows();
        assertNull(mA11yWindowManager.getWindowListLocked());
        assertEquals(mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT),
                AccessibilityWindowInfo.UNDEFINED_WINDOW_ID);
        assertEquals(mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID),
                activeWindowId);
    }

    @Test
    public void onWindowsChanged_duringTouchInteractAndFocusChange_shouldChangeActiveWindow() {
        final int activeWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);
        WindowInfo focusedWindowInfo = mWindowInfos.get(DEFAULT_FOCUSED_INDEX);
        assertEquals(activeWindowId, mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, focusedWindowInfo.token));

        focusedWindowInfo.focused = false;
        focusedWindowInfo = mWindowInfos.get(DEFAULT_FOCUSED_INDEX + 1);
        focusedWindowInfo.focused = true;

        mA11yWindowManager.onTouchInteractionStart();
        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);
        assertNotEquals(activeWindowId, mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID));
    }

    @Test
    public void onWindowsChanged_shouldReportCorrectLayer() {
        // AccessibilityWindowManager#onWindowsForAccessibilityChanged already invoked in setup
        List<AccessibilityWindowInfo> a11yWindows = mA11yWindowManager.getWindowListLocked();
        for (int i = 0; i < a11yWindows.size(); i++) {
            final AccessibilityWindowInfo a11yWindow = a11yWindows.get(i);
            final WindowInfo windowInfo = mWindowInfos.get(i);
            assertThat(mWindowInfos.size() - windowInfo.layer - 1,
                    is(a11yWindow.getLayer()));
        }
    }

    @Test
    public void onWindowsChanged_shouldReportCorrectOrder() {
        // AccessibilityWindowManager#onWindowsForAccessibilityChanged already invoked in setup
        List<AccessibilityWindowInfo> a11yWindows = mA11yWindowManager.getWindowListLocked();
        for (int i = 0; i < a11yWindows.size(); i++) {
            final AccessibilityWindowInfo a11yWindow = a11yWindows.get(i);
            final IBinder windowToken = mA11yWindowManager
                    .getWindowTokenForUserAndWindowIdLocked(USER_SYSTEM_ID, a11yWindow.getId());
            final WindowInfo windowInfo = mWindowInfos.get(i);
            assertThat(windowToken, is(windowInfo.token));
        }
    }

    @Test
    public void onWindowsChangedAndForceSend_shouldUpdateWindows() {
        final WindowInfo windowInfo = mWindowInfos.get(0);
        final int correctLayer = mA11yWindowManager.getWindowListLocked().get(0).getLayer();
        windowInfo.layer += 1;

        mA11yWindowManager.onWindowsForAccessibilityChanged(FORCE_SEND, mWindowInfos);
        assertNotEquals(correctLayer, mA11yWindowManager.getWindowListLocked().get(0).getLayer());
    }

    @Test
    public void onWindowsChangedNoForceSend_layerChanged_shouldNotUpdateWindows() {
        final WindowInfo windowInfo = mWindowInfos.get(0);
        final int correctLayer = mA11yWindowManager.getWindowListLocked().get(0).getLayer();
        windowInfo.layer += 1;

        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);
        assertEquals(correctLayer, mA11yWindowManager.getWindowListLocked().get(0).getLayer());
    }

    @Test
    public void onWindowsChangedNoForceSend_windowChanged_shouldUpdateWindows()
            throws RemoteException {
        final AccessibilityWindowInfo oldWindow = mA11yWindowManager.getWindowListLocked().get(0);
        final IWindow token = addAccessibilityInteractionConnection(true);
        final WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.type = AccessibilityWindowInfo.TYPE_APPLICATION;
        windowInfo.token = token.asBinder();
        windowInfo.layer = 0;
        windowInfo.boundsInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        mWindowInfos.set(0, windowInfo);

        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);
        assertNotEquals(oldWindow, mA11yWindowManager.getWindowListLocked().get(0));
    }

    @Test
    public void onWindowsChangedNoForceSend_focusChanged_shouldUpdateWindows() {
        final WindowInfo focusedWindowInfo = mWindowInfos.get(DEFAULT_FOCUSED_INDEX);
        final WindowInfo windowInfo = mWindowInfos.get(0);
        focusedWindowInfo.focused = false;
        windowInfo.focused = true;
        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);
        assertTrue(mA11yWindowManager.getWindowListLocked().get(0).isFocused());
    }

    @Test
    public void removeAccessibilityInteractionConnection_byWindowToken_shouldRemoved() {
        for (int i = 0; i < NUM_OF_WINDOWS; i++) {
            final int windowId = mA11yWindowTokens.keyAt(i);
            final IWindow windowToken = mA11yWindowTokens.valueAt(i);
            assertNotNull(mA11yWindowManager.getConnectionLocked(USER_SYSTEM_ID, windowId));

            mA11yWindowManager.removeAccessibilityInteractionConnection(windowToken);
            assertNull(mA11yWindowManager.getConnectionLocked(USER_SYSTEM_ID, windowId));
        }
    }

    @Test
    public void remoteAccessibilityConnection_binderDied_shouldRemoveConnection() {
        for (int i = 0; i < NUM_OF_WINDOWS; i++) {
            final int windowId = mA11yWindowTokens.keyAt(i);
            final RemoteAccessibilityConnection remoteA11yConnection =
                    mA11yWindowManager.getConnectionLocked(USER_SYSTEM_ID, windowId);
            assertNotNull(remoteA11yConnection);

            remoteA11yConnection.binderDied();
            assertNull(mA11yWindowManager.getConnectionLocked(USER_SYSTEM_ID, windowId));
        }
    }

    @Test
    public void getWindowTokenForUserAndWindowId_shouldNotNull() {
        final List<AccessibilityWindowInfo> windows = mA11yWindowManager.getWindowListLocked();
        for (int i = 0; i < windows.size(); i++) {
            final int windowId = windows.get(i).getId();

            assertNotNull(mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(
                    USER_SYSTEM_ID, windowId));
        }
    }

    @Test
    public void findWindowId() {
        final List<AccessibilityWindowInfo> windows = mA11yWindowManager.getWindowListLocked();
        for (int i = 0; i < windows.size(); i++) {
            final int windowId = windows.get(i).getId();
            final IBinder windowToken = mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(
                    USER_SYSTEM_ID, windowId);

            assertEquals(mA11yWindowManager.findWindowIdLocked(
                    USER_SYSTEM_ID, windowToken), windowId);
        }
    }

    @Test
    public void computePartialInteractiveRegionForWindow_wholeWindowVisible_returnWholeRegion() {
        // Updates top 2 z-order WindowInfo are whole visible.
        WindowInfo windowInfo = mWindowInfos.get(0);
        windowInfo.boundsInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT / 2);
        windowInfo = mWindowInfos.get(1);
        windowInfo.boundsInScreen.set(0, SCREEN_HEIGHT / 2,
                SCREEN_WIDTH, SCREEN_HEIGHT);
        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);

        final List<AccessibilityWindowInfo> a11yWindows = mA11yWindowManager.getWindowListLocked();
        final Region outBounds = new Region();
        int windowId = a11yWindows.get(0).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                windowId, outBounds);
        assertThat(outBounds.getBounds().width(), is(SCREEN_WIDTH));
        assertThat(outBounds.getBounds().height(), is(SCREEN_HEIGHT / 2));

        windowId = a11yWindows.get(1).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                windowId, outBounds);
        assertThat(outBounds.getBounds().width(), is(SCREEN_WIDTH));
        assertThat(outBounds.getBounds().height(), is(SCREEN_HEIGHT / 2));
    }

    @Test
    public void computePartialInteractiveRegionForWindow_halfWindowVisible_returnHalfRegion() {
        // Updates z-order #1 WindowInfo is half visible
        WindowInfo windowInfo = mWindowInfos.get(0);
        windowInfo.boundsInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT / 2);
        windowInfo = mWindowInfos.get(1);
        windowInfo.boundsInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);

        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);
        final List<AccessibilityWindowInfo> a11yWindows = mA11yWindowManager.getWindowListLocked();
        final Region outBounds = new Region();
        int windowId = a11yWindows.get(1).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                windowId, outBounds);
        assertThat(outBounds.getBounds().width(), is(SCREEN_WIDTH));
        assertThat(outBounds.getBounds().height(), is(SCREEN_HEIGHT / 2));
    }

    @Test
    public void computePartialInteractiveRegionForWindow_windowNotVisible_returnEmptyRegion() {
        // Updates z-order #1 WindowInfo is not visible
        WindowInfo windowInfo = mWindowInfos.get(0);
        windowInfo.boundsInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        windowInfo = mWindowInfos.get(1);
        windowInfo.boundsInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);

        final List<AccessibilityWindowInfo> a11yWindows = mA11yWindowManager.getWindowListLocked();
        final Region outBounds = new Region();
        int windowId = a11yWindows.get(1).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(
                windowId, outBounds);
        assertTrue(outBounds.getBounds().isEmpty());
    }

    @Test
    public void updateActiveAndA11yFocusedWindow_windowStateChangedEvent_noTracking_shouldUpdate() {
        final IBinder eventWindowToken = mWindowInfos.get(DEFAULT_FOCUSED_INDEX + 1).token;
        final int eventWindowId = mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, eventWindowToken);
        when(mMockWindowManagerInternal.getFocusedWindowToken())
                .thenReturn(eventWindowToken);

        final int noUse = 0;
        mA11yWindowManager.stopTrackingWindows();
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                eventWindowId,
                noUse,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                noUse);
        assertThat(mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID), is(eventWindowId));
        assertThat(mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT),
                is(eventWindowId));
    }

    @Test
    public void updateActiveAndA11yFocusedWindow_hoverEvent_touchInteract_shouldSetActiveWindow() {
        final int eventWindowId = getWindowIdFromWindowInfos(DEFAULT_FOCUSED_INDEX + 1);
        final int currentActiveWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);
        assertThat(currentActiveWindowId, is(not(eventWindowId)));

        final int noUse = 0;
        mA11yWindowManager.onTouchInteractionStart();
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                eventWindowId,
                noUse,
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
                noUse);
        assertThat(mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID), is(eventWindowId));
        final ArgumentCaptor<AccessibilityEvent> captor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);
        verify(mMockA11yEventSender, times(2))
                .sendAccessibilityEventForCurrentUserLocked(captor.capture());
        assertThat(captor.getAllValues().get(0),
                allOf(a11yWindowId(currentActiveWindowId),
                        a11yWindowChanges(AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)));
        assertThat(captor.getAllValues().get(1),
                allOf(a11yWindowId(eventWindowId),
                        a11yWindowChanges(AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)));
    }

    @Test
    public void updateActiveAndA11yFocusedWindow_a11yFocusEvent_shouldUpdateA11yFocus() {
        final int eventWindowId = getWindowIdFromWindowInfos(DEFAULT_FOCUSED_INDEX);
        final int currentA11yFocusedWindowId = mA11yWindowManager.getFocusedWindowId(
                AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        assertThat(currentA11yFocusedWindowId, is(not(eventWindowId)));

        final int noUse = 0;
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                eventWindowId,
                AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                noUse);
        assertThat(mA11yWindowManager.getFocusedWindowId(
                AccessibilityNodeInfo.FOCUS_ACCESSIBILITY), is(eventWindowId));
        final ArgumentCaptor<AccessibilityEvent> captor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);
        verify(mMockA11yEventSender, times(2))
                .sendAccessibilityEventForCurrentUserLocked(captor.capture());
        assertThat(captor.getAllValues().get(0),
                allOf(a11yWindowId(currentA11yFocusedWindowId),
                        a11yWindowChanges(
                                AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED)));
        assertThat(captor.getAllValues().get(1),
                allOf(a11yWindowId(eventWindowId),
                        a11yWindowChanges(
                                AccessibilityEvent.WINDOWS_CHANGE_ACCESSIBILITY_FOCUSED)));
    }

    @Test
    public void updateActiveAndA11yFocusedWindow_clearA11yFocusEvent_shouldClearA11yFocus() {
        final int eventWindowId = getWindowIdFromWindowInfos(DEFAULT_FOCUSED_INDEX);
        final int currentA11yFocusedWindowId = mA11yWindowManager.getFocusedWindowId(
                AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
        assertThat(currentA11yFocusedWindowId, is(not(eventWindowId)));

        final int noUse = 0;
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                eventWindowId,
                AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                noUse);
        assertThat(mA11yWindowManager.getFocusedWindowId(
                AccessibilityNodeInfo.FOCUS_ACCESSIBILITY), is(eventWindowId));
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                eventWindowId,
                AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUS_CLEARED,
                noUse);
        assertThat(mA11yWindowManager.getFocusedWindowId(
                AccessibilityNodeInfo.FOCUS_ACCESSIBILITY),
                is(AccessibilityWindowInfo.UNDEFINED_WINDOW_ID));
    }

    @Test
    public void onTouchInteractionEnd_shouldRollbackActiveWindow() {
        final int eventWindowId = getWindowIdFromWindowInfos(DEFAULT_FOCUSED_INDEX + 1);
        final int currentActiveWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);
        assertThat(currentActiveWindowId, is(not(eventWindowId)));

        final int noUse = 0;
        mA11yWindowManager.onTouchInteractionStart();
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                eventWindowId,
                noUse,
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
                noUse);
        assertThat(mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID), is(eventWindowId));
        // AccessibilityEventSender is invoked after active window changed. Reset it.
        Mockito.reset(mMockA11yEventSender);

        mA11yWindowManager.onTouchInteractionEnd();
        final ArgumentCaptor<AccessibilityEvent> captor =
                ArgumentCaptor.forClass(AccessibilityEvent.class);
        verify(mMockA11yEventSender, times(2))
                .sendAccessibilityEventForCurrentUserLocked(captor.capture());
        assertThat(captor.getAllValues().get(0),
                allOf(a11yWindowId(eventWindowId),
                        a11yWindowChanges(AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)));
        assertThat(captor.getAllValues().get(1),
                allOf(a11yWindowId(currentActiveWindowId),
                        a11yWindowChanges(AccessibilityEvent.WINDOWS_CHANGE_ACTIVE)));
    }

    @Test
    public void onTouchInteractionEnd_noServiceInteractiveWindow_shouldClearA11yFocus()
            throws RemoteException {
        final IBinder defaultFocusWinToken = mWindowInfos.get(DEFAULT_FOCUSED_INDEX).token;
        final int defaultFocusWindowId = mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, defaultFocusWinToken);
        when(mMockWindowManagerInternal.getFocusedWindowToken())
                .thenReturn(defaultFocusWinToken);
        final int newFocusWindowId = getWindowIdFromWindowInfos(DEFAULT_FOCUSED_INDEX + 1);
        final IAccessibilityInteractionConnection mockNewFocusConnection =
                mA11yWindowManager.getConnectionLocked(
                        USER_SYSTEM_ID, newFocusWindowId).getRemote();

        mA11yWindowManager.stopTrackingWindows();
        final int noUse = 0;
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                defaultFocusWindowId,
                noUse,
                AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
                noUse);
        assertThat(mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID), is(defaultFocusWindowId));
        assertThat(mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT),
                is(defaultFocusWindowId));

        mA11yWindowManager.onTouchInteractionStart();
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                newFocusWindowId,
                noUse,
                AccessibilityEvent.TYPE_VIEW_HOVER_ENTER,
                noUse);
        mA11yWindowManager.updateActiveAndAccessibilityFocusedWindowLocked(USER_SYSTEM_ID,
                newFocusWindowId,
                AccessibilityNodeInfo.ROOT_NODE_ID,
                AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                noUse);
        assertThat(mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID), is(newFocusWindowId));
        assertThat(mA11yWindowManager.getFocusedWindowId(
                AccessibilityNodeInfo.FOCUS_ACCESSIBILITY), is(newFocusWindowId));

        mA11yWindowManager.onTouchInteractionEnd();
        mHandler.sendLastMessage();
        verify(mockNewFocusConnection).clearAccessibilityFocus();
    }

    @Test
    public void getPictureInPictureWindow_shouldNotNull() {
        assertNull(mA11yWindowManager.getPictureInPictureWindow());
        mWindowInfos.get(1).inPictureInPicture = true;
        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);

        assertNotNull(mA11yWindowManager.getPictureInPictureWindow());
    }

    @Test
    public void notifyOutsideTouch() throws RemoteException {
        final int targetWindowId = getWindowIdFromWindowInfos(1);
        final int outsideWindowId = getWindowIdFromWindowInfos(0);
        final IAccessibilityInteractionConnection mockRemoteConnection =
                mA11yWindowManager.getConnectionLocked(
                        USER_SYSTEM_ID, outsideWindowId).getRemote();
        mWindowInfos.get(0).hasFlagWatchOutsideTouch = true;
        mA11yWindowManager.onWindowsForAccessibilityChanged(SEND_ON_WINDOW_CHANGES, mWindowInfos);

        mA11yWindowManager.notifyOutsideTouch(USER_SYSTEM_ID, targetWindowId);
        verify(mockRemoteConnection).notifyOutsideTouch();
    }

    private IWindow addAccessibilityInteractionConnection(boolean bGlobal)
            throws RemoteException {
        final IWindow mockWindowToken = Mockito.mock(IWindow.class);
        final IAccessibilityInteractionConnection mockA11yConnection = Mockito.mock(
                IAccessibilityInteractionConnection.class);
        final IBinder mockConnectionBinder = Mockito.mock(IBinder.class);
        final IBinder mockWindowBinder = Mockito.mock(IBinder.class);
        when(mockA11yConnection.asBinder()).thenReturn(mockConnectionBinder);
        when(mockWindowToken.asBinder()).thenReturn(mockWindowBinder);
        when(mMockA11ySecurityPolicy.isCallerInteractingAcrossUsers(USER_SYSTEM_ID))
                .thenReturn(bGlobal);

        int windowId = mA11yWindowManager.addAccessibilityInteractionConnection(
                mockWindowToken, mockA11yConnection, PACKAGE_NAME, USER_SYSTEM_ID);
        mA11yWindowTokens.put(windowId, mockWindowToken);
        return mockWindowToken;
    }

    private void addWindowInfo(IWindow windowToken, int layer) {
        final WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.type = AccessibilityWindowInfo.TYPE_APPLICATION;
        windowInfo.token = windowToken.asBinder();
        windowInfo.layer = layer;
        windowInfo.boundsInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        mWindowInfos.add(windowInfo);
    }

    private int getWindowIdFromWindowInfos(int index) {
        final IBinder windowToken = mWindowInfos.get(index).token;
        return mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, windowToken);
    }

    static class WindowIdMatcher extends TypeSafeMatcher<AccessibilityEvent> {
        private int mWindowId;

        WindowIdMatcher(int windowId) {
            super();
            mWindowId = windowId;
        }

        static WindowIdMatcher a11yWindowId(int windowId) {
            return new WindowIdMatcher(windowId);
        }

        @Override
        protected boolean matchesSafely(AccessibilityEvent event) {
            return event.getWindowId() == mWindowId;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Matching to windowId " + mWindowId);
        }
    }

    static class WindowChangesMatcher extends TypeSafeMatcher<AccessibilityEvent> {
        private int mWindowChanges;

        WindowChangesMatcher(int windowChanges) {
            super();
            mWindowChanges = windowChanges;
        }

        static WindowChangesMatcher a11yWindowChanges(int windowChanges) {
            return new WindowChangesMatcher(windowChanges);
        }

        @Override
        protected boolean matchesSafely(AccessibilityEvent event) {
            return event.getWindowChanges() == mWindowChanges;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Matching to window changes " + mWindowChanges);
        }
    }
}
