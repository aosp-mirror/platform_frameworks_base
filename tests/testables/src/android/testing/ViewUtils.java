/*
 * Copyright (C) 2017 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package android.testing;

import android.content.pm.ApplicationInfo;
import android.graphics.PixelFormat;
import android.support.test.InstrumentationRegistry;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowManager.LayoutParams;

/**
 * Utilities to make testing views easier.
 */
public class ViewUtils {

    /**
     * Causes the view (and its children) to have {@link View#onAttachedToWindow()} called.
     *
     * This is currently done by adding the view to a window.
     */
    public static void attachView(View view) {
        // Make sure hardware acceleration isn't turned on.
        view.getContext().getApplicationInfo().flags &=
                ~(ApplicationInfo.FLAG_HARDWARE_ACCELERATED);
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT,
                LayoutParams.TYPE_APPLICATION_OVERLAY,
                0, PixelFormat.TRANSLUCENT);
        view.getContext().getSystemService(WindowManager.class).addView(view, lp);
    }

    /**
     * Causes the view (and its children) to have {@link View#onDetachedFromWindow()} called.
     *
     * This is currently done by removing the view from a window.
     */
    public static void detachView(View view) {
        view.getContext().getSystemService(WindowManager.class).removeViewImmediate(view);
    }
}
