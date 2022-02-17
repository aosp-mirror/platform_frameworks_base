/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.window;

import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG;
import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
import static android.view.WindowManager.LayoutParams.TYPE_INPUT_METHOD;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import android.app.Activity;
import android.app.EmptyActivity;
import android.app.Instrumentation;
import android.app.WindowConfiguration;
import android.content.ComponentCallbacks;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.IBinder;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.IWindowManager;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams.WindowType;
import android.view.WindowManagerGlobal;
import android.view.WindowManagerImpl;

import androidx.annotation.NonNull;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link WindowContext}
 *
 * <p>Build/Install/Run:
 *  atest FrameworksCoreTests:WindowContextTest
 *
 * <p>This test class is a part of Window Manager Service tests and specified in
 * {@link com.android.server.wm.test.filters.FrameworksTestsFilter}.
 */
@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class WindowContextTest {
    @Rule
    public ActivityTestRule<EmptyActivity> mActivityRule =
            new ActivityTestRule<>(EmptyActivity.class, false /* initialTouchMode */,
                    false /* launchActivity */);

    private final Instrumentation mInstrumentation = InstrumentationRegistry.getInstrumentation();
    private final WindowContext mWindowContext = createWindowContext();
    private final IWindowManager mWms = WindowManagerGlobal.getWindowManagerService();

    private static final int TIMEOUT_IN_SECONDS = 4;

    @Test
    public void testCreateWindowContextWindowManagerAttachClientToken() {
        final WindowManager windowContextWm = WindowManagerImpl
                .createWindowContextWindowManager(mWindowContext);
        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        mInstrumentation.runOnMainSync(() -> {
            final View view = new View(mWindowContext);
            windowContextWm.addView(view, params);
        });

        assertEquals(mWindowContext.getWindowContextToken(), params.mWindowContextToken);
    }

    /**
     * Test the {@link WindowContext} life cycle behavior to add a new window token:
     * <ul>
     *  <li>The window token is created before adding the first view.</li>
     *  <li>The window token is registered after adding the first view.</li>
     *  <li>The window token is removed after {@link WindowContext}'s release.</li>
     * </ul>
     */
    @Test
    public void testCreateWindowContextNewTokenFromClient() throws Throwable {
        final IBinder token = mWindowContext.getWindowContextToken();

        // Test that the window token is not created yet.
        assertFalse("Token must not be registered until adding the first window",
                mWms.isWindowToken(token));

        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(TYPE_APPLICATION_OVERLAY);
        final View testView = new View(mWindowContext);

        final CountDownLatch latch = new CountDownLatch(1);
        testView.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override
            public void onViewAttachedToWindow(View v) {
                latch.countDown();
            }

            @Override
            public void onViewDetachedFromWindow(View v) {}
        });

        mInstrumentation.runOnMainSync(() -> {
            mWindowContext.getSystemService(WindowManager.class).addView(testView, params);

            assertEquals(token, params.mWindowContextToken);
        });


        assertTrue(latch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS));


        // Verify that the window token of the window context is created after first addView().
        assertTrue("Token must exist after adding the first view.",
                mWms.isWindowToken(token));

        mWindowContext.release();

        // After the window context's release, the window token is also removed.
        assertFalse("Token must be removed after release.", mWms.isWindowToken(token));
    }

    /**
     * Verifies the behavior when window context attaches an {@link Activity} by override
     * {@link WindowManager.LayoutParams#token}.
     *
     * The window context token should be overridden to
     * {@link android.view.WindowManager.LayoutParams} and the {@link Activity}'s token must not be
     * removed regardless of release of window context.
     */
    @Test
    public void testCreateWindowContext_AttachActivity_TokenNotRemovedAfterRelease()
            throws Throwable {
        mActivityRule.launchActivity(new Intent());
        final Activity activity = mActivityRule.getActivity();
        final WindowManager.LayoutParams params = activity.getWindow().getAttributes();

        final WindowContext windowContext = createWindowContext(params.type);
        final IBinder token = windowContext.getWindowContextToken();

        final View testView = new View(windowContext);

        mInstrumentation.runOnMainSync(() -> {
            windowContext.getSystemService(WindowManager.class).addView(testView, params);

            assertEquals(token, params.mWindowContextToken);
        });
        windowContext.release();

        // Even if the window context is released, the activity should still exist.
        assertTrue("Token must exist even if the window context is released.",
                mWms.isWindowToken(activity.getActivityToken()));
    }

    /**
     * Verifies the behavior when window context attaches an existing token by override
     * {@link WindowManager.LayoutParams#token}.
     *
     * The window context token should be overridden to
     * {@link android.view.WindowManager.LayoutParams} and the {@link Activity}'s token must not be
     * removed regardless of release of window context.
     */
    @Test
    public void testCreateWindowContext_AttachWindowToken_TokenNotRemovedAfterRelease()
            throws Throwable {
        final WindowContext windowContext = createWindowContext(TYPE_INPUT_METHOD);
        final IBinder token = windowContext.getWindowContextToken();

        final IBinder existingToken = new Binder();
        mWms.addWindowToken(existingToken, TYPE_INPUT_METHOD, windowContext.getDisplayId(),
                null /* options */);

        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(TYPE_INPUT_METHOD);
        params.token = existingToken;
        final View testView = new View(windowContext);

        mInstrumentation.runOnMainSync(() -> {
            windowContext.getSystemService(WindowManager.class).addView(testView, params);

            assertEquals(token, params.mWindowContextToken);
        });
        windowContext.release();

        // Even if the window context is released, the existing token should still exist.
        assertTrue("Token must exist even if the window context is released.",
                mWms.isWindowToken(existingToken));

        mWms.removeWindowToken(existingToken, DEFAULT_DISPLAY);
    }

    @Test
    public void testWindowContextAddViewWithSubWindowType_NotCrash() throws Throwable {
        final WindowContext windowContext = createWindowContext(TYPE_INPUT_METHOD);
        final WindowManager wm = windowContext.getSystemService(WindowManager.class);

        // Create a WindowToken with system window type.
        final IBinder existingToken = new Binder();
        mWms.addWindowToken(existingToken, TYPE_INPUT_METHOD, windowContext.getDisplayId(),
                null /* options */);

        final WindowManager.LayoutParams params =
                new WindowManager.LayoutParams(TYPE_INPUT_METHOD);
        params.token = existingToken;
        final View parentWindow = new View(windowContext);

        final AttachStateListener listener = new AttachStateListener();
        parentWindow.addOnAttachStateChangeListener(listener);

        // Add the parent window
        mInstrumentation.runOnMainSync(() -> wm.addView(parentWindow, params));

        assertTrue(listener.mLatch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS));

        final WindowManager.LayoutParams subWindowAttrs =
                new WindowManager.LayoutParams(TYPE_APPLICATION_ATTACHED_DIALOG);
        subWindowAttrs.token = parentWindow.getWindowToken();
        final View subWindow = new View(windowContext);

        // Add a window with sub-window type.
        mInstrumentation.runOnMainSync(() -> wm.addView(subWindow, subWindowAttrs));
    }

    @Test
    public void testGetCustomDrawable() {
        assertNotNull(mWindowContext.getResources().getDrawable(R.drawable.custom_drawable,
                null /* theme */));
    }

    @Test
    public void testRegisterComponentCallbacks() {
        final WindowContext windowContext = createWindowContext();
        final ConfigurationListener listener = new ConfigurationListener();

        windowContext.registerComponentCallbacks(listener);

        try {
            final Configuration config = new Configuration();
            config.windowConfiguration.setWindowingMode(
                    WindowConfiguration.WINDOWING_MODE_FREEFORM);
            config.windowConfiguration.setBounds(new Rect(0, 0, 100, 100));

            windowContext.dispatchConfigurationChanged(config);

            try {
                assertWithMessage("Waiting for onConfigurationChanged timeout.")
                        .that(listener.mLatch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                fail("Waiting for configuration changed failed because of " + e);
            }

            assertThat(listener.mConfiguration).isEqualTo(config);
        } finally {
            windowContext.unregisterComponentCallbacks(listener);
        }
    }

    @Test
    public void testRegisterComponentCallbacksOnWindowContextWrapper() {
        final WindowContext windowContext = createWindowContext();
        final Context wrapper = new ContextWrapper(windowContext);
        final ConfigurationListener listener = new ConfigurationListener();

        wrapper.registerComponentCallbacks(listener);

        try {
            final Configuration config = new Configuration();
            config.windowConfiguration.setWindowingMode(
                    WindowConfiguration.WINDOWING_MODE_FREEFORM);
            config.windowConfiguration.setBounds(new Rect(0, 0, 100, 100));

            windowContext.dispatchConfigurationChanged(config);

            try {
                assertWithMessage("Waiting for onConfigurationChanged timeout.")
                        .that(listener.mLatch.await(TIMEOUT_IN_SECONDS, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                fail("Waiting for configuration changed failed because of " + e);
            }

            assertThat(listener.mConfiguration).isEqualTo(config);
        } finally {
            wrapper.unregisterComponentCallbacks(listener);
        }
    }

    private WindowContext createWindowContext() {
        return createWindowContext(TYPE_APPLICATION_OVERLAY);
    }

    private WindowContext createWindowContext(@WindowType int type) {
        final Context instContext = mInstrumentation.getTargetContext();
        final Display display = instContext.getSystemService(DisplayManager.class)
                .getDisplay(DEFAULT_DISPLAY);
        return (WindowContext) instContext.createWindowContext(display, type,  null /* options */);
    }

    private static class ConfigurationListener implements ComponentCallbacks {
        private Configuration mConfiguration;
        private CountDownLatch mLatch = new CountDownLatch(1);

        @Override
        public void onConfigurationChanged(@NonNull Configuration newConfig) {
            mConfiguration = newConfig;
            mLatch.countDown();
        }

        @Override
        public void onLowMemory() {}
    }

    private static class AttachStateListener implements View.OnAttachStateChangeListener {
        final CountDownLatch mLatch = new CountDownLatch(1);

        @Override
        public void onViewAttachedToWindow(View v) {
            mLatch.countDown();
        }

        @Override
        public void onViewDetachedFromWindow(View v) {}
    }
}
