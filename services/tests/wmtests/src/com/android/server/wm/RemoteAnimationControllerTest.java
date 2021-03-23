/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_NAVIGATION_BAR;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER;
import static android.view.WindowManager.TRANSIT_OLD_NONE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.atLeast;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION;

import static junit.framework.Assert.fail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;

import android.graphics.Point;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.os.IInterface;
import android.platform.test.annotations.Presubmit;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceControl.Transaction;

import androidx.test.filters.SmallTest;

import com.android.server.testutils.OffsettableClock;
import com.android.server.testutils.TestHandler;
import com.android.server.wm.RemoteAnimationController.RemoteAnimationRecord;
import com.android.server.wm.SurfaceAnimator.OnAnimationFinishedCallback;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/**
 * Build/Install/Run:
 *  atest WmTests:RemoteAnimationControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class RemoteAnimationControllerTest extends WindowTestsBase {

    @Mock SurfaceControl mMockLeash;
    @Mock SurfaceControl mMockThumbnailLeash;
    @Mock Transaction mMockTransaction;
    @Mock OnAnimationFinishedCallback mFinishedCallback;
    @Mock OnAnimationFinishedCallback mThumbnailFinishedCallback;
    @Mock IRemoteAnimationRunner mMockRunner;
    private RemoteAnimationAdapter mAdapter;
    private RemoteAnimationController mController;
    private final OffsettableClock mClock = new OffsettableClock.Stopped();
    private TestHandler mHandler;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        when(mMockRunner.asBinder()).thenReturn(new Binder());
        mAdapter = new RemoteAnimationAdapter(mMockRunner, 100, 50, true /* changeNeedsSnapshot */);
        mAdapter.setCallingPidUid(123, 456);
        runWithScissors(mWm.mH, () -> mHandler = new TestHandler(null, mClock), 0);
        mController = new RemoteAnimationController(mWm, mDisplayContent, mAdapter, mHandler);
    }

    private WindowState createAppOverlayWindow() {
        final WindowState win = createWindow(null /* parent */, TYPE_APPLICATION_OVERLAY,
                "testOverlayWindow");
        win.mActivityRecord = null;
        win.mHasSurface = true;
        return win;
    }

    @Test
    public void testRun() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mOpeningApps.add(win.mActivityRecord);
        final WindowState overlayWin = createAppOverlayWindow();
        try {
            final AnimationAdapter adapter = mController.createRemoteAnimationRecord(win.mActivityRecord,
                    new Point(50, 100), null, new Rect(50, 100, 150, 150), null).mAdapter;
            adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                    mFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);
            mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_ACTIVITY_OPEN),
                    appsCaptor.capture(), wallpapersCaptor.capture(), nonAppsCaptor.capture(),
                    finishedCaptor.capture());
            assertEquals(1, appsCaptor.getValue().length);
            final RemoteAnimationTarget app = appsCaptor.getValue()[0];
            assertEquals(new Point(50, 100), app.position);
            assertEquals(new Rect(50, 100, 150, 150), app.sourceContainerBounds);
            assertEquals(win.mActivityRecord.getPrefixOrderIndex(), app.prefixOrderIndex);
            assertEquals(win.mActivityRecord.getTask().mTaskId, app.taskId);
            assertEquals(mMockLeash, app.leash);
            assertEquals(false, app.isTranslucent);
            verify(mMockTransaction).setPosition(mMockLeash, app.position.x, app.position.y);
            verify(mMockTransaction).setWindowCrop(mMockLeash, 100, 50);

            finishedCaptor.getValue().onAnimationFinished();
            verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_APP_TRANSITION),
                    eq(adapter));
            assertEquals(0, nonAppsCaptor.getValue().length);
        } finally {
            mDisplayContent.mOpeningApps.clear();
        }
    }

    @Test
    public void testCancel() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        final AnimationAdapter adapter = mController.createRemoteAnimationRecord(win.mActivityRecord,
                new Point(50, 100), null, new Rect(50, 100, 150, 150), null).mAdapter;
        adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                mFinishedCallback);
        mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);

        adapter.onAnimationCancelled(mMockLeash);
        verify(mMockRunner).onAnimationCancelled();
    }

    @Test
    public void testTimeout() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        final AnimationAdapter adapter = mController.createRemoteAnimationRecord(win.mActivityRecord,
                new Point(50, 100), null, new Rect(50, 100, 150, 150), null).mAdapter;
        adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                mFinishedCallback);
        mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);

        mClock.fastForward(2500);
        mHandler.timeAdvance();

        verify(mMockRunner).onAnimationCancelled();
        verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_APP_TRANSITION),
                eq(adapter));
    }

    @Test
    public void testTimeout_scaled() throws Exception {
        try {
            mWm.setAnimationScale(2, 5.0f);
            final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                    "testWin");
            final AnimationAdapter adapter = mController.createRemoteAnimationRecord(
                    win.mActivityRecord, new Point(50, 100), null, new Rect(50, 100, 150, 150),
                    null).mAdapter;
            adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                    mFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);

            mClock.fastForward(2500);
            mHandler.timeAdvance();

            verify(mMockRunner, never()).onAnimationCancelled();

            mClock.fastForward(10000);
            mHandler.timeAdvance();

            verify(mMockRunner).onAnimationCancelled();
            verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_APP_TRANSITION),
                    eq(adapter));
        } finally {
            mWm.setAnimationScale(2, 1.0f);
        }
    }

    @Test
    public void testZeroAnimations() throws Exception {
        mController.goodToGo(TRANSIT_OLD_NONE);
        verify(mMockRunner, never()).onAnimationStart(anyInt(), any(), any(), any(), any());
        verify(mMockRunner).onAnimationCancelled();
    }

    @Test
    public void testNotReallyStarted() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mController.createRemoteAnimationRecord(win.mActivityRecord,
                new Point(50, 100), null, new Rect(50, 100, 150, 150), null);
        mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);
        verify(mMockRunner, never()).onAnimationStart(anyInt(), any(), any(), any(), any());
        verify(mMockRunner).onAnimationCancelled();
    }

    @Test
    public void testOneNotStarted() throws Exception {
        final WindowState win1 = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin1");
        final WindowState win2 = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin2");
        mController.createRemoteAnimationRecord(win1.mActivityRecord,
                new Point(50, 100), null, new Rect(50, 100, 150, 150), null);
        final AnimationAdapter adapter = mController.createRemoteAnimationRecord(win2.mActivityRecord,
                new Point(50, 100), null, new Rect(50, 100, 150, 150), null).mAdapter;
        adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                mFinishedCallback);
        mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);
        mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
        final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
        final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
        final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
        final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
        verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_ACTIVITY_OPEN),
                appsCaptor.capture(), wallpapersCaptor.capture(), nonAppsCaptor.capture(),
                finishedCaptor.capture());
        assertEquals(1, appsCaptor.getValue().length);
        assertEquals(mMockLeash, appsCaptor.getValue()[0].leash);
    }

    @Test
    public void testRemovedBeforeStarted() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        final AnimationAdapter adapter = mController.createRemoteAnimationRecord(win.mActivityRecord,
                new Point(50, 100), null, new Rect(50, 100, 150, 150), null).mAdapter;
        adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                mFinishedCallback);
        win.mActivityRecord.removeImmediately();
        mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);
        verify(mMockRunner, never()).onAnimationStart(anyInt(), any(), any(), any(), any());
        verify(mMockRunner).onAnimationCancelled();
        verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_APP_TRANSITION),
                eq(adapter));
    }

    @Test
    public void testChangeToSmallerSize() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mChangingContainers.add(win.mActivityRecord);
        try {
            final RemoteAnimationRecord record = mController.createRemoteAnimationRecord(
                    win.mActivityRecord, new Point(50, 100), null, new Rect(50, 100, 150, 150),
                    new Rect(0, 0, 200, 200));
            assertNotNull(record.mThumbnailAdapter);
            ((AnimationAdapter) record.mAdapter)
                    .startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_WINDOW_ANIMATION,
                            mFinishedCallback);
            ((AnimationAdapter) record.mThumbnailAdapter).startAnimation(mMockThumbnailLeash,
                    mMockTransaction, ANIMATION_TYPE_WINDOW_ANIMATION, mThumbnailFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE);
            mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE),
                    appsCaptor.capture(), wallpapersCaptor.capture(), nonAppsCaptor.capture(),
                    finishedCaptor.capture());
            assertEquals(1, appsCaptor.getValue().length);
            final RemoteAnimationTarget app = appsCaptor.getValue()[0];
            assertEquals(RemoteAnimationTarget.MODE_CHANGING, app.mode);
            assertEquals(new Point(50, 100), app.position);
            assertEquals(new Rect(50, 100, 150, 150), app.sourceContainerBounds);
            assertEquals(new Rect(0, 0, 200, 200), app.startBounds);
            assertEquals(mMockLeash, app.leash);
            assertEquals(mMockThumbnailLeash, app.startLeash);
            assertEquals(false, app.isTranslucent);
            verify(mMockTransaction).setPosition(
                    mMockLeash, app.startBounds.left, app.startBounds.top);
            verify(mMockTransaction).setWindowCrop(
                    mMockLeash, app.startBounds.width(), app.startBounds.height());
            verify(mMockTransaction).setPosition(mMockThumbnailLeash, 0, 0);
            verify(mMockTransaction).setWindowCrop(mMockThumbnailLeash, -1, -1);

            finishedCaptor.getValue().onAnimationFinished();
            verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_WINDOW_ANIMATION),
                    eq(record.mAdapter));
            verify(mThumbnailFinishedCallback).onAnimationFinished(
                    eq(ANIMATION_TYPE_WINDOW_ANIMATION), eq(record.mThumbnailAdapter));
        } finally {
            mDisplayContent.mChangingContainers.clear();
        }
    }

    @Test
    public void testChangeTolargerSize() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mChangingContainers.add(win.mActivityRecord);
        try {
            final RemoteAnimationRecord record = mController.createRemoteAnimationRecord(
                    win.mActivityRecord, new Point(0, 0), null, new Rect(0, 0, 200, 200),
                    new Rect(50, 100, 150, 150));
            assertNotNull(record.mThumbnailAdapter);
            ((AnimationAdapter) record.mAdapter)
                    .startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_WINDOW_ANIMATION,
                            mFinishedCallback);
            ((AnimationAdapter) record.mThumbnailAdapter).startAnimation(mMockThumbnailLeash,
                    mMockTransaction, ANIMATION_TYPE_WINDOW_ANIMATION, mThumbnailFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE);
            mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE),
                    appsCaptor.capture(), wallpapersCaptor.capture(), nonAppsCaptor.capture(),
                    finishedCaptor.capture());
            assertEquals(1, appsCaptor.getValue().length);
            final RemoteAnimationTarget app = appsCaptor.getValue()[0];
            assertEquals(RemoteAnimationTarget.MODE_CHANGING, app.mode);
            assertEquals(new Point(0, 0), app.position);
            assertEquals(new Rect(0, 0, 200, 200), app.sourceContainerBounds);
            assertEquals(new Rect(50, 100, 150, 150), app.startBounds);
            assertEquals(mMockLeash, app.leash);
            assertEquals(mMockThumbnailLeash, app.startLeash);
            assertEquals(false, app.isTranslucent);
            verify(mMockTransaction).setPosition(
                    mMockLeash, app.startBounds.left, app.startBounds.top);
            verify(mMockTransaction).setWindowCrop(
                    mMockLeash, app.startBounds.width(), app.startBounds.height());
            verify(mMockTransaction).setPosition(mMockThumbnailLeash, 0, 0);
            verify(mMockTransaction).setWindowCrop(mMockThumbnailLeash, -1, -1);

            finishedCaptor.getValue().onAnimationFinished();
            verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_WINDOW_ANIMATION),
                    eq(record.mAdapter));
            verify(mThumbnailFinishedCallback).onAnimationFinished(
                    eq(ANIMATION_TYPE_WINDOW_ANIMATION), eq(record.mThumbnailAdapter));
        } finally {
            mDisplayContent.mChangingContainers.clear();
        }
    }

    @Test
    public void testWallpaperIncluded_expectTarget() throws Exception {
        final WindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true, mDisplayContent, true /* ownerCanManageAppTokens */);
        spyOn(mDisplayContent.mWallpaperController);
        doReturn(true).when(mDisplayContent.mWallpaperController).isWallpaperVisible();
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mOpeningApps.add(win.mActivityRecord);
        try {
            final AnimationAdapter adapter = mController.createRemoteAnimationRecord(win.mActivityRecord,
                    new Point(50, 100), null, new Rect(50, 100, 150, 150), null).mAdapter;
            adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                    mFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);
            mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_ACTIVITY_OPEN),
                    appsCaptor.capture(), wallpapersCaptor.capture(), nonAppsCaptor.capture(),
                    finishedCaptor.capture());
            assertEquals(1, wallpapersCaptor.getValue().length);
        } finally {
            mDisplayContent.mOpeningApps.clear();
        }
    }

    @Test
    public void testWallpaperAnimatorCanceled_expectAnimationKeepsRunning() throws Exception {
        final WindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true, mDisplayContent, true /* ownerCanManageAppTokens */);
        spyOn(mDisplayContent.mWallpaperController);
        doReturn(true).when(mDisplayContent.mWallpaperController).isWallpaperVisible();
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mOpeningApps.add(win.mActivityRecord);
        try {
            final AnimationAdapter adapter = mController.createRemoteAnimationRecord(win.mActivityRecord,
                    new Point(50, 100), null, new Rect(50, 100, 150, 150), null).mAdapter;
            adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                    mFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_ACTIVITY_OPEN);
            mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> nonAPpsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_ACTIVITY_OPEN),
                    appsCaptor.capture(), wallpapersCaptor.capture(), nonAPpsCaptor.capture(),
                    finishedCaptor.capture());
            assertEquals(1, wallpapersCaptor.getValue().length);

            // Cancel the wallpaper window animator and ensure the runner is not canceled
            wallpaperWindowToken.cancelAnimation();
            verify(mMockRunner, never()).onAnimationCancelled();
        } finally {
            mDisplayContent.mOpeningApps.clear();
        }
    }

    @Test
    public void testNonAppIncluded_keygaurdGoingAway() throws Exception {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mOpeningApps.add(win.mActivityRecord);
        // Add overlay window hidden by the keyguard.
        final WindowState overlayWin = createAppOverlayWindow();
        overlayWin.hide(false /* doAnimation */, false /* requestAnim */);
        try {
            final AnimationAdapter adapter = mController.createRemoteAnimationRecord(
                    win.mActivityRecord, new Point(50, 100), null,
                    new Rect(50, 100, 150, 150), null).mAdapter;
            adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                    mFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_KEYGUARD_GOING_AWAY);
            mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_KEYGUARD_GOING_AWAY),
                    appsCaptor.capture(), wallpapersCaptor.capture(), nonAppsCaptor.capture(),
                    finishedCaptor.capture());
            assertEquals(1, appsCaptor.getValue().length);
            final RemoteAnimationTarget app = appsCaptor.getValue()[0];
            assertEquals(new Point(50, 100), app.position);
            assertEquals(new Rect(50, 100, 150, 150), app.sourceContainerBounds);
            assertEquals(win.mActivityRecord.getPrefixOrderIndex(), app.prefixOrderIndex);
            assertEquals(win.mActivityRecord.getTask().mTaskId, app.taskId);
            assertEquals(mMockLeash, app.leash);
            assertEquals(false, app.isTranslucent);
            verify(mMockTransaction).setPosition(mMockLeash, app.position.x, app.position.y);
            verify(mMockTransaction).setWindowCrop(mMockLeash, 100, 50);

            finishedCaptor.getValue().onAnimationFinished();
            verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_APP_TRANSITION),
                    eq(adapter));
            assertEquals(1, nonAppsCaptor.getValue().length);
        } finally {
            mDisplayContent.mOpeningApps.clear();
        }
    }

    @Test
    public void testNonAppIncluded_keygaurdGoingAwayToWallpaper() throws Exception {
        final WindowToken wallpaperWindowToken = new WallpaperWindowToken(mWm, mock(IBinder.class),
                true, mDisplayContent, true /* ownerCanManageAppTokens */);
        spyOn(mDisplayContent.mWallpaperController);
        doReturn(true).when(mDisplayContent.mWallpaperController).isWallpaperVisible();
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mOpeningApps.add(win.mActivityRecord);
        // Add overlay window hidden by the keyguard.
        final WindowState overlayWin = createAppOverlayWindow();
        overlayWin.hide(false /* doAnimation */, false /* requestAnim */);
        try {
            final AnimationAdapter adapter = mController.createRemoteAnimationRecord(
                    win.mActivityRecord, new Point(50, 100), null,
                    new Rect(50, 100, 150, 150), null).mAdapter;
            adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                    mFinishedCallback);
            mController.goodToGo(TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER);
            mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
            final ArgumentCaptor<RemoteAnimationTarget[]> appsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> wallpapersCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                    ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
            final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                    ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
            verify(mMockRunner).onAnimationStart(eq(TRANSIT_OLD_KEYGUARD_GOING_AWAY_ON_WALLPAPER),
                    appsCaptor.capture(), wallpapersCaptor.capture(), nonAppsCaptor.capture(),
                    finishedCaptor.capture());
            assertEquals(1, wallpapersCaptor.getValue().length);
            assertEquals(1, nonAppsCaptor.getValue().length);
        } finally {
            mDisplayContent.mOpeningApps.clear();
        }
    }

    @Test
    public void testNonAppTarget_sendNavBar() throws Exception {
        final int transit = TRANSIT_OLD_TASK_OPEN;
        final AnimationAdapter adapter = setupForNonAppTargetNavBar(transit, true);

        final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
        final ArgumentCaptor<IRemoteAnimationFinishedCallback> finishedCaptor =
                ArgumentCaptor.forClass(IRemoteAnimationFinishedCallback.class);
        verify(mMockRunner).onAnimationStart(eq(transit), any(), any(),
                nonAppsCaptor.capture(), finishedCaptor.capture());
        boolean containNavTarget = false;
        for (int i = 0; i < nonAppsCaptor.getValue().length; i++) {
            if (nonAppsCaptor.getValue()[0].windowType == TYPE_NAVIGATION_BAR) {
                containNavTarget = true;
                break;
            }
        }
        assertTrue(containNavTarget);
        assertEquals(1, mController.mPendingNonAppAnimations.size());
        final NonAppWindowAnimationAdapter nonAppAdapter =
                mController.mPendingNonAppAnimations.get(0);
        spyOn(nonAppAdapter.getLeashFinishedCallback());

        finishedCaptor.getValue().onAnimationFinished();
        verify(mFinishedCallback).onAnimationFinished(eq(ANIMATION_TYPE_APP_TRANSITION),
                eq(adapter));
        verify(nonAppAdapter.getLeashFinishedCallback())
                .onAnimationFinished(nonAppAdapter.getLastAnimationType(), nonAppAdapter);
    }

    @Test
    public void testNonAppTarget_notSendNavBar_notAttachToApp() throws Exception {
        final int transit = TRANSIT_OLD_TASK_OPEN;
        setupForNonAppTargetNavBar(transit, false);

        final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
        verify(mMockRunner).onAnimationStart(eq(transit),
                any(), any(), nonAppsCaptor.capture(), any());
        for (int i = 0; i < nonAppsCaptor.getValue().length; i++) {
            if (nonAppsCaptor.getValue()[0].windowType == TYPE_NAVIGATION_BAR) {
                fail("Non-app animation target must not contain navbar");
            }
        }
    }

    @Test
    public void testNonAppTarget_notSendNavBar_controlledByFadeRotation() throws Exception {
        final FadeRotationAnimationController mockController =
                mock(FadeRotationAnimationController.class);
        doReturn(mockController).when(mDisplayContent).getFadeRotationAnimationController();
        final int transit = TRANSIT_OLD_TASK_OPEN;
        setupForNonAppTargetNavBar(transit, true);

        final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
        verify(mMockRunner).onAnimationStart(eq(transit),
                any(), any(), nonAppsCaptor.capture(), any());
        for (int i = 0; i < nonAppsCaptor.getValue().length; i++) {
            if (nonAppsCaptor.getValue()[0].windowType == TYPE_NAVIGATION_BAR) {
                fail("Non-app animation target must not contain navbar");
            }
        }
    }

    @Test
    public void testNonAppTarget_notSendNavBar_controlledByRecents() throws Exception {
        final RecentsAnimationController mockController =
                mock(RecentsAnimationController.class);
        doReturn(mockController).when(mWm).getRecentsAnimationController();
        final int transit = TRANSIT_OLD_TASK_OPEN;
        setupForNonAppTargetNavBar(transit, true);

        final ArgumentCaptor<RemoteAnimationTarget[]> nonAppsCaptor =
                ArgumentCaptor.forClass(RemoteAnimationTarget[].class);
        verify(mMockRunner).onAnimationStart(eq(transit),
                any(), any(), nonAppsCaptor.capture(), any());
        for (int i = 0; i < nonAppsCaptor.getValue().length; i++) {
            if (nonAppsCaptor.getValue()[0].windowType == TYPE_NAVIGATION_BAR) {
                fail("Non-app animation target must not contain navbar");
            }
        }
    }

    private AnimationAdapter setupForNonAppTargetNavBar(int transit, boolean shouldAttachNavBar) {
        final WindowState win = createWindow(null /* parent */, TYPE_BASE_APPLICATION, "testWin");
        mDisplayContent.mOpeningApps.add(win.mActivityRecord);
        final WindowState navBar = createWindow(null, TYPE_NAVIGATION_BAR, "NavigationBar");
        mDisplayContent.getDisplayPolicy().addWindowLw(navBar, navBar.mAttrs);
        final DisplayPolicy policy = mDisplayContent.getDisplayPolicy();
        spyOn(policy);
        doReturn(shouldAttachNavBar).when(policy).shouldAttachNavBarToAppDuringTransition();

        final AnimationAdapter adapter = mController.createRemoteAnimationRecord(
                win.mActivityRecord, new Point(50, 100), null,
                new Rect(50, 100, 150, 150), null).mAdapter;
        adapter.startAnimation(mMockLeash, mMockTransaction, ANIMATION_TYPE_APP_TRANSITION,
                mFinishedCallback);
        mController.goodToGo(transit);
        mWm.mAnimator.executeAfterPrepareSurfacesRunnables();
        return adapter;
    }

    private static void verifyNoMoreInteractionsExceptAsBinder(IInterface binder) {
        verify(binder, atLeast(0)).asBinder();
        verifyNoMoreInteractions(binder);
    }
}
