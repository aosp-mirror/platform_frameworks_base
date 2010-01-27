/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.view;

import android.content.Context;
import android.test.AndroidTestCase;
import android.test.suitebuilder.annotation.SmallTest;

public class ViewGroupAttributesTest extends AndroidTestCase {

    private MyViewGroup mViewGroup;

    private static final class MyViewGroup extends ViewGroup {

        public MyViewGroup(Context context) {
            super(context);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {
        }
        
        @Override
        public boolean isChildrenDrawnWithCacheEnabled() {
            return super.isChildrenDrawnWithCacheEnabled();
        }
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mViewGroup = new MyViewGroup(getContext());
    }

    @SmallTest
    public void testDescendantFocusabilityEnum() {
        assertEquals("expected ViewGroup.FOCUS_BEFORE_DESCENDANTS to be default",
                ViewGroup.FOCUS_BEFORE_DESCENDANTS, mViewGroup.getDescendantFocusability());

        // remember some state before we muck with flags
        final boolean isAnimationCachEnabled = mViewGroup.isAnimationCacheEnabled();
        final boolean isAlwaysDrawnWithCacheEnabled = mViewGroup.isAlwaysDrawnWithCacheEnabled();
        final boolean isChildrenDrawnWithCacheEnabled = mViewGroup.isChildrenDrawnWithCacheEnabled();

        mViewGroup.setDescendantFocusability(ViewGroup.FOCUS_AFTER_DESCENDANTS);
        assertEquals(ViewGroup.FOCUS_AFTER_DESCENDANTS, mViewGroup.getDescendantFocusability());

        mViewGroup.setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        assertEquals(ViewGroup.FOCUS_BLOCK_DESCENDANTS, mViewGroup.getDescendantFocusability());

        mViewGroup.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);
        assertEquals(ViewGroup.FOCUS_BEFORE_DESCENDANTS, mViewGroup.getDescendantFocusability());

        // verify we didn't change something unrelated
        final String msg = "setDescendantFocusability messed with an unrelated flag";
        assertEquals(msg, isAnimationCachEnabled, mViewGroup.isAnimationCacheEnabled());
        assertEquals(msg, isAlwaysDrawnWithCacheEnabled, mViewGroup.isAlwaysDrawnWithCacheEnabled());
        assertEquals(msg, isChildrenDrawnWithCacheEnabled, mViewGroup.isChildrenDrawnWithCacheEnabled());
    }

    @SmallTest
    public void testWrongIntSetForDescendantFocusabilityEnum() {
        try {
            mViewGroup.setDescendantFocusability(0);
            fail("expected setting wrong flag to throw an exception");
        } catch (IllegalArgumentException expected) {
        }
    }
}
