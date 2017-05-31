/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package com.android.server.wm;

import android.util.MergedConfiguration;
import android.view.WindowManager;
import org.junit.Test;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.LinkedList;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
import static android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ABOVE_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_MEDIA_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_SUB_PANEL;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Tests for the {@link WindowState} class.
 *
 * Build/Install/Run:
 *  bit FrameworksServicesTests:com.android.server.wm.WindowStateTests
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowStateTests extends WindowTestsBase {

    @Test
    public void testIsParentWindowHidden() throws Exception {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child2");

        assertFalse(parentWindow.mHidden);
        assertFalse(parentWindow.isParentWindowHidden());
        assertFalse(child1.isParentWindowHidden());
        assertFalse(child2.isParentWindowHidden());

        parentWindow.mHidden = true;
        assertFalse(parentWindow.isParentWindowHidden());
        assertTrue(child1.isParentWindowHidden());
        assertTrue(child2.isParentWindowHidden());
    }

    @Test
    public void testIsChildWindow() throws Exception {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child2");
        final WindowState randomWindow = createWindow(null, TYPE_APPLICATION, "randomWindow");

        assertFalse(parentWindow.isChildWindow());
        assertTrue(child1.isChildWindow());
        assertTrue(child2.isChildWindow());
        assertFalse(randomWindow.isChildWindow());
    }

    @Test
    public void testHasChild() throws Exception {
        final WindowState win1 = createWindow(null, TYPE_APPLICATION, "win1");
        final WindowState win11 = createWindow(win1, FIRST_SUB_WINDOW, "win11");
        final WindowState win12 = createWindow(win1, FIRST_SUB_WINDOW, "win12");
        final WindowState win2 = createWindow(null, TYPE_APPLICATION, "win2");
        final WindowState win21 = createWindow(win2, FIRST_SUB_WINDOW, "win21");
        final WindowState randomWindow = createWindow(null, TYPE_APPLICATION, "randomWindow");

        assertTrue(win1.hasChild(win11));
        assertTrue(win1.hasChild(win12));
        assertTrue(win2.hasChild(win21));

        assertFalse(win1.hasChild(win21));
        assertFalse(win1.hasChild(randomWindow));

        assertFalse(win2.hasChild(win11));
        assertFalse(win2.hasChild(win12));
        assertFalse(win2.hasChild(randomWindow));
    }

    @Test
    public void testGetParentWindow() throws Exception {
        final WindowState parentWindow = createWindow(null, TYPE_APPLICATION, "parentWindow");
        final WindowState child1 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(parentWindow, FIRST_SUB_WINDOW, "child2");

        assertNull(parentWindow.getParentWindow());
        assertEquals(parentWindow, child1.getParentWindow());
        assertEquals(parentWindow, child2.getParentWindow());
    }

    @Test
    public void testGetTopParentWindow() throws Exception {
        final WindowState root = createWindow(null, TYPE_APPLICATION, "root");
        final WindowState child1 = createWindow(root, FIRST_SUB_WINDOW, "child1");
        final WindowState child2 = createWindow(child1, FIRST_SUB_WINDOW, "child2");

        assertEquals(root, root.getTopParentWindow());
        assertEquals(root, child1.getTopParentWindow());
        assertEquals(child1, child2.getParentWindow());
        assertEquals(root, child2.getTopParentWindow());

        // Test case were child is detached from parent.
        root.removeChild(child1);
        assertEquals(child1, child1.getTopParentWindow());
        assertEquals(child1, child2.getParentWindow());
    }

    @Test
    public void testIsOnScreen_hiddenByPolicy() {
        final WindowState window = createWindow(null, TYPE_APPLICATION, "window");
        window.setHasSurface(true);
        assertTrue(window.isOnScreen());
        window.hideLw(false /* doAnimation */);
        assertFalse(window.isOnScreen());
    }

    @Test
    public void testCanBeImeTarget() throws Exception {
        final WindowState appWindow = createWindow(null, TYPE_APPLICATION, "appWindow");
        final WindowState imeWindow = createWindow(null, TYPE_INPUT_METHOD, "imeWindow");

        // Setting FLAG_NOT_FOCUSABLE without FLAG_ALT_FOCUSABLE_IM prevents the window from being
        // an IME target.
        appWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;
        imeWindow.mAttrs.flags |= FLAG_NOT_FOCUSABLE;

        // Make windows visible
        appWindow.setHasSurface(true);
        imeWindow.setHasSurface(true);

        // Windows without flags (FLAG_NOT_FOCUSABLE|FLAG_ALT_FOCUSABLE_IM) can't be IME targets
        assertFalse(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Add IME target flags
        appWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);
        imeWindow.mAttrs.flags |= (FLAG_NOT_FOCUSABLE | FLAG_ALT_FOCUSABLE_IM);

        // Visible app window with flags can be IME target while an IME window can never be an IME
        // target regardless of its visibility or flags.
        assertTrue(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());

        // Make windows invisible
        appWindow.hideLw(false /* doAnimation */);
        imeWindow.hideLw(false /* doAnimation */);

        // Invisible window can't be IME targets even if they have the right flags.
        assertFalse(appWindow.canBeImeTarget());
        assertFalse(imeWindow.canBeImeTarget());
    }

    @Test
    public void testGetWindow() throws Exception {
        final WindowState root = createWindow(null, TYPE_APPLICATION, "root");
        final WindowState mediaChild = createWindow(root, TYPE_APPLICATION_MEDIA, "mediaChild");
        final WindowState mediaOverlayChild = createWindow(root,
                TYPE_APPLICATION_MEDIA_OVERLAY, "mediaOverlayChild");
        final WindowState attachedDialogChild = createWindow(root,
                TYPE_APPLICATION_ATTACHED_DIALOG, "attachedDialogChild");
        final WindowState subPanelChild = createWindow(root,
                TYPE_APPLICATION_SUB_PANEL, "subPanelChild");
        final WindowState aboveSubPanelChild = createWindow(root,
                TYPE_APPLICATION_ABOVE_SUB_PANEL, "aboveSubPanelChild");

        final LinkedList<WindowState> windows = new LinkedList();

        root.getWindow(w -> {
            windows.addLast(w);
            return false;
        });

        // getWindow should have returned candidate windows in z-order.
        assertEquals(aboveSubPanelChild, windows.pollFirst());
        assertEquals(subPanelChild, windows.pollFirst());
        assertEquals(attachedDialogChild, windows.pollFirst());
        assertEquals(root, windows.pollFirst());
        assertEquals(mediaOverlayChild, windows.pollFirst());
        assertEquals(mediaChild, windows.pollFirst());
        assertTrue(windows.isEmpty());
    }

    @Test
    public void testPrepareWindowToDisplayDuringRelayout() throws Exception {
        testPrepareWindowToDisplayDuringRelayout(false /*wasVisible*/);
        testPrepareWindowToDisplayDuringRelayout(true /*wasVisible*/);
    }

    private void testPrepareWindowToDisplayDuringRelayout(boolean wasVisible) {
        final WindowState root = createWindow(null, TYPE_APPLICATION, "root");
        root.mAttrs.flags |= WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON;
        root.mTurnOnScreen = false;

        root.prepareWindowToDisplayDuringRelayout(new MergedConfiguration(),
                wasVisible /*wasVisible*/);
        assertTrue(root.mTurnOnScreen);
    }
}
