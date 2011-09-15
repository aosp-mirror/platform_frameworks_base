/**
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.accessibilityservice;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLEAR_SELECTION;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_SELECT;

import com.android.frameworks.coretests.R;

import android.content.Context;
import android.graphics.Rect;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.test.ActivityInstrumentationTestCase2;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityInteractionClient;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.IAccessibilityManager;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

/**
 * Activity for testing the accessibility APIs for "interrogation" of
 * the screen content. These APIs allow exploring the screen and
 * requesting an action to be performed on a given view from an
 * AccessiiblityService.
 */
public class InterrogationActivityTest
        extends ActivityInstrumentationTestCase2<InterrogationActivity> {
    private static final boolean DEBUG = true;

    private static String LOG_TAG = "InterrogationActivityTest";

    // Timeout before give up wait for the system to process an accessibility setting change.
    private static final int TIMEOUT_PROPAGATE_ACCESSIBLITY_SETTING = 2000;

    // Helpers to figure out the first and last test methods
    // This is a workaround for the lack of such support in JUnit3
    private static int sTestMethodCount;
    private static int sExecutedTestMethodCount;

    // Handle to a connection to the AccessibilityManagerService
    private static IAccessibilityServiceConnection sConnection;

    // The last received accessibility event
    private static volatile AccessibilityEvent sLastFocusAccessibilityEvent;

    public InterrogationActivityTest() {
        super(InterrogationActivity.class);
        sTestMethodCount = getTestMethodCount();
    }

    @LargeTest
    public void testFindAccessibilityNodeInfoByViewId() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            AccessibilityNodeInfo button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertNotNull(button);
            assertEquals(0, button.getChildCount());

            // bounds
            Rect bounds = new Rect();
            button.getBoundsInParent(bounds);
            assertEquals(0, bounds.left);
            assertEquals(0, bounds.top);
            assertEquals(160, bounds.right);
            assertEquals(100, bounds.bottom);

            // char sequence attributes
            assertEquals("com.android.frameworks.coretests", button.getPackageName());
            assertEquals("android.widget.Button", button.getClassName());
            assertEquals("Button5", button.getText());
            assertNull(button.getContentDescription());

            // boolean attributes
            assertTrue(button.isFocusable());
            assertTrue(button.isClickable());
            assertTrue(button.isEnabled());
            assertFalse(button.isFocused());
            assertTrue(button.isClickable());
            assertFalse(button.isPassword());
            assertFalse(button.isSelected());
            assertFalse(button.isCheckable());
            assertFalse(button.isChecked());

            // actions
            assertEquals(ACTION_FOCUS | ACTION_SELECT | ACTION_CLEAR_SELECTION,
                button.getActions());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testFindAccessibilityNodeInfoByViewId: "
                        + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testFindAccessibilityNodeInfoByViewText() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view by text
            List<AccessibilityNodeInfo> buttons =  AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfosByViewTextInActiveWindow(getConnection(), "butto");
            assertEquals(9, buttons.size());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testFindAccessibilityNodeInfoByViewText: "
                        + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testFindAccessibilityNodeInfoByViewTextContentDescription() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view by text
            List<AccessibilityNodeInfo> buttons =  AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfosByViewTextInActiveWindow(getConnection(),
                        "contentDescription");
            assertEquals(1, buttons.size());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testFindAccessibilityNodeInfoByViewTextContentDescription: "
                        + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testTraverseAllViews() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // make list of expected nodes
            List<String> classNameAndTextList = new ArrayList<String>();
            classNameAndTextList.add("android.widget.LinearLayout");
            classNameAndTextList.add("android.widget.LinearLayout");
            classNameAndTextList.add("android.widget.LinearLayout");
            classNameAndTextList.add("android.widget.LinearLayout");
            classNameAndTextList.add("android.widget.ButtonButton1");
            classNameAndTextList.add("android.widget.ButtonButton2");
            classNameAndTextList.add("android.widget.ButtonButton3");
            classNameAndTextList.add("android.widget.ButtonButton4");
            classNameAndTextList.add("android.widget.ButtonButton5");
            classNameAndTextList.add("android.widget.ButtonButton6");
            classNameAndTextList.add("android.widget.ButtonButton7");
            classNameAndTextList.add("android.widget.ButtonButton8");
            classNameAndTextList.add("android.widget.ButtonButton9");

            AccessibilityNodeInfo root = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.root);
            assertNotNull("We must find the existing root.", root);

            Queue<AccessibilityNodeInfo> fringe = new LinkedList<AccessibilityNodeInfo>();
            fringe.add(root);

            // do a BFS traversal and check nodes
            while (!fringe.isEmpty()) {
                AccessibilityNodeInfo current = fringe.poll();

                CharSequence className = current.getClassName();
                CharSequence text = current.getText();
                String receivedClassNameAndText = className.toString()
                   + ((text != null) ? text.toString() : "");
                String expectedClassNameAndText = classNameAndTextList.remove(0);

                assertEquals("Did not get the expected node info",
                        expectedClassNameAndText, receivedClassNameAndText);

                final int childCount = current.getChildCount();
                for (int i = 0; i < childCount; i++) {
                    AccessibilityNodeInfo child = current.getChild(i);
                    fringe.add(child);
                }
            }
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testTraverseAllViews: " + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testPerformAccessibilityActionFocus() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view and make sure it is not focused
            AccessibilityNodeInfo button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertFalse(button.isFocused());

            // focus the view
            assertTrue(button.performAction(ACTION_FOCUS));

            // find the view again and make sure it is focused
            button =  AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertTrue(button.isFocused());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testPerformAccessibilityActionFocus: " + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testPerformAccessibilityActionClearFocus() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view and make sure it is not focused
            AccessibilityNodeInfo button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertFalse(button.isFocused());

            // focus the view
            assertTrue(button.performAction(ACTION_FOCUS));

            // find the view again and make sure it is focused
            button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertTrue(button.isFocused());

            // unfocus the view
            assertTrue(button.performAction(ACTION_CLEAR_FOCUS));

            // find the view again and make sure it is not focused
            button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertFalse(button.isFocused());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testPerformAccessibilityActionClearFocus: "
                        + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testPerformAccessibilityActionSelect() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view and make sure it is not selected
            AccessibilityNodeInfo button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertFalse(button.isSelected());

            // select the view
            assertTrue(button.performAction(ACTION_SELECT));

            // find the view again and make sure it is selected
            button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertTrue(button.isSelected());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testPerformAccessibilityActionSelect: " + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testPerformAccessibilityActionClearSelection() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view and make sure it is not selected
            AccessibilityNodeInfo button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertFalse(button.isSelected());

            // select the view
            assertTrue(button.performAction(ACTION_SELECT));

            // find the view again and make sure it is selected
            button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertTrue(button.isSelected());

            // unselect the view
            assertTrue(button.performAction(ACTION_CLEAR_SELECTION));

            // find the view again and make sure it is not selected
            button =  AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertFalse(button.isSelected());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testPerformAccessibilityActionClearSelection: "
                        + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testAccessibilityEventGetSource() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view and make sure it is not focused
            AccessibilityNodeInfo button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            assertFalse(button.isSelected());

            // focus the view
            assertTrue(button.performAction(ACTION_FOCUS));

            synchronized (sConnection) {
                try {
                    sConnection.wait(500);
                } catch (InterruptedException ie) {
                    /* ignore */
                }
            }

            // check that last event source
            AccessibilityNodeInfo source = sLastFocusAccessibilityEvent.getSource();
            assertNotNull(source);

            // bounds
            Rect buttonBounds = new Rect();
            button.getBoundsInParent(buttonBounds);
            Rect sourceBounds = new Rect();
            source.getBoundsInParent(sourceBounds);

            assertEquals(buttonBounds.left, sourceBounds.left);
            assertEquals(buttonBounds.right, sourceBounds.right);
            assertEquals(buttonBounds.top, sourceBounds.top);
            assertEquals(buttonBounds.bottom, sourceBounds.bottom);

            // char sequence attributes
            assertEquals(button.getPackageName(), source.getPackageName());
            assertEquals(button.getClassName(), source.getClassName());
            assertEquals(button.getText(), source.getText());
            assertSame(button.getContentDescription(), source.getContentDescription());

            // boolean attributes
            assertSame(button.isFocusable(), source.isFocusable());
            assertSame(button.isClickable(), source.isClickable());
            assertSame(button.isEnabled(), source.isEnabled());
            assertNotSame(button.isFocused(), source.isFocused());
            assertSame(button.isLongClickable(), source.isLongClickable());
            assertSame(button.isPassword(), source.isPassword());
            assertSame(button.isSelected(), source.isSelected());
            assertSame(button.isCheckable(), source.isCheckable());
            assertSame(button.isChecked(), source.isChecked());
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testAccessibilityEventGetSource: " + elapsedTimeMillis + "ms");
            }
        }
    }

    @LargeTest
    public void testObjectContract() throws Exception {
        beforeClassIfNeeded();
        final long startTimeMillis = SystemClock.uptimeMillis();
        try {
            // bring up the activity
            getActivity();

            // find a view and make sure it is not focused
            AccessibilityNodeInfo button = AccessibilityInteractionClient.getInstance()
                .findAccessibilityNodeInfoByViewIdInActiveWindow(getConnection(), R.id.button5);
            AccessibilityNodeInfo parent = button.getParent();
            final int childCount = parent.getChildCount();
            for (int i = 0; i < childCount; i++) {
                AccessibilityNodeInfo child = parent.getChild(i);
                assertNotNull(child);
                if (child.equals(button)) {
                    assertEquals("Equal objects must have same hasCode.", button.hashCode(),
                            child.hashCode());
                    return;
                }
            }
            fail("Parent's children do not have the info whose parent is the parent.");
        } finally {
            afterClassIfNeeded();
            if (DEBUG) {
                final long elapsedTimeMillis = SystemClock.uptimeMillis() - startTimeMillis;
                Log.i(LOG_TAG, "testObjectContract: " + elapsedTimeMillis + "ms");
            }
        }
    }

    @Override
    protected void scrubClass(Class<?> testCaseClass) {
        /* intentionally do not scrub */
    }

    /**
     * Sets accessibility in a given state by writing the state to the
     * settings and waiting until the accessibility manager service picks
     * it up for max {@link #TIMEOUT_PROPAGATE_ACCESSIBLITY_SETTING}.
     *
     * @param state The accessibility state.
     * @throws Exception If any error occurs.
     */
    private void ensureAccessibilityState(boolean state) throws Exception {
        Context context = getInstrumentation().getContext();
        // If the local manager ready => nothing to do.
        AccessibilityManager accessibilityManager = AccessibilityManager.getInstance(context);
        if (accessibilityManager.isEnabled() == state) {
            return;
        }
        synchronized (this) {
            // Check if the system already knows about the desired state. 
            final boolean currentState = Settings.Secure.getInt(context.getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED) == 1;
            if (currentState != state) {
                // Make sure we wake ourselves as the desired state is propagated.
                accessibilityManager.addAccessibilityStateChangeListener(
                        new AccessibilityManager.AccessibilityStateChangeListener() {
                            public void onAccessibilityStateChanged(boolean enabled) {
                                synchronized (this) {
                                    notifyAll();
                                }
                            }
                        });
                Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, state ? 1 : 0);
            }
            // No while one attempt and that is it.
            try {
                wait(TIMEOUT_PROPAGATE_ACCESSIBLITY_SETTING);
            } catch (InterruptedException ie) {
                /* ignore */
            }
        }
        if (accessibilityManager.isEnabled() != state) {
            throw new IllegalStateException("Could not set accessibility state to: " + state);
        }
    }

    /**
     * Execute some set up code before any test method.
     *
     * NOTE: I miss Junit4's @BeforeClass
     *
     * @throws Exception If an error occurs.
     */
    private void beforeClassIfNeeded() throws Exception {
        sExecutedTestMethodCount++;
        if (sExecutedTestMethodCount == 1) {
            ensureAccessibilityState(true);
        }
    }

    /**
     * Execute some clean up code after all test methods.
     *
     * NOTE: I miss Junit4's @AfterClass
     *
     * @throws Exception If an error occurs.
     */
    public void afterClassIfNeeded() throws Exception {
        if (sExecutedTestMethodCount == sTestMethodCount) {
            sExecutedTestMethodCount = 0;
            ensureAccessibilityState(false);
        }
    }

    private static IAccessibilityServiceConnection getConnection() throws Exception {
        if (sConnection == null) {
            IEventListener listener = new IEventListener.Stub() {
                public void setConnection(IAccessibilityServiceConnection connection)
                        throws RemoteException {
                    AccessibilityServiceInfo info = new AccessibilityServiceInfo();
                    info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
                    info.feedbackType = AccessibilityServiceInfo.FEEDBACK_SPOKEN;
                    info.notificationTimeout = 0;
                    info.flags = AccessibilityServiceInfo.DEFAULT;
                    connection.setServiceInfo(info);
                }

                public void onInterrupt() {}

                public void onAccessibilityEvent(AccessibilityEvent event) {
                    if (event.getEventType() == AccessibilityEvent.TYPE_VIEW_FOCUSED) {
                        sLastFocusAccessibilityEvent = AccessibilityEvent.obtain(event);
                    }
                    synchronized (sConnection) {
                        sConnection.notifyAll();
                    }
                }
            };
            IAccessibilityManager manager = IAccessibilityManager.Stub.asInterface(
                ServiceManager.getService(Context.ACCESSIBILITY_SERVICE));
            sConnection = manager.registerEventListener(listener);
        }
        return sConnection;
    }

    /**
     * @return The number of test methods.
     */
    private int getTestMethodCount() {
        int testMethodCount = 0;
        for (Method method : getClass().getMethods()) {
            final int modifiers = method.getModifiers();
            if (method.getName().startsWith("test")
                    && (modifiers & Modifier.PUBLIC) != 0
                    && (modifiers & Modifier.STATIC) == 0) {
                testMethodCount++;
            }
        }
        return testMethodCount;
    }
}
