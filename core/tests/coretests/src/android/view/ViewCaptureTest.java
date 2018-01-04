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

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.support.test.filters.SmallTest;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.util.SparseIntArray;
import android.view.ViewDebug.CanvasProvider;
import android.view.ViewDebug.HardwareCanvasProvider;
import android.view.ViewDebug.SoftwareCanvasProvider;

import com.android.frameworks.coretests.R;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

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
        mViewToCapture = mActivity.findViewById(R.id.capture);
    }

    @Test
    @SmallTest
    public void testCreateSnapshot_software() {
        assertChildrenVisibility();
        testCreateSnapshot(new SoftwareCanvasProvider(), true,
                R.drawable.view_capture_test_no_children_golden);
        assertChildrenVisibility();
        testCreateSnapshot(new SoftwareCanvasProvider(), false,
                R.drawable.view_capture_test_with_children_golden);
        assertChildrenVisibility();
    }

    @Test
    @SmallTest
    public void testCreateSnapshot_hardware() {
        Assume.assumeTrue(mViewToCapture.isHardwareAccelerated());
        assertChildrenVisibility();
        testCreateSnapshot(new HardwareCanvasProvider(), true,
                R.drawable.view_capture_test_no_children_golden);
        assertChildrenVisibility();
        testCreateSnapshot(new HardwareCanvasProvider(), false,
                R.drawable.view_capture_test_with_children_golden);
        assertChildrenVisibility();
    }

    private void testCreateSnapshot(
            CanvasProvider canvasProvider, boolean skipChildren, int goldenResId) {
        Bitmap result = mViewToCapture.createSnapshot(canvasProvider, skipChildren);
        result.setHasAlpha(false); // resource will have no alpha, since content is opaque
        Bitmap golden = BitmapFactory.decodeResource(mActivity.getResources(), goldenResId);

        // We dont care about the config of the bitmap, so convert to same config before comparing
        result = result.copy(golden.getConfig(), false);
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
