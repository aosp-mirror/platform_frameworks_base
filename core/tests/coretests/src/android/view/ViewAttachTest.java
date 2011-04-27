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

import android.content.pm.ActivityInfo;
import android.os.SystemClock;
import android.test.ActivityInstrumentationTestCase2;

public class ViewAttachTest extends
        ActivityInstrumentationTestCase2<ViewAttachTestActivity> {

    public ViewAttachTest() {
        super(ViewAttachTestActivity.class);
    }

    /**
     * Make sure that onAttachedToWindow and onDetachedToWindow is called in the
     * correct order The ViewAttachTestActivity contains a view that will throw
     * an RuntimeException if onDetachedToWindow and onAttachedToWindow is
     * called in the wrong order.
     *
     * 1. Initiate the activity 2. Perform a series of orientation changes to
     * the activity (this will force the View hierarchy to be rebuild,
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
}
