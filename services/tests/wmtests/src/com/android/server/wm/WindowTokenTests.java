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
 * limitations under the License.
 */

package com.android.server.wm;

import static android.view.WindowManager.LayoutParams.FIRST_SUB_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_TOAST;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import android.os.IBinder;
import android.platform.test.annotations.Presubmit;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Tests for the {@link WindowToken} class.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowTokenTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowTokenTests extends WindowTestsBase {

    @Test
    public void testAddWindow() {
        final WindowTestUtils.TestWindowToken token =
                WindowTestUtils.createTestWindowToken(0, mDisplayContent);

        assertEquals(0, token.getWindowsCount());

        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token, "window1");
        final WindowState window11 = createWindow(window1, FIRST_SUB_WINDOW, token, "window11");
        final WindowState window12 = createWindow(window1, FIRST_SUB_WINDOW, token, "window12");
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token, "window2");
        final WindowState window3 = createWindow(null, TYPE_APPLICATION, token, "window3");

        token.addWindow(window1);
        // NOTE: Child windows will not be added to the token as window containers can only
        // contain/reference their direct children.
        token.addWindow(window11);
        token.addWindow(window12);
        token.addWindow(window2);
        token.addWindow(window3);

        // Should not contain the child windows that were added above.
        assertEquals(3, token.getWindowsCount());
        assertTrue(token.hasWindow(window1));
        assertFalse(token.hasWindow(window11));
        assertFalse(token.hasWindow(window12));
        assertTrue(token.hasWindow(window2));
        assertTrue(token.hasWindow(window3));
    }

    @Test
    public void testChildRemoval() {
        final DisplayContent dc = mDisplayContent;
        final WindowTestUtils.TestWindowToken token = WindowTestUtils.createTestWindowToken(0, dc);

        assertEquals(token, dc.getWindowToken(token.token));

        final WindowState window1 = createWindow(null, TYPE_APPLICATION, token, "window1");
        final WindowState window2 = createWindow(null, TYPE_APPLICATION, token, "window2");

        window2.removeImmediately();
        // The token should still be mapped in the display content since it still has a child.
        assertEquals(token, dc.getWindowToken(token.token));

        window1.removeImmediately();
        // The token should have been removed from the display content since it no longer has a
        // child.
        assertEquals(null, dc.getWindowToken(token.token));
    }

    /**
     * Test that a window token isn't orphaned by the system when it is requested to be removed.
     * Tokens should only be removed from the system when all their windows are gone.
     */
    @Test
    public void testTokenRemovalProcess() {
        final WindowTestUtils.TestWindowToken token = WindowTestUtils.createTestWindowToken(
                TYPE_TOAST, mDisplayContent, true /* persistOnEmpty */);

        // Verify that the token is on the display
        assertNotNull(mDisplayContent.getWindowToken(token.token));

        final WindowState window1 = createWindow(null, TYPE_TOAST, token, "window1");
        final WindowState window2 = createWindow(null, TYPE_TOAST, token, "window2");

        mDisplayContent.removeWindowToken(token.token);
        // Verify that the token is no longer mapped on the display
        assertNull(mDisplayContent.getWindowToken(token.token));
        // Verify that the token is still attached to its parent
        assertNotNull(token.getParent());
        // Verify that the token windows are still around.
        assertEquals(2, token.getWindowsCount());

        window1.removeImmediately();
        // Verify that the token is still attached to its parent
        assertNotNull(token.getParent());
        // Verify that the other token window is still around.
        assertEquals(1, token.getWindowsCount());

        window2.removeImmediately();
        // Verify that the token is no-longer attached to its parent
        assertNull(token.getParent());
        // Verify that the token windows are no longer attached to it.
        assertEquals(0, token.getWindowsCount());
    }

    /**
     * Test that {@link WindowToken} constructor parameters is set with expectation.
     */
    @Test
    public void testWindowTokenConstructorSanity() {
        WindowToken token = new WindowToken(mDisplayContent.mWmService, mock(IBinder.class),
                TYPE_TOAST, true /* persistOnEmpty */, mDisplayContent,
                true /* ownerCanManageAppTokens */);
        assertFalse(token.mRoundedCornerOverlay);
        assertFalse(token.mFromClientToken);

        token = new WindowToken(mDisplayContent.mWmService, mock(IBinder.class), TYPE_TOAST,
                true /* persistOnEmpty */, mDisplayContent, true /* ownerCanManageAppTokens */,
                true /* roundedCornerOverlay */);
        assertTrue(token.mRoundedCornerOverlay);
        assertFalse(token.mFromClientToken);

        token = new WindowToken(mDisplayContent.mWmService, mock(IBinder.class), TYPE_TOAST,
                true /* persistOnEmpty */, mDisplayContent, true /* ownerCanManageAppTokens */,
                true /* roundedCornerOverlay */, true /* fromClientToken */);
        assertTrue(token.mRoundedCornerOverlay);
        assertTrue(token.mFromClientToken);
    }
}
