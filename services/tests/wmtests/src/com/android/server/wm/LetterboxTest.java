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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
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
        mLetterbox = new Letterbox(mSurfaces, () -> mock(SurfaceControl.Transaction.class));
        mTransaction = mock(SurfaceControl.Transaction.class);
    }

    @Test
    public void testOverlappingWith_usesGlobalCoordinates() {
        mLetterbox.layout(new Rect(0, 0, 10, 50), new Rect(0, 2, 10, 45), new Point(1000, 2000));
        assertTrue(mLetterbox.isOverlappingWith(new Rect(0, 0, 1, 1)));
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
