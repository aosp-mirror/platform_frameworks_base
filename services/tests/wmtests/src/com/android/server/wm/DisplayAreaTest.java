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

package com.android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_MULTI_WINDOW;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.server.wm.ActivityTaskSupervisor.ON_TOP;
import static com.android.server.wm.DisplayArea.Type.ABOVE_TASKS;
import static com.android.server.wm.DisplayArea.Type.ANY;
import static com.android.server.wm.DisplayArea.Type.BELOW_TASKS;
import static com.android.server.wm.DisplayArea.Type.checkChild;
import static com.android.server.wm.DisplayArea.Type.checkSiblings;
import static com.android.server.wm.DisplayArea.Type.typeOf;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;
import static com.android.server.wm.testing.Assert.assertThrows;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.View;
import android.view.WindowManager;
import android.window.DisplayAreaInfo;
import android.window.IDisplayAreaOrganizer;

import com.google.android.collect.Lists;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Tests for the {@link DisplayArea} container.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayAreaTest
 */
@Presubmit
@RunWith(WindowTestRunner.class)
public class DisplayAreaTest extends WindowTestsBase {

    @Test
    public void testDisplayArea_positionChanged_throwsIfIncompatibleChild() {
        DisplayArea<WindowContainer> parent = new DisplayArea<>(mWm, BELOW_TASKS, "Parent");
        DisplayArea<WindowContainer> child = new DisplayArea<>(mWm, ANY, "Child");

        assertThrows(IllegalStateException.class, () -> parent.addChild(child, 0));
    }

    @Test
    public void testType_typeOf() {
        assertEquals(ABOVE_TASKS, typeOf(new DisplayArea<>(mWm, ABOVE_TASKS, "test")));
        assertEquals(ANY, typeOf(new DisplayArea<>(mWm, ANY, "test")));
        assertEquals(BELOW_TASKS, typeOf(new DisplayArea<>(mWm, BELOW_TASKS, "test")));

        assertEquals(ABOVE_TASKS, typeOf(createWindowToken(TYPE_APPLICATION_OVERLAY)));
        assertEquals(ABOVE_TASKS, typeOf(createWindowToken(TYPE_PRESENTATION)));
        assertEquals(BELOW_TASKS, typeOf(createWindowToken(TYPE_WALLPAPER)));

        assertThrows(IllegalArgumentException.class, () -> typeOf(mock(ActivityRecord.class)));
        assertThrows(IllegalArgumentException.class, () -> typeOf(mock(WindowContainer.class)));
    }

    @Test
    public void testType_checkSiblings() {
        checkSiblings(BELOW_TASKS, BELOW_TASKS);
        checkSiblings(BELOW_TASKS, ANY);
        checkSiblings(BELOW_TASKS, ABOVE_TASKS);
        checkSiblings(ANY, ABOVE_TASKS);
        checkSiblings(ABOVE_TASKS, ABOVE_TASKS);
        checkSiblings(ANY, ANY);

        assertThrows(IllegalStateException.class, () -> checkSiblings(ABOVE_TASKS, BELOW_TASKS));
        assertThrows(IllegalStateException.class, () -> checkSiblings(ABOVE_TASKS, ANY));
        assertThrows(IllegalStateException.class, () -> checkSiblings(ANY, BELOW_TASKS));
    }

    @Test
    public void testType_checkChild() {
        checkChild(ANY, ANY);
        checkChild(ANY, ABOVE_TASKS);
        checkChild(ANY, BELOW_TASKS);
        checkChild(ABOVE_TASKS, ABOVE_TASKS);
        checkChild(BELOW_TASKS, BELOW_TASKS);

        assertThrows(IllegalStateException.class, () -> checkChild(ABOVE_TASKS, BELOW_TASKS));
        assertThrows(IllegalStateException.class, () -> checkChild(ABOVE_TASKS, ANY));
        assertThrows(IllegalStateException.class, () -> checkChild(BELOW_TASKS, ABOVE_TASKS));
        assertThrows(IllegalStateException.class, () -> checkChild(BELOW_TASKS, ANY));
    }

    @Test
    public void testAsDisplayArea() {
        final WindowContainer windowContainer = new WindowContainer(mWm);
        final DisplayArea<WindowContainer> displayArea = new DisplayArea<>(mWm, ANY, "DA");
        final TaskDisplayArea taskDisplayArea = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA", FEATURE_DEFAULT_TASK_CONTAINER);

        assertThat(windowContainer.asDisplayArea()).isNull();
        assertThat(displayArea.asDisplayArea()).isEqualTo(displayArea);
        assertThat(taskDisplayArea.asDisplayArea()).isEqualTo(taskDisplayArea);
    }

    @Test
    public void testForAllTaskDisplayAreas_onlyTraversesDisplayAreaOfTypeAny() {
        final RootDisplayArea root =
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWm);
        final Predicate<TaskDisplayArea> callback0 = tda -> false;
        final Consumer<TaskDisplayArea> callback1 = tda -> { };
        final BiFunction<TaskDisplayArea, Integer, Integer> callback2 = (tda, result) -> result;
        final Function<TaskDisplayArea, TaskDisplayArea> callback3 = tda -> null;

        // Don't traverse the child if the current DA has type BELOW_TASKS
        final DisplayArea<WindowContainer> da1 = new DisplayArea<>(mWm, BELOW_TASKS, "DA1");
        final DisplayArea<WindowContainer> da2 = new DisplayArea<>(mWm, BELOW_TASKS, "DA2");
        root.addChild(da1, POSITION_BOTTOM);
        da1.addChild(da2, POSITION_TOP);
        spyOn(da2);

        da1.forAllTaskDisplayAreas(callback0);
        da1.forAllTaskDisplayAreas(callback1);
        da1.reduceOnAllTaskDisplayAreas(callback2, 0);
        da1.getItemFromTaskDisplayAreas(callback3);

        verifyZeroInteractions(da2);

        // Traverse the child if the current DA has type ANY
        final DisplayArea<WindowContainer> da3 = new DisplayArea<>(mWm, ANY, "DA3");
        final DisplayArea<WindowContainer> da4 = new DisplayArea<>(mWm, ANY, "DA4");
        root.addChild(da3, POSITION_TOP);
        da3.addChild(da4, POSITION_TOP);
        spyOn(da4);

        da3.forAllTaskDisplayAreas(callback0);
        da3.forAllTaskDisplayAreas(callback1);
        da3.reduceOnAllTaskDisplayAreas(callback2, 0);
        da3.getItemFromTaskDisplayAreas(callback3);

        verify(da4).forAllTaskDisplayAreas(callback0, true /* traverseTopToBottom */);
        verify(da4).forAllTaskDisplayAreas(callback1, true /* traverseTopToBottom */);
        verify(da4).reduceOnAllTaskDisplayAreas(callback2, 0 /* initValue */,
                true /* traverseTopToBottom */);
        verify(da4).getItemFromTaskDisplayAreas(
                callback3, true /* traverseTopToBottom */);

        // Don't traverse the child if the current DA has type ABOVE_TASKS
        final DisplayArea<WindowContainer> da5 = new DisplayArea<>(mWm, ABOVE_TASKS, "DA5");
        final DisplayArea<WindowContainer> da6 = new DisplayArea<>(mWm, ABOVE_TASKS, "DA6");
        root.addChild(da5, POSITION_TOP);
        da5.addChild(da6, POSITION_TOP);
        spyOn(da6);

        da5.forAllTaskDisplayAreas(callback0);
        da5.forAllTaskDisplayAreas(callback1);
        da5.reduceOnAllTaskDisplayAreas(callback2, 0);
        da5.getItemFromTaskDisplayAreas(callback3);

        verifyZeroInteractions(da6);
    }

    @Test
    public void testForAllTaskDisplayAreas_appliesOnTaskDisplayAreaInOrder() {
        final RootDisplayArea root =
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWm);
        final DisplayArea<DisplayArea> da1 =
                new DisplayArea<>(mWm, ANY, "DA1");
        final DisplayArea<DisplayArea> da2 =
                new DisplayArea<>(mWm, ANY, "DA2");
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA2", FEATURE_VENDOR_FIRST);
        final TaskDisplayArea tda3 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA3", FEATURE_VENDOR_FIRST + 1);
        root.addChild(da1, POSITION_TOP);
        root.addChild(da2, POSITION_TOP);
        da1.addChild(tda1, POSITION_TOP);
        da2.addChild(tda2, POSITION_TOP);
        da2.addChild(tda3, POSITION_TOP);

        /*  The hierarchy looks like this
            Root
              - DA1
                - TDA1 ------ bottom
              - DA2
                - TDA2
                - TDA3 ------ top
         */

        // Test forAllTaskDisplayAreas(Consumer<TaskDisplayArea>)
        List<TaskDisplayArea> actualOrder = new ArrayList<>();
        root.forAllTaskDisplayAreas(tda -> {
            actualOrder.add(tda);
        });

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda3, tda2, tda1));

        // Test forAllTaskDisplayAreas(Consumer<TaskDisplayArea>, boolean)
        actualOrder.clear();
        root.forAllTaskDisplayAreas(tda -> {
            actualOrder.add(tda);
        }, false /* traverseTopToBottom */);

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda1, tda2, tda3));

        // Test forAllTaskDisplayAreas(Function<TaskDisplayArea, Boolean>)
        actualOrder.clear();
        root.forAllTaskDisplayAreas(tda -> {
            actualOrder.add(tda);
            return false;
        });

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda3, tda2, tda1));

        // Test forAllTaskDisplayAreas(Function<TaskDisplayArea, Boolean>, boolean)
        actualOrder.clear();
        root.forAllTaskDisplayAreas(tda -> {
            actualOrder.add(tda);
            return false;
        }, false /* traverseTopToBottom */);

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda1, tda2, tda3));

        // Test forAllTaskDisplayAreas(BiFunction<TaskDisplayArea, R, R>, R)
        actualOrder.clear();
        root.reduceOnAllTaskDisplayAreas((tda, result) -> {
            actualOrder.add(tda);
            return result;
        }, 0 /* initValue */);

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda3, tda2, tda1));

        // Test forAllTaskDisplayAreas(BiFunction<TaskDisplayArea, R, R>, R, boolean)
        actualOrder.clear();
        root.reduceOnAllTaskDisplayAreas((tda, result) -> {
            actualOrder.add(tda);
            return result;
        }, 0 /* initValue */, false /* traverseTopToBottom */);

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda1, tda2, tda3));

        // Test <R> R getItemFromTaskDisplayAreas(Function<TaskDisplayArea, R> callback)
        actualOrder.clear();
        root.getItemFromTaskDisplayAreas(tda -> {
            actualOrder.add(tda);
            return null;
        });

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda3, tda2, tda1));

        // Test <R> R getItemFromTaskDisplayAreas(Function<TaskDisplayArea, R> callback, boolean)
        actualOrder.clear();
        root.getItemFromTaskDisplayAreas(tda -> {
            actualOrder.add(tda);
            return null;
        }, false /* traverseTopToBottom */);

        assertThat(actualOrder).isEqualTo(Lists.newArrayList(tda1, tda2, tda3));
    }

    @Test
    public void testForAllTaskDisplayAreas_returnsWhenCallbackReturnTrue() {
        final RootDisplayArea root =
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWm);
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA2", FEATURE_VENDOR_FIRST);
        root.addChild(tda1, POSITION_TOP);
        root.addChild(tda2, POSITION_TOP);

        /*  The hierarchy looks like this
            Root
              - TDA1 ------ bottom
              - TDA2 ------ top
         */

        root.forAllTaskDisplayAreas(tda -> {
            assertThat(tda).isEqualTo(tda2);
            return true;
        });

        root.forAllTaskDisplayAreas(tda -> {
            assertThat(tda).isEqualTo(tda1);
            return true;
        }, false /* traverseTopToBottom */);
    }

    @Test
    public void testReduceOnAllTaskDisplayAreas_returnsTheAccumulativeResult() {
        final RootDisplayArea root =
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWm);
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA2", FEATURE_VENDOR_FIRST);
        root.addChild(tda1, POSITION_TOP);
        root.addChild(tda2, POSITION_TOP);

        /*  The hierarchy looks like this
            Root
              - TDA1 ------ bottom
              - TDA2 ------ top
         */

        String accumulativeName = root.reduceOnAllTaskDisplayAreas((tda, result) ->
                result + tda.getName(), "" /* initValue */);
        assertThat(accumulativeName).isEqualTo("TDA2TDA1");

        accumulativeName = root.reduceOnAllTaskDisplayAreas((tda, result) ->
                result + tda.getName(), "" /* initValue */, false /* traverseTopToBottom */);
        assertThat(accumulativeName).isEqualTo("TDA1TDA2");
    }

    @Test
    public void testGetItemFromTaskDisplayAreas_returnsWhenCallbackReturnNotNull() {
        final RootDisplayArea root =
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWm);
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWm, "TDA2", FEATURE_VENDOR_FIRST);
        root.addChild(tda1, POSITION_TOP);
        root.addChild(tda2, POSITION_TOP);

        /*  The hierarchy looks like this
            Root
              - TDA1 ------ bottom
              - TDA2 ------ top
         */

        TaskDisplayArea result = root.getItemFromTaskDisplayAreas(tda -> {
            assertThat(tda).isEqualTo(tda2);
            return tda;
        });

        assertThat(result).isEqualTo(tda2);

        result = root.getItemFromTaskDisplayAreas(tda -> {
            assertThat(tda).isEqualTo(tda1);
            return tda;
        }, false /* traverseTopToBottom */);

        assertThat(result).isEqualTo(tda1);
    }

    @Test
    public void testSetMaxBounds() {
        final Rect parentBounds = new Rect(0, 0, 100, 100);
        final Rect childBounds1 = new Rect(parentBounds.left, parentBounds.top,
                parentBounds.right / 2, parentBounds.bottom);
        final Rect childBounds2 = new Rect(parentBounds.right / 2, parentBounds.top,
                parentBounds.right, parentBounds.bottom);
        TestDisplayArea parentDa = new TestDisplayArea(mWm, parentBounds, "Parent");
        TestDisplayArea childDa1 = new TestDisplayArea(mWm, childBounds1, "Child1");
        TestDisplayArea childDa2 = new TestDisplayArea(mWm, childBounds2, "Child2");
        parentDa.addChild(childDa1, 0);
        parentDa.addChild(childDa2, 1);

        assertEquals(parentBounds, parentDa.getMaxBounds());
        assertEquals(childBounds1, childDa1.getMaxBounds());
        assertEquals(childBounds2, childDa2.getMaxBounds());

        final WindowToken windowToken = createWindowToken(TYPE_APPLICATION);
        childDa1.addChild(windowToken, 0);

        assertEquals("DisplayArea's children must have the same max bounds as itself",
                childBounds1, windowToken.getMaxBounds());
    }

    @Test
    public void testRestrictAppBoundsToOverrideBounds() {
        final RootDisplayArea root =
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWm);
        final DisplayArea<DisplayArea> da = new DisplayArea<>(mWm, ANY, "Test_DA");
        root.addChild(da, POSITION_TOP);
        final Rect displayBounds = new Rect(0, 0, 1800, 2800);
        final Rect displayAppBounds = new Rect(0, 100, 1800, 2800);
        final Rect daBounds = new Rect(0, 1400, 1800, 2800);
        root.setBounds(displayBounds);

        // DA inherit parent app bounds.
        final Configuration displayConfig = new Configuration();
        displayConfig.windowConfiguration.setAppBounds(displayAppBounds);
        root.onRequestedOverrideConfigurationChanged(displayConfig);

        assertEquals(displayAppBounds, da.getConfiguration().windowConfiguration.getAppBounds());

        // Restrict DA appBounds to override Bounds
        da.setBounds(daBounds);

        final Rect expectedDaAppBounds = new Rect(daBounds);
        expectedDaAppBounds.intersect(displayAppBounds);
        assertEquals(expectedDaAppBounds, da.getConfiguration().windowConfiguration.getAppBounds());
    }

    @Test
    public void testGetOrientation() {
        final DisplayArea.Tokens area = new DisplayArea.Tokens(mWm, ABOVE_TASKS, "test");
        final WindowToken token = createWindowToken(TYPE_APPLICATION_OVERLAY);
        spyOn(token);
        doReturn(mock(DisplayContent.class)).when(token).getDisplayContent();
        doNothing().when(token).setParent(any());
        final WindowState win = createWindowState(token);
        spyOn(win);
        doNothing().when(win).setParent(any());
        win.mAttrs.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        token.addChild(win, 0);
        area.addChild(token);

        doReturn(true).when(win).isVisible();

        assertEquals("Visible window can request orientation",
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE,
                area.getOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR));

        doReturn(false).when(win).isVisible();

        assertEquals("Invisible window cannot request orientation",
                ActivityInfo.SCREEN_ORIENTATION_NOSENSOR,
                area.getOrientation(ActivityInfo.SCREEN_ORIENTATION_NOSENSOR));
    }

    @Test
    public void testSetIgnoreOrientationRequest() {
        final DisplayArea.Tokens area = new DisplayArea.Tokens(mWm, ABOVE_TASKS, "test");
        final WindowToken token = createWindowToken(TYPE_APPLICATION_OVERLAY);
        spyOn(token);
        doReturn(mock(DisplayContent.class)).when(token).getDisplayContent();
        doNothing().when(token).setParent(any());
        final WindowState win = createWindowState(token);
        spyOn(win);
        doNothing().when(win).setParent(any());
        win.mAttrs.screenOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        token.addChild(win, 0);
        area.addChild(token);
        doReturn(true).when(win).isVisible();

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, area.getOrientation());

        area.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        assertEquals(ActivityInfo.SCREEN_ORIENTATION_UNSET, area.getOrientation());
    }

    @Test
    public void testSetIgnoreOrientationRequest_notCallSuperOnDescendantOrientationChanged() {
        final TaskDisplayArea tda = mDisplayContent.getDefaultTaskDisplayArea();
        final Task stack =
                new TaskBuilder(mSupervisor).setOnTop(!ON_TOP).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getTopNonFinishingActivity();

        tda.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        activity.setRequestedOrientation(SCREEN_ORIENTATION_LANDSCAPE);

        verify(tda).onDescendantOrientationChanged(any());
        verify(mDisplayContent, never()).onDescendantOrientationChanged(any());

        tda.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);
        activity.setRequestedOrientation(SCREEN_ORIENTATION_PORTRAIT);

        verify(tda, times(2)).onDescendantOrientationChanged(any());
        verify(mDisplayContent).onDescendantOrientationChanged(any());
    }

    @Test
    public void testSetIgnoreOrientationRequest_updateOrientationRequestingTaskDisplayArea() {
        final TaskDisplayArea tda = mDisplayContent.getDefaultTaskDisplayArea();
        final Task stack =
                new TaskBuilder(mSupervisor).setOnTop(!ON_TOP).setCreateActivity(true).build();
        final ActivityRecord activity = stack.getTopNonFinishingActivity();

        mDisplayContent.setFocusedApp(activity);
        assertThat(mDisplayContent.getOrientationRequestingTaskDisplayArea()).isEqualTo(tda);

        // TDA is no longer handling orientation request, clear the last focused TDA.
        tda.setIgnoreOrientationRequest(true /* ignoreOrientationRequest */);

        assertThat(mDisplayContent.getOrientationRequestingTaskDisplayArea()).isNull();

        // TDA now handles orientation request, update last focused TDA based on the focused app.
        tda.setIgnoreOrientationRequest(false /* ignoreOrientationRequest */);

        assertThat(mDisplayContent.getOrientationRequestingTaskDisplayArea()).isEqualTo(tda);
    }

    @Test
    public void testDisplayContentUpdateDisplayAreaOrganizers_onDisplayAreaAppeared() {
        final DisplayArea<WindowContainer> displayArea = new DisplayArea<>(
                mWm, BELOW_TASKS, "NewArea", FEATURE_VENDOR_FIRST);
        final IDisplayAreaOrganizer mockDisplayAreaOrganizer = mock(IDisplayAreaOrganizer.class);
        spyOn(mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController);
        when(mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController
                .getOrganizerByFeature(FEATURE_VENDOR_FIRST))
                .thenReturn(mockDisplayAreaOrganizer);

        mDisplayContent.addChild(displayArea, 0);
        mDisplayContent.updateDisplayAreaOrganizers();

        assertEquals(mockDisplayAreaOrganizer, displayArea.mOrganizer);
        verify(mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController)
                .onDisplayAreaAppeared(
                        eq(mockDisplayAreaOrganizer),
                        argThat(it -> it == displayArea && it.getSurfaceControl() != null));
    }

    @Test
    public void testRemoveImmediately_onDisplayAreaVanished() {
        final DisplayArea<WindowContainer> displayArea = new DisplayArea<>(
                mWm, BELOW_TASKS, "NewArea", FEATURE_VENDOR_FIRST);
        final IDisplayAreaOrganizer mockDisplayAreaOrganizer = mock(IDisplayAreaOrganizer.class);
        doReturn(mock(IBinder.class)).when(mockDisplayAreaOrganizer).asBinder();
        displayArea.mOrganizer = mockDisplayAreaOrganizer;
        spyOn(mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController);
        mDisplayContent.addChild(displayArea, 0);

        displayArea.removeImmediately();

        assertNull(displayArea.mOrganizer);
        verify(mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController)
                .onDisplayAreaVanished(mockDisplayAreaOrganizer, displayArea);
    }

    @Test
    public void testGetDisplayAreaInfo() {
        final DisplayArea<WindowContainer> displayArea = new DisplayArea<>(
                mWm, BELOW_TASKS, "NewArea", FEATURE_VENDOR_FIRST);
        mDisplayContent.addChild(displayArea, 0);
        final DisplayAreaInfo info = displayArea.getDisplayAreaInfo();

        assertThat(info.token).isEqualTo(displayArea.mRemoteToken.toWindowContainerToken());
        assertThat(info.configuration).isEqualTo(displayArea.getConfiguration());
        assertThat(info.displayId).isEqualTo(mDisplayContent.getDisplayId());
        assertThat(info.featureId).isEqualTo(displayArea.mFeatureId);
        assertThat(info.rootDisplayAreaId).isEqualTo(mDisplayContent.mFeatureId);

        final TaskDisplayArea tda = mDisplayContent.getDefaultTaskDisplayArea();
        final int tdaIndex = tda.getParent().mChildren.indexOf(tda);
        final RootDisplayArea root =
                new DisplayAreaGroup(mWm, "TestRoot", FEATURE_VENDOR_FIRST + 1);
        mDisplayContent.addChild(root, tdaIndex + 1);
        displayArea.reparent(root, 0);

        final DisplayAreaInfo info2 = displayArea.getDisplayAreaInfo();

        assertThat(info2.rootDisplayAreaId).isEqualTo(root.mFeatureId);
    }

    @Test
    public void testRegisterSameFeatureOrganizer_expectThrowsException() {
        final IDisplayAreaOrganizer mockDisplayAreaOrganizer = mock(IDisplayAreaOrganizer.class);
        final IBinder binder = mock(IBinder.class);
        doReturn(true).when(binder).isBinderAlive();
        doReturn(binder).when(mockDisplayAreaOrganizer).asBinder();
        final DisplayAreaOrganizerController controller =
                mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController;
        controller.registerOrganizer(mockDisplayAreaOrganizer, FEATURE_VENDOR_FIRST);
        assertThrows(IllegalStateException.class,
                () -> controller.registerOrganizer(mockDisplayAreaOrganizer, FEATURE_VENDOR_FIRST));
    }

    @Test
    public void testRegisterUnregisterOrganizer() {
        final IDisplayAreaOrganizer mockDisplayAreaOrganizer = mock(IDisplayAreaOrganizer.class);
        doReturn(mock(IBinder.class)).when(mockDisplayAreaOrganizer).asBinder();
        final DisplayAreaOrganizerController controller =
                mWm.mAtmService.mWindowOrganizerController.mDisplayAreaOrganizerController;
        controller.registerOrganizer(mockDisplayAreaOrganizer, FEATURE_VENDOR_FIRST);
        controller.unregisterOrganizer(mockDisplayAreaOrganizer);
        controller.registerOrganizer(mockDisplayAreaOrganizer, FEATURE_VENDOR_FIRST);
    }

    @Test
    public void testSetAlwaysOnTop_movesDisplayAreaToTop() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        DisplayArea<WindowContainer> parent = new TestDisplayArea(mWm, bounds, "Parent");
        parent.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        DisplayArea<WindowContainer> child1 = new TestDisplayArea(mWm, bounds, "Child1");
        child1.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        DisplayArea<WindowContainer> child2 = new TestDisplayArea(mWm, bounds, "Child2");
        child2.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        parent.addChild(child2, 0);
        parent.addChild(child1, 1);

        child2.setAlwaysOnTop(true);

        assertEquals(parent.getChildAt(1), child2);
        assertThat(child2.isAlwaysOnTop()).isTrue();
    }

    @Test
    public void testDisplayAreaRequestsTopPosition_alwaysOnTopSiblingExists_doesNotMoveToTop() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        DisplayArea<WindowContainer> parent = new TestDisplayArea(mWm, bounds, "Parent");
        parent.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        DisplayArea<WindowContainer> alwaysOnTopChild = new TestDisplayArea(mWm, bounds,
                "AlwaysOnTopChild");
        alwaysOnTopChild.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        DisplayArea<WindowContainer> child = new TestDisplayArea(mWm, bounds, "Child");
        child.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        parent.addChild(alwaysOnTopChild, 0);
        parent.addChild(child, 1);
        alwaysOnTopChild.setAlwaysOnTop(true);

        parent.positionChildAt(POSITION_TOP, child, false /* includingParents */);

        assertEquals(parent.getChildAt(1), alwaysOnTopChild);
        assertEquals(parent.getChildAt(0), child);
    }

    @Test
    public void testAlwaysOnTopDisplayArea_requestsNonTopLocation_doesNotMove() {
        final Rect bounds = new Rect(0, 0, 100, 100);
        DisplayArea<WindowContainer> parent = new TestDisplayArea(mWm, bounds, "Parent");
        parent.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        DisplayArea<WindowContainer> alwaysOnTopChild = new TestDisplayArea(mWm, bounds,
                "AlwaysOnTopChild");
        alwaysOnTopChild.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        DisplayArea<WindowContainer> child = new TestDisplayArea(mWm, bounds, "Child");
        child.setWindowingMode(WINDOWING_MODE_MULTI_WINDOW);
        parent.addChild(alwaysOnTopChild, 0);
        parent.addChild(child, 1);
        alwaysOnTopChild.setAlwaysOnTop(true);

        parent.positionChildAt(POSITION_BOTTOM, alwaysOnTopChild, false /* includingParents */);

        assertEquals(parent.getChildAt(1), alwaysOnTopChild);
        assertEquals(parent.getChildAt(0), child);
    }

    private static class TestDisplayArea<T extends WindowContainer> extends DisplayArea<T> {
        private TestDisplayArea(WindowManagerService wms, Rect bounds, String name) {
            super(wms, ANY, name);
            setBounds(bounds);
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceControlBuilder();
        }
    }

    private WindowState createWindowState(WindowToken token) {
        return new WindowState(mWm, mock(Session.class), new TestIWindow(), token,
                null /* parentWindow */, 0 /* appOp */, new WindowManager.LayoutParams(),
                View.VISIBLE, 0 /* ownerId */, 0 /* showUserId */,
                false /* ownerCanAddInternalSystemWindow */);
    }

    private WindowToken createWindowToken(int type) {
        return new WindowToken.Builder(mWm, new Binder(), type).build();
    }
}
