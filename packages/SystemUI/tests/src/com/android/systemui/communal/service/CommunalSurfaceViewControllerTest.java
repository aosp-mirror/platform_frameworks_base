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

package com.android.systemui.communal.service;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.res.Resources;
import android.graphics.PixelFormat;
import android.graphics.Region;
import android.os.IBinder;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;

import androidx.test.filters.SmallTest;

import com.android.systemui.R;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.communal.CommunalStateController;
import com.android.systemui.statusbar.NotificationShadeWindowController;
import com.android.systemui.util.concurrency.FakeExecutor;
import com.android.systemui.util.time.FakeSystemClock;

import com.google.common.util.concurrent.SettableFuture;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@SmallTest
public class CommunalSurfaceViewControllerTest extends SysuiTestCase {
    private static final int MEASURED_HEIGHT = 200;
    private static final int MEASURED_WIDTH = 500;
    private static final int DISPLAY_ID = 3;
    private static final int SPLIT_NOTIFICATION_STATUS_BAR_HEIGHT = 23;
    private static final int NOTIFICATION_PANEL_MARGIN_TOP = 20;
    private static final int KEYGUARD_INDICATION_BOTTOM_PADDING = 15;

    @Mock
    private Display mDisplay;

    @Mock
    private IBinder mHostToken;

    @Mock
    private SurfaceView mSurfaceView;

    @Mock
    private SurfaceHolder mSurfaceHolder;

    @Mock
    private CommunalSourceImpl mCommunalSource;

    @Mock
    private SurfaceControlViewHost.SurfacePackage mSurfacePackage;

    @Mock
    private CommunalStateController mCommunalStateController;

    @Mock
    private Resources mResources;

    @Mock
    private NotificationShadeWindowController mNotificationShadeWindowController;

    @Mock
    private IBinder mWindowToken;

    private FakeExecutor mFakeExecutor = new FakeExecutor(new FakeSystemClock());

    private SurfaceHolder.Callback mCallback;

    private CommunalSurfaceViewController mController;

    private SettableFuture<SurfaceControlViewHost.SurfacePackage> mPackageFuture;

    private View.OnLayoutChangeListener mLayoutChangeListener;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        when(mSurfaceView.getHolder()).thenReturn(mSurfaceHolder);
        when(mSurfaceView.getDisplay()).thenReturn(mDisplay);
        when(mDisplay.getDisplayId()).thenReturn(DISPLAY_ID);
        when(mSurfaceView.getHostToken()).thenReturn(mHostToken);
        when(mSurfaceView.getWindowToken()).thenReturn(mWindowToken);
        when(mSurfaceView.getMeasuredWidth()).thenReturn(MEASURED_WIDTH);
        when(mSurfaceView.getMeasuredHeight()).thenReturn(MEASURED_HEIGHT);
        when(mSurfaceView.isAttachedToWindow()).thenReturn(false);
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(true);
        when(mResources.getDimensionPixelSize(R.dimen.split_shade_header_height))
                .thenReturn(SPLIT_NOTIFICATION_STATUS_BAR_HEIGHT);
        when(mResources.getDimensionPixelSize(R.dimen.notification_panel_margin_top))
                .thenReturn(NOTIFICATION_PANEL_MARGIN_TOP);
        when(mResources.getDimensionPixelSize(R.dimen.keyguard_indication_bottom_padding))
                .thenReturn(KEYGUARD_INDICATION_BOTTOM_PADDING);
        mController = new CommunalSurfaceViewController(mSurfaceView, mResources, mFakeExecutor,
                mCommunalStateController, mNotificationShadeWindowController, mCommunalSource);
        mController.init();

        final ArgumentCaptor<SurfaceHolder.Callback> callbackCapture =
                ArgumentCaptor.forClass(SurfaceHolder.Callback.class);
        verify(mSurfaceHolder).addCallback(callbackCapture.capture());
        verify(mSurfaceHolder).setFormat(PixelFormat.TRANSPARENT);
        mCallback = callbackCapture.getValue();

        final ArgumentCaptor<View.OnLayoutChangeListener> listenerCapture =
                ArgumentCaptor.forClass(View.OnLayoutChangeListener.class);
        verify(mSurfaceView).addOnLayoutChangeListener(listenerCapture.capture());
        mLayoutChangeListener = listenerCapture.getValue();

        mPackageFuture = SettableFuture.create();

        when(mCommunalSource.requestCommunalSurface(any()))
                .thenReturn(mPackageFuture);
    }

    @Test
    public void testSetSurfacePackage() {
        // There should be no requests without the proper state.
        verify(mCommunalSource, times(0))
                .requestCommunalSurface(any());

        // The full state must be present to make a request.
        mController.onViewAttached();
        verify(mCommunalSource, times(0))
                .requestCommunalSurface(any());

        clearInvocations(mSurfaceView);

        // Request surface view once all conditions are met.
        mCallback.surfaceCreated(mSurfaceHolder);
        final CommunalSourceImpl.Request expectedRequest = new CommunalSourceImpl.Request(
                MEASURED_WIDTH, MEASURED_HEIGHT, DISPLAY_ID, mHostToken);
        verify(mCommunalSource).requestCommunalSurface(eq(expectedRequest));

        when(mSurfaceView.isAttachedToWindow()).thenReturn(true);

        // Respond to request.
        mPackageFuture.set(mSurfacePackage);
        mFakeExecutor.runAllReady();


        // Make sure SurfaceView is set.
        verify(mSurfaceView).setChildSurfacePackage(mSurfacePackage);
        verify(mSurfaceView).setWillNotDraw(false);
    }

    @Test
    public void testCommunalStateControllerShowNotified() {
        // Move CommunalSurfaceView to show
        mController.onViewAttached();
        mCallback.surfaceCreated(mSurfaceHolder);
        when(mSurfaceView.isAttachedToWindow()).thenReturn(true);
        mPackageFuture.set(mSurfacePackage);
        mFakeExecutor.runAllReady();

        // Ensure state controller is informed that the communal view is showing.
        verify(mCommunalStateController).setCommunalViewShowing(true);
    }

    // Invoked to setup surface view package.
    private void givenSurfacePresent() {
        mController.onViewAttached();
        mCallback.surfaceCreated(mSurfaceHolder);
        when(mSurfaceView.isAttachedToWindow()).thenReturn(true);
        mPackageFuture.set(mSurfacePackage);
        mFakeExecutor.runAllReady();
        clearInvocations(mSurfaceView);
    }

    @Test
    public void testClearOnDetach() {
        givenSurfacePresent();
        when(mSurfaceView.isAttachedToWindow()).thenReturn(false);
        mController.onViewDetached();
        verify(mSurfaceView).setWillNotDraw(true);
    }

    @Test
    public void testClearOnSurfaceDestroyed() {
        givenSurfacePresent();
        mCallback.surfaceDestroyed(mSurfaceHolder);
        verify(mSurfaceView).setWillNotDraw(true);
    }

    @Test
    public void testCancelRequest() {
        mController.onViewAttached();
        mCallback.surfaceCreated(mSurfaceHolder);
        when(mSurfaceView.isAttachedToWindow()).thenReturn(true);
        mFakeExecutor.runAllReady();
        clearInvocations(mSurfaceView);

        final CommunalSourceImpl.Request expectedRequest = new CommunalSourceImpl.Request(
                MEASURED_WIDTH, MEASURED_HEIGHT, DISPLAY_ID, mHostToken);
        verify(mCommunalSource, times(1)).requestCommunalSurface(eq(expectedRequest));

        mController.onViewDetached();
        assertTrue(mPackageFuture.isCancelled());
        verify(mSurfaceView).setWillNotDraw(true);
    }

    @Test
    public void testTapExclusion() {
        final int left = 0;
        final int top = 0;
        final int right = 200;
        final int bottom = 100;
        final Region splitNotificationExclusionRegion = new Region(
                left,
                top + SPLIT_NOTIFICATION_STATUS_BAR_HEIGHT,
                right,
                bottom - KEYGUARD_INDICATION_BOTTOM_PADDING);

        final Region notificationExclusionRegion = new Region(
                left,
                top + NOTIFICATION_PANEL_MARGIN_TOP,
                right,
                bottom - KEYGUARD_INDICATION_BOTTOM_PADDING);

        // There should be no exclusion when communal isn't present.
        mLayoutChangeListener.onLayoutChange(mSurfaceView, left, top, right, bottom, 0, 0, 0, 0);
        verify(mNotificationShadeWindowController)
                .setTouchExclusionRegion(eq(new Region()));


        // Attach view
        mController.onViewAttached();
        clearInvocations(mNotificationShadeWindowController);
        // Verify tap exclusion area matches proper dimensions.
        mLayoutChangeListener.onLayoutChange(mSurfaceView, left, top, right, bottom, 0, 0, 0, 0);
        verify(mNotificationShadeWindowController)
                .setTouchExclusionRegion(eq(splitNotificationExclusionRegion));

        // Switch to normal notification margin, verify padding changes.
        clearInvocations(mNotificationShadeWindowController);
        when(mResources.getBoolean(R.bool.config_use_split_notification_shade)).thenReturn(false);
        mLayoutChangeListener.onLayoutChange(mSurfaceView, left, top, right, bottom, 0, 0, 0, 0);
        verify(mNotificationShadeWindowController)
                .setTouchExclusionRegion(eq(notificationExclusionRegion));

        // Occlude, verify no exclude region.
        clearInvocations(mNotificationShadeWindowController);
        when(mCommunalStateController.getCommunalViewOccluded()).thenReturn(true);
        mLayoutChangeListener.onLayoutChange(mSurfaceView, left, top, right, bottom, 0, 0, 0, 0);
        verify(mNotificationShadeWindowController)
                .setTouchExclusionRegion(eq(new Region()));
    }

    @Test
    public void testLayoutChange() {
        final int left = 0;
        final int top = 0;
        final int right = 200;
        final int bottom = 100;

        givenSurfacePresent();

        // Layout change should trigger a request to get new communal surface.
        mLayoutChangeListener.onLayoutChange(mSurfaceView, left, top, right, bottom, 0, 0, 0,
                0);
        // Note that the measured are preset and different than the layout input.
        final CommunalSourceImpl.Request expectedRequest =
                new CommunalSourceImpl.Request(MEASURED_WIDTH, MEASURED_HEIGHT, DISPLAY_ID,
                        mHostToken);
        verify(mCommunalSource)
                .requestCommunalSurface(eq(expectedRequest));

        clearInvocations(mCommunalSource);

        // Subsequent matching layout change should not trigger any request.
        mLayoutChangeListener.onLayoutChange(mSurfaceView, left, top, right, bottom, 0, 0, 0,
                0);
        verify(mCommunalSource, never()).requestCommunalSurface(any());
    }
}
