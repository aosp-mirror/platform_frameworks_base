/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package com.android.systemui.statusbar;

import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.content.ComponentName;
import android.graphics.Rect;
import android.os.Bundle;

import androidx.test.filters.SmallTest;

import com.android.internal.statusbar.StatusBarIcon;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.statusbar.CommandQueue.Callbacks;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

@SmallTest
public class CommandQueueTest extends SysuiTestCase {

    private CommandQueue mCommandQueue;
    private Callbacks mCallbacks;

    @Before
    public void setup() {
        mCommandQueue = new CommandQueue();
        mCallbacks = mock(Callbacks.class);
        mCommandQueue.addCallbacks(mCallbacks);
        verify(mCallbacks).disable(eq(0), eq(0), eq(false));
    }

    @After
    public void tearDown() {
        verifyNoMoreInteractions(mCallbacks);
    }

    @Test
    public void testIcon() {
        String slot = "testSlot";
        StatusBarIcon icon = mock(StatusBarIcon.class);
        mCommandQueue.setIcon(slot, icon);
        waitForIdleSync();
        verify(mCallbacks).setIcon(eq(slot), eq(icon));

        mCommandQueue.removeIcon(slot);
        waitForIdleSync();
        verify(mCallbacks).removeIcon(eq(slot));
    }

    @Test
    public void testDisable() {
        int state1 = 14;
        int state2 = 42;
        mCommandQueue.disable(state1, state2);
        waitForIdleSync();
        verify(mCallbacks).disable(eq(state1), eq(state2), eq(true));
    }

    @Test
    public void testExpandNotifications() {
        mCommandQueue.animateExpandNotificationsPanel();
        waitForIdleSync();
        verify(mCallbacks).animateExpandNotificationsPanel();
    }

    @Test
    public void testCollapsePanels() {
        mCommandQueue.animateCollapsePanels();
        waitForIdleSync();
        verify(mCallbacks).animateCollapsePanels(eq(0));
    }

    @Test
    public void testExpandSettings() {
        String panel = "some_panel";
        mCommandQueue.animateExpandSettingsPanel(panel);
        waitForIdleSync();
        verify(mCallbacks).animateExpandSettingsPanel(eq(panel));
    }

    @Test
    public void testSetSystemUiVisibility() {
        Rect r = new Rect();
        mCommandQueue.setSystemUiVisibility(1, 2, 3, 4, null, r);
        waitForIdleSync();
        verify(mCallbacks).setSystemUiVisibility(eq(1), eq(2), eq(3), eq(4), eq(null), eq(r));
    }

    @Test
    public void testTopAppWindowChanged() {
        mCommandQueue.topAppWindowChanged(true);
        waitForIdleSync();
        verify(mCallbacks).topAppWindowChanged(eq(true));
    }

    @Test
    public void testShowImeButton() {
        mCommandQueue.setImeWindowStatus(null, 1, 2, true);
        waitForIdleSync();
        verify(mCallbacks).setImeWindowStatus(eq(null), eq(1), eq(2), eq(true));
    }

    @Test
    public void testShowRecentApps() {
        mCommandQueue.showRecentApps(true);
        waitForIdleSync();
        verify(mCallbacks).showRecentApps(eq(true));
    }

    @Test
    public void testHideRecentApps() {
        mCommandQueue.hideRecentApps(true, false);
        waitForIdleSync();
        verify(mCallbacks).hideRecentApps(eq(true), eq(false));
    }

    @Test
    public void testToggleRecentApps() {
        mCommandQueue.toggleRecentApps();
        waitForIdleSync();
        verify(mCallbacks).toggleRecentApps();
    }

    @Test
    public void testPreloadRecentApps() {
        mCommandQueue.preloadRecentApps();
        waitForIdleSync();
        verify(mCallbacks).preloadRecentApps();
    }

    @Test
    public void testCancelPreloadRecentApps() {
        mCommandQueue.cancelPreloadRecentApps();
        waitForIdleSync();
        verify(mCallbacks).cancelPreloadRecentApps();
    }

    @Test
    public void testDismissKeyboardShortcuts() {
        mCommandQueue.dismissKeyboardShortcutsMenu();
        waitForIdleSync();
        verify(mCallbacks).dismissKeyboardShortcutsMenu();
    }

    @Test
    public void testToggleKeyboardShortcuts() {
        mCommandQueue.toggleKeyboardShortcutsMenu(1);
        waitForIdleSync();
        verify(mCallbacks).toggleKeyboardShortcutsMenu(eq(1));
    }

    @Test
    public void testSetWindowState() {
        mCommandQueue.setWindowState(1, 2);
        waitForIdleSync();
        verify(mCallbacks).setWindowState(eq(1), eq(2));
    }

    @Test
    public void testScreenPinRequest() {
        mCommandQueue.showScreenPinningRequest(1);
        waitForIdleSync();
        verify(mCallbacks).showScreenPinningRequest(eq(1));
    }

    @Test
    public void testAppTransitionPending() {
        mCommandQueue.appTransitionPending();
        waitForIdleSync();
        verify(mCallbacks).appTransitionPending(eq(false));
    }

    @Test
    public void testAppTransitionCancelled() {
        mCommandQueue.appTransitionCancelled();
        waitForIdleSync();
        verify(mCallbacks).appTransitionCancelled();
    }

    @Test
    public void testAppTransitionStarting() {
        mCommandQueue.appTransitionStarting(1, 2);
        waitForIdleSync();
        verify(mCallbacks).appTransitionStarting(eq(1L), eq(2L), eq(false));
    }

    @Test
    public void testAppTransitionFinished() {
        mCommandQueue.appTransitionFinished();
        waitForIdleSync();
        verify(mCallbacks).appTransitionFinished();
    }

    @Test
    public void testAssistDisclosure() {
        mCommandQueue.showAssistDisclosure();
        waitForIdleSync();
        verify(mCallbacks).showAssistDisclosure();
    }

    @Test
    public void testStartAssist() {
        Bundle b = new Bundle();
        mCommandQueue.startAssist(b);
        waitForIdleSync();
        verify(mCallbacks).startAssist(eq(b));
    }

    @Test
    public void testCameraLaunchGesture() {
        mCommandQueue.onCameraLaunchGestureDetected(1);
        waitForIdleSync();
        verify(mCallbacks).onCameraLaunchGestureDetected(eq(1));
    }

    @Test
    public void testShowPipMenu() {
        mCommandQueue.showPictureInPictureMenu();
        waitForIdleSync();
        verify(mCallbacks).showPictureInPictureMenu();
    }

    @Test
    public void testAddQsTile() {
        ComponentName c = new ComponentName("testpkg", "testcls");
        mCommandQueue.addQsTile(c);
        waitForIdleSync();
        verify(mCallbacks).addQsTile(eq(c));
    }

    @Test
    public void testRemoveQsTile() {
        ComponentName c = new ComponentName("testpkg", "testcls");
        mCommandQueue.remQsTile(c);
        waitForIdleSync();
        verify(mCallbacks).remQsTile(eq(c));
    }

    @Test
    public void testClickQsTile() {
        ComponentName c = new ComponentName("testpkg", "testcls");
        mCommandQueue.clickQsTile(c);
        waitForIdleSync();
        verify(mCallbacks).clickTile(eq(c));
    }

    @Test
    public void testToggleAppSplitScreen() {
        mCommandQueue.toggleSplitScreen();
        waitForIdleSync();
        verify(mCallbacks).toggleSplitScreen();
    }

    @Test
    public void testHandleSysKey() {
        mCommandQueue.handleSystemKey(1);
        waitForIdleSync();
        verify(mCallbacks).handleSystemKey(eq(1));
    }
}
