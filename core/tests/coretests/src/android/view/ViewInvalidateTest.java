/*
 * Copyright (C) 2016 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.view.ViewTreeObserver.OnDrawListener;
import android.widget.FrameLayout;

import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.test.rule.ActivityTestRule;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Test of invalidates, drawing, and the flags that support them
 */
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ViewInvalidateTest {
    @Rule
    public ActivityTestRule<Activity> mActivityRule = new ActivityTestRule<>(Activity.class);

    private static final int INVAL_TEST_FLAG_MASK = View.PFLAG_DIRTY
            | View.PFLAG_DRAWN
            | View.PFLAG_DRAWING_CACHE_VALID
            | View.PFLAG_INVALIDATED
            | View.PFLAG_DRAW_ANIMATION;

    @Before
    public void setup() throws Throwable {
        // separate runnable to initialize, so ref is safe to pass to runOnMainAndDrawSync
        mActivityRule.runOnUiThread(() -> {
            mParent = new FrameLayout(getContext());
            mChild = new View(getContext());
        });

        // attached view is drawn once
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mParent, () -> {
            mParent.addView(mChild);
            getActivity().setContentView(mParent);

            // 'invalidated', but not yet drawn
            validateInvalFlags(mChild, View.PFLAG_INVALIDATED);
        });
    }

    @After
    public void teardown() {
        // ensure we don't share views between tests
        mParent = null;
        mChild = null;
    }

    Context getContext() {
        return InstrumentationRegistry.getTargetContext();
    }

    Activity getActivity() {
        return mActivityRule.getActivity();
    }

    private ViewGroup mParent;
    private View mChild;

    private static void validateInvalFlags(View view, int... expectedFlagArray) {
        int expectedFlags = 0;
        for (int expectedFlag : expectedFlagArray) {
            expectedFlags |= expectedFlag;
        }

        final int observedFlags = view.mPrivateFlags & INVAL_TEST_FLAG_MASK;
        assertEquals(String.format("expect %x, observed %x", expectedFlags, observedFlags),
                expectedFlags, observedFlags);
    }

    private static ViewRootImpl getViewRoot(View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent instanceof ViewRootImpl) {
                return (ViewRootImpl) parent;
            }
            parent = parent.getParent();
        }
        return null;
    }

    @UiThreadTest
    @Test
    public void testInvalidate_behavior() throws Throwable {
        validateInvalFlags(mChild,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);
        validateInvalFlags(mParent,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);
        assertFalse(getViewRoot(mParent).mTraversalScheduled);

        mChild.invalidate();

        // no longer drawn, is now invalidated
        validateInvalFlags(mChild,
                View.PFLAG_DIRTY,
                View.PFLAG_INVALIDATED);

        // parent drawing cache no longer valid, marked dirty
        validateInvalFlags(mParent,
                View.PFLAG_DRAWN,
                View.PFLAG_DIRTY);
        assertTrue(getViewRoot(mParent).mTraversalScheduled);
    }

    @UiThreadTest
    @Test
    public void testInvalidate_false() {
        // Invalidate makes it invalid
        validateInvalFlags(mChild,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);

        mChild.invalidate(/*don't invalidate cache*/ false);

        // drawn is cleared, dirty set, nothing else changed
        validateInvalFlags(mChild,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DIRTY);
    }

    @Test
    public void testInvalidate_simple() throws Throwable {
        // simple invalidate, which marks the view invalid
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mParent, () -> {
            validateInvalFlags(mChild,
                    View.PFLAG_DRAWING_CACHE_VALID,
                    View.PFLAG_DRAWN);

            mChild.invalidate();

            validateInvalFlags(mChild,
                    View.PFLAG_DIRTY,
                    View.PFLAG_INVALIDATED);
        });

        // after draw pass, view has drawn, no longer invalid
        mActivityRule.runOnUiThread(() -> {
            validateInvalFlags(mChild,
                    View.PFLAG_DRAWING_CACHE_VALID,
                    View.PFLAG_DRAWN);
        });
    }

    @UiThreadTest
    @Test
    public void testInvalidate_manualUpdateDisplayList() {
        // Invalidate makes it invalid
        validateInvalFlags(mChild,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);

        mChild.invalidate();
        validateInvalFlags(mChild,
                View.PFLAG_DIRTY,
                View.PFLAG_INVALIDATED);

        // updateDisplayListIfDirty makes it valid again, but invalidate still set,
        // since it's cleared by View#draw(canvas, parent, drawtime)
        mChild.updateDisplayListIfDirty();
            validateInvalFlags(mChild,
                    View.PFLAG_DRAWING_CACHE_VALID,
                    View.PFLAG_DRAWN,
                    View.PFLAG_INVALIDATED);
    }

    @UiThreadTest
    @Test
    public void testInvalidateChild_simple() {
        validateInvalFlags(mParent,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);
        assertFalse(getViewRoot(mParent).mTraversalScheduled);

        mParent.invalidateChild(mChild, new Rect(0, 0, 1, 1));

        validateInvalFlags(mParent,
                View.PFLAG_DIRTY,
                View.PFLAG_DRAWN);
        assertTrue(getViewRoot(mParent).mTraversalScheduled);
    }

    @Test
    public void testInvalidateChild_childHardwareLayer() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mParent, () -> {
            // do in runnable, so tree won't be dirty
            mParent.setLayerType(View.LAYER_TYPE_HARDWARE, null);
        });

        mActivityRule.runOnUiThread(() -> {
            validateInvalFlags(mParent,
                    View.PFLAG_DRAWING_CACHE_VALID,
                    View.PFLAG_DRAWN);

            mParent.invalidateChild(mChild, new Rect(0, 0, 1, 1));

            validateInvalFlags(mParent,
                    View.PFLAG_DIRTY,
                    View.PFLAG_DRAWN); // Note: note invalidated, since HW damage handled in native
        });
    }

    @Test
    public void testInvalidateChild_childSoftwareLayer() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mParent, () -> {
            // do in runnable, so tree won't be dirty
            mParent.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        });

        mActivityRule.runOnUiThread(() -> {
            validateInvalFlags(mParent,
                    View.PFLAG_DRAWING_CACHE_VALID,
                    View.PFLAG_DRAWN);

            mParent.invalidateChild(mChild, new Rect(0, 0, 1, 1));

            validateInvalFlags(mParent,
                    View.PFLAG_DIRTY,
                    View.PFLAG_DRAWN,
                    View.PFLAG_INVALIDATED); // Note: invalidated, since SW damage handled here
        });
    }

    @UiThreadTest
    @Test
    public void testInvalidateChild_legacyAnimation() throws Throwable {
        mChild.mPrivateFlags |= View.PFLAG_DRAW_ANIMATION;

        validateInvalFlags(mChild,
                View.PFLAG_DRAW_ANIMATION,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);
        validateInvalFlags(mParent,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);
        assertFalse(getViewRoot(mParent).mIsAnimating);

        mParent.invalidateChild(mChild, new Rect(0, 0, 1, 1));

        validateInvalFlags(mChild,
                View.PFLAG_DRAW_ANIMATION,
                View.PFLAG_DRAWING_CACHE_VALID,
                View.PFLAG_DRAWN);
        validateInvalFlags(mParent,
                View.PFLAG_DIRTY,
                View.PFLAG_DRAW_ANIMATION, // carried up to parent
                View.PFLAG_DRAWN);
        assertTrue(getViewRoot(mParent).mIsAnimating);
    }

    /** Copied from cts/common/device-side/util. */
    static class WidgetTestUtils {
        public static void runOnMainAndDrawSync(@NonNull final ActivityTestRule activityTestRule,
                @NonNull final View view, @Nullable final Runnable runner) throws Throwable {
            final CountDownLatch latch = new CountDownLatch(1);

            activityTestRule.runOnUiThread(() -> {
                final OnDrawListener listener = new OnDrawListener() {
                    @Override
                    public void onDraw() {
                        // posting so that the sync happens after the draw that's about to happen
                        view.post(() -> {
                            activityTestRule.getActivity().getWindow().getDecorView()
                                    .getViewTreeObserver().removeOnDrawListener(this);
                            latch.countDown();
                        });
                    }
                };

                activityTestRule.getActivity().getWindow().getDecorView()
                        .getViewTreeObserver().addOnDrawListener(listener);

                if (runner != null) {
                    runner.run();
                }
                view.invalidate();
            });

            try {
                Assert.assertTrue("Expected draw pass occurred within 5 seconds",
                        latch.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }

    }
}
