/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.view;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.graphics.PixelFormat;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;
import android.test.UiThreadTest;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

import com.android.frameworks.coretests.R;

public class ViewAttachTest extends
        ActivityInstrumentationTestCase2<ViewAttachTestActivity> {

    public ViewAttachTest() {
        super(ViewAttachTestActivity.class);
    }

    /**
     * Make sure that onAttachedToWindow and onDetachedToWindow is called in the
     * correct order. The ViewAttachTestActivity contains a view that will throw
     * a RuntimeException if onDetachedToWindow and onAttachedToWindow are
     * called in the wrong order.
     *
     * 1. Initiate the activity 2. Perform a series of orientation changes to
     * the activity (this will force the View hierarchy to be rebuilt,
     * generating onAttachedToWindow and onDetachedToWindow)
     *
     * Expected result: No RuntimeException is thrown from the TestView in
     * ViewFlipperTestActivity.
     *
     * @throws Throwable
     */
    public void testAttached() throws Throwable {
        final ViewAttachTestActivity activity = getActivity();
        for (int i = 0; i < 20; i++) {
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            SystemClock.sleep(250);
            activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            SystemClock.sleep(250);
        }
    }

    /**
     * Make sure that on any attached view, if the view is full-screen and hosted
     * on a round device, the round scrollbars will be displayed even if the activity
     * window is offset.
     *
     * @throws Throwable
     */
    @UiThreadTest
    public void testRoundScrollbars() throws Throwable {
        final ViewAttachTestActivity activity = getActivity();
        final View rootView = activity.getWindow().getDecorView();
        final WindowManager.LayoutParams params =
            new WindowManager.LayoutParams(
                rootView.getWidth(),
                rootView.getHeight(),
                50, /* xPosition */
                0, /* yPosition */
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);

        rootView.setLayoutParams(params);

        View contentView = activity.findViewById(R.id.view_attach_view);
        boolean shouldDrawRoundScrollbars = contentView.shouldDrawRoundScrollbar();

        if (activity.getResources().getConfiguration().isScreenRound()) {
            assertTrue(shouldDrawRoundScrollbars);
        } else {
            // Never draw round scrollbars on non-round devices.
            assertFalse(shouldDrawRoundScrollbars);
        }
    }
}
