/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.content.res.Resources;
import android.content.Context;
import android.view.SurfaceControl;
import android.view.View;

import android.annotation.TestApi;

/**
 * Utility class for adding a view hierarchy to a SurfaceControl.
 *
 * See WindowlessWmTest for example usage.
 * @hide
 */
@TestApi
public class WindowlessViewRoot {
    ViewRootImpl mViewRoot;
    WindowlessWindowManager mWm;
    public WindowlessViewRoot(Context c, Display d, SurfaceControl rootSurface) {
        mWm = new WindowlessWindowManager(c.getResources().getConfiguration(), rootSurface);
        mViewRoot = new ViewRootImpl(c, d, mWm);
    }

    public void addView(View view, WindowManager.LayoutParams attrs) {
        mViewRoot.setView(view, attrs, null);
    }
}
