/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.systemui.accessibility;

import static android.view.WindowManager.LayoutParams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.graphics.Point;
import android.testing.AndroidTestingRunner;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class MirrorWindowControlTest extends SysuiTestCase {

    @Mock WindowManager mWindowManager;
    View mView;
    int mViewWidth;
    int mViewHeight;

    StubMirrorWindowControl mStubMirrorWindowControl;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mView = new View(getContext());
        mViewWidth = 10;
        mViewHeight = 20;
        getContext().addMockSystemService(Context.WINDOW_SERVICE, mWindowManager);
        doAnswer(invocation -> {
            View view = invocation.getArgument(0);
            LayoutParams lp = invocation.getArgument(1);
            view.setLayoutParams(lp);
            return null;
        }).when(mWindowManager).addView(any(View.class), any(LayoutParams.class));

        mStubMirrorWindowControl = new StubMirrorWindowControl(getContext(), mView, mViewWidth,
                mViewHeight);
    }

    @Test
    public void showControl_createViewAndAddView() {
        mStubMirrorWindowControl.showControl();

        assertTrue(mStubMirrorWindowControl.mInvokeOnCreateView);
        ArgumentCaptor<ViewGroup.LayoutParams> lpCaptor = ArgumentCaptor.forClass(
                ViewGroup.LayoutParams.class);
        verify(mWindowManager).addView(any(), lpCaptor.capture());
        assertTrue(lpCaptor.getValue().width == mViewWidth);
        assertTrue(lpCaptor.getValue().height == mViewHeight);
    }

    @Test
    public void destroyControl_removeView() {
        mStubMirrorWindowControl.showControl();
        ArgumentCaptor<View> captor = ArgumentCaptor.forClass(View.class);
        verify(mWindowManager).addView(captor.capture(), any(LayoutParams.class));

        mStubMirrorWindowControl.destroyControl();

        verify(mWindowManager).removeView(eq(captor.getValue()));
    }

    @Test
    public void move_offsetIsCorrect() {
        ArgumentCaptor<ViewGroup.LayoutParams> lpCaptor = ArgumentCaptor.forClass(
                ViewGroup.LayoutParams.class);
        mStubMirrorWindowControl.showControl();
        verify(mWindowManager).addView(any(), lpCaptor.capture());
        LayoutParams lp = (LayoutParams) lpCaptor.getValue();
        Point startPosition = new Point(lp.x, lp.y);

        mStubMirrorWindowControl.move(-10, -20);

        verify(mWindowManager).updateViewLayout(eq(mView), lpCaptor.capture());
        assertTrue(lpCaptor.getAllValues().size() == 2);
        lp = (LayoutParams) lpCaptor.getValue();
        Point currentPosition = new Point(lp.x, lp.y);
        assertEquals(-10, currentPosition.x - startPosition.x);
        assertEquals(-20, currentPosition.y - startPosition.y);
    }

    private static class StubMirrorWindowControl extends MirrorWindowControl {
        private final int mWidth;
        private final int mHeight;
        private final View mView;

        boolean mInvokeOnCreateView = false;

        StubMirrorWindowControl(Context context, View view, int width, int height) {
            super(context);
            mView = view;
            mWidth = width;
            mHeight = height;
        }

        @Override
        public String getWindowTitle() {
            return "StubMirrorWindowControl";
        }

        @Override
        View onCreateView(LayoutInflater inflater, Point viewSize) {
            mInvokeOnCreateView = true;
            viewSize.x = mWidth;
            viewSize.y = mHeight;
            return mView;
        }

    }
}
