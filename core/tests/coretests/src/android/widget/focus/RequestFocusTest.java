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

import java.util.ArrayList;
import java.util.List;

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
     * wants to clear its focus. From a client perspective, the view does not loose
     * focus after the call, therefore no callback for focus change should be invoked.
     *
     * @throws Exception If an error occurs.
     */
    @UiThreadTest
    public void testOnFocusChangeNotCalledIfFocusDoesNotMove() throws Exception {
        // Get the first focusable.
        Button button = mTopLeftButton;

        // Make sure that the button is the first focusable and focus it.
        button.getRootView().requestFocus(View.FOCUS_DOWN);
        assertTrue(button.hasFocus());

        // Attach on focus change listener that should not be called.
        button.setOnFocusChangeListener(new OnFocusChangeListener() {
            @Override
            public void onFocusChange(View v, boolean hasFocus) {
                throw new IllegalStateException("Unexpeced call to"
                        + "OnFocusChangeListener#onFocusChange");
            }
        });

        // Attach on global focus change listener that should not be called.
        button.getViewTreeObserver().addOnGlobalFocusChangeListener(
                new OnGlobalFocusChangeListener() {
            @Override
            public void onGlobalFocusChanged(View oldFocus, View newFocus) {
                throw new IllegalStateException("Unexpeced call to"
                        + "OnFocusChangeListener#onFocusChange");
            }
        });

        // Try to clear focus.
        button.clearFocus();
    }

    /**
     * This tests check whether the on focus change callbacks are invoked in
     * the proper order when a View loses focus and the framework gives it to
     * the fist focusable one.
     *
     * @throws Exception
     */
    @UiThreadTest
    public void testOnFocusChangeCallbackOrder() throws Exception {
        // Get the first focusable.
        Button clearingFocusButton = mTopRightButton;
        Button gainingFocusButton = mTopLeftButton;

        // Make sure that the clearing focus is not the first focusable.
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
        CallbackOrderChecker checker = new CallbackOrderChecker(clearingFocusButton,
                gainingFocusButton);
        clearingFocusButton.setOnFocusChangeListener(checker);
        gainingFocusButton.setOnFocusChangeListener(checker);
        clearingFocusButton.getViewTreeObserver().addOnGlobalFocusChangeListener(checker);

        // Try to clear focus.
        clearingFocusButton.clearFocus();

        // Check that no callback was invoked since focus did not move.
        checker.verify();
    }

    /**
     * This class check whether the focus change callback are invoked in order.
     */
    private class CallbackOrderChecker implements OnFocusChangeListener,
            OnGlobalFocusChangeListener {

        private class CallbackInvocation {
            final String mMethodName;
            final Object[] mArguments;

            CallbackInvocation(String methodName, Object[] arguments) {
                mMethodName = methodName;
                mArguments = arguments;
            }
        }

        private final View mClearingFocusView;
        private final View mGainingFocusView;

        private final List<CallbackInvocation> mInvocations = new ArrayList<CallbackInvocation>();

        public CallbackOrderChecker(View clearingFocusView, View gainingFocusView) {
            mClearingFocusView = clearingFocusView;
            mGainingFocusView = gainingFocusView;
        }

        @Override
        public void onFocusChange(View view, boolean hasFocus) {
            CallbackInvocation invocation = new CallbackInvocation(
                    "OnFocusChangeListener#onFocusChange", new Object[] {view, hasFocus});
            mInvocations.add(invocation);
        }

        @Override
        public void onGlobalFocusChanged(View oldFocus, View newFocus) {
            CallbackInvocation invocation = new CallbackInvocation(
                    "OnFocusChangeListener#onFocusChange", new Object[] {oldFocus, newFocus});
            mInvocations.add(invocation);
        }

        public void verify() {
            assertSame("All focus change callback should be invoked.", 3, mInvocations.size());
            assertInvioked("Callback for View clearing focus explected.", 0,
                    "OnFocusChangeListener#onFocusChange",
                    new Object[] {mClearingFocusView, false});
            assertInvioked("Callback for View global focus change explected.", 1,
                    "OnFocusChangeListener#onFocusChange", new Object[] {mClearingFocusView,
                    mGainingFocusView});
            assertInvioked("Callback for View gaining focus explected.", 2,
                    "OnFocusChangeListener#onFocusChange", new Object[] {mGainingFocusView, true});
        }

        private void assertInvioked(String message, int order, String methodName,
                Object[] arguments) {
            CallbackInvocation invocation = mInvocations.get(order);
            assertEquals(message, methodName, invocation.mMethodName);
            assertEquals(message, arguments[0], invocation.mArguments[0]);
            assertEquals(message, arguments[1], invocation.mArguments[1]);
        }
    }
}
