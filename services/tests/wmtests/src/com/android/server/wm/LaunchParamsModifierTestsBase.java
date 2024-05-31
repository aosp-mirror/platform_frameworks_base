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

package com.android.server.wm;

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.util.DisplayMetrics.DENSITY_DEFAULT;

import static com.android.server.wm.LaunchParamsController.LaunchParamsModifier.PHASE_BOUNDS;

import android.app.ActivityOptions;
import android.content.pm.ActivityInfo;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.InsetsSource;
import android.view.InsetsState;
import android.view.WindowInsets;

import com.android.server.wm.LaunchParamsController.LaunchParams;
import com.android.server.wm.LaunchParamsController.LaunchParamsModifier;

/** Common base class for launch param modifier unit test classes. */
public class LaunchParamsModifierTestsBase<T extends LaunchParamsModifier> extends WindowTestsBase {

    static final Rect DISPLAY_BOUNDS = new Rect(/* left */ 0, /* top */ 0,
            /* right */ 1920, /* bottom */ 1080);
    static final Rect DISPLAY_STABLE_BOUNDS = new Rect(/* left */ 100,
            /* top */ 200, /* right */ 1620, /* bottom */ 680);

    ActivityRecord mActivity;

    T mTarget;

    LaunchParams mCurrent;
    LaunchParams mResult;


    TestDisplayContent createNewDisplayContent(int windowingMode) {
        return createNewDisplayContent(windowingMode, DISPLAY_BOUNDS, DISPLAY_STABLE_BOUNDS);
    }

    TestDisplayContent createNewDisplayContent(int windowingMode, Rect displayBounds,
            Rect displayStableBounds) {
        final TestDisplayContent display = addNewDisplayContentAt(DisplayContent.POSITION_TOP);
        display.getDefaultTaskDisplayArea().setWindowingMode(windowingMode);
        display.setBounds(displayBounds);
        display.getConfiguration().densityDpi = DENSITY_DEFAULT;
        display.getConfiguration().orientation = ORIENTATION_LANDSCAPE;
        configInsetsState(display.getInsetsStateController().getRawInsetsState(), display,
                displayStableBounds);
        return display;
    }

    /**
     * Creates insets sources so that we can get the expected stable frame.
     */
    static void configInsetsState(InsetsState state, DisplayContent display,
            Rect stableFrame) {
        final Rect displayFrame = display.getBounds();
        final int dl = displayFrame.left;
        final int dt = displayFrame.top;
        final int dr = displayFrame.right;
        final int db = displayFrame.bottom;
        final int sl = stableFrame.left;
        final int st = stableFrame.top;
        final int sr = stableFrame.right;
        final int sb = stableFrame.bottom;
        final @WindowInsets.Type.InsetsType int statusBarType = WindowInsets.Type.statusBars();
        final @WindowInsets.Type.InsetsType int navBarType = WindowInsets.Type.navigationBars();

        state.setDisplayFrame(displayFrame);
        if (sl > dl) {
            state.getOrCreateSource(InsetsSource.createId(null, 0, statusBarType), statusBarType)
                    .setFrame(dl, dt, sl, db);
        }
        if (st > dt) {
            state.getOrCreateSource(InsetsSource.createId(null, 1, statusBarType), statusBarType)
                    .setFrame(dl, dt, dr, st);
        }
        if (sr < dr) {
            state.getOrCreateSource(InsetsSource.createId(null, 0, navBarType), navBarType)
                    .setFrame(sr, dt, dr, db);
        }
        if (sb < db) {
            state.getOrCreateSource(InsetsSource.createId(null, 1, navBarType), navBarType)
                    .setFrame(dl, sb, dr, db);
        }
        // Recompute config and push to children.
        display.onRequestedOverrideConfigurationChanged(display.getConfiguration());
    }

    class CalculateRequestBuilder {
        private Task mTask;
        private ActivityInfo.WindowLayout mLayout;
        private ActivityRecord mActivity = LaunchParamsModifierTestsBase.this.mActivity;
        private ActivityRecord mSource;
        private ActivityOptions mOptions;
        private ActivityStarter.Request mRequest;
        private int mPhase = PHASE_BOUNDS;
        private LaunchParams mCurrentParams = mCurrent;
        private LaunchParams mOutParams = mResult;

        CalculateRequestBuilder setTask(Task task) {
            mTask = task;
            return this;
        }

        CalculateRequestBuilder setLayout(ActivityInfo.WindowLayout layout) {
            mLayout = layout;
            return this;
        }

        CalculateRequestBuilder setPhase(int phase) {
            mPhase = phase;
            return this;
        }

        CalculateRequestBuilder setActivity(ActivityRecord activity) {
            mActivity = activity;
            return this;
        }

        CalculateRequestBuilder setSource(ActivityRecord source) {
            mSource = source;
            return this;
        }

        CalculateRequestBuilder setOptions(ActivityOptions options) {
            mOptions = options;
            return this;
        }

        CalculateRequestBuilder setRequest(ActivityStarter.Request request) {
            mRequest = request;
            return this;
        }

        @LaunchParamsController.LaunchParamsModifier.Result int calculate() {
            return mTarget.onCalculate(mTask, mLayout, mActivity, mSource, mOptions, mRequest,
                    mPhase, mCurrentParams, mOutParams);
        }
    }

    static class WindowLayoutBuilder {
        private int mWidth = -1;
        private int mHeight = -1;
        private float mWidthFraction = -1f;
        private float mHeightFraction = -1f;
        private int mGravity = Gravity.NO_GRAVITY;
        private int mMinWidth = -1;
        private int mMinHeight = -1;

        WindowLayoutBuilder setWidth(int width) {
            mWidth = width;
            return this;
        }

        WindowLayoutBuilder setHeight(int height) {
            mHeight = height;
            return this;
        }

        WindowLayoutBuilder setWidthFraction(float widthFraction) {
            mWidthFraction = widthFraction;
            return this;
        }

        WindowLayoutBuilder setHeightFraction(float heightFraction) {
            mHeightFraction = heightFraction;
            return this;
        }

        WindowLayoutBuilder setGravity(int gravity) {
            mGravity = gravity;
            return this;
        }

        WindowLayoutBuilder setMinWidth(int minWidth) {
            mMinWidth = minWidth;
            return this;
        }

        WindowLayoutBuilder setMinHeight(int minHeight) {
            mMinHeight = minHeight;
            return this;
        }

        ActivityInfo.WindowLayout build() {
            return new ActivityInfo.WindowLayout(mWidth, mWidthFraction, mHeight, mHeightFraction,
                    mGravity, mMinWidth, mMinHeight);
        }
    }
}
