/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static junit.framework.Assert.assertFalse;
import static junit.framework.Assert.assertTrue;

import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.Service;
import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.SystemClock;
import android.support.test.InstrumentationRegistry;
import android.support.test.rule.ActivityTestRule;
import android.support.test.runner.AndroidJUnit4;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityTestActivity;
import android.view.accessibility.AccessibilityWindowInfo;

import com.android.compatibility.common.util.TestUtils;
import com.android.frameworks.coretests.R;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeoutException;

@RunWith(AndroidJUnit4.class)
public class AccessibilityInteractionControllerTest {
    static final long TIMEOUT_DEFAULT = 10000; // 10 seconds

    private static Instrumentation sInstrumentation;
    private static UiAutomation sUiAutomation;

    @Rule
    public ActivityTestRule<AccessibilityTestActivity> mActivityRule = new ActivityTestRule<>(
            AccessibilityTestActivity.class, false, false);

    private AccessibilityInteractionController mAccessibilityInteractionController;
    private ViewRootImpl mViewRootImpl;
    private View mButton;

    @BeforeClass
    public static void oneTimeSetup() {
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
        sUiAutomation = sInstrumentation.getUiAutomation();
    }

    @AfterClass
    public static void postTestTearDown() {
        sUiAutomation.destroy();
    }

    @Before
    public void setUp() throws Throwable {
        launchActivity();
        enableTouchExploration(true);
        mActivityRule.runOnUiThread(() -> {
            mViewRootImpl = mActivityRule.getActivity().getWindow().getDecorView()
                    .getViewRootImpl();
            mButton = mActivityRule.getActivity().findViewById(R.id.appNameBtn);
        });
        mAccessibilityInteractionController =
                mViewRootImpl.getAccessibilityInteractionController();
    }

    @After
    public void tearDown() {
        enableTouchExploration(false);
    }

    @Test
    public void clearAccessibilityFocus_shouldClearFocus() throws Exception {
        performAccessibilityFocus("com.android.frameworks.coretests:id/appNameBtn");
        assertTrue("Button should have a11y focus",
                mButton.isAccessibilityFocused());
        mAccessibilityInteractionController.clearAccessibilityFocusClientThread();
        sInstrumentation.waitForIdleSync();
        assertFalse("Button should not have a11y focus",
                mButton.isAccessibilityFocused());
    }

    @Test
    public void clearAccessibilityFocus_uiThread_shouldClearFocus() throws Exception {
        performAccessibilityFocus("com.android.frameworks.coretests:id/appNameBtn");
        assertTrue("Button should have a11y focus",
                mButton.isAccessibilityFocused());
        sInstrumentation.runOnMainSync(() -> {
            mAccessibilityInteractionController.clearAccessibilityFocusClientThread();
        });
        assertFalse("Button should not have a11y focus",
                mButton.isAccessibilityFocused());
    }

    private void launchActivity() {
        final Object waitObject = new Object();
        final int[] location = new int[2];
        final StringBuilder activityPackage = new StringBuilder();
        final Rect bounds = new Rect();
        final StringBuilder activityTitle = new StringBuilder();
        try {
            final long executionStartTimeMillis = SystemClock.uptimeMillis();
            sUiAutomation.setOnAccessibilityEventListener((event) -> {
                if (event.getEventTime() < executionStartTimeMillis) {
                    return;
                }
                synchronized (waitObject) {
                    waitObject.notifyAll();
                }
            });
            enableRetrieveAccessibilityWindows();

            final Activity activity = mActivityRule.launchActivity(null);
            sInstrumentation.runOnMainSync(() -> {
                activity.getWindow().getDecorView().getLocationOnScreen(location);
                activityPackage.append(activity.getPackageName());
                activityTitle.append(activity.getTitle());
            });
            sInstrumentation.waitForIdleSync();

            TestUtils.waitOn(waitObject, () -> {
                final AccessibilityWindowInfo window = findWindowByTitle(activityTitle);
                if (window == null) return false;
                window.getBoundsInScreen(bounds);
                activity.getWindow().getDecorView().getLocationOnScreen(location);
                if (bounds.isEmpty()) {
                    return false;
                }
                return (!bounds.isEmpty())
                        && (bounds.left == location[0]) && (bounds.top == location[1]);
            }, TIMEOUT_DEFAULT, "Launch Activity");
        } finally {
            sUiAutomation.setOnAccessibilityEventListener(null);
        }
    }

    private void enableRetrieveAccessibilityWindows() {
        AccessibilityServiceInfo info = sUiAutomation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        sUiAutomation.setServiceInfo(info);
    }

    private void enableTouchExploration(boolean enabled) {
        final Object waitObject = new Object();
        final AccessibilityManager accessibilityManager =
                (AccessibilityManager) sInstrumentation.getContext().getSystemService(
                        Service.ACCESSIBILITY_SERVICE);
        final AccessibilityManager.TouchExplorationStateChangeListener listener = status -> {
            synchronized (waitObject) {
                waitObject.notifyAll();
            }
        };
        try {
            accessibilityManager.addTouchExplorationStateChangeListener(listener);
            final AccessibilityServiceInfo info = sUiAutomation.getServiceInfo();
            if (enabled) {
                info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            } else {
                info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
            }
            sUiAutomation.setServiceInfo(info);
            TestUtils.waitOn(waitObject,
                    () -> accessibilityManager.isTouchExplorationEnabled() == enabled,
                    TIMEOUT_DEFAULT,
                    (enabled ? "Enable" : "Disable") + "touch exploration");
        } finally {
            accessibilityManager.removeTouchExplorationStateChangeListener(listener);
        }
    }

    private void performAccessibilityFocus(String viewId) throws TimeoutException {
        final AccessibilityNodeInfo node = sUiAutomation.getRootInActiveWindow()
                .findAccessibilityNodeInfosByViewId(viewId).get(0);
        // Perform an action and wait for an event
        sUiAutomation.executeAndWaitForEvent(
                () -> node.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS),
                event -> event.getEventType() == AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED,
                TIMEOUT_DEFAULT);
        node.refresh();
    }

    private AccessibilityWindowInfo findWindowByTitle(CharSequence title) {
        final List<AccessibilityWindowInfo> windows = sUiAutomation.getWindows();
        AccessibilityWindowInfo returnValue = null;
        for (int i = 0; i < windows.size(); i++) {
            final AccessibilityWindowInfo window = windows.get(i);
            if (TextUtils.equals(title, window.getTitle())) {
                returnValue = window;
            } else {
                window.recycle();
            }
        }
        return returnValue;
    }
}
