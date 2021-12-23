/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.systemui.dreams;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import androidx.constraintlayout.widget.ConstraintLayout;
import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class DreamOverlayContainerViewControllerTest extends SysuiTestCase {
    private static final int DREAM_OVERLAY_NOTIFICATIONS_DRAG_AREA_HEIGHT = 100;

    @Mock
    Resources mResources;

    @Mock
    ViewTreeObserver mViewTreeObserver;

    @Mock
    DreamOverlayStatusBarViewController mDreamOverlayStatusBarViewController;

    @Mock
    DreamOverlayContainerView mDreamOverlayContainerView;

    @Mock
    ViewGroup mDreamOverlayContentView;

    DreamOverlayContainerViewController mController;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mResources.getDimensionPixelSize(
                R.dimen.dream_overlay_notifications_drag_area_height)).thenReturn(
                        DREAM_OVERLAY_NOTIFICATIONS_DRAG_AREA_HEIGHT);
        when(mDreamOverlayContainerView.getResources()).thenReturn(mResources);
        when(mDreamOverlayContainerView.getViewTreeObserver()).thenReturn(mViewTreeObserver);

        mController = new DreamOverlayContainerViewController(
                mDreamOverlayContainerView, mDreamOverlayContentView,
                mDreamOverlayStatusBarViewController);
    }

    @Test
    public void testDreamOverlayStatusBarViewControllerInitialized() {
        mController.init();
        verify(mDreamOverlayStatusBarViewController).init();
    }

    @Test
    public void testSetsDreamOverlayNotificationsDragAreaHeight() {
        assertEquals(
                mController.getDreamOverlayNotificationsDragAreaHeight(),
                DREAM_OVERLAY_NOTIFICATIONS_DRAG_AREA_HEIGHT);
    }

    @Test
    public void testAddOverlayAddsOverlayToContentView() {
        View overlay = new View(getContext());
        ConstraintLayout.LayoutParams layoutParams = new ConstraintLayout.LayoutParams(100, 100);
        mController.addOverlay(overlay, layoutParams);
        verify(mDreamOverlayContentView).addView(overlay, layoutParams);
    }

    @Test
    public void testRemoveAllOverlaysRemovesOverlaysFromContentView() {
        mController.removeAllOverlays();
        verify(mDreamOverlayContentView).removeAllViews();
    }

    @Test
    public void testOnViewAttachedRegistersComputeInsetsListener() {
        mController.onViewAttached();
        verify(mViewTreeObserver).addOnComputeInternalInsetsListener(any());
    }

    @Test
    public void testOnViewDetachedUnregistersComputeInsetsListener() {
        mController.onViewDetached();
        verify(mViewTreeObserver).removeOnComputeInternalInsetsListener(any());
    }

    @Test
    public void testComputeInsetsListenerReturnsRegion() {
        final ArgumentCaptor<ViewTreeObserver.OnComputeInternalInsetsListener>
                computeInsetsListenerCapture =
                ArgumentCaptor.forClass(ViewTreeObserver.OnComputeInternalInsetsListener.class);
        mController.onViewAttached();
        verify(mViewTreeObserver).addOnComputeInternalInsetsListener(
                computeInsetsListenerCapture.capture());
        final ViewTreeObserver.InternalInsetsInfo info = new ViewTreeObserver.InternalInsetsInfo();
        computeInsetsListenerCapture.getValue().onComputeInternalInsets(info);
        assertNotNull(info.touchableRegion);
    }
}
