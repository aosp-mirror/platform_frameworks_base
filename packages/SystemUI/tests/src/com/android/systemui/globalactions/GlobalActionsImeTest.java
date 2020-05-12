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

package com.android.systemui.globalactions;

import static android.view.WindowInsets.Type.ime;

import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.EditText;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.filters.LargeTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;

import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

@LargeTest
public class GlobalActionsImeTest {

    @Rule
    public ActivityTestRule<TestActivity> mActivityTestRule = new ActivityTestRule<>(
            TestActivity.class, false, false);

    /**
     * This test verifies that GlobalActions, which is frequently used to capture bugreports,
     * doesn't interfere with the IME, i.e. soft-keyboard state.
     */
    @Test
    public void testGlobalActions_doesntStealImeControl() {
        final TestActivity activity = mActivityTestRule.launchActivity(null);

        activity.waitFor(() -> activity.mHasFocus && activity.mControlsIme && activity.mImeVisible);

        InstrumentationRegistry.getInstrumentation().getUiAutomation().executeShellCommand(
                "input keyevent --longpress POWER"
        );

        activity.waitFor(() -> !activity.mHasFocus);
        // Give the dialog time to animate in, and steal IME focus. Unfortunately, there's currently
        // no better way to wait for this.
        SystemClock.sleep(TimeUnit.SECONDS.toMillis(2));

        runAssertionOnMainThread(() -> {
            assertTrue("IME should remain visible behind GlobalActions, but didn't",
                    activity.mControlsIme);
            assertTrue("App behind GlobalActions should remain in control of IME, but didn't",
                    activity.mImeVisible);
        });
    }

    /** Like Instrumentation.runOnMainThread(), but forwards AssertionErrors to the caller. */
    private static void runAssertionOnMainThread(Runnable r) {
        AssertionError[] t = new AssertionError[1];
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            try {
                r.run();
            } catch (AssertionError e) {
                t[0] = e;
                // Ignore assertion - throwing it here would crash the main thread.
            }
        });
        if (t[0] != null) {
            throw t[0];
        }
    }

    public static class TestActivity extends Activity implements
            WindowInsetsController.OnControllableInsetsChangedListener,
            View.OnApplyWindowInsetsListener {

        private EditText mContent;
        boolean mHasFocus;
        boolean mControlsIme;
        boolean mImeVisible;

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            mContent = new EditText(this);
            mContent.setCursorVisible(false);  // Otherwise, main thread doesn't go idle.
            setContentView(mContent);
            mContent.requestFocus();

            getWindow().getDecorView().setOnApplyWindowInsetsListener(this);
            WindowInsetsController wic = mContent.getWindowInsetsController();
            wic.addOnControllableInsetsChangedListener(this);
            wic.show(ime());
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            synchronized (this) {
                mHasFocus = hasFocus;
                notifyAll();
            }
        }

        @Override
        public void onControllableInsetsChanged(@NonNull WindowInsetsController controller,
                int typeMask) {
            synchronized (this) {
                mControlsIme = (typeMask & ime()) != 0;
                notifyAll();
            }
        }

        void waitFor(BooleanSupplier condition) {
            synchronized (this) {
                while (!condition.getAsBoolean()) {
                    try {
                        wait(TimeUnit.SECONDS.toMillis(5));
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                }
            }
        }

        @Override
        public WindowInsets onApplyWindowInsets(View v, WindowInsets insets) {
            mImeVisible = insets.isVisible(ime());
            return v.onApplyWindowInsets(insets);
        }
    }
}
