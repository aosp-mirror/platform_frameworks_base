/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static android.view.InsetsState.ITYPE_LOCAL_NAVIGATION_BAR_1;
import static android.view.InsetsState.ITYPE_LOCAL_NAVIGATION_BAR_2;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_BASE_APPLICATION;
import static android.view.WindowManager.TRANSIT_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_CLOSE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_FRAGMENT_CHANGE;
import static android.view.WindowManager.TRANSIT_OLD_TASK_OPEN;
import static android.view.WindowManager.TRANSIT_OPEN;
import static android.window.DisplayAreaOrganizer.FEATURE_DEFAULT_TASK_CONTAINER;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyFloat;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.never;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.reset;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spy;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.server.wm.DisplayArea.Type.ANY;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_ALL;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_APP_TRANSITION;
import static com.android.server.wm.SurfaceAnimator.ANIMATION_TYPE_SCREEN_ROTATION;
import static com.android.server.wm.WindowContainer.AnimationFlags.CHILDREN;
import static com.android.server.wm.WindowContainer.AnimationFlags.PARENTS;
import static com.android.server.wm.WindowContainer.AnimationFlags.TRANSITION;
import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.clearInvocations;

import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.view.IRemoteAnimationFinishedCallback;
import android.view.IRemoteAnimationRunner;
import android.view.InsetsSource;
import android.view.RemoteAnimationAdapter;
import android.view.RemoteAnimationTarget;
import android.view.SurfaceControl;
import android.view.SurfaceSession;
import android.view.WindowManager;

import androidx.test.filters.SmallTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.Comparator;


/**
 * Test class for {@link WindowContainer}.
 *
 * Build/Install/Run:
 *  atest WmTests:WindowContainerTests
 */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner.class)
public class WindowContainerTests extends WindowTestsBase {

    @Test
    public void testCreation() {
        final TestWindowContainer w = new TestWindowContainerBuilder(mWm).setLayer(0).build();
        assertNull("window must have no parent", w.getParentWindow());
        assertEquals("window must have no children", 0, w.getChildrenCount());
    }

    @Test
    public void testAdd() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer layer1 = root.addChildWindow(builder.setLayer(1));
        final TestWindowContainer secondLayer1 = root.addChildWindow(builder.setLayer(1));
        final TestWindowContainer layer2 = root.addChildWindow(builder.setLayer(2));
        final TestWindowContainer layerNeg1 = root.addChildWindow(builder.setLayer(-1));
        final TestWindowContainer layerNeg2 = root.addChildWindow(builder.setLayer(-2));
        final TestWindowContainer secondLayerNeg1 = root.addChildWindow(builder.setLayer(-1));
        final TestWindowContainer layer0 = root.addChildWindow(builder.setLayer(0));

        assertEquals(7, root.getChildrenCount());

        assertEquals(root, layer1.getParentWindow());
        assertEquals(root, secondLayer1.getParentWindow());
        assertEquals(root, layer2.getParentWindow());
        assertEquals(root, layerNeg1.getParentWindow());
        assertEquals(root, layerNeg2.getParentWindow());
        assertEquals(root, secondLayerNeg1.getParentWindow());
        assertEquals(root, layer0.getParentWindow());

        assertEquals(layerNeg2, root.getChildAt(0));
        assertEquals(secondLayerNeg1, root.getChildAt(1));
        assertEquals(layerNeg1, root.getChildAt(2));
        assertEquals(layer0, root.getChildAt(3));
        assertEquals(layer1, root.getChildAt(4));
        assertEquals(secondLayer1, root.getChildAt(5));
        assertEquals(layer2, root.getChildAt(6));

        assertTrue(layer1.mOnParentChangedCalled);
        assertTrue(secondLayer1.mOnParentChangedCalled);
        assertTrue(layer2.mOnParentChangedCalled);
        assertTrue(layerNeg1.mOnParentChangedCalled);
        assertTrue(layerNeg2.mOnParentChangedCalled);
        assertTrue(secondLayerNeg1.mOnParentChangedCalled);
        assertTrue(layer0.mOnParentChangedCalled);
    }

    @Test
    public void testAddChildSetsSurfacePosition() {
        reset(mTransaction);
        try (MockSurfaceBuildingContainer top = new MockSurfaceBuildingContainer(mWm)) {
            WindowContainer child = new WindowContainer(mWm);
            child.setBounds(1, 1, 10, 10);

            verify(mTransaction, never()).setPosition(any(), anyFloat(), anyFloat());
            top.addChild(child, 0);
            verify(mTransaction, times(1)).setPosition(any(), eq(1.f), eq(1.f));
        }
    }

    @Test
    public void testAdd_AlreadyHasParent() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();

        boolean gotException = false;
        try {
            child1.addChildWindow(child2);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);

        gotException = false;
        try {
            root.addChildWindow(child2);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);
    }

    @Test
    public void testHasChild() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow();
        final TestWindowContainer child21 = child2.addChildWindow();

        assertEquals(2, root.getChildrenCount());
        assertEquals(2, child1.getChildrenCount());
        assertEquals(1, child2.getChildrenCount());

        assertTrue(root.hasChild(child1));
        assertTrue(root.hasChild(child2));
        assertTrue(root.hasChild(child11));
        assertTrue(root.hasChild(child12));
        assertTrue(root.hasChild(child21));

        assertTrue(child1.hasChild(child11));
        assertTrue(child1.hasChild(child12));
        assertFalse(child1.hasChild(child21));

        assertTrue(child2.hasChild(child21));
        assertFalse(child2.hasChild(child11));
        assertFalse(child2.hasChild(child12));
    }

    @Test
    public void testRemoveImmediately() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow();
        final TestWindowContainer child21 = child2.addChildWindow();

        assertNotNull(child12.getParentWindow());
        child12.removeImmediately();
        assertNull(child12.getParentWindow());
        assertEquals(1, child1.getChildrenCount());
        assertFalse(child1.hasChild(child12));
        assertFalse(root.hasChild(child12));

        assertTrue(root.hasChild(child2));
        assertNotNull(child2.getParentWindow());
        child2.removeImmediately();
        assertNull(child2.getParentWindow());
        assertNull(child21.getParentWindow());
        assertEquals(0, child2.getChildrenCount());
        assertEquals(1, root.getChildrenCount());
        assertFalse(root.hasChild(child2));
        assertFalse(root.hasChild(child21));

        assertTrue(root.hasChild(child1));
        assertTrue(root.hasChild(child11));

        root.removeImmediately();
        assertEquals(0, root.getChildrenCount());
    }

    @Test
    public void testRemoveImmediatelyClearsLastSurfacePosition() {
        reset(mTransaction);
        try (MockSurfaceBuildingContainer top = new MockSurfaceBuildingContainer(mWm)) {
            final WindowContainer<WindowContainer> child1 = new WindowContainer(mWm);
            child1.setBounds(1, 1, 10, 10);

            top.addChild(child1, 0);
            assertEquals(1, child1.getLastSurfacePosition().x);
            assertEquals(1, child1.getLastSurfacePosition().y);

            WindowContainer child11 = new WindowContainer(mWm);
            child1.addChild(child11, 0);

            child1.setBounds(2, 2, 20, 20);
            assertEquals(2, child1.getLastSurfacePosition().x);
            assertEquals(2, child1.getLastSurfacePosition().y);

            child1.removeImmediately();
            assertEquals(0, child1.getLastSurfacePosition().x);
            assertEquals(0, child1.getLastSurfacePosition().y);
            assertEquals(0, child11.getLastSurfacePosition().x);
            assertEquals(0, child11.getLastSurfacePosition().y);
        }
    }

    @Test
    public void testRemoveImmediatelyClearsLeash() {
        final AnimationAdapter animAdapter = mock(AnimationAdapter.class);
        final WindowToken token = createTestWindowToken(TYPE_APPLICATION_OVERLAY, mDisplayContent);
        final SurfaceControl.Transaction t = token.getPendingTransaction();
        token.startAnimation(t, animAdapter, false /* hidden */,
                SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION);
        final ArgumentCaptor<SurfaceControl> leashCaptor =
                ArgumentCaptor.forClass(SurfaceControl.class);
        verify(animAdapter).startAnimation(leashCaptor.capture(), eq(t), anyInt(), any());
        assertTrue(token.mSurfaceAnimator.hasLeash());
        token.removeImmediately();
        assertFalse(token.mSurfaceAnimator.hasLeash());
        verify(t).remove(eq(leashCaptor.getValue()));
    }

    @Test
    public void testAddChildByIndex() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child = root.addChildWindow();

        final TestWindowContainer child2 = builder.setLayer(1).build();
        final TestWindowContainer child3 = builder.setLayer(2).build();
        final TestWindowContainer child4 = builder.setLayer(3).build();

        // Test adding at top.
        root.addChild(child2, POSITION_TOP);
        assertEquals(child2, root.getChildAt(root.getChildrenCount() - 1));

        // Test adding at bottom.
        root.addChild(child3, POSITION_BOTTOM);
        assertEquals(child3, root.getChildAt(0));

        // Test adding in the middle.
        root.addChild(child4, 1);
        assertEquals(child3, root.getChildAt(0));
        assertEquals(child4, root.getChildAt(1));
        assertEquals(child, root.getChildAt(2));
        assertEquals(child2, root.getChildAt(3));
    }

    @Test
    public void testPositionChildAt() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child3 = root.addChildWindow();

        // Test position at top.
        root.positionChildAt(POSITION_TOP, child1, false /* includingParents */);
        assertEquals(child1, root.getChildAt(root.getChildrenCount() - 1));

        // Test position at bottom.
        root.positionChildAt(POSITION_BOTTOM, child1, false /* includingParents */);
        assertEquals(child1, root.getChildAt(0));

        // Test position in the middle.
        root.positionChildAt(1, child3, false /* includingParents */);
        assertEquals(child1, root.getChildAt(0));
        assertEquals(child3, root.getChildAt(1));
        assertEquals(child2, root.getChildAt(2));
    }

    @Test
    public void testPositionChildAtIncludeParents() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow();
        final TestWindowContainer child13 = child1.addChildWindow();
        final TestWindowContainer child21 = child2.addChildWindow();
        final TestWindowContainer child22 = child2.addChildWindow();
        final TestWindowContainer child23 = child2.addChildWindow();

        // Test moving to top.
        child1.positionChildAt(POSITION_TOP, child11, true /* includingParents */);
        assertEquals(child12, child1.getChildAt(0));
        assertEquals(child13, child1.getChildAt(1));
        assertEquals(child11, child1.getChildAt(2));
        assertEquals(child2, root.getChildAt(0));
        assertEquals(child1, root.getChildAt(1));

        // Test moving to bottom.
        child1.positionChildAt(POSITION_BOTTOM, child11, true /* includingParents */);
        assertEquals(child11, child1.getChildAt(0));
        assertEquals(child12, child1.getChildAt(1));
        assertEquals(child13, child1.getChildAt(2));
        assertEquals(child1, root.getChildAt(0));
        assertEquals(child2, root.getChildAt(1));

        // Test moving to middle, includeParents shouldn't do anything.
        child2.positionChildAt(1, child21, true /* includingParents */);
        assertEquals(child11, child1.getChildAt(0));
        assertEquals(child12, child1.getChildAt(1));
        assertEquals(child13, child1.getChildAt(2));
        assertEquals(child22, child2.getChildAt(0));
        assertEquals(child21, child2.getChildAt(1));
        assertEquals(child23, child2.getChildAt(2));
        assertEquals(child1, root.getChildAt(0));
        assertEquals(child2, root.getChildAt(1));
    }

    @Test
    public void testIsAnimating_TransitionFlag() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();
        final TestWindowContainer child1 = root.addChildWindow(
                builder.setWaitForTransitionStart(true));

        assertFalse(root.isAnimating(TRANSITION));
        assertTrue(child1.isAnimating(TRANSITION));
    }

    @Test
    public void testIsAnimating_ParentsFlag() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();
        final TestWindowContainer child1 = root.addChildWindow(builder);
        final TestWindowContainer child2 = root.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child21 = child2.addChildWindow(builder.setIsAnimating(false));

        assertFalse(root.isAnimating());
        assertFalse(child1.isAnimating());
        assertFalse(child1.isAnimating(PARENTS));
        assertTrue(child2.isAnimating());
        assertTrue(child2.isAnimating(PARENTS));
        assertFalse(child21.isAnimating());
        assertTrue(child21.isAnimating(PARENTS));
    }

    @Test
    public void testIsAnimating_ChildrenFlag() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();
        final TestWindowContainer child1 = root.addChildWindow(builder);
        final TestWindowContainer child2 = root.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child11 = child1.addChildWindow(builder.setIsAnimating(true));

        assertFalse(root.isAnimating());
        assertTrue(root.isAnimating(CHILDREN));
        assertFalse(child1.isAnimating());
        assertTrue(child1.isAnimating(CHILDREN));
        assertTrue(child2.isAnimating());
        assertTrue(child2.isAnimating(CHILDREN));
        assertTrue(child11.isAnimating());
        assertTrue(child11.isAnimating(CHILDREN));
    }

    @Test
    public void testIsAnimating_combineFlags() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child21 = child2.addChildWindow();

        assertFalse(root.isAnimating(TRANSITION | PARENTS));
        assertTrue(child1.isAnimating(TRANSITION | PARENTS));
        assertTrue(child11.isAnimating(TRANSITION | PARENTS));
        assertTrue(child12.isAnimating(TRANSITION | PARENTS));
        assertFalse(child2.isAnimating(TRANSITION | PARENTS));
        assertFalse(child21.isAnimating(TRANSITION | PARENTS));

        assertTrue(root.isAnimating(TRANSITION | CHILDREN));
        assertTrue(child1.isAnimating(TRANSITION | CHILDREN));
        assertFalse(child11.isAnimating(TRANSITION | CHILDREN));
        assertTrue(child12.isAnimating(TRANSITION | CHILDREN));
        assertFalse(child2.isAnimating(TRANSITION | CHILDREN));
        assertFalse(child21.isAnimating(TRANSITION | CHILDREN));
    }

    @Test
    public void testIsAnimating_typesToCheck() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer window = builder.setIsAnimating(true).setLayer(0).build();

        assertTrue(window.isAnimating());
        assertFalse(window.isAnimating(0, ANIMATION_TYPE_SCREEN_ROTATION));
        assertTrue(window.isAnimating(0, ANIMATION_TYPE_APP_TRANSITION));
        assertFalse(window.isAnimating(0, ANIMATION_TYPE_ALL & ~ANIMATION_TYPE_APP_TRANSITION));

        final TestWindowContainer child = window.addChildWindow();
        assertFalse(child.isAnimating());
        assertTrue(child.isAnimating(PARENTS));
        assertTrue(child.isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION));
        assertFalse(child.isAnimating(PARENTS, ANIMATION_TYPE_SCREEN_ROTATION));

        final WindowState windowState = createWindow(null /* parent */, TYPE_BASE_APPLICATION,
                mDisplayContent, "TestWindowState");
        WindowContainer parent = windowState.getParent();
        spyOn(windowState.mSurfaceAnimator);
        doReturn(true).when(windowState.mSurfaceAnimator).isAnimating();
        doReturn(ANIMATION_TYPE_APP_TRANSITION).when(
                windowState.mSurfaceAnimator).getAnimationType();
        assertTrue(parent.isAnimating(CHILDREN));

        windowState.setControllableInsetProvider(mock(WindowContainerInsetsSourceProvider.class));
        assertFalse(parent.isAnimating(CHILDREN));
    }

    @Test
    public void testIsVisible() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow(builder.setIsVisible(true));
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow(builder.setIsVisible(true));
        final TestWindowContainer child21 = child2.addChildWindow();

        assertFalse(root.isVisible());
        assertTrue(child1.isVisible());
        assertFalse(child11.isVisible());
        assertTrue(child12.isVisible());
        assertFalse(child2.isVisible());
        assertFalse(child21.isVisible());
    }

    @Test
    public void testOverrideConfigurationAncestorNotification() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer grandparent = builder.setLayer(0).build();

        final TestWindowContainer parent = grandparent.addChildWindow();
        final TestWindowContainer child = parent.addChildWindow();
        child.onRequestedOverrideConfigurationChanged(new Configuration());

        assertTrue(grandparent.mOnDescendantOverrideCalled);
    }

    @Test
    public void testRemoveChild() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();
        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child21 = child2.addChildWindow();

        assertTrue(root.hasChild(child2));
        assertTrue(root.hasChild(child21));
        root.removeChild(child2);
        assertFalse(root.hasChild(child2));
        assertFalse(root.hasChild(child21));
        assertNull(child2.getParentWindow());

        boolean gotException = false;
        assertTrue(root.hasChild(child11));
        try {
            // Can only detach our direct children.
            root.removeChild(child11);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);
    }

    @Test
    public void testGetOrientation_childSpecified() {
        testGetOrientation_childSpecifiedConfig(false, SCREEN_ORIENTATION_LANDSCAPE,
                SCREEN_ORIENTATION_LANDSCAPE);
        testGetOrientation_childSpecifiedConfig(false, SCREEN_ORIENTATION_UNSET,
                SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void testGetOrientation_childSpecifiedConfig(boolean childVisible, int childOrientation,
            int expectedOrientation) {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();
        root.setFillsParent(true);

        builder.setIsVisible(childVisible);

        if (childOrientation != SCREEN_ORIENTATION_UNSET) {
            builder.setOrientation(childOrientation);
        }

        final TestWindowContainer child1 = root.addChildWindow(builder);
        child1.setFillsParent(true);

        assertEquals(expectedOrientation, root.getOrientation());
    }

    @Test
    public void testGetOrientation_Unset() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).setIsVisible(true).build();
        // Unspecified well because we didn't specify anything...
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, root.getOrientation());
    }

    @Test
    public void testGetOrientation_InvisibleParentUnsetVisibleChildren() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).setIsVisible(true).build();

        builder.setIsVisible(false).setLayer(-1);
        final TestWindowContainer invisible = root.addChildWindow(builder);
        builder.setIsVisible(true).setLayer(-2);
        final TestWindowContainer invisibleChild1VisibleAndSet = invisible.addChildWindow(builder);
        invisibleChild1VisibleAndSet.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        // Landscape well because the container is visible and that is what we set on it above.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, invisibleChild1VisibleAndSet.getOrientation());
        // Landscape because even though the container isn't visible it has a child that is
        // specifying it can influence the orientation by being visible.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, invisible.getOrientation());
        // Landscape because the grandchild is visible and therefore can participate.
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, root.getOrientation());

        builder.setIsVisible(true).setLayer(-3);
        final TestWindowContainer visibleUnset = root.addChildWindow(builder);
        visibleUnset.setOrientation(SCREEN_ORIENTATION_UNSET);
        assertEquals(SCREEN_ORIENTATION_UNSET, visibleUnset.getOrientation());
        assertEquals(SCREEN_ORIENTATION_LANDSCAPE, root.getOrientation());
    }

    @Test
    public void testGetOrientation_setBehind() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).setIsVisible(true).build();

        builder.setIsVisible(true).setLayer(-1);
        final TestWindowContainer visibleUnset = root.addChildWindow(builder);
        visibleUnset.setOrientation(SCREEN_ORIENTATION_UNSET);

        builder.setIsVisible(true).setLayer(-2);
        final TestWindowContainer visibleUnsetChild1VisibleSetBehind =
                visibleUnset.addChildWindow(builder);
        visibleUnsetChild1VisibleSetBehind.setOrientation(SCREEN_ORIENTATION_BEHIND);
        // Setting to visible behind will be used by the parents if there isn't another other
        // container behind this one that has an orientation set.
        assertEquals(SCREEN_ORIENTATION_BEHIND,
                visibleUnsetChild1VisibleSetBehind.getOrientation());
        assertEquals(SCREEN_ORIENTATION_BEHIND, visibleUnset.getOrientation());
        assertEquals(SCREEN_ORIENTATION_BEHIND, root.getOrientation());
    }

    @Test
    public void testGetOrientation_fillsParent() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).setIsVisible(true).build();

        builder.setIsVisible(true).setLayer(-1);
        final TestWindowContainer visibleUnset = root.addChildWindow(builder);
        visibleUnset.setOrientation(SCREEN_ORIENTATION_BEHIND);

        builder.setLayer(1).setIsVisible(true);
        final TestWindowContainer visibleUnspecifiedRootChild = root.addChildWindow(builder);
        visibleUnspecifiedRootChild.setFillsParent(false);
        visibleUnspecifiedRootChild.setOrientation(SCREEN_ORIENTATION_UNSPECIFIED);
        // Unset because the child doesn't fill the parent. May as well be invisible...
        assertEquals(SCREEN_ORIENTATION_UNSET, visibleUnspecifiedRootChild.getOrientation());
        // The parent uses whatever orientation is set behind this container since it doesn't fill
        // the parent.
        assertEquals(SCREEN_ORIENTATION_BEHIND, root.getOrientation());

        // Test case of child filling its parent, but its parent isn't filling its own parent.
        builder.setLayer(2).setIsVisible(true);
        final TestWindowContainer visibleUnspecifiedRootChildChildFillsParent =
                visibleUnspecifiedRootChild.addChildWindow(builder);
        visibleUnspecifiedRootChildChildFillsParent.setOrientation(SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(SCREEN_ORIENTATION_PORTRAIT,
                visibleUnspecifiedRootChildChildFillsParent.getOrientation());
        assertEquals(SCREEN_ORIENTATION_UNSET, visibleUnspecifiedRootChild.getOrientation());
        assertEquals(SCREEN_ORIENTATION_BEHIND, root.getOrientation());


        visibleUnspecifiedRootChild.setFillsParent(true);
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, visibleUnspecifiedRootChild.getOrientation());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, root.getOrientation());
    }

    @Test
    public void testSetOrientation() {
        final TestWindowContainer root = spy(new TestWindowContainerBuilder(mWm).build());
        final TestWindowContainer child = spy(root.addChildWindow());
        doReturn(true).when(root).handlesOrientationChangeFromDescendant();
        child.getWindowConfiguration().setWindowingMode(WINDOWING_MODE_FULLSCREEN);
        child.setOrientation(SCREEN_ORIENTATION_PORTRAIT);
        // The ancestor should decide whether to dispatch the configuration change.
        verify(child, never()).onConfigurationChanged(any());

        doReturn(false).when(root).handlesOrientationChangeFromDescendant();
        child.setOrientation(SCREEN_ORIENTATION_LANDSCAPE);
        // The ancestor doesn't handle the request so the descendant applies the change directly.
        verify(child).onConfigurationChanged(any());
    }

    @Test
    public void testCompareTo() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow();

        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child21 = child2.addChildWindow();
        final TestWindowContainer child22 = child2.addChildWindow();
        final TestWindowContainer child222 = child22.addChildWindow();
        final TestWindowContainer child223 = child22.addChildWindow();
        final TestWindowContainer child2221 = child222.addChildWindow();
        final TestWindowContainer child2222 = child222.addChildWindow();
        final TestWindowContainer child2223 = child222.addChildWindow();

        final TestWindowContainer root2 = builder.setLayer(0).build();

        assertEquals(0, root.compareTo(root));
        assertEquals(-1, child1.compareTo(child2));
        assertEquals(1, child2.compareTo(child1));

        boolean inTheSameTree = true;
        try {
            root.compareTo(root2);
        } catch (IllegalArgumentException e) {
            inTheSameTree = false;
        }
        assertFalse(inTheSameTree);

        assertEquals(-1, child1.compareTo(child11));
        assertEquals(1, child21.compareTo(root));
        assertEquals(1, child21.compareTo(child12));
        assertEquals(-1, child11.compareTo(child2));
        assertEquals(1, child2221.compareTo(child11));
        assertEquals(-1, child2222.compareTo(child223));
        assertEquals(1, child2223.compareTo(child21));
    }

    @Test
    public void testPrefixOrderIndex() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.build();

        final TestWindowContainer child1 = root.addChildWindow();

        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow();

        final TestWindowContainer child2 = root.addChildWindow();

        final TestWindowContainer child21 = child2.addChildWindow();
        final TestWindowContainer child22 = child2.addChildWindow();

        final TestWindowContainer child221 = child22.addChildWindow();
        final TestWindowContainer child222 = child22.addChildWindow();
        final TestWindowContainer child223 = child22.addChildWindow();

        final TestWindowContainer child23 = child2.addChildWindow();

        assertEquals(0, root.getPrefixOrderIndex());
        assertEquals(1, child1.getPrefixOrderIndex());
        assertEquals(2, child11.getPrefixOrderIndex());
        assertEquals(3, child12.getPrefixOrderIndex());
        assertEquals(4, child2.getPrefixOrderIndex());
        assertEquals(5, child21.getPrefixOrderIndex());
        assertEquals(6, child22.getPrefixOrderIndex());
        assertEquals(7, child221.getPrefixOrderIndex());
        assertEquals(8, child222.getPrefixOrderIndex());
        assertEquals(9, child223.getPrefixOrderIndex());
        assertEquals(10, child23.getPrefixOrderIndex());
    }

    @Test
    public void testPrefixOrder_addEntireSubtree() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.build();
        final TestWindowContainer subtree = builder.build();
        final TestWindowContainer subtree2 = builder.build();

        final TestWindowContainer child1 = subtree.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child2 = subtree2.addChildWindow();
        final TestWindowContainer child3 = subtree2.addChildWindow();
        subtree.addChild(subtree2, 1);
        root.addChild(subtree, 0);

        assertEquals(0, root.getPrefixOrderIndex());
        assertEquals(1, subtree.getPrefixOrderIndex());
        assertEquals(2, child1.getPrefixOrderIndex());
        assertEquals(3, child11.getPrefixOrderIndex());
        assertEquals(4, subtree2.getPrefixOrderIndex());
        assertEquals(5, child2.getPrefixOrderIndex());
        assertEquals(6, child3.getPrefixOrderIndex());
    }

    @Test
    public void testPrefixOrder_remove() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.build();

        final TestWindowContainer child1 = root.addChildWindow();

        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow();

        final TestWindowContainer child2 = root.addChildWindow();

        assertEquals(0, root.getPrefixOrderIndex());
        assertEquals(1, child1.getPrefixOrderIndex());
        assertEquals(2, child11.getPrefixOrderIndex());
        assertEquals(3, child12.getPrefixOrderIndex());
        assertEquals(4, child2.getPrefixOrderIndex());

        root.removeChild(child1);

        assertEquals(1, child2.getPrefixOrderIndex());
    }

    /**
     * Ensure children of a {@link WindowContainer} do not have
     * {@link WindowContainer#onParentResize()} called when {@link WindowContainer#onParentResize()}
     * is invoked with overridden bounds.
     */
    @Test
    public void testOnParentResizePropagation() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = builder.build();

        final TestWindowContainer child = root.addChildWindow();
        child.setBounds(new Rect(1, 1, 2, 2));

        final TestWindowContainer grandChild = mock(TestWindowContainer.class);

        child.addChildWindow(grandChild);
        root.onResize();

        // Make sure the child does not propagate resize through onParentResize when bounds are set.
        verify(grandChild, never()).onParentResize();

        child.removeChild(grandChild);

        child.setBounds(null);
        child.addChildWindow(grandChild);
        root.onResize();

        // Make sure the child propagates resize through onParentResize when no bounds set.
        verify(grandChild, times(1)).onParentResize();
    }

    @Test
    public void testOnDescendantOrientationRequestChangedPropagation() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = spy(builder.build());

        final ActivityRecord activityRecord = mock(ActivityRecord.class);
        final TestWindowContainer child = root.addChildWindow();

        child.setOrientation(ActivityInfo.SCREEN_ORIENTATION_LOCKED, activityRecord);
        verify(root).onDescendantOrientationChanged(activityRecord);
    }

    @Test
    public void testHandlesOrientationChangeFromDescendantProgation() {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder(mWm);
        final TestWindowContainer root = spy(builder.build());

        final TestWindowContainer child = root.addChildWindow();
        assertFalse(child.handlesOrientationChangeFromDescendant());

        Mockito.doReturn(true).when(root).handlesOrientationChangeFromDescendant();
        assertTrue(child.handlesOrientationChangeFromDescendant());
    }

    @Test
    public void testOnDisplayChanged() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);

        final DisplayContent newDc = createNewDisplay();
        rootTask.getDisplayArea().removeRootTask(rootTask);
        newDc.getDefaultTaskDisplayArea().addChild(rootTask, POSITION_TOP);

        verify(rootTask).onDisplayChanged(newDc);
        verify(task).onDisplayChanged(newDc);
        verify(activity).onDisplayChanged(newDc);
        assertEquals(newDc, rootTask.mDisplayContent);
        assertEquals(newDc, task.mDisplayContent);
        assertEquals(newDc, activity.mDisplayContent);
    }

    @Test
    public void testHandleCompleteDeferredRemoval() {
        final DisplayContent displayContent = createNewDisplay();
        // Do not reparent activity to default display when removing the display.
        doReturn(true).when(displayContent).shouldDestroyContentOnRemove();

        // An animating window with mRemoveOnExit can be removed by handleCompleteDeferredRemoval
        // once it no longer animates.
        final WindowState exitingWindow = createWindow(null, TYPE_APPLICATION_OVERLAY,
                displayContent, "exiting window");
        exitingWindow.startAnimation(exitingWindow.getPendingTransaction(),
                mock(AnimationAdapter.class), false /* hidden */,
                SurfaceAnimator.ANIMATION_TYPE_WINDOW_ANIMATION);
        exitingWindow.mRemoveOnExit = true;
        exitingWindow.handleCompleteDeferredRemoval();
        // The animation has not finished so the window is not removed.
        assertTrue(exitingWindow.isAnimating());
        assertTrue(exitingWindow.isAttached());
        exitingWindow.cancelAnimation();
        // The window is removed because the animation is gone.
        exitingWindow.handleCompleteDeferredRemoval();
        assertFalse(exitingWindow.isAttached());

        final ActivityRecord r = new TaskBuilder(mSupervisor).setCreateActivity(true)
                .setDisplay(displayContent).build().getTopMostActivity();
        // Add a window and make the activity animating so the removal of activity is deferred.
        createWindow(null, TYPE_BASE_APPLICATION, r, "win");
        doReturn(true).when(r).isAnimating(anyInt(), anyInt());

        displayContent.remove();
        // Ensure that ActivityRecord#onRemovedFromDisplay is called.
        r.destroyed("test");
        // The removal is deferred, so the activity is still in the display.
        assertEquals(r, displayContent.getTopMostActivity());

        // Assume the animation is done so the deferred removal can continue.
        doReturn(false).when(r).isAnimating(anyInt(), anyInt());

        assertFalse(displayContent.handleCompleteDeferredRemoval());
        assertFalse(displayContent.hasChild());
        assertFalse(r.hasChild());
    }

    @Test
    public void testTaskCanApplyAnimation() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity2 = createActivityRecord(mDisplayContent, task);
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent, task);
        verifyWindowContainerApplyAnimation(task, activity1, activity2);
    }

    @Test
    public void testRootTaskCanApplyAnimation() {
        final Task rootTask = createTask(mDisplayContent);
        final ActivityRecord activity2 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(rootTask, 0 /* userId */));
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(rootTask, 0 /* userId */));
        verifyWindowContainerApplyAnimation(rootTask, activity1, activity2);
    }

    @Test
    public void testGetDisplayArea() {
        // WindowContainer
        final WindowContainer windowContainer = new WindowContainer(mWm);

        assertNull(windowContainer.getDisplayArea());

        // Task > WindowContainer
        final Task task = createTask(mDisplayContent);
        task.addChild(windowContainer, 0);
        task.setParent(null);

        assertNull(windowContainer.getDisplayArea());
        assertNull(task.getDisplayArea());

        // TaskDisplayArea > Task > WindowContainer
        final TaskDisplayArea taskDisplayArea = new TaskDisplayArea(
                mDisplayContent, mWm, "TaskDisplayArea", FEATURE_DEFAULT_TASK_CONTAINER);
        taskDisplayArea.addChild(task, 0);

        assertEquals(taskDisplayArea, windowContainer.getDisplayArea());
        assertEquals(taskDisplayArea, task.getDisplayArea());
        assertEquals(taskDisplayArea, taskDisplayArea.getDisplayArea());

        // DisplayArea
        final DisplayArea displayArea = new DisplayArea(mWm, ANY, "DisplayArea");

        assertEquals(displayArea, displayArea.getDisplayArea());
    }

    private void verifyWindowContainerApplyAnimation(WindowContainer wc, ActivityRecord act,
            ActivityRecord act2) {
        // Initial remote animation for app transition.
        final RemoteAnimationAdapter adapter = new RemoteAnimationAdapter(
                new IRemoteAnimationRunner.Stub() {
                    @Override
                    public void onAnimationStart(@WindowManager.TransitionOldType int transit,
                            RemoteAnimationTarget[] apps,
                            RemoteAnimationTarget[] wallpapers,
                            RemoteAnimationTarget[] nonApps,
                            IRemoteAnimationFinishedCallback finishedCallback) {
                        try {
                            finishedCallback.onAnimationFinished();
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    }

                    @Override
                    public void onAnimationCancelled(boolean isKeyguardOccluded) {
                    }
                }, 0, 0, false);
        adapter.setCallingPidUid(123, 456);
        wc.getDisplayContent().prepareAppTransition(TRANSIT_OPEN);
        wc.getDisplayContent().mAppTransition.overridePendingAppTransitionRemote(adapter);
        spyOn(wc);
        doReturn(true).when(wc).okToAnimate();

        // Make sure animating state is as expected after applied animation.

        // Animation target is promoted from act to wc. act2 is a descendant of wc, but not a source
        // of the animation.
        ArrayList<WindowContainer<WindowState>> sources = new ArrayList<>();
        sources.add(act);
        assertTrue(wc.applyAnimation(null, TRANSIT_OLD_TASK_OPEN, true, false, sources));

        assertEquals(act, wc.getTopMostActivity());
        assertTrue(wc.isAnimating());
        assertTrue(wc.isAnimating(0, ANIMATION_TYPE_APP_TRANSITION));
        assertTrue(wc.getAnimationSources().contains(act));
        assertFalse(wc.getAnimationSources().contains(act2));
        assertTrue(act.isAnimating(PARENTS));
        assertTrue(act.isAnimating(PARENTS, ANIMATION_TYPE_APP_TRANSITION));
        assertEquals(wc, act.getAnimatingContainer(PARENTS, ANIMATION_TYPE_APP_TRANSITION));

        // Make sure animation finish callback will be received and reset animating state after
        // animation finish.
        wc.getDisplayContent().mAppTransition.goodToGo(TRANSIT_OLD_TASK_OPEN, act);
        verify(wc).onAnimationFinished(eq(ANIMATION_TYPE_APP_TRANSITION), any());
        assertFalse(wc.isAnimating());
        assertFalse(act.isAnimating(PARENTS));
    }

    @Test
    public void testRegisterWindowContainerListener() {
        final WindowContainer container = new WindowContainer(mWm);
        container.mDisplayContent = mDisplayContent;
        final TestWindowContainerListener listener = new TestWindowContainerListener();
        Configuration config = container.getConfiguration();
        Rect bounds = new Rect(0, 0, 10, 10);
        config.windowConfiguration.setBounds(bounds);
        config.densityDpi = 100;
        container.onRequestedOverrideConfigurationChanged(config);
        container.registerWindowContainerListener(listener);

        assertEquals(mDisplayContent, listener.mDisplayContent);
        assertEquals(bounds, listener.mConfiguration.windowConfiguration.getBounds());
        assertEquals(100, listener.mConfiguration.densityDpi);

        container.onDisplayChanged(mDefaultDisplay);
        assertEquals(listener.mDisplayContent, mDefaultDisplay);

        config = new Configuration();
        bounds = new Rect(0, 0, 20, 20);
        config.windowConfiguration.setBounds(bounds);
        config.densityDpi = 200;

        container.onRequestedOverrideConfigurationChanged(config);

        assertEquals(bounds, listener.mConfiguration.windowConfiguration.getBounds());
        assertEquals(200, listener.mConfiguration.densityDpi);
    }

    @Test
    public void testFreezeInsets() {
        final Task task = createTask(mDisplayContent);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, activity, "win");

        // Set visibility to false, verify the main window of the task will be set the frozen
        // insets state immediately.
        activity.setVisibility(false);
        assertNotNull(win.getFrozenInsetsState());

        // Now make it visible again, verify that the insets are immediately unfrozen.
        activity.setVisibility(true);
        assertNull(win.getFrozenInsetsState());
    }

    @Test
    public void testFreezeInsetsStateWhenAppTransition() {
        final Task rootTask = createTask(mDisplayContent);
        final Task task = createTaskInRootTask(rootTask, 0 /* userId */);
        final ActivityRecord activity = createActivityRecord(mDisplayContent, task);
        final WindowState win = createWindow(null, TYPE_BASE_APPLICATION, activity, "win");
        task.getDisplayContent().prepareAppTransition(TRANSIT_CLOSE);
        spyOn(win);
        doReturn(true).when(task).okToAnimate();
        ArrayList<WindowContainer> sources = new ArrayList<>();
        sources.add(activity);

        // Simulate the task applying the exit transition, verify the main window of the task
        // will be set the frozen insets state before the animation starts
        activity.setVisibility(false);
        task.applyAnimation(null, TRANSIT_OLD_TASK_CLOSE, false /* enter */,
                false /* isVoiceInteraction */, sources);
        verify(win).freezeInsetsState();

        // Simulate the task transition finished.
        activity.commitVisibility(false, false);
        task.onAnimationFinished(ANIMATION_TYPE_APP_TRANSITION,
                task.mSurfaceAnimator.getAnimation());

        // Now make it visible again, verify that the insets are immediately unfrozen even before
        // transition starts.
        activity.setVisibility(true);
        verify(win).clearFrozenInsetsState();
    }

    @Test
    public void testAssignRelativeLayer() {
        final WindowContainer container = new WindowContainer(mWm);
        container.mSurfaceControl = mock(SurfaceControl.class);
        final SurfaceAnimator surfaceAnimator = container.mSurfaceAnimator;
        final SurfaceControl relativeParent = mock(SurfaceControl.class);
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        spyOn(container);
        spyOn(surfaceAnimator);

        // Trigger for first relative layer call.
        container.assignRelativeLayer(t, relativeParent, 1 /* layer */);
        verify(surfaceAnimator).setRelativeLayer(t, relativeParent, 1 /* layer */);

        // Not trigger for the same relative layer call.
        clearInvocations(surfaceAnimator);
        container.assignRelativeLayer(t, relativeParent, 1 /* layer */);
        verify(surfaceAnimator, never()).setRelativeLayer(t, relativeParent, 1 /* layer */);

        // Trigger for the same relative layer call if forceUpdate=true
        container.assignRelativeLayer(t, relativeParent, 1 /* layer */, true /* forceUpdate */);
        verify(surfaceAnimator).setRelativeLayer(t, relativeParent, 1 /* layer */);
    }

    @Test
    public void testAssignAnimationLayer() {
        final WindowContainer container = new WindowContainer(mWm);
        container.mSurfaceControl = mock(SurfaceControl.class);
        final SurfaceAnimator surfaceAnimator = container.mSurfaceAnimator;
        final SurfaceFreezer surfaceFreezer = container.mSurfaceFreezer;
        final SurfaceControl relativeParent = mock(SurfaceControl.class);
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        spyOn(container);
        spyOn(surfaceAnimator);
        spyOn(surfaceFreezer);

        container.setLayer(t, 1);
        container.setRelativeLayer(t, relativeParent, 2);

        // Set through surfaceAnimator if surfaceFreezer doesn't have leash.
        verify(surfaceAnimator).setLayer(t, 1);
        verify(surfaceAnimator).setRelativeLayer(t, relativeParent, 2);
        verify(surfaceFreezer, never()).setLayer(any(), anyInt());
        verify(surfaceFreezer, never()).setRelativeLayer(any(), any(), anyInt());

        clearInvocations(surfaceAnimator);
        clearInvocations(surfaceFreezer);
        doReturn(true).when(surfaceFreezer).hasLeash();

        container.setLayer(t, 1);
        container.setRelativeLayer(t, relativeParent, 2);

        // Set through surfaceFreezer if surfaceFreezer has leash.
        verify(surfaceFreezer).setLayer(t, 1);
        verify(surfaceFreezer).setRelativeLayer(t, relativeParent, 2);
        verify(surfaceAnimator, never()).setLayer(any(), anyInt());
        verify(surfaceAnimator, never()).setRelativeLayer(any(), any(), anyInt());
    }

    @Test
    public void testStartChangeTransitionWhenPreviousIsNotFinished() {
        final WindowContainer container = createTaskFragmentWithParentTask(
                createTask(mDisplayContent), false);
        container.mSurfaceControl = mock(SurfaceControl.class);
        final SurfaceAnimator surfaceAnimator = container.mSurfaceAnimator;
        final SurfaceFreezer surfaceFreezer = container.mSurfaceFreezer;
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        spyOn(container);
        spyOn(surfaceAnimator);
        mockSurfaceFreezerSnapshot(surfaceFreezer);
        doReturn(t).when(container).getPendingTransaction();
        doReturn(t).when(container).getSyncTransaction();

        // Leash and snapshot created for change transition.
        container.initializeChangeTransition(new Rect(0, 0, 1000, 2000));

        assertNotNull(surfaceFreezer.mLeash);
        assertNotNull(surfaceFreezer.mSnapshot);
        assertEquals(surfaceFreezer.mLeash, container.getAnimationLeash());

        // Start animation: surfaceAnimator take over the leash and snapshot from surfaceFreezer.
        container.applyAnimationUnchecked(null /* lp */, true /* enter */,
                TRANSIT_OLD_TASK_FRAGMENT_CHANGE, false /* isVoiceInteraction */,
                null /* sources */);

        assertNull(surfaceFreezer.mLeash);
        assertNull(surfaceFreezer.mSnapshot);
        assertNotNull(surfaceAnimator.mLeash);
        assertNotNull(surfaceAnimator.mSnapshot);
        final SurfaceControl prevLeash = surfaceAnimator.mLeash;
        final SurfaceFreezer.Snapshot prevSnapshot = surfaceAnimator.mSnapshot;

        // Prepare another change transition.
        container.initializeChangeTransition(new Rect(0, 0, 1000, 2000));

        assertNotNull(surfaceFreezer.mLeash);
        assertNotNull(surfaceFreezer.mSnapshot);
        assertEquals(surfaceFreezer.mLeash, container.getAnimationLeash());
        assertNotEquals(prevLeash, container.getAnimationLeash());

        // Start another animation before the previous one is finished, it should reset the previous
        // one, but not change the current one.
        container.applyAnimationUnchecked(null /* lp */, true /* enter */,
                TRANSIT_OLD_TASK_FRAGMENT_CHANGE, false /* isVoiceInteraction */,
                null /* sources */);

        verify(container, never()).onAnimationLeashLost(any());
        verify(surfaceFreezer, never()).unfreeze(any());
        assertNotNull(surfaceAnimator.mLeash);
        assertNotNull(surfaceAnimator.mSnapshot);
        assertEquals(surfaceAnimator.mLeash, container.getAnimationLeash());
        assertNotEquals(prevLeash, surfaceAnimator.mLeash);
        assertNotEquals(prevSnapshot, surfaceAnimator.mSnapshot);

        // Clean up after animation finished.
        surfaceAnimator.mInnerAnimationFinishedCallback.onAnimationFinished(
                ANIMATION_TYPE_APP_TRANSITION, surfaceAnimator.getAnimation());

        verify(container).onAnimationLeashLost(any());
        assertNull(surfaceAnimator.mLeash);
        assertNull(surfaceAnimator.mSnapshot);
    }

    @Test
    public void testUnfreezeWindow_removeWindowFromChanging() {
        final WindowContainer container = createTaskFragmentWithParentTask(
                createTask(mDisplayContent), false);
        mockSurfaceFreezerSnapshot(container.mSurfaceFreezer);
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);

        container.initializeChangeTransition(new Rect(0, 0, 1000, 2000));

        assertTrue(mDisplayContent.mChangingContainers.contains(container));

        container.mSurfaceFreezer.unfreeze(t);

        assertFalse(mDisplayContent.mChangingContainers.contains(container));
    }

    @Test
    public void testFailToTaskSnapshot_unfreezeWindow() {
        final WindowContainer container = createTaskFragmentWithParentTask(
                createTask(mDisplayContent), false);
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        spyOn(container.mSurfaceFreezer);

        container.initializeChangeTransition(new Rect(0, 0, 1000, 2000));

        verify(container.mSurfaceFreezer).freeze(any(), any(), any(), any());
        verify(container.mSurfaceFreezer).unfreeze(any());
        assertTrue(mDisplayContent.mChangingContainers.isEmpty());
    }

    @Test
    public void testRemoveUnstartedFreezeSurfaceWhenFreezeAgain() {
        final WindowContainer container = createTaskFragmentWithParentTask(
                createTask(mDisplayContent), false);
        container.mSurfaceControl = mock(SurfaceControl.class);
        final SurfaceFreezer surfaceFreezer = container.mSurfaceFreezer;
        mockSurfaceFreezerSnapshot(surfaceFreezer);
        final SurfaceControl.Transaction t = mock(SurfaceControl.Transaction.class);
        spyOn(container);
        doReturn(t).when(container).getPendingTransaction();
        doReturn(t).when(container).getSyncTransaction();

        // Leash and snapshot created for change transition.
        container.initializeChangeTransition(new Rect(0, 0, 1000, 2000));

        assertNotNull(surfaceFreezer.mLeash);
        assertNotNull(surfaceFreezer.mSnapshot);

        final SurfaceControl prevLeash = surfaceFreezer.mLeash;
        final SurfaceFreezer.Snapshot prevSnapshot = surfaceFreezer.mSnapshot;
        spyOn(prevSnapshot);

        container.initializeChangeTransition(new Rect(0, 0, 1500, 2500));

        verify(t).remove(prevLeash);
        verify(prevSnapshot).destroy(t);
    }

    @Test
    public void testAddLocalInsetsSourceProvider() {
         /*
                ___ rootTask _______________________________________________
               |        |                |                                  |
          activity0    container     navigationBarInsetsProvider1    navigationBarInsetsProvider2
                       /       \
               activity1    activity2
         */
        final Task rootTask = createTask(mDisplayContent);

        final ActivityRecord activity0 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(rootTask, 0 /* userId */));
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow0");
        activity0.addWindow(createWindowState(attrs, activity0));

        final Task container = createTaskInRootTask(rootTask, 0);
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(container, 0 /* userId */));
        final WindowManager.LayoutParams attrs1 = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs1.setTitle("AppWindow1");
        activity1.addWindow(createWindowState(attrs1, activity1));

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(container, 0 /* userId */));
        final WindowManager.LayoutParams attrs2 = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs2.setTitle("AppWindow2");
        activity2.addWindow(createWindowState(attrs2, activity2));
        Rect navigationBarInsetsRect1 = new Rect(0, 200, 1080, 700);
        Rect navigationBarInsetsRect2 = new Rect(0, 0, 1080, 200);

        rootTask.addLocalRectInsetsSourceProvider(navigationBarInsetsRect1,
                new int[]{ITYPE_LOCAL_NAVIGATION_BAR_1});
        container.addLocalRectInsetsSourceProvider(navigationBarInsetsRect2,
                new int[]{ITYPE_LOCAL_NAVIGATION_BAR_2});

        InsetsSource navigationBarInsetsProvider1Source = new InsetsSource(
                ITYPE_LOCAL_NAVIGATION_BAR_1);
        navigationBarInsetsProvider1Source.setFrame(navigationBarInsetsRect1);
        navigationBarInsetsProvider1Source.setVisible(true);
        InsetsSource navigationBarInsetsProvider2Source = new InsetsSource(
                ITYPE_LOCAL_NAVIGATION_BAR_2);
        navigationBarInsetsProvider2Source.setFrame(navigationBarInsetsRect2);
        navigationBarInsetsProvider2Source.setVisible(true);

        activity0.forAllWindows(window -> {
            assertEquals(navigationBarInsetsRect1,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1).getFrame());
            assertEquals(null,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_2));
        }, true);
        activity1.forAllWindows(window -> {
            assertEquals(navigationBarInsetsRect1,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1).getFrame());
            assertEquals(navigationBarInsetsRect2,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_2).getFrame());
        }, true);
        activity2.forAllWindows(window -> {
            assertEquals(navigationBarInsetsRect1,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1).getFrame());
            assertEquals(navigationBarInsetsRect2,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_2).getFrame());
        }, true);
    }

    @Test
    public void testAddLocalInsetsSourceProvider_sameType_replacesInsets() {
         /*
                ___ rootTask ________________________________________
               |                  |                                  |
          activity0      navigationBarInsetsProvider1    navigationBarInsetsProvider2
         */
        final Task rootTask = createTask(mDisplayContent);

        final ActivityRecord activity0 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(rootTask, 0 /* userId */));
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow0");
        activity0.addWindow(createWindowState(attrs, activity0));

        Rect navigationBarInsetsRect1 = new Rect(0, 200, 1080, 700);
        Rect navigationBarInsetsRect2 = new Rect(0, 0, 1080, 200);

        rootTask.addLocalRectInsetsSourceProvider(navigationBarInsetsRect1,
                new int[]{ITYPE_LOCAL_NAVIGATION_BAR_1});
        activity0.forAllWindows(window -> {
            assertEquals(navigationBarInsetsRect1,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1).getFrame());
        }, true);

        rootTask.addLocalRectInsetsSourceProvider(navigationBarInsetsRect2,
                new int[]{ITYPE_LOCAL_NAVIGATION_BAR_1});

        activity0.forAllWindows(window -> {
            assertEquals(navigationBarInsetsRect2,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1).getFrame());
        }, true);
    }

    @Test
    public void testRemoveLocalInsetsSourceProvider() {
         /*
                ___ rootTask _______________________________________________
               |        |                |                                  |
          activity0    container    navigationBarInsetsProvider1     navigationBarInsetsProvider2
                       /       \
               activity1    activity2
         */
        final Task rootTask = createTask(mDisplayContent);

        final ActivityRecord activity0 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(rootTask, 0 /* userId */));
        final WindowManager.LayoutParams attrs = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs.setTitle("AppWindow0");
        activity0.addWindow(createWindowState(attrs, activity0));

        final Task container = createTaskInRootTask(rootTask, 0);
        final ActivityRecord activity1 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(container, 0 /* userId */));
        final WindowManager.LayoutParams attrs1 = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs1.setTitle("AppWindow1");
        activity1.addWindow(createWindowState(attrs1, activity1));

        final ActivityRecord activity2 = createActivityRecord(mDisplayContent,
                createTaskInRootTask(container, 0 /* userId */));
        final WindowManager.LayoutParams attrs2 = new WindowManager.LayoutParams(
                TYPE_BASE_APPLICATION);
        attrs2.setTitle("AppWindow2");
        activity2.addWindow(createWindowState(attrs2, activity2));

        activity2.addWindow(createWindowState(attrs2, activity2));
        Rect navigationBarInsetsRect1 = new Rect(0, 200, 1080, 700);
        Rect navigationBarInsetsRect2 = new Rect(0, 0, 1080, 200);

        rootTask.addLocalRectInsetsSourceProvider(navigationBarInsetsRect1,
                new int[]{ITYPE_LOCAL_NAVIGATION_BAR_1});
        container.addLocalRectInsetsSourceProvider(navigationBarInsetsRect2,
                new int[]{ITYPE_LOCAL_NAVIGATION_BAR_2});
        mDisplayContent.getInsetsStateController().onPostLayout();
        rootTask.removeLocalInsetsSourceProvider(new int[]{ITYPE_LOCAL_NAVIGATION_BAR_1});
        mDisplayContent.getInsetsStateController().onPostLayout();

        activity0.forAllWindows(window -> {
            assertEquals(null,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1));
            assertEquals(null,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_2));
        }, true);
        activity1.forAllWindows(window -> {
            assertEquals(null,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1));
            assertEquals(navigationBarInsetsRect2,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_2).getFrame());
        }, true);
        activity2.forAllWindows(window -> {
            assertEquals(null,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_1));
            assertEquals(navigationBarInsetsRect2,
                    window.getInsetsState().peekSource(ITYPE_LOCAL_NAVIGATION_BAR_2).getFrame());
        }, true);
    }

    /* Used so we can gain access to some protected members of the {@link WindowContainer} class */
    private static class TestWindowContainer extends WindowContainer<TestWindowContainer> {
        private final int mLayer;
        private boolean mIsAnimating;
        private boolean mIsVisible;
        private boolean mFillsParent;
        private boolean mWaitForTransitStart;
        private Integer mOrientation;

        private boolean mOnParentChangedCalled;
        private boolean mOnDescendantOverrideCalled;

        /**
         * Compares 2 window layers and returns -1 if the first is lesser than the second in terms
         * of z-order and 1 otherwise.
         */
        private static final Comparator<TestWindowContainer> SUBLAYER_COMPARATOR = (w1, w2) -> {
            final int layer1 = w1.mLayer;
            final int layer2 = w2.mLayer;
            if (layer1 < layer2 || (layer1 == layer2 && layer2 < 0)) {
                // We insert the child window into the list ordered by the mLayer. For same layers,
                // the negative one should go below others; the positive one should go above others.
                return -1;
            }
            if (layer1 == layer2) return 0;
            return 1;
        };

        TestWindowContainer(WindowManagerService wm, int layer, boolean isAnimating,
                boolean isVisible, boolean waitTransitStart, Integer orientation) {
            super(wm);

            mLayer = layer;
            mIsAnimating = isAnimating;
            mIsVisible = isVisible;
            mFillsParent = true;
            mOrientation = orientation;
            mWaitForTransitStart = waitTransitStart;
            spyOn(mSurfaceAnimator);
            doReturn(mIsAnimating).when(mSurfaceAnimator).isAnimating();
            doReturn(ANIMATION_TYPE_APP_TRANSITION).when(mSurfaceAnimator).getAnimationType();
        }

        TestWindowContainer getParentWindow() {
            return (TestWindowContainer) getParent();
        }

        int getChildrenCount() {
            return mChildren.size();
        }

        TestWindowContainer addChildWindow(TestWindowContainer child) {
            addChild(child, SUBLAYER_COMPARATOR);
            return child;
        }

        TestWindowContainer addChildWindow(TestWindowContainerBuilder childBuilder) {
            TestWindowContainer child = childBuilder.build();
            addChild(child, SUBLAYER_COMPARATOR);
            return child;
        }

        TestWindowContainer addChildWindow() {
            return addChildWindow(new TestWindowContainerBuilder(mWmService).setLayer(1));
        }

        @Override
        void onParentChanged(ConfigurationContainer newParent, ConfigurationContainer oldParent) {
            mOnParentChangedCalled = true;
        }

        @Override
        void onDescendantOverrideConfigurationChanged() {
            mOnDescendantOverrideCalled = true;
            super.onDescendantOverrideConfigurationChanged();
        }

        @Override
        boolean isVisible() {
            return mIsVisible;
        }

        @Override
        int getOrientation(int candidate) {
            return mOrientation != null ? mOrientation : super.getOrientation(candidate);
        }

        @Override
        int getOrientation() {
            return getOrientation(super.mOrientation);
        }

        @Override
        boolean fillsParent() {
            return mFillsParent;
        }

        void setFillsParent(boolean fillsParent) {
            mFillsParent = fillsParent;
        }

        @Override
        boolean isWaitingForTransitionStart() {
            return mWaitForTransitStart;
        }
    }

    private static class TestWindowContainerBuilder {
        private final WindowManagerService mWm;
        private int mLayer;
        private boolean mIsAnimating;
        private boolean mIsVisible;
        private boolean mIsWaitTransitStart;
        private Integer mOrientation;

        TestWindowContainerBuilder(WindowManagerService wm) {
            mWm = wm;
            mLayer = 0;
            mIsAnimating = false;
            mIsVisible = false;
            mOrientation = null;
        }

        TestWindowContainerBuilder setLayer(int layer) {
            mLayer = layer;
            return this;
        }

        TestWindowContainerBuilder setIsAnimating(boolean isAnimating) {
            mIsAnimating = isAnimating;
            return this;
        }

        TestWindowContainerBuilder setIsVisible(boolean isVisible) {
            mIsVisible = isVisible;
            return this;
        }

        TestWindowContainerBuilder setOrientation(int orientation) {
            mOrientation = orientation;
            return this;
        }

        TestWindowContainerBuilder setWaitForTransitionStart(boolean waitTransitStart) {
            mIsWaitTransitStart = waitTransitStart;
            return this;
        }

        TestWindowContainer build() {
            return new TestWindowContainer(mWm, mLayer, mIsAnimating, mIsVisible,
                    mIsWaitTransitStart, mOrientation);
        }
    }

    private static class MockSurfaceBuildingContainer extends WindowContainer<WindowContainer>
            implements AutoCloseable {
        private final SurfaceSession mSession = new SurfaceSession();

        MockSurfaceBuildingContainer(WindowManagerService wm) {
            super(wm);
        }

        static class MockSurfaceBuilder extends SurfaceControl.Builder {
            MockSurfaceBuilder(SurfaceSession ss) {
                super(ss);
            }

            @Override
            public SurfaceControl build() {
                return mock(SurfaceControl.class);
            }
        }

        @Override
        SurfaceControl.Builder makeChildSurface(WindowContainer child) {
            return new MockSurfaceBuilder(mSession);
        }

        @Override
        public void close() {
            mSession.kill();
        }
    }

    private static class TestWindowContainerListener implements WindowContainerListener {
        private Configuration mConfiguration = new Configuration();
        private DisplayContent mDisplayContent;

        @Override
        public void onRequestedOverrideConfigurationChanged(Configuration overrideConfiguration) {
            mConfiguration.setTo(overrideConfiguration);
        }

        @Override
        public void onDisplayChanged(DisplayContent dc) {
            mDisplayContent = dc;
        }
    }
}
