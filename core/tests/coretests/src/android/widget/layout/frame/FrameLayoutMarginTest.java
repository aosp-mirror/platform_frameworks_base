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

package android.widget.layout.frame;

import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.ViewAsserts;
import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.layout.frame.FrameLayoutMargin;
import com.android.frameworks.coretests.R;

public class FrameLayoutMarginTest extends ActivityInstrumentationTestCase<FrameLayoutMargin> {
    private View mLeftView;
    private View mRightView;
    private View mTopView;
    private View mBottomView;
    private View mParent;

    public FrameLayoutMarginTest() {
        super("com.android.frameworks.coretests", FrameLayoutMargin.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final Activity activity = getActivity();

        mParent = activity.findViewById(R.id.parent);

        mLeftView = activity.findViewById(R.id.left);
        mRightView = activity.findViewById(R.id.right);
        mTopView = activity.findViewById(R.id.top);
        mBottomView = activity.findViewById(R.id.bottom);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mParent);
        assertNotNull(mLeftView);
        assertNotNull(mRightView);
        assertNotNull(mTopView);
        assertNotNull(mBottomView);
    }

    @MediumTest
    public void testLeftMarginAligned() throws Exception {
        ViewAsserts.assertLeftAligned(mParent, mLeftView,
                ((ViewGroup.MarginLayoutParams) mLeftView.getLayoutParams()).leftMargin);
    }

    @MediumTest
    public void testRightMarginAligned() throws Exception {
        ViewAsserts.assertRightAligned(mParent, mRightView,
                ((ViewGroup.MarginLayoutParams) mRightView.getLayoutParams()).rightMargin);
    }

    @MediumTest
    public void testTopMarginAligned() throws Exception {
        ViewAsserts.assertTopAligned(mParent, mTopView,
                ((ViewGroup.MarginLayoutParams) mTopView.getLayoutParams()).topMargin);
    }

    @MediumTest
    public void testBottomMarginAligned() throws Exception {
        ViewAsserts.assertBottomAligned(mParent, mBottomView,
                ((ViewGroup.MarginLayoutParams) mBottomView.getLayoutParams()).bottomMargin);
    }
}
