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

package com.android.systemui.car.window;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class OverlayViewControllerTest extends SysuiTestCase {
    private TestOverlayViewController mOverlayViewController;
    private ViewGroup mBaseLayout;

    @Mock
    private OverlayViewGlobalStateController mOverlayViewGlobalStateController;

    @Captor
    private ArgumentCaptor<Runnable> mRunnableArgumentCaptor;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(/* testClass= */ this);

        mOverlayViewController = new TestOverlayViewController(R.id.overlay_view_controller_stub,
                mOverlayViewGlobalStateController);

        mBaseLayout = (ViewGroup) LayoutInflater.from(mContext).inflate(
                R.layout.overlay_view_controller_test, /* root= */ null);
    }

    @Test
    public void inflate_layoutInitialized() {
        mOverlayViewController.inflate(mBaseLayout);

        assertThat(mOverlayViewController.getLayout().getId()).isEqualTo(
                R.id.overlay_view_controller_test);
    }

    @Test
    public void inflate_onFinishInflateCalled() {
        mOverlayViewController.inflate(mBaseLayout);

        assertThat(mOverlayViewController.mOnFinishInflateCalled).isTrue();
    }

    @Test
    public void start_viewInflated_viewShown() {
        mOverlayViewController.inflate(mBaseLayout);

        mOverlayViewController.start();

        verify(mOverlayViewGlobalStateController).showView(eq(mOverlayViewController),
                mRunnableArgumentCaptor.capture());

        mRunnableArgumentCaptor.getValue().run();

        assertThat(mOverlayViewController.mShowInternalCalled).isTrue();
    }

    @Test
    public void stop_viewInflated_viewHidden() {
        mOverlayViewController.inflate(mBaseLayout);

        mOverlayViewController.stop();

        verify(mOverlayViewGlobalStateController).hideView(eq(mOverlayViewController),
                mRunnableArgumentCaptor.capture());

        mRunnableArgumentCaptor.getValue().run();

        assertThat(mOverlayViewController.mHideInternalCalled).isTrue();
    }

    @Test
    public void start_viewNotInflated_viewNotShown() {
        mOverlayViewController.start();

        verify(mOverlayViewGlobalStateController).showView(eq(mOverlayViewController),
                mRunnableArgumentCaptor.capture());

        mRunnableArgumentCaptor.getValue().run();

        assertThat(mOverlayViewController.mShowInternalCalled).isFalse();
    }

    @Test
    public void stop_viewNotInflated_viewNotHidden() {
        mOverlayViewController.stop();

        verify(mOverlayViewGlobalStateController).hideView(eq(mOverlayViewController),
                mRunnableArgumentCaptor.capture());

        mRunnableArgumentCaptor.getValue().run();

        assertThat(mOverlayViewController.mHideInternalCalled).isFalse();
    }

    private static class TestOverlayViewController extends OverlayViewController {
        boolean mOnFinishInflateCalled = false;
        boolean mShowInternalCalled = false;
        boolean mHideInternalCalled = false;

        TestOverlayViewController(int stubId,
                OverlayViewGlobalStateController overlayViewGlobalStateController) {
            super(stubId, overlayViewGlobalStateController);
        }

        @Override
        protected void onFinishInflate() {
            mOnFinishInflateCalled = true;
        }

        @Override
        protected void showInternal() {
            mShowInternalCalled = true;
        }

        @Override
        protected void hideInternal() {
            mHideInternalCalled = true;
        }
    }
}
