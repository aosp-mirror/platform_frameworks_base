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

import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

import java.util.Comparator;
import java.util.LinkedList;

/**
 * Test class for {@link WindowContainer}.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/$TARGET_PRODUCT/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.wm.WindowContainerTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class WindowContainerTests extends AndroidTestCase {

    public void testCreation() throws Exception {
        final TestWindowContainer w = new TestWindowContainerBuilder().setLayer(0).build();
        assertNull("window must have no parent", w.getParentWindow());
        assertEquals("window must have no children", 0, w.getChildrenCount());
    }

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

    public void testDetachFromDisplay() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow(builder.setCanDetach(true));
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow(builder.setCanDetach(true));
        final TestWindowContainer child21 = child2.addChildWindow();

        assertTrue(root.detachFromDisplay());
        assertTrue(child1.detachFromDisplay());
        assertFalse(child11.detachFromDisplay());
        assertTrue(child12.detachFromDisplay());
        assertFalse(child2.detachFromDisplay());
        assertFalse(child21.detachFromDisplay());
    }

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

    public void testIsVisible() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).build();

        final TestWindowContainer child1 = root.addChildWindow(builder.setIsVisible(true));
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child12 = child1.addChildWindow(builder.setIsVisible(true));
        final TestWindowContainer child21 = child2.addChildWindow();

        assertTrue(root.isVisible());
        assertTrue(child1.isVisible());
        assertFalse(child11.isVisible());
        assertTrue(child12.isVisible());
        assertFalse(child2.isVisible());
        assertFalse(child21.isVisible());
    }

    public void testDetachChild() throws Exception {
        final TestWindowContainerBuilder builder = new TestWindowContainerBuilder();
        final TestWindowContainer root = builder.setLayer(0).build();
        final TestWindowContainer child1 = root.addChildWindow();
        final TestWindowContainer child2 = root.addChildWindow();
        final TestWindowContainer child11 = child1.addChildWindow();
        final TestWindowContainer child21 = child2.addChildWindow();

        assertTrue(root.hasChild(child2));
        assertTrue(root.hasChild(child21));
        root.detachChild(child2);
        assertFalse(root.hasChild(child2));
        assertFalse(root.hasChild(child21));
        assertNull(child2.getParentWindow());

        boolean gotException = false;
        assertTrue(root.hasChild(child11));
        try {
            // Can only detach our direct children.
            root.detachChild(child11);
        } catch (IllegalArgumentException e) {
            gotException = true;
        }
        assertTrue(gotException);
    }

    /* Used so we can gain access to some protected members of the {@link WindowContainer} class */
    private class TestWindowContainer extends WindowContainer {
        private final int mLayer;
        private final LinkedList<String> mUsers = new LinkedList();
        private final boolean mCanDetach;
        private boolean mIsAnimating;
        private boolean mIsVisible;
        private int mRemoveIfPossibleCount;
        private int mRemoveImmediatelyCount;

        /**
         * Compares 2 window layers and returns -1 if the first is lesser than the second in terms
         * of z-order and 1 otherwise.
         */
        private final Comparator<WindowContainer> mWindowSubLayerComparator = (w1, w2) -> {
            final int layer1 = ((TestWindowContainer)w1).mLayer;
            final int layer2 = ((TestWindowContainer)w2).mLayer;
            if (layer1 < layer2 || (layer1 == layer2 && layer2 < 0 )) {
                // We insert the child window into the list ordered by the mLayer.
                // For same layers, the negative one should go below others; the positive one should
                // go above others.
                return -1;
            }
            return 1;
        };

        TestWindowContainer(int layer, LinkedList<String> users, boolean canDetach,
                boolean isAnimating, boolean isVisible) {
            mLayer = layer;
            mUsers.addAll(users);
            mCanDetach = canDetach;
            mIsAnimating = isAnimating;
            mIsVisible = isVisible;
        }

        TestWindowContainer getParentWindow() {
            return (TestWindowContainer) getParent();
        }

        int getChildrenCount() {
            return mChildren.size();
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
            return (TestWindowContainer) mChildren.get(index);
        }

        @Override
        boolean detachFromDisplay() {
            return super.detachFromDisplay() || mCanDetach;
        }

        @Override
        boolean isAnimating() {
            return mIsAnimating || super.isAnimating();
        }

        @Override
        boolean isVisible() {
            return mIsVisible || super.isVisible();
        }

        @Override
        void removeImmediately() {
            super.removeImmediately();
            mRemoveImmediatelyCount++;
        }

        @Override
        void removeIfPossible() {
            super.removeIfPossible();
            mRemoveIfPossibleCount++;
        }
    }

    private class TestWindowContainerBuilder {
        private int mLayer;
        private LinkedList<String> mUsers = new LinkedList();
        private boolean mCanDetach;
        private boolean mIsAnimating;
        private boolean mIsVisible;

        TestWindowContainerBuilder setLayer(int layer) {
            mLayer = layer;
            return this;
        }

        TestWindowContainerBuilder addUser(String user) {
            mUsers.add(user);
            return this;
        }

        TestWindowContainerBuilder setCanDetach(boolean canDetach) {
            mCanDetach = canDetach;
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
            mUsers.clear();
            mCanDetach = false;
            mIsAnimating = false;
            mIsVisible = false;
            return this;
        }

        TestWindowContainer build() {
            return new TestWindowContainer(mLayer, mUsers, mCanDetach, mIsAnimating, mIsVisible);
        }
    }
}
