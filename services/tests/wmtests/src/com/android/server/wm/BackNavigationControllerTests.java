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

package com.android.server.wm;

import static android.app.ActivityOptions.ANIM_SCENE_TRANSITION;
import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.pm.ApplicationInfo.PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
import static android.view.WindowManager.LayoutParams.FIRST_APPLICATION_WINDOW;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.window.BackNavigationInfo.typeToString;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.ActivityRecord.State.STOPPED;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.ActivityOptions;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.res.Resources;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.platform.test.annotations.RequiresFlagsDisabled;
import android.util.ArraySet;
import android.view.WindowManager;
import android.window.BackAnimationAdapter;
import android.window.BackMotionEvent;
import android.window.BackNavigationInfo;
import android.window.IOnBackInvokedCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedCallbackInfo;
import android.window.OnBackInvokedDispatcher;
import android.window.TaskFragmentOrganizer;
import android.window.TaskSnapshot;
import android.window.WindowOnBackInvokedDispatcher;

import com.android.server.LocalServices;
import com.android.window.flags.Flags;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for the {@link BackNavigationController} class.
 *
 * Build/Install/Run:
 *  atest WmTests:BackNavigationControllerTests
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class BackNavigationControllerTests extends WindowTestsBase {
    private BackNavigationController mBackNavigationController;
    private WindowManagerInternal mWindowManagerInternal;
    private BackAnimationAdapter mBackAnimationAdapter;
    private BackNavigationController.NavigationMonitor mNavigationMonitor;
    private Task mRootHomeTask;

    @Before
    public void setUp() throws Exception {
        final BackNavigationController original = new BackNavigationController();
        original.setWindowManager(mWm);
        mBackNavigationController = Mockito.spy(original);
        LocalServices.removeServiceForTest(WindowManagerInternal.class);
        mWindowManagerInternal = mock(WindowManagerInternal.class);
        LocalServices.addService(WindowManagerInternal.class, mWindowManagerInternal);
        mBackAnimationAdapter = mock(BackAnimationAdapter.class);
        doReturn(true).when(mBackAnimationAdapter).isAnimatable(anyInt());
        mNavigationMonitor = mock(BackNavigationController.NavigationMonitor.class);
        mRootHomeTask = initHomeActivity();
    }

    @Test
    public void backNavInfo_HomeWhenBackToLauncher() {
        Task task = createTopTaskWithActivity();
        IOnBackInvokedCallback callback = withSystemCallback(task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));

        // verify if back animation would start.
        assertTrue("Animation scheduled", backNavigationInfo.isPrepareRemoteAnimation());
    }

    @Test
    public void noBackWhenMoveTaskToBack() {
        Task taskA = createTask(mDefaultDisplay);
        ActivityRecord recordA = createActivityRecord(taskA);
        Mockito.doNothing().when(recordA).reparentSurfaceControl(any(), any());

        final Task topTask = createTopTaskWithActivity();
        withSystemCallback(topTask);
        // simulate moveTaskToBack
        topTask.setVisibleRequested(false);
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNull();
    }

    @Test
    public void backTypeCrossTaskWhenBackToPreviousTask() {
        Task taskA = createTask(mDefaultDisplay);
        ActivityRecord recordA = createActivityRecord(taskA);
        Mockito.doNothing().when(recordA).reparentSurfaceControl(any(), any());

        final Task topTask = createTopTaskWithActivity();
        withSystemCallback(topTask);
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_TASK));

        // verify if back animation would start.
        assertTrue("Animation scheduled", backNavigationInfo.isPrepareRemoteAnimation());

        // reset drawing status to test translucent activity
        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        final ActivityRecord topActivity = topTask.getTopMostActivity();
        makeWindowVisibleAndDrawn(topActivity.findMainWindow());
        // simulate translucent
        topActivity.setOccludesParent(false);
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));

        // reset drawing status to test if previous task is translucent activity
        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        makeWindowVisibleAndDrawn(topActivity.findMainWindow());
        // simulate translucent
        recordA.setOccludesParent(false);
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));

        // reset drawing status to test keyguard occludes
        topActivity.setOccludesParent(true);
        recordA.setOccludesParent(true);
        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        makeWindowVisibleAndDrawn(topActivity.findMainWindow());
        setupKeyguardOccluded();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));

        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        doReturn(true).when(recordA).canShowWhenLocked();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_TASK));
    }

    @Test
    public void backTypeBackToHomeDifferentUser() {
        Task taskA = createTask(mDefaultDisplay);
        ActivityRecord recordA = createActivityRecord(taskA);
        Mockito.doNothing().when(recordA).reparentSurfaceControl(any(), any());
        doReturn(false).when(taskA).showToCurrentUser();

        withSystemCallback(createTopTaskWithActivity());
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));
    }

    @Test
    public void backTypeCrossActivityWithCustomizeExitAnimation() {
        CrossActivityTestCase testCase = createTopTaskWithTwoActivities();
        IOnBackInvokedCallback callback = withSystemCallback(testCase.task);
        testCase.windowFront.mAttrs.windowAnimations = 0x10;
        spyOn(mDisplayContent.mAppTransition.mTransitionAnimation);
        doReturn(0xffff00AB).when(mDisplayContent.mAppTransition.mTransitionAnimation)
                .getAnimationResId(any(), anyInt(), anyInt());
        doReturn(0xffff00CD).when(mDisplayContent.mAppTransition.mTransitionAnimation)
                .getDefaultAnimationResId(anyInt(), anyInt());

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
        assertThat(backNavigationInfo.getCustomAnimationInfo().getWindowAnimations())
                .isEqualTo(testCase.windowFront.mAttrs.windowAnimations);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_ACTIVITY));
        // verify if back animation would start.
        assertTrue("Animation scheduled", backNavigationInfo.isPrepareRemoteAnimation());
    }

    @Test
    public void backTypeCrossActivityWhenBackToPreviousActivity() {
        CrossActivityTestCase testCase = createTopTaskWithTwoActivities();
        IOnBackInvokedCallback callback = withSystemCallback(testCase.task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_ACTIVITY));
        // verify if back animation would start.
        assertTrue("Animation scheduled", backNavigationInfo.isPrepareRemoteAnimation());

        // reset drawing status
        testCase.recordBack.setState(STOPPED, "stopped");
        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        makeWindowVisibleAndDrawn(testCase.recordFront.findMainWindow());
        setupKeyguardOccluded();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));

        // reset drawing status, test if top activity is translucent
        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        makeWindowVisibleAndDrawn(testCase.recordFront.findMainWindow());
        // simulate translucent
        testCase.recordFront.setOccludesParent(false);
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));
        testCase.recordFront.setOccludesParent(true);

        // reset drawing status, test if bottom activity is translucent
        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        makeWindowVisibleAndDrawn(testCase.recordBack.findMainWindow());
        // simulate translucent
        testCase.recordBack.setOccludesParent(false);
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));
        testCase.recordBack.setOccludesParent(true);

        // reset drawing status, test canShowWhenLocked
        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);
        doReturn(true).when(testCase.recordBack).canShowWhenLocked();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CROSS_ACTIVITY));
    }

    @Test
    public void backTypeCrossActivityInTaskFragment() {
        final Task task = createTask(mDefaultDisplay);
        final TaskFragment tf1 = createTaskFragmentWithActivity(task);
        final TaskFragment tf2 = createTaskFragmentWithActivity(task);
        final ArrayList<ActivityRecord> outPrevActivities = new ArrayList<>();

        ActivityRecord prevAr = tf1.getTopMostActivity();
        ActivityRecord topAr = tf2.getTopMostActivity();
        boolean predictable;

        // Stacked + no Companion => predict for previous activity.
        // TF2
        // TF1
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.contains(prevAr));
        assertTrue(predictable);
        outPrevActivities.clear();

        // Stacked + top companion to bottom but bottom didn't => predict for previous activity
        tf2.setCompanionTaskFragment(tf1);
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.contains(prevAr));
        assertTrue(predictable);
        tf2.setCompanionTaskFragment(null);
        outPrevActivities.clear();

        // Stacked + next companion to top => predict for previous task
        tf1.setCompanionTaskFragment(tf2);
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.isEmpty());
        assertTrue(predictable);
        tf1.setCompanionTaskFragment(null);

        // Adjacent + no companion => unable to predict
        // TF1 | TF2
        tf1.setAdjacentTaskFragment(tf2);
        tf2.setAdjacentTaskFragment(tf1);
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.isEmpty());
        assertFalse(predictable);
        predictable = BackNavigationController.getAnimatablePrevActivities(task, prevAr,
                outPrevActivities);
        assertTrue(outPrevActivities.isEmpty());
        assertFalse(predictable);

        // Adjacent + companion => predict for previous task
        tf1.setCompanionTaskFragment(tf2);
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.isEmpty());
        assertTrue(predictable);
        tf1.setCompanionTaskFragment(null);

        tf2.setCompanionTaskFragment(tf1);
        predictable = BackNavigationController.getAnimatablePrevActivities(task, prevAr,
                outPrevActivities);
        assertTrue(outPrevActivities.isEmpty());
        assertTrue(predictable);
        // reset
        tf1.setAdjacentTaskFragment(null);
        tf2.setAdjacentTaskFragment(null);
        tf1.setCompanionTaskFragment(null);
        tf2.setCompanionTaskFragment(null);

        final TaskFragment tf3 = new TaskFragmentBuilder(mAtm)
                .createActivityCount(2)
                .setParentTask(task)
                .build();
        topAr = tf3.getTopMostActivity();
        prevAr = tf3.getBottomMostActivity();
        // Stacked => predict for previous activity.
        // TF3
        // TF2
        // TF1
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.contains(prevAr));
        assertTrue(predictable);
        // reset
        outPrevActivities.clear();

        // Adjacent => predict for previous activity.
        // TF2 | TF3
        // TF1
        tf2.setAdjacentTaskFragment(tf3);
        tf3.setAdjacentTaskFragment(tf2);
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.contains(prevAr));
        assertTrue(predictable);
        // reset
        outPrevActivities.clear();
        tf2.setAdjacentTaskFragment(null);
        tf3.setAdjacentTaskFragment(null);

        final TaskFragment tf4 = createTaskFragmentWithActivity(task);
        // Stacked + next companion to top => predict for previous activity below companion.
        // Tf4
        // TF3
        // TF2
        // TF1
        tf3.setCompanionTaskFragment(tf4);
        topAr = tf4.getTopMostActivity();
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.contains(tf2.getTopMostActivity()));
        assertTrue(predictable);
        outPrevActivities.clear();
        tf3.setCompanionTaskFragment(null);

        // Stacked +  top companion to next but next one didn't => predict for previous activity.
        tf4.setCompanionTaskFragment(tf3);
        topAr = tf4.getTopMostActivity();
        predictable = BackNavigationController.getAnimatablePrevActivities(task, topAr,
                outPrevActivities);
        assertTrue(outPrevActivities.contains(tf3.getTopMostActivity()));
        assertTrue(predictable);
    }

    @Test
    public void backTypeDialogCloseWhenBackFromDialog() {
        DialogCloseTestCase testCase = createTopTaskWithActivityAndDialog();
        IOnBackInvokedCallback callback = withSystemCallback(testCase.task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_DIALOG_CLOSE));
        // verify if back animation would start.
        assertTrue("Animation scheduled", backNavigationInfo.isPrepareRemoteAnimation());
    }

    @Test
    public void backInfoWithNullWindow() {
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(backNavigationInfo).isNull();
    }

    @Test
    public void backInfoWindowWithNoActivity() {
        WindowState window = createWindow(null, WindowManager.LayoutParams.TYPE_WALLPAPER,
                "Wallpaper");
        addToWindowMap(window, true);
        makeWindowVisibleAndDrawn(window);

        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        window.setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(
                        callback,
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        /* isAnimationCallback = */ false));

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getType()).isEqualTo(BackNavigationInfo.TYPE_CALLBACK);
        assertThat(backNavigationInfo.isAnimationCallback()).isEqualTo(false);
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
    }

    @Test
    public void backInfoWithAnimationCallback() {
        WindowState window = createWindow(null, WindowManager.LayoutParams.TYPE_WALLPAPER,
                "Wallpaper");
        addToWindowMap(window, true);
        makeWindowVisibleAndDrawn(window);

        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        window.setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(
                        callback,
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        /* isAnimationCallback = */ true));

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        assertThat(backNavigationInfo.getType()).isEqualTo(BackNavigationInfo.TYPE_CALLBACK);
        assertThat(backNavigationInfo.isAnimationCallback()).isEqualTo(true);
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
    }

    @Test
    public void preparesForBackToHome() {
        final Task topTask = createTopTaskWithActivity();
        withSystemCallback(topTask);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_RETURN_TO_HOME));

        backNavigationInfo.onBackNavigationFinished(false);
        mBackNavigationController.clearBackAnimations(true);

        final WindowState window = topTask.getTopVisibleAppMainWindow();
        makeWindowVisibleAndDrawn(window);
        setupKeyguardOccluded();
        backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));
    }

    @Test
    public void backTypeCallback() {
        Task task = createTopTaskWithActivity();
        IOnBackInvokedCallback appCallback = withAppCallback(task);

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(appCallback);
    }

    // TODO (b/259427810) Remove this test when we figure out new API
    @Test
    public void backAnimationSkipSharedElementTransition() {
        // Simulate ActivityOptions#makeSceneTransitionAnimation
        final Bundle myBundle = new Bundle();
        myBundle.putInt(ActivityOptions.KEY_ANIM_TYPE, ANIM_SCENE_TRANSITION);
        final ActivityOptions options = new ActivityOptions(myBundle);
        final ActivityOptions.SceneTransitionInfo info = new ActivityOptions.SceneTransitionInfo();
        info.setResultReceiver(mock(android.os.ResultReceiver.class));
        options.setSceneTransitionInfo(info);

        final ActivityRecord testActivity = new ActivityBuilder(mAtm)
                .setCreateTask(true)
                .setActivityOptions(options)
                .build();
        testActivity.info.applicationInfo.privateFlagsExt |=
                PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        final WindowState window = createWindow(null, TYPE_BASE_APPLICATION, testActivity,
                "window");
        addToWindowMap(window, true);
        makeWindowVisibleAndDrawn(window);
        IOnBackInvokedCallback callback = withSystemCallback(testActivity.getTask());

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertTrue(testActivity.mHasSceneTransition);
        assertThat(typeToString(backNavigationInfo.getType()))
                .isEqualTo(typeToString(BackNavigationInfo.TYPE_CALLBACK));
        assertThat(backNavigationInfo.getOnBackInvokedCallback()).isEqualTo(callback);
    }

    @Test
    public void testUnregisterCallbacksWithSystemCallback()
            throws InterruptedException, RemoteException {
        CountDownLatch systemLatch = new CountDownLatch(1);
        CountDownLatch appLatch = new CountDownLatch(1);

        final ApplicationInfo info = mock(ApplicationInfo.class);
        final Context context = mock(Context.class);
        Mockito.doReturn(true).when(info).isOnBackInvokedCallbackEnabled();
        Mockito.doReturn(info).when(context).getApplicationInfo();

        Task task = createTopTaskWithActivity();
        WindowState appWindow = task.getTopVisibleAppMainWindow();
        WindowOnBackInvokedDispatcher dispatcher =
                new WindowOnBackInvokedDispatcher(context, Looper.getMainLooper());
        spyOn(appWindow.mSession);
        doAnswer(invocation -> {
            appWindow.setOnBackInvokedCallbackInfo(invocation.getArgument(1));
            return null;
        }).when(appWindow.mSession).setOnBackInvokedCallbackInfo(eq(appWindow.mClient), any());

        addToWindowMap(appWindow, true);
        dispatcher.attachToWindow(appWindow.mSession, appWindow.mClient, null, null);


        OnBackInvokedCallback appCallback = createBackCallback(appLatch);
        OnBackInvokedCallback systemCallback = createBackCallback(systemLatch);

        // Register both a system callback and an application callback
        dispatcher.registerSystemOnBackInvokedCallback(systemCallback);
        dispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                appCallback);

        // Check that the top callback is the app callback
        assertEquals(appCallback, dispatcher.getTopCallback());

        // Now unregister the app callback and check that the top callback is the system callback
        dispatcher.unregisterOnBackInvokedCallback(appCallback);
        assertEquals(systemCallback, dispatcher.getTopCallback());

        // Verify that this has correctly been propagated to the server and that the
        // BackNavigationInfo object will contain the system callback
        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertWithMessage("BackNavigationInfo").that(backNavigationInfo).isNotNull();
        IOnBackInvokedCallback callback = backNavigationInfo.getOnBackInvokedCallback();
        assertThat(callback).isNotNull();

        try {
            callback.onBackInvoked();
        } catch (RemoteException e) {
            throw new RuntimeException(e);
        }

        // Check that the system callback has been call
        assertTrue("System callback has not been called",
                systemLatch.await(500, TimeUnit.MILLISECONDS));
        assertEquals("App callback should not have been called",
                1, appLatch.getCount());
    }

    @Test
    public void backInfoWindowWithoutDrawn() {
        WindowState window = createWindow(null, WindowManager.LayoutParams.TYPE_APPLICATION,
                "TestWindow");
        addToWindowMap(window, true);

        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        window.setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(
                        callback,
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        /* isAnimationCallback = */ false));

        BackNavigationInfo backNavigationInfo = startBackNavigation();
        assertThat(backNavigationInfo).isNull();
    }

    @Test
    @RequiresFlagsDisabled(Flags.FLAG_MIGRATE_PREDICTIVE_BACK_TRANSITION)
    public void testTransitionHappensCancelNavigation() {
        // Create a floating task and a fullscreen task, then navigating on fullscreen task.
        // The navigation should not been cancelled when transition happens on floating task, and
        // only be cancelled when transition happens on the navigating task.
        final Task floatingTask = createTask(mDisplayContent, WINDOWING_MODE_MULTI_WINDOW,
                ACTIVITY_TYPE_STANDARD);
        final ActivityRecord baseFloatingActivity = createActivityRecord(floatingTask);

        final Task fullscreenTask = createTopTaskWithActivity();
        withSystemCallback(fullscreenTask);
        final ActivityRecord baseFullscreenActivity = fullscreenTask.getTopMostActivity();

        final CountDownLatch navigationObserver = new CountDownLatch(1);
        startBackNavigation(navigationObserver);

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        final ActivityRecord secondFloatingActivity = createActivityRecord(floatingTask);
        opening.add(secondFloatingActivity);
        closing.add(baseFloatingActivity);
        mBackNavigationController.removeIfContainsBackAnimationTargets(opening, closing);
        assertEquals("Transition happen on an irrelevant task, callback should not been called",
                1, navigationObserver.getCount());

        // Create a new activity above navigation target, the transition should cancel navigation.
        final ActivityRecord topFullscreenActivity = createActivityRecord(fullscreenTask);
        opening.clear();
        closing.clear();
        opening.add(topFullscreenActivity);
        closing.add(baseFullscreenActivity);
        mBackNavigationController.removeIfContainsBackAnimationTargets(opening, closing);
        assertEquals("Transition happen on navigation task, callback should have been called",
                0, navigationObserver.getCount());
    }

    @Test
    public void testWindowFocusChangeCancelNavigation() {
        Task task = createTopTaskWithActivity();
        withSystemCallback(task);
        WindowState focusWindow = task.getTopVisibleAppMainWindow();
        final CountDownLatch navigationObserver = new CountDownLatch(1);
        startBackNavigation(navigationObserver);

        mBackNavigationController.onFocusChanged(null);
        assertEquals("change focus to null, callback should not have been called",
                1, navigationObserver.getCount());
        mBackNavigationController.onFocusChanged(focusWindow);
        assertEquals("change focus back, callback should not have been called",
                1, navigationObserver.getCount());

        WindowState newWindow = createWindow(null, TYPE_APPLICATION_OVERLAY, "overlayWindow");
        addToWindowMap(newWindow, true);
        mBackNavigationController.onFocusChanged(newWindow);
        assertEquals("Focus change, callback should have been called",
                0, navigationObserver.getCount());
    }

    @Test
    public void testBackOnMostRecentWindowInActivityEmbedding() {
        final Task task = createTask(mDefaultDisplay);
        final TaskFragmentOrganizer organizer = new TaskFragmentOrganizer(Runnable::run);
        final TaskFragment primaryTf = createTaskFragmentWithEmbeddedActivity(task, organizer);
        final TaskFragment secondaryTf = createTaskFragmentWithEmbeddedActivity(task, organizer);
        final ActivityRecord primaryActivity = primaryTf.getTopMostActivity();
        final ActivityRecord secondaryActivity = secondaryTf.getTopMostActivity();
        primaryTf.setAdjacentTaskFragment(secondaryTf);
        secondaryTf.setAdjacentTaskFragment(primaryTf);

        final WindowState primaryWindow = mock(WindowState.class);
        final WindowState secondaryWindow = mock(WindowState.class);
        doReturn(primaryActivity).when(primaryWindow).getActivityRecord();
        doReturn(secondaryActivity).when(secondaryWindow).getActivityRecord();
        doReturn(1L).when(primaryActivity).getLastWindowCreateTime();
        doReturn(2L).when(secondaryActivity).getLastWindowCreateTime();
        doReturn(mDisplayContent).when(primaryActivity).getDisplayContent();
        doReturn(secondaryWindow).when(mDisplayContent).findFocusedWindow(eq(secondaryActivity));

        final WindowState mostRecentUsedWindow =
                mWm.getMostRecentUsedEmbeddedWindowForBack(primaryWindow);
        assertThat(mostRecentUsedWindow).isEqualTo(secondaryWindow);
    }

    /**
     * Test with
     * config_predictShowStartingSurface = true
     */
    @Test
    public void testEnableWindowlessSurface() {
        testPrepareAnimation(true);
    }

    /**
     * Test with
     * config_predictShowStartingSurface = false
     */
    @Test
    public void testDisableWindowlessSurface() {
        testPrepareAnimation(false);
    }

    private IOnBackInvokedCallback withSystemCallback(Task task) {
        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        task.getTopMostActivity().getTopChild().setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(
                        callback,
                        OnBackInvokedDispatcher.PRIORITY_SYSTEM,
                        /* isAnimationCallback = */ false));
        return callback;
    }

    private IOnBackInvokedCallback withAppCallback(Task task) {
        IOnBackInvokedCallback callback = createOnBackInvokedCallback();
        task.getTopMostActivity().getTopChild().setOnBackInvokedCallbackInfo(
                new OnBackInvokedCallbackInfo(
                        callback,
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
                        /* isAnimationCallback = */ false));
        return callback;
    }

    @Nullable
    private BackNavigationInfo startBackNavigation() {
        return mBackNavigationController.startBackNavigation(
                createNavigationObserver(null), mBackAnimationAdapter);
    }

    @Nullable
    private BackNavigationInfo startBackNavigation(CountDownLatch navigationObserverLatch) {
        return mBackNavigationController.startBackNavigation(
                createNavigationObserver(navigationObserverLatch), mBackAnimationAdapter);
    }

    @NonNull
    private IOnBackInvokedCallback createOnBackInvokedCallback() {
        return new IOnBackInvokedCallback.Stub() {
            @Override
            public void onBackStarted(BackMotionEvent backMotionEvent) {
            }

            @Override
            public void onBackProgressed(BackMotionEvent backMotionEvent) {
            }

            @Override
            public void onBackCancelled() {
            }

            @Override
            public void onBackInvoked() {
            }

            @Override
            public void setTriggerBack(boolean triggerBack) {
            }
        };
    }

    private OnBackInvokedCallback createBackCallback(CountDownLatch latch) {
        return new OnBackInvokedCallback() {
            @Override
            public void onBackInvoked() {
                if (latch != null) {
                    latch.countDown();
                }
            }
        };
    }

    private RemoteCallback createNavigationObserver(CountDownLatch latch) {
        return new RemoteCallback(result -> {
            if (latch != null) {
                latch.countDown();
            }
        });
    }

    private Task initHomeActivity() {
        final Task task = mDisplayContent.getDefaultTaskDisplayArea().getRootHomeTask();
        task.forAllLeafTasks((t) -> {
            if (t.getTopMostActivity() == null) {
                final ActivityRecord r = createActivityRecord(t);
                Mockito.doNothing().when(t).reparentSurfaceControl(any(), any());
                Mockito.doNothing().when(r).reparentSurfaceControl(any(), any());
            }
        }, true);
        return task;
    }

    private void setupKeyguardOccluded() {
        final KeyguardController kc = mRootHomeTask.mTaskSupervisor.getKeyguardController();
        doReturn(true).when(kc).isKeyguardLocked(anyInt());
        doReturn(true).when(kc).isKeyguardOccluded(anyInt());
    }

    private void testPrepareAnimation(boolean preferWindowlessSurface) {
        final TaskSnapshot taskSnapshot = mock(TaskSnapshot.class);
        final ContextWrapper contextSpy = Mockito.spy(new ContextWrapper(mWm.mContext));
        final Resources resourcesSpy = Mockito.spy(contextSpy.getResources());

        spyOn(mAtm.mTaskOrganizerController);
        when(contextSpy.getResources()).thenReturn(resourcesSpy);

        MockitoSession mockitoSession = mockitoSession().mockStatic(BackNavigationController.class)
                .strictness(Strictness.LENIENT).startMocking();
        doReturn(taskSnapshot).when(() -> BackNavigationController.getSnapshot(any(), any()));
        when(resourcesSpy.getBoolean(
                com.android.internal.R.bool.config_predictShowStartingSurface))
                .thenReturn(preferWindowlessSurface);

        final BackNavigationController.AnimationHandler animationHandler =
                Mockito.spy(new BackNavigationController.AnimationHandler(mWm));
        doReturn(true).when(animationHandler).isSupportWindowlessSurface();
        testWithConfig(animationHandler, preferWindowlessSurface);
        mockitoSession.finishMocking();
    }

    private void testWithConfig(BackNavigationController.AnimationHandler animationHandler,
            boolean preferWindowlessSurface) {
        final Task task = createTask(mDefaultDisplay);
        final ActivityRecord bottomActivity = createActivityRecord(task);
        final ActivityRecord homeActivity = mRootHomeTask.getTopNonFinishingActivity();
        final ArrayList<ActivityRecord> openActivities = new ArrayList<>();
        openActivities.add(homeActivity);
        final BackNavigationController.AnimationHandler.ScheduleAnimationBuilder toHomeBuilder =
                animationHandler.prepareAnimation(
                        BackNavigationInfo.TYPE_RETURN_TO_HOME,
                        mBackAnimationAdapter,
                        mNavigationMonitor,
                        task,
                        mRootHomeTask,
                        bottomActivity,
                        openActivities,
                        task);
        assertTrue(toHomeBuilder.mIsLaunchBehind);
        toHomeBuilder.build();
        verify(mAtm.mTaskOrganizerController, never()).addWindowlessStartingSurface(
                any(), any(), any(), any(), any(), any());
        animationHandler.clearBackAnimateTarget(true);
        openActivities.clear();

        // Back to ACTIVITY and TASK have the same logic, just with different target.
        final ActivityRecord topActivity = createActivityRecord(task);
        openActivities.add(bottomActivity);
        final BackNavigationController.AnimationHandler.ScheduleAnimationBuilder toActivityBuilder =
                animationHandler.prepareAnimation(
                        BackNavigationInfo.TYPE_CROSS_ACTIVITY,
                        mBackAnimationAdapter,
                        mNavigationMonitor,
                        task,
                        task,
                        topActivity,
                        openActivities,
                        topActivity);
        assertFalse(toActivityBuilder.mIsLaunchBehind);
        toActivityBuilder.build();
        if (preferWindowlessSurface) {
            verify(mAtm.mTaskOrganizerController).addWindowlessStartingSurface(
                    any(), any(), any(), any(), any(), any());
        } else {
            verify(mAtm.mTaskOrganizerController, never()).addWindowlessStartingSurface(
                    any(), any(), any(), any(), any(),  any());
        }
    }

    @NonNull
    private Task createTopTaskWithActivity() {
        Task task = createTask(mDefaultDisplay);
        ActivityRecord record = createActivityRecord(task);
        // enable OnBackInvokedCallbacks
        record.info.applicationInfo.privateFlagsExt |=
                PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, record, "window");
        when(record.mSurfaceControl.isValid()).thenReturn(true);
        Mockito.doNothing().when(task).reparentSurfaceControl(any(), any());
        mAtm.setFocusedTask(task.mTaskId, record);
        addToWindowMap(window, true);
        makeWindowVisibleAndDrawn(window);
        return task;
    }

    @NonNull
    private DialogCloseTestCase createTopTaskWithActivityAndDialog() {
        Task task = createTask(mDefaultDisplay);
        ActivityRecord record = createActivityRecord(task);
        // enable OnBackInvokedCallbacks
        record.info.applicationInfo.privateFlagsExt |=
                PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        WindowState window = createWindow(null, FIRST_APPLICATION_WINDOW, record, "window");
        WindowState dialog = createWindow(null, TYPE_APPLICATION, record, "dialog");
        when(record.mSurfaceControl.isValid()).thenReturn(true);
        Mockito.doNothing().when(task).reparentSurfaceControl(any(), any());
        mAtm.setFocusedTask(task.mTaskId, record);
        addToWindowMap(window, true);
        addToWindowMap(dialog, true);

        makeWindowVisibleAndDrawn(dialog);

        DialogCloseTestCase testCase = new DialogCloseTestCase();
        testCase.task = task;
        testCase.record = record;
        testCase.windowBack = window;
        testCase.windowFront = dialog;
        return testCase;
    }

    @NonNull
    private CrossActivityTestCase createTopTaskWithTwoActivities() {
        Task task = createTask(mDefaultDisplay);
        ActivityRecord record1 = createActivityRecord(task);
        ActivityRecord record2 = createActivityRecord(task);
        // enable OnBackInvokedCallbacks
        record2.info.applicationInfo.privateFlagsExt |=
                PRIVATE_FLAG_EXT_ENABLE_ON_BACK_INVOKED_CALLBACK;
        WindowState window1 = createWindow(null, FIRST_APPLICATION_WINDOW, record1, "window1");
        WindowState window2 = createWindow(null, FIRST_APPLICATION_WINDOW, record2, "window2");
        when(task.mSurfaceControl.isValid()).thenReturn(true);
        when(record1.mSurfaceControl.isValid()).thenReturn(true);
        when(record2.mSurfaceControl.isValid()).thenReturn(true);
        Mockito.doNothing().when(task).reparentSurfaceControl(any(), any());
        Mockito.doNothing().when(record1).reparentSurfaceControl(any(), any());
        Mockito.doNothing().when(record2).reparentSurfaceControl(any(), any());
        mAtm.setFocusedTask(task.mTaskId, record1);
        mAtm.setFocusedTask(task.mTaskId, record2);
        addToWindowMap(window1, true);
        addToWindowMap(window2, true);

        makeWindowVisibleAndDrawn(window2);

        CrossActivityTestCase testCase = new CrossActivityTestCase();
        testCase.task = task;
        testCase.recordBack = record1;
        testCase.recordFront = record2;
        testCase.windowBack = window1;
        testCase.windowFront = window2;
        record1.setState(STOPPED, "stopped");
        return testCase;
    }

    private void addToWindowMap(WindowState window, boolean focus) {
        mWm.mWindowMap.put(window.mClient.asBinder(), window);
        if (focus) {
            doReturn(window.getWindowInfo().token)
                    .when(mWindowManagerInternal).getFocusedWindowToken();
            doReturn(window).when(mWm).getFocusedWindowLocked();
        }
    }

    private class CrossActivityTestCase {
        public Task task;
        public ActivityRecord recordBack;
        public WindowState windowBack;
        public ActivityRecord recordFront;
        public WindowState windowFront;
    }

    private class DialogCloseTestCase {
        public Task task;
        public ActivityRecord record;
        public WindowState windowBack;
        public WindowState windowFront;
    }
}
