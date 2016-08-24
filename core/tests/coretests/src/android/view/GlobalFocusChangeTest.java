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

import android.test.ActivityInstrumentationTestCase;
import android.test.FlakyTest;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.test.TouchUtils;
import android.test.suitebuilder.annotation.Suppress;
import android.view.View;
import android.view.KeyEvent;
import com.android.frameworks.coretests.R;

@Suppress // Flaky
public class GlobalFocusChangeTest extends ActivityInstrumentationTestCase<GlobalFocusChange> {
    private GlobalFocusChange mActivity;
    private View mLeft;
    private View mRight;

    public GlobalFocusChangeTest() {
        super("com.android.frameworks.coretests", GlobalFocusChange.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mActivity = getActivity();
        mLeft = mActivity.findViewById(R.id.left);
        mRight = mActivity.findViewById(R.id.right);
    }

    @Override
    protected void tearDown() throws Exception {
        mActivity.reset();
        super.tearDown();
    }

    @FlakyTest(tolerance = 4)
    @LargeTest
    public void testFocusChange() throws Exception {
        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);

        assertFalse(mLeft.isFocused());
        assertTrue(mRight.isFocused());

        assertSame(mLeft, mActivity.mOldFocus);
        assertSame(mRight, mActivity.mNewFocus);        
    }

    @FlakyTest(tolerance = 4)
    @MediumTest
    public void testEnterTouchMode() throws Exception {
        assertTrue(mLeft.isFocused());

        TouchUtils.tapView(this, mLeft);

        assertSame(mLeft, mActivity.mOldFocus);
        assertSame(null, mActivity.mNewFocus);        
    }

    @FlakyTest(tolerance = 4)
    @MediumTest
    public void testLeaveTouchMode() throws Exception {
        assertTrue(mLeft.isFocused());

        TouchUtils.tapView(this, mLeft);
        sendKeys(KeyEvent.KEYCODE_DPAD_RIGHT);

        assertTrue(mLeft.isFocused());

        assertSame(null, mActivity.mOldFocus);
        assertSame(mLeft, mActivity.mNewFocus);
    }
}
