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

package com.android.server.wm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Color;
import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.DisableFlags;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.annotations.Presubmit;
import android.platform.test.flag.junit.SetFlagsRule;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import com.android.server.testutils.StubTransaction;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.function.Supplier;

/**
 * Test class for {@link Letterbox}.
 * <p>
 * Build/Install/Run:
 * atest WmTests:LetterboxTest
 */
@SmallTest
@Presubmit
public class LetterboxTest {

    @Rule
    public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private Letterbox mLetterbox;
    private SurfaceControlMocker mSurfaces;
    private SurfaceControl.Transaction mTransaction;

    private SurfaceControl mParentSurface = mock(SurfaceControl.class);
    private AppCompatLetterboxOverrides mLetterboxOverrides;

    @Before
    public void setUp() throws Exception {
        mSurfaces = new SurfaceControlMocker();
        mLetterboxOverrides =  mock(AppCompatLetterboxOverrides.class);
        doReturn(false).when(mLetterboxOverrides).shouldLetterboxHaveRoundedCorners();
        doReturn(Color.valueOf(Color.BLACK)).when(mLetterboxOverrides)
                .getLetterboxBackgroundColor();
        doReturn(false).when(mLetterboxOverrides).hasWallpaperBackgroundForLetterbox();
        doReturn(0).when(mLetterboxOverrides).getLetterboxWallpaperBlurRadiusPx();
        doReturn(0.5f).when(mLetterboxOverrides).getLetterboxWallpaperDarkScrimAlpha();
        mLetterbox = new Letterbox(mSurfaces, StubTransaction::new,
                mock(AppCompatReachabilityPolicy.class), mLetterboxOverrides, () -> mParentSurface);
        mTransaction = spy(StubTransaction.class);
    }

    private static final int TOP_BAR = 0x1;
    private static final int BOTTOM_BAR = 0x2;
    private static final int LEFT_BAR = 0x4;
    private static final int RIGHT_BAR = 0x8;

    @Test
    public void testNotIntersectsOrFullyContains_usesGlobalCoordinates() {
        final Rect outer = new Rect(0, 0, 10, 50);
        final Point surfaceOrig = new Point(1000, 2000);

        final Rect topBar = new Rect(0, 0, 10, 2);
        final Rect bottomBar = new Rect(0, 45, 10, 50);
        final Rect leftBar = new Rect(0, 0, 2, 50);
        final Rect rightBar = new Rect(8, 0, 10, 50);

        final LetterboxLayoutVerifier verifier =
                new LetterboxLayoutVerifier(outer, surfaceOrig, mLetterbox);
        verifier.setBarRect(topBar, bottomBar, leftBar, rightBar);

        // top
        verifier.setInner(0, 2, 10, 50).verifyPositions(TOP_BAR | BOTTOM_BAR, BOTTOM_BAR);
        // bottom
        verifier.setInner(0, 0, 10, 45).verifyPositions(TOP_BAR | BOTTOM_BAR, TOP_BAR);
        // left
        verifier.setInner(2, 0, 10, 50).verifyPositions(LEFT_BAR | RIGHT_BAR, RIGHT_BAR);
        // right
        verifier.setInner(0, 0, 8, 50).verifyPositions(LEFT_BAR | RIGHT_BAR, LEFT_BAR);
        // top + bottom
        verifier.setInner(0, 2, 10, 45).verifyPositions(TOP_BAR | BOTTOM_BAR, 0);
        // left + right
        verifier.setInner(2, 0, 8, 50).verifyPositions(LEFT_BAR | RIGHT_BAR, 0);
        // top + left
        verifier.setInner(2, 2, 10, 50).verifyPositions(TOP_BAR | LEFT_BAR, 0);
        // top + left + right
        verifier.setInner(2, 2, 8, 50).verifyPositions(TOP_BAR | LEFT_BAR | RIGHT_BAR, 0);
        // left + right + bottom
        verifier.setInner(2, 0, 8, 45).verifyPositions(LEFT_BAR | RIGHT_BAR | BOTTOM_BAR, 0);
        // all
        verifier.setInner(2, 2, 8, 45)
                .verifyPositions(TOP_BAR | BOTTOM_BAR | LEFT_BAR | RIGHT_BAR, 0);
    }

    private static class LetterboxLayoutVerifier {
        final Rect mOuter;
        final Rect mInner = new Rect();
        final Point mSurfaceOrig;
        final Letterbox mLetterbox;
        final Rect mTempRect = new Rect();

        final Rect mTop = new Rect();
        final Rect mBottom = new Rect();
        final Rect mLeft = new Rect();
        final Rect mRight = new Rect();

        LetterboxLayoutVerifier(Rect outer, Point surfaceOrig, Letterbox letterbox) {
            mOuter = new Rect(outer);
            mSurfaceOrig = new Point(surfaceOrig);
            mLetterbox = letterbox;
        }

        LetterboxLayoutVerifier setInner(int left, int top, int right, int bottom) {
            mInner.set(left, top, right, bottom);
            mLetterbox.layout(mOuter, mInner, mSurfaceOrig);
            return this;
        }

        void setBarRect(Rect top, Rect bottom, Rect left, Rect right) {
            mTop.set(top);
            mBottom.set(bottom);
            mLeft.set(left);
            mRight.set(right);
        }

        void verifyPositions(int allowedPos, int noOverlapPos) {
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mTop),
                    (allowedPos & TOP_BAR) != 0);
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mBottom),
                    (allowedPos & BOTTOM_BAR) != 0);
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mLeft),
                    (allowedPos & LEFT_BAR) != 0);
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mRight),
                    (allowedPos & RIGHT_BAR) != 0);

            mTempRect.set(mTop.left, mTop.top, mTop.right, mTop.bottom + 1);
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mTempRect),
                    (noOverlapPos & TOP_BAR) != 0);
            mTempRect.set(mLeft.left, mLeft.top, mLeft.right + 1, mLeft.bottom);
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mTempRect),
                    (noOverlapPos & LEFT_BAR) != 0);
            mTempRect.set(mRight.left - 1, mRight.top, mRight.right, mRight.bottom);
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mTempRect),
                    (noOverlapPos & RIGHT_BAR) != 0);
            mTempRect.set(mBottom.left, mBottom.top - 1, mBottom.right, mBottom.bottom);
            assertEquals(mLetterbox.notIntersectsOrFullyContains(mTempRect),
                    (noOverlapPos & BOTTOM_BAR) != 0);
        }
    }

    @Test
    public void testSurfaceOrigin_applied() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();
        verify(mTransaction).setPosition(mSurfaces.top, -1000, -2000);
    }

    @DisableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testSurface_created_scrollingFromLetterboxDisabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();
        assertNotNull(mSurfaces.top);
    }

    @DisableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testInputSurface_notCreated_scrollingFromLetterboxDisabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();
        assertNull(mSurfaces.topInput);
    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testSurface_created_scrollingFromLetterboxEnabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();
        assertNotNull(mSurfaces.top);
    }

    @EnableFlags(Flags.FLAG_SCROLLING_FROM_LETTERBOX)
    @Test
    public void testInputSurface_notCreated_notAttachedInputAndScrollingFromLetterboxEnabled() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();
        assertNull(mSurfaces.topInput);
    }

    @Test
    public void testApplySurfaceChanges_setColor() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();

        verify(mTransaction).setColor(mSurfaces.top, new float[]{0, 0, 0});

        doReturn(Color.valueOf(Color.GREEN)).when(mLetterboxOverrides)
                .getLetterboxBackgroundColor();

        assertTrue(mLetterbox.needsApplySurfaceChanges());

        applySurfaceChanges();

        verify(mTransaction).setColor(mSurfaces.top, new float[]{0, 1, 0});
    }

    @Test
    public void testNeedsApplySurfaceChanges_wallpaperBackgroundRequested() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();

        verify(mTransaction).setAlpha(mSurfaces.top, 1.0f);
        assertFalse(mLetterbox.needsApplySurfaceChanges());

        doReturn(true).when(mLetterboxOverrides).hasWallpaperBackgroundForLetterbox();

        assertTrue(mLetterbox.needsApplySurfaceChanges());

        applySurfaceChanges();
        verify(mTransaction).setAlpha(mSurfaces.fullWindowSurface, /* alpha */ 0.5f);
    }

    @Test
    public void testNeedsApplySurfaceChanges_setParentSurface() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();

        verify(mTransaction).reparent(mSurfaces.top, mParentSurface);
        assertFalse(mLetterbox.needsApplySurfaceChanges());

        mParentSurface = mock(SurfaceControl.class);

        assertTrue(mLetterbox.needsApplySurfaceChanges());

        applySurfaceChanges();
        verify(mTransaction).reparent(mSurfaces.top, mParentSurface);
    }

    @Test
    public void testApplySurfaceChanges_cornersNotRounded_surfaceFullWindowSurfaceNotCreated() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();

        assertNull(mSurfaces.fullWindowSurface);
    }

    @Test
    public void testApplySurfaceChanges_cornersRounded_surfaceFullWindowSurfaceCreated() {
        doReturn(true).when(mLetterboxOverrides).shouldLetterboxHaveRoundedCorners();
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();

        assertNotNull(mSurfaces.fullWindowSurface);
    }

    @Test
    public void testApplySurfaceChanges_wallpaperBackground_surfaceFullWindowSurfaceCreated() {
        doReturn(true).when(mLetterboxOverrides).hasWallpaperBackgroundForLetterbox();
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();

        assertNotNull(mSurfaces.fullWindowSurface);
    }

    @Test
    public void testNotIntersectsOrFullyContains_cornersRounded() {
        doReturn(true).when(mLetterboxOverrides).shouldLetterboxHaveRoundedCorners();
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(0, 0));
        applySurfaceChanges();

        assertTrue(mLetterbox.notIntersectsOrFullyContains(new Rect(1, 2, 9, 9)));
    }

    @Test
    public void testSurfaceOrigin_changeCausesReapply() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        applySurfaceChanges();
        clearInvocations(mTransaction);
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(0, 0));
        assertTrue(mLetterbox.needsApplySurfaceChanges());
        applySurfaceChanges();
        verify(mTransaction).setPosition(mSurfaces.top, 0, 0);
    }

    private void applySurfaceChanges() {
        mLetterbox.applySurfaceChanges(/* syncTransaction */ mTransaction,
                /* pendingTransaction */ mTransaction);
    }

    static class SurfaceControlMocker implements Supplier<SurfaceControl.Builder> {
        private SurfaceControl.Builder mTopBuilder;
        public SurfaceControl top;
        private SurfaceControl.Builder mTopInputBuilder;
        public SurfaceControl topInput;
        private SurfaceControl.Builder mFullWindowSurfaceBuilder;
        public SurfaceControl fullWindowSurface;

        @Override
        public SurfaceControl.Builder get() {
            final SurfaceControl.Builder builder = mock(SurfaceControl.Builder.class,
                    InvocationOnMock::getMock);
            when(builder.setName(anyString())).then((i) -> {
                if (((String) i.getArgument(0)).contains("Letterbox - top")) {
                    mTopBuilder = (SurfaceControl.Builder) i.getMock();
                } else if (((String) i.getArgument(0)).contains("Letterbox - fullWindow")) {
                    mFullWindowSurfaceBuilder = (SurfaceControl.Builder) i.getMock();
                } else if (((String) i.getArgument(0)).contains("LetterboxInput - top")) {
                    mTopInputBuilder = (SurfaceControl.Builder) i.getMock();
                }
                return i.getMock();
            });

            doAnswer((i) -> {
                final SurfaceControl control = mock(SurfaceControl.class);
                if (i.getMock() == mTopBuilder) {
                    top = control;
                } else if (i.getMock() == mFullWindowSurfaceBuilder) {
                    fullWindowSurface = control;
                } else if (i.getMock() == mTopInputBuilder) {
                    topInput = control;
                }
                return control;
            }).when(builder).build();
            return builder;
        }
    }
}
