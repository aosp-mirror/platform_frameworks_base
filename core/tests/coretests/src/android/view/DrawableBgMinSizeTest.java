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

package android.view;

import android.graphics.drawable.Drawable;
import android.test.ActivityInstrumentationTestCase;
import android.test.TouchUtils;
import android.widget.AbsoluteLayout;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

/**
 * {@link DrawableBgMinSize} exercises Views to obey their background drawable's
 * minimum sizes.
 */
public class DrawableBgMinSizeTest extends
        ActivityInstrumentationTestCase<DrawableBgMinSize> {
    private Button mChangeBackgroundsButton;
    
    private Drawable mBackgroundDrawable;
    private Drawable mBigBackgroundDrawable;
    
    private TextView mTextView;
    private LinearLayout mLinearLayout;
    private RelativeLayout mRelativeLayout;
    private FrameLayout mFrameLayout;
    private AbsoluteLayout mAbsoluteLayout;
    
    public DrawableBgMinSizeTest() {
        super("com.android.frameworks.coretests", DrawableBgMinSize.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final DrawableBgMinSize a = getActivity();

        mChangeBackgroundsButton = (Button) a.findViewById(R.id.change_backgrounds);
        mBackgroundDrawable = a.getResources().getDrawable(R.drawable.drawable_background);
        mBigBackgroundDrawable = a.getResources().getDrawable(R.drawable.big_drawable_background);
        mTextView = (TextView) a.findViewById(R.id.text_view);
        mLinearLayout = (LinearLayout) a.findViewById(R.id.linear_layout);
        mRelativeLayout = (RelativeLayout) a.findViewById(R.id.relative_layout);
        mFrameLayout = (FrameLayout) a.findViewById(R.id.frame_layout);
        mAbsoluteLayout = (AbsoluteLayout) a.findViewById(R.id.absolute_layout);
    }

    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mChangeBackgroundsButton);
        assertNotNull(mBackgroundDrawable);
        assertNotNull(mBigBackgroundDrawable);
        assertNotNull(mTextView);
        assertNotNull(mLinearLayout);
        assertNotNull(mRelativeLayout);
        assertNotNull(mFrameLayout);
        assertNotNull(mAbsoluteLayout);
    }
    
    public void doMinimumSizeTest(View view) throws Exception {
        assertTrue(view.getClass().getSimpleName() + " should respect the background Drawable's minimum width",
                view.getWidth() >= mBackgroundDrawable.getMinimumWidth());
        assertTrue(view.getClass().getSimpleName() + " should respect the background Drawable's minimum height",
                view.getHeight() >= mBackgroundDrawable.getMinimumHeight());
    }

    @MediumTest
    public void testTextViewMinimumSize() throws Exception {
        doMinimumSizeTest(mTextView);
    }
    
    @MediumTest
    public void testLinearLayoutMinimumSize() throws Exception {
        doMinimumSizeTest(mLinearLayout);
    }
    
    @MediumTest
    public void testRelativeLayoutMinimumSize() throws Exception {
        doMinimumSizeTest(mRelativeLayout);
    }
    
    @MediumTest
    public void testAbsoluteLayoutMinimumSize() throws Exception {
        doMinimumSizeTest(mAbsoluteLayout);
    }
    
    @MediumTest
    public void testFrameLayoutMinimumSize() throws Exception {
        doMinimumSizeTest(mFrameLayout);
    }
    
    public void doDiffBgMinimumSizeTest(final View view) throws Exception {
        // Change to the bigger backgrounds
        TouchUtils.tapView(this, mChangeBackgroundsButton);

        assertTrue(view.getClass().getSimpleName()
                + " should respect the different bigger background Drawable's minimum width", view
                .getWidth() >= mBigBackgroundDrawable.getMinimumWidth());
        assertTrue(view.getClass().getSimpleName()
                + " should respect the different bigger background Drawable's minimum height", view
                .getHeight() >= mBigBackgroundDrawable.getMinimumHeight());
    }

    @MediumTest
    public void testTextViewDiffBgMinimumSize() throws Exception {
        doDiffBgMinimumSizeTest(mTextView);
    }
    
    @MediumTest
    public void testLinearLayoutDiffBgMinimumSize() throws Exception {
        doDiffBgMinimumSizeTest(mLinearLayout);
    }
    
    @MediumTest
    public void testRelativeLayoutDiffBgMinimumSize() throws Exception {
        doDiffBgMinimumSizeTest(mRelativeLayout);
    }
    
    @MediumTest
    public void testAbsoluteLayoutDiffBgMinimumSize() throws Exception {
        doDiffBgMinimumSizeTest(mAbsoluteLayout);
    }
    
    @MediumTest
    public void testFrameLayoutDiffBgMinimumSize() throws Exception {
        doDiffBgMinimumSizeTest(mFrameLayout);
    }
    
}
