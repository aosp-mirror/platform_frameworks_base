/*
 * Copyright (C) 2024 The Android Open Source Project
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

import static android.view.Surface.FRAME_RATE_CATEGORY_HIGH;
import static android.view.Surface.FRAME_RATE_CATEGORY_HIGH_HINT;
import static android.view.Surface.FRAME_RATE_CATEGORY_LOW;
import static android.view.Surface.FRAME_RATE_CATEGORY_NORMAL;
import static android.view.Surface.FRAME_RATE_CATEGORY_NO_PREFERENCE;
import static android.view.flags.Flags.FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY;
import static android.view.flags.Flags.FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY;
import static android.view.flags.Flags.FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY;
import static android.view.flags.Flags.FLAG_VIEW_VELOCITY_API;
import static android.view.flags.Flags.toolkitFrameRateBySizeReadOnly;
import static android.view.flags.Flags.toolkitFrameRateDefaultNormalReadOnly;
import static android.view.flags.Flags.toolkitFrameRateSmallUsesPercentReadOnly;
import static android.view.flags.Flags.toolkitFrameRateVelocityMappingReadOnly;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;
import android.platform.test.flag.junit.CheckFlagsRule;
import android.platform.test.flag.junit.DeviceFlagsValueProvider;
import android.util.DisplayMetrics;
import android.widget.FrameLayout;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ViewFrameRateTest {

    @Rule
    public ActivityTestRule<ViewCaptureTestActivity> mActivityRule = new ActivityTestRule<>(
            ViewCaptureTestActivity.class);

    @Rule
    public final CheckFlagsRule mCheckFlagsRule = DeviceFlagsValueProvider.createCheckFlagsRule();

    private Activity mActivity;
    private View mMovingView;
    private ViewRootImpl mViewRoot;
    private CountDownLatch mAfterDrawLatch;
    private Throwable mAfterDrawThrowable;

    @Before
    public void setUp() throws Throwable {
        mActivity = mActivityRule.getActivity();
        mActivityRule.runOnUiThread(() -> {
            mActivity.setContentView(R.layout.view_velocity_test);
            mMovingView = mActivity.findViewById(R.id.moving_view);
        });
        ViewParent parent = mActivity.getWindow().getDecorView().getParent();
        while (parent instanceof View) {
            parent = parent.getParent();
        }
        mViewRoot = (ViewRootImpl) parent;
        mAfterDrawThrowable = null;
    }

    @Test
    @RequiresFlagsEnabled({FLAG_VIEW_VELOCITY_API,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void frameRateChangesWhenContentMoves() throws Throwable {
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.offsetLeftAndRight(100);
            runAfterDraw(() -> {
                if (toolkitFrameRateVelocityMappingReadOnly()) {
                    float frameRate = mViewRoot.getLastPreferredFrameRate();
                    assertTrue(frameRate > 0);
                } else {
                    assertEquals(FRAME_RATE_CATEGORY_HIGH,
                            mViewRoot.getLastPreferredFrameRateCategory());
                }
            });
        });
        waitForAfterDraw();
    }

    @UiThreadTest
    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void firstFrameNoMovement() {
        assertEquals(0f, mViewRoot.getLastPreferredFrameRate(), 0f);
    }

    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void touchBoostDisable() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(
                    /* downTime */ now,
                    /* eventTime */ now,
                    /* action */ MotionEvent.ACTION_DOWN,
                    /* x */ 0f,
                    /* y */ 0f,
                    /* metaState */ 0
            );
            mActivity.dispatchTouchEvent(down);
            mMovingView.offsetLeftAndRight(10);
        });
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
        });

        mActivityRule.runOnUiThread(() -> {
            assertFalse(mViewRoot.getIsTouchBoosting());
        });
    }

    @Test
    @RequiresFlagsEnabled({FLAG_VIEW_VELOCITY_API,
            FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void lowVelocity60() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mMovingView.setLayoutParams(layoutParams);
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setFrameContentVelocity(1f);
            mMovingView.invalidate();
            runAfterDraw(() -> assertEquals(60f, mViewRoot.getLastPreferredFrameRate(), 0f));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_VIEW_VELOCITY_API,
            FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void velocityWithChildMovement() throws Throwable {
        FrameLayout frameLayout = new FrameLayout(mActivity);
        mActivityRule.runOnUiThread(() -> {
            ViewGroup.LayoutParams fullSize = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            mActivity.setContentView(frameLayout, fullSize);
            if (mMovingView.getParent() instanceof ViewGroup) {
                ((ViewGroup) mMovingView.getParent()).removeView(mMovingView);
            }
            frameLayout.addView(mMovingView, fullSize);
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            frameLayout.setFrameContentVelocity(1f);
            mMovingView.offsetTopAndBottom(100);
            frameLayout.invalidate();
            runAfterDraw(() -> assertEquals(60f, mViewRoot.getLastPreferredFrameRate(), 0f));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_VIEW_VELOCITY_API,
            FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void highVelocity120() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mMovingView.setLayoutParams(layoutParams);
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setFrameContentVelocity(1_000_000_000f);
            mMovingView.invalidate();
            runAfterDraw(() -> {
                assertEquals(120f, mViewRoot.getLastPreferredFrameRate(), 0f);
            });
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategorySmall() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                double smallSize = Math.sqrt(pixels);
                layoutParams.width = (int) smallSize;
                layoutParams.height = (int) smallSize;
            } else {
                float density = displayMetrics.density;
                layoutParams.height = ((int) (40 * density));
                layoutParams.width = ((int) (40 * density));
            }

            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a normal category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateBySizeReadOnly()
                    ? FRAME_RATE_CATEGORY_LOW : FRAME_RATE_CATEGORY_NORMAL;
            runAfterDraw(
                    () -> assertEquals(expected, mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryNarrowWidth() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                int parentWidth = ((View) mMovingView.getParent()).getWidth();
                layoutParams.width = parentWidth;
                layoutParams.height = (int) (pixels / parentWidth);
            } else {
                float density = displayMetrics.density;
                layoutParams.width = (int) (10 * density);
                layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            }
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a normal category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateBySizeReadOnly()
                    ? FRAME_RATE_CATEGORY_LOW : FRAME_RATE_CATEGORY_NORMAL;
            runAfterDraw(
                    () -> assertEquals(expected, mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryNarrowHeight() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                int parentHeight = ((View) mMovingView.getParent()).getHeight();
                layoutParams.width = (int) (pixels / parentHeight);
                layoutParams.height = parentHeight;
            } else {
                float density = displayMetrics.density;
                layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
                layoutParams.height = (int) (10 * density);
            }
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a normal category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateBySizeReadOnly()
                    ? FRAME_RATE_CATEGORY_LOW : FRAME_RATE_CATEGORY_NORMAL;
            runAfterDraw(
                    () -> assertEquals(expected, mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryLargeWidth() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                double smallSize = Math.sqrt(pixels);
                layoutParams.width = 1 + (int) Math.ceil(pixels / smallSize);
                layoutParams.height = (int) smallSize;
            } else {
                float density = displayMetrics.density;
                layoutParams.width = ((int) Math.ceil(40 * density)) + 1;
                layoutParams.height = ((int) (40 * density));
            }
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a high category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateDefaultNormalReadOnly()
                    ? FRAME_RATE_CATEGORY_NORMAL : FRAME_RATE_CATEGORY_HIGH;
            runAfterDraw(
                    () -> assertEquals(expected, mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void noVelocityUsesCategoryLargeHeight() throws Throwable {
        final CountDownLatch drawLatch1 = new CountDownLatch(1);
        mActivityRule.runOnUiThread(() -> {
            DisplayMetrics displayMetrics = mActivity.getResources().getDisplayMetrics();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            if (toolkitFrameRateSmallUsesPercentReadOnly()) {
                float pixels = displayMetrics.widthPixels * displayMetrics.heightPixels * 0.07f;
                double smallSize = Math.sqrt(pixels);
                layoutParams.width = (int) smallSize;
                layoutParams.height = 1 + (int) Math.ceil(pixels / smallSize);
            } else {
                float density = displayMetrics.density;
                layoutParams.width = ((int) (40 * density));
                layoutParams.height = ((int) Math.ceil(40 * density)) + 1;
            }
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.getViewTreeObserver().addOnDrawListener(drawLatch1::countDown);
        });

        assertTrue(drawLatch1.await(1, TimeUnit.SECONDS));
        waitForFrameRateCategoryToSettle();

        // Now that it is small, any invalidation should have a high category
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateDefaultNormalReadOnly()
                    ? FRAME_RATE_CATEGORY_NORMAL : FRAME_RATE_CATEGORY_HIGH;
            runAfterDraw(
                    () -> assertEquals(expected, mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void defaultNormal() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            View parent = (View) mMovingView.getParent();
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = parent.getWidth() / 2;
            layoutParams.height = parent.getHeight() / 2;
            mMovingView.setLayoutParams(layoutParams);
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            int expected = toolkitFrameRateDefaultNormalReadOnly()
                    ? FRAME_RATE_CATEGORY_NORMAL : FRAME_RATE_CATEGORY_HIGH;
            runAfterDraw(() -> assertEquals(expected,
                    mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VELOCITY_MAPPING_READ_ONLY
    })
    public void frameRateAndCategory() throws Throwable {
        mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_LOW);
            mMovingView.setFrameContentVelocity(1f);
            mMovingView.invalidate();
            runAfterDraw(() -> {
                assertEquals(FRAME_RATE_CATEGORY_LOW,
                        mViewRoot.getLastPreferredFrameRateCategory());
                assertEquals(60f, mViewRoot.getLastPreferredFrameRate());
            });
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY
    })
    public void willNotDrawUsesCategory() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setWillNotDraw(true);
            mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_LOW);
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            runAfterDraw(() -> assertEquals(FRAME_RATE_CATEGORY_LOW,
                    mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL);
            mMovingView.setAlpha(0.9f);
            runAfterDraw(() -> {
                assertEquals(FRAME_RATE_CATEGORY_NORMAL,
                        mViewRoot.getLastPreferredFrameRateCategory());
            });
        });
        waitForAfterDraw();
    }

    /**
     * A common behavior is for two different views to be invalidated in succession, but
     * intermittently. We want to treat this as an intermittent invalidation.
     *
     * This test will only succeed on non-cuttlefish devices, so it is commented out
     * for potential manual testing.
     */
//    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY})
    public void intermittentDoubleInvalidate() throws Throwable {
        View parent = (View) mMovingView.getParent();
        mActivityRule.runOnUiThread(() -> {
            parent.setWillNotDraw(false);
            // Make sure the View is large
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = parent.getWidth();
            layoutParams.height = parent.getHeight();
            mMovingView.setLayoutParams(layoutParams);
        });
        waitForFrameRateCategoryToSettle();
        for (int i = 0; i < 5; i++) {
            int expectedCategory;
            if (i < 2) {
                // not intermittent yet.
                // It takes 2 frames of intermittency before Views vote as intermittent.
                expectedCategory =
                        toolkitFrameRateDefaultNormalReadOnly() ? FRAME_RATE_CATEGORY_NORMAL
                                : FRAME_RATE_CATEGORY_HIGH;
            } else {
                // intermittent
                // Even though this is not a small View, step 3 is triggered by this flag, which
                // brings intermittent to LOW
                expectedCategory = toolkitFrameRateBySizeReadOnly()
                        ? FRAME_RATE_CATEGORY_LOW
                        : FRAME_RATE_CATEGORY_NORMAL;
            }
            mActivityRule.runOnUiThread(() -> {
                mMovingView.invalidate();
                runAfterDraw(() -> assertEquals(expectedCategory,
                        mViewRoot.getLastPreferredFrameRateCategory()));
            });
            waitForAfterDraw();
            mActivityRule.runOnUiThread(() -> {
                parent.invalidate();
                runAfterDraw(() -> assertEquals(expectedCategory,
                        mViewRoot.getLastPreferredFrameRateCategory()));
            });
            waitForAfterDraw();
            Thread.sleep(90);
        }
    }

    // When a view has two motions that offset each other, the overall motion
    // should be canceled and be considered unmoved.
    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY
    })
    public void sameFrameMotion() throws Throwable {
        mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NO_PREFERENCE);
        waitForFrameRateCategoryToSettle();

        mActivityRule.runOnUiThread(() -> {
            mMovingView.offsetLeftAndRight(10);
            mMovingView.offsetLeftAndRight(-10);
            mMovingView.offsetTopAndBottom(100);
            mMovingView.offsetTopAndBottom(-100);
            mMovingView.invalidate();
            runAfterDraw(() -> {
                assertEquals(0f, mViewRoot.getLastPreferredFrameRate(), 0f);
                assertEquals(FRAME_RATE_CATEGORY_NO_PREFERENCE,
                        mViewRoot.getLastPreferredFrameRateCategory());
            });
        });
        waitForAfterDraw();
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY
    })
    public void frameRateReset() throws Throwable {
        mMovingView.setRequestedFrameRate(120f);
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> mMovingView.setVisibility(View.INVISIBLE));

        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        for (int i = 0; i < 120; i++) {
            mActivityRule.runOnUiThread(() -> {
                mMovingView.getParent().onDescendantInvalidated(mMovingView, mMovingView);
            });
            instrumentation.waitForIdleSync();
        }

        assertEquals(0f, mViewRoot.getLastPreferredFrameRate(), 0f);
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY
    })
    public void frameRateResetWithInvalidations() throws Throwable {
        mMovingView.setRequestedFrameRate(120f);
        waitForFrameRateCategoryToSettle();
        mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_NORMAL);

        for (int i = 0; i < 120; i++) {
            mActivityRule.runOnUiThread(() -> {
                mMovingView.invalidate();
                runAfterDraw(() -> {});
            });
            waitForAfterDraw();
        }

        assertEquals(0f, mViewRoot.getLastPreferredFrameRate(), 0f);
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY
    })
    public void testQuickTouchBoost() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_LOW);
            ViewGroup.LayoutParams layoutParams = mMovingView.getLayoutParams();
            layoutParams.width = ViewGroup.LayoutParams.MATCH_PARENT;
            layoutParams.height = ViewGroup.LayoutParams.MATCH_PARENT;
            mMovingView.setLayoutParams(layoutParams);
            mMovingView.setOnClickListener((v) -> {});
        });
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> assertEquals(FRAME_RATE_CATEGORY_LOW,
                mViewRoot.getLastPreferredFrameRateCategory()));
        int[] position = new int[2];
        mActivityRule.runOnUiThread(() -> {
            mMovingView.getLocationOnScreen(position);
            position[0] += mMovingView.getWidth() / 2;
            position[1] += mMovingView.getHeight() / 2;
        });
        final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();

        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(
                now, // downTime
                now, // eventTime
                MotionEvent.ACTION_DOWN, // action
                position[0], // x
                position[1], // y
                0 // metaState
        );
        down.setSource(InputDevice.SOURCE_TOUCHSCREEN);
        instrumentation.sendPointerSync(down);
        assertEquals(FRAME_RATE_CATEGORY_HIGH_HINT, mViewRoot.getLastPreferredFrameRateCategory());
    }

    @Test
    @RequiresFlagsEnabled({FLAG_TOOLKIT_SET_FRAME_RATE_READ_ONLY,
            FLAG_TOOLKIT_FRAME_RATE_VIEW_ENABLING_READ_ONLY
    })
    public void idleDetected() throws Throwable {
        waitForFrameRateCategoryToSettle();
        mActivityRule.runOnUiThread(() -> {
            mMovingView.setRequestedFrameRate(View.REQUESTED_FRAME_RATE_CATEGORY_HIGH);
            mMovingView.setFrameContentVelocity(Float.MAX_VALUE);
            mMovingView.invalidate();
            runAfterDraw(() -> assertEquals(FRAME_RATE_CATEGORY_HIGH,
                    mViewRoot.getLastPreferredFrameRateCategory()));
        });
        waitForAfterDraw();

        // Wait for idle timeout
        Thread.sleep(500);
        assertEquals(0f, mViewRoot.getLastPreferredFrameRate());
        assertEquals(FRAME_RATE_CATEGORY_NO_PREFERENCE,
                mViewRoot.getLastPreferredFrameRateCategory());
    }

    private void runAfterDraw(@NonNull Runnable runnable) {
        Handler handler = new Handler(Looper.getMainLooper());
        mAfterDrawLatch = new CountDownLatch(1);
        ViewTreeObserver.OnDrawListener listener = new ViewTreeObserver.OnDrawListener() {
            @Override
            public void onDraw() {
                handler.postAtFrontOfQueue(() -> {
                    mMovingView.getViewTreeObserver().removeOnDrawListener(this);
                    try {
                        runnable.run();
                    } catch (Throwable t) {
                        mAfterDrawThrowable = t;
                    }
                    mAfterDrawLatch.countDown();
                });
            }
        };
        mMovingView.getViewTreeObserver().addOnDrawListener(listener);
    }

    private void waitForAfterDraw() throws Throwable {
        assertTrue(mAfterDrawLatch.await(1, TimeUnit.SECONDS));
        if (mAfterDrawThrowable != null) {
            throw mAfterDrawThrowable;
        }
    }

    private void waitForFrameRateCategoryToSettle() throws Throwable {
        for (int i = 0; i < 5 || mViewRoot.getIsFrameRateBoosting(); i++) {
            final CountDownLatch drawLatch = new CountDownLatch(1);

            ViewTreeObserver.OnDrawListener listener = drawLatch::countDown;

            mActivityRule.runOnUiThread(() -> {
                mMovingView.invalidate();
                mMovingView.getViewTreeObserver().addOnDrawListener(listener);
            });

            assertTrue(drawLatch.await(1, TimeUnit.SECONDS));
            mActivityRule.runOnUiThread(
                    () -> mMovingView.getViewTreeObserver().removeOnDrawListener(listener));
        }
        // after boosting is complete, wait for one more draw cycle to ensure the boost isn't
        // the last frame rate set
        mActivityRule.runOnUiThread(() -> {
            mMovingView.invalidate();
            runAfterDraw(() -> {});
        });
        waitForAfterDraw();
    }
}
