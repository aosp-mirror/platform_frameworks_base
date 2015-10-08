/*
 * Copyright (C) 2015 The Android Open Source Project
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
 * limitations under the License
 */

package android.util;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.pm.ActivityInfo;

import android.test.ActivityInstrumentationTestCase2;

import com.android.internal.util.Preconditions;

/**
 * Utilities for manipulating screen orientation.
 */
public final class OrientationUtil {

    private final Activity mActivity;
    private final Instrumentation mInstrumentation;

    private final Runnable mSetToPortrait = new Runnable() {
        @Override
        public void run() {
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        }
    };

    private final Runnable mSetToLandscape = new Runnable() {
        @Override
        public void run() {
            mActivity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
        }
    };

    public static OrientationUtil initializeAndStartActivityIfNotStarted(
            ActivityInstrumentationTestCase2 testCase) {
        Preconditions.checkNotNull(testCase);
        return new OrientationUtil(testCase.getActivity(), testCase.getInstrumentation());
    }

    private OrientationUtil(Activity activity, Instrumentation instrumentation) {
        mActivity = activity;
        mInstrumentation = instrumentation;
    }

    public void setPortraitOrientation() {
        mInstrumentation.runOnMainSync(mSetToPortrait);
    }

    public void setLandscapeOrientation() {
        mInstrumentation.runOnMainSync(mSetToLandscape);
    }
}
