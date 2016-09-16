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

import org.junit.Test;
import org.junit.runner.RunWith;

import android.platform.test.annotations.Presubmit;
import android.support.test.filters.SmallTest;
import android.support.test.runner.AndroidJUnit4;

import java.util.Comparator;

import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_BEHIND;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSET;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

/**
 * Test class for {@link WindowContainer}.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/$TARGET_PRODUCT/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.wm.WindowContainerTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class WindowContainerTests {

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
    public void testIsAnimating() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow(builder.setIsAnimating(true));
        final TestWindowContainer child21 = child2.addChildWindow();

        assertTrue(root.isAnimating());
        assertTrue(child1.isAnimating());
        assertFalse(child11.isAnimating());
        assertTrue(child12.isAnimating());
        assertFalse(child2.isAnimating());
        assertFalse(child21.isAnimating());
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
        // Unset because the container isn't visible even though it has a child that thinks it is
        // visible.
        assertEquals(SCREEN_ORIENTATION_UNSET, invisible.getOrientation());
        // Unspecified because we are visible and we didn't specify an orientation and there isn't
        // a visible child.
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, root.getOrientation());

        builder.setIsVisible(true).setLayer(-3);
        final TestWindowContainer visibleUnset = root.addChildWindow(builder);
        visibleUnset.setOrientation(SCREEN_ORIENTATION_UNSET);
        assertEquals(SCREEN_ORIENTATION_UNSET, visibleUnset.getOrientation());
        assertEquals(SCREEN_ORIENTATION_UNSPECIFIED, root.getOrientation());

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

    /* Used so we can gain access to some protected members of the {@link WindowContainer} class */
    private class TestWindowContainer extends WindowContainer<TestWindowContainer> {
        private final int mLayer;
        private boolean mIsAnimating;
        private boolean mIsVisible;
        private boolean mFillsParent;

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

        TestWindowContainer(int layer, boolean isAnimating, boolean isVisible) {
            mLayer = layer;
            mIsAnimating = isAnimating;
            mIsVisible = isVisible;
            mFillsParent = true;
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

        TestWindowContainer getChildAt(int index) {
            return mChildren.get(index);
        }

        @Override
        boolean isAnimating() {
            return mIsAnimating || super.isAnimating();
        }

        @Override
        boolean isVisible() {
            return mIsVisible;
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

        TestWindowContainerBuilder reset() {
            mLayer = 0;
            mIsAnimating = false;
            mIsVisible = false;
            return this;
        }

        TestWindowContainer build() {
            return new TestWindowContainer(mLayer, mIsAnimating, mIsVisible);
        }
    }
}
