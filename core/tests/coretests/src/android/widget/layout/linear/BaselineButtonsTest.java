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

package android.widget.layout.linear;

import android.app.Activity;
import android.test.ActivityInstrumentationTestCase;
import android.view.View;
import android.widget.ImageButton;

import androidx.test.filters.MediumTest;

import com.android.frameworks.coretests.R;

public class BaselineButtonsTest extends ActivityInstrumentationTestCase<BaselineButtons> {
    private View mCurrentTime;
    private View mTotalTime;
    private ImageButton mPrev;
    private ImageButton mNext;
    private ImageButton mPause;
    private View mLayout;

    public BaselineButtonsTest() {
        super("com.android.frameworks.coretests", BaselineButtons.class);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        final Activity activity = getActivity();
        mCurrentTime = activity.findViewById(R.id.currenttime);
        mTotalTime = activity.findViewById(R.id.totaltime);
        mPrev = (ImageButton) activity.findViewById(R.id.prev);
        mNext = (ImageButton) activity.findViewById(R.id.next);
        mPause = (ImageButton) activity.findViewById(R.id.pause);
        mLayout = activity.findViewById(R.id.layout);
    }

    @MediumTest
    public void testPreconditions() {
        assertNotNull(mCurrentTime);    
        assertNotNull(mTotalTime);  
        assertNotNull(mPrev);  
        assertNotNull(mNext);  
        assertNotNull(mPause);  
        assertNotNull(mLayout);  
    }
    
    @MediumTest
    public void testLayout() {
        int pauseHeight =  Math.max(mPause.getDrawable().getIntrinsicHeight()
                + mPause.getPaddingTop() + mPause.getPaddingBottom(),
                mPause.getBackground().getMinimumHeight());
        int prevHeight = Math.max(mPrev.getDrawable().getIntrinsicHeight() + mPrev.getPaddingTop()
                + mPrev.getPaddingBottom(),
                mPrev.getBackground().getMinimumHeight());
        int nextHeight = Math.max(mNext.getDrawable().getIntrinsicHeight() + mNext.getPaddingTop()
                + mNext.getPaddingBottom(),
                mNext.getBackground().getMinimumHeight());
        assertEquals("Layout incorrect height", pauseHeight, mLayout.getHeight()); 
        assertEquals("Pause incorrect height", pauseHeight, mPause.getHeight()); 
        assertEquals("Prev incorrect height", prevHeight, mPrev.getHeight()); 
        assertEquals("Next incorrect height", nextHeight, mNext.getHeight()); 
        assertEquals("Pause wrong top", 0, mPause.getTop()); 
        assertEquals("Prev wrong top", (pauseHeight - prevHeight) / 2, mPrev.getTop());
        assertEquals("Next wrong top", (pauseHeight - nextHeight) / 2, mNext.getTop());
        assertEquals("CurrentTime wrong bottom",  pauseHeight, mCurrentTime.getBottom());
        assertEquals("TotalTime wrong bottom",  pauseHeight, mTotalTime.getBottom());
        assertTrue("CurrentTime too tall", mCurrentTime.getTop() > 0);
        assertTrue("TotalTime too tall", mTotalTime.getTop() > 0);
    }
}
