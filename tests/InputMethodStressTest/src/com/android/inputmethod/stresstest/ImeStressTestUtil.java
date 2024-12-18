/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.inputmethod.stresstest;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP;

import static com.android.compatibility.common.util.SystemUtil.eventually;

import static com.google.common.truth.Truth.assertWithMessage;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsAnimation;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ThrowingRunnable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Utility methods for IME stress test. */
public final class ImeStressTestUtil {

    private static final long TIMEOUT = TimeUnit.SECONDS.toMillis(3);

    private ImeStressTestUtil() {}

    private static final int[] WINDOW_FOCUS_FLAGS =
            new int[] {
                LayoutParams.FLAG_NOT_FOCUSABLE,
                LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                LayoutParams.FLAG_NOT_FOCUSABLE | LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                LayoutParams.FLAG_LOCAL_FOCUS_MODE
            };

    private static final int[] SOFT_INPUT_VISIBILITY_FLAGS =
            new int[] {
                LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED,
                LayoutParams.SOFT_INPUT_STATE_UNCHANGED,
                LayoutParams.SOFT_INPUT_STATE_HIDDEN,
                LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN,
                LayoutParams.SOFT_INPUT_STATE_VISIBLE,
                LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE,
            };

    private static final int[] SOFT_INPUT_ADJUST_FLAGS =
            new int[] {
                LayoutParams.SOFT_INPUT_ADJUST_UNSPECIFIED,
                LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                LayoutParams.SOFT_INPUT_ADJUST_PAN,
                LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            };

    public static final String SOFT_INPUT_FLAGS = "soft_input_flags";
    public static final String WINDOW_FLAGS = "window_flags";
    public static final String UNFOCUSABLE_VIEW = "unfocusable_view";
    public static final String REQUEST_FOCUS_ON_CREATE = "request_focus_on_create";
    public static final String INPUT_METHOD_MANAGER_SHOW_ON_CREATE =
            "input_method_manager_show_on_create";
    public static final String INPUT_METHOD_MANAGER_HIDE_ON_CREATE =
            "input_method_manager_hide_on_create";
    public static final String WINDOW_INSETS_CONTROLLER_SHOW_ON_CREATE =
            "window_insets_controller_show_on_create";
    public static final String WINDOW_INSETS_CONTROLLER_HIDE_ON_CREATE =
            "window_insets_controller_hide_on_create";

    /** Parameters for show/hide ime parameterized tests. */
    public static ArrayList<Object[]> getWindowAndSoftInputFlagParameters() {
        ArrayList<Object[]> params = new ArrayList<>();

        // Set different window focus flags and keep soft input flags as default values (4 cases)
        for (int windowFocusFlags : WINDOW_FOCUS_FLAGS) {
            params.add(
                    new Object[] {
                        windowFocusFlags,
                        LayoutParams.SOFT_INPUT_STATE_UNSPECIFIED,
                        LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                    });
        }
        // Set the combinations of different softInputVisibility, softInputAdjustment flags,
        // keep the window focus flag as default value ( 6 * 4 = 24 cases)
        for (int softInputVisibility : SOFT_INPUT_VISIBILITY_FLAGS) {
            for (int softInputAdjust : SOFT_INPUT_ADJUST_FLAGS) {
                params.add(
                        new Object[] {
                            0x0 /* No window focus flags */, softInputVisibility, softInputAdjust
                        });
            }
        }
        return params;
    }

    /** Checks if the IME is shown on the window that the given view belongs to. */
    public static boolean isImeShown(View view) {
        WindowInsets insets = view.getRootWindowInsets();
        if (insets == null) {
            return false;
        }
        return insets.isVisible(WindowInsets.Type.ime());
    }

    /** Calls the callable on the main thread and returns the result. */
    public static <V> V callOnMainSync(Callable<V> callable) {
        AtomicReference<V> result = new AtomicReference<>();
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(
                        () -> {
                            try {
                                result.set(callable.call());
                            } catch (Exception e) {
                                throw new RuntimeException("Exception was thrown", e);
                            }
                        });
        return result.get();
    }

    /**
     * Requests EditText view focus on the main thread, and assert this returns {@code true}.
     */
    public static void requestFocusAndVerify(TestActivity activity) {
        boolean result = callOnMainSync(activity::requestFocus);
        assertWithMessage("View focus request should have succeeded").that(result).isTrue();
    }

    /**
     * Waits until {@code pred} returns true, or throws on timeout.
     *
     * <p>The given {@code pred} will be called on the main thread.
     */
    public static void waitOnMainUntil(String message, Callable<Boolean> pred) {
        eventually(() -> assertWithMessage(message).that(callOnMainSync(pred)).isTrue(), TIMEOUT);
    }

    /** Waits until IME is shown, or throws on timeout. */
    public static void waitOnMainUntilImeIsShown(View view) {
        eventually(
                () ->
                        assertWithMessage("IME should have been shown")
                                .that(callOnMainSync(() -> isImeShown(view)))
                                .isTrue(),
                TIMEOUT);
    }

    /** Waits until IME is hidden, or throws on timeout. */
    public static void waitOnMainUntilImeIsHidden(View view) {
        eventually(
                () ->
                        assertWithMessage("IME should have been hidden")
                                .that(callOnMainSync(() -> isImeShown(view)))
                                .isFalse(),
                TIMEOUT);
    }

    /** Waits until window gains focus, or throws on timeout. */
    public static void waitOnMainUntilWindowGainsFocus(View view) {
        eventually(
                () ->
                        assertWithMessage(
                                "Window should have gained focus; value of hasWindowFocus:")
                                .that(callOnMainSync(view::hasWindowFocus))
                                .isTrue(),
                TIMEOUT);
    }

    /** Waits until view gains focus, or throws on timeout. */
    public static void waitOnMainUntilViewGainsFocus(View view) {
        eventually(
                () ->
                        assertWithMessage("View should have gained focus; value of hasFocus:")
                                .that(callOnMainSync(view::hasFocus))
                                .isTrue(),
                TIMEOUT);
    }

    /** Verify IME is always hidden within the given time duration. */
    public static void verifyImeIsAlwaysHidden(View view) {
        always(
                () ->
                        assertWithMessage("IME should have been hidden")
                                .that(callOnMainSync(() -> isImeShown(view)))
                                .isFalse(),
                TIMEOUT);
    }

    /** Verify the window never gains focus within the given time duration. */
    public static void verifyWindowNeverGainsFocus(View view) {
        always(
                () ->
                        assertWithMessage(
                                "Window should not have gained focus; value of hasWindowFocus:")
                                .that(callOnMainSync(view::hasWindowFocus))
                                .isFalse(),
                TIMEOUT);
    }

    /** Verify the view never gains focus within the given time duration. */
    public static void verifyViewNeverGainsFocus(View view) {
        always(
                () ->
                        assertWithMessage("View should not have gained focus; value of hasFocus:")
                                .that(callOnMainSync(view::hasFocus))
                                .isFalse(),
                TIMEOUT);
    }

    /**
     * Make sure that a {@link Runnable} always finishes without throwing a {@link Exception} in the
     * given duration
     *
     * @param r The {@link Runnable} to run.
     * @param timeoutMillis The number of milliseconds to wait for {@code r} to not throw
     */
    public static void always(ThrowingRunnable r, long timeoutMillis) {
        long start = System.currentTimeMillis();

        while (true) {
            try {
                r.run();
                if (System.currentTimeMillis() - start >= timeoutMillis) {
                    return;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    // Do nothing
                }
            } catch (Throwable e) {
                throw new RuntimeException(e);
            }
        }
    }

    /**
     * Returns {@code true} if the activity can't receive IME focus, based on its window flags,
     * and {@code false} otherwise.
     *
     * @param activity the activity to check.
     */
    public static boolean hasUnfocusableWindowFlags(Activity activity) {
        return hasUnfocusableWindowFlags(activity.getWindow().getAttributes().flags);
    }

    /**
     * Returns {@code true} if the activity can't receive IME focus, based on its window flags,
     * and {@code false} otherwise.
     *
     * @param windowFlags the window flags to check.
     */
    public static boolean hasUnfocusableWindowFlags(int windowFlags) {
        return (windowFlags & LayoutParams.FLAG_NOT_FOCUSABLE) != 0
                || (windowFlags & LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0
                || (windowFlags & LayoutParams.FLAG_LOCAL_FOCUS_MODE) != 0;
    }

    public static void verifyWindowAndViewFocus(
            View view, boolean expectWindowFocus, boolean expectViewFocus) {
        if (expectWindowFocus) {
            waitOnMainUntilWindowGainsFocus(view);
        } else {
            verifyWindowNeverGainsFocus(view);
        }
        if (expectViewFocus) {
            waitOnMainUntilViewGainsFocus(view);
        } else {
            verifyViewNeverGainsFocus(view);
        }
    }

    public static void verifyImeAlwaysHiddenWithWindowFlagSet(TestActivity activity) {
        int windowFlags = activity.getWindow().getAttributes().flags;
        View view = activity.getEditText();
        if ((windowFlags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0) {
            // When FLAG_NOT_FOCUSABLE is set true, the view will never gain window focus. The IME
            // will always be hidden even though the view can get focus itself.
            verifyWindowAndViewFocus(view, /*expectWindowFocus*/ false, /*expectViewFocus*/ true);
            verifyImeIsAlwaysHidden(view);
        } else if ((windowFlags & WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM) != 0
                || (windowFlags & WindowManager.LayoutParams.FLAG_LOCAL_FOCUS_MODE) != 0) {
            // When FLAG_ALT_FOCUSABLE_IM or FLAG_LOCAL_FOCUS_MODE is set, the view can gain both
            // window focus and view focus but not IME focus. The IME will always be hidden.
            verifyWindowAndViewFocus(view, /*expectWindowFocus*/ true, /*expectViewFocus*/ true);
            verifyImeIsAlwaysHidden(view);
        }
    }

    /** Activity to help test show/hide behavior of IME. */
    public static class TestActivity extends Activity {
        private static final String TAG = "ImeStressTestUtil.TestActivity";
        private EditText mEditText;
        private boolean mIsAnimating;
        private static WeakReference<TestActivity> sLastCreatedInstance =
                new WeakReference<>(null);

        private final WindowInsetsAnimation.Callback mWindowInsetsAnimationCallback =
                new WindowInsetsAnimation.Callback(DISPATCH_MODE_STOP) {
                    @NonNull
                    @Override
                    public WindowInsetsAnimation.Bounds onStart(
                            @NonNull WindowInsetsAnimation animation,
                            @NonNull WindowInsetsAnimation.Bounds bounds) {
                        mIsAnimating = true;
                        return super.onStart(animation, bounds);
                    }

                    @Override
                    public void onEnd(@NonNull WindowInsetsAnimation animation) {
                        super.onEnd(animation);
                        mIsAnimating = false;
                    }

                    @NonNull
                    @Override
                    public WindowInsets onProgress(
                            @NonNull WindowInsets insets,
                            @NonNull List<WindowInsetsAnimation> runningAnimations) {
                        return insets;
                    }
                };

        /** Create intent with extras. */
        public static Intent createIntent(
                int windowFlags, int softInputFlags, List<String> extras) {
            Intent intent =
                    new Intent()
                            .putExtra(WINDOW_FLAGS, windowFlags)
                            .putExtra(SOFT_INPUT_FLAGS, softInputFlags);
            for (String extra : extras) {
                intent.putExtra(extra, true);
            }
            return intent;
        }

        @Override
        protected void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            Log.i(TAG, "onCreate()");
            sLastCreatedInstance = new WeakReference<>(this);
            boolean isUnfocusableView = getIntent().getBooleanExtra(UNFOCUSABLE_VIEW, false);
            boolean requestFocus = getIntent().getBooleanExtra(REQUEST_FOCUS_ON_CREATE, false);
            int softInputFlags = getIntent().getIntExtra(SOFT_INPUT_FLAGS, 0);
            int windowFlags = getIntent().getIntExtra(WINDOW_FLAGS, 0);
            boolean showWithInputMethodManagerOnCreate =
                    getIntent().getBooleanExtra(INPUT_METHOD_MANAGER_SHOW_ON_CREATE, false);
            boolean hideWithInputMethodManagerOnCreate =
                    getIntent().getBooleanExtra(INPUT_METHOD_MANAGER_HIDE_ON_CREATE, false);
            boolean showWithWindowInsetsControllerOnCreate =
                    getIntent().getBooleanExtra(WINDOW_INSETS_CONTROLLER_SHOW_ON_CREATE, false);
            boolean hideWithWindowInsetsControllerOnCreate =
                    getIntent().getBooleanExtra(WINDOW_INSETS_CONTROLLER_HIDE_ON_CREATE, false);

            getWindow().addFlags(windowFlags);
            getWindow().setSoftInputMode(softInputFlags);

            LinearLayout rootView = new LinearLayout(this);
            rootView.setOrientation(LinearLayout.VERTICAL);
            mEditText = new EditText(this);
            if (isUnfocusableView) {
                mEditText.setFocusableInTouchMode(false);
            }
            rootView.addView(mEditText, new LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT));
            rootView.setFitsSystemWindows(true);
            setContentView(rootView);

            if (requestFocus) {
                requestFocus();
            }
            if (showWithInputMethodManagerOnCreate) {
                showImeWithInputMethodManager();
            }
            if (hideWithInputMethodManagerOnCreate) {
                hideImeWithInputMethodManager();
            }
            if (showWithWindowInsetsControllerOnCreate) {
                showImeWithWindowInsetsController();
            }
            if (hideWithWindowInsetsControllerOnCreate) {
                hideImeWithWindowInsetsController();
            }
        }

        /** Get the last created TestActivity instance. */
        @Nullable
        public static TestActivity getLastCreatedInstance() {
            return sLastCreatedInstance.get();
        }

        /** Show IME with InputMethodManager. */
        public boolean showImeWithInputMethodManager() {
            boolean showResult =
                    getInputMethodManager()
                            .showSoftInput(mEditText, 0 /* flags */);
            if (showResult) {
                Log.i(TAG, "IMM#showSoftInput succeeded");
            } else {
                Log.i(TAG, "IMM#showSoftInput failed");
            }
            return showResult;
        }

        /** Hide IME with InputMethodManager. */
        public boolean hideImeWithInputMethodManager() {
            boolean hideResult =
                    getInputMethodManager()
                            .hideSoftInputFromWindow(mEditText.getWindowToken(), 0 /* flags */);
            if (hideResult) {
                Log.i(TAG, "IMM#hideSoftInput succeeded");
            } else {
                Log.i(TAG, "IMM#hideSoftInput failed");
            }
            return hideResult;
        }

        /** Show IME with WindowInsetsController */
        public void showImeWithWindowInsetsController() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return;
            }
            Log.i(TAG, "showImeWithWIC()");
            WindowInsetsController windowInsetsController = mEditText.getWindowInsetsController();
            assertWithMessage("WindowInsetsController")
                    .that(windowInsetsController)
                    .isNotNull();
            windowInsetsController.show(WindowInsets.Type.ime());
        }

        /** Hide IME with WindowInsetsController. */
        public void hideImeWithWindowInsetsController() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                return;
            }
            Log.i(TAG, "hideImeWithWIC()");
            WindowInsetsController windowInsetsController = mEditText.getWindowInsetsController();
            assertWithMessage("WindowInsetsController")
                    .that(windowInsetsController)
                    .isNotNull();
            windowInsetsController.hide(WindowInsets.Type.ime());
        }

        private InputMethodManager getInputMethodManager() {
            return getSystemService(InputMethodManager.class);
        }

        public EditText getEditText() {
            return mEditText;
        }

        /** Start TestActivity with intent. */
        public static TestActivity start(Intent intent) {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            intent.setAction(Intent.ACTION_MAIN)
                    .setClass(instrumentation.getContext(), TestActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            return (TestActivity) instrumentation.startActivitySync(intent);
        }

        /** Start the second TestActivity with intent. */
        public TestActivity startSecondTestActivity(Intent intent) {
            Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
            intent.setClass(TestActivity.this, TestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            return (TestActivity) instrumentation.startActivitySync(intent);
        }

        public void enableAnimationMonitoring() {
            // Enable WindowInsetsAnimation.
            // Note that this has a side effect of disabling InsetsAnimationThreadControlRunner.
            InstrumentationRegistry.getInstrumentation()
                    .runOnMainSync(
                            () -> {
                                getWindow().setDecorFitsSystemWindows(false);
                                mEditText.setWindowInsetsAnimationCallback(
                                        mWindowInsetsAnimationCallback);
                            });
        }

        public boolean isAnimating() {
            return mIsAnimating;
        }

        public boolean requestFocus() {
            boolean requestFocusResult = mEditText.requestFocus();
            if (requestFocusResult) {
                Log.i(TAG, "View#requestFocus succeeded");
            } else {
                Log.i(TAG, "View#requestFocus failed");
            }
            return requestFocusResult;
        }
    }
}
