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

package android.widget.listview.focus;

import android.test.ActivityInstrumentationTestCase;
import android.widget.InternalSelectionView;
import android.view.KeyEvent;
import android.widget.ListView;
import android.widget.listview.AdjacentListsWithAdjacentISVsInside;

import androidx.test.filters.MediumTest;
import androidx.test.filters.Suppress;

public class AdjacentListsWithAdjacentISVsInsideTest extends ActivityInstrumentationTestCase<AdjacentListsWithAdjacentISVsInside> {

    private ListView mLeftListView;
    private InternalSelectionView mLeftIsv;
    private InternalSelectionView mLeftMiddleIsv;
    private ListView mRightListView;
    private InternalSelectionView mRightMiddleIsv;
    private InternalSelectionView mRightIsv;

    public AdjacentListsWithAdjacentISVsInsideTest() {
        super("com.android.frameworks.coretests", AdjacentListsWithAdjacentISVsInside.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        final AdjacentListsWithAdjacentISVsInside a = getActivity();
        mLeftListView = a.getLeftListView();
        mLeftIsv = a.getLeftIsv();
        mLeftMiddleIsv = a.getLeftMiddleIsv();
        mRightListView = a.getRightListView();
        mRightMiddleIsv = a.getRightMiddleIsv();
        mRightIsv = a.getRightIsv();
    }

    @MediumTest
    public void testPreconditions() {
        assertTrue(mLeftListView.hasFocus());
        assertTrue(mLeftIsv.isFocused());
        assertEquals(0, mLeftIsv.getSelectedRow());
    }

    /**
     * rockinist test name to date!
     */
    @MediumTest
    public void testFocusedRectAndFocusHintWorkWithinListItemHorizontal() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN);
        assertEquals(1, mLeftIsv.getSelectedRow());

        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue(mLeftListView.hasFocus());
        assertTrue(mLeftMiddleIsv.isFocused());
        assertEquals("mLeftMiddleIsv.getSelectedRow()", 1, mLeftMiddleIsv.getSelectedRow());

        sendKeys(KeyEvent.KEYCODE_DPAD_LEFT);
        assertTrue(mLeftIsv.isFocused());
        assertEquals("mLeftIsv.getSelectedRow()", 1, mLeftIsv.getSelectedRow());
    }

    @MediumTest
    @Suppress // Failing.
    public void testFocusTransfersOutsideOfListWhenNoCandidateInsideHorizontal() {
        sendKeys(KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT);

        assertTrue(mLeftListView.hasFocus());
        assertTrue(mLeftMiddleIsv.isFocused());
        assertEquals(2, mLeftMiddleIsv.getSelectedRow());

        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue("mRightListView.hasFocus()", mRightListView.hasFocus());
        assertTrue("mRightMiddleIsv.isFocused()", mRightMiddleIsv.isFocused());
        assertEquals("mRightMiddleIsv.getSelectedRow()", 2, mRightMiddleIsv.getSelectedRow());  
    }    
}
