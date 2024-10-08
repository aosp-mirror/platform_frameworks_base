/*
 * Copyright 2024 The Android Open Source Project
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

import static com.android.server.testutils.MockitoUtilsKt.eq;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.InputConfig;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.view.InputWindowHandle;
import android.view.SurfaceControl;
import android.view.WindowManager;

import com.android.server.testutils.StubTransaction;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;

/**
 * Test class for {@link Letterbox}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:LetterboxAttachInputTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class LetterboxAttachInputTest extends WindowTestsBase {

    private Letterbox mLetterbox;
    private LetterboxTest.SurfaceControlMocker mSurfaces;

    @Before
    public void setUp() throws Exception {
        mSurfaces = new LetterboxTest.SurfaceControlMocker();
        AppCompatLetterboxOverrides letterboxOverrides = mock(AppCompatLetterboxOverrides.class);
        doReturn(false).when(letterboxOverrides).shouldLetterboxHaveRoundedCorners();
        doReturn(Color.valueOf(Color.BLACK)).when(letterboxOverrides)
                .getLetterboxBackgroundColor();
        doReturn(false).when(letterboxOverrides).hasWallpaperBackgroundForLetterbox();
        doReturn(0).when(letterboxOverrides).getLetterboxWallpaperBlurRadiusPx();
        doReturn(0.5f).when(letterboxOverrides).getLetterboxWallpaperDarkScrimAlpha();
        mLetterbox = new Letterbox(mSurfaces, StubTransaction::new,
                mock(AppCompatReachabilityPolicy.class), letterboxOverrides,
                () -> mock(SurfaceControl.class));
        mTransaction = spy(StubTransaction.class);
    }

    @DisableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testSurface_createdHasSlipperyInput_scrollingFromLetterboxDisabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));

        attachInput();
        applySurfaceChanges();

        assertNotNull(mSurfaces.top);
        ArgumentCaptor<InputWindowHandle> handleCaptor =
                ArgumentCaptor.forClass(InputWindowHandle.class);
        verify(mTransaction).setInputWindowInfo(eq(mSurfaces.top), handleCaptor.capture());
        InputWindowHandle capturedHandle = handleCaptor.getValue();
        assertTrue((capturedHandle.inputConfig & InputConfig.SLIPPERY) != 0);
        assertFalse((capturedHandle.inputConfig & InputConfig.SPY) != 0);
    }

    @DisableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testInputSurface_notCreated_scrollingFromLetterboxDisabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));

        attachInput();
        applySurfaceChanges();

        assertNull(mSurfaces.topInput);
    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testSurface_createdHasNoInput_scrollingFromLetterboxEnabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));

        attachInput();
        applySurfaceChanges();

        assertNotNull(mSurfaces.top);
        verify(mTransaction, never()).setInputWindowInfo(eq(mSurfaces.top), any());

    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testInputSurface_createdHasSpyInput_scrollingFromLetterboxEnabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));

        attachInput();
        applySurfaceChanges();

        assertNotNull(mSurfaces.topInput);
        ArgumentCaptor<InputWindowHandle> handleCaptor =
                ArgumentCaptor.forClass(InputWindowHandle.class);
        verify(mTransaction).setInputWindowInfo(eq(mSurfaces.topInput), handleCaptor.capture());
        InputWindowHandle capturedHandle = handleCaptor.getValue();
        assertTrue((capturedHandle.inputConfig & InputConfig.SPY) != 0);
        assertFalse((capturedHandle.inputConfig & InputConfig.SLIPPERY) != 0);
    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testInputSurfaceOrigin_applied_scrollingFromLetterboxEnabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));

        attachInput();
        applySurfaceChanges();

        verify(mTransaction).setPosition(mSurfaces.topInput, -1000, -2000);
    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testInputSurfaceOrigin_changeCausesReapply_scrollingFromLetterboxEnabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));

        attachInput();
        applySurfaceChanges();
        clearInvocations(mTransaction);
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(0, 0));

        assertTrue(mLetterbox.needsApplySurfaceChanges());

        applySurfaceChanges();

        verify(mTransaction).setPosition(mSurfaces.topInput, 0, 0);
    }

    private void applySurfaceChanges() {
        mLetterbox.applySurfaceChanges(/* syncTransaction */ mTransaction,
                /* pendingTransaction */ mTransaction);
    }

    private void attachInput() {
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams();
        final WindowToken windowToken = createTestWindowToken(0, mDisplayContent);
        WindowState windowState = createWindowState(attrs, windowToken);
        mLetterbox.attachInput(windowState);
    }
}
