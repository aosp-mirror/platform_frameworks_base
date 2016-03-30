/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package android.view;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.test.filters.SmallTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseIntArray;

import com.android.frameworks.coretests.R;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ViewCaptureTest {

    private static final SparseIntArray EXPECTED_CHILDREN_VISIBILITY = new SparseIntArray();
    static {
        EXPECTED_CHILDREN_VISIBILITY.append(R.id.child1, View.VISIBLE);
        EXPECTED_CHILDREN_VISIBILITY.append(R.id.child2, View.INVISIBLE);
        EXPECTED_CHILDREN_VISIBILITY.append(R.id.child3, View.GONE);
        EXPECTED_CHILDREN_VISIBILITY.append(R.id.child4, View.VISIBLE);
    }

    @Rule
    public ActivityTestRule<ViewCaptureTestActivity> mActivityRule = new ActivityTestRule<>(
            ViewCaptureTestActivity.class);

    private Activity mActivity;
    private ViewGroup mViewToCapture;

    @Before
    public void setUp() throws Exception {
        mActivity = mActivityRule.getActivity();
        mViewToCapture = (ViewGroup) mActivity.findViewById(R.id.capture);
    }

    @Test
    @SmallTest
    public void testCreateSnapshot() {
        assertChildrenVisibility();
        testCreateSnapshot(true, R.drawable.view_capture_test_no_children_golden);
        assertChildrenVisibility();
        testCreateSnapshot(false, R.drawable.view_capture_test_with_children_golden);
        assertChildrenVisibility();
    }

    private void testCreateSnapshot(boolean skipChildren, int goldenResId) {
        Bitmap result = mViewToCapture.createSnapshot(Bitmap.Config.ARGB_8888, 0, skipChildren);
        Bitmap golden = BitmapFactory.decodeResource(mActivity.getResources(), goldenResId);
        assertTrue(golden.sameAs(result));
    }

    private void assertChildrenVisibility() {
        for (int i = 0; i < EXPECTED_CHILDREN_VISIBILITY.size(); i++) {
            int id = EXPECTED_CHILDREN_VISIBILITY.keyAt(i);
            View child = mViewToCapture.findViewById(id);
            Assert.assertEquals(EXPECTED_CHILDREN_VISIBILITY.get(id), child.getVisibility());
        }
    }
}
