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
 * limitations under the License
 */

package com.android.server.wm;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

import static com.android.server.wm.WindowContainer.POSITION_BOTTOM;
import static com.android.server.wm.WindowContainer.POSITION_TOP;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyFloat;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.res.Configuration;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;
import android.view.SurfaceControl;
import android.view.SurfaceSession;

import androidx.test.filters.FlakyTest;
import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Comparator;

/**
 * Test class for {@link WindowContainer}.
 *
 * Build/Install/Run:
 *  atest FrameworksServicesTests:com.android.server.wm.WindowContainerTests
 */
@SmallTest
@Presubmit
@FlakyTest(bugId = 74078662)
@RunWith(AndroidJUnit4.class)
public class WindowContainerTests extends WindowTestsBase {

    @Test
    public void testCreation() throws Exception {
        final TestWindowContainer w = new TestWindowContainerBuilder().setLayer(0).build();
        assertNull("window must have no parent", w.getParentWindow());
        assertEquals("window must have no children", 0, w.getChildrenCount());
    }

    @Test
    public void testAdd() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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

        assertTrue(layer1.mOnParentSetCalled);
        assertTrue(secondLayer1.mOnParentSetCalled);
        assertTrue(layer2.mOnParentSetCalled);
        assertTrue(layerNeg1.mOnParentSetCalled);
        assertTrue(layerNeg2.mOnParentSetCalled);
        assertTrue(secondLayerNeg1.mOnParentSetCalled);
        assertTrue(layer0.mOnParentSetCalled);
    }

    @Test
    public void testAddChildSetsSurfacePosition() throws Exception {
        MockSurfaceBuildingContainer top = new MockSurfaceBuildingContainer();

        final SurfaceControl.Transaction transaction = mock(SurfaceControl.Transaction.class);
        sWm.mTransactionFactory = () -> transaction;

        WindowContainer child = new WindowContainer(sWm);
        child.setBounds(1, 1, 10, 10);

        verify(transaction, never()).setPosition(any(), anyFloat(), anyFloat());
        top.addChild(child, 0);
        verify(transaction, times(1)).setPosition(any(), eq(1.f), eq(1.f));
    }

    @Test
    public void testAdd_AlreadyHasParent() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testHasChild() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testRemoveImmediately() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testRemoveImmediately_WithController() throws Exception {
        final WindowContainer container = new WindowContainer(sWm);
        final WindowContainerController controller = new WindowContainerController(null, sWm);

        container.setController(controller);
        assertEquals(controller, container.getController());
        assertEquals(container, controller.mContainer);

        container.removeImmediately();
        assertNull(container.getController());
        assertNull(controller.mContainer);
    }

    @Test
    public void testSetController() throws Exception {
        final WindowContainerController controller = new WindowContainerController(null, sWm);
        final WindowContainer container = new WindowContainer(sWm);

        container.setController(controller);
        assertEquals(controller, container.getController());
        assertEquals(container, controller.mContainer);

        // Assert we can't change the controller to another one once set
        boolean gotException = false;
        try {
            container.setController(new WindowContainerController(null, sWm));
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);

        // Assert that we can set the controller to null.
        container.setController(null);
        assertNull(container.getController());
        assertNull(controller.mContainer);
    }

    @Test
    public void testPositionChildAt() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testPositionChildAtIncludeParents() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testPositionChildAtInvalid() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();

        boolean gotException = false;
        try {
            // Check response to negative position.
            root.positionChildAt(-1, child1, false /* includingParents */);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);

        gotException = false;
        try {
            // Check response to position that's bigger than child number.
            root.positionChildAt(3, child1, false /* includingParents */);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);
    }

    @Test
    public void testIsAnimating() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child21 = child2.addChildWindow();

        assertFalse(root.isAnimating());
        assertTrue(child1.isAnimating());
        assertTrue(child11.isAnimating());
        assertTrue(child12.isAnimating());
        assertFalse(child2.isAnimating());
        assertFalse(child21.isAnimating());

        assertTrue(root.isSelfOrChildAnimating());
        assertTrue(child1.isSelfOrChildAnimating());
        assertFalse(child11.isSelfOrChildAnimating());
        assertTrue(child12.isSelfOrChildAnimating());
        assertFalse(child2.isSelfOrChildAnimating());
        assertFalse(child21.isSelfOrChildAnimating());
    }

    @Test
    public void testIsVisible() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer grandparent = builder.setLayer(0).build();

        final TestWindowContainer parent = grandparent.addChildWindow();
        final TestWindowContainer child = parent.addChildWindow();
        child.onOverrideConfigurationChanged(new Configuration());

        assertTrue(grandparent.mOnDescendantOverrideCalled);
    }

    @Test
    public void testRemoveChild() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testGetOrientation_childSpecified() throws Exception {
        testGetOrientation_childSpecifiedConfig(false, SCREEN_ORIENTATION_LANDSCAPE,
            SCREEN_ORIENTATION_LANDSCAPE);
        testGetOrientation_childSpecifiedConfig(false, SCREEN_ORIENTATION_UNSET,
            SCREEN_ORIENTATION_UNSPECIFIED);
    }

    private void testGetOrientation_childSpecifiedConfig(boolean childVisible, int childOrientation,
        int expectedOrientation) {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testGetOrientation_Unset() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).setIsVisible(true).build();
        // Unspecified well because we didn't specify anything...
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, root.getOrientation());
    }

    @Test
    public void testGetOrientation_InvisibleParentUnsetVisibleChildren() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testGetOrientation_setBehind() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testGetOrientation_fillsParent() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
        visibleUnspecifiedRootChildChildFillsParent.setOrientation(
                SCREEN_ORIENTATION_PORTRAIT);
        assertEquals(SCREEN_ORIENTATION_PORTRAIT,
                visibleUnspecifiedRootChildChildFillsParent.getOrientation());
        assertEquals(SCREEN_ORIENTATION_UNSET, visibleUnspecifiedRootChild.getOrientation());
        assertEquals(SCREEN_ORIENTATION_BEHIND, root.getOrientation());


        visibleUnspecifiedRootChild.setFillsParent(true);
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, visibleUnspecifiedRootChild.getOrientation());
        assertEquals(SCREEN_ORIENTATION_PORTRAIT, root.getOrientation());
    }

    @Test
    public void testCompareTo() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow();

        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child21 = child2.addChildWindow();
        final TestWindowContainer child22 = child2.addChildWindow();
        final TestWindowContainer child23 = child2.addChildWindow();
        final TestWindowContainer child221 = child22.addChildWindow();
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
    public void testPrefixOrderIndex() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testPrefixOrder_addEntireSubtree() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testPrefixOrder_remove() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
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
    public void testOnParentResizePropagation() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.build();

        final TestWindowContainer child = root.addChildWindow();
        child.setBounds(new Rect(1,1,2,2));

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

    /* Used so we can gain access to some protected members of the {@link WindowContainer} class */
    private class TestWindowContainer extends WindowContainer<TestWindowContainer> {
        private final int mLayer;
        private boolean mIsAnimating;
        private boolean mIsVisible;
        private boolean mFillsParent;
        private Integer mOrientation;

        private boolean mOnParentSetCalled;
        private boolean mOnDescendantOverrideCalled;

        /**
         * Compares 2 window layers and returns -1 if the first is lesser than the second in terms
         * of z-order and 1 otherwise.
         */
        private final Comparator<TestWindowContainer> mWindowSubLayerComparator = (w1, w2) -> {
            final int layer1 = w1.mLayer;
            final int layer2 = w2.mLayer;
            if (layer1 < layer2 || (layer1 == layer2 && layer2 < 0 )) {
                // We insert the child window into the list ordered by the mLayer. For same layers,
                // the negative one should go below others; the positive one should go above others.
                return -1;
            }
            return 1;
        };

        TestWindowContainer(int layer, boolean isAnimating, boolean isVisible,
            Integer orientation) {
            super(sWm);
            mLayer = layer;
            mIsAnimating = isAnimating;
            mIsVisible = isVisible;
            mFillsParent = true;
            mOrientation = orientation;
        }

        TestWindowContainer getParentWindow() {
            return (TestWindowContainer) getParent();
        }

        int getChildrenCount() {
            return mChildren.size();
        }

        TestWindowContainer addChildWindow(TestWindowContainer child) {
            addChild(child, mWindowSubLayerComparator);
            return child;
        }

        TestWindowContainer addChildWindow(TestWindowContainerBuilder childBuilder) {
            TestWindowContainer child = childBuilder.build();
            addChild(child, mWindowSubLayerComparator);
            return child;
        }

        TestWindowContainer addChildWindow() {
            return addChildWindow(new TestWindowContainerBuilder().setLayer(1));
        }

        @Override
        void onParentSet() {
            mOnParentSetCalled = true;
        }

        @Override
        void onDescendantOverrideConfigurationChanged() {
            mOnDescendantOverrideCalled = true;
            super.onDescendantOverrideConfigurationChanged();
        }

        @Override
        boolean isSelfAnimating() {
            return mIsAnimating;
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
    }

    private class TestWindowContainerBuilder {
        private int mLayer;
        private boolean mIsAnimating;
        private boolean mIsVisible;
        private Integer mOrientation;

        public TestWindowContainerBuilder() {
            reset();
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

        TestWindowContainerBuilder reset() {
            mLayer = 0;
            mIsAnimating = false;
            mIsVisible = false;
            mOrientation = null;
            return this;
        }

        TestWindowContainer build() {
            return new TestWindowContainer(mLayer, mIsAnimating, mIsVisible, mOrientation);
        }
    }

    private class MockSurfaceBuildingContainer extends WindowContainer<WindowContainer> {
        final SurfaceSession mSession = new SurfaceSession();

        MockSurfaceBuildingContainer() {
            super(sWm);
        }

        class MockSurfaceBuilder extends SurfaceControl.Builder {
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
    }
}
