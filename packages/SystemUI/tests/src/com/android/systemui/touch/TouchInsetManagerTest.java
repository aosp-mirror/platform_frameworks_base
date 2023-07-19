/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.systemui.touch;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.graphics.Rect;
import android.graphics.Region;
import android.testing.AndroidTestingRunner;
import android.view.View;
import android.view.ViewRootImpl;

import androidx.test.filters.SmallTest;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

@SmallTest
@RunWith(AndroidTestingRunner.class)
public class TouchInsetManagerTest extends SysuiTestCase {
    @Mock
    private View mRootView;

    @Mock
    private ViewRootImpl mRootViewImpl;

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mRootView.getViewRootImpl()).thenReturn(mRootViewImpl);
    }

    @Test
    public void testRootViewOnAttachedHandling() {
        // Create inset manager
        final TouchInsetManager insetManager = new TouchInsetManager(mFakeExecutor,
                mRootView);

        final ArgumentCaptor<View.OnAttachStateChangeListener> listener =
                ArgumentCaptor.forClass(View.OnAttachStateChangeListener.class);

        // Ensure manager has registered to listen to attached state of root view.
        verify(mRootView).addOnAttachStateChangeListener(listener.capture());

        // Trigger attachment and verify touchable region is set.
        listener.getValue().onViewAttachedToWindow(mRootView);
        verify(mRootViewImpl).setTouchableRegion(any());
    }

    @Test
    public void testInsetRegionPropagation() {
        // Create inset manager
        final TouchInsetManager insetManager = new TouchInsetManager(mFakeExecutor,
                mRootView);

        // Create session
        final TouchInsetManager.TouchInsetSession session = insetManager.createSession();

        // Add a view to the session.
        final Rect rect = new Rect(0, 0, 2, 2);

        session.addViewToTracking(createView(rect));
        mFakeExecutor.runAllReady();

        // Check to see if view was properly accounted for.
        final Region expectedRegion = Region.obtain();
        expectedRegion.op(rect, Region.Op.UNION);
        verify(mRootViewImpl).setTouchableRegion(eq(expectedRegion));
    }

    @Test
    public void testMultipleRegions() {
        // Create inset manager
        final TouchInsetManager insetManager = new TouchInsetManager(mFakeExecutor,
                mRootView);

        // Create session
        final TouchInsetManager.TouchInsetSession session = insetManager.createSession();

        // Add a view to the session.
        final Rect firstBounds = new Rect(0, 0, 2, 2);
        session.addViewToTracking(createView(firstBounds));

        mFakeExecutor.runAllReady();
        clearInvocations(mRootViewImpl);

        // Create second session
        final TouchInsetManager.TouchInsetSession secondSession = insetManager.createSession();

        // Add a view to the second session.
        final Rect secondBounds = new Rect(4, 4, 8, 10);
        secondSession.addViewToTracking(createView(secondBounds));

        mFakeExecutor.runAllReady();

        // Check to see if all views and sessions was properly accounted for.
        {
            final Region expectedRegion = Region.obtain();
            expectedRegion.op(firstBounds, Region.Op.UNION);
            expectedRegion.op(secondBounds, Region.Op.UNION);
            verify(mRootViewImpl).setTouchableRegion(eq(expectedRegion));
        }


        clearInvocations(mRootViewImpl);

        // clear first session, ensure second session is still reflected.
        session.clear();
        mFakeExecutor.runAllReady();
        {
            final Region expectedRegion = Region.obtain();
            expectedRegion.op(firstBounds, Region.Op.UNION);
            verify(mRootViewImpl).setTouchableRegion(eq(expectedRegion));
        }
    }

    @Test
    public void testMultipleViews() {
        // Create inset manager
        final TouchInsetManager insetManager = new TouchInsetManager(mFakeExecutor,
                mRootView);

        // Create session
        final TouchInsetManager.TouchInsetSession session = insetManager.createSession();

        // Add a view to the session.
        final Rect firstViewBounds = new Rect(0, 0, 2, 2);
        session.addViewToTracking(createView(firstViewBounds));

        // only capture second invocation.
        mFakeExecutor.runAllReady();
        clearInvocations(mRootViewImpl);

        // Add a second view to the session
        final Rect secondViewBounds = new Rect(4, 4, 9, 10);
        final View secondView = createView(secondViewBounds);
        session.addViewToTracking(secondView);

        mFakeExecutor.runAllReady();

        // Check to see if all views and sessions was properly accounted for.
        {
            final Region expectedRegion = Region.obtain();
            expectedRegion.op(firstViewBounds, Region.Op.UNION);
            expectedRegion.op(secondViewBounds, Region.Op.UNION);
            verify(mRootViewImpl).setTouchableRegion(eq(expectedRegion));
        }

        // Remove second view.
        session.removeViewFromTracking(secondView);

        clearInvocations(mRootViewImpl);
        mFakeExecutor.runAllReady();

        // Ensure first view still reflected in touch region.
        {
            final Region expectedRegion = Region.obtain();
            expectedRegion.op(firstViewBounds, Region.Op.UNION);
            verify(mRootViewImpl).setTouchableRegion(eq(expectedRegion));
        }
    }

    private View createView(Rect bounds) {
        final Rect rect = new Rect(bounds);
        final View view = Mockito.mock(View.class);
        doAnswer(invocation -> {
            ((Rect) invocation.getArgument(0)).set(rect);
            return null;
        }).when(view).getBoundsOnScreen(any());

        return view;
    }
}
