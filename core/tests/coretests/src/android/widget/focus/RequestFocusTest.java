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

package android.widget.focus;

import android.widget.focus.RequestFocus;
import com.android.frameworks.coretests.R;

import android.os.Handler;
import android.test.ActivityInstrumentationTestCase;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.widget.Button;
import android.util.AndroidRuntimeException;

/**
 * {@link RequestFocusTest} is set up to exercise cases where the views that
 * have focus become invisible or GONE.
 */
public class RequestFocusTest extends ActivityInstrumentationTestCase<RequestFocus> {

    private Button mTopLeftButton;
    private Button mBottomLeftButton;
    private Button mTopRightButton;
    private Button mBottomRightButton;
    private Handler mHandler;

    public RequestFocusTest() {
        super("com.android.frameworks.coretests", RequestFocus.class);
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        final RequestFocus a = getActivity();
        mHandler = a.getHandler();
        mTopLeftButton = (Button) a.findViewById(R.id.topLeftButton);
        mBottomLeftButton = (Button) a.findViewById(R.id.bottomLeftButton);
        mTopRightButton = (Button) a.findViewById(R.id.topRightButton);
        mBottomRightButton = (Button) a.findViewById(R.id.bottomRightButton);
    }

    // Test that setUp did what we expect it to do.  These asserts
    // can't go in SetUp, or the test will hang.
    @MediumTest
    public void testSetUpConditions() throws Exception {
        assertNotNull(mHandler);
        assertNotNull(mTopLeftButton);
        assertNotNull(mTopRightButton);
        assertNotNull(mBottomLeftButton);
        assertNotNull(mBottomRightButton);
        assertTrue("requestFocus() should work from onCreate.", mBottomRightButton.hasFocus());
    }

    // Test that a posted requestFocus works.
    @LargeTest
    public void testPostedRequestFocus() throws Exception {
        mHandler.post(new Runnable() { public void run() {
            mBottomLeftButton.requestFocus();
        }});
        synchronized(this) {
            try {
                wait(500);
            } catch (InterruptedException e) {
                // Don't care.
            }
        }
        assertTrue("Focus should move to bottom left", mBottomLeftButton.hasFocus());
    }

    // Test that a requestFocus from the wrong thread fails.
    @MediumTest
    public void testWrongThreadRequestFocusFails() throws Exception {
        try {
            mTopRightButton.requestFocus();
            fail("requestFocus from wrong thread should raise exception.");
        } catch (AndroidRuntimeException e) {
            // Expected.  The actual exception is not public, so we can't catch it.
            assertEquals("android.view.ViewAncestor$CalledFromWrongThreadException",
                         e.getClass().getName());
        }
    }
}
