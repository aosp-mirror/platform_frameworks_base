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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Region;
import android.os.IBinder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.SparseArray;
import android.view.Display;
import android.view.IWindow;
import android.view.WindowInfo;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.IAccessibilityInteractionConnection;

import com.android.server.accessibility.AccessibilityWindowManager.RemoteAccessibilityConnection;
import com.android.server.accessibility.test.MessageCapturingHandler;
import com.android.server.wm.WindowManagerInternal;
import com.android.server.wm.WindowManagerInternal.WindowsForAccessibilityCallback;

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
import java.util.Arrays;
import java.util.List;

/**
 * Tests for the AccessibilityWindowManager
 */
public class AccessibilityWindowManagerTest {
    private static final String PACKAGE_NAME = "com.android.server.accessibility";
    private static final boolean FORCE_SEND = true;
    private static final boolean SEND_ON_WINDOW_CHANGES = false;
    private static final int USER_SYSTEM_ID = UserHandle.USER_SYSTEM;
    private static final int USER_PROFILE = 11;
    private static final int USER_PROFILE_PARENT = 1;
    private static final int SECONDARY_DISPLAY_ID = Display.DEFAULT_DISPLAY + 1;
    private static final int NUM_GLOBAL_WINDOWS = 4;
    private static final int NUM_APP_WINDOWS = 4;
    private static final int NUM_OF_WINDOWS = (NUM_GLOBAL_WINDOWS + NUM_APP_WINDOWS);
    private static final int DEFAULT_FOCUSED_INDEX = 1;
    private static final int SCREEN_WIDTH = 1080;
    private static final int SCREEN_HEIGHT = 1920;
    private static final int INVALID_ID = AccessibilityWindowInfo.UNDEFINED_WINDOW_ID;
    private static final int HOST_WINDOW_ID = 10;
    private static final int EMBEDDED_WINDOW_ID = 11;
    private static final int OTHER_WINDOW_ID = 12;

    private AccessibilityWindowManager mA11yWindowManager;
    // Window manager will support multiple focused window if config_perDisplayFocusEnabled is true,
    // i.e., each display would have its current focused window, and one of all focused windows
    // would be top focused window. Otherwise, window manager only supports one focused window
    // at all displays, and that focused window would be top focused window.
    private boolean mSupportPerDisplayFocus = false;
    private int mTopFocusedDisplayId = Display.INVALID_DISPLAY;
    private IBinder mTopFocusedWindowToken = null;

    // List of window token, mapping from windowId -> window token.
    private final SparseArray<IWindow> mA11yWindowTokens = new SparseArray<>();
    // List of window info lists, mapping from displayId -> window info lists.
    private final SparseArray<ArrayList<WindowInfo>> mWindowInfos =
            new SparseArray<>();
    // List of callback, mapping from displayId -> callback.
    private final SparseArray<WindowsForAccessibilityCallback> mCallbackOfWindows =
            new SparseArray<>();
    // List of display ID.
    private final ArrayList<Integer> mExpectedDisplayList = new ArrayList<>(Arrays.asList(
            Display.DEFAULT_DISPLAY, SECONDARY_DISPLAY_ID));

    private final MessageCapturingHandler mHandler = new MessageCapturingHandler(null);

    @Mock private WindowManagerInternal mMockWindowManagerInternal;
    @Mock private AccessibilityWindowManager.AccessibilityEventSender mMockA11yEventSender;
    @Mock private AccessibilitySecurityPolicy mMockA11ySecurityPolicy;
    @Mock private AccessibilitySecurityPolicy.AccessibilityUserManager mMockA11yUserManager;

    @Mock private IBinder mMockHostToken;
    @Mock private IBinder mMockEmbeddedToken;
    @Mock private IBinder mMockInvalidToken;

    @Before
    public void setUp() throws RemoteException {
        MockitoAnnotations.initMocks(this);
        when(mMockA11yUserManager.getCurrentUserIdLocked()).thenReturn(USER_SYSTEM_ID);
        when(mMockA11ySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                USER_PROFILE)).thenReturn(USER_PROFILE_PARENT);
        when(mMockA11ySecurityPolicy.resolveCallingUserIdEnforcingPermissionsLocked(
                USER_SYSTEM_ID)).thenReturn(USER_SYSTEM_ID);
        when(mMockA11ySecurityPolicy.resolveValidReportedPackageLocked(
                anyString(), anyInt(), anyInt(), anyInt())).thenReturn(PACKAGE_NAME);

        mA11yWindowManager = new AccessibilityWindowManager(new Object(), mHandler,
                mMockWindowManagerInternal,
                mMockA11yEventSender,
                mMockA11ySecurityPolicy,
                mMockA11yUserManager);
        // Starts tracking window of default display and sets the default display
        // as top focused display before each testing starts.
        startTrackingPerDisplay(Display.DEFAULT_DISPLAY);

        // AccessibilityEventSender is invoked during onWindowsForAccessibilityChanged.
        // Resets it for mockito verify of further test case.
        Mockito.reset(mMockA11yEventSender);

        registerLeashedTokenAndWindowId();
    }

    @After
    public void tearDown() {
        mHandler.removeAllMessages();
    }

    @Test
    public void startTrackingWindows_shouldEnableWindowManagerCallback() {
        // AccessibilityWindowManager#startTrackingWindows already invoked in setup.
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked(Display.DEFAULT_DISPLAY));
        final WindowsForAccessibilityCallback callbacks =
                mCallbackOfWindows.get(Display.DEFAULT_DISPLAY);
        verify(mMockWindowManagerInternal).setWindowsForAccessibilityCallback(
                eq(Display.DEFAULT_DISPLAY), eq(callbacks));
    }

    @Test
    public void stopTrackingWindows_shouldDisableWindowManagerCallback() {
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked(Display.DEFAULT_DISPLAY));
        Mockito.reset(mMockWindowManagerInternal);

        mA11yWindowManager.stopTrackingWindows(Display.DEFAULT_DISPLAY);
        assertFalse(mA11yWindowManager.isTrackingWindowsLocked(Display.DEFAULT_DISPLAY));
        verify(mMockWindowManagerInternal).setWindowsForAccessibilityCallback(
                eq(Display.DEFAULT_DISPLAY), isNull());

    }

    @Test
    public void stopTrackingWindows_shouldClearWindows() {
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked(Display.DEFAULT_DISPLAY));
        final int activeWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);

        mA11yWindowManager.stopTrackingWindows(Display.DEFAULT_DISPLAY);
        assertNull(mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY));
        assertEquals(mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT),
                AccessibilityWindowInfo.UNDEFINED_WINDOW_ID);
        assertEquals(mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID),
                activeWindowId);
    }

    @Test
    public void stopTrackingWindows_onNonTopFocusedDisplay_shouldNotResetTopFocusWindow()
            throws RemoteException {
        // At setup, the default display sets be the top focused display and
        // its current focused window sets be the top focused window.
        // Starts tracking window of second display.
        startTrackingPerDisplay(SECONDARY_DISPLAY_ID);
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked(SECONDARY_DISPLAY_ID));
        // Stops tracking windows of second display.
        mA11yWindowManager.stopTrackingWindows(SECONDARY_DISPLAY_ID);
        assertNotEquals(mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT),
                AccessibilityWindowInfo.UNDEFINED_WINDOW_ID);
    }

    @Test
    public void onWindowsChanged_duringTouchInteractAndFocusChange_shouldChangeActiveWindow() {
        final int activeWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);
        WindowInfo focusedWindowInfo =
                mWindowInfos.get(Display.DEFAULT_DISPLAY).get(DEFAULT_FOCUSED_INDEX);
        assertEquals(activeWindowId, mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, focusedWindowInfo.token));

        focusedWindowInfo.focused = false;
        focusedWindowInfo =
                mWindowInfos.get(Display.DEFAULT_DISPLAY).get(DEFAULT_FOCUSED_INDEX + 1);
        focusedWindowInfo.focused = true;

        mA11yWindowManager.onTouchInteractionStart();
        setTopFocusedWindowAndDisplay(Display.DEFAULT_DISPLAY, DEFAULT_FOCUSED_INDEX + 1);
        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);

        assertNotEquals(activeWindowId, mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID));
    }

    @Test
    public void
            onWindowsChanged_focusChangeOnNonTopFocusedDisplay_perDisplayFocusOn_notChangeWindow()
            throws RemoteException {
        // At setup, the default display sets be the top focused display and
        // its current focused window sets be the top focused window.
        // Sets supporting multiple focused window, i.e., config_perDisplayFocusEnabled is true.
        mSupportPerDisplayFocus = true;
        // Starts tracking window of second display.
        startTrackingPerDisplay(SECONDARY_DISPLAY_ID);
        // Gets the active window.
        final int activeWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);
        // Gets the top focused window.
        final int topFocusedWindowId =
                mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT);
        // Changes the current focused window at second display.
        changeFocusedWindowOnDisplayPerDisplayFocusConfig(SECONDARY_DISPLAY_ID,
                DEFAULT_FOCUSED_INDEX + 1, Display.DEFAULT_DISPLAY, DEFAULT_FOCUSED_INDEX);

        onWindowsForAccessibilityChanged(SECONDARY_DISPLAY_ID, SEND_ON_WINDOW_CHANGES);
        // The active window should not be changed.
        assertEquals(activeWindowId, mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID));
        // The top focused window should not be changed.
        assertEquals(topFocusedWindowId,
                mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT));
    }

    @Test
    public void
            onWindowChange_focusChangeToNonTopFocusedDisplay_perDisplayFocusOff_shouldChangeWindow()
            throws RemoteException {
        // At setup, the default display sets be the top focused display and
        // its current focused window sets be the top focused window.
        // Sets not supporting multiple focused window, i.e., config_perDisplayFocusEnabled is
        // false.
        mSupportPerDisplayFocus = false;
        // Starts tracking window of second display.
        startTrackingPerDisplay(SECONDARY_DISPLAY_ID);
        // Gets the active window.
        final int activeWindowId = mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID);
        // Gets the top focused window.
        final int topFocusedWindowId =
                mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT);
        // Changes the current focused window from default display to second display.
        changeFocusedWindowOnDisplayPerDisplayFocusConfig(SECONDARY_DISPLAY_ID,
                DEFAULT_FOCUSED_INDEX, Display.DEFAULT_DISPLAY, DEFAULT_FOCUSED_INDEX);

        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);
        onWindowsForAccessibilityChanged(SECONDARY_DISPLAY_ID, SEND_ON_WINDOW_CHANGES);
        // The active window should be changed.
        assertNotEquals(activeWindowId, mA11yWindowManager.getActiveWindowId(USER_SYSTEM_ID));
        // The top focused window should be changed.
        assertNotEquals(topFocusedWindowId,
                mA11yWindowManager.getFocusedWindowId(AccessibilityNodeInfo.FOCUS_INPUT));
    }

    @Test
    public void onWindowsChanged_shouldReportCorrectLayer() {
        // AccessibilityWindowManager#onWindowsForAccessibilityChanged already invoked in setup.
        List<AccessibilityWindowInfo> a11yWindows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        for (int i = 0; i < a11yWindows.size(); i++) {
            final AccessibilityWindowInfo a11yWindow = a11yWindows.get(i);
            final WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(i);
            assertThat(mWindowInfos.get(Display.DEFAULT_DISPLAY).size() - windowInfo.layer - 1,
                    is(a11yWindow.getLayer()));
        }
    }

    @Test
    public void onWindowsChanged_shouldReportCorrectOrder() {
        // AccessibilityWindowManager#onWindowsForAccessibilityChanged already invoked in setup.
        List<AccessibilityWindowInfo> a11yWindows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        for (int i = 0; i < a11yWindows.size(); i++) {
            final AccessibilityWindowInfo a11yWindow = a11yWindows.get(i);
            final IBinder windowToken = mA11yWindowManager
                    .getWindowTokenForUserAndWindowIdLocked(USER_SYSTEM_ID, a11yWindow.getId());
            final WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(i);
            assertThat(windowToken, is(windowInfo.token));
        }
    }

    @Test
    public void onWindowsChangedAndForceSend_shouldUpdateWindows() {
        final WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(0);
        final int correctLayer =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY).get(0).getLayer();
        windowInfo.layer += 1;

        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, FORCE_SEND);
        assertNotEquals(correctLayer,
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY).get(0).getLayer());
    }

    @Test
    public void onWindowsChangedNoForceSend_layerChanged_shouldNotUpdateWindows() {
        final WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(0);
        final int correctLayer =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY).get(0).getLayer();
        windowInfo.layer += 1;

        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);
        assertEquals(correctLayer,
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY).get(0).getLayer());
    }

    @Test
    public void onWindowsChangedNoForceSend_windowChanged_shouldUpdateWindows()
            throws RemoteException {
        final AccessibilityWindowInfo oldWindow =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY).get(0);
        final IWindow token = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY,
                true, USER_SYSTEM_ID);
        final WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.type = AccessibilityWindowInfo.TYPE_APPLICATION;
        windowInfo.token = token.asBinder();
        windowInfo.layer = 0;
        windowInfo.regionInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        mWindowInfos.get(Display.DEFAULT_DISPLAY).set(0, windowInfo);

        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);
        assertNotEquals(oldWindow,
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY).get(0));
    }

    @Test
    public void onWindowsChangedNoForceSend_focusChanged_shouldUpdateWindows() {
        final WindowInfo focusedWindowInfo =
                mWindowInfos.get(Display.DEFAULT_DISPLAY).get(DEFAULT_FOCUSED_INDEX);
        final WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(0);
        focusedWindowInfo.focused = false;
        windowInfo.focused = true;

        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);
        assertTrue(mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY).get(0)
                .isFocused());
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
        final List<AccessibilityWindowInfo> windows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        for (int i = 0; i < windows.size(); i++) {
            final int windowId = windows.get(i).getId();

            assertNotNull(mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(
                    USER_SYSTEM_ID, windowId));
        }
    }

    @Test
    public void findWindowId() {
        final List<AccessibilityWindowInfo> windows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        for (int i = 0; i < windows.size(); i++) {
            final int windowId = windows.get(i).getId();
            final IBinder windowToken = mA11yWindowManager.getWindowTokenForUserAndWindowIdLocked(
                    USER_SYSTEM_ID, windowId);

            assertEquals(mA11yWindowManager.findWindowIdLocked(
                    USER_SYSTEM_ID, windowToken), windowId);
        }
    }

    @Test
    public void resolveParentWindowId_windowIsNotEmbedded_shouldReturnGivenId()
            throws RemoteException {
        final int windowId = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY, false,
                Mockito.mock(IBinder.class), USER_SYSTEM_ID);
        assertEquals(windowId, mA11yWindowManager.resolveParentWindowIdLocked(windowId));
    }

    @Test
    public void resolveParentWindowId_windowIsNotRegistered_shouldReturnGivenId() {
        final int windowId = -1;
        assertEquals(windowId, mA11yWindowManager.resolveParentWindowIdLocked(windowId));
    }

    @Test
    public void resolveParentWindowId_windowIsAssociated_shouldReturnParentWindowId()
            throws RemoteException {
        final IBinder mockHostToken = Mockito.mock(IBinder.class);
        final IBinder mockEmbeddedToken = Mockito.mock(IBinder.class);
        final int hostWindowId = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY,
                false, mockHostToken, USER_SYSTEM_ID);
        final int embeddedWindowId = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY,
                false, mockEmbeddedToken, USER_SYSTEM_ID);
        mA11yWindowManager.associateEmbeddedHierarchyLocked(mockHostToken, mockEmbeddedToken);
        final int resolvedWindowId = mA11yWindowManager.resolveParentWindowIdLocked(
                embeddedWindowId);
        assertEquals(hostWindowId, resolvedWindowId);
    }

    @Test
    public void resolveParentWindowId_windowIsDisassociated_shouldReturnGivenId()
            throws RemoteException {
        final IBinder mockHostToken = Mockito.mock(IBinder.class);
        final IBinder mockEmbeddedToken = Mockito.mock(IBinder.class);
        final int hostWindowId = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY,
                false, mockHostToken, USER_SYSTEM_ID);
        final int embeddedWindowId = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY,
                false, mockEmbeddedToken, USER_SYSTEM_ID);
        mA11yWindowManager.associateEmbeddedHierarchyLocked(mockHostToken, mockEmbeddedToken);
        mA11yWindowManager.disassociateEmbeddedHierarchyLocked(mockEmbeddedToken);
        final int resolvedWindowId = mA11yWindowManager.resolveParentWindowIdLocked(
                embeddedWindowId);
        assertEquals(embeddedWindowId, resolvedWindowId);
    }

    @Test
    public void computePartialInteractiveRegionForWindow_wholeVisible_returnWholeRegion() {
        // Updates top 2 z-order WindowInfo are whole visible.
        WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(0);
        windowInfo.regionInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT / 2);
        windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(1);
        windowInfo.regionInScreen.set(0, SCREEN_HEIGHT / 2,
                SCREEN_WIDTH, SCREEN_HEIGHT);
        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);

        final List<AccessibilityWindowInfo> a11yWindows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        final Region outBounds = new Region();
        int windowId = a11yWindows.get(0).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(windowId, outBounds);
        assertThat(outBounds.getBounds().width(), is(SCREEN_WIDTH));
        assertThat(outBounds.getBounds().height(), is(SCREEN_HEIGHT / 2));

        windowId = a11yWindows.get(1).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(windowId, outBounds);
        assertThat(outBounds.getBounds().width(), is(SCREEN_WIDTH));
        assertThat(outBounds.getBounds().height(), is(SCREEN_HEIGHT / 2));
    }

    @Test
    public void computePartialInteractiveRegionForWindow_halfVisible_returnHalfRegion() {
        // Updates z-order #1 WindowInfo is half visible.
        WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(0);
        windowInfo.regionInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT / 2);

        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);
        final List<AccessibilityWindowInfo> a11yWindows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        final Region outBounds = new Region();
        int windowId = a11yWindows.get(1).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(windowId, outBounds);
        assertThat(outBounds.getBounds().width(), is(SCREEN_WIDTH));
        assertThat(outBounds.getBounds().height(), is(SCREEN_HEIGHT / 2));
    }

    @Test
    public void computePartialInteractiveRegionForWindow_notVisible_returnEmptyRegion() {
        // Since z-order #0 WindowInfo is full screen, z-order #1 WindowInfo should be invisible.
        final List<AccessibilityWindowInfo> a11yWindows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        final Region outBounds = new Region();
        int windowId = a11yWindows.get(1).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(windowId, outBounds);
        assertTrue(outBounds.getBounds().isEmpty());
    }

    @Test
    public void computePartialInteractiveRegionForWindow_partialVisible_returnVisibleRegion() {
        // Updates z-order #0 WindowInfo to have two interact-able areas.
        Region region = new Region(0, 0, SCREEN_WIDTH, 200);
        region.op(0, SCREEN_HEIGHT - 200, SCREEN_WIDTH, SCREEN_HEIGHT, Region.Op.UNION);
        WindowInfo windowInfo = mWindowInfos.get(Display.DEFAULT_DISPLAY).get(0);
        windowInfo.regionInScreen.set(region);
        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);

        final List<AccessibilityWindowInfo> a11yWindows =
                mA11yWindowManager.getWindowListLocked(Display.DEFAULT_DISPLAY);
        final Region outBounds = new Region();
        int windowId = a11yWindows.get(1).getId();

        mA11yWindowManager.computePartialInteractiveRegionForWindowLocked(windowId, outBounds);
        assertFalse(outBounds.getBounds().isEmpty());
        assertThat(outBounds.getBounds().width(), is(SCREEN_WIDTH));
        assertThat(outBounds.getBounds().height(), is(SCREEN_HEIGHT - 400));
    }

    @Test
    public void updateActiveAndA11yFocusedWindow_windowStateChangedEvent_noTracking_shouldUpdate() {
        final IBinder eventWindowToken =
                mWindowInfos.get(Display.DEFAULT_DISPLAY).get(DEFAULT_FOCUSED_INDEX + 1).token;
        final int eventWindowId = mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, eventWindowToken);
        when(mMockWindowManagerInternal.getFocusedWindowToken())
                .thenReturn(eventWindowToken);

        final int noUse = 0;
        mA11yWindowManager.stopTrackingWindows(Display.DEFAULT_DISPLAY);
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
        final int eventWindowId = getWindowIdFromWindowInfosForDisplay(Display.DEFAULT_DISPLAY,
                DEFAULT_FOCUSED_INDEX + 1);
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
        final int eventWindowId = getWindowIdFromWindowInfosForDisplay(Display.DEFAULT_DISPLAY,
                DEFAULT_FOCUSED_INDEX);
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
        final int eventWindowId = getWindowIdFromWindowInfosForDisplay(Display.DEFAULT_DISPLAY,
                DEFAULT_FOCUSED_INDEX);
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
        final int eventWindowId = getWindowIdFromWindowInfosForDisplay(Display.DEFAULT_DISPLAY,
                DEFAULT_FOCUSED_INDEX + 1);
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
        final IBinder defaultFocusWinToken =
                mWindowInfos.get(Display.DEFAULT_DISPLAY).get(DEFAULT_FOCUSED_INDEX).token;
        final int defaultFocusWindowId = mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, defaultFocusWinToken);
        when(mMockWindowManagerInternal.getFocusedWindowToken())
                .thenReturn(defaultFocusWinToken);
        final int newFocusWindowId = getWindowIdFromWindowInfosForDisplay(Display.DEFAULT_DISPLAY,
                DEFAULT_FOCUSED_INDEX + 1);
        final IAccessibilityInteractionConnection mockNewFocusConnection =
                mA11yWindowManager.getConnectionLocked(
                        USER_SYSTEM_ID, newFocusWindowId).getRemote();

        mA11yWindowManager.stopTrackingWindows(Display.DEFAULT_DISPLAY);
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
        assertNull(mA11yWindowManager.getPictureInPictureWindowLocked());
        mWindowInfos.get(Display.DEFAULT_DISPLAY).get(1).inPictureInPicture = true;
        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);

        assertNotNull(mA11yWindowManager.getPictureInPictureWindowLocked());
    }

    @Test
    public void notifyOutsideTouch() throws RemoteException {
        final int targetWindowId =
                getWindowIdFromWindowInfosForDisplay(Display.DEFAULT_DISPLAY, 1);
        final int outsideWindowId =
                getWindowIdFromWindowInfosForDisplay(Display.DEFAULT_DISPLAY, 0);
        final IAccessibilityInteractionConnection mockRemoteConnection =
                mA11yWindowManager.getConnectionLocked(
                        USER_SYSTEM_ID, outsideWindowId).getRemote();
        mWindowInfos.get(Display.DEFAULT_DISPLAY).get(0).hasFlagWatchOutsideTouch = true;
        onWindowsForAccessibilityChanged(Display.DEFAULT_DISPLAY, SEND_ON_WINDOW_CHANGES);

        mA11yWindowManager.notifyOutsideTouch(USER_SYSTEM_ID, targetWindowId);
        verify(mockRemoteConnection).notifyOutsideTouch();
    }

    @Test
    public void addAccessibilityInteractionConnection_profileUser_findInParentUser()
            throws RemoteException {
        final IWindow token = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY,
                false, USER_PROFILE);
        final int windowId = mA11yWindowManager.findWindowIdLocked(
                USER_PROFILE_PARENT, token.asBinder());
        assertTrue(windowId >= 0);
    }

    @Test
    public void getDisplayList() throws RemoteException {
        // Starts tracking window of second display.
        startTrackingPerDisplay(SECONDARY_DISPLAY_ID);

        final ArrayList<Integer> displayList = mA11yWindowManager.getDisplayListLocked();
        assertTrue(displayList.equals(mExpectedDisplayList));
    }

    @Test
    public void setAccessibilityWindowIdToSurfaceMetadata()
            throws RemoteException {
        final IWindow token = addAccessibilityInteractionConnection(Display.DEFAULT_DISPLAY,
                true, USER_SYSTEM_ID);
        int windowId = -1;
        for (int i = 0; i < mA11yWindowTokens.size(); i++) {
            if (mA11yWindowTokens.valueAt(i).equals(token)) {
                windowId = mA11yWindowTokens.keyAt(i);
            }
        }
        assertNotEquals("Returned token is not found in mA11yWindowTokens", -1, windowId);
        verify(mMockWindowManagerInternal, times(1)).setAccessibilityIdToSurfaceMetadata(
                token.asBinder(), windowId);

        mA11yWindowManager.removeAccessibilityInteractionConnection(token);
        verify(mMockWindowManagerInternal, times(1)).setAccessibilityIdToSurfaceMetadata(
                token.asBinder(), -1);
    }

    @Test
    public void getHostTokenLocked_hierarchiesAreAssociated_shouldReturnHostToken() {
        mA11yWindowManager.associateLocked(mMockEmbeddedToken, mMockHostToken);
        final IBinder hostToken = mA11yWindowManager.getHostTokenLocked(mMockEmbeddedToken);
        assertEquals(hostToken, mMockHostToken);
    }

    @Test
    public void getHostTokenLocked_hierarchiesAreNotAssociated_shouldReturnNull() {
        final IBinder hostToken = mA11yWindowManager.getHostTokenLocked(mMockEmbeddedToken);
        assertNull(hostToken);
    }

    @Test
    public void getHostTokenLocked_embeddedHierarchiesAreDisassociated_shouldReturnNull() {
        mA11yWindowManager.associateLocked(mMockEmbeddedToken, mMockHostToken);
        mA11yWindowManager.disassociateLocked(mMockEmbeddedToken);
        final IBinder hostToken = mA11yWindowManager.getHostTokenLocked(mMockEmbeddedToken);
        assertNull(hostToken);
    }

    @Test
    public void getHostTokenLocked_hostHierarchiesAreDisassociated_shouldReturnNull() {
        mA11yWindowManager.associateLocked(mMockEmbeddedToken, mMockHostToken);
        mA11yWindowManager.disassociateLocked(mMockHostToken);
        final IBinder hostToken = mA11yWindowManager.getHostTokenLocked(mMockHostToken);
        assertNull(hostToken);
    }

    @Test
    public void getWindowIdLocked_windowIsRegistered_shouldReturnWindowId() {
        final int windowId = mA11yWindowManager.getWindowIdLocked(mMockHostToken);
        assertEquals(windowId, HOST_WINDOW_ID);
    }

    @Test
    public void getWindowIdLocked_windowIsNotRegistered_shouldReturnInvalidWindowId() {
        final int windowId = mA11yWindowManager.getWindowIdLocked(mMockInvalidToken);
        assertEquals(windowId, INVALID_ID);
    }

    @Test
    public void getTokenLocked_windowIsRegistered_shouldReturnToken() {
        final IBinder token = mA11yWindowManager.getTokenLocked(HOST_WINDOW_ID);
        assertEquals(token, mMockHostToken);
    }

    @Test
    public void getTokenLocked_windowIsNotRegistered_shouldReturnNull() {
        final IBinder token = mA11yWindowManager.getTokenLocked(OTHER_WINDOW_ID);
        assertNull(token);
    }

    @Test
    public void onDisplayReparented_shouldRemoveObserver() throws RemoteException {
        // Starts tracking window of second display.
        startTrackingPerDisplay(SECONDARY_DISPLAY_ID);
        assertTrue(mA11yWindowManager.isTrackingWindowsLocked(SECONDARY_DISPLAY_ID));
        // Notifies the second display is an embedded one of the default display.
        final WindowsForAccessibilityCallback callbacks =
                mCallbackOfWindows.get(Display.DEFAULT_DISPLAY);
        callbacks.onDisplayReparented(SECONDARY_DISPLAY_ID);
        // Makes sure the observer of the second display is removed.
        assertFalse(mA11yWindowManager.isTrackingWindowsLocked(SECONDARY_DISPLAY_ID));
    }

    private void registerLeashedTokenAndWindowId() {
        mA11yWindowManager.registerIdLocked(mMockHostToken, HOST_WINDOW_ID);
        mA11yWindowManager.registerIdLocked(mMockEmbeddedToken, EMBEDDED_WINDOW_ID);
    }

    private void startTrackingPerDisplay(int displayId) throws RemoteException {
        ArrayList<WindowInfo> windowInfosForDisplay = new ArrayList<>();
        // Adds RemoteAccessibilityConnection into AccessibilityWindowManager, and copy
        // mock window token into mA11yWindowTokens. Also, preparing WindowInfo mWindowInfos
        // for the test.
        int layer = 0;
        for (int i = 0; i < NUM_GLOBAL_WINDOWS; i++) {
            final IWindow token = addAccessibilityInteractionConnection(displayId,
                    true, USER_SYSTEM_ID);
            addWindowInfo(windowInfosForDisplay, token, layer++);

        }
        for (int i = 0; i < NUM_APP_WINDOWS; i++) {
            final IWindow token = addAccessibilityInteractionConnection(displayId,
                    false, USER_SYSTEM_ID);
            addWindowInfo(windowInfosForDisplay, token, layer++);
        }
        // Sets up current focused window of display.
        // Each display has its own current focused window if config_perDisplayFocusEnabled is true.
        // Otherwise only default display needs to current focused window.
        if (mSupportPerDisplayFocus || displayId == Display.DEFAULT_DISPLAY) {
            windowInfosForDisplay.get(DEFAULT_FOCUSED_INDEX).focused = true;
        }
        // Turns on windows tracking, and update window info.
        when(mMockWindowManagerInternal.setWindowsForAccessibilityCallback(eq(displayId), any()))
                .thenReturn(true);
        mA11yWindowManager.startTrackingWindows(displayId);
        // Puts window lists into array.
        mWindowInfos.put(displayId, windowInfosForDisplay);
        // Sets the default display is the top focused display and
        // its current focused window is the top focused window.
        if (displayId == Display.DEFAULT_DISPLAY) {
            setTopFocusedWindowAndDisplay(displayId, DEFAULT_FOCUSED_INDEX);
        }
        // Invokes callback for sending window lists to A11y framework.
        onWindowsForAccessibilityChanged(displayId, FORCE_SEND);

        assertEquals(mA11yWindowManager.getWindowListLocked(displayId).size(),
                windowInfosForDisplay.size());
    }

    private WindowsForAccessibilityCallback getWindowsForAccessibilityCallbacks(int displayId) {
        ArgumentCaptor<WindowsForAccessibilityCallback> windowsForAccessibilityCallbacksCaptor =
                ArgumentCaptor.forClass(
                        WindowManagerInternal.WindowsForAccessibilityCallback.class);
        verify(mMockWindowManagerInternal)
                .setWindowsForAccessibilityCallback(eq(displayId),
                        windowsForAccessibilityCallbacksCaptor.capture());
        return windowsForAccessibilityCallbacksCaptor.getValue();
    }

    private IWindow addAccessibilityInteractionConnection(int displayId, boolean bGlobal,
            int userId) throws RemoteException {
        final IWindow mockWindowToken = Mockito.mock(IWindow.class);
        final IAccessibilityInteractionConnection mockA11yConnection = Mockito.mock(
                IAccessibilityInteractionConnection.class);
        final IBinder mockConnectionBinder = Mockito.mock(IBinder.class);
        final IBinder mockWindowBinder = Mockito.mock(IBinder.class);
        final IBinder mockLeashToken = Mockito.mock(IBinder.class);
        when(mockA11yConnection.asBinder()).thenReturn(mockConnectionBinder);
        when(mockWindowToken.asBinder()).thenReturn(mockWindowBinder);
        when(mMockA11ySecurityPolicy.isCallerInteractingAcrossUsers(userId))
                .thenReturn(bGlobal);
        when(mMockWindowManagerInternal.getDisplayIdForWindow(mockWindowToken.asBinder()))
                .thenReturn(displayId);

        int windowId = mA11yWindowManager.addAccessibilityInteractionConnection(
                mockWindowToken, mockLeashToken, mockA11yConnection, PACKAGE_NAME, userId);
        mA11yWindowTokens.put(windowId, mockWindowToken);
        return mockWindowToken;
    }

    private int addAccessibilityInteractionConnection(int displayId, boolean bGlobal,
            IBinder leashToken, int userId) throws RemoteException {
        final IWindow mockWindowToken = Mockito.mock(IWindow.class);
        final IAccessibilityInteractionConnection mockA11yConnection = Mockito.mock(
                IAccessibilityInteractionConnection.class);
        final IBinder mockConnectionBinder = Mockito.mock(IBinder.class);
        final IBinder mockWindowBinder = Mockito.mock(IBinder.class);
        when(mockA11yConnection.asBinder()).thenReturn(mockConnectionBinder);
        when(mockWindowToken.asBinder()).thenReturn(mockWindowBinder);
        when(mMockA11ySecurityPolicy.isCallerInteractingAcrossUsers(userId))
                .thenReturn(bGlobal);
        when(mMockWindowManagerInternal.getDisplayIdForWindow(mockWindowToken.asBinder()))
                .thenReturn(displayId);

        int windowId = mA11yWindowManager.addAccessibilityInteractionConnection(
                mockWindowToken, leashToken, mockA11yConnection, PACKAGE_NAME, userId);
        mA11yWindowTokens.put(windowId, mockWindowToken);
        return windowId;
    }

    private void addWindowInfo(ArrayList<WindowInfo> windowInfos, IWindow windowToken, int layer) {
        final WindowInfo windowInfo = WindowInfo.obtain();
        windowInfo.type = AccessibilityWindowInfo.TYPE_APPLICATION;
        windowInfo.token = windowToken.asBinder();
        windowInfo.layer = layer;
        windowInfo.regionInScreen.set(0, 0, SCREEN_WIDTH, SCREEN_HEIGHT);
        windowInfos.add(windowInfo);
    }

    private int getWindowIdFromWindowInfosForDisplay(int displayId, int index) {
        final IBinder windowToken = mWindowInfos.get(displayId).get(index).token;
        return mA11yWindowManager.findWindowIdLocked(
                USER_SYSTEM_ID, windowToken);
    }

    private void setTopFocusedWindowAndDisplay(int displayId, int index) {
        // Sets the top focus window.
        mTopFocusedWindowToken = mWindowInfos.get(displayId).get(index).token;
        // Sets the top focused display.
        mTopFocusedDisplayId = displayId;
    }

    private void onWindowsForAccessibilityChanged(int displayId, boolean forceSend) {
        WindowsForAccessibilityCallback callbacks = mCallbackOfWindows.get(displayId);
        if (callbacks == null) {
            callbacks = getWindowsForAccessibilityCallbacks(displayId);
            mCallbackOfWindows.put(displayId, callbacks);
        }
        callbacks.onWindowsForAccessibilityChanged(forceSend, mTopFocusedDisplayId,
                mTopFocusedWindowToken, mWindowInfos.get(displayId));
    }

    private void changeFocusedWindowOnDisplayPerDisplayFocusConfig(
            int changeFocusedDisplayId, int newFocusedWindowIndex, int oldTopFocusedDisplayId,
            int oldFocusedWindowIndex) {
        if (mSupportPerDisplayFocus) {
            // Gets the old focused window of display which wants to change focused window.
            WindowInfo focusedWindowInfo =
                    mWindowInfos.get(changeFocusedDisplayId).get(oldFocusedWindowIndex);
            // Resets the focus of old focused window.
            focusedWindowInfo.focused = false;
            // Gets the new window of display which wants to change focused window.
            focusedWindowInfo =
                    mWindowInfos.get(changeFocusedDisplayId).get(newFocusedWindowIndex);
            // Sets the focus of new focused window.
            focusedWindowInfo.focused = true;
        } else {
            // Gets the window of display which wants to change focused window.
            WindowInfo focusedWindowInfo =
                    mWindowInfos.get(changeFocusedDisplayId).get(newFocusedWindowIndex);
            // Sets the focus of new focused window.
            focusedWindowInfo.focused = true;
            // Gets the old focused window of old top focused display.
            focusedWindowInfo =
                    mWindowInfos.get(oldTopFocusedDisplayId).get(oldFocusedWindowIndex);
            // Resets the focus of old focused window.
            focusedWindowInfo.focused = false;
            // Changes the top focused display and window.
            setTopFocusedWindowAndDisplay(changeFocusedDisplayId, newFocusedWindowIndex);
        }
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
