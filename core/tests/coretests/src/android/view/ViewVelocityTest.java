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

import static android.view.flags.Flags.FLAG_VIEW_VELOCITY_API;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.os.SystemClock;
import android.platform.test.annotations.RequiresFlagsEnabled;

import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.frameworks.coretests.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ViewVelocityTest {

    @Rule
    public ActivityTestRule<ViewCaptureTestActivity> mActivityRule = new ActivityTestRule<>(
            ViewCaptureTestActivity.class);

    private Activity mActivity;
    private View mMovingView;
    private ViewRootImpl mViewRoot;

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
    }

    @UiThreadTest
    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void frameRateChangesWhenContentMoves() {
        mMovingView.offsetLeftAndRight(100);
        float frameRate = mViewRoot.getPreferredFrameRate();
        assertTrue(frameRate > 0);
    }

    @UiThreadTest
    @Test
    @RequiresFlagsEnabled(FLAG_VIEW_VELOCITY_API)
    public void firstFrameNoMovement() {
        assertEquals(0f, mViewRoot.getPreferredFrameRate(), 0f);
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
}
