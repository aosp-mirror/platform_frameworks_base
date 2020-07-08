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

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_PRESENTATION;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;
import static android.window.DisplayAreaOrganizer.FEATURE_VENDOR_FIRST;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyZeroInteractions;

import android.os.Binder;
import android.platform.test.annotations.Presubmit;

import com.google.android.collect.Lists;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Tests for the {@link DisplayArea} container.
 *
 * Build/Install/Run:
 *  atest WmTests:DisplayAreaTest
 */
@Presubmit
public class DisplayAreaTest {

    @Rule
    public SystemServicesTestRule mWmsRule = new SystemServicesTestRule();

    private WindowManagerService mWms;

    @Before
    public void setup() {
        mWms = mWmsRule.getWindowManagerService();
    }

    @Test
    public void testDisplayArea_positionChanged_throwsIfIncompatibleChild() {
        DisplayArea<WindowContainer> parent = new DisplayArea<>(mWms, BELOW_TASKS, "Parent");
        DisplayArea<WindowContainer> child = new DisplayArea<>(mWms, ANY, "Child");

        assertThrows(IllegalStateException.class, () -> parent.addChild(child, 0));
    }

    @Test
    public void testType_typeOf() {
        assertEquals(ABOVE_TASKS, typeOf(new DisplayArea<>(mWms, ABOVE_TASKS, "test")));
        assertEquals(ANY, typeOf(new DisplayArea<>(mWms, ANY, "test")));
        assertEquals(BELOW_TASKS, typeOf(new DisplayArea<>(mWms, BELOW_TASKS, "test")));

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
        final WindowContainer windowContainer = new WindowContainer(mWms);
        final DisplayArea<WindowContainer> displayArea = new DisplayArea<>(mWms, ANY, "DA");
        final TaskDisplayArea taskDisplayArea = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA", FEATURE_DEFAULT_TASK_CONTAINER);

        assertThat(windowContainer.asDisplayArea()).isNull();
        assertThat(displayArea.asDisplayArea()).isEqualTo(displayArea);
        assertThat(taskDisplayArea.asDisplayArea()).isEqualTo(taskDisplayArea);
    }

    @Test
    public void testForAllTaskDisplayAreas_onlyTraversesDisplayAreaOfTypeAny() {
        final RootDisplayArea root =
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWms);
        final Function<TaskDisplayArea, Boolean> callback0 = tda -> false;
        final Consumer<TaskDisplayArea> callback1 = tda -> { };
        final BiFunction<TaskDisplayArea, Integer, Integer> callback2 = (tda, result) -> result;
        final Function<TaskDisplayArea, TaskDisplayArea> callback3 = tda -> null;

        // Don't traverse the child if the current DA has type BELOW_TASKS
        final DisplayArea<WindowContainer> da1 = new DisplayArea<>(mWms, BELOW_TASKS, "DA1");
        final DisplayArea<WindowContainer> da2 = new DisplayArea<>(mWms, BELOW_TASKS, "DA2");
        root.addChild(da1, POSITION_BOTTOM);
        da1.addChild(da2, POSITION_TOP);
        spyOn(da2);

        da1.forAllTaskDisplayAreas(callback0);
        da1.forAllTaskDisplayAreas(callback1);
        da1.reduceOnAllTaskDisplayAreas(callback2, 0);
        da1.getItemFromTaskDisplayAreas(callback3);

        verifyZeroInteractions(da2);

        // Traverse the child if the current DA has type ANY
        final DisplayArea<WindowContainer> da3 = new DisplayArea<>(mWms, ANY, "DA3");
        final DisplayArea<WindowContainer> da4 = new DisplayArea<>(mWms, ANY, "DA4");
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
        final DisplayArea<WindowContainer> da5 = new DisplayArea<>(mWms, ABOVE_TASKS, "DA5");
        final DisplayArea<WindowContainer> da6 = new DisplayArea<>(mWms, ABOVE_TASKS, "DA6");
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
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWms);
        final DisplayArea<DisplayArea> da1 =
                new DisplayArea<>(mWms, ANY, "DA1");
        final DisplayArea<DisplayArea> da2 =
                new DisplayArea<>(mWms, ANY, "DA2");
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA2", FEATURE_VENDOR_FIRST);
        final TaskDisplayArea tda3 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA3", FEATURE_VENDOR_FIRST + 1);
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
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWms);
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA2", FEATURE_VENDOR_FIRST);
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
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWms);
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA2", FEATURE_VENDOR_FIRST);
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
                new DisplayAreaPolicyBuilderTest.SurfacelessDisplayAreaRoot(mWms);
        final TaskDisplayArea tda1 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA1", FEATURE_DEFAULT_TASK_CONTAINER);
        final TaskDisplayArea tda2 = new TaskDisplayArea(null /* displayContent */,
                mWms, "TDA2", FEATURE_VENDOR_FIRST);
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

    private WindowToken createWindowToken(int type) {
        return new WindowToken(mWmsRule.getWindowManagerService(), new Binder(),
                type, false /* persist */, null /* displayContent */,
                false /* canManageTokens */);
    }
}
