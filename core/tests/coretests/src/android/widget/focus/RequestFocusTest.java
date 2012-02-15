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

import static com.google.testing.littlemock.LittleMock.inOrder;
import static com.google.testing.littlemock.LittleMock.mock;

import android.os.Handler;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.test.suitebuilder.annotation.LargeTest;
import android.test.suitebuilder.annotation.MediumTest;
import android.util.AndroidRuntimeException;
import android.view.View;
import android.view.View.OnFocusChangeListener;
import android.view.ViewTreeObserver.OnGlobalFocusChangeListener;
import android.widget.Button;

import com.android.frameworks.coretests.R;
import com.google.testing.littlemock.LittleMock.InOrder;

/**
 * {@link RequestFocusTest} is set up to exercise cases where the views that
 * have focus become invisible or GONE.
 */
public class RequestFocusTest extends ActivityInstrumentationTestCase2<RequestFocus> {

    private Button mTopLeftButton;
    private Button mBottomLeftButton;
    private Button mTopRightButton;
    private Button mBottomRightButton;
    private Handler mHandler;

    public RequestFocusTest() {
        super(RequestFocus.class);
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
            assertEquals("android.view.ViewRootImpl$CalledFromWrongThreadException",
                         e.getClass().getName());
        }
    }

    /**
     * This tests checks the case in which the first focusable View clears focus.
     * In such a case the framework tries to give the focus to another View starting
     * from the top. Hence, the framework will try to give focus to the view that
     * wants to clear its focus.
     *
     * @throws Exception If an error occurs.
     */
    @UiThreadTest
    public void testOnFocusChangeCallbackOrderWhenClearingFocusOfFirstFocusable()
            throws Exception {
        // Get the first focusable.
        Button clearingFocusButton = mTopLeftButton;
        Button gainingFocusButton = mTopLeftButton;

        // Make sure that the clearing focus View is the first focusable.
        View focusCandidate = clearingFocusButton.getRootView().getParent().focusSearch(null,
                View.FOCUS_FORWARD);
        assertSame("The clearing focus button is the first focusable.",
                clearingFocusButton, focusCandidate);
        assertSame("The gaining focus button is the first focusable.",
                gainingFocusButton, focusCandidate);

        // Focus the clearing focus button.
        clearingFocusButton.requestFocus();
        assertTrue(clearingFocusButton.hasFocus());

        // Register the invocation order checker.
        CombinedListeners mock = mock(CombinedListeners.class);
        clearingFocusButton.setOnFocusChangeListener(mock);
        gainingFocusButton.setOnFocusChangeListener(mock);
        clearingFocusButton.getViewTreeObserver().addOnGlobalFocusChangeListener(mock);

        // Try to clear focus.
        clearingFocusButton.clearFocus();

        // Check that no callback was invoked since focus did not move.
        InOrder inOrder = inOrder(mock);
        inOrder.verify(mock).onFocusChange(clearingFocusButton, false);
        inOrder.verify(mock).onGlobalFocusChanged(clearingFocusButton, gainingFocusButton);
        inOrder.verify(mock).onFocusChange(gainingFocusButton, true);
    }

    public interface CombinedListeners extends OnFocusChangeListener, OnGlobalFocusChangeListener {}

    /**
     * This tests check whether the on focus change callbacks are invoked in
     * the proper order when a View loses focus and the framework gives it to
     * the fist focusable one.
     *
     * @throws Exception
     */
    @UiThreadTest
    public void testOnFocusChangeCallbackOrderWhenClearingFocusOfNotFirstFocusable()
            throws Exception {
        Button clearingFocusButton = mTopRightButton;
        Button gainingFocusButton = mTopLeftButton;

        // Make sure that the clearing focus View is not the first focusable.
        View focusCandidate = clearingFocusButton.getRootView().getParent().focusSearch(null,
                View.FOCUS_FORWARD);
        assertNotSame("The clearing focus button is not the first focusable.",
                clearingFocusButton, focusCandidate);
        assertSame("The gaining focus button is the first focusable.",
                gainingFocusButton, focusCandidate);

        // Focus the clearing focus button.
        clearingFocusButton.requestFocus();
        assertTrue(clearingFocusButton.hasFocus());

        // Register the invocation order checker.
        CombinedListeners mock = mock(CombinedListeners.class);
        clearingFocusButton.setOnFocusChangeListener(mock);
        gainingFocusButton.setOnFocusChangeListener(mock);
        clearingFocusButton.getViewTreeObserver().addOnGlobalFocusChangeListener(mock);

        // Try to clear focus.
        clearingFocusButton.clearFocus();

        // Check that no callback was invoked since focus did not move.
        InOrder inOrder = inOrder(mock);
        inOrder.verify(mock).onFocusChange(clearingFocusButton, false);
        inOrder.verify(mock).onGlobalFocusChanged(clearingFocusButton, gainingFocusButton);
        inOrder.verify(mock).onFocusChange(gainingFocusButton, true);
    }
}
