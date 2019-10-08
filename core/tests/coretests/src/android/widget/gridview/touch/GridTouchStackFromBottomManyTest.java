/*
 * Copyright (C) 2007 The Android Open Source Project
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

package android.widget.gridview.touch;

import android.test.ActivityInstrumentationTestCase;
import android.test.TouchUtils;
import android.view.View;
import android.widget.GridView;
import android.widget.gridview.GridStackFromBottomMany;

import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;

public class GridTouchStackFromBottomManyTest extends ActivityInstrumentationTestCase<GridStackFromBottomMany> {
    private GridStackFromBottomMany mActivity;
    private GridView mGridView;

    public GridTouchStackFromBottomManyTest() {
        super("com.android.frameworks.coretests", GridStackFromBottomMany.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mActivity = getActivity();
        mGridView = getActivity().getGridView();
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mActivity);
        assertNotNull(mGridView);

        // Last item should be selected
        assertEquals(mGridView.getAdapter().getCount() - 1, mGridView.getSelectedItemPosition());
    }

    @LargeTest
    public void testScrollToTop() {
        View firstChild;
        TouchUtils.scrollToTop(this, mGridView);

        // Nothing should be selected
        assertEquals("Selection still available after touch", -1,
                mGridView.getSelectedItemPosition());

        firstChild = mGridView.getChildAt(0);

        assertEquals("Item zero not the first child in the grid", 0, firstChild.getId());

        assertEquals("Item zero not at the top of the grid",
                mGridView.getListPaddingTop(), firstChild.getTop());
    }

    @LargeTest
    public void testScrollToBottom() {
        TouchUtils.scrollToBottom(this, mGridView);

        // Nothing should be selected
        assertEquals("Selection still available after touch", -1,
                mGridView.getSelectedItemPosition());

        View lastChild = mGridView.getChildAt(mGridView.getChildCount() - 1);

        assertEquals("Grid is not scrolled to the bottom", mGridView.getAdapter().getCount() - 1,
                lastChild.getId());

        assertEquals("Last item is not touching the bottom edge",
                mGridView.getHeight() - mGridView.getListPaddingBottom(), lastChild.getBottom());
    }
}
