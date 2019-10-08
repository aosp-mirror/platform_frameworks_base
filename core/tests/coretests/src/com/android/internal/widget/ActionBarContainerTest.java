/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.internal.widget;

import android.content.Context;
import android.test.AndroidTestCase;
import android.view.ActionMode;
import android.view.View;
import android.view.ViewGroup;

import androidx.test.filters.SmallTest;

/**
 * Tests for {@link ActionBarContainer}.
 */
@SmallTest
public class ActionBarContainerTest extends AndroidTestCase {
    private ActionBarContainer mActionBarContainer;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mActionBarContainer = new ActionBarContainer(mContext);
    }

    public void testPrimaryActionModesAreStopped() {
        TestViewGroup viewGroup = new TestViewGroup(mContext);
        viewGroup.addView(mActionBarContainer);

        ActionMode mode = mActionBarContainer.startActionModeForChild(
                null, null, ActionMode.TYPE_PRIMARY);

        assertNull(mode);
        // Should not bubble up.
        assertFalse(viewGroup.isStartActionModeForChildTypedCalled);
        assertFalse(viewGroup.isStartActionModeForChildTypelessCalled);

        mode = mActionBarContainer.startActionModeForChild(null, null);

        assertNull(mode);
        // Should not bubble up.
        assertFalse(viewGroup.isStartActionModeForChildTypedCalled);
        assertFalse(viewGroup.isStartActionModeForChildTypelessCalled);
    }

    public void testFloatingActionModesAreBubbledUp() {
        TestViewGroup viewGroup = new TestViewGroup(mContext);
        viewGroup.addView(mActionBarContainer);

        mActionBarContainer.startActionModeForChild(null, null, ActionMode.TYPE_FLOATING);

        // Should bubble up.
        assertTrue(viewGroup.isStartActionModeForChildTypedCalled);
    }

    private static class TestViewGroup extends ViewGroup {
        boolean isStartActionModeForChildTypedCalled = false;
        boolean isStartActionModeForChildTypelessCalled = false;

        public TestViewGroup(Context context) {
            super(context);
        }

        @Override
        public ActionMode startActionModeForChild(View originalView, ActionMode.Callback callback) {
            isStartActionModeForChildTypelessCalled = true;
            return super.startActionModeForChild(originalView, callback);
        }

        @Override
        public ActionMode startActionModeForChild(
                View originalView, ActionMode.Callback callback, int type) {
            isStartActionModeForChildTypedCalled = true;
            return super.startActionModeForChild(originalView, callback, type);
        }

        @Override
        protected void onLayout(boolean changed, int l, int t, int r, int b) {}
    }
}
