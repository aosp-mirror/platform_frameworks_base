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

package android.widget.gridview;

import android.test.ActivityInstrumentationTestCase;
import android.test.ViewAsserts;
import android.util.GridScenario;
import android.widget.GridView;

import androidx.test.filters.MediumTest;

public class GridSetSelectionBaseTest<T extends GridScenario> extends ActivityInstrumentationTestCase<T> {
    private T mActivity;
    private GridView mGridView;

    protected GridSetSelectionBaseTest(Class<T> klass) {
        super("com.android.frameworks.coretests", klass);
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

        // First item should be selected
        if (mGridView.isStackFromBottom()) {
            assertEquals(mGridView.getAdapter().getCount() - 1,
                    mGridView.getSelectedItemPosition());
        } else {
            assertEquals(0, mGridView.getSelectedItemPosition());
        }
    }

    @MediumTest
    public void testSetSelectionToTheEnd() {
        final int target = mGridView.getAdapter().getCount() - 1;
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mGridView.setSelection(target);
            }
        });
        getInstrumentation().waitForIdleSync();

        assertEquals(mGridView.getSelectedItemPosition(), target);
        assertNotNull(mGridView.getSelectedView());

        ViewAsserts.assertOnScreen(mGridView, mGridView.getSelectedView());
    }

    @MediumTest
    public void testSetSelectionToMiddle() {
        final int target = mGridView.getAdapter().getCount() / 2;
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mGridView.setSelection(target);
            }
        });
        getInstrumentation().waitForIdleSync();

        assertEquals(mGridView.getSelectedItemPosition(), target);
        assertNotNull(mGridView.getSelectedView());

        ViewAsserts.assertOnScreen(mGridView, mGridView.getSelectedView());
    }
    
    @MediumTest
    public void testSetSelectionToTheTop() {
        mActivity.runOnUiThread(new Runnable() {
            public void run() {
                mGridView.setSelection(0);
            }
        });
        getInstrumentation().waitForIdleSync();

        assertEquals(mGridView.getSelectedItemPosition(), 0);
        assertNotNull(mGridView.getSelectedView());

        ViewAsserts.assertOnScreen(mGridView, mGridView.getSelectedView());
    }
}
