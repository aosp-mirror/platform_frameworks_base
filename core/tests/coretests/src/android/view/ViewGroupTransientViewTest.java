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

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.fail;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.widget.FrameLayout;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class ViewGroupTransientViewTest {

    @Rule
    public ActivityTestRule<Activity> mActivityRule = new ActivityTestRule<>(Activity.class);

    private FrameLayout mBasePanel;
    private ViewGroup mTestViewGroup;
    private TestView mTestView;

    @Before
    public void setUp() {
        final Activity activity = mActivityRule.getActivity();
        mBasePanel = new FrameLayout(activity);
        mTestViewGroup = new FrameLayout(activity);
        mTestView = new TestView(activity);
        activity.runOnUiThread(() -> activity.setContentView(mBasePanel));
    }

    @UiThreadTest
    @Test
    public void addAndRemove_inNonAttachedViewGroup_shouldNotAttachAndDetach() {
        mTestViewGroup.addTransientView(mTestView, 0);
        assertEquals(0, mTestView.mAttachedCount);

        mTestViewGroup.removeTransientView(mTestView);
        assertEquals(0, mTestView.mDetachedCount);
    }

    @UiThreadTest
    @Test
    public void addAndRemove_inAttachedViewGroup_shouldAttachAndDetachOnce() {
        mBasePanel.addView(mTestViewGroup);
        mTestViewGroup.addTransientView(mTestView, 0);
        assertEquals(mTestView, mTestViewGroup.getTransientView(0));
        assertEquals(1, mTestViewGroup.getTransientViewCount());
        assertEquals(1, mTestView.mAttachedCount);

        mBasePanel.removeView(mTestViewGroup);
        mTestViewGroup.removeTransientView(mTestView);
        assertEquals(null, mTestViewGroup.getTransientView(0));
        assertEquals(0, mTestViewGroup.getTransientViewCount());
        assertEquals(1, mTestView.mDetachedCount);
    }

    @UiThreadTest
    @Test
    public void addRemoveAdd_noException() {
        mBasePanel.addView(mTestViewGroup);
        mTestViewGroup.addTransientView(mTestView, 1);
        mTestViewGroup.removeTransientView(mTestView);
        mTestViewGroup.addTransientView(mTestView, 2);
    }

    @UiThreadTest
    @Test
    public void reAddBeforeRemove_shouldThrowException() {
        mTestViewGroup.addView(mTestView);

        try {
            mTestViewGroup.addTransientView(mTestView, 0);
            fail("Not allow to add as transient view before removing it");
        } catch (IllegalStateException e) {
            // Expected
        }

        mTestViewGroup.removeView(mTestView);
        mTestViewGroup.addTransientView(mTestView, 0);
        try {
            mTestViewGroup.addTransientView(mTestView, 1);
            fail("Not allow to add the same transient view again");
        } catch (IllegalStateException e) {
            // Expected
        }
    }

    @Test
    public void drawTransientView() throws Exception {
        // For view can be drawn if keyguard is active.
        mActivityRule.getActivity().setShowWhenLocked(true);

        final CountDownLatch latch = new CountDownLatch(1);
        mTestView.mOnDraw = () -> latch.countDown();

        mActivityRule.getActivity().runOnUiThread(() -> {
            mBasePanel.addView(mTestViewGroup);
            mTestViewGroup.addTransientView(mTestView, 0);
        });

        if (!latch.await(3, TimeUnit.SECONDS)) {
            fail("Transient view does not draw");
        }
    }

    private static class TestView extends View {
        int mAttachedCount;
        int mDetachedCount;
        Runnable mOnDraw;

        TestView(Context context) {
            super(context);
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            mAttachedCount++;
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            mDetachedCount++;
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (mOnDraw != null) {
                mOnDraw.run();
                mOnDraw = null;
            }
        }
    }
}
