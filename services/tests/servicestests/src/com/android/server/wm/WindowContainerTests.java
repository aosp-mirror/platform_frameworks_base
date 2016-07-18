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

/**
 * Test class for {@link WindowContainer}.
 *
 * Build: mmma -j32 frameworks/base/services/tests/servicestests
 * Install: adb install -r out/target/product/marlin/data/app/FrameworksServicesTests/FrameworksServicesTests.apk
 * Run: adb shell am instrument -w -e class com.android.server.wm.WindowContainerTests com.android.frameworks.servicestests/android.support.test.runner.AndroidJUnitRunner
 */
@SmallTest
public class WindowContainerTests extends AndroidTestCase {

    public void testCreation() throws Exception {
        final TestWindowContainer w = new TestWindowContainer();
        assertNull("window must have no parent", w.getParentWindow());
        assertEquals("window must have no children", 0, w.getChildrenCount());
    }

    public void testAdd() throws Exception {
        final TestWindowContainer root = new TestWindowContainer();

        final TestWindowContainer layer1 = root.addChildWindow(1);
        final TestWindowContainer secondLayer1 = root.addChildWindow(1);
        final TestWindowContainer layer2 = root.addChildWindow(2);
        final TestWindowContainer layerNeg1 = root.addChildWindow(-1);
        final TestWindowContainer layerNeg2 = root.addChildWindow(-2);
        final TestWindowContainer secondLayerNeg1 = root.addChildWindow(-1);
        final TestWindowContainer layer0 = root.addChildWindow(0);

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
        final TestWindowContainer root = new TestWindowContainer();

        final TestWindowContainer child1 = root.addChildWindow(1);
        final TestWindowContainer child2 = root.addChildWindow(1);
        final TestWindowContainer child11 = child1.addChildWindow(1);
        final TestWindowContainer child12 = child1.addChildWindow(1);
        final TestWindowContainer child21 = child2.addChildWindow(1);

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

    public void testRemove() throws Exception {
        final TestWindowContainer root = new TestWindowContainer();

        final TestWindowContainer child1 = root.addChildWindow(1);
        final TestWindowContainer child2 = root.addChildWindow(1);
        final TestWindowContainer child11 = child1.addChildWindow(1);
        final TestWindowContainer child12 = child1.addChildWindow(1);
        final TestWindowContainer child21 = child2.addChildWindow(1);

        child12.remove();
        assertNull(child12.getParentWindow());
        assertEquals(1, child1.getChildrenCount());
        assertFalse(child1.hasChild(child12));
        assertFalse(root.hasChild(child12));

        child2.remove();
        assertNull(child2.getParentWindow());
        assertNull(child21.getParentWindow());
        assertEquals(0, child2.getChildrenCount());
        assertEquals(1, root.getChildrenCount());
        assertFalse(root.hasChild(child2));
        assertFalse(root.hasChild(child21));

        assertTrue(root.hasChild(child1));
        assertTrue(root.hasChild(child11));

        root.remove();
        assertEquals(0, root.getChildrenCount());
    }

    /* Used so we can gain access to some protected members of the {@link WindowContainer} class */
    private class TestWindowContainer extends WindowContainer {
        private final int mLayer;

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

        TestWindowContainer() {
            mLayer = 0;
        }

        TestWindowContainer(int layer) {
            mLayer = layer;
        }

        TestWindowContainer getParentWindow() {
            return (TestWindowContainer) getParent();
        }

        int getChildrenCount() {
            return mChildren.size();
        }

        TestWindowContainer addChildWindow(int layer) {
            TestWindowContainer child = new TestWindowContainer(layer);
            addChild(child, mWindowSubLayerComparator);
            return child;
        }

        TestWindowContainer getChildAt(int index) {
            return (TestWindowContainer) mChildren.get(index);
        }
    }

}
