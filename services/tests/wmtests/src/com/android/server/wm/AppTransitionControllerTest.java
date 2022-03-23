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

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.view.WindowManager.LayoutParams.FLAG_SHOW_WALLPAPER;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CHANGE;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_ACTIVITY_OPEN;
import static android.view.WindowManager.TRANSIT_OLD_KEYGUARD_UNOCCLUDE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.view.WindowManager.TRANSIT_TO_FRONT;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.doCallRealMethod;

import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.util.ArraySet;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationDefinition;
import android.view.RemoteAnimationTarget;
import android.view.WindowManager;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Build/Install/Run:
 *  atest WmTests:AppTransitionControllerTest
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class AppTransitionControllerTest extends WindowTestsBase {

    private AppTransitionController mAppTransitionController;

    @Before
    public void setUp() throws Exception {
        mAppTransitionController = new AppTransitionController(mWm, mDisplayContent);
    }

    @Override
    ActivityRecord createActivityRecord(DisplayContent dc, int windowingMode, int activityType) {
        final ActivityRecord r = super.createActivityRecord(dc, windowingMode, activityType);
        // Ensure that ActivityRecord#setOccludesParent takes effect.
        doCallRealMethod().when(r).fillsParent();
        return r;
    }

    @Test
    public void testSkipOccludedActivityCloseTransition() {
        final ActivityRecord behind = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord topOpening = createActivityRecord(behind.getTask());
        topOpening.setOccludesParent(true);
        topOpening.setVisible(true);

        mDisplayContent.prepareAppTransition(TRANSIT_OPEN);
        mDisplayContent.prepareAppTransition(TRANSIT_CLOSE);
        mDisplayContent.mClosingApps.add(behind);

        assertEquals(WindowManager.TRANSIT_OLD_UNSET,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null, null, false));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testTranslucentOpen() {
        final ActivityRecord behind = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord translucentOpening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        translucentOpening.setOccludesParent(false);
        translucentOpening.setVisible(false);
        mDisplayContent.prepareAppTransition(TRANSIT_OPEN);
        mDisplayContent.mOpeningApps.add(behind);
        mDisplayContent.mOpeningApps.add(translucentOpening);

        assertEquals(WindowManager.TRANSIT_OLD_TRANSLUCENT_ACTIVITY_OPEN,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                    mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                    null, null, false));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testTranslucentClose() {
        final ActivityRecord behind = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord translucentClosing = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        translucentClosing.setOccludesParent(false);
        mDisplayContent.prepareAppTransition(TRANSIT_CLOSE);
        mDisplayContent.mClosingApps.add(translucentClosing);
        assertEquals(WindowManager.TRANSIT_OLD_TRANSLUCENT_ACTIVITY_CLOSE,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null, null, false));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testChangeIsNotOverwritten() {
        final ActivityRecord behind = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final ActivityRecord translucentOpening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        translucentOpening.setOccludesParent(false);
        translucentOpening.setVisible(false);
        mDisplayContent.prepareAppTransition(TRANSIT_CHANGE);
        mDisplayContent.mOpeningApps.add(behind);
        mDisplayContent.mOpeningApps.add(translucentOpening);
        assertEquals(TRANSIT_OLD_TASK_CHANGE_WINDOWING_MODE,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        null, null, false));
    }

    @Test
    @FlakyTest(bugId = 131005232)
    public void testTransitWithinTask() {
        final ActivityRecord opening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        opening.setOccludesParent(false);
        final ActivityRecord closing = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FREEFORM, ACTIVITY_TYPE_STANDARD);
        closing.setOccludesParent(false);
        final Task task = opening.getTask();
        mDisplayContent.mOpeningApps.add(opening);
        mDisplayContent.mClosingApps.add(closing);
        assertFalse(mAppTransitionController.isTransitWithinTask(TRANSIT_OLD_ACTIVITY_OPEN, task));
        closing.getTask().removeChild(closing);
        task.addChild(closing, 0);
        assertTrue(mAppTransitionController.isTransitWithinTask(TRANSIT_OLD_ACTIVITY_OPEN, task));
        assertFalse(mAppTransitionController.isTransitWithinTask(TRANSIT_OLD_TASK_OPEN, task));
    }


    @Test
    public void testIntraWallpaper_open() {
        final ActivityRecord opening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        opening.setVisible(false);
        final WindowManager.LayoutParams attrOpening = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrOpening.setTitle("WallpaperOpening");
        attrOpening.flags |= FLAG_SHOW_WALLPAPER;
        final TestWindowState appWindowOpening = createWindowState(attrOpening, opening);
        opening.addWindow(appWindowOpening);

        final ActivityRecord closing = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowManager.LayoutParams attrClosing = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrOpening.setTitle("WallpaperClosing");
        attrClosing.flags |= FLAG_SHOW_WALLPAPER;
        final TestWindowState appWindowClosing = createWindowState(attrClosing, closing);
        closing.addWindow(appWindowClosing);

        mDisplayContent.prepareAppTransition(TRANSIT_OPEN);
        mDisplayContent.mOpeningApps.add(opening);
        mDisplayContent.mClosingApps.add(closing);

        assertEquals(WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_OPEN,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        appWindowClosing, null, false));
    }

    @Test
    public void testIntraWallpaper_toFront() {
        final ActivityRecord opening = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        opening.setVisible(false);
        final WindowManager.LayoutParams attrOpening = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrOpening.setTitle("WallpaperOpening");
        attrOpening.flags |= FLAG_SHOW_WALLPAPER;
        final TestWindowState appWindowOpening = createWindowState(attrOpening, opening);
        opening.addWindow(appWindowOpening);

        final ActivityRecord closing = createActivityRecord(mDisplayContent,
                WINDOWING_MODE_FULLSCREEN, ACTIVITY_TYPE_STANDARD);
        final WindowManager.LayoutParams attrClosing = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrOpening.setTitle("WallpaperClosing");
        attrClosing.flags |= FLAG_SHOW_WALLPAPER;
        final TestWindowState appWindowClosing = createWindowState(attrClosing, closing);
        closing.addWindow(appWindowClosing);

        mDisplayContent.prepareAppTransition(TRANSIT_TO_FRONT);
        mDisplayContent.mOpeningApps.add(opening);
        mDisplayContent.mClosingApps.add(closing);

        assertEquals(WindowManager.TRANSIT_OLD_WALLPAPER_INTRA_OPEN,
                AppTransitionController.getTransitCompatType(mDisplayContent.mAppTransition,
                        mDisplayContent.mOpeningApps, mDisplayContent.mClosingApps,
                        appWindowClosing, null, false));
    }

    @Test
    public void testGetAnimationTargets_visibilityAlreadyUpdated() {
        // [DisplayContent] -+- [Task1] - [ActivityRecord1] (opening, visible)
        //                   +- [Task2] - [ActivityRecord2] (closing, invisible)
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent);
        activity2.setVisible(false);
        activity2.mVisibleRequested = false;

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity2);

        // No animation, since visibility of the opening and closing apps are already updated
        // outside of AppTransition framework.
        assertEquals(
                new ArraySet<>(),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_visibilityAlreadyUpdated_butForcedTransitionRequested() {
        // [DisplayContent] -+- [Task1] - [ActivityRecord1] (closing, invisible)
        //                   +- [Task2] - [ActivityRecord2] (opening, visible)
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        activity1.setVisible(true);
        activity1.mVisibleRequested = true;
        activity1.mRequestForceTransition = true;

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent);
        activity2.setVisible(false);
        activity2.mVisibleRequested = false;
        activity2.mRequestForceTransition = true;

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity2);

        // The visibility are already updated, but since forced transition is requested, it will
        // be included.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity1.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity2.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_exitingBeforeTransition() {
        // Create another non-empty task so the animation target won't promote to task display area.
        createActivityRecord(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        activity.setVisible(false);
        activity.mIsExiting = true;

        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity);

        // Animate closing apps even if it's not visible when it is exiting before we had a chance
        // to play the transition animation.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        new ArraySet<>(), closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_windowsAreBeingReplaced() {
        // [DisplayContent] -+- [Task1] - [ActivityRecord1] (opening, visible)
        //                                       +- [AppWindow1] (being-replaced)
        //                   +- [Task2] - [ActivityRecord2] (closing, invisible)
        //                                       +- [AppWindow2] (being-replaced)
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow1");
        final TestWindowState appWindow1 = createWindowState(attrs, activity1);
        appWindow1.mWillReplaceWindow = true;
        activity1.addWindow(appWindow1);

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent);
        activity2.setVisible(false);
        activity2.mVisibleRequested = false;
        attrs.setTitle("AppWindow2");
        final TestWindowState appWindow2 = createWindowState(attrs, activity2);
        appWindow2.mWillReplaceWindow = true;
        activity2.addWindow(appWindow2);

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity2);

        // Animate opening apps even if it's already visible in case its windows are being replaced.
        // Don't animate closing apps if it's already invisible even though its windows are being
        // replaced.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity1.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(new WindowContainer[]{}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_openingClosingInDifferentTask() {
        // [DisplayContent] -+- [Task1] -+- [ActivityRecord1] (opening, invisible)
        //                   |           +- [ActivityRecord2] (invisible)
        //                   |
        //                   +- [Task2] -+- [ActivityRecord3] (closing, visible)
        //                               +- [ActivityRecord4] (invisible)
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        activity1.setVisible(false);
        activity1.mVisibleRequested = true;
        final ActivityRecord activity2 = createActivityRecord(mDisplayContent,
                activity1.getTask());
        activity2.setVisible(false);
        activity2.mVisibleRequested = false;

        final ActivityRecord activity3 = createActivityRecord(mDisplayContent);
        final ActivityRecord activity4 = createActivityRecord(mDisplayContent,
                activity3.getTask());
        activity4.setVisible(false);
        activity4.mVisibleRequested = false;

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity3);

        // Promote animation targets to TaskStack level. Invisible ActivityRecords don't affect
        // promotion decision.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity1.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity3.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_openingClosingInSameTask() {
        // [DisplayContent] - [Task] -+- [ActivityRecord1] (opening, invisible)
        //                            +- [ActivityRecord2] (closing, visible)
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        activity1.setVisible(false);
        activity1.mVisibleRequested = true;
        final ActivityRecord activity2 = createActivityRecord(mDisplayContent,
                activity1.getTask());

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity2);

        // Don't promote an animation target to Task level, since the same task contains both
        // opening and closing app.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity1}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity2}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_animateOnlyTranslucentApp() {
        // [DisplayContent] -+- [Task1] -+- [ActivityRecord1] (opening, invisible)
        //                   |           +- [ActivityRecord2] (visible)
        //                   |
        //                   +- [Task2] -+- [ActivityRecord3] (closing, visible)
        //                               +- [ActivityRecord4] (visible)

        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        activity1.setVisible(false);
        activity1.mVisibleRequested = true;
        activity1.setOccludesParent(false);

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent,
                activity1.getTask());

        final ActivityRecord activity3 = createActivityRecord(mDisplayContent);
        activity3.setOccludesParent(false);
        final ActivityRecord activity4 = createActivityRecord(mDisplayContent,
                activity3.getTask());

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity3);

        // Don't promote an animation target to Task level, since opening (closing) app is
        // translucent and is displayed over other non-animating app.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity1}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity3}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_animateTranslucentAndOpaqueApps() {
        // [DisplayContent] -+- [Task1] -+- [ActivityRecord1] (opening, invisible)
        //                   |           +- [ActivityRecord2] (opening, invisible)
        //                   |
        //                   +- [Task2] -+- [ActivityRecord3] (closing, visible)
        //                               +- [ActivityRecord4] (closing, visible)

        final ActivityRecord activity1 = createActivityRecord(mDisplayContent);
        activity1.setVisible(false);
        activity1.mVisibleRequested = true;
        activity1.setOccludesParent(false);

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent,
                activity1.getTask());
        activity2.setVisible(false);
        activity2.mVisibleRequested = true;

        final ActivityRecord activity3 = createActivityRecord(mDisplayContent);
        activity3.setOccludesParent(false);
        final ActivityRecord activity4 = createActivityRecord(mDisplayContent,
                activity3.getTask());

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        opening.add(activity2);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity3);
        closing.add(activity4);

        // Promote animation targets to TaskStack level even though opening (closing) app is
        // translucent as long as all visible siblings animate at the same time.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity1.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity3.getRootTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    @Test
    public void testGetAnimationTargets_taskContainsMultipleTasks() {
        // [DisplayContent] - [Task] -+- [Task1] - [ActivityRecord1] (opening, invisible)
        //                            +- [Task2] - [ActivityRecord2] (closing, visible)
        final Task parentTask = createTask(mDisplayContent);
        final ActivityRecord activity1 = createActivityRecordWithParentTask(parentTask);
        activity1.setVisible(false);
        activity1.mVisibleRequested = true;
        final ActivityRecord activity2 = createActivityRecordWithParentTask(parentTask);

        final ArraySet<ActivityRecord> opening = new ArraySet<>();
        opening.add(activity1);
        final ArraySet<ActivityRecord> closing = new ArraySet<>();
        closing.add(activity2);

        // Promote animation targets up to Task level, not beyond.
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity1.getTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, true /* visible */));
        assertEquals(
                new ArraySet<>(new WindowContainer[]{activity2.getTask()}),
                AppTransitionController.getAnimationTargets(
                        opening, closing, false /* visible */));
    }

    static class TestRemoteAnimationRunner implements IRemoteAnimationRunner {
        @Override
        public void onAnimationStart(int transit, RemoteAnimationTarget[] apps,
                RemoteAnimationTarget[] wallpapers, RemoteAnimationTarget[] nonApps,
                IRemoteAnimationFinishedCallback finishedCallback) throws RemoteException {
        }

        @Override
        public void onAnimationCancelled() throws RemoteException {
        }

        @Override
        public IBinder asBinder() {
            return new Binder();
        }
    }

    @Test
    public void testGetRemoteAnimationOverrideEmpty() {
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        assertNull(mAppTransitionController.getRemoteAnimationOverride(activity,
                TRANSIT_OLD_ACTIVITY_OPEN, new ArraySet<Integer>()));
    }

    @Test
    public void testGetRemoteAnimationOverrideWindowContainer() {
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                new TestRemoteAnimationRunner(), 10, 1);
        definition.addRemoteAnimation(TRANSIT_OLD_ACTIVITY_OPEN, adapter);
        activity.registerRemoteAnimations(definition);

        assertEquals(adapter,
                mAppTransitionController.getRemoteAnimationOverride(
                        activity, TRANSIT_OLD_ACTIVITY_OPEN, new ArraySet<Integer>()));
        assertNull(mAppTransitionController.getRemoteAnimationOverride(
                        null, TRANSIT_OLD_ACTIVITY_OPEN, new ArraySet<Integer>()));
    }

    @Test
    public void testGetRemoteAnimationOverrideTransitionController() {
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final RemoteAnimationDefinition definition = new RemoteAnimationDefinition();
        final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                new TestRemoteAnimationRunner(), 10, 1);
        definition.addRemoteAnimation(TRANSIT_OLD_ACTIVITY_OPEN, adapter);
        mAppTransitionController.registerRemoteAnimations(definition);

        assertEquals(adapter,
                mAppTransitionController.getRemoteAnimationOverride(
                        activity, TRANSIT_OLD_ACTIVITY_OPEN, new ArraySet<Integer>()));
        assertEquals(adapter,
                mAppTransitionController.getRemoteAnimationOverride(
                        null, TRANSIT_OLD_ACTIVITY_OPEN, new ArraySet<Integer>()));
    }

    @Test
    public void testGetRemoteAnimationOverrideBoth() {
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final RemoteAnimationDefinition definition1 = new RemoteAnimationDefinition();
        final RemoteAnimationAdapter adapter1 = new RemoteAnimationAdapter(
                new TestRemoteAnimationRunner(), 10, 1);
        definition1.addRemoteAnimation(TRANSIT_OLD_ACTIVITY_OPEN, adapter1);
        activity.registerRemoteAnimations(definition1);

        final RemoteAnimationDefinition definition2 = new RemoteAnimationDefinition();
        final RemoteAnimationAdapter adapter2 = new RemoteAnimationAdapter(
                new TestRemoteAnimationRunner(), 10, 1);
        definition2.addRemoteAnimation(TRANSIT_OLD_KEYGUARD_UNOCCLUDE, adapter2);
        mAppTransitionController.registerRemoteAnimations(definition2);

        assertEquals(adapter2,
                mAppTransitionController.getRemoteAnimationOverride(
                        activity, TRANSIT_OLD_KEYGUARD_UNOCCLUDE, new ArraySet<Integer>()));
        assertEquals(adapter2,
                mAppTransitionController.getRemoteAnimationOverride(
                        null, TRANSIT_OLD_KEYGUARD_UNOCCLUDE, new ArraySet<Integer>()));
    }

    @Test
    public void testGetRemoteAnimationOverrideWindowContainerHasPriority() {
        final ActivityRecord activity = createActivityRecord(mDisplayContent);
        final RemoteAnimationDefinition definition1 = new RemoteAnimationDefinition();
        final RemoteAnimationAdapter adapter1 = new RemoteAnimationAdapter(
                new TestRemoteAnimationRunner(), 10, 1);
        definition1.addRemoteAnimation(TRANSIT_OLD_ACTIVITY_OPEN, adapter1);
        activity.registerRemoteAnimations(definition1);

        final RemoteAnimationDefinition definition2 = new RemoteAnimationDefinition();
        final RemoteAnimationAdapter adapter2 = new RemoteAnimationAdapter(
                new TestRemoteAnimationRunner(), 10, 1);
        definition2.addRemoteAnimation(TRANSIT_OLD_ACTIVITY_OPEN, adapter2);
        mAppTransitionController.registerRemoteAnimations(definition2);

        assertEquals(adapter1,
                mAppTransitionController.getRemoteAnimationOverride(
                        activity, TRANSIT_OLD_ACTIVITY_OPEN, new ArraySet<Integer>()));
    }
}