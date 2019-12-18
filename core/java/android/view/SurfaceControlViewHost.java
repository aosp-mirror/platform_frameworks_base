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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TestApi;
import android.content.Context;
import android.os.IBinder;

/**
 * Utility class for adding a view hierarchy to a SurfaceControl.
 *
 * See WindowlessWmTest for example usage.
 * @hide
 */
@TestApi
public class SurfaceControlViewHost {
    private ViewRootImpl mViewRoot;
    private WindowlessWindowManager mWm;

    private SurfaceControl mSurfaceControl;

    /**
     * @hide
     */
    @TestApi
    public class SurfacePackage {
        final SurfaceControl mSurfaceControl;
        // TODO: Accessibility ID goes here

        SurfacePackage(SurfaceControl sc) {
            mSurfaceControl = sc;
        }

        public @NonNull SurfaceControl getSurfaceControl() {
            return mSurfaceControl;
        }
    }

    /** @hide */
    public SurfaceControlViewHost(@NonNull Context c, @NonNull Display d,
            @NonNull WindowlessWindowManager wwm) {
        mWm = wwm;
        mViewRoot = new ViewRootImpl(c, d, mWm);
    }

    public SurfaceControlViewHost(@NonNull Context c, @NonNull Display d,
            @Nullable IBinder hostInputToken) {
        mSurfaceControl = new SurfaceControl.Builder()
            .setContainerLayer()
            .setName("SurfaceControlViewHost")
            .build();
        mWm = new WindowlessWindowManager(c.getResources().getConfiguration(), mSurfaceControl,
                hostInputToken);
        mViewRoot = new ViewRootImpl(c, d, mWm);
    }

    public @Nullable SurfacePackage getSurfacePackage() {
        if (mSurfaceControl != null) {
            return new SurfacePackage(mSurfaceControl);
        } else {
            return null;
        }
    }

    public void addView(View view, WindowManager.LayoutParams attrs) {
        mViewRoot.setView(view, attrs, null);
    }

    public void relayout(WindowManager.LayoutParams attrs) {
        mViewRoot.setLayoutParams(attrs, false);
        mViewRoot.setReportNextDraw();
        mWm.setCompletionCallback(mViewRoot.mWindow.asBinder(), (SurfaceControl.Transaction t) -> {
            t.apply();
        });
    }

    public void dispose() {
        mViewRoot.dispatchDetachedFromWindow();
    }

    /**
     * Tell this viewroot to clean itself up.
     * @hide
     */
    public void die() {
        mViewRoot.die(false /* immediate */);
    }
}
