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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Point;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;

import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

import java.util.function.Supplier;

@SmallTest
@Presubmit
public class LetterboxTest {

    Letterbox mLetterbox;
    SurfaceControlMocker mSurfaces;
    SurfaceControl.Transaction mTransaction;

    @Before
    public void setUp() throws Exception {
        mSurfaces = new SurfaceControlMocker();
        mLetterbox = new Letterbox(mSurfaces, StubTransaction::new);
        mTransaction = spy(StubTransaction.class);
    }

    @Test
    public void testOverlappingWith_usesGlobalCoordinates() {
        mLetterbox.layout(new Rect(0, 0, 10, 50), new Rect(0, 2, 10, 45), new Point(1000, 2000));
        assertTrue(mLetterbox.isOverlappingWith(new Rect(0, 0, 1, 1)));
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
        mLetterbox.applySurfaceChanges(mTransaction);
        verify(mTransaction).setPosition(mSurfaces.top, -1000, -2000);
    }

    @Test
    public void testSurfaceOrigin_changeCausesReapply() {
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(1000, 2000));
        mLetterbox.applySurfaceChanges(mTransaction);
        clearInvocations(mTransaction);
        mLetterbox.layout(new Rect(0, 0, 10, 10), new Rect(0, 1, 10, 10), new Point(0, 0));
        assertTrue(mLetterbox.needsApplySurfaceChanges());
        mLetterbox.applySurfaceChanges(mTransaction);
        verify(mTransaction).setPosition(mSurfaces.top, 0, 0);
    }

    class SurfaceControlMocker implements Supplier<SurfaceControl.Builder> {
        private SurfaceControl.Builder mLeftBuilder;
        public SurfaceControl left;
        private SurfaceControl.Builder mTopBuilder;
        public SurfaceControl top;
        private SurfaceControl.Builder mRightBuilder;
        public SurfaceControl right;
        private SurfaceControl.Builder mBottomBuilder;
        public SurfaceControl bottom;

        @Override
        public SurfaceControl.Builder get() {
            final SurfaceControl.Builder builder = mock(SurfaceControl.Builder.class,
                    InvocationOnMock::getMock);
            when(builder.setName(anyString())).then((i) -> {
                if (((String) i.getArgument(0)).contains("left")) {
                    mLeftBuilder = (SurfaceControl.Builder) i.getMock();
                } else if (((String) i.getArgument(0)).contains("top")) {
                    mTopBuilder = (SurfaceControl.Builder) i.getMock();
                } else if (((String) i.getArgument(0)).contains("right")) {
                    mRightBuilder = (SurfaceControl.Builder) i.getMock();
                } else if (((String) i.getArgument(0)).contains("bottom")) {
                    mBottomBuilder = (SurfaceControl.Builder) i.getMock();
                }
                return i.getMock();
            });

            doAnswer((i) -> {
                final SurfaceControl control = mock(SurfaceControl.class);
                if (i.getMock() == mLeftBuilder) {
                    left = control;
                } else if (i.getMock() == mTopBuilder) {
                    top = control;
                } else if (i.getMock() == mRightBuilder) {
                    right = control;
                } else if (i.getMock() == mBottomBuilder) {
                    bottom = control;
                }
                return control;
            }).when(builder).build();
            return builder;
        }
    }
}
